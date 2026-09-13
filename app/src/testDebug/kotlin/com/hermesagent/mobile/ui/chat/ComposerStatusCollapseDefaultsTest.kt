package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.composer.QueuedPrompt
import com.hermesagent.mobile.data.session.ComposerBackgroundProcess
import com.hermesagent.mobile.data.session.ComposerBackgroundProcessState
import com.hermesagent.mobile.data.session.ComposerGoalState
import com.hermesagent.mobile.data.session.ComposerGoalStatus
import com.hermesagent.mobile.data.session.ComposerStatusState
import com.hermesagent.mobile.data.session.ComposerSubagentStatus
import com.hermesagent.mobile.data.session.ComposerTodoState
import com.hermesagent.mobile.data.session.ComposerTodoStatus
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The composer status stack opens nothing by itself except the task list.
 *
 * This is Desktop's own contract, asserted there by
 * `apps/desktop/src/app/chat/composer/status-stack/default-collapse.test.tsx:45,73`
 * @ `564aef2946`: only todos auto-expand as activity arrives, a structured goal
 * renders `aria-expanded="false"`, and a manually expanded queue is still
 * expanded after the queue parks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerStatusCollapseDefaultsTest {
    @get:Rule val compose = createComposeRule()

    private val entry = QueuedPrompt("queue-one", "Use the safer plan", queuedAtMillis = 1)

    @Test
    fun `only the task list opens itself as activity arrives`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Box(Modifier.width(360.dp)) {
                    ComposerStatusStack(
                        activeSessionId = "session-a",
                        status = ComposerStatusState(
                            goal = ComposerGoalStatus("goal: ship it", ComposerGoalState.Active, "Ship it"),
                            todos = listOf(ComposerTodoStatus("todo", "Write tests", ComposerTodoState.InProgress)),
                            subagents = listOf(ComposerSubagentStatus("agent", "Verifier", "tests")),
                            backgroundProcesses = listOf(
                                ComposerBackgroundProcess("process", "Build", ComposerBackgroundProcessState.Running),
                            ),
                        ),
                    )
                }
            }
        }

        // The stack's own scroll region clips what it has scrolled past, so
        // presence is the claim here, not geometry.
        compose.onNodeWithContentDescription("Goal active, expand").assertExists()
        compose.onNodeWithContentDescription("Subagents, 1, expand").assertExists()
        compose.onNodeWithContentDescription("Background, 1, expand").assertExists()
        compose.onNodeWithContentDescription("Tasks 0/1, collapse").assertExists()

        // Only the task list's body is composed at all: the goal's title, the
        // subagent's line and the background row all wait behind a header.
        compose.onNodeWithText("Write tests").assertExists()
        compose.onAllNodesWithText("Ship it").assertCountEquals(0)
        compose.onAllNodesWithText("Verifier · tests").assertCountEquals(0)
        compose.onAllNodesWithText("Running · Build").assertCountEquals(0)
    }

    @Test
    fun `the collapsed goal header carries the state Desktop puts in its label`() {
        val state = mutableStateOf(ComposerGoalState.Active)
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Box(Modifier.width(360.dp)) {
                    ComposerStatusStack(
                        activeSessionId = "session-a",
                        status = ComposerStatusState(
                            goal = ComposerGoalStatus("goal line", state.value, "Ship it"),
                        ),
                    )
                }
            }
        }

        // `Goal active` / `Goal waiting` / `Goal paused` / `Goal done`, verbatim
        // from `apps/desktop/src/i18n/en.ts:2894,2896-2898` @ `564aef2946`.
        compose.onNodeWithContentDescription("Goal active, expand").assertIsDisplayed()
        listOf(
            ComposerGoalState.Waiting to "Goal waiting",
            ComposerGoalState.Paused to "Goal paused",
            ComposerGoalState.Done to "Goal done",
        ).forEach { (goalState, label) ->
            compose.runOnIdle { state.value = goalState }
            compose.onNodeWithContentDescription("$label, expand").assertIsDisplayed()
        }
    }

    @Test
    fun `an unrecognised goal line keeps its group open because the header cannot speak for it`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Box(Modifier.width(360.dp)) {
                    ComposerStatusStack(
                        activeSessionId = "session-a",
                        status = ComposerStatusState(
                            goal = ComposerGoalStatus("Unrecognized server line", ComposerGoalState.Unknown),
                        ),
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Goal, collapse").assertIsDisplayed()
        compose.onNodeWithText("Unrecognized server line").assertIsDisplayed()
    }

    @Test
    fun `an active goal that becomes unknown exposes its raw status`() {
        val goal = mutableStateOf(ComposerGoalStatus("goal: ship it", ComposerGoalState.Active, "Ship it"))
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Box(Modifier.width(360.dp)) {
                    ComposerStatusStack(activeSessionId = "session-a", status = ComposerStatusState(goal = goal.value))
                }
            }
        }

        compose.onNodeWithContentDescription("Goal active, expand").assertIsDisplayed()
        compose.runOnIdle {
            goal.value = ComposerGoalStatus("Unrecognized server line", ComposerGoalState.Unknown)
        }

        compose.onNodeWithContentDescription("Goal, collapse").assertIsDisplayed()
        compose.onNodeWithText("Unrecognized server line").assertIsDisplayed()
    }

    @Test
    fun `an automatically opened unknown goal closes when it becomes known`() {
        val goal = mutableStateOf(ComposerGoalStatus("Unrecognized server line", ComposerGoalState.Unknown))
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Box(Modifier.width(360.dp)) {
                    ComposerStatusStack(activeSessionId = "session-a", status = ComposerStatusState(goal = goal.value))
                }
            }
        }

        compose.onNodeWithContentDescription("Goal, collapse").assertIsDisplayed()
        compose.runOnIdle {
            goal.value = ComposerGoalStatus("goal: ship it", ComposerGoalState.Active, "Ship it")
        }

        compose.onNodeWithContentDescription("Goal active, expand").assertIsDisplayed()
        compose.onAllNodesWithText("Ship it").assertCountEquals(0)
    }

    @Test
    fun `a manually expanded goal stays expanded across known unknown known transitions`() {
        val goal = mutableStateOf(ComposerGoalStatus("goal: ship it", ComposerGoalState.Active, "Ship it"))
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Box(Modifier.width(360.dp)) {
                    ComposerStatusStack(activeSessionId = "session-a", status = ComposerStatusState(goal = goal.value))
                }
            }
        }

        compose.onNodeWithContentDescription("Goal active, expand").performClick()
        compose.runOnIdle { goal.value = ComposerGoalStatus("Unrecognized server line", ComposerGoalState.Unknown) }
        compose.onNodeWithText("Unrecognized server line").assertIsDisplayed()
        compose.runOnIdle { goal.value = ComposerGoalStatus("goal: ship it", ComposerGoalState.Active, "Ship it") }

        compose.onNodeWithContentDescription("Goal active, collapse").assertIsDisplayed()
        compose.onNodeWithText("Ship it").assertIsDisplayed()
    }

    @Test
    fun `a parked queue starts collapsed and a park does not open it`() {
        val parked = mutableStateOf(false)
        setQueueContent(parked)

        compose.onNodeWithContentDescription("Queue, 1 messages, expand").assertIsDisplayed()
        compose.runOnIdle { parked.value = true }
        // The park is stated in the header, and its Resume stays reachable
        // there; it no longer throws the whole panel open.
        compose.onNodeWithContentDescription("Queue, 1 messages, parked, expand").assertIsDisplayed()
        compose.onNodeWithContentDescription("Resume queued messages").assertIsDisplayed()
        compose.onAllNodesWithText("Use the safer plan").assertCountEquals(0)
    }

    @Test
    fun `a manually expanded queue survives a park`() {
        val parked = mutableStateOf(false)
        setQueueContent(parked)

        compose.onNodeWithContentDescription("Queue, 1 messages, expand").performClick()
        compose.onNodeWithText("Use the safer plan").assertIsDisplayed()
        compose.runOnIdle { parked.value = true }
        // Desktop dropped the `key={parked ? 'parked' : 'flowing'}` remount in
        // `5b181e511a`; this app's saveable key never included `parked`, so the
        // expansion the reader chose outlives the park.
        compose.onNodeWithContentDescription("Queue, 1 messages, parked, collapse").assertIsDisplayed()
        compose.onNodeWithText("Use the safer plan").assertIsDisplayed()
    }

    private fun setQueueContent(parked: MutableState<Boolean>) {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Box(Modifier.width(360.dp)) {
                    ComposerQueueSection(
                        durableSessionId = "session-a",
                        entries = listOf(entry),
                        parked = parked.value,
                        editingEntryId = null,
                        editingText = "",
                        onEdit = {},
                        onEditTextChange = {},
                        onSaveEdit = {},
                        onCancelEdit = {},
                        onDelete = {},
                        onSendNext = {},
                        onRedirectNow = {},
                        onResume = {},
                        onMarkReadyAfterReview = {},
                    )
                }
            }
        }
    }
}
