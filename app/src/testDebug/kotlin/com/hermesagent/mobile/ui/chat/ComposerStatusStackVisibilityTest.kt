package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.data.session.ComposerGatewayQueuedPrompt
import com.hermesagent.mobile.data.session.ComposerGoalState
import com.hermesagent.mobile.data.session.ComposerGoalStatus
import com.hermesagent.mobile.data.session.ComposerStatusState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerStatusStackVisibilityTest {
    @get:Rule val compose = createComposeRule()

    private fun setContent(status: ComposerStatusState?) {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Column {
                    ComposerStatusStack(activeSessionId = "session-a", status = status)
                }
            }
        }
    }

    @Test
    fun `goal with no active state renders no goal group`() {
        setContent(
            ComposerStatusState(
                goal = ComposerGoalStatus("No active goal.", ComposerGoalState.None),
            ),
        )
        compose.onAllNodesWithContentDescription("Goal, collapse").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Composer status").assertCountEquals(0)
    }

    @Test
    fun `unknown goal text still renders its raw payload`() {
        setContent(
            ComposerStatusState(
                goal = ComposerGoalStatus("Unrecognized server line", ComposerGoalState.Unknown),
            ),
        )
        compose.onNodeWithText("Unrecognized server line").assertIsDisplayed()
    }

    @Test
    fun `active goal renders as before`() {
        setContent(
            ComposerStatusState(
                goal = ComposerGoalStatus("finish slice", ComposerGoalState.Active, "Finish slice"),
            ),
        )
        compose.onNodeWithContentDescription("Goal, collapse").assertIsDisplayed()
        compose.onNodeWithText("Finish slice").performClick()
    }

    @Test
    fun `no-goal status alone does not reserve the stack`() {
        // Regression: a None-state goal previously made hasRows true, so the
        // empty stack container stayed mounted above the composer.
        setContent(ComposerStatusState(goal = ComposerGoalStatus("No active goal.", ComposerGoalState.None)))
        compose.onAllNodesWithContentDescription("Composer status").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Goal, collapse").assertCountEquals(0)
    }

    @Test
    fun `Gateway-owned queued prompt stays visible without entering the durable queue`() {
        setContent(
            ComposerStatusState(
                gatewayQueuedPrompts = listOf(
                    ComposerGatewayQueuedPrompt("queued-1", "inspect this screenshot"),
                ),
            ),
        )

        compose.onNodeWithContentDescription("Queued next, 1, collapse").assertIsDisplayed()
        compose.onNodeWithText("inspect this screenshot").assertIsDisplayed()
    }

    @Test
    fun `file reference text renders readably in the Gateway queue group`() {
        setContent(
            ComposerStatusState(
                gatewayQueuedPrompts = listOf(
                    ComposerGatewayQueuedPrompt("queued-2", "@file:`notes.txt` check this"),
                ),
            ),
        )

        compose.onNodeWithContentDescription("Queued next, 1, collapse").assertIsDisplayed()
        compose.onNodeWithText("@file:`notes.txt` check this").assertIsDisplayed()
    }
}
