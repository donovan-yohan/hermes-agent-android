package com.hermesagent.mobile

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.hermesagent.mobile.data.session.ComposerTodoState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `tool-name-aliases` capture fixture mounts what it receipts (#284).
 *
 * These are smoke checks on the fixture itself, not on production behaviour:
 * every state in the capture catalog must publish the accessibility evidence the
 * catalog names, or the capture lane would wait out its whole budget for a
 * description that never appears.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h844dp")
class ToolNameAliasesParityFixtureTest {
    @get:Rule
    val compose = createComposeRule()

    private fun setFixture(state: ToolNameAliasesFixtureState) {
        compose.setContent {
            HermesTheme(AppearanceSelection("mono", HermesThemeMode.Dark)) {
                ToolNameAliasesParityFixture(state)
            }
        }
    }

    @Test
    fun theCataloguedStatePublishesTheDescriptionItsStateNames() {
        setFixture(ToolNameAliasesFixtureState.TodoNameAliases)

        // The catalogued description is published by both mounted rows: the
        // legacy spelling and the pinned one resolve to one row (#284).
        compose.onAllNodes(hasContentDescription("Tool Updated todos, done")).assertCountEquals(2)
        compose.onAllNodes(hasText("Todo list", substring = true)).assertCountEquals(0)
    }

    @Test
    fun theComposerPanelIsTheParsersOutput() {
        setFixture(ToolNameAliasesFixtureState.TodoNameAliases)

        // The panel is fed by `latestComposerTodosFromHistory` over stored
        // `todo_list` rows, so the state also covers "old/new stored rows" on the
        // pinned spelling. Both rows are visible and counted the way the real
        // group counts them.
        val todos = toolNameAliasFixtureTodos()
        assertEquals(listOf("outline", "render"), todos.map { it.id })
        assertEquals(ComposerTodoState.InProgress, todos.last().state)

        compose.onNodeWithContentDescription("Tasks 1/2, collapse").assertIsDisplayed()
        compose.onNodeWithContentDescription("In progress task: Render the task panel").assertIsDisplayed()
    }

    @Test
    fun theFixtureRowsUseTheExplicitCountAndTheRealTranscriptRows() {
        setFixture(ToolNameAliasesFixtureState.TodoNameAliases)

        // `count: 2` is explicit so the label is the display table's noun, never
        // an inferred one; the same payload is mounted for both spellings.
        compose.onAllNodes(hasText("2 todos", substring = true)).assertCountEquals(2)
    }

    @Test
    fun anUnknownStateFailsBeforeRendering() {
        val failure = runCatching { ToolNameAliasesFixtureState.parse("goal-active") }.exceptionOrNull()

        assertEquals(IllegalArgumentException::class.java, failure?.javaClass)
    }
}
