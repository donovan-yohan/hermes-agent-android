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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.theme.HermesTheme

/** Phase-1 Settings destinations, ordered as their Desktop peers. */
@Composable
fun SettingsScreen(
    onOpenAppearance: () -> Unit,
    onOpenGateways: () -> Unit,
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
            description = "Configure and test SSH access to a Hermes host.",
            traversalIndex = 1f,
            onClick = onOpenGateways,
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
) {
    val tokens = HermesTheme.tokens
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .clickable(onClick = onClick)
                .clearAndSetSemantics {
                    role = Role.Button
                    this.traversalIndex = traversalIndex
                    testTag = "settings-row-${label.lowercase()}"
                    contentDescription = "$label. $description"
                    onClick(label = "Open $label") {
                        onClick()
                        true
                    }
                }
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 12.dp),
        ) {
            Text(text = label, style = HermesTheme.type.sessionTitle, color = tokens.textPrimary)
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
