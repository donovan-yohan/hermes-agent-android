package com.hermesagent.mobile.plugins.kanban
import androidx.compose.ui.test.performTextInput

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.plugins.ContributionRegistry
import com.hermesagent.mobile.plugins.PluginAreas
import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostEvent
import com.hermesagent.mobile.plugins.PluginHostResult
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
import com.hermesagent.mobile.ui.settings.SettingsScreen
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KanbanPluginJourneyTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `sidebar contribution is visible enabled touch sized and routes exactly to kanban`() {
        val registry = ContributionRegistry()
        val disposers = mutableListOf<() -> Unit>()
        KanbanPlugin().register(
            createPluginContext(
                pluginId = "kanban",
                registry = registry,
                rest = Rest,
                socket = Socket,
                storage = Storage,
                os = Os,
                host = Host,
                onDispose = { disposers += it },
            ),
        )
        val sidebar = registry.getArea(PluginAreas.SIDEBAR_NAV_AREA).single()
        val route = registry.getArea(PluginAreas.ROUTES_AREA).single()
        val navigations = mutableListOf<String>()
        var routeOpen by mutableStateOf(false)

        compose.setContent {
            HermesTheme {
                CompositionLocalProvider(
                    LocalPluginNavigation provides PluginNavigation(
                        onNavigate = { destination ->
                            navigations += destination
                            routeOpen = destination == "kanban:route"
                        },
                    ),
                ) {
                    if (routeOpen) {
                        route.render?.invoke()
                    } else {
                        sidebar.render?.invoke()
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("Kanban")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle { assertEquals(listOf("kanban:route"), navigations) }
        compose.onNodeWithText("Loading board…").assertIsDisplayed()

        disposers.forEach { it() }
    }

    @Test
    fun `board and task WIP controls are disabled marked and retain Desktop order`() {
        val task = KanbanTask("x", "A task", "open", body = "line one\nline two")
        val detail = KanbanTaskDetail(task, listOf("parent"), listOf("child"), emptyList())
        var state by mutableStateOf(
            KanbanUiState(KanbanPhase.Ready, listOf(KanbanColumn("Open", listOf(task)))),
        )
        compose.setContent {
            HermesTheme {
                KanbanScreen(
                    state = state,
                    onBack = {},
                    onRefresh = {},
                    onOpenTask = { state = state.copy(detail = KanbanDetail.Value(detail)) },
                    onCloseDetail = { state = state.copy(detail = KanbanDetail.None) },
                )
            }
        }

        compose.onNodeWithTag("Kanban Board controls").performClick()
        compose.waitForIdle()
        assertWipOrder("Board switcher", "Orchestration settings")
        compose.onNodeWithText("New task").assertIsDisplayed()
        compose.onNodeWithText("Filters").assertIsDisplayed()
        compose.onAllNodesWithTag("Kanban menu separator").assertCountEquals(1)
        compose.onNodeWithTag("Kanban Board controls").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("Kanban WIP Board switcher").assertDoesNotExist()
        compose.onNodeWithContentDescription("A task. open").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("line one\nline two").assertIsDisplayed()
        compose.onNodeWithTag("Kanban Task actions").performClick()
        compose.waitForIdle()
        assertWipOrder("Copy task id", "Copy title", "Archive", "Delete")
        compose.onNodeWithText("Move to").assertIsDisplayed()
        compose.onAllNodesWithTag("Kanban menu separator").assertCountEquals(2)
        compose.onNodeWithContentDescription("Back to board").performClick()
        compose.onNodeWithTag("Kanban board").assertIsDisplayed()
        compose.onNodeWithTag("Kanban refresh").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `detail unavailable has fixed Kanban copy separate from gone and refusal`() {
        val task = KanbanTask("x", "A task", "open")
        var state by mutableStateOf(KanbanUiState(detail = KanbanDetail.Unavailable(task)))
        compose.setContent {
            HermesTheme {
                KanbanScreen(state, {}, {}, {}, { state = state.copy(detail = KanbanDetail.None) })
            }
        }

        compose.onNodeWithText("Kanban unavailable").assertIsDisplayed()
        compose.onNodeWithText(KANBAN_UNAVAILABLE).assertIsDisplayed()
        compose.runOnIdle { state = KanbanUiState(detail = KanbanDetail.Gone(task)) }
        compose.onNodeWithText("This task is no longer available").assertIsDisplayed()
        compose.runOnIdle { state = KanbanUiState(detail = KanbanDetail.Refused(task)) }
        compose.onNodeWithText("Task details could not be loaded").assertIsDisplayed()
    }

    @Test
    fun `board empty unavailable and refused states render fixed copy and keep refresh`() {
        var state by mutableStateOf(KanbanUiState(KanbanPhase.Empty))
        compose.setContent {
            HermesTheme {
                KanbanScreen(state, {}, {}, {}, {})
            }
        }

        compose.onNodeWithText("No tasks on this board").assertIsDisplayed()
        compose.onNodeWithTag("Kanban refresh").assertIsDisplayed().assertHeightIsAtLeast(48.dp)

        compose.runOnIdle { state = KanbanUiState(KanbanPhase.Unavailable) }
        compose.onNodeWithText("Kanban unavailable").assertIsDisplayed()
        compose.onNodeWithText(KANBAN_UNAVAILABLE).assertIsDisplayed()

        compose.runOnIdle { state = KanbanUiState(KanbanPhase.Refused) }
        compose.onNodeWithText("Board could not be loaded").assertIsDisplayed()
        compose.onNodeWithTag("Kanban refresh").assertIsDisplayed()
    }

    @Test fun `move surface shows named board targets and sends only explicitly confirmed status`() {
        val task = KanbanTask("x", "Pearl", "todo")
        var state by mutableStateOf(KanbanUiState(detail = KanbanDetail.Value(KanbanTaskDetail(task, emptyList(), emptyList(), emptyList()))))
        var moved: String? = null
        compose.setContent { HermesTheme {
            KanbanScreen(state, {}, {}, {}, {},
                onMoveTask = { state = state.copy(move = KanbanMoving(KanbanMoveChoices("design", task, listOf("ready", "done")))) },
                onCloseMove = { state = state.copy(move = null) }, onConfirmMove = { status, _ -> moved = status })
        } }
        compose.onNodeWithTag("Kanban Task actions").performClick()
        compose.onNodeWithText("Move to").performClick()
        compose.onNodeWithText("Board: design").assertIsDisplayed()
        compose.onNodeWithText("Ready").performClick()
        compose.runOnIdle { assertEquals(null, moved) }
        compose.onNodeWithTag("Kanban confirm move").performClick()
        compose.runOnIdle { assertEquals("ready", moved) }
        compose.onAllNodesWithTag("Kanban WIP Move to").assertCountEquals(0)
        compose.onNodeWithContentDescription("Back to task").performClick()
        compose.onNodeWithTag("Kanban detail").assertExists()
    }

    @Test fun `move confirmation redacts backend semantics and collects required Done evidence`() {
        val task = KanbanTask("x", "password=title-demo", "ready")
        var submitted: Pair<String, String>? = null
        compose.setContent { HermesTheme {
            KanbanMoveScreen(KanbanMoving(KanbanMoveChoices("password=board-demo", task, listOf("done"))), {},
                { status, summary -> submitted = status to summary })
        } }
        compose.onNodeWithText("password=title-demo", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Board: password=board-demo", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("password=<redacted>", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("Kanban confirm move").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Completion summary").performTextInput("Verified the work")
        compose.onNodeWithTag("Kanban confirm move").performClick()
        compose.runOnIdle { assertEquals("done" to "Verified the work", submitted) }
    }

    @Test fun `new task form requires title and submits title description priority`() {
        val filters = KanbanFilters(search = "Pearl", assignee = "planner", archived = true)
        var state by mutableStateOf(KanbanUiState(filters = filters))
        var submitted: KanbanNewTask? = null
        compose.setContent { HermesTheme {
            KanbanScreen(state, {}, {}, {}, {},
                onNewTask = { state = state.copy(creation = KanbanCreation(KanbanCreateDefaults("design", "planner"))) },
                onCloseCreate = { state = state.copy(creation = null) },
                onSubmitTask = { submitted = it })
        } }
        compose.onNodeWithTag("Kanban Board controls").performClick()
        compose.onNodeWithText("New task").performClick()
        compose.onNodeWithText("Create task").assertIsNotEnabled()
        compose.onNodeWithTag("Kanban WIP Workspace").assertIsNotEnabled()
        compose.onNodeWithTag("Kanban WIP Assignee").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Title").performTextInput("Pearl")
        compose.onNodeWithContentDescription("Description").performTextInput("A useful task\nwith detail")
        compose.onNodeWithText("Create task").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(KanbanNewTask("Pearl", "A useful task\nwith detail", 0), submitted) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("Kanban refresh").assertExists()
        compose.onNodeWithContentDescription("Filter cards…").assertExists()
        compose.runOnIdle { assertEquals(filters, state.filters) }
    }

    @Test fun `create defaults publish only redacted semantics`() {
        compose.setContent { HermesTheme {
            KanbanNewTaskScreen(KanbanCreation(KanbanCreateDefaults("password=board-demo", "password=assignee-demo")), {}, {})
        } }
        compose.onNodeWithText("Board: password=board-demo", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Assignee: password=assignee-demo", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Board: password=<redacted>", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Assignee: password=<redacted>", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `search and profile filters hide cards without changing board snapshot`() {
        val tasks = listOf(
            KanbanTask("a", "Pearl", "todo", assignee = "alpha"),
            KanbanTask("b", "Shell", "todo", assignee = "beta"),
        )
        var state by mutableStateOf(KanbanUiState(
            KanbanPhase.Ready, listOf(KanbanColumn("todo", tasks)),
            assignees = listOf("alpha", "beta"),
        ))
        compose.setContent { HermesTheme {
            KanbanScreen(state, {}, {}, {}, {}, onFilters = { state = state.copy(filters = it) })
        } }
        compose.onNodeWithContentDescription("Filter cards…").performTextInput("pearl")
        compose.onNodeWithTag("Kanban task a").assertExists()
        compose.onNodeWithTag("Kanban task b").assertDoesNotExist()
        compose.onNodeWithContentDescription("Filter cards…").performTextClearance()
        compose.onNodeWithTag("Kanban Board controls").performClick()
        compose.onNodeWithText("Filters").performClick()
        compose.onNodeWithText("beta").performClick()
        compose.onNodeWithTag("Kanban task a").assertDoesNotExist()
        compose.onNodeWithTag("Kanban task b").assertExists()
        compose.onNodeWithContentDescription("Filter cards…").performTextInput("missing")
        compose.onNodeWithText("No tasks match the filters").assertIsDisplayed()
        compose.runOnIdle { assertEquals(tasks, state.columns.single().tasks) }
    }

    @Test fun `filter semantics redact backend labels but selection retains exact identities`() {
        val raw = "password=kanban-demo"
        var state by mutableStateOf(KanbanUiState(KanbanPhase.Empty,
            assignees = listOf(raw), tenants = listOf("Tenant password=tenant-demo")))
        compose.setContent { HermesTheme {
            KanbanScreen(state, {}, {}, {}, {}, onFilters = { state = state.copy(filters = it) })
        } }
        compose.onNodeWithTag("Kanban Board controls").performClick()
        compose.onNodeWithText("Filters").performClick()
        compose.onNodeWithText(raw, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Tenant password=tenant-demo", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Tenant password=<redacted>", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("password=<redacted>").performClick()
        compose.runOnIdle { assertEquals(raw, state.filters.assignee) }
    }

    private fun assertWipOrder(vararg labels: String) {
        val orderedLabels = labels.toList()
        val tops = orderedLabels.associateWith { label ->
            compose.onNodeWithTag("Kanban WIP $label")
                .assertExists()
                .assertIsNotEnabled()
                .getUnclippedBoundsInRoot()
                .top
        }
        orderedLabels.zipWithNext().forEach { (above, below) ->
            assertTrue(
                "$below should render below $above",
                tops.getValue(below) > tops.getValue(above),
            )
        }
    }

    private object Rest : PluginRest {
        override suspend fun execute(
            pluginId: String,
            path: String,
            options: PluginRestOptions,
        ) = PluginRestResult.Success(200, """{"columns":[]}""".toByteArray())
    }

    private object Socket : PluginSocket {
        override fun connect(pluginId: String, path: String, onMessage: (String) -> Unit): () -> Unit = {}
    }

    private object Storage : PluginStorage {
        override suspend fun get(key: String, fallback: String?) = fallback
        override suspend fun set(key: String, value: String) {}
        override suspend fun remove(key: String) {}
    }

    private object Os : PluginOs {
        override fun notify(input: PluginNotificationInput) {}
        override suspend fun openExternal(url: String) = false
        override suspend fun writeClipboard(text: String) = false
        override suspend fun share(text: String, title: String?) = false
    }

    private object Host : PluginHost {
        override val connected = MutableStateFlow(false)
        override val endpointGeneration = MutableStateFlow(0L)
        override suspend fun request(method: String, params: kotlinx.serialization.json.JsonObject) = PluginHostResult.UnavailableOnGateway
        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }
}
