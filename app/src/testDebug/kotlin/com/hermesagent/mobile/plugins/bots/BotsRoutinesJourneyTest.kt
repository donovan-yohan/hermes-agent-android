package com.hermesagent.mobile.plugins.bots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.hermesagent.mobile.plugins.createPluginContext
import com.hermesagent.mobile.ui.LocalPluginNavigation
import com.hermesagent.mobile.ui.PluginNavigation
import com.hermesagent.mobile.ui.common.WIP_SPOKEN
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesSpacing
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.data.gateway.EndpointDispatchingGatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayEvent
import com.hermesagent.mobile.data.gateway.GatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayRpcError
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Routines destination as a person walks it: the entry on the roster, and
 * every state the surface can be in.
 *
 * Everything is driven through the plugin's own contributions and its own host
 * door — the real route, the real ViewModel, the real screen — so what is
 * proved is what renders. No test here reaches a socket, a Gateway or a file.
 *
 * The viewport is pinned to a real phone's (`w411dp-h891dp`); Robolectric's
 * default is shorter than any phone this ships on, where a state message's own
 * action lays out past the window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class BotsRoutinesJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    private val clients = MutableStateFlow<GatewayRpcClient?>(null)
    private var pluginScope: CoroutineScope? = null

    @Before
    fun setUp() {
        pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @After
    fun tearDown() {
        pluginScope?.cancel()
    }

    /**
     * One connection leg's client, with the endpoint-scoped dispatch the host's
     * `requestAtEndpoint` requires (`EndpointDispatchFence.kt:61-67`).
     *
     * The fenced seam is the one under test, so the fake provides it rather than
     * letting the door refuse every read before it reaches the wire — which is
     * what a plain client does, and what every Routines read would otherwise hit.
     */
    private class FakeRpc(
        private val answer: (String, JsonObject) -> JsonElement,
    ) : EndpointDispatchingGatewayRpcClient {
        override val events: Flow<GatewayEvent> = emptyFlow()

        override suspend fun request(method: String, params: JsonObject): JsonElement =
            answer(method, params)

        override suspend fun requestAtEndpointDispatch(
            method: String,
            params: JsonObject,
            dispatch: (() -> Boolean) -> Boolean,
        ): JsonElement = answer(method, params)

        override fun close() {}
    }

    /**
     * One leg answering everything a walk from the roster to the destination
     * needs: the roster, the canonical Bot Chat lookup behind a row tap, and the
     * bot's routines.
     *
     * [routines] is called per `cron.manage` request, so a test can script a
     * sequence. [roster] is the `profiles.list` answer.
     */
    private fun rpc(
        routines: () -> JsonElement,
        roster: JsonElement = TWO_BOTS,
        chat: JsonElement = CANONICAL_CHAT,
    ): GatewayRpcClient = FakeRpc { method, _ ->
        when (method) {
            "profiles.list" -> roster
            "session.list" -> chat
            "cron.manage" -> routines()
            else -> error("unexpected method $method")
        }
    }

    /** A leg answering a fixed routines list, the roster, and the bot's chat. */
    private fun rpc(routines: JsonElement): GatewayRpcClient = this.rpc({ routines })

    private fun secondsAgo(seconds: Long): Long = (System.currentTimeMillis() / 1000L) - seconds

    private fun jobs(body: String): JsonElement = Json.parseToJsonElement(body)

    /** A `cron.manage list` answer with one row per name. */
    private fun listed(vararg names: String): JsonElement {
        val rows = names.mapIndexed { index, name ->
            """{"job_id":"j$index","name":"$name","schedule":"every 1h","enabled":true,
                "state":"scheduled","next_run_at":"2026-09-18T09:00:00+00:00"}"""
        }
        return jobs("""{"success":true,"count":${rows.size},"jobs":[${rows.joinToString(",")}]}""")
    }

    // ── the entry ─────────────────────────────────────────────────────────────

    @Test
    fun `the roster row's Routines control opens that bot's jobs, and a row tap still opens chat`() {
        val chatRows = mutableListOf<String>()
        clients.value = rpc(listed("[bot:ops] Morning digest"))
        launchRoutes(onOpenBotChat = { row -> chatRows += row.name })

        awaitText("Ops")

        // The row's own tap is still Bot Chat, untouched by the new control.
        compose.onNodeWithText("Ops").performClick()
        assertEquals(listOf("ops"), chatRows)
        // Clicking the row does not navigate to Routines.
        compose.onAllNodesWithTag(ROUTINES_ROW_ACTION_TAG, useUnmergedTree = true).fetchSemanticsNodes()
            .let { assertEquals(2, it.size) }
        compose.onAllNodesWithText(BotsRoutinesCopy.FAILED_LOAD).assertCountEquals(0)

        // The distinct Routines entry is visible, named for its bot, and walks
        // the production path into that bot's own scheduled jobs.
        compose.onNodeWithContentDescription(routinesEntryLabel("Ops"))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(HermesSpacing().touchTarget)
            .performClick()

        awaitText("Morning digest")
        // The destination names the bot it is scoped to: the header shows the
        // profile, and no roster row is on screen any more.
        compose.onNodeWithText("Ops").assertExists()
        compose.onAllNodesWithTag(ROUTINES_ROW_ACTION_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `every row carries its own Routines entry, named for its own bot`() {
        clients.value = rpc(listed())
        launchRoutes()

        awaitText("Research")
        compose.onAllNodesWithTag(ROUTINES_ROW_ACTION_TAG, useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithContentDescription(routinesEntryLabel("Ops")).assertIsDisplayed()
        compose.onNodeWithContentDescription(routinesEntryLabel("Research")).assertIsDisplayed()
    }

    // ── the states ────────────────────────────────────────────────────────────

    @Test
    fun `a populated list renders the title, schedule, next run and the paused row`() {
        clients.value = rpc(
            jobs(
                """{"success":true,"count":2,"scoped":"ops","jobs":[
                    {"job_id":"j0","name":"[bot:ops] Morning digest","schedule":"every 1440m",
                     "enabled":true,"state":"scheduled","next_run_at":"2026-09-18T09:00:00+00:00"},
                    {"job_id":"j1","name":"[bot:ops] Nightly sweep","schedule":"30m",
                     "enabled":false,"state":"paused"}
                  ]}""",
            ),
        )
        launchRoutes()
        openRoutinesFor("Ops")

        awaitText("Morning digest")
        // The routing tag is plumbing, not a title: it never renders.
        compose.onAllNodesWithText("[bot:ops] Morning digest").assertCountEquals(0)
        // Desktop's own schedule labels, and the paused row still listed.
        compose.onNodeWithText("Nightly sweep").assertIsDisplayed()
        compose.onNodeWithText("Daily").assertIsDisplayed()
        compose.onNodeWithText("Once (30m)").assertIsDisplayed()
        compose.onNodeWithText(BotsRoutinesCopy.STATE_PAUSED).assertIsDisplayed()
    }

    @Test
    fun `no backend prose is ever rendered`() {
        clients.value = rpc(
            jobs(
                """{"success":true,"count":1,"scoped":"ops","jobs":[
                    {"job_id":"j0","name":"[bot:ops] Morning","schedule":"every 1h","enabled":true,
                     "state":"scheduled","last_fire_error":"Traceback: backendprose",
                     "last_delivery_error":"deliveryprose","paused_reason":"reasonprose",
                     "prompt_preview":"promptprose","warning":"warningprose"}
                  ]}""",
            ),
        )
        launchRoutes()
        openRoutinesFor("Ops")

        awaitText("Morning")
        val tree = compose.onRoot().printToString()
        // The five members this app must never display, asserted as absent from
        // the rendered tree rather than only from the model.
        listOf("backendprose", "deliveryprose", "reasonprose", "promptprose", "warningprose")
            .forEach { prose ->
                assertTrue("$prose reached the screen", !tree.contains(prose))
            }
    }

    @Test
    fun `a legacy delegated routine is listed, labelled, and never claimed as paused`() {
        // Desktop pauses one of these on load and says "Paused for security"
        // (`cron.tsx:591-595`). This slice issues no mutation, so it must say
        // what it actually did: nothing. The job stays listed and active.
        clients.value = rpc(
            jobs(
                """{"success":true,"count":1,"scoped":"ops","jobs":[
                    {"job_id":"j0","name":"[bot:ops] Audit","schedule":"every 1h","enabled":true,
                     "state":"scheduled",
                     "prompt_preview":"You are running the scheduled routine \"Audit\" for agent 'ops'. "}
                  ]}""",
            ),
        )
        launchRoutes()
        openRoutinesFor("Ops")

        awaitText("Audit")
        compose.onNodeWithTag(LEGACY_NOTICE_TAG).assertIsDisplayed()
        compose.onNodeWithText(BotsRoutinesCopy.LEGACY_NOTICE).assertIsDisplayed()
        compose.onAllNodesWithText(BotsRoutinesCopy.STATE_PAUSED).assertCountEquals(0)
        val tree = compose.onRoot().printToString()
        assertTrue("Desktop's security-pause sentence was reused", !tree.contains("Paused for security"))
    }

    @Test
    fun `the empty store is the empty state with the disabled create marker`() {
        clients.value = rpc(jobs("""{"success":true,"count":0,"scoped":"ops","jobs":[]}"""))
        launchRoutes()
        openRoutinesFor("Ops")

        awaitText(BotsRoutinesCopy.EMPTY_TITLE)
        compose.onNodeWithText(BotsRoutinesCopy.EMPTY_DESC).assertIsDisplayed()
        // Desktop's empty card action is a create — a mutation this slice does
        // not ship, so it renders as the marked, disabled control instead. The
        // header carries the same control, so both are asserted as marked and
        // disabled rather than counted as one.
        compose.onAllNodesWithContentDescription("${BotsRoutinesCopy.NEW_CRON}. $WIP_SPOKEN")
            .assertCountEquals(2)
        compose.onAllNodesWithContentDescription("${BotsRoutinesCopy.NEW_CRON}. $WIP_SPOKEN")
            .fetchSemanticsNodes()
            .forEach { node ->
                assertTrue(
                    "a deferred control is live",
                    node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled),
                )
            }
    }

    @Test
    fun `an empty view over a legacy store explains itself and offers no create`() {
        // `routineFilterHint`: jobs exist on the profile but none are tagged for
        // this bot. The store here answers *without* a `scoped` echo, which is
        // the legacy case where the tag is the only filter left.
        clients.value = rpc(
            jobs(
                """{"success":true,"count":1,"jobs":[
                    {"job_id":"j0","name":"[bot:research] Digest","schedule":"every 1h","enabled":true}
                  ]}""",
            ),
        )
        launchRoutes()
        openRoutinesFor("Ops")

        awaitText(BotsRoutinesCopy.EMPTY_TITLE)
        compose.onNodeWithText(BotsRoutinesCopy.FILTER_HINT).assertIsDisplayed()
        compose.onAllNodesWithText("Digest").assertCountEquals(0)
    }

    @Test
    fun `a read failure draws its own sentence and a retry`() {
        val routinesLeg = rpc({ throw GatewayRpcError(5023, "backend prose") })
        clients.value = routinesLeg
        launchRoutes()
        openRoutinesFor("Ops")

        awaitText(BotsRoutinesCopy.READ_FAILURE)
        compose.onNodeWithText(BotsRoutinesCopy.FAILED_LOAD).assertIsDisplayed()
        compose.onNodeWithText(BotsRosterCopy.RETRY_NOW).assertIsDisplayed()
        val tree = compose.onRoot().printToString()
        assertTrue("backend prose reached the screen", !tree.contains("backend prose"))
    }

    @Test
    fun `a gateway without cron dot manage says so rather than reporting a failure`() {
        clients.value = rpc({ throw GatewayRpcError(-32601, "unknown method") })
        launchRoutes()
        openRoutinesFor("Ops")

        awaitText(BotsRoutinesCopy.UNAVAILABLE)
        compose.onNodeWithText(BotsRoutinesCopy.UNAVAILABLE).assertIsDisplayed()
        compose.onNodeWithText(BotsRosterCopy.RETRY_NOW).assertIsDisplayed()
    }

    @Test
    fun `an answer about a different profile is refused in its own words`() {
        clients.value = rpc(
            jobs(
                """{"success":true,"scoped":"research","jobs":[
                    {"job_id":"r0","name":"ordinary research cronjob","schedule":"every 1h","enabled":true}
                  ]}""",
            ),
        )
        launchRoutes()
        openRoutinesFor("Ops")

        awaitText(BotsRoutinesCopy.MISMATCHED_TITLE)
        compose.onNodeWithText(BotsRoutinesCopy.MISMATCHED_DESC).assertIsDisplayed()
        // Nothing from that store renders, not even the tagged-looking rows.
        compose.onAllNodesWithText("ordinary research cronjob").assertCountEquals(0)
        // The state names no profile: the person is told what happened, not
        // which name the Gateway answered about.
        val body = compose.onNodeWithTag(ROUTINES_MESSAGE_TAG).printToString()
        assertTrue("the foreign profile name reached the screen", !body.contains("research"))
    }

    @Test
    fun `a stale list keeps its rows and says they are old`() {
        val answers = ArrayDeque(
            listOf<() -> JsonElement>(
                { listed("[bot:ops] Morning") },
                { throw GatewayRpcError(5023, "backend prose") },
            ),
        )
        clients.value = rpc({ answers.removeFirstOrNull()?.invoke() ?: throw GatewayRpcError(5023, "backend prose") })
        val owner = TestOwner()
        launchRoutes(lifecycleOwner = owner)
        openRoutinesFor("Ops")
        awaitText("Morning")

        // A resume is the surface's own refetch, exactly as a re-entry is: the
        // answer fails, and the list stays with the banner that explains it.
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }

        awaitText(BotsRoutinesCopy.STALE_NOTICE)
        compose.onNodeWithTag(STALE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Morning").assertIsDisplayed()
    }

    @Test
    fun `a bot with an unknown state renders neither a running dot nor a state word`() {
        clients.value = rpc(
            jobs(
                """{"success":true,"count":1,"scoped":"ops","jobs":[
                    {"job_id":"j0","name":"[bot:ops] Odd one","schedule":"every 1h","enabled":true,
                     "state":"hibernating"}
                  ]}""",
            ),
        )
        launchRoutes()
        openRoutinesFor("Ops")

        awaitText("Odd one")
        val tree = compose.onRoot().printToString()
        // The state word itself is not echoed, and the row makes no running
        // claim either: nothing here licenses one.
        assertTrue("the raw state word reached the screen", !tree.contains("hibernating"))
        assertTrue("a state word was invented", !tree.contains(BotsRoutinesCopy.STATE_PAUSED))
        // The dot says it is not scheduled to run rather than claiming a run.
        compose.onNodeWithContentDescription(INACTIVE_DOT_SPOKEN).assertIsDisplayed()
    }

    // ── what this slice does not do ───────────────────────────────────────────

    @Test
    fun `the deferred Desktop controls are visible, disabled and marked`() {
        clients.value = rpc(listed("[bot:ops] Morning"))
        launchRoutes()
        openRoutinesFor("Ops")

        awaitText("Morning")
        // Desktop's header add, and its row's pause and delete, all ship here
        // as marked placeholders rather than absent controls or live ones that
        // would issue a mutation this slice does not have.
        listOf(BotsRoutinesCopy.NEW_CRON, BotsRoutinesCopy.PAUSE_CRON, BotsRoutinesCopy.DELETE)
            .forEach { label ->
                compose.onNodeWithContentDescription("$label. $WIP_SPOKEN")
                    .assertIsDisplayed()
                    .assertIsNotEnabled()
            }
    }

    // ── harness ───────────────────────────────────────────────────────────────

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
     * Walk the production path into one bot's Routines: click the row's own
     * control for that bot, exactly as a person would, and wait for the
     * destination to be on screen.
     */
    private fun openRoutinesFor(displayName: String) {
        awaitText(displayName)
        compose.onNodeWithContentDescription(routinesEntryLabel(displayName)).performClick()
        compose.waitForIdle()
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /**
     * Register the plugin once and render whichever of its route contributions
     * the plugin's own navigation points at — the real route ids, the real
     * render functions, and the real `onNavigate` a route would see. Clicking a
     * roster row's Routines control therefore walks the production path from the
     * entry to the destination.
     */
    private fun launchRoutes(
        onOpenBotChat: (BotRosterRow) -> Unit = {},
        lifecycleOwner: LifecycleOwner? = null,
    ) {
        val registry = ContributionRegistry()
        val scope = requireNotNull(pluginScope)
        val plugin = BotsPlugin(scope = scope)
        plugin.register(
            createPluginContext(
                pluginId = plugin.id,
                registry = registry,
                rest = NoRest,
                socket = NoSocket,
                storage = NoStorage,
                os = NoOs,
                host = GatewayPluginHost(scope, clients, MutableStateFlow(0L)) as PluginHost,
            ),
        )
        val routes = registry.getArea(PluginAreas.ROUTES_AREA)
        val rosterRoute = requireNotNull(routes.firstOrNull { it.id.endsWith(":route") }) {
            "the plugin registers no roster route: ${routes.map { it.id }}"
        }.id
        requireNotNull(routes.firstOrNull { it.id.endsWith(":routines") }) {
            "the plugin registers no routines route: ${routes.map { it.id }}"
        }

        compose.setContent {
            var route by remember { mutableStateOf(rosterRoute) }
            val navigation = remember {
                PluginNavigation(
                    onNavigate = { target -> route = target },
                    onOpenBotChat = { profile, _, onFinished ->
                        onOpenBotChat(BotRosterRow(name = profile))
                        onFinished(true)
                    },
                )
            }
            val screen: @Composable () -> Unit = {
                HermesTheme(AppearanceSelection()) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalPluginNavigation provides navigation,
                    ) {
                        routes.firstOrNull { it.id == route }?.render?.invoke()
                    }
                }
            }
            if (lifecycleOwner == null) {
                screen()
            } else {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalLifecycleOwner provides lifecycleOwner,
                ) { screen() }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The plugin's own route ids, as `BotsPlugin` registers them. Read off the
     * registration rather than hard-coded in the tests that use them.
     */
    private companion object {
        /** The stale banner's tag, shared with the roster's own banner. */
        const val STALE_TAG = "Bots stale"

        /** The spoken form of the row's inactive dot. */
        const val INACTIVE_DOT_SPOKEN = "Not scheduled to run"

        /**
         * Two bots, one of them called `Ops` by display name and `ops` by
         * profile — so a test can prove the destination is scoped by the
         * profile and reads as the bot either way.
         */
        val TWO_BOTS: JsonElement = Json.parseToJsonElement(
            """{"profiles": [
                 {"name": "ops", "display_name": "Ops", "last_session": {"last_active": 1800000000}},
                 {"name": "research", "display_name": "Research", "last_session": {"last_active": 1799999000}}
               ]}""",
        )

        /** The canonical Bot Chat row a roster row's tap resolves to. */
        val CANONICAL_CHAT: JsonElement = Json.parseToJsonElement(
            """{"sessions": [{"id": "tip", "resolved_id": "durable-tip", "title": "Bot Chat"}]}""",
        )
    }

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
}
