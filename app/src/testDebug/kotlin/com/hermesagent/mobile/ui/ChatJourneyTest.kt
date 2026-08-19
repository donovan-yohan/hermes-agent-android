package com.hermesagent.mobile.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatViewModel
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Compose semantics over live-repository-shaped state; no demo engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatJourneyTest {
    @get:Rule
    val compose = createComposeRule()

    private val cache = SessionCache()
    private lateinit var repository: JourneyRepository
    private lateinit var viewModel: ChatViewModel
    private var themeName by mutableStateOf(BuiltinThemes.DEFAULT_NAME)

    private fun launch(connected: Boolean = true, withSessions: Boolean = true) {
        if (withSessions) {
            cache.upsertSessions(
                listOf(
                    SessionSummary("live-a", "Remote planning", "Gateway preview", NOW),
                    SessionSummary("live-b", "Second remote session", "Other preview", NOW - 86_400_000),
                ),
            )
            cache.setTranscript(
                "live-a",
                listOf(
                    UserTurn("row-u", "What is live?", NOW - 2),
                    AssistantTurn("row-a", "Live reply from Gateway", NOW - 1),
                ),
            )
            cache.setTranscript("live-b", listOf(AssistantTurn("row-b", "Second live transcript", NOW - 1)))
        }
        repository = JourneyRepository(cache, connected)
        viewModel = ChatViewModel(cache, repository, clock = { NOW })

        compose.setContent {
            val state by viewModel.uiState.collectAsState()
            HermesTheme(AppearanceSelection(themeName, HermesThemeMode.Dark)) {
                ChatScreen(
                    state = state,
                    actions = ChatActions(
                        onQueryChange = viewModel::setQuery,
                        onDraftChange = viewModel::setDraft,
                        onSelectSession = viewModel::selectSession,
                        onCreateSession = viewModel::createSession,
                        onSend = viewModel::submit,
                        onStop = viewModel::stop,
                    ),
                    onOpenSettings = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `chat opens on newest backend session with connected state`() {
        launch()
        assertTrue(compose.countWithText("Remote planning") >= 1)
        compose.onNodeWithText("Live reply from Gateway").assertIsDisplayed()
        assertTrue(compose.countWithText("Connected") >= 1)
    }

    @Test
    fun `drawer searches and resumes selected durable session`() {
        launch()
        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithContentDescription("Search sessions").performTextInput("second")
        assertEquals(1, compose.countWithText("Second remote session"))
        assertEquals(0, compose.countWithText("Gateway preview"))

        compose.onNodeWithText("Second remote session").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Second live transcript").assertIsDisplayed()
        assertTrue(repository.opened.contains("live-b"))
    }

    @Test
    fun `send uses live repository and create opens its returned durable session`() {
        launch()
        compose.onNodeWithContentDescription("Message Hermes").performTextInput("send through Gateway")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.waitForIdle()
        assertEquals(listOf("live-a" to "send through Gateway"), repository.submitted)
        assertEquals("send through Gateway", (cache.transcript("live-a").last() as UserTurn).text)

        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithContentDescription("New session").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No messages yet").assertIsDisplayed()
        assertEquals("created-live", viewModel.uiState.value.activeSession?.id)
    }

    @Test
    fun `disconnected chat shows truthful status and disables send`() {
        launch(connected = false)
        cache.upsertSession(cache.session("live-a")!!.copy(status = com.hermesagent.mobile.data.session.SessionStatus.Working))
        compose.waitForIdle()
        compose.onNodeWithText("Disconnected").assertIsDisplayed()
        assertEquals(0, compose.countWithText("Streaming · Connected"))
        compose.onNodeWithContentDescription("Message Hermes").performTextInput("not sent")
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        assertTrue(repository.submitted.isEmpty())
    }

    @Test
    fun `fresh disconnected chat disables new session and points to Gateway setup`() {
        launch(connected = false, withSessions = false)

        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithContentDescription("New session").assertIsNotEnabled()
        compose.onNodeWithText("Connect to a Gateway to start a session.").assertIsDisplayed()
    }

    @Test
    fun `tool completion keeps concise duration while stopped tools do not look successful`() {
        launch()
        cache.setTranscript(
            "live-a",
            listOf(
                ToolActivity("tool-whole", "Whole", "done", ToolState.Done, 2.0),
                ToolActivity("tool-fraction", "Fraction", "done", ToolState.Done, 1.234),
                ToolActivity("tool-stopped", "Stopped", "partial", ToolState.Stopped, 7.5),
            ),
        )
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Tool Whole, done").assertIsDisplayed()
        compose.onNodeWithContentDescription("Tool Fraction, done").assertIsDisplayed()
        compose.onNodeWithContentDescription("Tool Stopped, stopped").assertIsDisplayed()
        compose.onNodeWithText("2s").assertIsDisplayed()
        compose.onNodeWithText("1.2s").assertIsDisplayed()
        assertEquals(0, compose.countWithText("7.5s"))
    }

    @Test
    fun `another running turn keeps stream ownership and disables a second submit`() {
        launch()
        cache.upsertSession(cache.session("live-a")!!.copy(status = com.hermesagent.mobile.data.session.SessionStatus.Working))
        viewModel.selectSession("live-b")
        compose.waitForIdle()

        compose.onNodeWithText("Wait for the running turn to finish").assertIsDisplayed()
        compose.onNodeWithContentDescription("Message Hermes").performTextInput("not ambiguous")
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        assertTrue(repository.submitted.isEmpty())
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1000dp-h800dp")
    fun `wide layout keeps persistent sessions rail`() {
        launch()
        compose.onNodeWithText("Sessions").assertIsDisplayed()
        compose.onNodeWithText("Second remote session").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Open sessions")).fetchSemanticsNodes().size)
    }

    @Test
    fun `every builtin theme renders live transcript`() {
        launch()
        for (preset in BuiltinThemes.ALL) {
            themeName = preset.name
            compose.waitForIdle()
            compose.onNodeWithText("Live reply from Gateway").assertIsDisplayed()
        }
    }

    private class JourneyRepository(private val cache: SessionCache, connected: Boolean) : GatewaySessionRepository {
        override val connectionState = MutableStateFlow(
            GatewayConnectionState(
                if (connected) GatewayConnectionStatus.Connected else GatewayConnectionStatus.Disconnected,
            ),
        )
        val opened = mutableListOf<String>()
        val submitted = mutableListOf<Pair<String, String>>()

        override suspend fun refreshSessions() = Unit
        override suspend fun openSession(durableId: String): String {
            opened += durableId
            return durableId
        }

        override suspend fun createSession(): String {
            cache.upsertSession(SessionSummary("created-live", "New session", "", NOW + 1))
            return "created-live"
        }

        override suspend fun submit(durableId: String, text: String): GatewaySubmitOutcome {
            submitted += durableId to text
            cache.appendEntry(durableId, UserTurn("submitted", text, NOW))
            return GatewaySubmitOutcome.Accepted
        }

        override suspend fun interrupt(durableId: String) = Unit
    }

    private companion object {
        const val NOW = 1_755_600_000_000L
    }
}

private fun ComposeContentTestRule.countWithText(text: String, substring: Boolean = false): Int =
    onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size
