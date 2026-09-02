package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.SessionCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The `approvals.mode` leg: the two RPC shapes, the optimistic write and its
 * rollback, and which streamed `session.info` may reconcile the value.
 *
 * Every contract claim here is against `hermes-agent` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalModeRepositoryTest {

    @Test
    fun `unknown wire values fall back to manual, never to a permissive mode`() {
        assertEquals(ApprovalMode.Manual, ApprovalMode.fromWire(null))
        assertEquals(ApprovalMode.Manual, ApprovalMode.fromWire(""))
        assertEquals(ApprovalMode.Manual, ApprovalMode.fromWire("yolo"))
        assertEquals(ApprovalMode.Manual, ApprovalMode.fromWire("  MANUAL "))
        assertEquals(ApprovalMode.Smart, ApprovalMode.fromWire("Smart"))
        assertEquals(ApprovalMode.Off, ApprovalMode.fromWire("off"))
        // The order the menu renders, fixed by `approval-mode-menu.tsx:62`.
        assertEquals(
            listOf(ApprovalMode.Manual, ApprovalMode.Smart, ApprovalMode.Off),
            ApprovalMode.MENU_ORDER,
        )
    }

    @Test
    fun `config_get carries the key and the active profile`() = runTest {
        val rpc = FakeRpc()
        val repository = repository(rpc)
        runCurrent()
        rpc.approvalMode = "smart"
        repository.setProfileRouting(ProfileRouting(activeProfile = "research"))

        // Nothing is known before the read, so the control shows nothing.
        assertNull(repository.approvalMode.value.mode)

        repository.refreshApprovalMode()

        val call = rpc.lastCall("config.get")
        assertNotNull(call)
        assertEquals("approvals.mode", call!!.params.string("key"))
        // Desktop sends no profile (`store/approval-mode.ts:56`) and therefore
        // always reads the launch profile; the handler is `@_profile_scoped`
        // (`methods_config.py:181-182`), so this app names its own scope.
        assertEquals("research", call.params.string("profile"))
        assertEquals(ApprovalMode.Smart, repository.approvalMode.value.mode)
    }

    @Test
    fun `the default scope omits the profile exactly as every other session rpc does`() = runTest {
        val rpc = FakeRpc()
        val repository = repository(rpc)
        runCurrent()

        repository.refreshApprovalMode()

        assertNull(rpc.lastCall("config.get")!!.params.string("profile"))
    }

    @Test
    fun `config_set writes the mode and the gateway's echo confirms it`() = runTest {
        val rpc = FakeRpc()
        val repository = repository(rpc)
        runCurrent()
        repository.setProfileRouting(ProfileRouting(activeProfile = "research"))

        assertEquals(ApprovalModeOutcome.Applied, repository.setApprovalMode(ApprovalMode.Off))

        val call = rpc.lastCall("config.set")
        assertNotNull(call)
        assertEquals("approvals.mode", call!!.params.string("key"))
        assertEquals("off", call.params.string("value"))
        assertEquals("research", call.params.string("profile"))
        assertEquals(ApprovalMode.Off, repository.approvalMode.value.mode)
    }

    @Test
    fun `the chosen mode paints before the gateway answers`() = runTest {
        val rpc = FakeRpc()
        val repository = repository(rpc)
        runCurrent()
        rpc.approvalMode = "manual"
        repository.refreshApprovalMode()
        assertEquals(ApprovalMode.Manual, repository.approvalMode.value.mode)

        val gate = CompletableDeferred<Unit>()
        rpc.configSetGate = gate
        val write = launch { repository.setApprovalMode(ApprovalMode.Smart) }
        runCurrent()

        // Optimistic, the way `setApprovalModeForProfile` is
        // (`store/approval-mode.ts:74`): the control does not wait on the host.
        assertEquals(ApprovalMode.Smart, repository.approvalMode.value.mode)
        gate.complete(Unit)
        write.join()
        assertEquals(ApprovalMode.Smart, repository.approvalMode.value.mode)
    }

    @Test
    fun `a refused mode rolls back to the last confirmed one`() = runTest {
        val rpc = FakeRpc()
        val repository = repository(rpc)
        runCurrent()
        rpc.approvalMode = "manual"
        repository.refreshApprovalMode()

        // `_err(rid, 4002, "unknown approval mode: …")` (`server.py:14587-14591`).
        rpc.configSetError = GatewayRpcError(4002, "unknown approval mode")
        val outcome = repository.setApprovalMode(ApprovalMode.Off)

        assertTrue(outcome is ApprovalModeOutcome.Rejected)
        assertEquals(APPROVAL_MODE_REJECTED, (outcome as ApprovalModeOutcome.Rejected).safeMessage)
        assertEquals(ApprovalMode.Manual, repository.approvalMode.value.mode)
    }

    @Test
    fun `a transport failure rolls back too, and an unread mode rolls back to unknown`() = runTest {
        val rpc = FakeRpc()
        val repository = repository(rpc)
        runCurrent()

        rpc.configSetError = GatewayRpcException("the socket closed")
        val outcome = repository.setApprovalMode(ApprovalMode.Off)

        assertTrue(outcome is ApprovalModeOutcome.Rejected)
        // Nothing was ever confirmed, so the control goes back to showing
        // nothing rather than inventing a mode it was never told.
        assertNull(repository.approvalMode.value.mode)
    }

    @Test
    fun `a failed read keeps the last confirmed mode`() = runTest {
        val rpc = FakeRpc()
        val repository = repository(rpc)
        runCurrent()
        rpc.approvalMode = "off"
        repository.refreshApprovalMode()
        assertEquals(ApprovalMode.Off, repository.approvalMode.value.mode)

        rpc.configGetError = GatewayRpcException("the socket closed")
        repository.refreshApprovalMode()

        assertEquals(ApprovalMode.Off, repository.approvalMode.value.mode)
    }

    @Test
    fun `a streamed session_info reconciles the mode and the effective bypass`() = runTest {
        val rpc = FakeRpc()
        val repository = repository(rpc)
        runCurrent()
        repository.openSession("session-1")
        runCurrent()

        // `config.set` re-emits `session.info` for every live session
        // (`server.py:14594-14597`), and the info carries both fields
        // (`server.py:7659-7660`).
        rpc.emit(
            "session.info",
            "runtime-1",
            """{"stored_session_id":"session-1","running":false,"approval_mode":"off","yolo":true}""",
        )
        runCurrent()

        assertEquals(ApprovalMode.Off, repository.approvalMode.value.mode)
        assertTrue(repository.approvalMode.value.bypassActive)
    }

    @Test
    fun `a named scope ignores a session_info that reports the launch profile`() = runTest {
        val rpc = FakeRpc()
        val repository = repository(rpc)
        runCurrent()
        rpc.approvalMode = "off"
        repository.setProfileRouting(ProfileRouting(activeProfile = "research"))
        repository.refreshApprovalMode()
        repository.openSession("session-1")
        runCurrent()

        // `_session_info` resolves `approvals.mode` under whichever HERMES_HOME
        // is bound when it is emitted (`server.py:5953-5971`), and a turn's own
        // emits carry no profile binding at all. Accepting one here would paint
        // the launch profile's posture under the research profile's name.
        rpc.emit(
            "session.info",
            "runtime-1",
            """{"stored_session_id":"session-1","running":false,"approval_mode":"manual","yolo":false}""",
        )
        runCurrent()

        assertEquals(ApprovalMode.Off, repository.approvalMode.value.mode)
    }

    @Test
    fun `a profile switch shows nothing until the new scope answers`() = runTest {
        val rpc = FakeRpc()
        val repository = repository(rpc)
        runCurrent()
        rpc.approvalMode = "manual"
        repository.setProfileRouting(ProfileRouting(activeProfile = "alpha"))
        repository.refreshApprovalMode()
        assertEquals(ApprovalMode.Manual, repository.approvalMode.value.mode)

        // `approvals.mode` is per profile (`methods_config.py:181-182`), so
        // alpha's answer is not beta's. Between the switch and beta's own read
        // the chip must name nothing rather than the profile just left — the
        // dangerous direction is real: alpha `manual`, beta `off`.
        rpc.approvalMode = "off"
        repository.setProfileRouting(ProfileRouting(activeProfile = "beta"))
        assertNull(repository.approvalMode.value.mode)

        repository.refreshApprovalMode()
        assertEquals("beta", rpc.lastCall("config.get")!!.params.string("profile"))
        assertEquals(ApprovalMode.Off, repository.approvalMode.value.mode)
    }

    @Test
    fun `a profile switch whose read fails leaves the chip hidden, never the old posture`() = runTest {
        val rpc = FakeRpc()
        val repository = repository(rpc)
        runCurrent()
        rpc.approvalMode = "manual"
        repository.setProfileRouting(ProfileRouting(activeProfile = "alpha"))
        repository.refreshApprovalMode()
        assertEquals(ApprovalMode.Manual, repository.approvalMode.value.mode)

        rpc.configGetError = GatewayRpcException("the socket closed")
        repository.setProfileRouting(ProfileRouting(activeProfile = "beta"))
        repository.refreshApprovalMode()

        // The read is silent, and silence here means "not known" — the previous
        // profile's answer must not survive as this one's.
        assertNull(repository.approvalMode.value.mode)

        // A routing that does not move the scope is not a change of subject.
        rpc.configGetError = null
        rpc.approvalMode = "smart"
        repository.refreshApprovalMode()
        assertEquals(ApprovalMode.Smart, repository.approvalMode.value.mode)
        repository.setProfileRouting(ProfileRouting(activeProfile = "beta", listProfiles = listOf("beta")))
        assertEquals(ApprovalMode.Smart, repository.approvalMode.value.mode)
    }

    @Test
    fun `a session_info racing a profile switch cannot repaint the profile just left`() = runTest {
        val rpc = FakeRpc()
        val repository = repository(rpc)
        runCurrent()

        // A launch-profile `session.info` is applied on the event pump while the
        // rail's switch lands on another thread. The scope test and the publish
        // have to be one critical section: read under its own acquisition, the
        // gate can see the launch profile, the switch can land, and the publish
        // then paints the new profile's chip with the old one's posture — the
        // answer `setProfileRouting` had just dropped, and one a failed scoped
        // `config.get` would leave standing. Raw threads because a single test
        // dispatcher cannot interleave two of them inside one function.
        val info = Json.parseToJsonElement(
            """{"stored_session_id":"session-1","running":false,"approval_mode":"off"}""",
        ).jsonObject
        val launchScope = ProfileRouting()
        val namedScope = ProfileRouting(activeProfile = "beta")
        val stop = AtomicBoolean(false)
        val pump = thread(name = "session-info-pump") {
            while (!stop.get()) repository.applyStreamedApprovalMode(info)
        }

        var repaintedOnRound: Int? = null
        try {
            repeat(RACE_ROUNDS) { round ->
                repository.setProfileRouting(launchScope)
                Thread.yield()
                repository.setProfileRouting(namedScope)
                // Beta is on the rail before this line: nothing the pump is
                // holding may reach the chip any more.
                repeat(SETTLE_READS) {
                    if (repaintedOnRound == null && repository.approvalMode.value.mode != null) {
                        repaintedOnRound = round
                    }
                }
            }
        } finally {
            stop.set(true)
            pump.join()
        }

        assertNull(
            "a launch-profile session.info repainted the switched-to profile on round $repaintedOnRound",
            repaintedOnRound,
        )
    }

    @Test
    fun `an accepted write whose echo carries no value keeps the mode that was written`() = runTest {
        val rpc = FakeRpc()
        val repository = repository(rpc)
        runCurrent()
        rpc.approvalMode = "manual"
        repository.refreshApprovalMode()

        // `server.py:14598` always echoes `value` at this pin. If it ever did
        // not, resolving the missing field through `fromWire` would confirm
        // Manual and silently revert a write the host accepted.
        rpc.configSetEchoesValue = false
        assertEquals(ApprovalModeOutcome.Applied, repository.setApprovalMode(ApprovalMode.Off))

        assertEquals(ApprovalMode.Off, repository.approvalMode.value.mode)
    }

    @Test
    fun `an endpoint switch forgets everything this host said about approvals`() = runTest {
        val first = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(first)
        val repository = LiveGatewaySessionRepository(
            SessionCache(),
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            clients,
            backgroundScope,
            restContext = EmptyCoroutineContext,
        ) { 1_000L }
        runCurrent()
        first.approvalMode = "off"
        repository.refreshApprovalMode()
        assertEquals(ApprovalMode.Off, repository.approvalMode.value.mode)

        // The next backend is a different machine with its own approvals config.
        clients.value = FakeRpc()
        runCurrent()

        assertNull(repository.approvalMode.value.mode)
        assertEquals(false, repository.approvalMode.value.bypassActive)
    }

    /**
     * Enough switches to land one inside the window a second lock acquisition
     * would open; the assertion itself holds on every round of a correct build.
     */
    private companion object {
        const val RACE_ROUNDS = 2_000
        const val SETTLE_READS = 64
    }

    private fun kotlinx.coroutines.test.TestScope.repository(rpc: FakeRpc) = LiveGatewaySessionRepository(
        SessionCache(),
        MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
        MutableStateFlow<GatewayRpcClient?>(rpc),
        backgroundScope,
        restContext = EmptyCoroutineContext,
    ) { 1_000L }

    private class FakeRpc : GatewayRpcClient {
        private val eventChannel = Channel<GatewayEvent>(capacity = 1024)
        override val events: Flow<GatewayEvent> = eventChannel.receiveAsFlow()

        var approvalMode: String = "manual"
        var configGetError: Throwable? = null
        var configSetError: Throwable? = null
        var configSetGate: CompletableDeferred<Unit>? = null
        var configSetEchoesValue: Boolean = true

        private val calls = mutableListOf<RpcCall>()

        data class RpcCall(val method: String, val params: JsonObject)

        fun lastCall(method: String): RpcCall? = calls.lastOrNull { it.method == method }

        fun emit(type: String, runtimeId: String?, payload: String) {
            eventChannel.trySend(GatewayEvent(type, runtimeId, json(payload)))
        }

        private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

        override suspend fun request(method: String, params: JsonObject): JsonElement {
            calls += RpcCall(method, params)
            return when (method) {
                "config.get" -> {
                    configGetError?.let { throw it }
                    Json.parseToJsonElement("""{"value":"$approvalMode"}""")
                }

                "config.set" -> {
                    configSetGate?.await()
                    configSetError?.let { throw it }
                    val value = params.string("value").orEmpty()
                    approvalMode = value
                    if (configSetEchoesValue) {
                        Json.parseToJsonElement("""{"key":"approvals.mode","value":"$value"}""")
                    } else {
                        Json.parseToJsonElement("""{"key":"approvals.mode"}""")
                    }
                }

                "session.resume" -> Json.parseToJsonElement(
                    """{"session_id":"runtime-1","resumed":"session-1","message_count":0,"messages":[],"info":{"model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true},"inflight":null,"running":false,"session_key":"session-1","started_at":1700001000.125,"status":"idle"}""",
                )

                "session.history" -> Json.parseToJsonElement("""{"messages":[],"count":0}""")
                else -> Json.parseToJsonElement("{}")
            }
        }

        override fun close() {}
    }
}
