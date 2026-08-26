package com.hermesagent.mobile.ui.chat.composer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesagent.mobile.data.attachments.AttachmentKind
import com.hermesagent.mobile.data.attachments.AttachmentStage
import com.hermesagent.mobile.data.attachments.ComposerAttachmentDraft
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertTrue
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

        val reviewCopy =
            "May have been sent: inspect this screenshot · Check the session, then remove and attach again if needed."
        val caption = compose.onNodeWithText(reviewCopy).assertIsDisplayed()
            .fetchSemanticsNode().boundsInWindow
        val remove = compose.onNodeWithContentDescription("Remove shot.png").assertIsDisplayed()
            .fetchSemanticsNode().boundsInWindow
        val viewport = compose.onRoot().fetchSemanticsNode().boundsInWindow
        val twoLineHeightWithTolerance = with(compose.density) { 34.sp.toPx() + 1.dp.toPx() }
        assertTrue(
            "Review copy $caption exceeds its two-line bound of $twoLineHeightWithTolerance px",
            caption.height <= twoLineHeightWithTolerance,
        )
        assertTrue(
            "Remove action $remove is clipped outside the $viewport viewport",
            remove.left >= viewport.left &&
                remove.top >= viewport.top &&
                remove.right <= viewport.right &&
                remove.bottom <= viewport.bottom,
        )
    }
}
