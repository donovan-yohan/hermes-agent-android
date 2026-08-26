@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hermesagent.mobile.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import com.hermesagent.mobile.ui.gateway.ConnectionsCopy
import com.hermesagent.mobile.ui.gateway.ConnectionsUiState
import com.hermesagent.mobile.ui.gateway.glyph
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * The session rail's connection switcher — Desktop's `ConnectionSwitcher`
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
 */
@Composable
fun ConnectionSwitcherBar(
    state: ConnectionsUiState,
    actions: ConnectionsActions,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.switchable) return
    val tokens = HermesTheme.tokens
    val active = state.active ?: return
    val pending = state.pendingId != null
    var sheetVisible by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .clickable(role = Role.Button) { sheetVisible = true }
            .semantics {
                contentDescription = "${ConnectionsCopy.TITLE}: ${active.label}"
                if (pending) stateDescription = CONNECTING
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
            Text(CONNECTING, style = HermesTheme.type.scaffold, color = tokens.textTertiary)
        }
        HermesIconGlyph(HermesIcon.ChevronDown, color = tokens.textQuaternary, size = 12.sp)
    }

    if (sheetVisible) {
        ConnectionSwitcherSheet(
            state = state,
            onSelect = { id ->
                sheetVisible = false
                actions.onSelect(id)
            },
            onManage = {
                sheetVisible = false
                onManage()
            },
            onDismiss = { sheetVisible = false },
        )
    }
}

@Composable
private fun ConnectionSwitcherSheet(
    state: ConnectionsUiState,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
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
                            pending = connection.id == state.pendingId,
                            onSelect = { onSelect(connection.id) },
                        )
                    }
                }
            }
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

/**
 * One `DropdownMenuRadioItem`: the kind glyph, the label, the non-secret
 * endpoint beneath it, and a check on the connection this device is on.
 */
@Composable
private fun ConnectionRadioRow(
    connection: SavedConnection,
    selected: Boolean,
    pending: Boolean,
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
            .semantics {
                contentDescription = buildString {
                    append(connection.label)
                    connection.endpoint?.let { append(". ${redact(it)}") }
                    if (pending) append(". $CONNECTING")
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
        if (pending) {
            Text(CONNECTING, style = HermesTheme.type.scaffold, color = tokens.textTertiary)
        } else if (selected) {
            HermesIconGlyph(HermesIcon.Check, color = tokens.accent)
        }
    }
}

/** The one word this surface uses while a switch is in flight. */
private const val CONNECTING = "Connecting…"
