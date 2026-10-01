package com.hermesagent.mobile

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotModelRuntimeExportTest {
    @Test fun pendingExportHasLiveMonotonicTimeAndCancellationIsNotPending() = runTest {
        val host = BotManagementFixtureHost()
        host.scenario = "bot-model-inventory-loading"
        val job = backgroundScope.launch {
            host.request("model.options", buildJsonObject {
                put("profile", "synthetic-planner"); put("include_unconfigured", true); put("explicit_only", false)
            })
        }
        testScheduler.runCurrent()
        val first = host.runtimeSnapshot()["requests"]!!.jsonArray.single().jsonObject
        assertTrue(first["pending"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, first["response"])
        assertEquals(JsonNull, first["error"])
        val second = host.runtimeSnapshot()["requests"]!!.jsonArray.single().jsonObject
        assertTrue(second["elapsed_ms"]!!.jsonPrimitive.double >= first["elapsed_ms"]!!.jsonPrimitive.double)
        job.cancel()
        testScheduler.runCurrent()
        val cancelled = host.runtimeSnapshot()["requests"]!!.jsonArray.single().jsonObject
        assertFalse(cancelled["pending"]!!.jsonPrimitive.boolean)
        assertEquals("cancelled", cancelled["error"]!!.jsonPrimitive.content)
    }

    @Test fun privateCallerValuesNeverEnterExport() = runTest {
        val host = BotManagementFixtureHost()
        host.request("profiles.describe", buildJsonObject { put("name", "PRIVATE-UNSAFE") })
        assertFalse(host.runtimeSnapshot().toString().contains("PRIVATE-UNSAFE"))
        assertTrue(host.runtimeSnapshot()["requests"]!!.jsonArray.isEmpty())
    }

    @Test fun exportRecordsActualNamedReadWarningApplyAndReadback() = runTest {
        val host = BotManagementFixtureHost()
        host.scenario = "bot-model-saved"
        val named = buildJsonObject { put("name", "synthetic-planner") }
        val pair = buildJsonObject { put("name", "synthetic-planner"); put("provider", "synthetic-provider"); put("model", "synthetic-planner-v2") }
        host.request("profiles.describe", named)
        host.request("profiles.configure", pair)
        host.request("profiles.configure", JsonObject(pair + ("confirm_expensive_model" to JsonPrimitive(true))))
        host.request("profiles.describe", named)
        val method = host.javaClass.methods.firstOrNull { it.name == "runtimeSnapshot" }
        assertNotNull("debug host must export runtime outcomes", method)
        val trace = method!!.invoke(host) as JsonObject
        assertEquals("synthetic-planner-v2", trace["authoritative_model"]!!.jsonPrimitive.content)
        val requests = trace["requests"]!!.jsonArray
        assertEquals(4, requests.size)
        assertEquals(listOf(1, 2, 3, 4), requests.map { it.jsonObject["sequence"]!!.jsonPrimitive.int })
        assertEquals(true, requests[1].jsonObject["response"]!!.jsonObject["confirm_required"]!!.jsonPrimitive.boolean)
        assertEquals("synthetic-planner-v2", requests.last().jsonObject["response"]!!.jsonObject["model"]!!.jsonObject["default"]!!.jsonPrimitive.content)
    }
}
