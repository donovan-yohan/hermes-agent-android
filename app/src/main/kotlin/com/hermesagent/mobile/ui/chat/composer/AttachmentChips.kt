package com.hermesagent.mobile.ui.chat.composer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.hermesagent.mobile.data.attachments.AttachmentKind
import com.hermesagent.mobile.data.attachments.AttachmentStage
import com.hermesagent.mobile.data.attachments.ComposerAttachmentDraft
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Locally acquired attachments waiting to leave with their message. Chips show
 * only a display name and truthful state — never a URI, path, or byte count of
 * payload content. Image drafts show the bounded thumbnail decoded from the
 * in-memory bytes. Remove wipes that occurrence's bytes from memory.
 */
@Composable
internal fun AttachmentChipRow(
    attachments: List<ComposerAttachmentDraft>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    thumbnails: Map<String, ImageBitmap> = emptyMap(),
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
                is AttachmentStage.ReviewRequired ->
                    reviewRequiredStateText(stage) to tokens.destructive
                is AttachmentStage.Refused ->
                    stage.safeMessage to tokens.destructive
            }
            val thumbnail = thumbnails[draft.occurrenceId]
            Row(
                Modifier
                    .heightIn(min = HermesTheme.spacing.touchTarget)
                    .background(tokens.widgetSurface, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (draft.kind == AttachmentKind.Image && thumbnail != null) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .testTag("Attachment thumbnail ${draft.occurrenceId}"),
                    )
                } else {
                    HermesIconGlyph(
                        HermesIcon.File,
                        color = tokens.textTertiary,
                        size = 14.sp,
                    )
                }
                // A LazyRow item has unbounded width. Bound the text column so
                // review copy cannot push the Remove action off a phone screen.
                Column(Modifier.widthIn(max = 195.dp)) {
                    Text(
                        draft.displayName,
                        style = HermesTheme.type.caption,
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stateText,
                        style = HermesTheme.type.scaffoldMeta,
                        color = stateColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier
                        // Text lays its glyph at the top of a min-height box.
                        // Center an ordinary caption inside the 48dp target so
                        // the visible label, not just its semantics bounds,
                        // aligns with the attachment content.
                        .heightIn(min = HermesTheme.spacing.touchTarget)
                        .widthIn(min = HermesTheme.spacing.touchTarget)
                        .clickable(role = Role.Button) { onRemove(draft.occurrenceId) }
                        .semantics {
                            contentDescription = "Remove ${draft.displayName}"
                        }
                        .testTag("Attachment remove ${draft.occurrenceId}"),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        "Remove",
                        style = HermesTheme.type.caption,
                        color = tokens.accent,
                        modifier = Modifier.testTag("Attachment remove label ${draft.occurrenceId}"),
                    )
                }
            }
        }
    }
}

private fun formattedByteCount(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun reviewRequiredStateText(stage: AttachmentStage.ReviewRequired): String {
    val normalizedCaption = stage.submittedText
        .replace(WHITESPACE, " ")
        .trim()
    if (normalizedCaption.isEmpty()) return stage.safeMessage
    val submittedCaption = if (normalizedCaption.length <= MAX_REVIEW_CAPTION_CHARS) {
        normalizedCaption
    } else {
        normalizedCaption.take(MAX_REVIEW_CAPTION_CHARS - 1) + "…"
    }
    return "May have been sent: $submittedCaption · Check the session, then remove and attach again if needed."
}

private const val MAX_REVIEW_CAPTION_CHARS = 120
private val WHITESPACE = Regex("\\s+")
