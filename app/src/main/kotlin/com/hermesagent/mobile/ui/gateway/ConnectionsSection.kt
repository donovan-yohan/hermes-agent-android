package com.hermesagent.mobile.ui.gateway

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.connections.CONNECTION_SEARCH_THRESHOLD
import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.connections.connectionMatchesQuery
import com.hermesagent.mobile.data.gateway.DEFAULT_LOCAL_GATEWAY_URL
import com.hermesagent.mobile.data.ssh.redact
import com.hermesagent.mobile.ui.ConnectionsActions
import com.hermesagent.mobile.ui.common.COMING_SOON
import com.hermesagent.mobile.ui.common.ChoiceButton
import com.hermesagent.mobile.ui.common.ComingSoonAction
import com.hermesagent.mobile.ui.common.ConfirmSheet
import com.hermesagent.mobile.ui.common.EmptyState
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconButton
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.common.LabelledField
import com.hermesagent.mobile.ui.common.ModeCardGrid
import com.hermesagent.mobile.ui.common.Pill
import com.hermesagent.mobile.ui.common.PillTone
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.SearchField
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
 * Desktop's Cloud kind, its update fan-out, its launch-mode toggle and its
 * proxy-header editor are deliberately absent, and its per-row `Test` and
 * `Make primary` are present but disabled — see
 * `docs/parity/gateway-connections.md`.
 */
@Composable
internal fun ConnectionsSection(
    state: ConnectionsUiState,
    actions: ConnectionsActions,
    modifier: Modifier = Modifier,
    /**
     * Whether the route pane above this list is currently showing its Connect
     * button (`SshScreen.kt`, `GatewayScreen.kt` — offered only while the
     * gateway is neither connected nor connecting).
     *
     * A row that cannot dial itself explains why and names Connect as the next
     * action; once that connection is up there is no Connect on screen and
     * nothing left to explain, so the sentence would be stale advice about a
     * problem that is over.
     */
    connectOffered: Boolean = true,
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

        if (state.loaded && !state.writable) {
            // The saved document belongs to a newer build and is deliberately
            // left untouched. Saying so beside the list is the difference
            // between "nothing happened" and "nothing was allowed to happen".
            Text(
                ConnectionsCopy.REGISTRY_LOCKED,
                style = HermesTheme.type.caption,
                color = tokens.destructive,
            )
        }

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
                    // Desktop reports a pending switch on the rail trigger
                    // only, because its radio menu closes on the click; this
                    // list stays on screen, so the row that is moving has to
                    // say so itself — and every other row has to stop offering
                    // a switch that would be ignored.
                    pendingId = state.pendingId,
                    connectOffered = connectOffered,
                    canRemove = state.canRemove,
                    onSelect = { actions.onSelect(connection.id) },
                    onEdit = { actions.onBeginEdit(connection.id) },
                    onRemove = { actions.onRequestRemove(connection.id) },
                )
            }
        }

        if (state.loaded && !state.canRemove && ordered.isNotEmpty()) {
            // A control that cannot be used says why, beside itself.
            Text(
                ConnectionsCopy.LAST_CONNECTION_HINT,
                style = HermesTheme.type.caption,
                color = tokens.textTertiary,
            )
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
 * One saved connection: kind glyph, label, the Current marker, the non-secret
 * endpoint summary, and the row's actions — Desktop's row title, description
 * and action cluster (`connections-registry.tsx:578-641`).
 *
 * Desktop's action order is kept (`Test`, `Make primary`, `Edit`, `Remove`),
 * with the switch action ahead of it because on a phone this list *is* a
 * switch surface — see [ConnectionRowActions]. Everything endpoint-shaped goes
 * through [redact] on the way to the screen, so a value typed into the wrong
 * field cannot be read back out of this list.
 */
@Composable
private fun ConnectionRow(
    connection: SavedConnection,
    current: Boolean,
    /** The row a switch is in flight for, if any — see [ConnectionRowActions]. */
    pendingId: String?,
    /** Whether the route pane above is currently offering its Connect button. */
    connectOffered: Boolean,
    canRemove: Boolean,
    onSelect: () -> Unit,
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // The honest landing for a row that is now active but that
                // nothing is going to dial. Whether that is true is
                // `SavedConnection.restorable`'s to say, not this screen's —
                // the same rule the switch controller waits on. Only the
                // sentence is per-kind, because only the reason is. The route
                // pane above this list follows the active row's kind, so the
                // Connect both sentences name is already on screen.
                if (current && !connection.restorable && connectOffered) {
                    Text(
                        text = when (connection.kind) {
                            ConnectionKind.Ssh -> ConnectionsCopy.SSH_NEEDS_CREDENTIAL
                            ConnectionKind.Remote, ConnectionKind.Local -> ConnectionsCopy.NEEDS_CONNECT
                        },
                        style = HermesTheme.type.caption,
                        color = tokens.textTertiary,
                    )
                }
                ConnectionRowActions(
                    connection = connection,
                    current = current,
                    pendingId = pendingId,
                    canRemove = canRemove,
                    onSelect = onSelect,
                    onEdit = onEdit,
                    onRemove = onRemove,
                )
            }
        },
    )
}

/**
 * Desktop's row action cluster, in Desktop's order.
 *
 * Desktop renders these inline rather than behind an overflow menu
 * (`connections-registry.tsx:586-625`), and so does this — but a phone row is
 * narrower than a Desktop one, so the cluster wraps instead of overflowing.
 *
 * Two of Desktop's four are here disabled behind a
 * [com.hermesagent.mobile.ui.common.COMING_SOON]
 * pill rather than left out: `Test` has no route-independent reachability probe
 * on Android yet, and `Make primary` needs the launch-mode registry field this
 * app does not persist. Showing them dimmed says the surface is unfinished;
 * omitting them would say it was never meant to have them.
 *
 * The switch action leads, and exists only here. Desktop switches from its
 * sidebar radio group and its registry does not offer the act at all
 * (`connection-switcher.tsx:212-227`); on a phone the person is already on
 * this screen when they add or fix a gateway, and sending them back to Sessions
 * to start using it is a round trip Desktop never has to make. It is a discrete
 * target rather than a tap on the whole row on purpose: a switch drops the live
 * connection, this endpoint's cached sessions and its unsent drafts, so it must
 * not be what a thumb reaching for Edit lands on.
 */
@Composable
private fun ConnectionRowActions(
    connection: SavedConnection,
    current: Boolean,
    pendingId: String?,
    canRemove: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    // Both facts come from one field, so it travels as one field: a `pending`
    // that disagreed with a `switching` would be a state this row cannot be in,
    // and nothing down here would have caught it.
    val pending = connection.id == pendingId
    val switching = pendingId != null
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // `pending ||`, not just `!current`: the controller writes the active
        // marker *before* it waits for the dial
        // (`ConnectionSwitchController.select`), so for the whole settle window
        // the target row is already `current`. Keying only on `!current` took
        // this control away exactly when it had something to say, leaving a row
        // marked `Current` beside siblings whose `Switch` had gone grey for no
        // stated reason.
        if (pending || !current) {
            TextButton(
                label = if (pending) ConnectionsCopy.CONNECTING else ConnectionsCopy.SWITCH_CONNECTION,
                onClick = onSelect,
                // Disarmed for the whole flight, on every row: the one being
                // switched to has nothing left to ask for, and the others
                // would be asking for a switch that is already being ignored.
                enabled = !switching,
                modifier = Modifier.semantics {
                    contentDescription = "${ConnectionsCopy.SWITCH_CONNECTION} ${connection.label}"
                    if (pending) stateDescription = ConnectionsCopy.CONNECTING
                },
            )
        }
        ComingSoonAction(ConnectionsCopy.TEST_CONNECTION)
        ComingSoonAction(ConnectionsCopy.MAKE_PRIMARY)
        HermesIconButton(
            icon = HermesIcon.Edit,
            contentDescription = "${ConnectionsCopy.EDIT_CONNECTION} ${connection.label}",
            onClick = onEdit,
        )
        HermesIconButton(
            icon = HermesIcon.Trash,
            contentDescription = if (canRemove) {
                "${ConnectionsCopy.REMOVE_CONNECTION} ${connection.label}"
            } else {
                "${ConnectionsCopy.REMOVE_CONNECTION} ${connection.label}. " +
                    ConnectionsCopy.LAST_CONNECTION_HINT
            },
            onClick = onRemove,
            enabled = canRemove,
        )
    }
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
            // Desktop's `grid grid-cols-2 gap-2 @2xl:grid-cols-4`
            // (`connections-registry.tsx:648` @ `f82f2dba`). That one *is* a
            // container query — unlike the mode grid above, which is a viewport
            // query — so this reads the editor's own width, not the window's.
            BoxWithConstraints {
                ModeCardGrid(
                    items = CONNECTION_KIND_CHOICES,
                    columns = if (maxWidth >= KIND_CHOOSER_FOUR_COLUMN) 4 else 2,
                ) { choice ->
                    ChoiceButton(
                        label = choice.label,
                        selected = choice.kind == editor.kind,
                        enabled = choice.kind != null,
                        onClick = { choice.kind?.let(actions.onEditKind) },
                        modifier = Modifier.fillMaxWidth(),
                        trailing = if (choice.kind == null) {
                            { Pill(COMING_SOON) }
                        } else {
                            null
                        },
                    )
                }
            }
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

            // The address and the one credential this route has. Desktop's
            // Local connection needs no credential at all — it is the runtime
            // its own app manages — so this pairs Desktop's *remote* token
            // field (`connections-registry.tsx:721-733` @ `f82f2dba`) with the
            // loopback address, which is what the route actually is.
            ConnectionKind.Local -> {
                LabelledField(
                    label = ConnectionsCopy.URL_TITLE,
                    value = editor.url,
                    placeholder = DEFAULT_LOCAL_GATEWAY_URL,
                    onValueChange = actions.onEditUrl,
                    keyboardType = KeyboardType.Uri,
                )
                LabelledField(
                    label = ConnectionsCopy.TOKEN_TITLE,
                    value = editor.token,
                    placeholder = ConnectionsCopy.TOKEN_PLACEHOLDER,
                    onValueChange = actions.onEditToken,
                    secret = true,
                )
                Text(ConnectionsCopy.TOKEN_DESC, style = HermesTheme.type.caption, color = tokens.textTertiary)
            }
        }

        editor.error?.let { message ->
            Text(message, style = HermesTheme.type.caption, color = tokens.destructive)
        }

        // One limitation, beside the action it qualifies. This app connects to
        // that Hermes; it does not keep it alive, and a row saved here is not a
        // promise that anything is listening.
        if (editor.kind == ConnectionKind.Local) {
            Text(
                ConnectionsCopy.LOCAL_LIMITATION,
                style = HermesTheme.type.caption,
                color = tokens.textTertiary,
            )
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

/**
 * One button in Desktop's registry kind chooser
 * (`connections-registry.tsx:652-665` @ `f82f2dba`).
 *
 * [kind] is `null` for a kind Desktop offers that a row here cannot be. Like
 * the mode cards above, it renders anyway — disabled, behind a "coming soon"
 * pill — because the gate's rule is that unsupported is disabled rather than
 * absent, and a `null` kind is unselectable by construction.
 */
internal data class ConnectionKindChoice(
    val kind: ConnectionKind?,
    val label: String,
)

/**
 * The four kinds Desktop offers on create, in Desktop's order: local, cloud,
 * remote, ssh (`connections-registry.tsx:652` @ `f82f2dba`).
 *
 * Total over [ConnectionKind], and asserted to be. The chooser used to be a
 * `SegmentedControl`, which has no way to render a `selected` value that is
 * not among its `options` — a hand-curated subset could leave a kind selected
 * with nothing lit and no way back. Buttons compute their own `selected`, so
 * that shape is gone; the hazard underneath it is not, because a kind with no
 * button is still a kind the form cannot show or change.
 * `ConnectionsSectionTest` fails if a fourth kind is added without one.
 *
 * Desktop also disables Local once its one managed local entry exists
 * (`connections-registry.tsx:654`) and explains that with `localAddHint`
 * (`en.ts:757`). Neither is ported, deliberately: Desktop's registry holds at
 * most one Local, while this one keys Local rows by normalized loopback
 * *address* — two Termux servers on two ports are two Gateways — so there is
 * no one-Local rule to disable on, a genuine duplicate is refused by address
 * instead, and a hint announcing a rule this app does not have would be false
 * on the device it is on. `cloudAddHint` (`en.ts:758-759`) goes with the Cloud
 * kind: it renders only while the editor's kind *is* cloud, which a disabled
 * Cloud button makes unreachable.
 */
internal val CONNECTION_KIND_CHOICES = listOf(
    ConnectionKindChoice(ConnectionKind.Local, ConnectionsCopy.KIND_LOCAL),
    // Desktop's `cloud`. There is no Android Hermes Cloud sign-in, and
    // `ConnectionKind` deliberately has no member for it: a kind no row can be
    // should be unrepresentable, not merely refused.
    ConnectionKindChoice(null, ConnectionsCopy.KIND_CLOUD),
    ConnectionKindChoice(ConnectionKind.Remote, ConnectionsCopy.KIND_REMOTE),
    ConnectionKindChoice(ConnectionKind.Ssh, ConnectionsCopy.KIND_SSH),
)

/** Every kind a saved row can be, in chooser order. */
internal val OFFERED_CONNECTION_KINDS = CONNECTION_KIND_CHOICES.mapNotNull { it.kind }

/**
 * Desktop's `@2xl` container step (42rem / 672px), read off the editor's own
 * width because Desktop's is a container query, not a viewport one.
 */
private val KIND_CHOOSER_FOUR_COLUMN = 672.dp

/** Desktop's `KIND_ICONS`, restricted to the kinds Android ships (`connections-registry.tsx:26-31`). */
internal val ConnectionKind.glyph: HermesIcon
    get() = when (this) {
        ConnectionKind.Remote -> HermesIcon.Globe
        ConnectionKind.Ssh -> HermesIcon.Terminal
        ConnectionKind.Local -> HermesIcon.Monitor
    }
