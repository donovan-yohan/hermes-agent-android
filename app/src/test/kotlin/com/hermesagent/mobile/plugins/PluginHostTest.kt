package com.hermesagent.mobile.plugins

import com.hermesagent.mobile.data.gateway.GatewayEvent
import com.hermesagent.mobile.data.gateway.GatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayRpcError
import com.hermesagent.mobile.data.gateway.GatewayRpcException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
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
    ) : GatewayRpcClient {
        var lastMethod: String? = null
        var lastParams: JsonObject? = null
        var calls = 0

        override suspend fun request(method: String, params: JsonObject): JsonElement {
            calls += 1
            lastMethod = method
            lastParams = params
            return answer(method, params)
        }

        override fun close() {}
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
    fun `request without a live connection refuses with the transport sentence`() = runTest {
        val clients = MutableStateFlow<GatewayRpcClient?>(null)
        val scope = scopeFor(this)
        val host = GatewayPluginHost(scope, clients)

        val refused = host.request("session.list")
        assertEquals(PluginHostResult.Refused(0, "Reconnect to the Gateway and try again."), refused)

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
