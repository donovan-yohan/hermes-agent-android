package com.hermesagent.mobile.plugins

import com.hermesagent.mobile.data.gateway.CorrelatedGatewayRpc
import com.hermesagent.mobile.data.gateway.EndpointDispatchFence
import com.hermesagent.mobile.data.gateway.EndpointDispatchingGatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayEvent
import com.hermesagent.mobile.data.gateway.GatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayRpcError
import com.hermesagent.mobile.data.gateway.GatewayRpcException
import com.hermesagent.mobile.data.gateway.GatewayRpcWire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host door: gateway JSON-RPC over the live connection, the event tap, and
 * the lifecycle rule that disabling a plugin stops its in-flight calls.
 *
 * Everything here runs on virtual time with injected timing — no test sleeps,
 * and no fake reaches a real socket.
 */
@OptIn(
    ExperimentalCoroutinesApi::class,
    ExperimentalForInheritanceCoroutinesApi::class,
    InternalCoroutinesApi::class,
)
class PluginHostTest {

    /**
     * Stands in for one connection leg's client. The real door is leg-agnostic
     * (the connection resolves a loopback session token or a remote bearer
     * before the client exists at all), so a "leg" here is only which client
     * the live slot currently holds.
     */
    private class FakeRpc(
        override val events: Flow<GatewayEvent> = emptyFlow(),
        private val answer: suspend (String, JsonObject) -> JsonElement = { _, _ -> JsonNull },
        private val beforeEndpointWire: suspend () -> Unit = {},
    ) : EndpointDispatchingGatewayRpcClient {
        var lastMethod: String? = null
        var lastParams: JsonObject? = null
        var calls = 0

        override suspend fun request(method: String, params: JsonObject): JsonElement {
            calls += 1
            lastMethod = method
            lastParams = params
            return answer(method, params)
        }

        override suspend fun requestAtEndpointDispatch(
            method: String,
            params: JsonObject,
            dispatch: (() -> Boolean) -> Boolean,
        ): JsonElement {
            // This is the production contract in miniature: the test may stop
            // immediately before the actual wire hand-off, but the mutation
            // counter moves only inside the host's dispatch gate.
            beforeEndpointWire()
            if (!dispatch({
                    calls += 1
                    lastMethod = method
                    lastParams = params
                    true
                })
            ) {
                throw GatewayRpcException("The endpoint dispatch lease is no longer current.")
            }
            return answer(method, params)
        }

        override fun close() {}
    }

    /** Switches the endpoint/client between the host's first fence read and client snapshot. */
    private class SwitchingEndpoint(
        private val state: MutableStateFlow<Long>,
        private val switch: () -> Unit,
    ) : StateFlow<Long> {
        private var reads = 0

        override val value: Long
            get() {
                val current = state.value
                if (reads++ == 0) switch()
                return current
            }

        override val replayCache: List<Long> get() = state.replayCache

        override suspend fun collect(collector: FlowCollector<Long>): Nothing = state.collect(collector)
    }

    /**
     * A dispatcher the test pumps by hand.
     *
     * "The scope has not run the exchange yet" is then a fact rather than a
     * race, which is what lets the switch below land in exactly the window the
     * dispatch gate exists to close: after the fence accepted the call and
     * before any frame reached a transport.
     */
    private class PausedDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        val pending: Boolean get() = queued.isNotEmpty()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued += block
        }

        /** Runs one queued dispatch on the caller's thread, like a worker waking up. */
        fun runNext(): Boolean {
            val next = queued.removeFirstOrNull() ?: return false
            next.run()
            return true
        }
    }

    private fun scopeFor(owner: CoroutineScope): CoroutineScope =
        CoroutineScope(owner.coroutineContext + SupervisorJob(owner.coroutineContext[Job]))

    @Test
    fun `request reaches whichever connection leg is live and returns its result`() = runTest {
        // A loopback (Local / Managed SSH) leg and a Remote bearer leg answer
        // differently; the door must carry the call on either.
        val localLeg = FakeRpc(answer = { _, _ -> buildJsonObject { put("leg", JsonPrimitive("local")) } })
        val remoteLeg = FakeRpc(answer = { _, _ -> buildJsonObject { put("leg", JsonPrimitive("remote")) } })
        val clients = MutableStateFlow<GatewayRpcClient?>(localLeg)
        val scope = scopeFor(this)
        val host = GatewayPluginHost(scope, clients)

        val onLocal = host.request("session.list", buildJsonObject { put("limit", JsonPrimitive(5)) })
        assertEquals("local", (onLocal as PluginHostResult.Success).result.jsonObject["leg"]?.jsonPrimitive?.content)
        assertEquals("session.list", localLeg.lastMethod)
        assertEquals("5", localLeg.lastParams?.get("limit")?.jsonPrimitive?.content)

        // The connection moves to the Remote leg; the door re-reads the slot.
        clients.value = remoteLeg
        val onRemote = host.request("session.list")
        assertEquals("remote", (onRemote as PluginHostResult.Success).result.jsonObject["leg"]?.jsonPrimitive?.content)
        assertEquals(1, remoteLeg.calls)

        scope.cancel()
    }

    @Test
    fun `an endpoint-bound request cannot follow a client switch between its fence and snapshot`() = runTest {
        val oldLeg = FakeRpc()
        val newLeg = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(oldLeg)
        val endpointState = MutableStateFlow(0L)
        val endpoint = SwitchingEndpoint(endpointState) {
            clients.value = newLeg
            endpointState.value = 1L
        }
        val scope = scopeFor(this)
        val host = GatewayPluginHost(scope, clients, endpoint)

        val result = host.requestAtEndpoint(0L, "session.create")

        assertEquals(PluginHostResult.Refused(0, "Reconnect to the Gateway and try again."), result)
        assertEquals("the old Gateway is not called after the switch", 0, oldLeg.calls)
        assertEquals("the replacement Gateway is never mutated by the stale operation", 0, newLeg.calls)
        scope.cancel()
    }

    @Test
    fun `a switch that lands while the dispatch is queued keeps both gateways untouched`() = runTest {
        // The fence accepted the call, and the exchange is still queued behind
        // the scope's dispatcher — the window the first check cannot cover,
        // because a whole switch can land in it. The dispatch gate re-reads the
        // door there, so the stale call is refused before any frame is sent.
        val oldLeg = FakeRpc()
        val newLeg = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(oldLeg)
        val endpointState = MutableStateFlow(0L)
        val paused = PausedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + paused)
        val host = GatewayPluginHost(scope, clients, endpointState)

        val outcome = async { host.requestAtEndpoint(0L, "session.create") }
        runCurrent()
        assertTrue("the exchange is queued behind the paused dispatcher", paused.pending)

        clients.value = newLeg
        endpointState.value = 1L
        assertTrue(paused.runNext())
        runCurrent()

        assertEquals(PluginHostResult.Refused(0, "Reconnect to the Gateway and try again."), outcome.await())
        assertEquals("the old Gateway is not called after the switch", 0, oldLeg.calls)
        assertEquals("the replacement Gateway is never mutated by the stale operation", 0, newLeg.calls)
        scope.cancel()
    }

    @Test
    fun `endpoint invalidation after final ownership validation but before wire send reaches neither gateway`() = runTest {
        // The host has already accepted the old endpoint when this latch opens.
        // The fake is stopped at its exact wire hand-off, not at a dispatcher
        // queue. A real switch invalidates the shared lease first; releasing
        // the old client afterwards must therefore admit no stale mutation.
        val beforeWire = CompletableDeferred<Unit>()
        val releaseWire = CompletableDeferred<Unit>()
        val oldLeg = FakeRpc(beforeEndpointWire = {
            beforeWire.complete(Unit)
            releaseWire.await()
        })
        val newLeg = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(oldLeg)
        val endpointState = MutableStateFlow(0L)
        val fence = EndpointDispatchFence()
        val scope = scopeFor(this)
        val host = GatewayPluginHost(scope, clients, endpointState, fence)

        val outcome = async { host.requestAtEndpoint(0L, "session.create") }
        beforeWire.await()

        // This is the order ConnectionSwitchController.leaveLocked uses: make
        // old leases ineligible, then withdraw/replace the route and publish
        // its new endpoint generation.
        fence.invalidate()
        clients.value = newLeg
        endpointState.value = 1L
        releaseWire.complete(Unit)
        runCurrent()

        assertEquals(PluginHostResult.Refused(0, "Reconnect to the Gateway and try again."), outcome.await())
        assertEquals("the old wire is never handed the stale mutation", 0, oldLeg.calls)
        assertEquals("the replacement wire is never used by an old lease", 0, newLeg.calls)
        scope.cancel()
    }

    @Test
    fun `a switch during the exchange rejects the stale answer`() = runTest {
        // The frame was already at the Gateway the app was on when the endpoint
        // moved, so the request itself cannot be undone — but its answer is not
        // this endpoint's truth any more, and the door refuses it rather than
        // letting a stale result be adopted as canonical.
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldLeg = FakeRpc(answer = { _, _ ->
            arrived.complete(Unit)
            release.await()
            buildJsonObject { put("stale", JsonPrimitive(true)) }
        })
        val newLeg = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(oldLeg)
        val endpointState = MutableStateFlow(0L)
        val scope = scopeFor(this)
        val host = GatewayPluginHost(scope, clients, endpointState)

        val outcome = async { host.requestAtEndpoint(0L, "session.title") }
        runCurrent()
        assertTrue("the frame reached the old Gateway before the switch", arrived.isCompleted)

        clients.value = newLeg
        endpointState.value = 1L
        release.complete(Unit)
        runCurrent()

        assertEquals(PluginHostResult.Refused(0, "Reconnect to the Gateway and try again."), outcome.await())
        assertEquals("the in-flight frame was the old Gateway's own", 1, oldLeg.calls)
        assertEquals("the replacement Gateway is never mutated by the stale operation", 0, newLeg.calls)
        scope.cancel()
    }

    @Test
    fun `request without a live connection refuses with the transport sentence`() = runTest {
        val clients = MutableStateFlow<GatewayRpcClient?>(null)
        val scope = scopeFor(this)
        val host = GatewayPluginHost(scope, clients)

        val refused = host.request("session.list")
        assertEquals(PluginHostResult.Refused(0, "Reconnect to the Gateway and try again."), refused)

        scope.cancel()
    }

    @Test
    fun `the door reports the live connection, and follows the slot in both directions`() = runTest {
        val clients = MutableStateFlow<GatewayRpcClient?>(null)
        val scope = scopeFor(this)
        val host = GatewayPluginHost(scope, clients)

        assertFalse(host.connected.value)

        clients.value = FakeRpc()
        advanceUntilIdle()
        assertTrue(host.connected.value)

        // The leg closing is the other edge a plugin recovers from.
        clients.value = null
        advanceUntilIdle()
        assertFalse(host.connected.value)

        scope.cancel()
    }

    @Test
    fun `a door with no live route reports a connection that never arrives`() = runTest {
        // Not "unknown": the honest answer for a context that has no route, so
        // a plugin that waits on an edge simply never receives one.
        assertFalse(UnavailablePluginHost.connected.value)

        val scope = scopeFor(this)
        val host: PluginHost = UnavailablePluginHost
        assertEquals(false, host.connected.value)
        scope.cancel()
    }

    @Test
    fun `namespace guarding refuses a malformed method locally, before any request`() = runTest {
        val rpc = FakeRpc()
        val scope = scopeFor(this)
        val host = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(rpc))

        for (method in listOf("../admin", "bot..relay", "a/b", "  ", "bot relay")) {
            val failure = runCatching { host.request(method) }.exceptionOrNull()
            assertTrue("expected refusal for \"$method\"", failure is IllegalArgumentException)
        }

        assertEquals("no malformed method may reach the wire", 0, rpc.calls)

        // The guard is a check, not a ban: a namespaced method still goes.
        host.request("bot_relay.deliver")
        assertEquals("bot_relay.deliver", rpc.lastMethod)

        scope.cancel()
    }

    @Test
    fun `an unknown method maps to UnavailableOnGateway, a remote error to Refused`() = runTest {
        val unknown = FakeRpc(answer = { method, _ -> throw GatewayRpcError(-32601, "unknown method: $method") })
        val scope = scopeFor(this)
        val host = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(unknown))

        assertEquals(PluginHostResult.UnavailableOnGateway, host.request("bot_relay.deliver"))

        // A Gateway that answers some other error is a refusal, and the text it
        // wrote never leaves this class.
        val failing = FakeRpc(answer = { _, _ -> throw GatewayRpcError(-32000, "backend said something secret") })
        val refusedHost = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(failing))
        val refused = refusedHost.request("session.list")
        assertEquals(PluginHostResult.Refused(-32000, "Hermes refused that Gateway request."), refused)

        val closed = FakeRpc(answer = { _, _ -> throw GatewayRpcException("The gateway connection is closed.") })
        val closedHost = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(closed))
        assertEquals(
            PluginHostResult.Refused(0, "Reconnect to the Gateway and try again."),
            closedHost.request("session.list"),
        )

        scope.cancel()
    }

    @Test
    fun `a recognized hosted-room reason survives the ordinary exchange path`() = runTest {
        // The Gateway's own sentence here is prose nobody may paint; the reason
        // beside it is the machine datum this door exists to carry.
        val rpc = FakeRpc(answer = { _, _ ->
            throw GatewayRpcError(4112, "This Group Chat reached its history limit.", "room_history_expired")
        })
        val scope = scopeFor(this)
        val host = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(rpc))

        assertEquals(
            PluginHostResult.Refused(4112, "Hermes refused that Gateway request.", PluginRefusalReason.RoomHistoryExpired),
            host.request("groups.log"),
        )

        // The other recognized reason, on the same path.
        val conflicted = FakeRpc(answer = { _, _ ->
            throw GatewayRpcError(4111, "This Group Chat is managed by another gateway.", "authority_conflict")
        })
        val conflictedHost = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(conflicted))
        assertEquals(
            PluginHostResult.Refused(4111, "Hermes refused that Gateway request.", PluginRefusalReason.AuthorityConflict),
            conflictedHost.request("groups.send"),
        )

        scope.cancel()
    }

    @Test
    fun `a recognized hosted-room reason survives the endpoint-bound exchange path`() = runTest {
        val rpc = FakeRpc(answer = { _, _ ->
            throw GatewayRpcError(4112, "This Group Chat reached its history limit.", "room_history_expired")
        })
        val scope = scopeFor(this)
        val host = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(rpc), MutableStateFlow(0L))

        assertEquals(
            PluginHostResult.Refused(4112, "Hermes refused that Gateway request.", PluginRefusalReason.RoomHistoryExpired),
            host.requestAtEndpoint(0L, "groups.log"),
        )
        assertEquals("the frame really left through the fence", 1, rpc.calls)

        val conflicted = FakeRpc(answer = { _, _ ->
            throw GatewayRpcError(4113, "This Group Chat is managed by another gateway.", "authority_conflict")
        })
        val conflictedHost = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(conflicted), MutableStateFlow(0L))
        assertEquals(
            PluginHostResult.Refused(4113, "Hermes refused that Gateway request.", PluginRefusalReason.AuthorityConflict),
            conflictedHost.requestAtEndpoint(0L, "groups.disband"),
        )

        scope.cancel()
    }

    @Test
    fun `no unrecognized reason reaches a plugin, and a reasonless 4112 is only a refusal`() = runTest {
        val scope = scopeFor(this)
        // A reason this build does not know — a reworded or mistyped string —
        // is unknown, not a new value, and the string itself never escapes.
        val unknown = FakeRpc(answer = { _, _ ->
            throw GatewayRpcError(4112, "backend said something secret", "room_history_expired_v2")
        })
        val unknownHost = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(unknown))
        val unknownResult = unknownHost.request("groups.log")
        assertEquals(PluginHostResult.Refused(4112, "Hermes refused that Gateway request."), unknownResult)
        assertNull((unknownResult as PluginHostResult.Refused).reason)
        assertFalse(
            "a backend string must not ride out on the result's own text",
            unknownResult.toString().contains("room_history_expired_v2") ||
                unknownResult.toString().contains("secret"),
        )

        // The 4112 in ADR 0004's table is the log method's refusal *code*; it
        // says nothing on its own. Only a reason makes it a pruned room.
        val reasonless = FakeRpc(answer = { _, _ ->
            throw GatewayRpcError(4112, "This Group Chat reached its history limit.")
        })
        val reasonlessHost = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(reasonless))
        assertEquals(
            PluginHostResult.Refused(4112, "Hermes refused that Gateway request."),
            reasonlessHost.request("groups.log"),
        )

        // And an empty string is not a reason either.
        val blank = FakeRpc(answer = { _, _ -> throw GatewayRpcError(4111, "refused", "") })
        val blankHost = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(blank))
        assertNull(blankHost.request("groups.send").let { (it as PluginHostResult.Refused).reason })

        // The same three shapes on the endpoint-bound door, so neither path's
        // mapping can drift from the other's.
        val boundUnknown = FakeRpc(answer = { _, _ ->
            throw GatewayRpcError(4112, "backend said something secret", "room_history_expired_v2")
        })
        val boundUnknownHost =
            GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(boundUnknown), MutableStateFlow(0L))
        val boundUnknownResult = boundUnknownHost.requestAtEndpoint(0L, "groups.log")
        assertEquals(PluginHostResult.Refused(4112, "Hermes refused that Gateway request."), boundUnknownResult)
        assertNull((boundUnknownResult as PluginHostResult.Refused).reason)
        assertFalse(
            "a backend string must not ride out on the result's own text",
            boundUnknownResult.toString().contains("room_history_expired_v2") ||
                boundUnknownResult.toString().contains("secret"),
        )
        assertEquals("the frame really left through the fence", 1, boundUnknown.calls)

        val boundReasonless = FakeRpc(answer = { _, _ ->
            throw GatewayRpcError(4112, "This Group Chat reached its history limit.")
        })
        val boundReasonlessHost =
            GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(boundReasonless), MutableStateFlow(0L))
        assertNull(
            (boundReasonlessHost.requestAtEndpoint(0L, "groups.log") as PluginHostResult.Refused).reason,
        )

        val boundBlank = FakeRpc(answer = { _, _ -> throw GatewayRpcError(4111, "refused", "") })
        val boundBlankHost =
            GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(boundBlank), MutableStateFlow(0L))
        assertNull(
            (boundBlankHost.requestAtEndpoint(0L, "groups.send") as PluginHostResult.Refused).reason,
        )

        scope.cancel()
    }

    @Test
    fun `a reason on a method the Gateway does not serve is not a refusal`() = runTest {
        // `-32601` stays `UnavailableOnGateway`: there is no refusal to type,
        // so nothing may smuggle a reason out through this branch.
        val rpc = FakeRpc(answer = { _, _ ->
            throw GatewayRpcError(-32601, "unknown method: groups.log", "room_history_expired")
        })
        val scope = scopeFor(this)
        val host = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(rpc))

        assertEquals(PluginHostResult.UnavailableOnGateway, host.request("groups.log"))

        // The precedence holds at the endpoint-bound door too: `-32601` still
        // wins over the reason riding on the same error.
        val bound = FakeRpc(answer = { _, _ ->
            throw GatewayRpcError(-32601, "unknown method: groups.log", "room_history_expired")
        })
        val endpointHost = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(bound), MutableStateFlow(0L))
        assertEquals(PluginHostResult.UnavailableOnGateway, endpointHost.requestAtEndpoint(0L, "groups.log"))

        scope.cancel()
    }

    /**
     * The reason is only useful if it is the wire's own `error.data.reason`,
     * decoded by the real client. A fake that throws a prebuilt
     * [GatewayRpcError] cannot prove that leg, so this drives the production
     * [CorrelatedGatewayRpc] over the smallest wire.
     */
    @Test
    fun `the wire's own data reason becomes the typed reason`() = runTest {
        val wire = RecordingWire()
        val rpc = CorrelatedGatewayRpc(wire, timeoutMillis = 1_000)
        val scope = scopeFor(this)
        val host = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(rpc))

        var outcome: PluginHostResult? = null
        val call = launch { outcome = host.request("groups.log") }
        runCurrent()
        assertEquals("the refusal is not answered until the Gateway answers", 1, wire.frames.size)

        rpc.receive(
            """{"jsonrpc":"2.0","id":"m1","error":{"code":4112,""" +
                """"message":"backend prose nobody paints","data":{"reason":"room_history_expired"}}}""",
        )
        runCurrent()

        assertEquals(
            PluginHostResult.Refused(4112, "Hermes refused that Gateway request.", PluginRefusalReason.RoomHistoryExpired),
            outcome,
        )

        // An arbitrary reason string on the same wire is dropped whole: the raw
        // value appears nowhere on the result, including its text.
        var hostile: PluginHostResult? = null
        val second = launch { hostile = host.request("groups.log") }
        runCurrent()
        rpc.receive(
            """{"jsonrpc":"2.0","id":"m2","error":{"code":4111,""" +
                """"message":"backend prose nobody paints","data":{"reason":"authorization: Bearer leaked"}}}""",
        )
        runCurrent()

        val refused = hostile as PluginHostResult.Refused
        assertEquals(PluginHostResult.Refused(4111, "Hermes refused that Gateway request."), refused)
        assertNull(refused.reason)
        assertFalse("no raw reason substring in the result text", refused.toString().contains("Bearer"))
        assertFalse("no backend prose in the result text", refused.toString().contains("nobody paints"))

        // Only a JSON string can name a reason, so a malformed shape is dropped
        // whole rather than read as one. The id comes off the frame the client
        // actually sent, so this answers that call and no other.
        for (shape in listOf("4112", "null", """["room_history_expired"]""")) {
            val framesBefore = wire.frames.size
            var shaped: PluginHostResult? = null
            val shapedCall = launch { shaped = host.request("groups.log") }
            runCurrent()
            assertEquals("the shaped call reached the wire", framesBefore + 1, wire.frames.size)
            val id = wire.frames.last().substringAfter("\"id\":\"").substringBefore('"')
            rpc.receive(
                """{"jsonrpc":"2.0","id":"$id","error":{"code":4112,""" +
                    """"message":"backend prose nobody paints","data":{"reason":$shape}}}""",
            )
            runCurrent()
            val shapedResult = shaped as PluginHostResult.Refused
            assertNull("a $shape reason is not a reason", shapedResult.reason)
            assertEquals(PluginHostResult.Refused(4112, "Hermes refused that Gateway request."), shapedResult)
            assertFalse("a $shape reason never reaches the result text", shapedResult.toString().contains("room_history_expired"))
            shapedCall.join()
        }

        call.join()
        second.join()
        scope.cancel()
    }

    @Test
    fun `disabling a plugin cuts its in-flight request off`() = runTest {
        // A call that never answers on its own: only cancellation ends it.
        val gate = CompletableDeferred<JsonElement>()
        val rpc = FakeRpc(answer = { _, _ -> gate.await() })
        val clients = MutableStateFlow<GatewayRpcClient?>(rpc)
        // A child scope of the test scope, so cancelling "the plugin" does not
        // tear the test down with it.
        val scope = scopeFor(this)
        val host = GatewayPluginHost(scope, clients)

        var result: PluginHostResult? = null
        val call = launch { result = host.request("bot_relay.deliver") }
        runCurrent()

        assertEquals(1, rpc.calls)
        assertNull("the request is still in flight", result)

        // PluginLoader deactivate() → every disposer, including the scope cancel.
        scope.cancel()
        call.join()

        assertNull("a disposed plugin must never see the answer", result)
        assertTrue("the caller is cancelled, not left suspended", call.isCancelled)
    }

    @Test
    fun `onEvent delivers matching events, isolates a throwing listener, and stops on dispose`() =
        runTest {
            val events = MutableSharedFlow<GatewayEvent>(replay = 16)
            val rpc = FakeRpc(events = events)
            val scope = scopeFor(this)
            val host = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(rpc))

            val seen = mutableListOf<String>()
            val throwing = host.onEvent("bot.relay") { throw IllegalStateException("plugin bug") }
            val typed = host.onEvent("bot.relay") { seen += it.payload.jsonPrimitive.content }
            val star = host.onEvent("*") { seen += "star:${it.type}" }
            val unrelated = host.onEvent("cron.changed") { seen += "cron" }
            runCurrent()

            events.emit(GatewayEvent("bot.relay", null, JsonPrimitive("a")))
            events.emit(GatewayEvent("cron.changed", null, JsonPrimitive("b")))
            runCurrent()

            assertTrue("typed listener still works after a sibling threw", "a" in seen)
            assertTrue("star receives everything", "star:bot.relay" in seen && "star:cron.changed" in seen)
            assertTrue("an unrelated type still reaches its listener", "cron" in seen)

            typed()
            val before = seen.size
            events.emit(GatewayEvent("bot.relay", null, JsonPrimitive("c")))
            runCurrent()
            // Only the `*` listener is still attached, so exactly one new entry
            // lands — and it is the star's, not the disposed typed listener's.
            assertEquals("a disposed listener hears nothing more", before + 1, seen.size)
            assertFalse("c" in seen)

            throwing()
            star()
            unrelated()
            scope.cancel()
        }

    /**
     * The regression the PR #207 review found: a plugin's tap used to be a
     * second *competing consumer* of the client's single-consumer event queue,
     * so an `onEvent` subscription stole events from the app's only pump.
     * Both now see the whole stream — driven here through the real
     * [CorrelatedGatewayRpc], because the `MutableSharedFlow` fake above
     * structurally cannot catch it.
     */
    @Test
    fun `a plugin tap and the app pump each receive every gateway event`() = runTest {
        val rpc = CorrelatedGatewayRpc(
            RecordingWire(),
            eventPumpDispatcher = StandardTestDispatcher(testScheduler),
        )
        val scope = scopeFor(this)
        val host = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(rpc))

        val appPump = mutableListOf<String>()
        val app = launch { rpc.events.collect { appPump += it.payload.jsonObject.getValue("delta").jsonPrimitive.content } }
        val pluginTap = mutableListOf<String>()
        val dispose = host.onEvent(PluginHost.ALL_EVENTS) {
            pluginTap += it.payload.jsonObject.getValue("delta").jsonPrimitive.content
        }
        runCurrent()

        repeat(4) { index -> rpc.receive(deltaFrame(index)) }
        advanceUntilIdle()

        assertEquals("the app's pump keeps every event", listOf("0", "1", "2", "3"), appPump)
        assertEquals("a plugin tap is a subscriber, not a thief", listOf("0", "1", "2", "3"), pluginTap)

        dispose()
        app.cancel()
        scope.cancel()
        rpc.close()
        advanceUntilIdle()
    }

    /**
     * The regression the PR #207 review found: `withTimeout` in the client
     * raises `TimeoutCancellationException`, which used to escape
     * `host.request` as a cancellation of the plugin's own coroutine. The door
     * contracts a value, so a call the Gateway never answers is
     * [PluginHostResult.Refused].
     */
    @Test
    fun `a gateway timeout is a result value, not a cancellation`() = runTest {
        val rpc = CorrelatedGatewayRpc(RecordingWire(), timeoutMillis = 100)
        val scope = scopeFor(this)
        val host = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(rpc))

        var outcome: Result<PluginHostResult>? = null
        val call = launch { outcome = runCatching { host.request("session.list") } }
        runCurrent()
        advanceTimeBy(101)
        runCurrent()

        val answered = outcome
        assertNotNull("the call answers instead of staying in flight", answered)
        assertNull(
            "a timeout must not throw out of the host door",
            answered!!.exceptionOrNull(),
        )
        assertEquals(
            PluginHostResult.Refused(0, "The Gateway did not answer in time."),
            answered.getOrNull(),
        )

        call.join()
        assertFalse("the plugin's own scope survives the timeout", scope.coroutineContext[Job]?.isCancelled == true)
        scope.cancel()
    }

    /** A `message.delta` frame carrying [index], the shape the gateway sends. */
    private fun deltaFrame(index: Int): String =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"r1","payload":{"delta":"$index"}}}"""

    /** The smallest wire a real [CorrelatedGatewayRpc] can be driven over. */
    private class RecordingWire : GatewayRpcWire {
        val frames = mutableListOf<String>()

        override fun send(text: String): Boolean = frames.add(text)

        override fun close() {}
    }

    @Test
    fun `a malformed method is refused even with no live connection`() = runTest {
        val scope = scopeFor(this)
        val host = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(null))

        val failure = runCatching { host.request("bot..relay") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)

        scope.cancel()
    }

    @Test
    fun `UnavailablePluginHost refuses every call rather than throwing`() = runTest {
        assertEquals(
            PluginHostResult.Refused(0, "Reconnect to the Gateway and try again."),
            UnavailablePluginHost.request("session.list"),
        )
        val disposer = UnavailablePluginHost.onEvent("bot.relay") {}
        disposer()
    }
}
