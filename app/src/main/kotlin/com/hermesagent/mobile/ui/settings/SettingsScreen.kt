package com.hermesagent.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.relay.RELAY_UNAVAILABLE_ON_GATEWAY_MESSAGE
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.system.SystemCopy
import com.hermesagent.mobile.ui.theme.HermesTheme

/** Settings destinations, ordered as their Desktop peers. */
@Composable
fun SettingsScreen(
    onOpenAppearance: () -> Unit,
    onOpenGateways: () -> Unit,
    onOpenSystem: () -> Unit,
    onOpenRelay: () -> Unit,
    /**
     * Whether this Gateway exposes the Relay plugin. A Gateway without it is a
     * fact about that Gateway, so the row stays where Relay lives and says so,
     * rather than disappearing or raising an error somewhere else.
     */
    relayAvailable: Boolean,
    /**
     * Whether a Gateway is connected. The System panel reads the backend's own
     * version, session count and messaging-gateway state over HTTP, so with no
     * connection there is nothing for it to show and nothing for its two
     * actions to act on. The row stays where it is and says so by being
     * disabled, rather than appearing and disappearing under the person.
     */
    systemAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    Column(modifier.fillMaxSize().background(tokens.chatSurface)) {
        SettingsRow(
            label = "Appearance",
            description = "Mode, theme, and chat chrome.",
            traversalIndex = 0f,
            onClick = onOpenAppearance,
        )
        SettingsRow(
            label = "Gateways",
            description = "Connect a Remote Gateway or use Managed SSH.",
            traversalIndex = 1f,
            onClick = onOpenGateways,
        )
        SettingsRow(
            // Verbatim `commandCenter.sectionEntries.system` (`en.ts:1548` @
            // `3ca096de5f8183cb2e0ec23673f294d5978656a3`). Desktop reaches this
            // panel from a command palette, which a phone has no form of, so it
            // becomes a Settings destination and sits with the Gateway rows it
            // is about.
            label = SystemCopy.TITLE,
            description = SystemCopy.DETAIL,
            traversalIndex = 2f,
            enabled = systemAvailable,
            onClick = onOpenSystem,
        )
        SettingsRow(
            label = "Relay channels",
            description = if (relayAvailable) {
                // Desktop's own launcher wording (hermes-plugin-relay @
                // `563a8c8`, `desktop/plugin.js:377`).
                "Channels, transcripts, and messaging live in their own workspace."
            } else {
                RELAY_UNAVAILABLE_ON_GATEWAY_MESSAGE
            },
            traversalIndex = 3f,
            enabled = relayAvailable,
            onClick = onOpenRelay,
        )
    }
}

/** Flat, full-width Settings destination row: one target and one spoken label. */
@Composable
private fun SettingsRow(
    label: String,
    description: String,
    traversalIndex: Float,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val tokens = HermesTheme.tokens
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .clickable(enabled = enabled, onClick = onClick)
                .clearAndSetSemantics {
                    role = Role.Button
                    this.traversalIndex = traversalIndex
                    testTag = "settings-row-${label.lowercase()}"
                    contentDescription = "$label. $description"
                    if (enabled) {
                        onClick(label = "Open $label") {
                            onClick()
                            true
                        }
                    } else {
                        disabled()
                    }
                }
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 12.dp),
        ) {
            Text(
                text = label,
                style = HermesTheme.type.sessionTitle,
                color = if (enabled) tokens.textPrimary else tokens.textQuaternary,
            )
            Text(
                text = description,
                style = HermesTheme.type.caption,
                color = tokens.textTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Hairline(Modifier.padding(start = HermesTheme.spacing.pageInset))
    }
}
