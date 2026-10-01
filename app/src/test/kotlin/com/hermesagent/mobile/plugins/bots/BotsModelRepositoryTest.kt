package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class ModelTestHost : PluginHost {
    override val endpointGeneration = MutableStateFlow(7L)
    override val connected = MutableStateFlow(true)
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var beforeDispatch: suspend () -> Unit = {}
    var answer: suspend (String, JsonObject) -> PluginHostResult = { _, _ -> error("Unexpected request") }
    override suspend fun request(method: String, params: JsonObject): PluginHostResult = error("Unfenced call")
    override suspend fun requestAtEndpoint(expectedGeneration: Long, method: String, params: JsonObject): PluginHostResult {
        return requestAtEndpointGuarded(expectedGeneration, method, params) { true }
    }
    override suspend fun requestAtEndpointGuarded(expectedGeneration: Long, method: String, params: JsonObject, dispatchAllowed: () -> Boolean): PluginHostResult {
        beforeDispatch()
        if (!dispatchAllowed() || expectedGeneration != endpointGeneration.value) return PluginHostResult.Refused(0, "Changed")
        calls += method to params
        val response = answer(method, params)
        return if (expectedGeneration == endpointGeneration.value) response else PluginHostResult.Refused(0, "Changed")
    }
    override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
}
internal fun modelReply(text: String) = PluginHostResult.Success(Json.parseToJsonElement(text))

class BotsModelRepositoryTest {
    private val target = BotManagementTarget("worker", 7L)
    private val pair = BotModelSelection("custom-provider", "custom-model")

    @Test fun `save contains only model pair and requires exact describe readback`() = runTest {
        val host = ModelTestHost().apply { answer = { method, _ -> if (method == "profiles.configure")
            modelReply("""{"ok":true,"applied":{"model":true}}""") else
            modelReply("""{"name":"worker","model":{"provider":"custom-provider","default":"custom-model"}}""") } }
        assertEquals(BotModelSave.Saved, BotsModelRepository(host).save(target, pair))
        assertEquals(Json.parseToJsonElement("""{"name":"worker","provider":"custom-provider","model":"custom-model"}"""), host.calls.first().second)
        assertEquals(listOf("profiles.configure", "profiles.describe"), host.calls.map { it.first })
        assertEquals(Json.parseToJsonElement("""{"name":"worker"}"""), host.calls.last().second)
    }

    @Test fun `confirmation even with ok and applied is not success and resend is model only`() = runTest {
        val host = ModelTestHost().apply { answer = { _, _ -> modelReply("""{"ok":true,"applied":{"model":true},"confirm_required":true,"confirm_message":"Review cost"}""") } }
        val repo = BotsModelRepository(host)
        assertTrue(repo.save(target, pair) is BotModelSave.Confirmation)
        assertEquals(1, host.calls.size)
        repo.save(target, pair, confirmed = true)
        assertEquals(Json.parseToJsonElement("""{"name":"worker","provider":"custom-provider","model":"custom-model","confirm_expensive_model":true}"""), host.calls.last().second)
    }

    @Test fun `missing false malformed receipts never trigger readback`() = runTest {
        for (receipt in listOf("{}", """{"ok":true}""", """{"ok":true,"applied":{"model":false}}""", """{"ok":true,"applied":{"model":"true"}}""", """{"ok":false,"applied":{"model":true}}""")) {
            val host = ModelTestHost().apply { answer = { _, _ -> modelReply(receipt) } }
            assertEquals(BotModelSave.Unconfirmed, BotsModelRepository(host).save(target, pair))
            assertEquals(1, host.calls.size)
        }
    }

    @Test fun `foreign name or mismatched persisted pair is unconfirmed`() = runTest {
        for (readback in listOf("""{"name":"other","model":{"provider":"custom-provider","default":"custom-model"}}""", """{"name":"worker","model":{"provider":"custom-provider","default":"old"}}""")) {
            val host = ModelTestHost().apply { answer = { method, _ -> modelReply(if (method == "profiles.configure") """{"ok":true,"applied":{"model":true}}""" else readback) } }
            assertEquals(BotModelSave.Unconfirmed, BotsModelRepository(host).save(target, pair))
        }
    }

    @Test fun `blank reset half pairs and whitespace are refused without dispatch`() = runTest {
        val host = ModelTestHost()
        for (selection in listOf(BotModelSelection(), BotModelSelection("p", ""), BotModelSelection("", "m"), BotModelSelection(" p", "m")))
            assertEquals(BotModelSave.Rejected, BotsModelRepository(host).save(target, selection))
        assertTrue(host.calls.isEmpty())
    }

    @Test fun `inventory is bot scoped and retains provider aliases and legacy model rows`() = runTest {
        val host = ModelTestHost().apply { answer = { _, _ -> modelReply("""{"providers":[{"slug":"p","name":"Provider","aliases":["alias"],"models":["one",{"id":"two"},{"name":"three"}]}]}""") } }
        val options = BotsModelRepository(host).options(target)!!
        assertEquals(listOf("one", "two", "three"), options.single().models)
        assertTrue(options.single().matches("alias"))
        assertEquals(Json.parseToJsonElement("""{"profile":"worker","include_unconfigured":true,"explicit_only":false}"""), host.calls.single().second)
    }
}
