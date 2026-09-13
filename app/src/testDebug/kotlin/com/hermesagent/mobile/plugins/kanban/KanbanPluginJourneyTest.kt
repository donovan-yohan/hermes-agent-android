package com.hermesagent.mobile.plugins.kanban

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesSpacing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KanbanPluginJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `settings launcher board tapped detail back and fixed empty unavailable states`() {
        val task = KanbanTask("x", "A task", "open", body = "line one\nline two")
        val detail = KanbanTaskDetail(task, listOf("parent"), listOf("child"), emptyList())
        var state by mutableStateOf(KanbanUiState(KanbanPhase.Ready, listOf(KanbanColumn("Open", listOf(task)))))
        compose.setContent { HermesTheme { KanbanScreen(state, {}, {}, { state = state.copy(detail = KanbanDetail.Value(detail)) }, { state = state.copy(detail = KanbanDetail.None) }) } }
        compose.onNodeWithText("A task").assertIsDisplayed()
        compose.onNodeWithContentDescription("A task. open").assertHeightIsAtLeast(HermesSpacing().touchTarget).performClick()
        compose.onNodeWithText("line one\nline two").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to board").performClick()
        compose.onNodeWithTag("Kanban board").assertIsDisplayed()
        compose.runOnIdle { state = KanbanUiState(KanbanPhase.Empty) }
        compose.onNodeWithText("No tasks on this board").assertIsDisplayed()
        compose.runOnIdle { state = KanbanUiState(KanbanPhase.Unavailable) }
        compose.onNodeWithText("Kanban unavailable").assertIsDisplayed()
        compose.onNodeWithTag("Kanban refresh").assertHeightIsAtLeast(HermesSpacing().touchTarget)
    }
}
