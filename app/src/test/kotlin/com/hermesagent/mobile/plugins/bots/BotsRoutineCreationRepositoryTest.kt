package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostEvent
import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotsRoutineCreationRepositoryTest {
    private class Host(
        private val answer: PluginHostResult,
        override val endpointGeneration: MutableStateFlow<Long> = MutableStateFlow(7L),
    ) : PluginHost {
        var method: String? = null
        var params: JsonObject? = null

        override suspend fun request(method: String, params: JsonObject): PluginHostResult {
            this.method = method
            this.params = params
            return answer
        }

        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }

    @Test
    fun `production host prevents add on a replaced endpoint at the wire boundary`() = runTest {
        val endpoint = MutableStateFlow(0L)
        val beforeWire = kotlinx.coroutines.CompletableDeferred<Unit>()
        val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
        var writes = 0
        val rpc = object : com.hermesagent.mobile.data.gateway.EndpointDispatchingGatewayRpcClient {
            override val events = kotlinx.coroutines.flow.emptyFlow<com.hermesagent.mobile.data.gateway.GatewayEvent>()
            override fun close() = Unit
            override suspend fun request(method: String, params: JsonObject): kotlinx.serialization.json.JsonElement =
                error("unfenced add")
            override suspend fun requestAtEndpointDispatch(
                method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean,
            ): kotlinx.serialization.json.JsonElement {
                reached.complete(Unit)
                beforeWire.await()
                if (!dispatch { writes++; true }) throw com.hermesagent.mobile.data.gateway.GatewayRpcException("stale lease")
                return Json.parseToJsonElement("""{"success":true,"job_id":"new"}""")
            }
        }
        val host = com.hermesagent.mobile.plugins.GatewayPluginHost(
            backgroundScope, MutableStateFlow<com.hermesagent.mobile.data.gateway.GatewayRpcClient?>(rpc), endpoint,
        )
        val result = kotlinx.coroutines.CompletableDeferred<RoutineCreationAck>()
        backgroundScope.launch {
            result.complete(BotsPluginRepository(host).createRoutine(RoutineCreationTarget("ops", 0L, 0L), draft))
        }
        reached.await()
        endpoint.value++
        beforeWire.complete(Unit)
        assertTrue(result.await() is RoutineCreationAck.Unconfirmed)
        assertEquals(0, writes)
    }

    private val draft = RoutineCreationDraft(title = "Morning", instruction = "Do work")
    private val target = RoutineCreationTarget(owner = " Ops-Team ", endpoint = 7L, selection = 3L)

    @Test fun `null id success crosses the repository seam as unconfirmed without fabricated identity`() = runTest {
        for (id in listOf("null", "false", "42", "{}", "[]")) {
            val host = Host(PluginHostResult.Success(Json.parseToJsonElement("""{"success":true,"job_id":$id}""")))
            assertEquals(RoutineCreationAck.Unconfirmed(), BotsPluginRepository(host).createRoutine(target, draft))
            assertEquals("cron.manage", host.method)
        }
    }

    @Test
    fun `add dispatch captures raw owner and exact composed fields`() = runTest {
        val host = Host(PluginHostResult.Success(Json.parseToJsonElement("""{"success":true,"job_id":"job-7"}""")))

        val result = BotsPluginRepository(host).createRoutine(target, draft)

        assertEquals(RoutineCreationAck.Created("job-7"), result)
        assertEquals("cron.manage", host.method)
        assertEquals(Json.parseToJsonElement("""{"action":"add","name":"[bot: Ops-Team ] Morning","schedule":"0 9 * * *","prompt":"Do work","profile":" Ops-Team "}"""), host.params)
    }

    @Test
    fun `inner success false is a rejection rather than a successful rpc`() = runTest {
        val host = Host(PluginHostResult.Success(Json.parseToJsonElement("""{"success":false,"error":"private backend prose"}""")))

        val result = BotsPluginRepository(host).createRoutine(target, draft)

        assertEquals(RoutineCreationAck.Rejected, result)
        assertFalse(result.automaticRecreationAllowed)
    }

    @Test
    fun `saved registration failure retains exact identity without retry permission`() = runTest {
        val host = Host(PluginHostResult.Success(Json.parseToJsonElement("""{"success":false,"job_id":"saved-7","job_saved":true,"scheduler_registered":false,"retry_create":false}""")))

        val result = BotsPluginRepository(host).createRoutine(target, draft)

        assertEquals(RoutineCreationAck.SavedRegistrationFailed("saved-7"), result)
        assertFalse(result.automaticRecreationAllowed)
    }

    @Test
    fun `transport refusal is unconfirmed and never silently retried`() = runTest {
        val host = Host(PluginHostResult.Refused(0, "safe refusal"))

        val result = BotsPluginRepository(host).createRoutine(target, draft)

        assertTrue(result is RoutineCreationAck.Unconfirmed)
        assertEquals(1, if (host.method == "cron.manage") 1 else 0)
    }
}
