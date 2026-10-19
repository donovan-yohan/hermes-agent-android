package com.hermesagent.mobile.data.cron

import com.hermesagent.mobile.data.gateway.EndpointDispatchFence
import com.hermesagent.mobile.data.gateway.OkHttpGatewayHttp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in actual upstream APIRouter/stores. No agent job is ever triggered. */
class GatewayCronLiveTest {
    @Test fun productionClientManagesRealNamedStoreAndScriptOutput() = runBlocking {
        val base = System.getenv("HM_CRON_SMOKE_URL")
        val token = System.getenv("HM_CRON_SMOKE_TOKEN")
        assumeTrue(!base.isNullOrBlank() && !token.isNullOrBlank())
        val client = OkHttpClient()
        val transport = OkHttpGatewayHttp(client, { base }, { "X-Hermes-Session-Token" to token!! })
        val api = GatewayCronApi({ transport }, MutableStateFlow(0L), EndpointDispatchFence())
        val scope = api.openScope(0, "worker_alpha")
        val default = api.openScope(0, "default")
        fun <T> confirmed(result: CronResult<T>): T {
            assertTrue("Production cron operation must be confirmed", result is CronResult.Success)
            return (result as CronResult.Success).value
        }
        try {
            assertTrue(confirmed(scope.jobs()).isEmpty())
            val created = confirmed(scope.create(CronDraft(name = "  Kotlin cron fixture  ", prompt = "Synthetic unused agent prompt")))
            assertEquals("worker_alpha", created.profile)
            assertEquals("Kotlin cron fixture", created.name)
            assertTrue(confirmed(default.jobs()).isEmpty())
            val edited = confirmed(scope.update(created, CronDraft.from(created).copy(name = "Kotlin edited", schedule = "30 10 * * 1-5")))
            assertEquals("Kotlin edited", confirmed(scope.detail(edited.id)).name)
            val natural = confirmed(scope.update(edited, CronDraft.from(edited).copy(
                name = "", schedule = "every monday and tuesday at 9am",
            )))
            assertEquals("Synthetic unused agent prompt", natural.name)
            assertEquals("0 9 * * 1,2", natural.schedule)
            confirmed(scope.act(edited, CronAction.Pause))
            assertFalse(confirmed(scope.detail(edited.id)).enabled)
            confirmed(scope.act(edited, CronAction.Resume))
            assertTrue(confirmed(scope.detail(edited.id)).enabled)
            assertTrue(confirmed(scope.runs(edited.id)).isEmpty())
            assertTrue(confirmed(scope.deliveryTargets()).any { it.id == "local" })
            val recipes = confirmed(scope.blueprints())
            assertTrue(recipes.isNotEmpty())
            confirmed(scope.act(edited, CronAction.Delete))
            assertTrue(confirmed(scope.jobs()).isEmpty())
            val unicodePrompt = "😀".repeat(30) + " daily report"
            val unicodeJob = confirmed(scope.create(CronDraft(prompt = unicodePrompt)))
            assertEquals(unicodePrompt, unicodeJob.name)
            confirmed(scope.act(unicodeJob, CronAction.Delete))
            assertTrue(confirmed(scope.jobs()).isEmpty())

            // Seed a real script job through the genuine REST endpoint because
            // the product's manual editor intentionally creates agent jobs only.
            for (repeatValue in listOf("forever", "1")) {
                val body = buildJsonObject {
                    put("name", "Kotlin script fixture"); put("prompt", ""); put("schedule", "0 9 * * *")
                    put("deliver", "local"); put("model", JsonNull); put("provider", JsonNull)
                    put("script", "harmless.sh"); put("no_agent", true); put("repeat", repeatValue); put("paused", false)
                }.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$base/api/cron/jobs?profile=worker_alpha")
                    .header("X-Hermes-Session-Token", token!!).post(body).build()
                val id = client.newCall(request).execute().use { response ->
                    assertEquals(200, response.code)
                    Json.parseToJsonElement(response.body!!.string()).jsonObject.getValue("id").jsonPrimitive.content
                }
                val script = confirmed(scope.detail(id))
                assertTrue(script.scriptOnly)
                confirmed(scope.act(script, CronAction.Trigger))
                val runs = confirmed(scope.runs(id))
                assertTrue(runs.isNotEmpty())
                assertTrue(runs.all { !it.isConversation && it.source == "cron_output" })
                assertTrue(runs.any { "HM_CRON_SCRIPT_OK" in it.preview })
                if (repeatValue == "1") {
                    val completed = confirmed(scope.detail(id))
                    assertEquals("completed", completed.state)
                    assertFalse(completed.enabled)
                }
                confirmed(scope.act(script, CronAction.Delete))
                assertTrue(confirmed(scope.jobs()).isEmpty())
            }
        } finally {
            scope.close(); default.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
