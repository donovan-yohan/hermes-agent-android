package com.hermesagent.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermesagent.mobile.data.gateway.GatewayEvent
import com.hermesagent.mobile.data.gateway.GatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayRpcError
import com.hermesagent.mobile.plugins.ContributionRegistry
import com.hermesagent.mobile.plugins.GatewayPluginHost
import com.hermesagent.mobile.plugins.PluginAreas
import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginNotificationInput
import com.hermesagent.mobile.plugins.PluginOs
import com.hermesagent.mobile.plugins.PluginRest
import com.hermesagent.mobile.plugins.PluginRestOptions
import com.hermesagent.mobile.plugins.PluginRestResult
import com.hermesagent.mobile.plugins.PluginSocket
import com.hermesagent.mobile.plugins.PluginStorage
import com.hermesagent.mobile.plugins.bots.BotMeta
import com.hermesagent.mobile.plugins.bots.BotRosterRow
import com.hermesagent.mobile.plugins.bots.BotSection
import com.hermesagent.mobile.plugins.bots.BotsPlugin
import com.hermesagent.mobile.plugins.bots.BotsRosterCopy
import com.hermesagent.mobile.plugins.bots.STALE_TAG
import com.hermesagent.mobile.plugins.createPluginContext
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesSpacing
import com.hermesagent.mobile.ui.common.WIP_SPOKEN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Bots roster as a person walks it.
 *
 * Every state below is driven through the real surface — the plugin's own
 * route contribution, its own host door, and the real live-connection slot —
 * rather than by asserting on the data structures behind it, so what is proved
 * is what is rendered.
 *
 * The seven states are audit section 1.5's: loading, error-with-no-roster,
 * true-empty, all-hidden, filtered-to-nothing, stale-but-showing-last-good, and
 * the normal sectioned list.
 *
 * The viewport is pinned to a real phone's (`w411dp-h891dp`). Robolectric's
 * default is 320x470px at density 1 — shorter than any phone this ships on —
 * and on it the action under a state's own message (`Show hidden bots`,
 * `Clear filters`) lays out past the window, where a click cannot reach it.
 * That is a property of the default viewport, not of the surface: the same
 * fixtures render both actions inside a phone-sized window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class BotsRosterJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The live connection slot, exactly as `HermesApplication` hands it to the
     * door: null until the app has a leg it can actually use.
     */
    private val clients = MutableStateFlow<GatewayRpcClient?>(null)

    private lateinit var pluginScope: CoroutineScope

    @Before
    fun setUp() {
        pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @After
    fun tearDown() {
        pluginScope.cancel()
    }

    /** One connection leg's client. No test here reaches a socket. */
    private class FakeRpc(
        private val answer: (String, JsonObject) -> JsonElement,
    ) : GatewayRpcClient {
        override val events: Flow<GatewayEvent> = emptyFlow()

        override suspend fun request(method: String, params: JsonObject): JsonElement =
            answer(method, params)

        override fun close() {}
    }

    /**
     * The cold start's recovery, which is the whole point of the slice: the
     * roster is entered before the Gateway is up, and it fills itself in when
     * the connection lands rather than staying on the error card until the app
     * restarts.
     */
    @Test
    fun `a cold start waits for the gateway and the roster arrives with the connection`() {
        launch()

        compose.onNodeWithText(BotsRosterCopy.WAITING_FOR_GATEWAY).assertIsDisplayed()

        clients.value = rosterRpc()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Researcher").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Researcher").assertIsDisplayed()
        // The primary profile is literally named `default`; the roster never
        // says that word — its handle is `hermes` (`data.ts:952-963` @ the pin).
        compose.onNodeWithText("@hermes").assertIsDisplayed()
        compose.onNodeWithText(BotsRosterCopy.WAITING_FOR_GATEWAY).assertDoesNotExist()
    }

    /**
     * The same edge, a refresh later: a bot created on the Gateway after the
     * first read appears when the connection comes back, with the list the
     * person already had still on screen and the banner that says so.
     */
    @Test
    fun `a reconnect refreshes the roster and a failed refresh keeps the last good list`() {
        val owner = TestOwner()
        clients.value = rosterRpc()
        launch(owner = owner)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Researcher").fetchSemanticsNodes().isNotEmpty()
        }

        // The Gateway adds a bot, then starts refusing: the surface keeps the
        // rows it has and says they are old.
        clients.value = FakeRpc { _, _ -> throw GatewayRpcError(500, "boom") }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(STALE_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(BotsRosterCopy.refreshFailed(connectionUp = true)).assertIsDisplayed()
        compose.onNodeWithText("Researcher").assertIsDisplayed()
        compose.onNodeWithText(BotsRosterCopy.WAITING_FOR_GATEWAY).assertDoesNotExist()
    }

    /** Error with no roster: a failure, its own sentence, and one way out. */
    @Test
    fun `an answered failure with nothing held is the error state with a retry`() {
        clients.value = FakeRpc { _, _ -> throw GatewayRpcError(500, "boom") }
        launch()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(BotsRosterCopy.RETRY_NOW).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(
            BotsRosterCopy.rosterUnavailable("Hermes refused that Gateway request."),
        ).assertIsDisplayed()
        compose.onNodeWithText(BotsRosterCopy.RETRY_NOW).assertIsDisplayed()
    }

    @Test
    fun `activity toasts stays visible as a disabled marked Desktop affordance`() {
        clients.value = rosterRpc()
        launch()

        compose.onNodeWithContentDescription("Activity toasts off — click to enable. $WIP_SPOKEN")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assertHeightIsAtLeast(HermesSpacing().touchTarget)
    }

    /** A Gateway without `profiles.list` says so, and closes the entry point. */
    @Test
    fun `a gateway that predates profiles dot list closes the entry point`() {
        clients.value = FakeRpc { _, _ -> throw GatewayRpcError(-32601, "unknown method") }
        launch(renderSidebarEntry = true)

        val closed = BotsRosterCopy.rosterUnavailable(PREDATES_REASON)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Bots. $closed").fetchSemanticsNodes().isNotEmpty()
        }
        // A statement, not a door: the row is there, spoken, and disabled.
        compose.onNodeWithTag("settings-row-bots").assertIsDisplayed()
        compose.onNodeWithContentDescription("Bots. $closed").assertIsNotEnabled()
    }

    /** True empty: the Gateway answered with nothing. */
    @Test
    fun `an empty roster is the empty state`() {
        clients.value = FakeRpc { _, _ -> Json.parseToJsonElement("""{"profiles": []}""") }
        launch()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(BotsRosterCopy.EMPTY_DESC).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(BotsRosterCopy.EMPTY_TITLE).assertIsDisplayed()
        compose.onNodeWithText(BotsRosterCopy.EMPTY_DESC).assertIsDisplayed()
    }

    /** All hidden is its own explainer, and the reveal brings the rows back. */
    @Test
    fun `hiding every bot is its own state and revealing them draws them`() {
        val hidden = listOf("default", "researcher")
            .associate { BotRosterRow(name = it).rosterKey to BotMeta(hidden = true) }
        clients.value = rosterRpc()
        launch(metaByKey = hidden)

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(BotsRosterCopy.ALL_HIDDEN).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(BotsRosterCopy.ALL_HIDDEN_DESC).assertIsDisplayed()

        compose.onNodeWithText(BotsRosterCopy.SHOW_HIDDEN).performClick()

        awaitText("Researcher")
        compose.onNodeWithText("Researcher").assertIsDisplayed()
    }

    /** Filtered to nothing: the roster is there, the filters match none of it. */
    @Test
    fun `filters that match nothing say so and clear`() {
        clients.value = rosterRpc("default", "researcher", "writer", "planner", "editor", "reviewer", "runner", "watcher")
        launch()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Filter roster").fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Filter roster").performTouchInput { click() }
        listOf(
            BotsRosterCopy.BOTS_AND_GROUPS,
            BotsRosterCopy.BOTS_ONLY,
            BotsRosterCopy.GROUPS_ONLY,
            BotsRosterCopy.ANY_ACTIVITY,
            BotsRosterCopy.ACTIVE_NOW,
            BotsRosterCopy.RECENTLY_ACTIVE,
            BotsRosterCopy.OLDER,
        ).forEach { label -> compose.onNodeWithText(label).assertIsDisplayed() }
        compose.onNodeWithText(BotsRosterCopy.GROUPS_ONLY).performClick()
        compose.onAllNodesWithText(BotsRosterCopy.BOTS_ONLY).assertCountEquals(0)

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(BotsRosterCopy.NO_MATCH_FILTERS).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(BotsRosterCopy.NO_MATCH_FILTERS).assertIsDisplayed()

        compose.onNodeWithText(BotsRosterCopy.CLEAR_FILTERS).performClick()
        awaitText("Researcher")
    }

    /**
     * Wait for [text] to render; on failure, say so **with the rendered tree**,
     * because "nothing appeared" on its own says nothing about which state the
     * surface settled into.
     */
    private fun awaitText(text: String) {
        val failure = runCatching {
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        }.exceptionOrNull() ?: return
        throw AssertionError(
            "“$text” never rendered after 5s (${failure.message})\n${compose.onRoot().printToString()}",
        )
    }

    /**
     * The normal list. With no user sections Desktop draws the plain list, so
     * nothing is labelled "Unassigned"; with sections made, every block that is
     * drawn carries its own heading.
     */
    @Test
    fun `the list is unlabelled with no user sections and headed once there are some`() {
        clients.value = rosterRpc()
        launch()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Researcher").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(BotsRosterCopy.UNASSIGNED).assertDoesNotExist()
    }

    @Test
    fun `a user section labels its rows and Unassigned labels the loose ones`() {
        val filed = BotRosterRow(name = "researcher").rosterKey
        clients.value = rosterRpc()
        launch(
            sections = listOf(BotSection("s1", "Clients")),
            metaByKey = mapOf(filed to BotMeta(sectionId = "s1")),
        )

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Clients").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Clients").assertIsDisplayed()
        compose.onNodeWithText(BotsRosterCopy.UNASSIGNED).assertIsDisplayed()
        compose.onNodeWithText("Default").assertIsDisplayed()
    }

    // ── harness ───────────────────────────────────────────────────────────────

    /**
     * Register the real plugin against the real door and render whichever
     * contribution this test is about.
     */
    private fun launch(
        owner: LifecycleOwner? = null,
        renderSidebarEntry: Boolean = false,
        sections: List<BotSection> = emptyList(),
        metaByKey: Map<String, BotMeta> = emptyMap(),
    ) {
        val registry = ContributionRegistry()
        val plugin = BotsPlugin(sections = sections, metaByKey = metaByKey, scope = pluginScope)
        plugin.register(
            createPluginContext(
                pluginId = plugin.id,
                registry = registry,
                rest = NoRest,
                socket = NoSocket,
                storage = NoStorage,
                os = NoOs,
                host = GatewayPluginHost(pluginScope, clients) as PluginHost,
            ),
        )
        val area = if (renderSidebarEntry) PluginAreas.SIDEBAR_NAV_AREA else PluginAreas.ROUTES_AREA
        val render = requireNotNull(registry.getArea(area).single().render)

        compose.setContent {
            val screen: @Composable () -> Unit = {
                HermesTheme(AppearanceSelection()) { render() }
            }
            if (owner == null) {
                screen()
            } else {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) { screen() }
            }
        }
        compose.waitForIdle()
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** A `profiles.list` answer with one row per name. */
    private fun rosterRpc(vararg names: String): GatewayRpcClient {
        val profiles = if (names.isEmpty()) arrayOf("default", "researcher") else names
        val rows = profiles.joinToString(",") { name ->
            """{"name": "$name", "display_name": "${name.replaceFirstChar { it.uppercase() }}",
                "last_session": {"last_active": ${secondsAgo(30)}, "preview": "hello"}}"""
        }
        return FakeRpc { method, _ ->
            assertEquals("profiles.list", method)
            Json.parseToJsonElement("""{"profiles": [$rows]}""")
        }
    }

    private fun secondsAgo(seconds: Long): Long = (System.currentTimeMillis() / 1000L) - seconds

    private object NoRest : PluginRest {
        override suspend fun execute(
            pluginId: String,
            path: String,
            options: PluginRestOptions,
        ): PluginRestResult = PluginRestResult.Success(200, "{}".toByteArray())
    }

    private object NoSocket : PluginSocket {
        override fun connect(pluginId: String, path: String, onMessage: (String) -> Unit): () -> Unit = {}
    }

    private object NoStorage : PluginStorage {
        override suspend fun get(key: String, fallback: String?): String? = fallback
        override suspend fun set(key: String, value: String) {}
        override suspend fun remove(key: String) {}
    }

    private object NoOs : PluginOs {
        override fun notify(input: PluginNotificationInput) {}
        override suspend fun openExternal(url: String): Boolean = true
        override suspend fun writeClipboard(text: String): Boolean = true
        override suspend fun share(text: String, title: String?): Boolean = true
    }

    private companion object {
        /** The reason the entry point's closed sentence carries. */
        const val PREDATES_REASON = "this Gateway does not serve profiles.list"
    }
}
