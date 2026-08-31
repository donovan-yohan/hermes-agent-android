@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hermesagent.mobile.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesagent.mobile.data.connections.CONNECTION_SEARCH_THRESHOLD
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.connections.connectionMatchesQuery
import com.hermesagent.mobile.data.ssh.redact
import com.hermesagent.mobile.ui.ConnectionsActions
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.common.SearchField
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.gateway.ConnectionsCopy
import com.hermesagent.mobile.ui.gateway.ConnectionsUiState
import com.hermesagent.mobile.ui.gateway.glyph
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * The connection switcher — Desktop's `ConnectionSwitcher`
 * (`apps/desktop/src/app/chat/sidebar/connection-switcher.tsx:40-322` @
 * `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`).
 *
 * Desktop renders no source chrome at all for a single connection
 * (`:118-120`), and neither does this: a phone rail is short, and a control
 * whose only option is the one you are already on is noise. Its dropdown
 * becomes a bottom sheet, its `DropdownMenuRadioGroup` becomes 48dp
 * radio rows with the active one checked, its `DropdownMenuSearch` appears at
 * the same eight-connection threshold, and its trailing "Manage gateways…"
 * item stays last, below a hairline.
 *
 * Mounted twice. The session rail is Desktop's own home for it — the statusbar
 * (`app/shell/hooks/use-statusbar-items.tsx:411,617-621` @ `f82f2dba`) — and
 * the top of the Gateways pane is the owner-approved mobile adaptation
 * recorded in `docs/parity/gateway-connections.md`: a phone's Gateways screen
 * is a destination rather than a pane beside a sidebar that is always there,
 * so without it the person who has just added a gateway must leave the screen
 * to start using it. One composable, two mounts — the trigger, the sheet, the
 * order, the search threshold and the select path are the same on both.
 */
@Composable
fun ConnectionSwitcherBar(
    state: ConnectionsUiState,
    actions: ConnectionsActions,
    /**
     * Where "Manage gateways…" goes, or null on the surface that *is* that
     * destination. Desktop's trailing item navigates to the settings
     * connections tab (`connection-switcher.tsx:234-236` @ `f82f2dba`), which
     * in this app is the Gateways screen — offering it from that screen would
     * be a door back into the room you are standing in. The hairline above it
     * goes with it, rather than leaving a rule under the last connection with
     * nothing beneath it.
     *
     * Deliberately without a default: Desktop's prop is required
     * (`connection-switcher.tsx:40`), so a mount that quietly forgot this
     * would drop a control Desktop always renders.
     */
    onManage: (() -> Unit)?,
    /**
     * The trigger's accessible name, announced as "⟨title⟩: ⟨connection⟩" —
     * Desktop's own `title` prop and its own composition
     * (`connection-switcher.tsx:248,264` @ `f82f2dba`).
     *
     * The default is the value Desktop passes (`:154`), so a mount that says
     * nothing gets Desktop's label rather than losing one — unlike [onManage],
     * where a silent default would drop a control. The Gateways screen
     * overrides it, because there alone the registry heading carries that same
     * string a few rows below; see [ConnectionsCopy.SWITCHER_LABEL].
     */
    title: String = ConnectionsCopy.TITLE,
    modifier: Modifier = Modifier,
) {
    if (!state.switchable) return
    val tokens = HermesTheme.tokens
    val active = state.active ?: return
    val pending = state.pendingId != null
    var sheetVisible by rememberSaveable { mutableStateOf(false) }
    // Which report has been put away. View-local `rememberSaveable`, the same
    // shape `ComposerStatusStack` already uses for its dismissible previews:
    // the failure itself is still true, so it is the *reading* of it that is
    // being recorded, not a change to what happened. Keyed on the attempt
    // rather than a boolean so dismissing one report cannot silence the next.
    //
    // It would be one writer instead of two if the view model owned it, but
    // `ConnectionsActions` is assembled in `MainActivity`, which this change
    // may not touch; #116 S-U5..S-U7 owns that file.
    var dismissedAttempt by rememberSaveable { mutableLongStateOf(0L) }
    val failure = state.switchFailure?.takeIf { it.attempt != dismissedAttempt }

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .clickable(role = Role.Button) { sheetVisible = true }
                .semantics {
                    contentDescription = "$title: ${active.label}"
                    if (pending) stateDescription = ConnectionsCopy.CONNECTING
                }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HermesIconGlyph(active.kind.glyph, color = tokens.textQuaternary, size = 12.sp)
            Text(
                text = active.label,
                style = HermesTheme.type.caption,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (pending) {
                Text(ConnectionsCopy.CONNECTING, style = HermesTheme.type.scaffold, color = tokens.textTertiary)
            }
            HermesIconGlyph(HermesIcon.ChevronDown, color = tokens.textQuaternary, size = 12.sp)
        }

        // Desktop puts this sentence in a toast raised from the same click
        // handler (`connection-switcher.tsx:123-128` @ `f82f2dba`,
        // `notifyError`). This app has no notification stack to raise one into
        // (#73), so it is an inline line under the control that started the
        // switch — the nearest thing on screen to where Desktop's toast points.
        // Recorded as an adaptation in `docs/parity/gateway-connections.md`.
        if (failure != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    // Desktop's toast announces itself by being a toast; an
                    // inline line that simply appears announces nothing, so a
                    // screen reader would never learn the switch failed. Same
                    // politeness `PendingInputSurface` uses for the other
                    // thing that arrives unasked.
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = ConnectionsCopy.switchConnectionFailed(failure.label),
                    style = HermesTheme.type.caption,
                    color = tokens.destructive,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    label = ConnectionsCopy.DISMISS,
                    onClick = { dismissedAttempt = failure.attempt },
                    color = tokens.textTertiary,
                    modifier = Modifier.semantics {
                        contentDescription = "${ConnectionsCopy.DISMISS} ${failure.label}"
                    },
                )
            }
        }
    }

    if (sheetVisible) {
        ConnectionSwitcherSheet(
            state = state,
            onSelect = { id ->
                sheetVisible = false
                actions.onSelect(id)
            },
            onManage = onManage?.let { manage ->
                {
                    sheetVisible = false
                    manage()
                }
            },
            onDismiss = { sheetVisible = false },
        )
    }
}

@Composable
private fun ConnectionSwitcherSheet(
    state: ConnectionsUiState,
    onSelect: (String) -> Unit,
    onManage: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val ordered = state.ordered
    val searchable = ordered.size >= CONNECTION_SEARCH_THRESHOLD
    val query = if (searchable) searchQuery else ""
    val visible = ordered.filter { connection ->
        connectionMatchesQuery(connection, query, listOf(ConnectionsCopy.kindLabel(connection.kind)))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = tokens.cardSurface,
        contentColor = tokens.textPrimary,
        scrimColor = tokens.textPrimary.copy(alpha = .32f),
        modifier = Modifier.testTag("Connection switcher sheet"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(ConnectionsCopy.TITLE, style = HermesTheme.type.screenTitle, color = tokens.textPrimary)
            if (searchable) {
                SearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = ConnectionsCopy.SEARCH_PLACEHOLDER,
                )
            }
            if (visible.isEmpty()) {
                Text(
                    text = ConnectionsCopy.NO_SEARCH_RESULTS,
                    style = HermesTheme.type.caption,
                    color = tokens.textTertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = HermesTheme.spacing.touchTarget)
                        .padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    Modifier.heightIn(max = 320.dp).selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(items = visible, key = SavedConnection::id) { connection ->
                        ConnectionRadioRow(
                            connection = connection,
                            selected = connection.id == state.activeId,
                            onSelect = { onSelect(connection.id) },
                        )
                    }
                }
            }
            if (onManage != null) {
                Hairline()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = HermesTheme.spacing.touchTarget)
                        .clickable(role = Role.Button, onClick = onManage)
                        .semantics { contentDescription = ConnectionsCopy.MANAGE_GATEWAYS }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HermesIconGlyph(HermesIcon.SettingsGear, color = tokens.textTertiary)
                    Text(
                        ConnectionsCopy.MANAGE_GATEWAYS,
                        style = HermesTheme.type.body,
                        color = tokens.textSecondary,
                    )
                }
            }
        }
    }
}

/**
 * One `DropdownMenuRadioItem`: the kind glyph, the label, the non-secret
 * endpoint beneath it, and a check on the connection this device is on.
 *
 * Desktop reports the pending switch on the trigger and nowhere else
 * (`connection-switcher.tsx:133,272`), and picking a row closes this sheet, so
 * no row is ever on screen while its switch is in flight. There is deliberately
 * no per-row pending affordance to go stale.
 */
@Composable
private fun ConnectionRadioRow(
    connection: SavedConnection,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .background(
                if (selected) tokens.sessionRowActiveSurface else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            // One spoken label for the whole row. Without this the glyph, the
            // label and the endpoint line are announced again after it.
            .clearAndSetSemantics {
                this.selected = selected
                role = Role.RadioButton
                contentDescription = buildString {
                    append(connection.label)
                    connection.endpoint?.let { append(". ${redact(it)}") }
                }
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HermesIconGlyph(connection.kind.glyph, color = tokens.textQuaternary)
        Column(Modifier.weight(1f)) {
            Text(
                text = connection.label,
                style = HermesTheme.type.body,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            connection.endpoint?.let { endpoint ->
                Text(
                    text = redact(endpoint),
                    style = HermesTheme.type.scaffold,
                    color = tokens.scaffoldMeta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            HermesIconGlyph(HermesIcon.Check, color = tokens.accent)
        }
    }
}
