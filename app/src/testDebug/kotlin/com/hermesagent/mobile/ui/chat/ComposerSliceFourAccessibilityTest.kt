package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.composer.QueuedPrompt
import com.hermesagent.mobile.data.composer.QueuedPromptDelivery
import com.hermesagent.mobile.data.session.ComposerBackgroundProcess
import com.hermesagent.mobile.data.session.ComposerBackgroundProcessState
import com.hermesagent.mobile.data.session.ComposerGoalState
import com.hermesagent.mobile.data.session.ComposerGoalStatus
import com.hermesagent.mobile.data.session.ComposerPreviewArtifact
import com.hermesagent.mobile.data.session.ComposerStatusState
import com.hermesagent.mobile.data.session.ComposerSubagentStatus
import com.hermesagent.mobile.data.session.ComposerTodoState
import com.hermesagent.mobile.data.session.ComposerTodoStatus
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesSpacing
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerSliceFourAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `status stack keeps ordered groups and omits unavailable coding controls`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                ComposerStatusStack(
                    activeSessionId = "session-a",
                    status = ComposerStatusState(
                        goal = ComposerGoalStatus("finish slice", ComposerGoalState.Active, "Finish slice"),
                        todos = listOf(ComposerTodoStatus("todo", "Write tests", ComposerTodoState.InProgress)),
                        subagents = listOf(ComposerSubagentStatus("agent", "Verifier", "tests")),
                        backgroundProcesses = listOf(
                            ComposerBackgroundProcess("process", "Build", ComposerBackgroundProcessState.Running),
                        ),
                        previewArtifacts = listOf(ComposerPreviewArtifact("preview", "Preview")),
                    ),
                )
            }
        }

        // The bounded scroll container clips rows it has scrolled away, so
        // order is asserted over semantics-tree order (depth-first), not
        // geometry.
        val orderedDescriptions = listOf(
            "Goal, collapse",
            "To do, 1, collapse",
            "Subagents, 1, expand",
            "Background, 1, expand",
            "Previews, 1, expand",
        )
        val allDescriptions = compose
            .onAllNodes(androidx.compose.ui.test.SemanticsMatcher("any node") { true })
            .fetchSemanticsNodes()
            .mapNotNull { node ->
                node.config
                    .getOrNull<List<String>>(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
                    ?.singleOrNull()
            }
        val positions = orderedDescriptions.map(allDescriptions::indexOf)
        assertTrue(
            "every group must be present: found=$allDescriptions",
            positions.none { it < 0 },
        )
        assertTrue(
            "stack order violated: $positions",
            positions == positions.sorted(),
        )
        assertEquals(0, compose.onAllNodes(hasText("Coding context")).fetchSemanticsNodes().size)
    }

    @Test
    fun `parked queue exposes explicit 48dp resume edit redirect and review actions`() {
        val entry = QueuedPrompt("queue-one", "Use the safer plan", queuedAtMillis = 1)
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Box(Modifier.width(320.dp)) {
                    Column {
                        ComposerQueueSection(
                            durableSessionId = "session-a",
                            entries = listOf(entry),
                            parked = true,
                            editingEntryId = null,
                            editingText = "",
                            redirectableEntryId = entry.id,
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
                        ComposerQueueSection(
                            durableSessionId = "session-b",
                            entries = listOf(entry.copy(id = "queue-two", delivery = QueuedPromptDelivery.Ambiguous)),
                            parked = false,
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

        val floor = HermesSpacing().touchTarget
        compose.onNodeWithContentDescription("Resume queued messages").assertHeightIsAtLeast(floor)
        // Only the parked section is expanded; the idle section stays collapsed,
        // so exactly one Edit affordance exists until its group is opened.
        assertEquals(1, compose.onAllNodes(hasText("Edit")).fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Send next queued message").assertHeightIsAtLeast(floor)
        compose.onNodeWithContentDescription("Redirect with queued message").assertHeightIsAtLeast(floor)
        compose.onNodeWithContentDescription("Queue, 1 messages, expand").performClick()
        compose.onNodeWithContentDescription("Mark queued message ready after review").assertHeightIsAtLeast(floor)
        compose.onNodeWithText("Review required · this message will not send automatically.").assertIsDisplayed()
    }
}
