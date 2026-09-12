package com.hermesagent.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.notifications.NotificationKind
import com.hermesagent.mobile.data.notifications.SETTABLE_KINDS
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.OutlineButton
import com.hermesagent.mobile.ui.common.SettingsListRow
import com.hermesagent.mobile.ui.common.TokenSwitch
import com.hermesagent.mobile.ui.theme.HermesTheme

/** What the screen renders and what it can change. */
data class NotificationsUiState(
    val enabled: Boolean = true,
    val kinds: Map<NotificationKind, Boolean> = emptyMap(),
    val preview: Boolean = true,
    /**
     * Whether the OS will deliver anything at all.
     *
     * Every switch below is a preference this app stores; none of them can
     * overrule a revoked system grant. A screen that let someone turn six
     * things on while the OS drops all of them would be lying by omission, so
     * the grant gets its own row and its own way out.
     */
    val systemAllowed: Boolean = true,
)

data class NotificationsActions(
    val onEnabledChange: (Boolean) -> Unit = {},
    val onKindChange: (NotificationKind, Boolean) -> Unit = { _, _ -> },
    val onPreviewChange: (Boolean) -> Unit = {},
    val onOpenSystemSettings: () -> Unit = {},
    /** Desktop's `Send test notification` (`en.ts:625`), and worth more here. */
    val onSendTest: () -> Unit = {},
)

/**
 * Desktop's notification settings panel
 * (`app/settings/notifications-settings.tsx` @ `72a3277cd7`) as the phone's own
 * surface.
 *
 * The divergences are ledgered in `docs/parity/notifications.md` and they all
 * come from one fact: this is an OS surface with a permission, a lock screen
 * and channels behind it, and Desktop's is a renderer preference. The kinds
 * with no mobile source are omitted rather than marked, because the ledger
 * classifies them `non-goal` and a non-goal is not a work-in-progress.
 */
@Composable
fun NotificationsScreen(
    state: NotificationsUiState,
    actions: NotificationsActions,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    var testSent by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .background(tokens.chatSurface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HermesTheme.spacing.pageInset),
    ) {
        Text(
            text = NotificationsCopy.INTRO,
            style = HermesTheme.type.caption,
            color = tokens.textTertiary,
            modifier = Modifier.padding(top = 16.dp),
        )

        if (!state.systemAllowed) {
            SystemGrantNotice(actions.onOpenSystemSettings)
            Hairline()
        }

        ToggleRow(
            label = NotificationsCopy.MASTER_LABEL,
            description = NotificationsCopy.MASTER_DESCRIPTION,
            checked = state.enabled,
            enabled = state.systemAllowed,
            tag = NOTIFICATIONS_MASTER_TAG,
            onCheckedChange = actions.onEnabledChange,
        )
        Hairline()

        SectionLabel(NotificationsCopy.KINDS_SECTION)
        SETTABLE_KINDS.forEach { kind ->
            ToggleRow(
                label = NotificationsCopy.label(kind),
                description = NotificationsCopy.description(kind),
                checked = state.kinds[kind] != false,
                // The master switch is the gate the notifier actually reads
                // (`NotificationSettings.allows`), so a per-kind row under a
                // master that is off is a control with no effect. Dimming it
                // says which switch to reach for.
                enabled = state.systemAllowed && state.enabled,
                tag = notificationKindTag(kind),
                onCheckedChange = { on -> actions.onKindChange(kind, on) },
            )
        }
        Hairline()

        SectionLabel(NotificationsCopy.PRIVACY_SECTION)
        ToggleRow(
            label = NotificationsCopy.PREVIEW_LABEL,
            description = NotificationsCopy.PREVIEW_DESCRIPTION,
            checked = state.preview,
            enabled = state.systemAllowed && state.enabled,
            tag = NOTIFICATIONS_PREVIEW_TAG,
            onCheckedChange = actions.onPreviewChange,
        )
        Text(
            text = NotificationsCopy.PRIVACY_FOOTNOTE,
            style = HermesTheme.type.caption,
            color = tokens.textTertiary,
        )
        Hairline(Modifier.padding(top = 16.dp))

        // Delivery can fail for reasons no switch on this screen controls — a
        // revoked grant, Do Not Disturb, a channel the person muted in Android
        // itself. One button that either appears in the shade or does not is
        // the only honest way to tell those apart from here.
        OutlineButton(
            label = NotificationsCopy.TEST_ACTION,
            onClick = {
                testSent = true
                actions.onSendTest()
            },
            enabled = state.systemAllowed,
            modifier = Modifier
                .padding(top = 16.dp)
                .testTag(NOTIFICATIONS_TEST_TAG),
        )
        if (testSent) {
            Text(
                text = NotificationsCopy.TEST_SENT,
                style = HermesTheme.type.caption,
                color = tokens.textTertiary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = HermesTheme.type.sectionLabel,
        color = HermesTheme.tokens.textTertiary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/**
 * The row that appears only when the OS grant is gone.
 *
 * It offers the system screen rather than re-asking: once someone has denied
 * `POST_NOTIFICATIONS` twice, Android stops showing the dialog at all, so a
 * button that requests the permission again is a button that does nothing.
 */
@Composable
private fun SystemGrantNotice(onOpenSystemSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .testTag(NOTIFICATIONS_BLOCKED_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = NotificationsCopy.BLOCKED_TITLE,
            style = HermesTheme.type.bodyStrong,
            color = HermesTheme.tokens.textPrimary,
        )
        Text(
            text = NotificationsCopy.BLOCKED_BODY,
            style = HermesTheme.type.caption,
            color = HermesTheme.tokens.textTertiary,
        )
        OutlineButton(
            label = NotificationsCopy.BLOCKED_ACTION,
            onClick = onOpenSystemSettings,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}

/**
 * One preference, one spoken node.
 *
 * The whole row is the target rather than the switch alone: a 48 dp switch
 * beside a two-line label is the smallest thing on the screen and the hardest
 * to hit, and Desktop's own rows are clickable end to end.
 */
@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsListRow(
        modifier = Modifier
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics { contentDescription = "$label. $description" }
            .testTag(tag),
        description = description,
        action = {
            Box(
                Modifier.size(HermesTheme.spacing.touchTarget),
                contentAlignment = Alignment.Center,
            ) {
                TokenSwitch(on = checked, enabled = enabled)
            }
        },
        title = {
            Text(
                text = label,
                style = HermesTheme.type.bodyStrong,
                color = if (enabled) {
                    HermesTheme.tokens.textPrimary
                } else {
                    HermesTheme.tokens.textTertiary
                },
            )
        },
    )
}

internal const val NOTIFICATIONS_MASTER_TAG: String = "notifications-master"
internal const val NOTIFICATIONS_PREVIEW_TAG: String = "notifications-preview"
internal const val NOTIFICATIONS_BLOCKED_TAG: String = "notifications-blocked"
internal const val NOTIFICATIONS_TEST_TAG: String = "notifications-test"

internal fun notificationKindTag(kind: NotificationKind): String = "notifications-kind-${kind.key}"
