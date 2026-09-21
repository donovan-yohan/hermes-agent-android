package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * The sentence a failed session open reports
 * (`ChatViewModel.openAndAdopt`).
 *
 * It is the ViewModel's own constant, and its only other reader is the chat
 * pane's failure surface, which takes the copy from [SessionOpenState.Failed] and
 * never from this name. The composer's status line deliberately does *not* repeat
 * it: the notice that carries this sentence is the one notice whose home moved.
 */
internal const val SESSION_OPEN_FAILED_COPY = "This session could not be opened. Check the Gateway and try again."

/**
 * A session that could not be opened, or whose history could not be read as part
 * of opening it.
 *
 * It belongs to the **chat pane**, not to the composer. The composer's status
 * line answers something the person just did *in this chat*; a chat that never
 * arrived has no chat to act in, so the failure is drawn where the chat would
 * have been, with the one action that can still resolve it. The draft is
 * untouched: a failed open is not a reason to throw away typed text.
 *
 * The classifier reads [SessionOpenState.Failed] — a typed fact carrying the id
 * that failed — and never the notice's prose. `ChatNotice` knows its escape only
 * as [ChatNoticeAction], whose sole entry is a live-owner refusal, so a notice
 * with no `sessionOpen` behind it is always some *other* report and keeps its
 * existing home on the composer. `ChatViewModel.retrySessionOpen` owns the retry
 * itself, and it re-opens without rehoming precisely so the draft survives.
 */
internal fun sessionOpenFailure(state: ChatUiState): SessionOpenState.Failed? =
    state.sessionOpen as? SessionOpenState.Failed

/**
 * The chat pane's report of a failed open, with the retry that re-opens it.
 *
 * Retry is the ViewModel's own re-open of the id that failed — not a navigation
 * — so the reader stays in this chat with their draft and composer scope intact.
 */
@Composable
internal fun SessionOpenFailurePanel(
    failure: SessionOpenState.Failed,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val tokens = HermesTheme.tokens
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("Session open failure")
            .border(1.dp, tokens.destructive.copy(alpha = 0.35f), shape)
            .background(tokens.destructive.copy(alpha = 0.07f), shape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = failure.message,
                style = HermesTheme.type.caption,
                color = tokens.destructive,
            )
            // The bounded, redacted internal cause, when there is one. It is what
            // tells a real defect apart from a Gateway that is down, and it is
            // safe to show: no transcript, no secret.
            failure.detail?.takeIf(String::isNotBlank)?.let { detail ->
                Text(
                    text = detail,
                    style = HermesTheme.type.scaffoldMeta,
                    color = tokens.textTertiary,
                    modifier = Modifier.testTag("Session open failure detail"),
                )
            }
        }
        if (onRetry != null) {
            TextButton(
                label = "Retry",
                onClick = onRetry,
                color = tokens.destructive,
                modifier = Modifier.semantics { contentDescription = "Retry opening the session" },
            )
        }
    }
}
