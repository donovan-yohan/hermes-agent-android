package com.hermesagent.mobile.ui.cron

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermesagent.mobile.data.cron.*
import com.hermesagent.mobile.ui.common.*
import com.hermesagent.mobile.ui.theme.HermesTheme

@Composable internal fun CronEditor(state: CronUiState, editor: CronEditorState, model: CronViewModel) {
    Dialog(onDismissRequest = { model.closeEditor(editor.ref) }, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
        SecureScreenLifetime { model.closeEditor(editor.ref) }
        val draft = editor.draft
        // Detail hydration may keep the same job id. Initialize once after loading,
        // never on each schedule edit (Custom must remain the user's choice).
        var preset by remember(editor.ref, editor.loading) { mutableStateOf(scheduleFor(draft.schedule).label) }
        val blueprint = state.blueprints.find { it.key == editor.blueprintKey }
        val jobKey = editor.job?.let { CronJobKey(it.profile, it.id) }
        val enabled = !editor.saving && !editor.loading && jobKey !in state.locked && jobKey !in state.busy
        Column(Modifier.fillMaxSize().background(HermesTheme.tokens.cardSurface).statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CronText(if (editor.job != null) "Edit cron job" else "New cron job", primary = true)
            CronText(if (blueprint != null) "Fill in the details and schedule it." else if (editor.job != null) "Update the schedule, prompt, or delivery target. Changes apply on next run." else "Schedule a prompt to run automatically. Use cron syntax or a natural phrase like \"every 15 minutes\".")
            CronText("Profile: ${editor.ref.profile}")
            Hairline()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (jobKey in state.locked) editor.job?.let { job ->
                    model.reference(state.jobs.find { it.id == job.id && it.profile == job.profile } ?: job)?.let { CronJobRecovery(state, model, it) }
                }
                if (editor.loading) CronText("Loading cron jobs...")
                if (editor.job == null) {
                    CronChoice("Start from", editor.blueprintKey, listOf(CronChoiceItem<String?>(null, "Custom")) + state.blueprints.map { CronChoiceItem<String?>(it.key, it.title) }, enabled) { key ->
                        model.chooseBlueprint(editor.ref, key)
                    }
                    if (state.blueprintsLoading) CronText("Loading blueprints...")
                    if (state.blueprintsFailure != null) { CronText("Failed to load blueprints"); OutlineButton("Retry blueprints", model::reloadBlueprints, enabled = !state.blueprintsLoading) }
                }
                if (blueprint != null) {
                    CronText(blueprint.description)
                    blueprint.fields.forEach { field ->
                        val value = editor.values[field.name].orEmpty()
                        fun change(next: String) = model.updateBlueprintValues(editor.ref, editor.values + (field.name to next))
                        when {
                            field.name == "deliver" -> DeliveryChoices(editor, value, ::change, enabled)
                            field.type == "enum" || field.type == "weekdays" -> CronChoice(field.label, value, field.options, enabled, ::change)
                            field.type == "text" || field.type == "time" -> CronField(field.label, value, ::change, "cron-blueprint-${field.name}", enabled = enabled)
                            else -> { CronText(field.label); ComingSoonAction(label = "${safe(field.label)} (${safe(field.type)})") }
                        }
                        if (field.help.isNotBlank() && field.type != "text" && field.name != "deliver") CronText(field.help)
                        if (field.optional) CronText("Optional")
                    }
                } else {
                    if (editor.job?.scriptOnly == true) CronText("Script-only job (no AI prompt). Job id: ${editor.job.id}")
                    CronField("Name", draft.name, { model.updateDraft(editor.ref, draft.copy(name = it)) }, "cron-name", enabled = enabled)
                    CronText("Optional")
                    CronField("Prompt", draft.prompt, { model.updateDraft(editor.ref, draft.copy(prompt = it)) }, "cron-prompt", multiline = true, enabled = enabled)
                    if (editor.job?.scriptOnly == true) CronText("Optional")
                    CronChoice("Frequency", preset, cronSchedules.map { it.label }, enabled) { value ->
                        preset = value
                        val option = cronSchedules.first { it.label == value }
                        model.updateDraft(editor.ref, draft.copy(schedule = option.expression ?: if (scheduleFor(draft.schedule).label != "Custom") "" else draft.schedule))
                    }
                    DeliveryChoices(editor, draft.deliver, { model.updateDraft(editor.ref, draft.copy(deliver = it)) }, enabled)
                    if (editor.job?.scriptOnly != true) {
                        val choices = buildList<Pair<String?,String?>> {
                            add(null to null)
                            if (!draft.model.isNullOrBlank()) add(draft.provider to draft.model)
                            editor.models.providers.forEach { provider -> provider.models.forEach { add(provider.id to it.id) } }
                        }.distinct()
                        val items = choices.map { pair -> CronChoiceItem(pair, if (pair.second == null) "Default (global model)" else listOfNotNull(pair.first, pair.second).joinToString(" / ")) }
                        CronChoice("Model", draft.provider to draft.model, items, enabled) { pair ->
                            model.updateDraft(editor.ref, draft.copy(provider = pair.first, model = pair.second))
                        }
                        CronText("Optional")
                    }
                    if (preset == "Custom") {
                        CronField("Custom schedule", draft.schedule, { model.updateDraft(editor.ref, draft.copy(schedule = it)) }, "cron-schedule", enabled = enabled)
                        CronText("Cron expression, or phrases like \"every hour\" or \"weekdays at 9am\".")
                    } else {
                        CronText(if (cronSchedules.find { it.expression == draft.schedule } != null) cronSchedules.first { it.label == preset }.hint else draft.schedule)
                        CronText(draft.schedule)
                    }
                }
                editor.error?.let { CronText(it) }
                state.failure?.let { CronText(failureCopy(it)) }
                if (state.creationLocked && editor.job == null) {
                    CronText("A previous create could not be confirmed. Review the jobs before creating another.")
                    state.jobs.forEach {
                        CronText(it.title, primary = true)
                        CronText(it.scheduleDisplay.ifBlank { it.schedule })
                    }
                    OutlineButton("Refresh", model::refresh)
                    OutlineButton("Start a different cron", { model.acknowledgeCreationOutcome(editor.ref) }, enabled = state.loaded && !state.stale && !state.loading && !editor.saving)
                    CronText("This clears the draft. Nothing is submitted again.")
                }
                Spacer(Modifier.height(8.dp))
            }
            Hairline()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlineButton("Cancel", { model.closeEditor(editor.ref) }, enabled = !editor.saving)
                val unsupported = blueprint?.fields?.any { it.name != "deliver" && it.type !in setOf("enum", "weekdays", "text", "time") } == true
                PrimaryButton(if (editor.saving) "Saving..." else if (blueprint != null) "Schedule it" else if (editor.job != null) "Save changes" else "Create cron", {
                    if (blueprint == null) model.save(editor.ref, draft) else model.instantiate(editor.ref, blueprint.key, editor.values)
                }, enabled = enabled && (!state.creationLocked || editor.job != null) && !unsupported, loading = editor.saving)
            }
        }
    }
}

@Composable private fun DeliveryChoices(editor: CronEditorState, value: String, change: (String) -> Unit, enabled: Boolean) {
    SectionLabel("Deliver to")
    if (editor.discoveryFailed) CronText("Failed to load delivery targets. Saved destinations are retained.")
    val selected = deliveryIds(value)
    val targets = (editor.deliveryTargets + selected.filter { id -> editor.deliveryTargets.none { it.id == id } }.map { CronDeliveryTarget(it, it) }).distinctBy { it.id }
    Column {
        targets.forEach { target ->
            val checked = target.id in selected
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox) { change(toggleDelivery(value, target.id, it)) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HermesIconGlyph(if (checked) HermesIcon.Check else HermesIcon.CircleSlash, color = HermesTheme.tokens.textSecondary)
                CronText((if (target.id == "local") "Local (save only)" else target.name) + if (target.id != "local" && !target.homeTargetSet) " — set a home channel first" else "")
            }
        }
    }
}
@Composable private fun CronChoice(label: String, selected: String, choices: List<String>, enabled: Boolean = true, change: (String) -> Unit) =
    CronChoice(label, selected, choices.map { CronChoiceItem(it, it) }, enabled, change)

@Composable internal fun <T> CronChoice(label: String, selected: T, choices: List<CronChoiceItem<T>>, enabled: Boolean = true, change: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(safe(label))
        Box {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = safe(label) }.clickable(enabled = enabled) { open = true }.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                CronText(choices.find { it.id == selected }?.label.orEmpty(), Modifier.weight(1f), primary = true)
                HermesIconGlyph(HermesIcon.ChevronDown, color = HermesTheme.tokens.textSecondary)
            }
            DropdownMenu(open, { open = false }, containerColor = HermesTheme.tokens.cardSurface) {
                choices.forEach { item -> DropdownMenuItem(text = { CronText(item.label) }, enabled = enabled, onClick = { open = false; change(item.id) }) }
            }
        }
    }
}
