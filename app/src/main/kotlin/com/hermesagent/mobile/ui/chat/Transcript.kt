package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.markdown.InlineSpan
import com.hermesagent.mobile.data.markdown.MarkdownBlock
import com.hermesagent.mobile.data.markdown.parseMarkdown
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.ui.common.EmptyState
import com.hermesagent.mobile.ui.common.ErrorState
import com.hermesagent.mobile.ui.common.ScaffoldRow
import com.hermesagent.mobile.ui.common.StatusDot
import com.hermesagent.mobile.ui.common.WorkingDots
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * The transcript.
 *
 * Grammar taken from Desktop, and the two halves are deliberately asymmetric
 * (`apps/desktop/src/components/assistant-ui/thread/` @ `f82f2dba`):
 *
 * - **The user speaks in a bubble** — a soft `--dt-user-bubble` fill with a
 *   hairline, aligned to the end (`user-message.tsx:67`).
 * - **The assistant does not.** Its prose is flat on the chat surface, full
 *   width, no card (`assistant-message.tsx:189-197`). This is what stops the
 *   transcript becoming the generic Material card pile.
 * - **Tool runs are scaffolding, not messages** — one quiet line in the
 *   scaffold colour, with the payload as an inset (`scaffold-row.tsx`).
 */
@Composable
fun Transcript(
    entries: List<TranscriptEntry>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val spacing = HermesTheme.spacing

    if (entries.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = "No messages yet",
                description = "Ask something to see a demo turn stream. Nothing leaves the device in this build.",
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(spacing.blockGap),
    ) {
        items(items = entries, key = { it.id }) { entry ->
            when (entry) {
                is UserTurn -> UserBubble(entry)
                is AssistantTurn -> AssistantProse(entry)
                is ToolActivity -> ToolRow(entry)
            }
        }
    }
}

@Composable
private fun UserBubble(turn: UserTurn) {
    val tokens = HermesTheme.tokens
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .background(tokens.userBubble, RoundedCornerShape(14.dp))
                .border(1.dp, tokens.userBubbleBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp)
                // Keep the bubble as one accessible message. Merging removes
                // the duplicate readable Text child while preserving any
                // descendant actions if the bubble gains one later.
                .semantics(mergeDescendants = true) {
                    contentDescription = "You said: ${turn.text}"
                },
        ) {
            // The parent retains its accessible label and any future actions;
            // only this visual leaf is silent so TalkBack does not read the
            // message twice through the merged subtree.
            Text(
                turn.text,
                style = HermesTheme.type.body,
                color = tokens.textPrimary,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/**
 * What a screen reader hears when a turn starts producing.
 *
 * Deliberately constant. A live region re-announces whenever its description
 * changes, so anything derived from the streamed text would read the reply
 * aloud a token at a time — the sighted equivalent of the dots being the only
 * cue is one announcement, not a running commentary. The finished prose is
 * readable as ordinary text once it lands.
 */
private const val WORKING_STATUS = "Hermes is working"

@Composable
private fun AssistantProse(turn: AssistantTurn) {
    val blocks = remember(turn.markdown) { parseMarkdown(turn.markdown) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = HermesTheme.spacing.textIndent),
        verticalArrangement = Arrangement.spacedBy(HermesTheme.spacing.turnGap),
    ) {
        for ((index, block) in blocks.withIndex()) {
            MarkdownBlockView(block)
            if (turn.streaming && index == blocks.lastIndex) {
                WorkingDots(status = WORKING_STATUS)
            }
        }

        if (blocks.isEmpty() && turn.streaming) {
            WorkingDots(status = WORKING_STATUS)
        }

        if (turn.stopped) {
            ScaffoldRow(label = "Stopped by you")
        }

        turn.error?.let { message ->
            ErrorState(title = "That turn failed", description = message)
        }
    }
}

@Composable
private fun ToolRow(activity: ToolActivity) {
    val tokens = HermesTheme.tokens
    val stateLabel = when (activity.state) {
        ToolState.Running -> "running"
        ToolState.Done -> "done"
        ToolState.Failed -> "failed"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Tool ${activity.label}, $stateLabel" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ScaffoldRow(
            label = activity.label,
            meta = if (activity.state == ToolState.Done) "${activity.elapsedSeconds}s" else null,
            leading = {
                when (activity.state) {
                    ToolState.Running -> WorkingDots(Modifier.size(width = 22.dp, height = 8.dp))
                    ToolState.Done -> StatusDot(tokens.statusUnread, filled = true, contentDescription = null, size = 6.dp)
                    ToolState.Failed -> StatusDot(tokens.destructive, filled = true, contentDescription = null, size = 6.dp)
                }
            },
        )
        // The inline widget: shared radius, mode-derived fill, no border
        // (`apps/desktop/src/components/chat/widget-shell.ts:12`).
        Text(
            text = activity.detail,
            style = HermesTheme.type.code,
            color = tokens.scaffoldMeta,
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.widgetSurface, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock) {
    val tokens = HermesTheme.tokens
    when (block) {
        is MarkdownBlock.Paragraph ->
            Text(block.spans.annotated(), style = HermesTheme.type.body, color = tokens.textPrimary)

        is MarkdownBlock.Heading ->
            Text(
                block.spans.annotated(),
                style = if (block.level <= 2) HermesTheme.type.screenTitle else HermesTheme.type.bodyStrong,
                color = tokens.textPrimary,
            )

        is MarkdownBlock.Bullets ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (item in block.items) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", style = HermesTheme.type.body, color = tokens.textTertiary)
                        Text(item.annotated(), style = HermesTheme.type.body, color = tokens.textPrimary)
                    }
                }
            }

        is MarkdownBlock.CodeFence -> CodeFenceView(block)
    }
}

/**
 * A fence keeps its own horizontal scroll: wrapping code is worse than
 * scrolling it, and the page body must never scroll sideways.
 */
@Composable
private fun CodeFenceView(block: MarkdownBlock.CodeFence) {
    val tokens = HermesTheme.tokens
    Column(
        Modifier
            .fillMaxWidth()
            .background(tokens.widgetSurface, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        block.language?.let {
            Text(it, style = HermesTheme.type.sectionLabel, color = tokens.textTertiary)
        }
        Text(
            text = block.code,
            style = HermesTheme.type.code,
            color = tokens.textSecondary,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

/** Inline spans as one annotated string, so text still selects and wraps. */
@Composable
private fun List<InlineSpan>.annotated(): AnnotatedString {
    val tokens = HermesTheme.tokens
    val mono = HermesTheme.type.code.fontFamily
    return buildAnnotatedString {
        for (span in this@annotated) {
            when (span) {
                is InlineSpan.Plain -> append(span.text)
                is InlineSpan.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(span.text) }
                is InlineSpan.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
                is InlineSpan.Code -> withStyle(
                    SpanStyle(
                        fontFamily = mono,
                        background = tokens.inlineCodeBackground,
                        color = tokens.inlineCodeForeground,
                    ),
                ) { append(span.text) }
            }
        }
    }
}
