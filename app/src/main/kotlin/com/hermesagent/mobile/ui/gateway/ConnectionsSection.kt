package com.hermesagent.mobile.ui.gateway

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.connections.CONNECTION_SEARCH_THRESHOLD
import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.connections.connectionMatchesQuery
import com.hermesagent.mobile.data.ssh.redact
import com.hermesagent.mobile.ui.ConnectionsActions
import com.hermesagent.mobile.ui.common.ConfirmSheet
import com.hermesagent.mobile.ui.common.EmptyState
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconButton
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.common.LabelledField
import com.hermesagent.mobile.ui.common.Pill
import com.hermesagent.mobile.ui.common.PillTone
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.SearchField
import com.hermesagent.mobile.ui.common.SegmentedControl
import com.hermesagent.mobile.ui.common.SettingsListRow
import com.hermesagent.mobile.ui.common.SettingsSectionHeading
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * The saved-connections registry, ported from Desktop's
 * `ConnectionsRegistrySection` (`apps/desktop/src/app/settings/connections-registry.tsx:221-888`
 * @ `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`), which lives at the foot of the
 * same Gateways page there (`gateway-settings.tsx:1499-1502`).
 *
 * Same grammar: a `SectionHeading` over `ListRow`s, one kind glyph per row,
 * an `EmptyState` when there is nothing (or nothing matching), search once the
 * list is long, an inline editor, and a destructive confirm before a removal.
 * Desktop's Local and Cloud kinds, its per-connection Test and Make primary
 * actions, its update fan-out, its launch-mode toggle and its proxy-header
 * editor are all deliberately absent — see `docs/parity/gateway-connections.md`.
 */
@Composable
internal fun ConnectionsSection(
    state: ConnectionsUiState,
    actions: ConnectionsActions,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val ordered = state.ordered
    val searchable = ordered.size >= CONNECTION_SEARCH_THRESHOLD
    val query = if (searchable) searchQuery else ""
    val visible = ordered.filter { connection ->
        connectionMatchesQuery(connection, query, listOf(ConnectionsCopy.kindLabel(connection.kind)))
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Hairline()
        SettingsSectionHeading(HermesIcon.Globe, ConnectionsCopy.TITLE)
        Text(ConnectionsCopy.INTRO, style = HermesTheme.type.caption, color = tokens.textTertiary)
        Text(ConnectionsCopy.STAGED_NOTE, style = HermesTheme.type.caption, color = tokens.textTertiary)

        if (searchable) {
            SearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = ConnectionsCopy.SEARCH_PLACEHOLDER,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        when {
            // Desktop holds a spinner here while the registry loads
            // (`connections-registry.tsx:560-563`). A phone reads the store in
            // a frame or two, so this holds the space instead of flashing an
            // empty state that is about to be wrong.
            !state.loaded -> Unit

            ordered.isEmpty() -> EmptyState(
                title = ConnectionsCopy.EMPTY,
                description = "Add one with ${ConnectionsCopy.ADD_CONNECTION} below.",
            )

            visible.isEmpty() -> EmptyState(
                title = ConnectionsCopy.NO_SEARCH_RESULTS,
                description = "Try a different name or address.",
            )

            else -> visible.forEach { connection ->
                ConnectionRow(
                    connection = connection,
                    current = connection.id == state.activeId,
                    canRemove = state.canRemove,
                    onEdit = { actions.onBeginEdit(connection.id) },
                    onRemove = { actions.onRequestRemove(connection.id) },
                )
            }
        }

        val editor = state.editor
        if (editor == null) {
            // Desktop's outline button carries a Plus glyph before its label
            // (`connections-registry.tsx:819-829`); the glyph and the label
            // stay one target here rather than becoming two.
            Row(
                Modifier
                    .heightIn(min = HermesTheme.spacing.touchTarget)
                    .clickable(role = Role.Button, onClick = actions.onBeginAdd)
                    .semantics { contentDescription = ConnectionsCopy.ADD_CONNECTION }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HermesIconGlyph(HermesIcon.Add, color = tokens.accent)
                Text(ConnectionsCopy.ADD_CONNECTION, style = HermesTheme.type.caption, color = tokens.accent)
            }
        } else {
            ConnectionEditor(editor, actions)
        }
    }

    state.removeTarget?.let { target ->
        ConfirmSheet(
            title = ConnectionsCopy.REMOVE_CONFIRM_TITLE,
            description = ConnectionsCopy.removeConfirmDesc(target.label),
            confirmLabel = ConnectionsCopy.REMOVE_CONNECTION,
            cancelLabel = ConnectionsCopy.CANCEL,
            onConfirm = actions.onConfirmRemove,
            onDismiss = actions.onCancelRemove,
            testTag = "Remove connection sheet",
        )
    }
}

/**
 * One saved connection: kind glyph, label, the Current marker, and the
 * non-secret endpoint summary — Desktop's row title and description
 * (`connections-registry.tsx:578-641`).
 *
 * Everything endpoint-shaped goes through [redact] on the way to the screen,
 * so a value typed into the wrong field cannot be read back out of this list.
 */
@Composable
private fun ConnectionRow(
    connection: SavedConnection,
    current: Boolean,
    canRemove: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    SettingsListRow(
        description = connection.summary(),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HermesIconGlyph(connection.kind.glyph, color = tokens.textTertiary)
                Text(
                    text = connection.label,
                    style = HermesTheme.type.bodyStrong,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (current) Pill(ConnectionsCopy.CURRENT_PILL, tone = PillTone.Primary)
            }
        },
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HermesIconButton(
                    icon = HermesIcon.Edit,
                    contentDescription = "${ConnectionsCopy.EDIT_CONNECTION} ${connection.label}",
                    onClick = onEdit,
                )
                HermesIconButton(
                    icon = HermesIcon.Trash,
                    contentDescription = "${ConnectionsCopy.REMOVE_CONNECTION} ${connection.label}",
                    onClick = onRemove,
                    enabled = canRemove,
                )
            }
        },
    )
}

/**
 * The add/edit form. The kind is a choice only while creating; on an existing
 * row it is stated, not offered (`connections-registry.tsx:649-654`).
 */
@Composable
private fun ConnectionEditor(
    editor: ConnectionEditorState,
    actions: ConnectionsActions,
) {
    val tokens = HermesTheme.tokens
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .border(1.dp, tokens.strokeTertiary, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (editor.id == null) {
            SegmentedControl(
                options = ConnectionKind.entries,
                selected = editor.kind,
                label = ConnectionsCopy::kindLabel,
                onSelect = actions.onEditKind,
                describe = ConnectionsCopy::kindDescription,
            )
        } else {
            Text(
                text = ConnectionsCopy.kindLabel(editor.kind),
                style = HermesTheme.type.bodyStrong,
                color = tokens.textPrimary,
            )
        }
        Text(
            text = ConnectionsCopy.kindDescription(editor.kind),
            style = HermesTheme.type.caption,
            color = tokens.textTertiary,
        )

        LabelledField(
            label = ConnectionsCopy.LABEL_TITLE,
            value = editor.label,
            placeholder = ConnectionsCopy.LABEL_PLACEHOLDER,
            onValueChange = actions.onEditLabel,
        )
        Text(ConnectionsCopy.LABEL_DESC, style = HermesTheme.type.caption, color = tokens.textTertiary)

        when (editor.kind) {
            ConnectionKind.Remote -> {
                LabelledField(
                    label = ConnectionsCopy.URL_TITLE,
                    value = editor.url,
                    placeholder = "https://hermes.example.com",
                    onValueChange = actions.onEditUrl,
                    keyboardType = KeyboardType.Uri,
                )
                LabelledField(
                    label = "Sign-in provider (optional)",
                    value = editor.provider,
                    placeholder = "Use the Gateway default",
                    onValueChange = actions.onEditProvider,
                )
                Text(
                    "Each client signs in to this gateway on its own device.",
                    style = HermesTheme.type.caption,
                    color = tokens.textTertiary,
                )
            }

            ConnectionKind.Ssh -> {
                LabelledField(
                    label = ConnectionsCopy.SSH_HOST_TITLE,
                    value = editor.destination,
                    placeholder = "user@host:22",
                    onValueChange = actions.onEditDestination,
                )
                Text(
                    "Changing the host or port asks you to review its host key again.",
                    style = HermesTheme.type.caption,
                    color = tokens.textTertiary,
                )
            }
        }

        editor.error?.let { message ->
            Text(message, style = HermesTheme.type.caption, color = tokens.destructive)
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryButton(
                label = ConnectionsCopy.SAVE,
                onClick = actions.onSaveEditor,
                modifier = Modifier.weight(1f),
                enabled = editor.canSave,
            )
            TextButton(ConnectionsCopy.CANCEL, actions.onCancelEditor, color = tokens.textTertiary)
        }
    }
}

/** Desktop's row description: kind, then the endpoint, then how it signs in. */
private fun SavedConnection.summary(): String {
    val endpoint = endpoint ?: return ConnectionsCopy.kindDescription(kind)
    return redact("${ConnectionsCopy.kindLabel(kind)} · $endpoint · $authModeLabel")
}

/** Desktop's `KIND_ICONS`, restricted to the kinds Android ships (`connections-registry.tsx:26-31`). */
internal val ConnectionKind.glyph: HermesIcon
    get() = when (this) {
        ConnectionKind.Remote -> HermesIcon.Globe
        ConnectionKind.Ssh -> HermesIcon.Terminal
    }
