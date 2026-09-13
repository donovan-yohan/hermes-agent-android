package com.hermesagent.mobile.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.hermesagent.mobile.data.gateway.PendingInputAction
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputKind
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.gateway.VaultCodePending
import com.hermesagent.mobile.data.gateway.VaultSaveLoginPending
import com.hermesagent.mobile.data.gateway.VaultUnlockPending
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The three vault cards as the person meets them.
 *
 * Copy is diffed against Desktop's own strings
 * (`apps/desktop/src/i18n/en.ts:3898-3922` @
 * `564aef2946c436500a5e80ee117b66b789b3f99a`) and the shape against
 * `apps/desktop/src/components/prompt-overlays.tsx:259-577`: two buttons, the
 * quiet one answering `""` rather than dismissing, and the loud one disabled
 * until the field it needs has something in it.
 *
 * Robolectric, because a `Dialog` is its own window and the assertion is about
 * what is in it, not about a real display.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class VaultPromptDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the unlock card names the manager and keeps it locked without a password`() {
        val answers = mutableListOf<PendingInputAction>()
        show(unlock(), answers)

        compose.onNodeWithText("Unlock 1Password").assertIsDisplayed()
        compose.onNodeWithContentDescription("Keep locked").performClick()
        compose.waitForIdle()

        // "" is an answer, not a dismissal: the turn resumes with the manager
        // still locked (`input-requests.ts:421-423` @ the pin).
        val answer = answers.single() as PendingInputAction.VaultUnlockPassword
        assertEquals(0, answer.password.size)
    }

    @Test
    fun `the unlock card sends the master password once and clears the field`() {
        val answers = mutableListOf<PendingInputAction>()
        show(unlock(), answers)

        compose.onNodeWithContentDescription("Master password entry").performTextInput("hunter2")
        compose.onNodeWithContentDescription("Unlock").performClick()
        compose.waitForIdle()

        val answer = answers.single() as PendingInputAction.VaultUnlockPassword
        assertEquals("hunter2", answer.password.concatToString())
        // The field is empty again the moment the answer leaves, so a
        // recomposition cannot re-send it and nothing is left to screenshot.
        compose.onNodeWithContentDescription("Master password entry").assertIsDisplayed()
        compose.onNodeWithText("hunter2").assertDoesNotExist()
    }

    @Test
    fun `unlock cannot be confirmed with an empty field`() {
        val answers = mutableListOf<PendingInputAction>()
        show(unlock(), answers)

        compose.onNodeWithContentDescription("Unlock").performClick()
        compose.waitForIdle()

        assertTrue(answers.isEmpty())
    }

    @Test
    fun `the save-login card asks for both halves and sends them as one answer`() {
        val answers = mutableListOf<PendingInputAction>()
        show(saveLogin(), answers)

        compose.onNodeWithText("Save your Example login?").assertIsDisplayed()
        // Desktop disables Save until both fields are filled
        // (`prompt-overlays.tsx:417-418`, `:463-465`).
        compose.onNodeWithContentDescription("Email or username entry").performTextInput("  ada  ")
        compose.onNodeWithContentDescription("Save & sign in").performClick()
        compose.waitForIdle()
        assertTrue("an identifier alone is not a login", answers.isEmpty())

        compose.onNodeWithContentDescription("Password entry").performTextInput("s3cret")
        compose.onNodeWithContentDescription("Save & sign in").performClick()
        compose.waitForIdle()

        val answer = answers.single() as PendingInputAction.VaultLogin
        assertEquals("ada", answer.identifier.concatToString())
        assertEquals("s3cret", answer.password.concatToString())
    }

    @Test
    fun `declining the save answers with nothing at all`() {
        val answers = mutableListOf<PendingInputAction>()
        show(saveLogin(), answers)

        compose.onNodeWithContentDescription("Don't save").performClick()
        compose.waitForIdle()

        val answer = answers.single() as PendingInputAction.VaultLogin
        assertEquals(0, answer.identifier.size)
        assertEquals(0, answer.password.size)
    }

    @Test
    fun `the code card strips the spaces a texted code arrives with`() {
        val answers = mutableListOf<PendingInputAction>()
        show(code(), answers)

        compose.onNodeWithText("Verification code for Example").assertIsDisplayed()
        compose.onNodeWithContentDescription("Code entry").performTextInput("123 456")
        compose.onNodeWithContentDescription("Enter code").performClick()
        compose.waitForIdle()

        // `code.replace(/[\s-]/g, '')` (`prompt-overlays.tsx:535` @ the pin).
        assertEquals("123456", (answers.single() as PendingInputAction.VaultCode).code.concatToString())
    }

    @Test
    fun `the code card is skippable and the skip is an empty code`() {
        val answers = mutableListOf<PendingInputAction>()
        show(code(), answers)

        compose.onNodeWithContentDescription("Skip").performClick()
        compose.waitForIdle()

        assertEquals(0, (answers.single() as PendingInputAction.VaultCode).code.size)
    }

    @Test
    fun `a vault prompt draws nothing in the transcript`() {
        // The inline surface is a public part of the chat; a password field
        // there would be in every screenshot of it.
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                PendingInputSurface(
                    pending = unlock(),
                    background = null,
                    isSubmitting = false,
                    onRespond = {},
                    onOpenSession = {},
                )
            }
        }

        compose.onNodeWithText("Unlock 1Password").assertDoesNotExist()
        assertTrue(unlock().isSecurePrompt())
    }

    /**
     * The card, not the whole dialog: a `BasicTextField` inside a Compose
     * `Dialog` never reaches idle under Robolectric, so driving the window
     * would time out rather than assert anything. `SecurePendingDialog` is the
     * window plus this card and nothing else.
     */
    private fun show(pending: PendingInputRequest, answers: MutableList<PendingInputAction>) {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                SecurePromptCard(
                    pending = pending,
                    prompt = requireNotNull(securePrompt(pending)),
                    isSubmitting = false,
                    errorText = null,
                    onRespond = { answers += it },
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun key(kind: PendingInputKind) = PendingInputKey(1L, "runtime-a", "req-1", kind)

    private fun unlock() = VaultUnlockPending(
        key = key(PendingInputKind.VaultUnlock),
        durableSessionId = "durable-a",
        runtimeSessionId = "runtime-a",
        backend = "1password",
        displayName = "1Password",
    )

    private fun saveLogin() = VaultSaveLoginPending(
        key = key(PendingInputKind.VaultSaveLogin),
        durableSessionId = "durable-a",
        runtimeSessionId = "runtime-a",
        origin = "the sign-in page",
        site = "Example",
    )

    private fun code() = VaultCodePending(
        key = key(PendingInputKind.VaultCode),
        durableSessionId = "durable-a",
        runtimeSessionId = "runtime-a",
        site = "Example",
        hint = "",
    )
}
