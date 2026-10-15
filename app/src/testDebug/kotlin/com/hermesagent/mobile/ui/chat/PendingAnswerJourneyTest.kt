package com.hermesagent.mobile.ui.chat

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.theme.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Historical single-request compatibility; target-shaped batch and canonical prompts have separate regressions. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingAnswerJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `rendered choice reaches original socket and releases next send`() = journey()

    @Test
    fun `rendered typed answer reaches original socket and releases next send`() = journey(typed = true)

    @Test
    fun `failed answer stays visible and deliberate retry sends once`() = journey(failFirst = true)

    @Test
    fun `target batch stages then sequential locks release next send`() = journey(batch = true)

    @Test
    fun `canonical clarify card confirms exact owner generation and releases next send`() = journey(canonical = true)

    @Test
    fun `canonical snapshot restores same staged clarify response path`() = journey(canonical = true, restored = true)

    @Test
    fun `native secret before canonical resume acknowledgement remains answerable`() = journey(canonical = true, earlySecret = true)

    @Test
    fun `canonical prompt between snapshot capture and resume acknowledgement survives`() = journey(canonical = true, earlyPrompt = true)

    @Test
    fun `authority native batch responds once with nullable answers and submitted outcome`() = journey(canonical = true, batch = true, nativeBatch = true)

    private fun journey(typed: Boolean = false, failFirst: Boolean = false, batch: Boolean = false, canonical: Boolean = false, restored: Boolean = false, earlySecret: Boolean = false, earlyPrompt: Boolean = false, nativeBatch: Boolean = false) = runTest {
        val frames = mutableListOf<JsonObject>()
        var refuseAnswer = failFirst
        val remaining = linkedSetOf("q1", "q2")
        var secretSent = false
        var promptSent = false
        lateinit var rpc: CorrelatedGatewayRpc
        rpc = CorrelatedGatewayRpc(object : GatewayRpcWire {
            override fun send(text: String): Boolean {
                val frame = Json.parseToJsonElement(text).jsonObject
                if (refuseAnswer && frame["id"] == JsonPrimitive("srq-public") && "result" in frame) {
                    refuseAnswer = false
                    return false
                }
                frames += frame
                val method = frame["method"]?.jsonPrimitive?.content
                if (method != null) {
                    if (method == "session.resume" && earlySecret && !secretSent) {
                        secretSent = true
                        rpc.receive("""{"jsonrpc":"2.0","id":"public-secret","method":"secret","params":{"session_id":"runtime-a","env_var":"PUBLIC_SYNTHETIC","prompt":"Public fixture value"}}""")
                    }
                    if (method == "session.resume" && earlyPrompt && !promptSent) {
                        promptSent = true
                        rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"clarify.request","session_id":"runtime-a","authority_epoch":2,"execution_generation":4,"replay_epoch":"public-epoch","seq":1,"payload":{"kind":"clarify","prompt_id":"public-prompt","execution_generation":4,"question":"Proceed?","choices":["Yes","No"],"multi_select":false}}}""")
                    }
                    val result = when (method) {
                        "session.list" -> """{"sessions":[]}"""
                        "runtime.describe" -> """{"instance_id":"public-instance","profile_id":"public-owner","authority_epoch":2,"capabilities":["durable-admission-v1","event-replay-v1"]}"""
                        "clarify.respond" -> """{"status":"resolved","prompt_id":"public-prompt"}"""
                        "prompt.submit" -> if (canonical) """{"admission_id":"public-admission","ref":{"profile_id":"public-owner","session_id":"runtime-a"},"sequence":1,"status":"started","outcome":null,"authority_epoch":2,"execution_generation":5}""" else "{}"
                        "session.resume" -> if (canonical) """{"session_id":"runtime-a","stored_session_id":"durable-a","messages":[],"display_messages":[],"message_count":0,"running":false,"authority_epoch":2,"replay_epoch":"public-epoch","last_sequence":${if (restored) 1 else 0},"subscription_id":"public-subscription","revision":7,"execution_generation":${if (restored) 4 else 3},"pending":[],"prompts":${if (restored) """[{"kind":"clarify","prompt_id":"public-prompt","execution_generation":4,"question":"Proceed?","choices":["Yes","No"],"multi_select":false}]""" else "[]"},"info":{"desktop_protocol":"hermes-gateway-v1","profile_id":"public-owner","profile_name":"work","model":"public-model","cwd":"/workspace/public"}}""" else """{"session_id":"runtime-a","resumed":"durable-a","session_key":"durable-a","messages":[],"running":false,"status":"idle","info":{"model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true}}"""
                        "session.history" -> """{"messages":[],"count":0}"""
                        "clarify.lock" -> {
                            remaining -= frame.getValue("params").jsonObject.getValue("question_id").jsonPrimitive.content
                            buildJsonObject { put("status", "ok"); put("remaining", JsonArray(remaining.map(::JsonPrimitive))) }.toString()
                        }
                        else -> "{}"
                    }
                    rpc.receive("""{"jsonrpc":"2.0","id":${frame.getValue("id")},"result":$result}""")
                }
                return true
            }
            override fun close() = Unit
        }, eventPumpDispatcher = UnconfinedTestDispatcher(testScheduler))
        if (canonical) rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""")
        val cache = SessionCache()
        cache.upsertSessions(listOf(SessionSummary("durable-a", "Synthetic chat", "", 1800000000000)))
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { 1800000000000 }
        val viewModel = ChatViewModel(cache, repository, clock = { 1800000000000 })
        val actions = ChatActions(onRespondToPendingInput = viewModel::respondToPendingInput,
            onDraftChange = viewModel::setDraft, onSend = viewModel::submit,
            onSelectSession = viewModel::selectSession)
        compose.setContent {
            val state by viewModel.uiState.collectAsState()
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                ChatScreen(state, actions, onOpenSettings = {})
            }
        }
        runCurrent()
        compose.waitForIdle()
        repository.openSession("durable-a")
        runCurrent()
        compose.waitForIdle()
        if (earlySecret) {
            val secret = repository.pendingInputs.value.values.single() as SecretPending
            val value = "public-synthetic-value".toCharArray()
            assertEquals(PendingInputResponse.Resolved, repository.respondToPendingInput(secret.key, PendingInputAction.SecretValue(value)))
            assertTrue(value.all { it == '\u0000' })
            assertEquals(1, frames.count { it["id"] == JsonPrimitive("public-secret") && "result" in it })
        }
        val question = if (batch) """{"questions":[{"qid":"q1","question":"Proceed?","choices":["Yes","No"],"multi_select":false},{"qid":"q2","question":"Additional detail?","choices":[],"multi_select":false}]}"""
            else """{"question":"Proceed?","choices":["Yes","No"],"multi_select":false}"""
        if (canonical && !restored && !earlyPrompt && !nativeBatch) rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"clarify.request","session_id":"runtime-a","authority_epoch":2,"execution_generation":4,"replay_epoch":"public-epoch","seq":1,"payload":{"kind":"clarify","prompt_id":"public-prompt","execution_generation":4,"question":"Proceed?","choices":["Yes","No"],"multi_select":false}}}""")
        else if (!canonical || nativeBatch) rpc.receive("""{"jsonrpc":"2.0","id":"srq-public","method":"clarify","params":{"session_id":"runtime-a",${question.drop(1)}}""")
        runCurrent()
        compose.waitForIdle()
        val renderedKey = repository.pendingInputs.value.keys.single()
        if (typed) {
            compose.onNodeWithContentDescription("Your answer").performTextInput("Public answer")
            compose.onNodeWithText("Send").performClick()
        } else {
            compose.onNodeWithText("Yes").performClick()
        }
        runCurrent()
        compose.waitForIdle()
        if (nativeBatch) {
            compose.onNodeWithText("Confirm and continue").performClick()
            runCurrent()
            compose.waitForIdle()
            assertTrue(frames.none { it["method"] == JsonPrimitive("clarify.lock") })
            val response = frames.single { it["id"] == JsonPrimitive("srq-public") && "result" in it }.getValue("result").jsonObject
            assertEquals(JsonPrimitive("submitted"), response["outcome"])
            assertEquals(JsonPrimitive("Yes"), response.getValue("answers").jsonObject["q1"])
            assertEquals(JsonNull, response.getValue("answers").jsonObject["q2"])
        } else if (canonical) {
            assertTrue(frames.none { it["method"] == JsonPrimitive("clarify.respond") })
            compose.onNodeWithText("Confirm and continue").performClick()
            runCurrent()
            compose.waitForIdle()
            val response = frames.single { it["method"] == JsonPrimitive("clarify.respond") }.getValue("params").jsonObject
            assertEquals(JsonPrimitive("runtime-a"), response["session_id"])
            assertEquals(JsonPrimitive("public-prompt"), response["prompt_id"])
            assertEquals(JsonPrimitive(4), response["execution_generation"])
            assertEquals(JsonPrimitive("Yes"), response["answer"])
        } else if (batch) {
            assertTrue("staging must not send any lock", frames.none { it["method"] == JsonPrimitive("clarify.lock") })
            compose.onNodeWithText("Confirm and continue").performClick()
            runCurrent()
            compose.waitForIdle()
            val locks = frames.filter { it["method"] == JsonPrimitive("clarify.lock") }.map { it.getValue("params").jsonObject }
            assertEquals(listOf("q1", "q2"), locks.map { it.getValue("question_id").jsonPrimitive.content })
            assertEquals(JsonNull, locks.last()["answer"])
        } else {
            if (failFirst) {
                compose.onNodeWithText("Could not send clarify response").assertExists()
                assertEquals(1, repository.pendingInputs.value.size)
                compose.onNodeWithText("Yes").performClick()
                runCurrent()
                compose.waitForIdle()
            }
            val responses = frames.filter { it["id"] == JsonPrimitive("srq-public") && "result" in it }
            assertEquals("rendered answer must leave through real correlated RPC", 1, responses.size)
            assertEquals(if (typed) "Public answer" else "Yes", responses.single().getValue("result").jsonObject.getValue("answer").jsonPrimitive.content)
        }
        assertTrue(repository.pendingInputs.value.isEmpty())
        // A duplicate/delayed handler call cannot dispatch again or wipe a new request.
        val beforeDuplicate = frames.size
        viewModel.respondToPendingInput(renderedKey, PendingInputAction.ClarifyAnswer(mapOf("" to "No")))
        val rejectedSecret = "public-synthetic-value".toCharArray()
        viewModel.respondToPendingInput(renderedKey, PendingInputAction.SecretValue(rejectedSecret))
        runCurrent()
        assertEquals(beforeDuplicate, frames.size)
        assertTrue(rejectedSecret.all { it == '\u0000' })
        rpc.receive(if (canonical) """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"runtime-a","authority_epoch":2,"execution_generation":4,"replay_epoch":"public-epoch","seq":2,"payload":{"text":"Public completion"}}}"""
            else """{"jsonrpc":"2.0","method":"event","params":{"type":"turn.end","session_id":"runtime-a","payload":{}}}""")
        runCurrent()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Message Hermes").performTextInput("Next public prompt")
        compose.onNodeWithContentDescription("Send message").performClick()
        runCurrent()
        compose.waitForIdle()
        assertEquals(1, frames.count { it["method"] == JsonPrimitive("prompt.submit") })
        rpc.close()
    }
}
