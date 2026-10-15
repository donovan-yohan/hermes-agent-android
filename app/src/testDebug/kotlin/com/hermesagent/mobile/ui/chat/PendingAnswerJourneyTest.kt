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

    private fun journey(typed: Boolean = false, failFirst: Boolean = false, batch: Boolean = false) = runTest {
        val frames = mutableListOf<JsonObject>()
        var refuseAnswer = failFirst
        val remaining = linkedSetOf("q1", "q2")
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
                    val result = when (method) {
                        "session.list" -> """{"sessions":[]}"""
                        "session.resume" -> """{"session_id":"runtime-a","resumed":"durable-a","session_key":"durable-a","messages":[],"running":false,"status":"idle","info":{"model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true}}"""
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
        val question = if (batch) """{"questions":[{"qid":"q1","question":"Proceed?","choices":["Yes","No"],"multi_select":false},{"qid":"q2","question":"Additional detail?","choices":[],"multi_select":false}]}"""
            else """{"question":"Proceed?","choices":["Yes","No"],"multi_select":false}"""
        rpc.receive("""{"jsonrpc":"2.0","id":"srq-public","method":"clarify","params":{"session_id":"runtime-a",${question.drop(1)}}""")
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
        if (batch) {
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
        rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"turn.end","session_id":"runtime-a","payload":{}}}""")
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
