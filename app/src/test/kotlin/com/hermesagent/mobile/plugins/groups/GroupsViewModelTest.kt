package com.hermesagent.mobile.plugins.groups

import com.hermesagent.mobile.plugins.PluginHostResult
import com.hermesagent.mobile.plugins.PluginRefusalReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupsViewModelTest {
    private class Wire {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var latest = 1L
        var failure: PluginHostResult? = null
        var working = false
        var answer: (suspend (String, JsonObject) -> PluginHostResult)? = null
        fun connection(): GroupReadConnection = GroupReadConnection(GroupsRepository({ method, params ->
            calls += method to params
            answer?.invoke(method, params) ?: when (method) {
                "groups.capabilities" -> PluginHostResult.Success(capabilityWire)
                "groups.list" -> PluginHostResult.Success(wire("""{"rooms":[${roomWire(latest = latest)}],"next_offset":null}"""))
                "groups.state" -> failure ?: PluginHostResult.Success(wire("""{"room":${roomWire(latest = latest)},
                    "driver_status":{"running":true,"working":$working,"blocked":false,"counts":{},"pending_actions":[],"peer_routes":[]}}"""))
                "groups.log" -> failure ?: PluginHostResult.Success(logWire(
                    ((params.getValue("since_seq").jsonPrimitive.long + 1)..latest).map { eventWire(it) }, latest))
                else -> error(method)
            }
        }), { true })
    }

    @Test fun unsupportedCapabilityIsRememberedAcrossResumeButOtherRefusalsAreErrors() = runTest {
        for (result in listOf(PluginHostResult.UnavailableOnGateway, PluginHostResult.Refused(4000, "secret"))) {
            var calls = 0
            val connection = GroupReadConnection(GroupsRepository({ _, _ -> calls++; result }), { true })
            val vm = GroupsViewModel(backgroundScope, MutableStateFlow(connection), MutableStateFlow(0L))
            runCurrent()
            assertEquals(if (result == PluginHostResult.UnavailableOnGateway) GroupsPhase.Unsupported else GroupsPhase.Failure, vm.uiState.value.phase)
            vm.setForeground(true); runCurrent()
            if (result == PluginHostResult.UnavailableOnGateway) assertEquals(1, calls)
        }
    }

    @Test fun reconnectRetainsCursorAndChecksCapabilityEvenWithoutBooleanEdge() = runTest {
        val wire = Wire()
        val legs = MutableStateFlow<GroupReadConnection?>(wire.connection())
        val vm = GroupsViewModel(backgroundScope, legs, MutableStateFlow(0))
        vm.setForeground(true); runCurrent(); vm.open("room"); runCurrent()
        assertEquals(1L, vm.uiState.value.transcript!!.cursor)
        wire.latest = 2
        legs.value = wire.connection(); runCurrent()
        assertEquals(2, wire.calls.count { it.first == "groups.capabilities" })
        assertEquals(2L, vm.uiState.value.transcript!!.cursor)
        assertEquals(1L, wire.calls.last { it.first == "groups.log" }.second.getValue("since_seq").jsonPrimitive.long)
        val last = wire.calls.takeLast(3).map { it.first }
        assertEquals(listOf("groups.capabilities", "groups.state", "groups.log"), last)
    }

    @Test fun cadenceStopsInBackgroundAndCatchesUpOnReturn() = runTest {
        val wire = Wire().apply { working = true }
        val vm = GroupsViewModel(backgroundScope, MutableStateFlow(wire.connection()), MutableStateFlow(0), 100, 1000)
        vm.setForeground(true); runCurrent(); vm.open("room"); runCurrent()
        val initial = wire.calls.size
        advanceTimeBy(99); runCurrent(); assertEquals(initial, wire.calls.size)
        advanceTimeBy(1); runCurrent(); assertEquals(initial + 2, wire.calls.size)
        vm.setForeground(false); runCurrent()
        val stopped = wire.calls.size
        advanceTimeBy(10_000); runCurrent(); assertEquals(stopped, wire.calls.size)
        wire.working = false
        vm.setForeground(true); runCurrent(); assertEquals(stopped + 2, wire.calls.size)
        advanceTimeBy(999); runCurrent(); assertEquals(stopped + 2, wire.calls.size)
        advanceTimeBy(1); runCurrent(); assertEquals(stopped + 4, wire.calls.size)
    }

    @Test fun reasonlessFailureNeverResetsButFreshStateAheadEvidenceDoes() = runTest {
        val wire = Wire().apply { latest = 2 }
        val vm = GroupsViewModel(backgroundScope, MutableStateFlow(wire.connection()), MutableStateFlow(0))
        vm.setForeground(true); runCurrent(); vm.open("room"); runCurrent()
        wire.failure = PluginHostResult.Refused(4112, "not uniquely cursor ahead")
        vm.refresh(); runCurrent()
        assertEquals(2L, vm.uiState.value.transcript!!.cursor)
        assertTrue(vm.uiState.value.stale)
        val stopped = wire.calls.size
        advanceTimeBy(100_000); runCurrent(); assertEquals(stopped, wire.calls.size)
        wire.failure = null; wire.latest = 1
        vm.refresh(); runCurrent()
        assertEquals(0L, wire.calls.last().second.getValue("since_seq").jsonPrimitive.long)
        assertEquals(1L, vm.uiState.value.transcript!!.cursor)
    }

    @Test fun expiredHistoryIsTombstonedAndAuthorityRefusalIsSafe() = runTest {
        val wire = Wire()
        val vm = GroupsViewModel(backgroundScope, MutableStateFlow(wire.connection()), MutableStateFlow(0))
        vm.setForeground(true); runCurrent(); vm.open("room"); runCurrent()
        wire.failure = PluginHostResult.Refused(4114, "secret", PluginRefusalReason.AuthorityConflict)
        vm.refresh(); runCurrent()
        assertTrue(vm.uiState.value.authorityConflict)
        assertEquals(1L, vm.uiState.value.transcript!!.cursor)
        wire.failure = PluginHostResult.Refused(4114, "secret", PluginRefusalReason.RoomHistoryExpired)
        vm.refresh(); runCurrent()
        assertTrue(vm.uiState.value.expired); assertNull(vm.uiState.value.transcript)
        wire.failure = null
        vm.closeRoom(); runCurrent()
        assertTrue(vm.uiState.value.rooms.isEmpty())
        vm.open("room"); runCurrent(); assertNull(vm.uiState.value.selected)
    }

    @Test fun endpointSwitchDropsCacheAndLateNonCancellableStateCannotPaint() = runTest {
        val wire = Wire()
        val endpoint = MutableStateFlow(0L)
        val legs = MutableStateFlow<GroupReadConnection?>(wire.connection())
        val vm = GroupsViewModel(backgroundScope, legs, endpoint)
        vm.setForeground(true); runCurrent()
        val answer = CompletableDeferred<PluginHostResult>()
        wire.answer = { method, _ ->
            if (method == "groups.state") withContext(NonCancellable) { answer.await() }
            else PluginHostResult.Refused(0, "refused")
        }
        vm.open("room"); runCurrent()
        endpoint.value = 1; legs.value = null; runCurrent()
        assertNull(vm.uiState.value.selected)
        assertTrue(vm.uiState.value.rooms.isEmpty())
        answer.complete(PluginHostResult.Success(wire("""{"room":${roomWire(latest = 1)}}""")))
        runCurrent()
        assertNull(vm.uiState.value.selected)
        assertNull(vm.uiState.value.transcript)
        assertTrue(vm.uiState.value.rooms.isEmpty())
        assertFalse(wire.calls.any { it.first == "groups.log" })
    }

    @Test fun disbandEventIsTerminalEvenWhenStateReadPrecededIt() = runTest {
        val wire = Wire()
        val vm = GroupsViewModel(backgroundScope, MutableStateFlow(wire.connection()), MutableStateFlow(0L))
        vm.setForeground(true); runCurrent()
        wire.answer = { method, _ -> PluginHostResult.Success(when (method) {
            "groups.state" -> wire("""{"room":${roomWire(latest = 0)}}""")
            "groups.log" -> logWire(listOf(eventWire(kind = "room.disbanded", payload = """{"room_id":"room"}""")), 1)
            else -> wire("""{"rooms":[${roomWire()}],"next_offset":null}""")
        }) }
        vm.open("room"); runCurrent()
        assertTrue(vm.uiState.value.transcript!!.state.room.disbanded)
        assertTrue(vm.uiState.value.rooms.isEmpty())
        vm.closeRoom(); runCurrent()
        assertTrue(vm.uiState.value.rooms.isEmpty())
    }

    @Test fun malformedCatchupPreservesEntireKnownGoodTranscript() = runTest {
        val wire = Wire()
        val vm = GroupsViewModel(backgroundScope, MutableStateFlow(wire.connection()), MutableStateFlow(0))
        vm.setForeground(true); runCurrent(); vm.open("room"); runCurrent()
        val held = vm.uiState.value.transcript
        wire.answer = { method, _ -> PluginHostResult.Success(
            if (method == "groups.state") wire("""{"room":${roomWire(latest = 3)}}""")
            else logWire(listOf(eventWire(2), JsonObject(eventWire(3).jsonObject - "actor")), 3)) }
        vm.refresh(); runCurrent()
        assertEquals(held, vm.uiState.value.transcript)
        assertTrue(vm.uiState.value.stale)
    }
}
