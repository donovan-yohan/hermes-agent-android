package com.hermesagent.mobile.plugins.groups

import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupsAuthorityTest {
    private class Gateway {
        var identity = "gateway-a"
        var owner = "gateway-b"
        var failState = false
        var events = emptyList<JsonElement>()
        var log: (suspend (Long) -> JsonElement)? = null
        val methods = mutableListOf<String>()
        fun connection() = GroupReadConnection(GroupsRepository({ method, params ->
            methods += method
            if (method == "groups.state" && failState) PluginHostResult.Refused(4114, "Read refused")
            else PluginHostResult.Success(when (method) {
                "groups.capabilities" -> JsonObject(capabilityWire.jsonObject + ("authority_gateway_id" to JsonPrimitive(identity)))
                "groups.list" -> buildJsonObject { put("rooms", JsonArray(listOf(room()))); put("next_offset", JsonNull) }
                "groups.state" -> buildJsonObject { put("room", room()) }
                "groups.log" -> {
                    val since = params.getValue("since_seq").jsonPrimitive.long
                    log?.invoke(since) ?: page(events.drop(since.toInt()), events.size.toLong(), owner)
                }
                else -> error("Unexpected read or mutation: $method")
            })
        }), { true })
        private fun room() = JsonObject(roomWire(latest = events.size.toLong()).jsonObject +
            ("authority_gateway_id" to JsonPrimitive(owner)))
    }

    @Test fun freshRemoteOwnerNeedsBannerWithoutAnyAuthorityEvents() = runTest {
        val gateway = Gateway()
        val vm = GroupsViewModel(backgroundScope, MutableStateFlow(gateway.connection()), MutableStateFlow(0L))
        vm.setForeground(true); runCurrent(); vm.open("room"); runCurrent()
        assertEquals(GroupsPhase.Ready, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.transcript!!.authorityLost)
        assertEquals("gateway-b", vm.uiState.value.transcript!!.state.room.authority)
    }

    @Test fun lostThenClaimToOtherStaysRemoteAndClaimBackBecomesLocal() = runTest {
        val gateway = Gateway().apply {
            events = listOf(authorityEvent(1, "authority.lost", "gateway-b"))
        }
        val vm = GroupsViewModel(backgroundScope, MutableStateFlow(gateway.connection()), MutableStateFlow(0L))
        vm.setForeground(true); runCurrent(); vm.open("room"); runCurrent()
        assertTrue(vm.uiState.value.transcript!!.authorityLost)
        gateway.owner = "gateway-c"
        gateway.events += authorityEvent(2, "authority.claimed", "gateway-c")
        vm.refresh(); runCurrent()
        assertTrue("A claim to C is not a return to connected A", vm.uiState.value.transcript!!.authorityLost)
        gateway.owner = "gateway-a"
        gateway.events += authorityEvent(3, "authority.claimed", "gateway-a")
        vm.refresh(); runCurrent()
        assertFalse(vm.uiState.value.transcript!!.authorityLost)
        assertEquals("gateway-a", vm.uiState.value.transcript!!.state.room.authority)
        assertTrue(gateway.methods.all { it in setOf("groups.capabilities", "groups.list", "groups.state", "groups.log") })
    }

    @Test fun latestLogOwnerWinsOverStateAndHistoricalClaimsDuringCatchup() = runTest {
        val gateway = Gateway().apply {
            owner = "gateway-a"
            log = { since -> when (since) {
                0L -> page(listOf(authorityEvent(1, "authority.claimed", "gateway-a")), 1, "gateway-b", latest = 2)
                else -> page(listOf(authorityEvent(2, "authority.claimed", "gateway-b")), 2, "gateway-c")
            } }
        }
        val vm = GroupsViewModel(backgroundScope, MutableStateFlow(gateway.connection()), MutableStateFlow(0L))
        vm.setForeground(true); runCurrent(); vm.open("room"); runCurrent()
        assertEquals(2L, vm.uiState.value.transcript!!.cursor)
        assertEquals("gateway-c", vm.uiState.value.transcript!!.state.room.authority)
        assertTrue(vm.uiState.value.transcript!!.authorityLost)
        gateway.log = { since -> page(emptyList(), since, "gateway-a") }
        // State still has an older replay cursor; keep it at the retained cursor for an empty delta.
        gateway.events = listOf(eventWire(1), eventWire(2))
        vm.refresh(); runCurrent()
        assertEquals("gateway-a", vm.uiState.value.transcript!!.state.room.authority)
        assertFalse(vm.uiState.value.transcript!!.authorityLost)
    }

    @Test fun reconnectRebindsCachedOwnerToFreshCapabilityIdentityEvenWhenStateFails() = runTest {
        val gateway = Gateway().apply { events = listOf(eventWire()) }
        val legs = MutableStateFlow<GroupReadConnection?>(gateway.connection())
        val vm = GroupsViewModel(backgroundScope, legs, MutableStateFlow(0L))
        vm.setForeground(true); runCurrent(); vm.open("room"); runCurrent()
        assertTrue(vm.uiState.value.transcript!!.authorityLost)
        legs.value = gateway.connection(); runCurrent()
        assertTrue(vm.uiState.value.transcript!!.authorityLost)
        assertEquals(1L, vm.uiState.value.transcript!!.cursor)
        gateway.identity = "gateway-b"
        gateway.failState = true
        legs.value = gateway.connection(); runCurrent()
        assertTrue(vm.uiState.value.stale)
        assertFalse("Retained owner B is local on freshly identified B", vm.uiState.value.transcript!!.authorityLost)
        gateway.identity = "gateway-a"
        legs.value = gateway.connection(); runCurrent()
        assertTrue(vm.uiState.value.transcript!!.authorityLost)
    }

    @Test fun failedCatchupKeepsKnownOwnerUntilACompleteValidatedReplacement() = runTest {
        val gateway = Gateway().apply { events = listOf(eventWire()) }
        val vm = GroupsViewModel(backgroundScope, MutableStateFlow(gateway.connection()), MutableStateFlow(0L))
        vm.setForeground(true); runCurrent(); vm.open("room"); runCurrent()
        val lastPage = CompletableDeferred<JsonElement>()
        gateway.log = { since -> if (since == 1L)
            page(listOf(authorityEvent(2, "authority.claimed", "gateway-a")), 2, "gateway-a", latest = 3)
            else lastPage.await()
        }
        vm.refresh(); runCurrent()
        assertEquals("gateway-b", vm.uiState.value.transcript!!.state.room.authority)
        assertTrue(vm.uiState.value.transcript!!.authorityLost)
        lastPage.complete(JsonNull); runCurrent()
        assertTrue(vm.uiState.value.stale)
        assertEquals("gateway-b", vm.uiState.value.transcript!!.state.room.authority)
        gateway.events += listOf(eventWire(2), eventWire(3))
        gateway.log = { since -> page(gateway.events.drop(since.toInt()), 3, "gateway-a") }
        vm.refresh(); runCurrent()
        assertEquals("gateway-a", vm.uiState.value.transcript!!.state.room.authority)
        assertFalse(vm.uiState.value.transcript!!.authorityLost)
    }

    @Test fun endpointChangeDropsOwnerAndLateOldCatchupCannotReplaceNewOwnership() = runTest {
        val old = Gateway()
        val legs = MutableStateFlow<GroupReadConnection?>(old.connection())
        val endpoint = MutableStateFlow(0L)
        val vm = GroupsViewModel(backgroundScope, legs, endpoint)
        vm.setForeground(true); runCurrent(); vm.open("room"); runCurrent()
        val answer = CompletableDeferred<JsonElement>()
        old.log = { withContext(NonCancellable) { answer.await() } }
        vm.refresh(); runCurrent()
        val replacement = Gateway().apply { identity = "gateway-b"; owner = "gateway-b" }
        endpoint.value = 1; legs.value = replacement.connection(); runCurrent()
        assertNull(vm.uiState.value.transcript)
        assertNull(vm.uiState.value.selected)
        answer.complete(page(emptyList(), 0, "gateway-c")); runCurrent()
        vm.open("room"); runCurrent()
        assertEquals("gateway-b", vm.uiState.value.transcript!!.state.room.authority)
        assertFalse(vm.uiState.value.transcript!!.authorityLost)
    }

    private companion object {
        fun authorityEvent(seq: Long, kind: String, owner: String) = eventWire(seq, kind,
            """{"previous_gateway_id":"gateway-a","authority_gateway_id":"$owner","authority_epoch":$seq}""")
        fun page(events: List<JsonElement>, cursor: Long, owner: String, latest: Long = cursor) =
            JsonObject(logWire(events, cursor, latest).jsonObject + ("authority" to buildJsonObject {
                put("gateway_id", owner); put("epoch", 3)
            }))
    }
}
