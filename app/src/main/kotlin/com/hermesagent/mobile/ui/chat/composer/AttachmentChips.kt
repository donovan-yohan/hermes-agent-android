package com.hermesagent.mobile.ui.chat.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.attachments.AttachmentStage
import com.hermesagent.mobile.data.attachments.ComposerAttachmentDraft
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Locally acquired attachments waiting to leave with their message. Chips show
 * only a display name and truthful state — never a URI, path, or byte count of
 * payload content. Remove wipes that occurrence's bytes from memory.
 */
@Composable
internal fun AttachmentChipRow(
    attachments: List<ComposerAttachmentDraft>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag("Attachment chips"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(attachments, key = { it.occurrenceId }) { draft ->
            val (stateText, stateColor) = when (val stage = draft.stage) {
                is AttachmentStage.Reading ->
                    "Reading" to tokens.textTertiary
                is AttachmentStage.Ready ->
                    formattedByteCount(stage.byteCount) to tokens.textSecondary
                is AttachmentStage.Staging ->
                    stage.phaseLabel to tokens.textTertiary
                is AttachmentStage.Staged ->
                    "Added" to tokens.textSecondary
                is AttachmentStage.Refused ->
                    stage.safeMessage to tokens.destructive
            }
            Row(
                Modifier
                    .heightIn(min = HermesTheme.spacing.touchTarget)
                    .background(tokens.widgetSurface, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    draft.displayName,
                    style = HermesTheme.type.caption,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(stateText, style = HermesTheme.type.scaffoldMeta, color = stateColor, maxLines = 1)
                Text(
                    "Remove",
                    style = HermesTheme.type.caption,
                    color = tokens.accent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        // Destructive action: keep the whole touch target at
                        // the 48dp floor, not just the caption text.
                        .heightIn(min = HermesTheme.spacing.touchTarget)
                        .widthIn(min = HermesTheme.spacing.touchTarget)
                        .clickable(role = Role.Button) { onRemove(draft.occurrenceId) }
                        .semantics {
                            contentDescription = "Remove ${draft.displayName}"
                        }
                        .testTag("Attachment remove ${draft.occurrenceId}"),
                )
            }
        }
    }
}

private fun formattedByteCount(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
