package com.hermesagent.mobile.plugins.groups

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermesagent.mobile.ui.common.WIP_SPOKEN
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import java.util.Locale
import java.util.TimeZone
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class GroupsJourneyTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var oldLocale: Locale
    private lateinit var oldZone: TimeZone
    @Before fun fixedEnvironment() {
        oldLocale = Locale.getDefault(); oldZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US); TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }
    @After fun restoreEnvironment() { Locale.setDefault(oldLocale); TimeZone.setDefault(oldZone) }
    private val room = HostedGroup("room", "Planning", listOf(GroupMember("ops", "ops", "Ops", false)), 1, false, "fixture")
    private fun mount(state: State<GroupsUiState>, actions: GroupsActions = GroupsActions(), back: () -> Unit = {}) {
        compose.setContent { HermesTheme(AppearanceSelection()) { GroupsScreen(state.value, back, actions) } }
    }

    @Test fun listLoadingUnsupportedEmptyFailureRetryAndPopulated() {
        val state = mutableStateOf(GroupsUiState())
        var retries = 0
        mount(state, GroupsActions(retry = { retries++ }))
        compose.onNodeWithText("Loading Group Chats…").assertIsDisplayed()
        compose.runOnIdle { state.value = GroupsUiState(phase = GroupsPhase.Unsupported) }
        compose.onNodeWithText("Group Chats need a newer Gateway. Update Hermes and reconnect.").assertIsDisplayed()
        compose.runOnIdle { state.value = GroupsUiState(phase = GroupsPhase.Ready) }
        compose.onNodeWithText("No Group Chats yet.").assertIsDisplayed()
        compose.runOnIdle { state.value = GroupsUiState(phase = GroupsPhase.Failure) }
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries); state.value = GroupsUiState(phase = GroupsPhase.Ready, rooms = listOf(room)) }
        compose.onNodeWithText("Planning").assertIsDisplayed()
    }

    @Test fun navigationAndDesktopOrderedMenuRemainReadOnly() {
        val state = mutableStateOf(GroupsUiState(phase = GroupsPhase.Ready, rooms = listOf(room)))
        var opened: String? = null
        mount(state, GroupsActions(open = { opened = it }))
        compose.onNodeWithContentDescription("New group chat. $WIP_SPOKEN").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Group Chat actions").performClick()
        listOf("Pin to top", "Move to section", "Delete").forEach {
            compose.onNodeWithContentDescription("$it. $WIP_SPOKEN").assertIsNotEnabled()
        }
        val openY = compose.onNodeWithText("Open Group Chat").fetchSemanticsNode().boundsInRoot.top
        val pinY = compose.onNodeWithText("Pin to top").fetchSemanticsNode().boundsInRoot.top
        val deleteY = compose.onNodeWithText("Delete").fetchSemanticsNode().boundsInRoot.top
        assertTrue(openY < pinY && pinY < deleteY)
        compose.onNodeWithText("Open Group Chat").performClick()
        compose.runOnIdle { assertEquals("room", opened) }
    }

    @Test fun roomLoadingBlockedDisbandedStaleAndExpiredStates() {
        val transcript = GroupTranscript(GroupState(room), listOf(GroupEvent(1, "message.user", null, "Hello")), 1)
        val state = mutableStateOf(GroupsUiState(phase = GroupsPhase.Ready, selected = room.id, roomLoading = true))
        mount(state)
        compose.onNodeWithText("Loading Group Chat…").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(roomLoading = false, transcript = transcript) }
        compose.onNodeWithText("Hello").assertIsDisplayed()
        compose.onNodeWithContentDescription("Group settings for Planning. $WIP_SPOKEN").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Manage group members. $WIP_SPOKEN").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Disband Planning. $WIP_SPOKEN").assertIsNotEnabled()
        compose.onNodeWithContentDescription("New Thread. $WIP_SPOKEN").assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.runOnIdle { state.value = state.value.copy(transcript = transcript.copy(state = GroupState(room, working = true))) }
        compose.onNodeWithText("The room is working…").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(transcript = transcript.copy(state = GroupState(room, blocked = true))) }
        compose.onNodeWithText("A bot in this group chat needs your input").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(stale = true, transcript = transcript.copy(state = GroupState(room.copy(disbanded = true)))) }
        compose.onNodeWithText("Disbanded").assertIsDisplayed()
        compose.onNodeWithText("Could not refresh. Showing the last saved view.").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(expired = true, transcript = null) }
        compose.onNodeWithText("This Group Chat’s history is no longer available.").assertIsDisplayed()
    }

    @Test fun productionReadJourneyOpensRoomAndBackReturnsToList() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        val calls = mutableListOf<String>()
        val repository = GroupsRepository({ method, _ ->
            calls += method
            com.hermesagent.mobile.plugins.PluginHostResult.Success(when (method) {
                "groups.capabilities" -> capabilityWire
                "groups.list" -> wire("""{"rooms":[${roomWire()}],"next_offset":null}""")
                "groups.state" -> wire("""{"room":${roomWire(latest = 1)}}""")
                "groups.log" -> logWire(listOf(eventWire()), 1)
                else -> error("Mutation attempted: $method")
            })
        })
        val model = GroupsViewModel(scope, kotlinx.coroutines.flow.MutableStateFlow(GroupReadConnection(repository) { true }),
            kotlinx.coroutines.flow.MutableStateFlow(0L), idleMillis = 1_000_000)
        val actions = GroupsActions(model::open, model::closeRoom, model::refresh, model::setForeground)
        try {
            compose.setContent {
                val state by model.uiState.collectAsState()
                HermesTheme(AppearanceSelection()) { GroupsScreen(state, {}, actions) }
            }
            compose.waitUntil { model.uiState.value.rooms.isNotEmpty() }
            compose.onNodeWithText("Planning").performClick()
            compose.waitUntil { model.uiState.value.transcript != null }
            compose.onNodeWithText("Hello").assertIsDisplayed()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            compose.runOnIdle { model.closeRoom() }
            compose.waitUntil { model.uiState.value.selected == null }
            compose.onNodeWithText("Planning").assertIsDisplayed()
            assertTrue(calls.containsAll(listOf("groups.capabilities", "groups.list", "groups.state", "groups.log")))
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test fun actualLifecycleResumeEffectStopsAndRestartsForeground() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val calls = mutableListOf<Boolean>()
        val actions = GroupsActions(foreground = { calls += it })
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                HermesTheme(AppearanceSelection()) { GroupsScreen(GroupsUiState(), {}, actions) }
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(listOf(true), calls); owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { assertEquals(listOf(true, false), calls); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(listOf(true, false, true), calls); owner.registry.currentState = Lifecycle.State.DESTROYED }
        compose.runOnIdle { assertEquals(listOf(true, false, true, false), calls) }
    }
}
