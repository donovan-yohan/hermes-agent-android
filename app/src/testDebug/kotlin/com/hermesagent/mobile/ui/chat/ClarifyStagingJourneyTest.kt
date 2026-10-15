package com.hermesagent.mobile.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.ui.theme.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Desktop pending.tsx:152-207 @ 379cf1220fe289b5ecfe8bcf61cd4d5126e140cd. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClarifyStagingJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `batch edits stage locally then one confirmation includes null skip and combined multi select`() {
        val actions = mutableListOf<PendingInputAction>()
        val pending = ClarifyPending(
            PendingInputKey(1, "runtime-public", "srq-public", PendingInputKind.Clarify),
            "durable-public", "runtime-public",
            questions = listOf(
                ClarifyQuestion("q1", "Which colours?", listOf("Blue", "Green"), true),
                ClarifyQuestion("q2", "Any other detail?", emptyList(), false),
            ),
        )
        compose.setContent {
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                PendingInputSurface(pending, null, false, { actions += it }, {})
            }
        }
        compose.onNodeWithText("Blue").performScrollTo().performClick()
        compose.onNodeWithText("Green").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Your answer: Which colours?").performScrollTo().performTextInput("Amber")
        assertTrue("choice and draft edits must not submit", actions.isEmpty())
        compose.onNodeWithText("Confirm and continue").performClick()
        val answer = actions.single() as PendingInputAction.ClarifyAnswer
        assertEquals(listOf("q1", "q2"), answer.answers.keys.toList())
        assertEquals("[\"Blue\",\"Green\",\"Amber\"]", answer.answers["q1"])
        assertTrue(answer.answers.containsKey("q2"))
        assertEquals(null, answer.answers["q2"])
    }

    @Test
    fun `new card clears prior staged answer and submission latch`() {
        val actions = mutableListOf<PendingInputAction>()
        fun card(id: String) = ClarifyPending(
            PendingInputKey(1, "runtime-public", id, PendingInputKind.Clarify),
            "durable-public", "runtime-public",
            questions = listOf(ClarifyQuestion(id, "Proceed?", listOf("Yes", "No"), false)),
        )
        val pending = mutableStateOf(card("first"))
        compose.setContent {
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                PendingInputSurface(pending.value, null, false, { actions += it }, {})
            }
        }
        compose.onNodeWithText("Yes").performClick()
        assertTrue(actions.isEmpty())
        compose.onNodeWithText("Confirm and continue").performClick()
        compose.runOnIdle { pending.value = card("second") }
        compose.onNodeWithText("No").performScrollTo().performClick()
        compose.onNodeWithText("Confirm and continue").performClick()
        assertEquals(mapOf("second" to "No"), (actions.last() as PendingInputAction.ClarifyAnswer).answers)
    }
}
