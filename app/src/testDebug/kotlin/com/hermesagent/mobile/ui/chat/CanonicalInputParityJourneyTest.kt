package com.hermesagent.mobile.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.data.session.*
import com.hermesagent.mobile.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Source-derived public questions; native Canvas proves production paint, not device/backend/provider execution.
 * apps/desktop/src/components/assistant-ui/clarify/pending.tsx:152-207,313-344 @ 379cf1220fe289b5ecfe8bcf61cd4d5126e140cd.
 * apps/desktop/src/components/assistant-ui/clarify/core/question-block.tsx:35-112 @ 379cf1220fe289b5ecfe8bcf61cd4d5126e140cd.
 * apps/desktop/src/components/assistant-ui/clarify/core/confirm-bar.tsx:29-40 @ 379cf1220fe289b5ecfe8bcf61cd4d5126e140cd.
 * apps/desktop/src/lib/guarded-model-switch.ts:57-79 @ 379cf1220fe289b5ecfe8bcf61cd4d5126e140cd. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanonicalInputParityJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var inputMode: InputModeManager
    private fun card(vararg questions: ClarifyQuestion) = ClarifyPending(
        PendingInputKey(1, "runtime-public", "public-request", PendingInputKind.Clarify),
        "durable-public", "runtime-public", questions = questions.toList())
    private fun render(pending: ClarifyPending, submitting: MutableState<Boolean> = mutableStateOf(false),
        respond: (PendingInputAction) -> Unit = {}) {
        compose.setContent { HermesTheme(AppearanceSelection("nous", HermesThemeMode.Light)) {
            inputMode = LocalInputModeManager.current
            Box(Modifier.width(412.dp)) { PendingInputSurface(pending, null, submitting.value, respond, {}) }
        } }
    }

    @Test fun `staged progress counts choices and nonblank drafts while null question remains unanswered`() {
        val actions = mutableListOf<PendingInputAction>()
        render(card(ClarifyQuestion("q1", "Which colours?", listOf("Blue", "Green"), true),
            ClarifyQuestion("q2", "Additional detail?", emptyList(), false)), respond = { actions += it })
        compose.onNodeWithText("0 of 2 answered").assertIsDisplayed()
        compose.onAllNodesWithText("Hermes has a question").assertCountEquals(0)
        compose.onNodeWithText("Blue").performScrollTo().performClick()
        compose.onNodeWithText("1 of 2 answered").assertIsDisplayed()
        compose.onNodeWithContentDescription("Your answer: Which colours?").performScrollTo().performTextInput("Amber")
        compose.onNodeWithText("1 of 2 answered").assertIsDisplayed()
        assertTrue(actions.isEmpty())
        compose.onNodeWithText("Confirm and continue").performClick()
        val answer = actions.single() as PendingInputAction.ClarifyAnswer
        assertEquals("[\"Blue\",\"Amber\"]", answer.answers["q1"])
        assertTrue(answer.answers.containsKey("q2")); assertNull(answer.answers["q2"])
        compose.onNodeWithContentDescription("Your answer: Additional detail?").performScrollTo().performTextInput("   ")
        compose.onNodeWithText("1 of 2 answered").assertIsDisplayed()
        compose.onNodeWithContentDescription("Your answer: Additional detail?").performTextInput("Public detail")
        compose.onNodeWithText("2 of 2 answered").assertIsDisplayed()
    }

    @Test fun `free text only renders prompt and placeholder without choice instructions`() {
        render(card(ClarifyQuestion("q1", "Additional detail?", emptyList(), false)))
        compose.onNodeWithText("1 question").assertIsDisplayed()
        compose.onNodeWithText("Additional detail?").assertIsDisplayed()
        compose.onNodeWithText("Type your answer…").assertIsDisplayed()
        compose.onAllNodesWithText("Pick one").assertCountEquals(0)
        compose.onAllNodesWithText("Select all that apply").assertCountEquals(0)
        compose.onAllNodesWithText("Other (type your answer)").assertCountEquals(0)
    }

    @Test fun `choice badge selects locally and hardware keys leave editable text alone`() {
        val actions = mutableListOf<PendingInputAction>()
        render(card(ClarifyQuestion("q1", "Proceed?", listOf("Yes", "No"), false)), respond = { actions += it })
        compose.onNodeWithText("A", useUnmergedTree = true).assertExists()
        val yes = compose.onNodeWithContentDescription("Answer Proceed?: Yes")
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        yes.performSemanticsAction(SemanticsActions.RequestFocus)
        yes.assertIsFocused()
        yes.performKeyInput { pressKey(Key.B) }
        compose.onNodeWithContentDescription("Answer Proceed?: No").assertIsSelected()
        val label = compose.onNodeWithText("No", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithContentDescription("Answer Proceed?: No").fetchSemanticsNode().boundsInRoot
        assertTrue("choice label must start near the row's leading badge", label.left < row.center.x)
        assertTrue("touch target stays at least 48dp", row.height >= 48f)
        compose.onNodeWithContentDescription("Your answer: Proceed?").performTextInput("Public ")
        compose.onNodeWithContentDescription("Your answer: Proceed?").performKeyInput { pressKey(Key.B) }
        compose.onNodeWithContentDescription("Answer Proceed?: No").assertIsNotSelected()
        assertTrue(actions.isEmpty())
        compose.onNodeWithText("Confirm and continue").performClick()
        assertEquals("Public b", (actions.single() as PendingInputAction.ClarifyAnswer).answers["q1"])
    }

    @Test fun `shortcut modifiers and out of range keys do not change answer while Other key focuses input`() {
        val actions = mutableListOf<PendingInputAction>()
        render(card(ClarifyQuestion("q1", "Proceed?", listOf("Yes", "No"), false)), respond = { actions += it })
        compose.onNodeWithText("No").performClick()
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        val row = compose.onNodeWithContentDescription("Answer Proceed?: Yes")
        row.performSemanticsAction(SemanticsActions.RequestFocus)
        row.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.A); keyUp(Key.CtrlLeft); pressKey(Key.D) }
        compose.onNodeWithContentDescription("Answer Proceed?: No").assertIsSelected()
        row.performKeyInput { pressKey(Key.Three) }
        compose.onNodeWithContentDescription("Your answer: Proceed?").assertIsFocused().performTextInput("Public detail")
        compose.onNodeWithContentDescription("Answer Proceed?: No").assertIsNotSelected()
        assertTrue(actions.isEmpty())
        compose.onNodeWithText("Confirm and continue").performClick()
        assertEquals("Public detail", (actions.single() as PendingInputAction.ClarifyAnswer).answers["q1"])
    }

    @Test fun `submitting shows progress and disables both actions without duplicate answer`() {
        val held = mutableStateOf(false);val actions = mutableListOf<PendingInputAction>()
        render(card(ClarifyQuestion("q1", "Proceed?", listOf("Yes", "No"), false)), held) {
            actions += it; held.value = true
        }
        compose.onNodeWithText("Yes").performClick()
        compose.onNodeWithText("Confirm and continue").performClick()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(1)
        compose.onNodeWithContentDescription("Confirm and continue").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Skip").assertIsNotEnabled().performClick()
        assertEquals(1, actions.size)
    }

    @Test fun `canonical queued header matches count Queued while legacy queue stays attributed`() {
        val key = GatewayAdmissionKey(1, 0, "runtime-public", "queued-public", GatewayAdmissionStatus.Queued, null)
        val resolved = mutableListOf<GatewayAdmissionKey>()
        compose.setContent { HermesTheme(AppearanceSelection("nous", HermesThemeMode.Light)) {
            ComposerStatusStack("durable-public", ComposerStatusState(
                gatewayAdmissions = listOf(ComposerGatewayAdmission(key, "Public queued prompt")),
                gatewayQueuedPrompts = listOf(ComposerGatewayQueuedPrompt("legacy-public", "Legacy queued prompt"))),
                onResolveGatewayAdmission = { resolved += it })
        } }
        compose.onNodeWithText("1 Queued").assertIsDisplayed().performClick()
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        assertEquals(listOf(key), resolved)
        compose.onNodeWithText("Queued next · 1").assertExists()
    }

    @Test fun `model confirm paints destructive token and preserves Keep before Switch`() {
        var expected = 0
        compose.setContent { HermesTheme(AppearanceSelection("nous", HermesThemeMode.Light)) {
            expected = HermesTheme.tokens.destructive.toArgb()
            ModelChangeDialog(ComposerMutationUiState.ConfirmationRequired(1, "public-model", ""), {}, {})
        } }
        compose.waitForIdle()
        val keep = compose.onNodeWithText("Keep current model").fetchSemanticsNode().boundsInWindow
        val apply = compose.onNodeWithText("Switch anyway").fetchSemanticsNode().boundsInWindow
        assertTrue(keep.left < apply.left)
        compose.runOnIdle {
            val type = Class.forName("android.view.WindowManagerGlobal")
            val global = type.getDeclaredMethod("getInstance").invoke(null)
            val field = type.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val view = (field.get(global) as List<View>).last { it.width > 0 && it.height > 0 }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try { view.draw(Canvas(bitmap));assertEquals("actual modal confirmation fill must be destructive", expected,
                bitmap.getPixel(apply.center.x.toInt(), (apply.top + apply.height * 0.12f).toInt())) }
            finally { bitmap.recycle() }
        }
    }
}
