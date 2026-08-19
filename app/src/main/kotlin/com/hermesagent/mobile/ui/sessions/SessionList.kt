package com.hermesagent.mobile.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.label
import com.hermesagent.mobile.ui.common.EmptyState
import com.hermesagent.mobile.ui.common.QuietIconButton
import com.hermesagent.mobile.ui.common.SearchField
import com.hermesagent.mobile.ui.common.SectionLabel
import com.hermesagent.mobile.ui.common.StatusDot
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesTokens

/**
 * Sessions.
 *
 * On a phone this is a drawer; on a wide screen it is the persistent rail.
 * Same composable either way — the layout decides where it lives, not what it
 * is, which is what keeps "switching context is a re-home, not a reboot"
 * (`apps/desktop/AGENTS.md` @ `f82f2dba`) true on both.
 *
 * Flat by construction: dividers only where the calendar bucket changes, no
 * per-row card, no nested rounded boxes. The active row is marked by fill and
 * by an accent edge, not by an outline.
 */
@Composable
fun SessionList(
    rows: List<SessionListRow>,
    activeSessionId: String?,
    query: String,
    canCreate: Boolean,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens

    Column(modifier.fillMaxSize().background(tokens.sidebarSurface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = HermesTheme.spacing.pageInset, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Sessions",
                style = HermesTheme.type.screenTitle,
                color = tokens.textPrimary,
                modifier = Modifier.weight(1f),
            )
            QuietIconButton(
                icon = Icons.Filled.Add,
                contentDescription = "New session",
                onClick = onCreate,
                enabled = canCreate,
                tint = tokens.accent,
            )
        }

        SearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Search sessions",
            modifier = Modifier.padding(horizontal = HermesTheme.spacing.pageInset, vertical = 4.dp),
        )

        if (rows.isEmpty()) {
            EmptyState(
                title = if (query.isBlank()) "No sessions" else "Nothing matches",
                description = when {
                    query.isNotBlank() -> "No session title or preview contains “$query”."
                    canCreate -> "Start one with the + above."
                    else -> "Connect to a Gateway to start a session."
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).testTag("Session list"),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(items = rows, key = { it.key() }) { row ->
                    when (row) {
                        is SessionListRow.Divider -> SectionLabel(
                            text = row.bucket.label(),
                            modifier = Modifier.padding(
                                start = HermesTheme.spacing.pageInset,
                                top = 14.dp,
                                bottom = 4.dp,
                            ),
                        )

                        is SessionListRow.Row -> SessionRow(
                            session = row.session,
                            active = row.session.id == activeSessionId,
                            onClick = { onSelect(row.session.id) },
                        )
                    }
                }
            }
        }

    }
}

private fun SessionListRow.key(): String = when (this) {
    is SessionListRow.Divider -> "divider-${bucket.name}"
    is SessionListRow.Row -> session.id
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    active: Boolean,
    onClick: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    val dot = session.status.dot(tokens)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .clickable(onClick = onClick)
            .testTag("Session row ${session.id}")
            .background(if (active) tokens.widgetSurface else tokens.sidebarSurface)
            .padding(start = HermesTheme.spacing.pageInset - 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .semantics {
                selected = active
                contentDescription = "${session.title}. ${dot.description}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Active edge: a 2dp accent bar, not a box around the row.
        Box(
            Modifier
                .width(2.dp)
                .height(28.dp)
                .background(
                    if (active) tokens.accent else Color.Transparent,
                    RoundedCornerShape(1.dp),
                ),
        )

        StatusDot(
            color = dot.color,
            filled = dot.filled,
            contentDescription = null,
            size = if (session.status == SessionStatus.Idle) 6.dp else 7.dp,
        )

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = session.title,
                style = HermesTheme.type.sessionTitle,
                color = if (active) tokens.textPrimary else tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (session.preview.isNotBlank()) {
                Text(
                    text = session.preview,
                    style = HermesTheme.type.sessionPreview,
                    color = tokens.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The dot lookup. Colour and fill carry the meaning; nothing animates. */
private data class DotStyle(val color: Color, val filled: Boolean, val description: String)

private fun SessionStatus.dot(tokens: HermesTokens): DotStyle = when (this) {
    SessionStatus.NeedsInput -> DotStyle(tokens.statusNeedsInput, true, "Waiting for your answer")
    SessionStatus.Working -> DotStyle(tokens.statusWorking, true, "Running")
    SessionStatus.Stalled -> DotStyle(tokens.statusWorking, false, "Running, nothing arriving")
    SessionStatus.Background -> DotStyle(tokens.textTertiary, false, "Background process still open")
    SessionStatus.Unread -> DotStyle(tokens.statusUnread, true, "Finished, unread")
    SessionStatus.Idle -> DotStyle(tokens.statusIdle, false, "Idle")
}
