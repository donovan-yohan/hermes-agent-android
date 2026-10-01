package com.hermesagent.mobile.plugins.bots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.ExperimentalMaterial3Api
import com.hermesagent.mobile.ui.common.ChoiceButton
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoutineCreationSheet(
    state: RoutineCreationUiState,
    actions: BotsRoutinesActions,
    ownerLabel: String,
    scopeConfirmed: Boolean = false,
) {
    val tokens = HermesTheme.tokens
    val editing = state.phase == RoutineCreationPhase.Editing || state.phase == RoutineCreationPhase.Rejected
    val unresolved = state.phase == RoutineCreationPhase.Pending
    ModalBottomSheet(
        onDismissRequest = { if (!unresolved) actions.onCloseCreation() },
        containerColor = tokens.cardSurface,
        contentColor = tokens.textPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("New cron job", style = HermesTheme.type.screenTitle)
            CreationTextField("Name", state.draft.title, editing) {
                actions.onUpdateCreation(state.draft.copy(title = it))
            }
            CreationTextField("Prompt", state.draft.instruction, editing) {
                actions.onUpdateCreation(state.draft.copy(instruction = it))
            }
            Text("When to run", style = HermesTheme.type.sectionLabel, color = tokens.textTertiary)
            RoutineFrequency.entries.forEach { frequency ->
                ChoiceButton(
                    label = routineFrequencyLabel(frequency),
                    selected = state.draft.schedule.frequency == frequency,
                    enabled = editing,
                    onClick = {
                        actions.onUpdateCreation(state.draft.copy(schedule = state.draft.schedule.copy(frequency = frequency)))
                    },
                )
            }
            val schedule = state.draft.schedule
            if (schedule.frequency.showsOnceAmount) {
                CreationTextField("In", schedule.onceN, editing) {
                    actions.onUpdateCreation(state.draft.copy(schedule = schedule.copy(onceN = sanitizeRoutineAmount(it))))
                }
                RoutineChoiceField(
                    label = "Delay unit", value = routineUnitLabel(schedule.onceUnit), enabled = editing,
                    options = listOf("m", "h", "d").map { RoutineTimeOption(it, routineUnitLabel(it)) },
                    onSelect = { option -> actions.onUpdateCreation(state.draft.copy(schedule = schedule.copy(onceUnit = option.id))) },
                )
            }
            if (schedule.frequency.showsTime) {
                RoutineChoiceField(
                    label = "Time",
                    value = routineTimeOptions().firstOrNull { it.id == schedule.time }?.label ?: "9:00 AM",
                    enabled = editing,
                    options = routineTimeOptions(),
                    onSelect = { option -> actions.onUpdateCreation(state.draft.copy(schedule = schedule.copy(time = option.id))) },
                )
            }
            if (schedule.frequency.showsWeekday) {
                RoutineChoiceField(
                    label = "Weekday",
                    value = routineWeekdayLabel(schedule.weekday),
                    enabled = editing,
                    options = routineWeekdayIds().map { RoutineTimeOption(it, routineWeekdayLabel(it)) },
                    onSelect = { option -> actions.onUpdateCreation(state.draft.copy(schedule = schedule.copy(weekday = option.id))) },
                )
            }
            if (schedule.frequency.showsMonthday) {
                CreationTextField("Day of month", schedule.monthday, editing) {
                    actions.onUpdateCreation(state.draft.copy(schedule = schedule.copy(monthday = sanitizeRoutineMonthday(it))))
                }
            }
            if (schedule.frequency.showsIntervalAmount) {
                CreationTextField("Every", schedule.intervalN, editing) {
                    actions.onUpdateCreation(state.draft.copy(schedule = schedule.copy(intervalN = sanitizeRoutineAmount(it))))
                }
                RoutineChoiceField(
                    label = "Interval unit",
                    value = routineUnitLabel(schedule.intervalUnit),
                    enabled = editing,
                    options = listOf("m", "h", "d").map { RoutineTimeOption(it, routineUnitLabel(it)) },
                    onSelect = { option -> actions.onUpdateCreation(state.draft.copy(schedule = schedule.copy(intervalUnit = option.id))) },
                )
            }
            if (schedule.frequency.showsRaw) {
                CreationTextField("Schedule", schedule.raw, editing) {
                    actions.onUpdateCreation(state.draft.copy(schedule = schedule.copy(raw = it)))
                }
            }
            if (schedule.frequency.showsRepeat) {
                CreationTextField("Repeat count (optional)", schedule.repeatN, editing) {
                    actions.onUpdateCreation(state.draft.copy(schedule = schedule.copy(repeatN = sanitizeRoutineAmount(it))))
                }
            }
            if (schedule.frequency != RoutineFrequency.Once) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.draft.continuity,
                        onCheckedChange = { checked ->
                            actions.onUpdateCreation(state.draft.copy(continuity = checked))
                        },
                        enabled = editing,
                    )
                    Text("Continuity: each run sees the previous run’s output (dedupe, continue where it left off)", style = HermesTheme.type.caption, color = tokens.textSecondary)
                }
                Text("Send results to", style = HermesTheme.type.sectionLabel, color = tokens.textTertiary)
                RoutineDelivery.entries.forEach { delivery ->
                    ChoiceButton(
                        label = if (delivery == RoutineDelivery.History) "Run history only" else "$ownerLabel’s chat (bot responds)",
                        selected = state.draft.delivery == delivery,
                        enabled = editing,
                        onClick = { actions.onUpdateCreation(state.draft.copy(delivery = delivery)) },
                    )
                }
            }
            when (state.phase) {
                RoutineCreationPhase.Created -> Text("Created. The job is now in the scheduled list.", color = tokens.textSecondary)
                RoutineCreationPhase.SavedRegistrationFailed -> Text("Saved, but scheduling is not confirmed. Do not create it again.", color = tokens.textSecondary)
                RoutineCreationPhase.Rejected -> Text("The Gateway refused this routine. Review the fields and try again.", color = tokens.textSecondary)
                RoutineCreationPhase.Unconfirmed -> Text("Creation is unconfirmed. Do not create it again; check the scheduled list.", color = tokens.textSecondary)
                else -> Unit
            }
            PrimaryButton(
                label = if (state.phase == RoutineCreationPhase.Pending) "Creating…" else "Create",
                onClick = actions.onSubmitCreation,
                enabled = state.canSubmit && scopeConfirmed,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(if (editing) "Cancel" else "Close", actions.onCloseCreation, enabled = !unresolved)
        }
    }
}

@Composable
private fun RoutineChoiceField(
    label: String,
    value: String,
    enabled: Boolean,
    options: List<RoutineTimeOption>,
    onSelect: (RoutineTimeOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        ChoiceButton(
            label = "$label: $value",
            selected = false,
            enabled = enabled,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** Plugin i18n.ts:899-906 @ e27448b231498e79ade668d68c0b6c6206951206. */
internal fun routineFrequencyLabel(frequency: RoutineFrequency): String = when (frequency) {
    RoutineFrequency.Once -> "Once, in…"
    RoutineFrequency.Hourly -> "Every hour"
    RoutineFrequency.Daily -> "Every day"
    RoutineFrequency.Weekdays -> "Weekdays"
    RoutineFrequency.Weekly -> "Every week"
    RoutineFrequency.Monthly -> "Every month"
    RoutineFrequency.Interval -> "Interval"
    RoutineFrequency.Advanced -> "Advanced…"
}

private fun routineWeekdayLabel(id: String): String = when (id) {
    "1" -> "Monday"
    "2" -> "Tuesday"
    "3" -> "Wednesday"
    "4" -> "Thursday"
    "5" -> "Friday"
    "6" -> "Saturday"
    else -> "Sunday"
}

private fun routineUnitLabel(id: String): String = when (id) {
    "m" -> "minute(s)"
    "d" -> "day(s)"
    else -> "hour(s)"
}

@Composable
private fun CreationTextField(label: String, value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    val tokens = HermesTheme.tokens
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        textStyle = HermesTheme.type.body.copy(color = tokens.textPrimary),
        cursorBrush = SolidColor(tokens.composerRing),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        decorationBox = { inner ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(label, style = HermesTheme.type.caption, color = tokens.textTertiary)
                inner()
            }
        },
    )
}
