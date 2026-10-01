package com.hermesagent.mobile.plugins.bots

import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.ssh.redact
import com.hermesagent.mobile.ui.common.ComingSoonAction
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme

class BotModelActions(
    val onUpdate: (BotModelSelection) -> Unit = {},
    val onSave: () -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onCancelWarning: () -> Unit = {},
)

@Composable
internal fun BotModelEditor(state: BotModelState, actions: BotModelActions) {
    val tokens = HermesTheme.tokens
    var manual by remember(state.ticket) { mutableStateOf(false) }
    val provider = state.providers.firstOrNull { it.matches(state.draft.provider) }
    val freeText = manual || provider == null || state.providers.isEmpty()
    Text("Model", style = HermesTheme.type.sectionLabel, color = tokens.textSecondary)
    Text("Sets this bot’s profile default. It does not change an active chat.",
        style = HermesTheme.type.caption, color = tokens.textSecondary)
    if (state.loading) Text("Reading model…", style = HermesTheme.type.caption)
    if (state.inventoryLoading) Text("Loading model choices…", style = HermesTheme.type.caption)
    state.inventoryMessage?.let { Text(it, style = HermesTheme.type.caption, color = tokens.textSecondary) }
    if (freeText) {
        BotIdentityField("Provider", state.draft.provider, state.editable) {
            actions.onUpdate(state.draft.copy(provider = it))
        }
        BotIdentityField("Model ID", state.draft.model, state.editable) {
            actions.onUpdate(state.draft.copy(model = it))
        }
    }
    // Inventory and manual entry coexist: an unknown pin is never replaced by a catalog default.
    if (state.providers.isNotEmpty()) {
        ModelOptionsMenu("Choose provider", state.editable, state.providers.map { it.slug to it.name }) { slug ->
            actions.onUpdate(state.draft.copy(provider = slug))
        }
        if (provider != null) ModelOptionsMenu("Choose model", state.editable,
            provider.models.map { it to it }) { id -> actions.onUpdate(state.draft.copy(model = id)) }
        Text("${redact(state.draft.provider)} / ${redact(state.draft.model)}",
            style = HermesTheme.type.caption, color = tokens.textSecondary)
        TextButton(if (manual) "Use model choices" else "Enter manually", { manual = !manual }, enabled = state.editable)
    }
    ComingSoonAction("Reset to default")
    if (state.editable && !state.draft.valid) Text("Enter both a provider and model. Clearing the default is not supported here.",
        style = HermesTheme.type.caption, color = tokens.textSecondary)
    state.warning?.let { warning ->
        Text(warning, style = HermesTheme.type.body, color = tokens.textSecondary)
        PrimaryButton("Confirm model change", actions.onConfirm, enabled = state.editable, modifier = Modifier.fillMaxWidth())
        TextButton("Cancel model change", actions.onCancelWarning, enabled = state.editable)
    }
    state.message?.let { Text(it, style = HermesTheme.type.body, color = tokens.textSecondary) }
    PrimaryButton(if (state.busy) "Saving model…" else "Save model", actions.onSave,
        enabled = state.canSave, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ModelOptionsMenu(label: String, enabled: Boolean, items: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(label, { expanded = true }, enabled = enabled && items.isNotEmpty())
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 240.dp), containerColor = HermesTheme.tokens.cardSurface) {
            items.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(redact(name), color = HermesTheme.tokens.textPrimary) },
                    onClick = { expanded = false; onPick(id) })
            }
        }
    }
}
