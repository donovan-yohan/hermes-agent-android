package com.hermesagent.mobile.ui.chat.composer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.hermesagent.mobile.data.attachments.AttachmentKind
import com.hermesagent.mobile.data.attachments.AttachmentStage
import com.hermesagent.mobile.data.attachments.ComposerAttachmentDraft
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h400dp")
class AttachmentChipRowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `review-required chip renders the bounded possibly-submitted caption`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                AttachmentChipRow(
                    attachments = listOf(
                        ComposerAttachmentDraft(
                            occurrenceId = "attachment-1",
                            durableSessionId = "session-a",
                            displayName = "shot.png",
                            kind = AttachmentKind.Image,
                            stage = AttachmentStage.ReviewRequired(
                                byteCount = 128,
                                safeMessage = "Check the session before retrying.",
                                submittedText = "  inspect\nthis screenshot  ",
                            ),
                        ),
                    ),
                    onRemove = {},
                )
            }
        }

        compose.onNodeWithText(
            "May have been sent: inspect this screenshot",
            substring = true,
        ).assertIsDisplayed()
        compose.onNodeWithText(
            "Check the session, then remove and attach again if needed.",
            substring = true,
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove shot.png").assertIsDisplayed()
    }
}
