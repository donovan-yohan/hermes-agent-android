package com.hermesagent.mobile.plugins.bots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.common.ChoiceButton
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.common.ComingSoonAction
import com.hermesagent.mobile.ui.theme.HermesTheme

class BotManagementActions(
    val onClose: () -> Unit = {},
    val onUpdate: (BotIdentityDraft) -> Unit = {},
    val onSectionName: (String) -> Unit = {},
    val onSection: (String?) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onDeleteSection: () -> Unit = {},
    val onMoveSection: (Int) -> Unit = {},
)

/** Phone adaptation of the profile/section dialogs; no secret or raw Gateway error fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotManagementSheet(
    state: BotManagementState, actions: BotManagementActions,
    modelState: BotModelState = BotModelState(), modelActions: BotModelActions = BotModelActions(),
    toolsetsState: BotToolsetsState = BotToolsetsState(), toolsetsActions: BotToolsetsActions = BotToolsetsActions(),
    avatarState: BotAvatarState = BotAvatarState(), avatarActions: BotAvatarActions = BotAvatarActions(),
) {
    val dialog = state.dialog ?: return
    val tokens = HermesTheme.tokens
    val editing = !state.busy && !state.consumed
    val create = dialog == BotManagementDialog.New || dialog == BotManagementDialog.Duplicate
    val title = when (dialog) {
        BotManagementDialog.New -> "New bot"
        BotManagementDialog.Edit -> "Edit profile"
        BotManagementDialog.Duplicate -> "Duplicate"
        BotManagementDialog.Delete -> "Delete bot and profile?"
        BotManagementDialog.Section -> if (state.sectionId == null) "New section" else "Rename section"
        BotManagementDialog.Move -> "Move to section"
        BotManagementDialog.Action -> "Saving changes"
    }
    ModalBottomSheet(
        onDismissRequest = { if (!state.busy) actions.onClose() },
        containerColor = tokens.cardSurface, contentColor = tokens.textPrimary,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = HermesTheme.type.screenTitle)
            when (dialog) {
                BotManagementDialog.New, BotManagementDialog.Edit, BotManagementDialog.Duplicate -> {
                    BotIdentityField("Bot name", state.draft.name, editing && create) {
                        actions.onUpdate(state.draft.copy(name = it))
                    }
                    if (create) Text("Use lowercase letters, numbers, hyphens or underscores (up to 64 characters).",
                        style = HermesTheme.type.caption, color = tokens.textSecondary)
                    BotIdentityField("Display name", state.draft.title, editing) {
                        actions.onUpdate(state.draft.copy(title = it))
                    }
                    BotIdentityField("Description", state.draft.description, editing) {
                        actions.onUpdate(state.draft.copy(description = it))
                    }
                    if (dialog != BotManagementDialog.Duplicate) {
                        BotIdentityField("SOUL.md", state.draft.soul, editing, singleLine = false) {
                            actions.onUpdate(state.draft.copy(soul = it))
                        }
                    }
                    if (create) Text("Uses this Gateway’s provider credentials. Messaging accounts and chat history are not copied.",
                        style = HermesTheme.type.caption, color = tokens.textSecondary)
                    // These are explicit remaining gaps, not enabled no-op controls.
                    if (dialog == BotManagementDialog.Edit && avatarState.ticket?.target == state.target)
                        BotAvatarEditor(avatarState, avatarActions)
                    else ComingSoonAction("Avatar")
                    if (dialog == BotManagementDialog.Edit && modelState.ticket?.target == state.target)
                        BotModelEditor(modelState, modelActions)
                    else ComingSoonAction("Model")
                    if (dialog == BotManagementDialog.Edit && toolsetsState.ticket?.target == state.target)
                        BotToolsetsEditor(toolsetsState, toolsetsActions)
                    else ComingSoonAction("Toolsets")
                    ComingSoonAction("Skills")
                    ComingSoonAction("MCP servers")
                }
                BotManagementDialog.Delete -> {
                    Text("Permanently delete ${state.target?.name.orEmpty()} and its profile data? This cannot be undone.",
                        style = HermesTheme.type.body)
                }
                BotManagementDialog.Section -> {
                    BotIdentityField("Section name", state.sectionName, editing, onChange = actions.onSectionName)
                    if (state.sectionId != null) {
                        val index = state.choices.indexOfFirst { it.id == state.sectionId }
                        TextButton("Move up", { actions.onMoveSection(-1) }, enabled = editing && index > 0)
                        TextButton("Move down", { actions.onMoveSection(1) },
                            enabled = editing && index >= 0 && index < state.choices.lastIndex)
                        TextButton("Delete section", actions.onDeleteSection, enabled = editing)
                    }
                }
                BotManagementDialog.Move -> {
                    ChoiceButton("Unassigned", state.sectionId == null, onClick = { actions.onSection(null) }, enabled = editing)
                    state.choices.forEach { section ->
                        ChoiceButton(section.name, state.sectionId == section.id,
                            onClick = { actions.onSection(section.id) }, enabled = editing)
                    }
                }
                BotManagementDialog.Action -> Unit
            }
            state.message?.let { Text(it, style = HermesTheme.type.body, color = tokens.textSecondary) }
            if (state.busy) Text("Working…", style = HermesTheme.type.caption)
            if (dialog != BotManagementDialog.Action) PrimaryButton(
                label = when (dialog) {
                    BotManagementDialog.New, BotManagementDialog.Duplicate -> "Create"
                    BotManagementDialog.Delete -> "Delete"
                    BotManagementDialog.Section -> if (state.sectionId == null) "Create" else "Save"
                    else -> "Save"
                },
                onClick = actions.onSubmit,
                enabled = editing && (!create || validBotId(state.draft.name) && state.draft.name != "default") &&
                    (dialog != BotManagementDialog.Section || state.sectionName.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(if (editing) "Cancel" else "Close", actions.onClose, enabled = !state.busy)
        }
    }
}

@Composable
internal fun BotIdentityField(
    label: String, value: String, enabled: Boolean, singleLine: Boolean = true, onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = HermesTheme.type.sectionLabel, color = HermesTheme.tokens.textSecondary)
        BasicTextField(
            value = value, onValueChange = onChange, enabled = enabled, singleLine = singleLine,
            textStyle = HermesTheme.type.body.copy(color = HermesTheme.tokens.textPrimary),
            cursorBrush = SolidColor(HermesTheme.tokens.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .background(HermesTheme.tokens.cardSurface)
                .padding(8.dp).semantics { contentDescription = label },
        )
    }
}
