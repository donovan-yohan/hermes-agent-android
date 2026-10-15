package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Client channel/provenance tests only. Synthetic peers do not prove server native settlement.
 * Public dashboard root response authorization is blocked for named-profile-only IDs at this pin.
 * Native builders: session_sudo_prompt.py:80-93; resolver: session_controls.py:139-146,581-590
 * @ 379cf1220fe289b5ecfe8bcf61cd4d5126e140cd. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileGatewayRpcTest {
    @Test
    fun `equal raw session ids attach once per immutable owner channel and native requests retain origin`() = runTest {
        val primary = Peer("alpha", UnconfinedTestDispatcher(testScheduler))
        val beta = Peer("beta", UnconfinedTestDispatcher(testScheduler))
        val opened = mutableListOf<String>()
        val client = ProfileGatewayRpcClient(primary.rpc, backgroundScope) { profile ->
            opened += profile
            beta.rpc
        }
        val requests = mutableListOf<GatewayServerRequest>()
        val events = mutableListOf<GatewayEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.serverRequests.collect { requests += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.events.collect { events += it } }
        val alpha = client.request("session.resume", params("alpha")).jsonObject.getValue("session_id").jsonPrimitive.content
        val betaKey = client.request("session.resume", params("beta")).jsonObject.getValue("session_id").jsonPrimitive.content
        runCurrent()
        assertEquals("named owner must attach on an isolated channel", listOf("beta"), opened)
        assertEquals(1, primary.frames.count { it["method"] == JsonPrimitive("session.resume") })
        assertEquals(1, beta.frames.count { it["method"] == JsonPrimitive("session.resume") })
        val declaration = beta.frames.single { it["method"] == JsonPrimitive("client.capabilities") }
        assertFalse("transport capability declaration has no profile field upstream",
            "profile" in declaration.getValue("params").jsonObject)
        assertNotEquals(alpha, betaKey)
        primary.askSecret()
        beta.askSecret()
        primary.idleInfo()
        beta.idleInfo()
        runCurrent()
        assertEquals(setOf(alpha, betaKey), requests.map { it.runtimeSessionId }.toSet())
        assertEquals(listOf("srq-public", "srq-public"), requests.map { it.id })
        val infos = events.filter { it.type == "session.info" }
        assertEquals(setOf(alpha, betaKey), infos.map { it.runtimeSessionId }.toSet())
        assertTrue(infos.all { it.payload.jsonObject.getValue("stored_session_id").jsonPrimitive.content == it.runtimeSessionId })
        assertTrue("fixtures must not smuggle in owner provenance", requests.all { "profile" !in it.params && "replay_epoch" !in it.params })
        assertTrue(runCatching { client.respondToServerRequest("srq-public", JsonObject(emptyMap())) }.exceptionOrNull() is GatewayRpcException)
        assertTrue(runCatching { client.respondToServerRequestAtOwner(alpha, "srq-public", buildJsonObject { put("value", "public alpha answer") }) { false } }.exceptionOrNull() is GatewayRpcException)
        assertTrue(primary.frames.none { "result" in it })
        client.respondToServerRequestAtOwner(alpha, "srq-public", buildJsonObject { put("value", "public alpha answer") }) { send -> send() }
        assertTrue(runCatching { client.respondToServerRequestAtOwner(betaKey, "srq-public", buildJsonObject { put("value", "public beta answer") }) { send -> send() } }.exceptionOrNull() is GatewayRpcException)
        assertEquals("public alpha answer", primary.frames.single { "result" in it }.getValue("result").jsonObject.getValue("value").jsonPrimitive.content)
        assertTrue("stock named native reply must not be sent", beta.frames.none { "result" in it })
        client.close()
        assertEquals(1, primary.closeCount)
        assertEquals(1, beta.closeCount)
    }

    @Test
    fun `late authenticated child dial closes without attaching after graph teardown`() = runTest {
        val primary = Peer("alpha", UnconfinedTestDispatcher(testScheduler))
        val child = Peer("beta", UnconfinedTestDispatcher(testScheduler))
        val dialing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = ProfileGatewayRpcClient(primary.rpc, backgroundScope) {
            dialing.complete(Unit)
            withContext(NonCancellable) { release.await() }
            child.rpc
        }
        val result = CompletableDeferred<Throwable?>()
        val request = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            result.complete(runCatching { client.request("session.resume", params("beta")) }.exceptionOrNull())
        }
        dialing.await()
        client.close()
        release.complete(Unit)
        runCurrent()
        request.join()
        assertNotNull(result.await())
        assertEquals(1, primary.closeCount)
        assertEquals(1, child.closeCount)
        assertTrue("a stale dial must never attach or advertise", child.frames.isEmpty())
        assertTrue(runCatching { client.request("session.resume", params("alpha")) }.exceptionOrNull() is GatewayRpcException)
        assertEquals(1, primary.closeCount)
    }

    @Test
    fun `child authentication refusal does not attach on primary or retry another grant`() = runTest {
        val primary = Peer("alpha", UnconfinedTestDispatcher(testScheduler))
        var dials = 0
        val client = ProfileGatewayRpcClient(primary.rpc, backgroundScope) {
            dials++
            throw GatewayRpcException("Synthetic authentication refusal")
        }
        val failure = runCatching { client.request("session.resume", params("beta")) }.exceptionOrNull()
        assertEquals("Synthetic authentication refusal", failure?.message)
        assertEquals(1, dials)
        assertTrue(primary.frames.none { it["method"] == JsonPrimitive("session.resume") })
        client.close()
    }

    @Test
    fun `stock named native input refuses answers and recovers only through captured owner Stop`() = runTest {
        val primary = Peer("alpha", UnconfinedTestDispatcher(testScheduler))
        val beta = Peer("beta", UnconfinedTestDispatcher(testScheduler))
        val graph = ProfileGatewayRpcClient(primary.rpc, backgroundScope) { beta.rpc }
        val cache = com.hermesagent.mobile.data.session.SessionCache()
        val repository = LiveGatewaySessionRepository(cache,
            kotlinx.coroutines.flow.MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            kotlinx.coroutines.flow.MutableStateFlow<GatewayRpcClient?>(graph), backgroundScope)
        runCurrent()
        repository.openSession("same", "beta")
        beta.askSecret()
        runCurrent()
        val pending = repository.pendingInputs.value.values.single()
        assertTrue(pending is UnsupportedPendingInput)
        val secret = "public synthetic value".toCharArray()
        assertEquals(PendingInputResponse.Unanswerable,
            repository.respondToPendingInput(pending.key, PendingInputAction.SecretValue(secret)))
        assertTrue(secret.all { it == '\u0000' })
        assertTrue(beta.frames.none { "result" in it })
        assertEquals(PendingInputResponse.Stopping,
            repository.respondToPendingInput(pending.key, PendingInputAction.StopUnsupported))
        assertTrue(repository.pendingInputs.value.values.single() is UnsupportedPendingInput)
        assertEquals(PendingInputResponse.Stopping,
            repository.respondToPendingInput(pending.key, PendingInputAction.StopUnsupported))
        assertEquals(1, beta.frames.count { it["method"] == JsonPrimitive("session.interrupt") })
        val control = beta.frames.single { it["method"] == JsonPrimitive("session.interrupt") }.getValue("params").jsonObject
        assertEquals(JsonPrimitive("beta"), control["profile"])
        assertEquals(JsonPrimitive(3), control["execution_generation"])
        assertTrue(primary.frames.none { it["method"] == JsonPrimitive("session.interrupt") })
        graph.close()
    }

    private fun params(profile: String) = buildJsonObject { put("profile", profile); put("session_id", "same") }

    private class Peer(private val owner: String, dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        val frames = mutableListOf<JsonObject>()
        var closeCount = 0
        val rpc: CorrelatedGatewayRpc = CorrelatedGatewayRpc(object : GatewayRpcWire {
            override fun send(text: String): Boolean {
                val frame = Json.parseToJsonElement(text).jsonObject
                frames += frame
                if ("method" !in frame) return true
                val params = frame.getValue("params").jsonObject
                val profile = params["profile"]?.jsonPrimitive?.content ?: owner
                if (frame["method"] == JsonPrimitive("runtime.describe") && params.isNotEmpty()) {
                    rpc.receive("""{"jsonrpc":"2.0","id":${frame.getValue("id")},"error":{"code":-32602,"message":"invalid_params","data":{"reason":"invalid_params","fields":["profile"]}}}""")
                    return true
                }
                val result = when (frame.getValue("method").jsonPrimitive.content) {
                    "profiles.list" -> """{"profiles":[{"name":"$profile","path":"public-owner-$profile"}],"bot_mode_protocol":true}"""
                    "runtime.describe" -> """{"instance_id":"public-instance","profile_id":"public-owner-alpha","authority_epoch":2,"capabilities":["durable-admission-v1","event-replay-v1","local-cli-create-v1"],"skin":{}}"""
                    "session.resume" -> """{"session_id":"same","stored_session_id":"same","messages":[],"display_messages":[],"message_count":0,"running":false,"authority_epoch":2,"replay_epoch":"public-epoch-$profile","last_sequence":0,"subscription_id":"public-sub-$profile","revision":7,"execution_generation":3,"pending":[],"prompts":[],"info":{"desktop_protocol":"hermes-gateway-v1","profile_name":"$profile","profile_id":"public-owner-$profile"}}"""
                    "session.list" -> """{"sessions":[],"scope":"live"}"""
                    else -> "{}"
                }
                rpc.receive("""{"jsonrpc":"2.0","id":${frame.getValue("id")},"result":$result}""")
                return true
            }
            override fun close() { closeCount++ }
        }, eventPumpDispatcher = dispatcher)
        init { rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true,"change_events":true}}}""") }
        fun idleInfo() = rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"session.info","session_id":"same","payload":{"stored_session_id":"same","pending":[],"desktop_protocol":"hermes-gateway-v1","running":false,"execution_generation":3,"revision":7},"replay_epoch":"public-epoch-$owner","seq":1}}""")
        fun askSecret() = rpc.receive("""{"jsonrpc":"2.0","id":"srq-public","method":"secret","params":{"session_id":"same","env_var":"PUBLIC_SYNTHETIC","prompt":"Synthetic secret"}}""")
    }
}
