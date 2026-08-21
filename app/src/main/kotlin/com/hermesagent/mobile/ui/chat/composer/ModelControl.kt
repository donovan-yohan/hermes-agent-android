@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hermesagent.mobile.ui.chat.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.composer.ComposerModelSelection
import com.hermesagent.mobile.data.composer.FastMode
import com.hermesagent.mobile.data.composer.ModelCatalog
import com.hermesagent.mobile.data.composer.ModelOption
import com.hermesagent.mobile.data.composer.ModelProvider
import com.hermesagent.mobile.data.composer.ModelControlsSnapshot
import com.hermesagent.mobile.data.composer.ReasoningEffort
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.theme.HermesTheme

/** Model/provider control plus its phone-safe catalog sheet. */
@Composable
internal fun ModelControl(
    catalog: ModelCatalog?,
    controls: ModelControlsSnapshot,
    isLiveSession: Boolean,
    isManualNewDraft: Boolean,
    isLoading: Boolean,
    error: String?,
    isSaving: Boolean,
    isDeferred: Boolean,
    onSelectModel: (ComposerModelSelection) -> Unit,
    onSelectReasoning: (ReasoningEffort) -> Unit,
    onSelectFast: (FastMode) -> Unit,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val tokens = HermesTheme.tokens
    val selection = controls.selection ?: catalog?.effectiveSelection
    val selectedProvider = catalog?.providers?.firstOrNull { it.id == selection?.provider }
    val selectedOption = selectedProvider?.models?.firstOrNull { it.id == selection?.model }
    val modelLabel = selectedOption?.label
        ?: selection?.model?.takeIf(String::isNotBlank)
        ?: "Loading model"
    val providerLabel = selectedProvider?.label
        ?: selection?.provider?.takeIf(String::isNotBlank)
    val scopeLabel = when {
        isDeferred -> "Applies after this turn"
        isSaving -> "Saving selection"
        isLiveSession -> "Current session"
        isManualNewDraft -> "New chats · saved selection"
        else -> "New chats"
    }
    val description = buildString {
        append("Open model controls. ")
        append(
            if (isLoading) {
                "Loading model choices"
            } else {
                listOfNotNull(modelLabel, providerLabel?.let { "from $it" }, scopeLabel).joinToString(". ")
            },
        )
        error?.let { append(". $it") }
    }

    Row(
        modifier = modifier
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .widthIn(min = HermesTheme.spacing.touchTarget)
            .clickable(role = Role.Button) { open = true }
            .semantics {
                contentDescription = description
                stateDescription = scopeLabel
            }
            .testTag("Composer model control")
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = modelLabel,
                style = HermesTheme.type.caption,
                color = if (isLoading) tokens.textTertiary else tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val modifiers = listOfNotNull(
                providerLabel,
                controls.reasoning?.wireValue?.takeUnless { it == "none" },
                controls.fast?.wireValue?.takeUnless { it == "normal" },
            ).joinToString(" · ")
            if (modifiers.isNotBlank() || isDeferred) {
                Text(
                    text = if (isDeferred) "Applies after this turn" else modifiers,
                    style = HermesTheme.type.scaffoldMeta,
                    color = if (isDeferred) tokens.accent else tokens.scaffoldMeta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isManualNewDraft && !isLiveSession) {
            Box(
                Modifier.size(6.dp).background(tokens.accent, RoundedCornerShape(3.dp)).semantics {
                    contentDescription = "Saved new-chat model selection"
                },
            )
        }
        HermesIconGlyph(HermesIcon.ChevronDown, color = tokens.textTertiary)
    }

    if (open) {
        ModelControlSheet(
            catalog = catalog,
            controls = controls,
            isLiveSession = isLiveSession,
            isLoading = isLoading,
            error = error,
            isSaving = isSaving,
            isDeferred = isDeferred,
            onDismiss = {
                open = false
                onDismiss()
            },
            onSelectModel = onSelectModel,
            onSelectReasoning = onSelectReasoning,
            onSelectFast = onSelectFast,
        )
    }
}

@Composable
private fun ModelControlSheet(
    catalog: ModelCatalog?,
    controls: ModelControlsSnapshot,
    isLiveSession: Boolean,
    isLoading: Boolean,
    error: String?,
    isSaving: Boolean,
    isDeferred: Boolean,
    onDismiss: () -> Unit,
    onSelectModel: (ComposerModelSelection) -> Unit,
    onSelectReasoning: (ReasoningEffort) -> Unit,
    onSelectFast: (FastMode) -> Unit,
) {
    val tokens = HermesTheme.tokens
    var modelQuery by remember { mutableStateOf("") }
    val selected = controls.selection ?: catalog?.effectiveSelection
    val selectedOption = catalog?.providers
        ?.firstOrNull { it.id == selected?.provider }
        ?.models?.firstOrNull { it.id == selected?.model }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = tokens.cardSurface,
        contentColor = tokens.textPrimary,
        scrimColor = tokens.textPrimary.copy(alpha = .32f),
        modifier = Modifier.testTag("Composer model sheet"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Model controls", style = HermesTheme.type.screenTitle, color = tokens.textPrimary)
            Text(
                text = when {
                    isDeferred -> "Applies after this turn"
                    isSaving -> "Saving this selection…"
                    isLiveSession -> "This changes only the current session."
                    else -> "Saved for new chats only."
                },
                style = HermesTheme.type.caption,
                color = if (isDeferred) tokens.accent else tokens.textTertiary,
            )
            error?.let { Text(it, style = HermesTheme.type.caption, color = tokens.destructive) }
            BasicTextField(
                value = modelQuery,
                onValueChange = { modelQuery = it },
                singleLine = true,
                textStyle = HermesTheme.type.body.copy(color = tokens.textPrimary),
                cursorBrush = SolidColor(tokens.composerRing),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = HermesTheme.spacing.touchTarget)
                    .background(tokens.widgetSurface, RoundedCornerShape(8.dp))
                    .semantics { contentDescription = "Search models" }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (modelQuery.isEmpty()) Text("Search models", style = HermesTheme.type.body, color = tokens.textTertiary)
                    inner()
                },
            )
            if (isLoading) {
                Text("Loading model choices…", style = HermesTheme.type.body, color = tokens.textTertiary,
                    modifier = Modifier.heightIn(min = HermesTheme.spacing.touchTarget).padding(vertical = 12.dp))
            } else if (catalog == null || catalog.providers.isEmpty()) {
                Text("Model choices are unavailable. Reconnect to the Gateway and try again.", style = HermesTheme.type.body, color = tokens.textTertiary,
                    modifier = Modifier.heightIn(min = HermesTheme.spacing.touchTarget).padding(vertical = 12.dp))
            } else {
                val visibleProviders = catalog.providers.mapNotNull { provider ->
                    val query = modelQuery.trim()
                    val models = provider.models.filter { option ->
                        query.isBlank() || option.label.contains(query, ignoreCase = true) ||
                            option.id.contains(query, ignoreCase = true) || provider.label.contains(query, ignoreCase = true)
                    }
                    provider.takeIf { models.isNotEmpty() }?.copy(models = models)
                }
                LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    visibleProviders.forEach { provider ->
                        item(key = "provider:${provider.id}") {
                            Text(provider.label, style = HermesTheme.type.sectionLabel, color = tokens.textTertiary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                        }
                        items(provider.models, key = { "${provider.id}:${it.id}" }) { option ->
                            ModelOptionRow(
                                provider,
                                option,
                                selected,
                                enabled = !isSaving,
                                onSelect = onSelectModel,
                            )
                        }
                    }
                }
                if (visibleProviders.isEmpty()) {
                    Text("No matching models", style = HermesTheme.type.caption, color = tokens.textTertiary,
                        modifier = Modifier.heightIn(min = HermesTheme.spacing.touchTarget).padding(vertical = 12.dp))
                }
            }
            HorizontalDivider(color = tokens.strokeTertiary)
            Text("Reasoning", style = HermesTheme.type.sectionLabel, color = tokens.textTertiary)
            val reasoningSupported = selectedOption?.supportsReasoning == true
            val reasoningDisabledReason = when {
                !reasoningSupported -> "Reasoning is not available for this model"
                isSaving -> "Saving this selection"
                isDeferred -> "Available after this turn"
                else -> null
            }
            ReasoningChoices(
                selected = controls.reasoning,
                disabledReason = reasoningDisabledReason,
                onSelect = onSelectReasoning,
            )
            if (!reasoningSupported) {
                Text(
                    "Reasoning is not available for this model.",
                    style = HermesTheme.type.scaffoldMeta,
                    color = tokens.scaffoldMeta,
                )
            }
            HorizontalDivider(color = tokens.strokeTertiary)
            val fastSupported = selectedOption?.supportsFast == true
            FastModeRow(
                selected = controls.fast,
                supported = fastSupported,
                enabled = fastSupported && !isSaving && !isDeferred,
                disabledReason = when {
                    !fastSupported -> "Fast mode is not available for this model"
                    isSaving -> "Saving this selection"
                    isDeferred -> "Available after this turn"
                    else -> null
                },
                onSelect = onSelectFast,
            )
        }
    }
}

@Composable
private fun ModelOptionRow(
    provider: ModelProvider,
    option: ModelOption,
    selected: ComposerModelSelection?,
    enabled: Boolean,
    onSelect: (ComposerModelSelection) -> Unit,
) {
    val tokens = HermesTheme.tokens
    val chosen = provider.id == selected?.provider && option.id == selected.model
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .clickable(enabled = enabled, role = Role.RadioButton) {
                onSelect(ComposerModelSelection(option.id, provider.id))
            }
            .semantics {
                contentDescription = "Use ${option.label} from ${provider.label}"
                stateDescription = if (chosen) "Selected" else "Not selected"
                if (!enabled) disabled()
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(8.dp).border(1.dp, if (chosen) tokens.accent else tokens.strokeSecondary, RoundedCornerShape(4.dp))
                .background(if (chosen) tokens.accent else tokens.cardSurface, RoundedCornerShape(4.dp)),
        )
        Text(option.label, style = HermesTheme.type.body, color = tokens.textPrimary, modifier = Modifier.weight(1f))
        if (option.supportsFast) Text("Fast", style = HermesTheme.type.scaffoldMeta, color = tokens.scaffoldMeta)
    }
}

@Composable
private fun ReasoningChoices(
    selected: ReasoningEffort?,
    disabledReason: String?,
    onSelect: (ReasoningEffort) -> Unit,
) {
    val options = listOf(
        ReasoningEffort.None,
        ReasoningEffort.Low,
        ReasoningEffort.Medium,
        ReasoningEffort.High,
        ReasoningEffort.XHigh,
    )
    val tokens = HermesTheme.tokens
    val enabled = disabledReason == null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            val chosen = option.wireValue == selected?.wireValue
            Text(
                text = if (option == ReasoningEffort.XHigh) "XHigh" else option.wireValue.replaceFirstChar { it.uppercase() },
                style = HermesTheme.type.caption,
                color = if (chosen) tokens.accentForeground else tokens.textSecondary,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = HermesTheme.spacing.touchTarget)
                    .background(if (chosen) tokens.accent else tokens.widgetSurface, RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(option) }
                    .semantics {
                        contentDescription = buildString {
                            append("Reasoning ${option.wireValue}")
                            disabledReason?.let { append(". $it") }
                        }
                        if (!enabled) disabled()
                    }
                    .padding(horizontal = 6.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun FastModeRow(
    selected: FastMode?,
    supported: Boolean,
    enabled: Boolean,
    disabledReason: String?,
    onSelect: (FastMode) -> Unit,
) {
    val tokens = HermesTheme.tokens
    val fast = selected?.wireValue == FastMode.Fast.wireValue
    val description = buildString {
        append("Fast mode")
        disabledReason?.let { append(". $it") }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .clickable(enabled = enabled, role = Role.Switch) { onSelect(if (fast) FastMode.Normal else FastMode.Fast) }
            .semantics {
                contentDescription = description
                stateDescription = if (fast) "On" else "Off"
                if (!enabled) disabled()
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Fast mode", style = HermesTheme.type.body, color = if (supported) tokens.textPrimary else tokens.textQuaternary)
            if (!supported) Text("Not available for this model", style = HermesTheme.type.scaffoldMeta, color = tokens.scaffoldMeta)
        }
        Text(if (fast) "On" else "Off", style = HermesTheme.type.caption, color = if (fast) tokens.accent else tokens.textTertiary)
    }
}
