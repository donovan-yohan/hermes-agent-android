package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.session.ComposerStatusState
import com.hermesagent.mobile.data.session.ComposerTodoState
import com.hermesagent.mobile.data.session.ComposerTodoStatus
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerStatusSurfaceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `task group renders every row and cancelled work is excluded from progress`() {
        val todos = (1..10).map { index ->
            ComposerTodoStatus("pending-$index", "Pending task $index", ComposerTodoState.Pending)
        } + ComposerTodoStatus("done", "Completed task", ComposerTodoState.Completed) +
            ComposerTodoStatus("cancelled", "Cancelled task", ComposerTodoState.Cancelled)

        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Box(Modifier.width(360.dp)) {
                    ComposerStatusStack(
                        activeSessionId = "session-a",
                        status = ComposerStatusState(todos = todos),
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Tasks 1/12, collapse").assertIsDisplayed()
        compose.onNodeWithContentDescription("Completed task: Completed task").assertExists()
        compose.onNodeWithContentDescription("Cancelled task: Cancelled task").assertExists()
        compose.onNodeWithText("Cancelled task").performScrollTo().assertIsDisplayed()
        todos.forEach { todo -> compose.onNodeWithText(todo.title).assertExists() }
    }

    @Test
    fun `coding row keeps passive shell and distinct live link targets`() {
        var reviewOpens = 0
        var openedUrl: String? = null
        var copiedPath: String? = null
        val status = CodingContext.Available(
            branch = "feat/markdown-rendering",
            worktreePath = "/home/alice/Documents/Programs/hermes-mobile",
            additions = 83,
            deletions = 37,
            pullRequest = CodingPullRequest(
                number = 23,
                url = "https://github.com/acme/hermes-mobile/pull/23",
                state = "open",
                draft = false,
            ),
        )

        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Box(Modifier.width(420.dp)) {
                    CodingStatusRow(
                        context = status,
                        onOpenReview = { reviewOpens += 1 },
                        openExternal = { openedUrl = it },
                        copyPath = { copiedPath = it },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Coding status").assertHasNoClickAction()
        compose.onNodeWithText("feat/markdown-rendering").assertIsDisplayed()
        compose.onNodeWithText("~/Documents/Programs/hermes-mobile").assertExists()
        compose.onNodeWithText("+83").assertIsDisplayed()
        compose.onNodeWithText("−37").assertIsDisplayed()

        compose.onNodeWithContentDescription("Open pull request #23")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(status.pullRequest?.url, openedUrl)

        compose.onNodeWithContentDescription("Review changes, 83 additions, 37 deletions")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, reviewOpens)

        compose.onNodeWithContentDescription("Copy repository path")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(status.worktreePath, copiedPath)
    }

    @Test
    fun `coding row renders nothing until authenticated status is available`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                CodingStatusRow(CodingContext.Unavailable, onOpenReview = {})
            }
        }

        assertEquals(0, compose.onAllNodesWithContentDescription("Coding status").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("+0").fetchSemanticsNodes().size)
    }

    @Test
    fun `coding row mirrors Desktop ahead behind and untracked-only counters`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("mono", HermesThemeMode.Dark)) {
                Box(Modifier.width(420.dp)) {
                    CodingStatusRow(
                        context = CodingContext.Available(
                            branch = "feat/status",
                            worktreePath = "/srv/repo",
                            additions = 0,
                            deletions = 0,
                            ahead = 2,
                            behind = 1,
                            untracked = 3,
                        ),
                        onOpenReview = {},
                    )
                }
            }
        }

        compose.onNodeWithText("↑2").assertIsDisplayed()
        compose.onNodeWithText("↓1").assertIsDisplayed()
        compose.onNodeWithText("3 changed").assertIsDisplayed()
        compose.onNodeWithContentDescription("Review changes, 2 ahead, 1 behind, 3 untracked files")
            .assertHasClickAction()
    }
}
