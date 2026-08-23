package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.composer.QueuedPrompt
import com.hermesagent.mobile.data.composer.QueuedPromptDelivery
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme

/** A status-stack group with explicit durable queue ownership and edit transaction. */
@Composable
fun ComposerQueueSection(
    durableSessionId: String?,
    entries: List<QueuedPrompt>,
    parked: Boolean,
    editingEntryId: String?,
    editingText: String,
    redirectableEntryId: String? = null,
    onEdit: (String) -> Unit,
    onEditTextChange: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onDelete: (String) -> Unit,
    onSendNext: (String) -> Unit,
    onRedirectNow: (String) -> Unit,
    onResume: () -> Unit,
    onMarkReadyAfterReview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    val tokens = HermesTheme.tokens
    var expanded by rememberSaveable(durableSessionId) { mutableStateOf(parked) }
    LaunchedEffect(parked) { if (parked) expanded = true }

    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, tokens.strokeTertiary, RoundedCornerShape(10.dp))
            .background(tokens.widgetSurface, RoundedCornerShape(10.dp))
            .padding(top = 2.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .clickable { expanded = !expanded }
                .semantics {
                    contentDescription = if (parked) {
                        "Queue, ${entries.size} messages, parked, ${if (expanded) "collapse" else "expand"}"
                    } else {
                        "Queue, ${entries.size} messages, ${if (expanded) "collapse" else "expand"}"
                    }
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (parked) "Queue · ${entries.size} · parked" else "Queue · ${entries.size}",
                style = HermesTheme.type.caption,
                color = tokens.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (parked) {
                TextButton(
                    label = "Resume",
                    onClick = onResume,
                    modifier = Modifier.semantics { contentDescription = "Resume queued messages" },
                )
            }
        }
        if (expanded) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                entries.forEach { entry ->
                    ComposerQueueRow(
                        entry = entry,
                        editingText = if (editingEntryId == entry.id) editingText else null,
                        canRedirectNow = redirectableEntryId == entry.id,
                        onEdit = { onEdit(entry.id) },
                        onEditTextChange = onEditTextChange,
                        onSave = onSaveEdit,
                        onCancel = onCancelEdit,
                        onDelete = { onDelete(entry.id) },
                        onSendNext = { onSendNext(entry.id) },
                        onRedirectNow = { onRedirectNow(entry.id) },
                        onMarkReadyAfterReview = { onMarkReadyAfterReview(entry.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerQueueRow(
    entry: QueuedPrompt,
    editingText: String?,
    canRedirectNow: Boolean,
    onEdit: () -> Unit,
    onEditTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSendNext: () -> Unit,
    onRedirectNow: () -> Unit,
    onMarkReadyAfterReview: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, tokens.strokeQuaternary, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (editingText == null) {
            Text(entry.text, style = HermesTheme.type.caption, color = tokens.textPrimary, maxLines = 3)
            if (entry.delivery == QueuedPromptDelivery.Ambiguous) {
                Text(
                    text = "Review required · this message will not send automatically.",
                    style = HermesTheme.type.scaffoldMeta,
                    color = tokens.destructive,
                )
            } else {
                Text(
                    text = "Ready",
                    style = HermesTheme.type.scaffoldMeta,
                    color = tokens.textTertiary,
                )
            }
            QueueActions(
                entry = entry,
                canRedirectNow = canRedirectNow,
                onEdit = onEdit,
                onDelete = onDelete,
                onSendNext = onSendNext,
                onRedirectNow = onRedirectNow,
                onMarkReadyAfterReview = onMarkReadyAfterReview,
            )
        } else {
            BasicTextField(
                value = editingText,
                onValueChange = onEditTextChange,
                textStyle = HermesTheme.type.caption.copy(color = tokens.textPrimary),
                cursorBrush = SolidColor(tokens.composerRing),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = HermesTheme.spacing.touchTarget)
                    .semantics { contentDescription = "Edit queued message" },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    label = "Cancel",
                    onClick = onCancel,
                    modifier = Modifier.semantics { contentDescription = "Cancel queue edit" },
                )
                TextButton(
                    label = "Save",
                    onClick = onSave,
                    enabled = editingText.isNotBlank(),
                    modifier = Modifier.semantics { contentDescription = "Save queue edit" },
                )
            }
        }
    }
}

@Composable
private fun QueueActions(
    entry: QueuedPrompt,
    canRedirectNow: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSendNext: () -> Unit,
    onRedirectNow: () -> Unit,
    onMarkReadyAfterReview: () -> Unit,
) {
    // Rows group by meaning — delivery (Send next/Redirect) above maintenance
    // (Edit/Delete) — so a ready entry reads as one compact card instead of
    // two scattered action rows. Targets stay ≥48dp tall; the widest delivery
    // row (Send next + Redirect now) fits narrow 320dp screens.
    Column(Modifier.fillMaxWidth()) {
        if (entry.delivery == QueuedPromptDelivery.Ambiguous) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    label = "Mark ready",
                    onClick = onMarkReadyAfterReview,
                    modifier = Modifier.semantics { contentDescription = "Mark queued message ready after review" },
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    label = "Send next",
                    onClick = onSendNext,
                    modifier = Modifier.semantics { contentDescription = "Send next queued message" },
                )
                if (canRedirectNow) {
                    TextButton(
                        label = "Redirect now",
                        onClick = onRedirectNow,
                        modifier = Modifier.semantics { contentDescription = "Redirect with queued message" },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                label = "Edit",
                onClick = onEdit,
                modifier = Modifier.semantics { contentDescription = "Edit queued message" },
            )
            TextButton(
                label = "Delete",
                onClick = onDelete,
                color = HermesTheme.tokens.destructive,
                modifier = Modifier.semantics { contentDescription = "Delete queued message" },
            )
        }
    }
}
