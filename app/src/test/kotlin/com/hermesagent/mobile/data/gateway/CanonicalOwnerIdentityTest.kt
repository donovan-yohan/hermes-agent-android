package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.SessionCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Owner namespace: apps/desktop/src/api/canonical-protocol.ts:6-10 and hermes_cli/web_routers/sessions.py:183-274 @ 379cf1220fe289b5ecfe8bcf61cd4d5126e140cd. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanonicalOwnerIdentityTest {
    @Test
    fun `root event alias retains implicit response route while equal named id stays separate`() {
        val identity = GatewaySessionIdentity("synthetic-endpoint")
        identity.bind(null, "synthetic-root")
        identity.bind("beta", "synthetic-beta")
        val root = identity.qualify("same", null)
        val beta = identity.qualify("same", "beta")
        // Canonical snapshot owners use the empty alias internally; its event must not
        // turn an implicit root route into a forbidden profile field on a closed response.
        assertEquals(root, identity.qualify("same", ""))
        assertNull(identity.address(root)?.profile)
        assertEquals("beta", identity.address(beta)?.profile)
        assertNotEquals(root, beta)
    }

    @Test
    fun `REST alpha same and beta same survive first merge as separate authoritative rows`() = runTest {
        lateinit var rpc: CorrelatedGatewayRpc
        rpc = CorrelatedGatewayRpc(object : GatewayRpcWire {
            override fun send(text: String): Boolean {
                val frame = Json.parseToJsonElement(text).jsonObject
                val params = frame.getValue("params").jsonObject
                val profile = params["profile"]?.jsonPrimitive?.content ?: "default"
                val result = when (frame["method"]) {
                    JsonPrimitive("runtime.describe") ->
                        """{"instance_id":"public-instance","profile_id":"public-owner-$profile","authority_epoch":2,"capabilities":["durable-admission-v1","event-replay-v1","local-cli-create-v1"],"session_create":{"sources":["gui"],"parameters":[]},"skin":{}}"""
                    JsonPrimitive("profiles.list") ->
                        """{"profiles":[{"name":"$profile","path":"public-owner-$profile"}],"bot_mode_protocol":true}"""
                    else -> "{}"
                }
                rpc.receive("""{"jsonrpc":"2.0","id":${frame.getValue("id")},"result":$result}""")
                return true
            }
            override fun close() = Unit
        }, eventPumpDispatcher = UnconfinedTestDispatcher(testScheduler))
        rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true,"change_events":true,"heartbeat":true,"replay_epoch":"public-global"}}}""")
        val requests = mutableListOf<GatewayHttpRequest>()
        val http = object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
                requests += request
                val profile = request.query["profile"] ?: "default"
                return GatewayHttpResult.Success(200, """{"sessions":[{"id":"same","title":"$profile public chat","profile":"$profile","source":"gui","started_at":1800000000,"last_active":1800000000,"message_count":1}],"total":1,"limit":100,"offset":0}""".toByteArray())
            }
        }
        val cache = SessionCache()
        val repository = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope,
            http = { http }, restContext = UnconfinedTestDispatcher(testScheduler)) { 1800000000000 }
        repository.setProfileRouting(ProfileRouting(activeProfile = "alpha", listProfiles = listOf("alpha", "beta")))
        runCurrent()
        repository.refreshSessions()
        runCurrent()
        val rows = cache.state.value.sessions.values.toList()
        assertEquals("same raw id in distinct profile stores must not clobber", 2, rows.size)
        assertEquals(setOf("alpha", "beta"), rows.map { it.remoteProfile }.toSet())
        assertEquals(2, rows.map { it.id }.distinct().size)
        assertTrue(requests.all { it.query["profile"] in setOf("alpha", "beta") })
        rpc.close()
    }
}
