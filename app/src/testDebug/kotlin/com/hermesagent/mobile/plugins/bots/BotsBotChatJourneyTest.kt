package com.hermesagent.mobile.plugins.bots

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import kotlinx.serialization.json.*
import kotlinx.coroutines.test.runCurrent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renders the Bot roster row and ChatScreen handoff, not Activity navigation or recreation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class BotsBotChatJourneyTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun `row action remains visibly loading until resume then renders the writable Bot Chat`() {
        var roster by mutableStateOf(rosterState())
        var route by mutableStateOf(Route.Roster)
        var completion: ((Boolean) -> Unit)? = null
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                when (route) {
                    Route.Roster -> BotsRosterScreen(
                        state = roster,
                        onBack = {},
                        onOpenBotChat = { row ->
                            roster = roster.copy(openingBotKey = row.rosterKey)
                            completion = { opened ->
                                roster = roster.copy(openingBotKey = null)
                                if (opened) route = Route.Chat else roster = roster.copy(botChatMessage = FAILED)
                            }
                        },
                    )
                    Route.Chat -> ChatScreen(botChat(), ChatActions(), onOpenSettings = {})
                    Route.OrdinaryChat -> ChatScreen(chat(botChat = false), ChatActions(), onOpenSettings = {})
                }
            }
        }

        compose.onNodeWithText("Researcher").performClick()
        compose.onNodeWithContentDescription("Opening Bot Chat").assertIsDisplayed()
        compose.onNodeWithText("Researcher").assertIsDisplayed()

        compose.runOnIdle { completion!!.invoke(true) }
        compose.onNodeWithText("canonical transcript").assertIsDisplayed()
        // Phase B renders the ordinary composer in a Bot Chat: the person's
        // first prompt is what arms live delivery, so the send affordance has
        // to be here. The Phase A notice is gone.
        compose.onNodeWithTag("Composer field shell").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send message").assertIsDisplayed()
        compose.onAllNodesWithText("Bot Chat is read-only on mobile.").assertCountEquals(0)

        compose.runOnIdle { route = Route.OrdinaryChat }
        compose.onNodeWithTag("Composer field shell").assertIsDisplayed()
        compose.onNodeWithText("canonical transcript").assertIsDisplayed()
    }

    @Test
    fun `failed resume keeps the roster and shows fixed safe copy`() {
        var roster by mutableStateOf(rosterState())
        var completion: ((Boolean) -> Unit)? = null
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                BotsRosterScreen(
                    state = roster,
                    onBack = {},
                    onOpenBotChat = { row ->
                        roster = roster.copy(openingBotKey = row.rosterKey)
                        completion = { opened ->
                            roster = roster.copy(
                                openingBotKey = null,
                                botChatMessage = if (opened) null else FAILED,
                            )
                        }
                    },
                )
            }
        }

        compose.onNodeWithText("Researcher").performClick()
        compose.onNodeWithContentDescription("Opening Bot Chat").assertIsDisplayed()
        compose.runOnIdle { completion!!.invoke(false) }
        compose.onNodeWithText("Researcher").assertIsDisplayed()
        compose.onNodeWithText(FAILED).assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Opening Bot Chat").assertCountEquals(0)
    }

    /** Synthetic wire -> canonical repository -> both ViewModels -> mounted ChatScreen. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `canonical Bot Chat collapses consecutive historical and current wrapping user bubbles`() = canonicalDisclosure(false)

    @Test fun `canonical Bot Chat light route collapses historical and current wrapping user bubbles`() = canonicalDisclosure(true)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun canonicalDisclosure(light: Boolean) = kotlinx.coroutines.test.runTest {
        val bodies = listOf("Earlier synthetic wrapping words without line breaks. ".repeat(12).trimEnd(),
            "Current synthetic wrapping words without line breaks. ".repeat(12).trimEnd())
        val frames = mutableListOf<kotlinx.serialization.json.JsonObject>()
        lateinit var rpc: com.hermesagent.mobile.data.gateway.CorrelatedGatewayRpc
        rpc = com.hermesagent.mobile.data.gateway.CorrelatedGatewayRpc(object : com.hermesagent.mobile.data.gateway.GatewayRpcWire {
            override fun send(text: String): Boolean {
                val frame = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
                frames += frame
                val method = frame["method"]?.jsonPrimitive?.content ?: return true
                val result = when (method) {
                    "profiles.list" -> """{"profiles":[{"name":"researcher","display_name":"Researcher"}]}"""
                    "session.list" -> if (frame["params"]?.jsonObject?.get("profile") == JsonPrimitive("researcher"))
                        """{"sessions":[{"id":"bot-chat","title":"Bot Chat"}]}""" else """{"sessions":[]}"""
                    "session.resume" -> """{"session_id":"runtime-bot","session_key":"bot-chat","title":"Bot Chat","running":false}"""
                    "session.history" -> kotlinx.serialization.json.buildJsonObject {
                        put("messages", kotlinx.serialization.json.buildJsonArray {
                            bodies.forEachIndexed { index, body -> add(kotlinx.serialization.json.buildJsonObject {
                                put("role", "user"); put("text", body); put("row_id", index + 1)
                            }) }
                            add(kotlinx.serialization.json.buildJsonObject { put("role", "assistant"); put("text", "canonical transcript\n\n" + "Synthetic assistant paragraph.\n\n".repeat(50)); put("row_id", 3) })
                        }); put("count", 3)
                    }.toString()
                    else -> "{}"
                }
                rpc.receive("""{"jsonrpc":"2.0","id":${frame.getValue("id")},"result":$result}""")
                return true
            }
            override fun close() = Unit
        }, eventPumpDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        try {
            val clients = kotlinx.coroutines.flow.MutableStateFlow<com.hermesagent.mobile.data.gateway.GatewayRpcClient?>(rpc)
            val cache = com.hermesagent.mobile.data.session.SessionCache()
            val repository = com.hermesagent.mobile.data.gateway.LiveGatewaySessionRepository(cache,
                kotlinx.coroutines.flow.MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)), clients, backgroundScope) { 1L }
            val chat = com.hermesagent.mobile.ui.chat.ChatViewModel(cache, repository, clock = { 1L })
            val host = com.hermesagent.mobile.plugins.GatewayPluginHost(backgroundScope, clients)
            val bots = BotsViewModel(BotsPluginRepository(host), backgroundScope, connected = kotlinx.coroutines.flow.MutableStateFlow(true))
            bots.refreshNow()
            var opened by mutableStateOf(false)
            lateinit var measurer: androidx.compose.ui.text.TextMeasurer
            lateinit var style: androidx.compose.ui.text.TextStyle
            var density = 1f
            compose.setContent {
                val roster by bots.uiState.collectAsState()
                val chatState by chat.uiState.collectAsState()
                HermesTheme(AppearanceSelection("nous", if (light) com.hermesagent.mobile.ui.theme.HermesThemeMode.Light else com.hermesagent.mobile.ui.theme.HermesThemeMode.Dark)) {
                    measurer = androidx.compose.ui.text.rememberTextMeasurer()
                    style = HermesTheme.type.body
                    density = androidx.compose.ui.platform.LocalDensity.current.density
                    if (opened) ChatScreen(chatState, ChatActions(), {}) else BotsRosterScreen(roster, {}, onOpenBotChat = { row ->
                        bots.openBotChat(row) { profile, id, finished -> chat.openBotChat(profile, id) { accepted -> finished(accepted); opened = accepted } }
                    })
                }
            }
            runCurrent()
            compose.waitForIdle()
            compose.onNodeWithText("Researcher").performClick()
            runCurrent()
            compose.waitForIdle()
            org.junit.Assert.assertTrue("canonical admission: frames=$frames state=${chat.uiState.value} roster=${bots.uiState.value}", chat.uiState.value.botChat)
            compose.onNodeWithTag("Composer field shell").assertIsDisplayed()
            org.junit.Assert.assertTrue(frames.any { it["method"] == kotlinx.serialization.json.JsonPrimitive("session.resume") })
            val defaultHeights = mutableListOf<Pair<Float, Float>>()
            bodies.forEachIndexed { index, body ->
                compose.onNodeWithTag("Transcript").performScrollToIndex(index)
                compose.onNodeWithTag("Transcript").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, -10000f) }
                compose.waitForIdle()
                val owner = compose.onNodeWithContentDescription("You said: $body").fetchSemanticsNode()
                val layout = measurer.measure(body, style, maxLines = 3,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = (owner.size.width - 24 * density).toInt()))
                org.junit.Assert.assertTrue("synthetic body must overflow rendered lines: size=${owner.size} density=$density lines=${layout.lineCount} layout=${layout.size}", layout.didOverflowHeight)
                defaultHeights += (layout.size.height + 18 * density) to owner.size.height.toFloat()
            }
            org.junit.Assert.assertTrue("every mounted Bot user body must default to three rendered lines: expected/actual=$defaultHeights",
                defaultHeights.all { (expected, actual) -> kotlin.math.abs(expected - actual) <= 1f })
            bodies.forEach { body -> org.junit.Assert.assertEquals("Expand prompt",
                compose.onNodeWithContentDescription("You said: $body").fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].label) }
            compose.runOnIdle {
                com.hermesagent.mobile.ui.saveNativeCapture(compose.activity, "bot-users-${if (light) "light" else "dark"}-collapsed.png")
            }
            // Real touch disclosure, not the historical helper that chooses Return.
            compose.onNodeWithContentDescription("You said: ${bodies.last()}").performTouchInput { click() }
            compose.runOnIdle {
                com.hermesagent.mobile.ui.saveNativeCapture(compose.activity, "bot-users-${if (light) "light" else "dark"}-expanded.png")
            }
        } finally { rpc.close() }
    }

    private fun rosterState() = BotsRosterUiState(
        phase = BotsRosterPhase.Ready,
        sections = listOf(
            BotSectionBlock(null, "section:unassigned", "Unassigned", listOf(BotRosterRow(name = "researcher"))),
        ),
    )

    private fun botChat() = chat(botChat = true)

    private fun chat(botChat: Boolean) = ChatUiState(
        activeSession = SessionSummary("bot-chat", "Bot Chat", "", 1L),
        transcript = listOf(AssistantTurn("reply", "canonical transcript", 1L)),
        connection = GatewayConnectionState(GatewayConnectionStatus.Connected),
        botChat = botChat,
    )

    private enum class Route { Roster, Chat, OrdinaryChat }

    private companion object {
        const val FAILED = "Bot Chat could not be opened. Check the Gateway and try again."
    }
}
