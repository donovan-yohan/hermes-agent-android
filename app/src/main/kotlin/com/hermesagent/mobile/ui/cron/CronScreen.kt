package com.hermesagent.mobile.ui.cron

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.hermesagent.mobile.data.cron.*
import com.hermesagent.mobile.ui.common.*
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.LocalCronBackAction

/** Phone adaptation of core Desktop cron @ 5fc490e2b34123212d73ef316086f939567a725a. */
@Composable
fun CronScreen(state: CronUiState, model: CronViewModel, onOpenRun: (CronRun) -> Unit) {
    CronVisibility(model, true)
    val selected = state.selected
    val leave = LocalCronBackAction.current
    BackHandler(enabled = selected != null && state.editor == null) { selected?.let(model::clearSelection) }
    Column(Modifier.fillMaxSize().background(HermesTheme.tokens.chatSurface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) HermesIconButton(HermesIcon.ArrowLeft, "Back to scheduled jobs", onClick = { model.clearSelection(selected) })
            else if (leave != null) HermesIconButton(HermesIcon.ArrowLeft, "Back", onClick = leave)
            Column(Modifier.weight(1f)) {
                Text("Scheduled jobs", color = HermesTheme.tokens.textPrimary, style = HermesTheme.type.sectionLabel)
                CronText("${state.jobs.size} ${if (state.jobs.size == 1) "job" else "jobs"}")
            }
            HermesIconButton(HermesIcon.Refresh, "Refresh", onClick = model::refresh)
        }
        Hairline()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(2.dp))
            Outcome(state, model)
            if (selected != null) {
                CronDetail(state, model, selected, onOpenRun)
            } else {
                CronList(state, model)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    CronDialogs(state, model)
}

@Composable
internal fun CronVisibility(model: CronViewModel, visible: Boolean) {
    val token = remember { Any() }
    LifecycleResumeEffect(model, visible) {
        model.setVisibility(token, visible)
        onPauseOrDispose { model.setVisibility(token, false) }
    }
}

@Composable internal fun CronText(value: String, modifier: Modifier = Modifier, primary: Boolean = false) {
    Text(safe(value), modifier, color = if (primary) HermesTheme.tokens.textPrimary else HermesTheme.tokens.textSecondary, style = HermesTheme.type.caption)
}
@Composable private fun Outcome(state: CronUiState, model: CronViewModel) {
    state.notice?.let { CronText(it) }
    state.failure?.let {
        CronText(failureCopy(it))
        if (it.savedJobId != null) CronText("Saved job: ${it.savedJobId}")
        OutlineButton("Refresh", model::refresh)
    }
    if (state.stale && state.jobs.isNotEmpty()) CronText("Showing the last loaded jobs. Refresh to check for changes.")
}
@Composable private fun CronList(state: CronUiState, model: CronViewModel) {
    if (state.loading) CronText("Loading cron jobs...")
    if (state.loaded || state.jobs.isNotEmpty()) {
        CronField("Search cron jobs...", state.query, model::setQuery, tag = "cron-search")
        val needle = state.query.trim().lowercase()
        val visible = state.jobs.filter { job -> listOf(job.title, job.prompt, job.schedule, job.scheduleDisplay, job.deliver).any { it.lowercase().contains(needle) } }.sortedBy { it.title.lowercase() }
        if (visible.isEmpty()) {
            CronText(if (needle.isNotEmpty()) "No matches" else "No scheduled jobs yet", primary = true)
            CronText(if (needle.isNotEmpty()) "Try a broader search query." else "Schedule a prompt to run on a cron expression. Hermes will run it and deliver results to the destination you pick.")
        }
        visible.forEach { job ->
            val ref = remember(state.endpoint, state.revision, job) { model.reference(job) }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { ref?.let(model::select) }.padding(vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CronStateDot(job)
                        CronText(job.title, primary = true)
                    }
                    CronStatePill(job)
                    if (state.profile == "all") CronText(job.profile)
                }
                if (ref != null) CronListMenu(ref, model)
            }
            Hairline()
        }
    }
    PrimaryButton("New cron", { model.edit() }, enabled = !state.creationLocked)
    if (state.creationLocked) {
        CronText("A previous create could not be confirmed. Review the jobs before creating another.")
        OutlineButton("Review previous create", { model.refresh(); model.edit() })
    }
    SectionLabel("Blueprints")
    CronText("Ready-made automations")
    when {
        state.blueprintsLoading -> CronText("Loading blueprints...")
        state.blueprintsFailure != null -> { CronText("Failed to load blueprints"); OutlineButton("Retry blueprints", model::reloadBlueprints) }
        state.blueprints.isEmpty() -> { CronText("No blueprints available"); CronText("No automation blueprints are available on this backend.") }
        else -> state.blueprints.filter { "${it.title} ${it.description}".contains(state.query.trim(), ignoreCase = true) }.forEach { blueprint ->
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !state.creationLocked) {
                model.edit(); model.state.value.editor?.let { model.chooseBlueprint(it.ref, blueprint.key) }
            }.padding(vertical = 8.dp)) {
                CronText(blueprint.title, primary = true)
                blueprint.plugin?.let { CronText(it) }
                if (blueprint.description.isNotBlank()) CronText(blueprint.description)
            }
        }
    }
}
@Composable private fun CronListMenu(ref: CronJobRef, model: CronViewModel) {
    var open by remember(ref) { mutableStateOf(false) }
    Box {
        HermesIconButton(HermesIcon.Ellipsis, "Manage", onClick = { open = true })
        DropdownMenu(open, { open = false }, containerColor = HermesTheme.tokens.cardSurface) {
            DropdownMenuItem(text = { CronText("Edit cron") }, leadingIcon = { HermesIconGlyph(HermesIcon.Edit) }, onClick = { open = false; model.edit(ref) })
            DropdownMenuItem(text = { CronText("Delete") }, leadingIcon = { HermesIconGlyph(HermesIcon.Trash, color = HermesTheme.tokens.destructive) }, onClick = { open = false; model.requestDelete(ref) })
        }
    }
}
@Composable private fun CronDetail(state: CronUiState, model: CronViewModel, ref: CronJobRef, onOpenRun: (CronRun) -> Unit) {
    val job = state.jobs.find { it.id == ref.job.id && it.profile == ref.job.profile } ?: return
    val busy = ref.key in state.busy || ref.key in state.locked
    if (ref.key in state.locked) CronJobRecovery(state, model, ref)
    Row(verticalAlignment = Alignment.CenterVertically) {
        CronText(job.title, Modifier.weight(1f), primary = true)
        CronListMenu(ref, model)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (job.scriptOnly) Pill("script")
        CronStatePill(job)
        if (state.profile == "all") CronText(job.profile)
    }
    val pauseAction = pauseResumeAction(job)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CronActionButton(if (pauseAction == CronAction.Resume) "Resume" else "Pause", if (pauseAction == CronAction.Resume) CronGlyph.Play else CronGlyph.Pause,
            enabled = !busy && pauseAction != null, wip = pauseAction == null) { pauseAction?.let { model.act(ref, it) } }
        CronActionButton("Trigger now", CronGlyph.Zap, enabled = !busy && ref.key !in state.pending, primary = true, loading = ref.key in state.pending) { model.act(ref, CronAction.Trigger) }
    }
    Meta("Frequency", job.scheduleDisplay.ifBlank { job.schedule }.ifBlank { "—" })
    val clock = LocalCronDisplayClock.current
    Meta("Last", clock.displayTime(job.lastRunAt))
    Meta(if (overdue(job, state.nowMillis)) "Overdue since" else "Next", clock.displayTime(job.nextRunAt))
    Meta("Deliver to", if (job.deliver == "local") "Local (save only)" else job.deliver)
    job.model?.takeIf(String::isNotBlank)?.let { Meta("Model", listOfNotNull(job.provider, it).joinToString(" / ")) }
    job.lastError?.takeIf(String::isNotBlank)?.let {
        Column(Modifier.fillMaxWidth().background(HermesTheme.tokens.widgetSurface, RoundedCornerShape(8.dp)).padding(12.dp)) {
            CronText("Last run failed: ${safe(it).lineSequence().first().take(200)}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("Edit job", { model.edit(ref) }, icon = HermesIcon.Edit, enabled = !busy)
                CronActionButton("Run again", CronGlyph.Zap, enabled = !busy && ref.key !in state.pending) { model.act(ref, CronAction.Trigger) }
            }
        }
    }
    val description = job.prompt.ifBlank { job.script.orEmpty() }
    if (description.isNotBlank()) {
        SectionLabel(if (job.scriptOnly && job.prompt.isBlank()) "Script" else "Prompt")
        CronText(description, Modifier.fillMaxWidth().background(HermesTheme.tokens.widgetSurface, RoundedCornerShape(8.dp)).padding(12.dp))
    }
    CronRuns(state, model, ref, onOpenRun)
}
@Composable internal fun CronJobRecovery(state: CronUiState, model: CronViewModel, ref: CronJobRef) {
    CronText("The previous change could not be confirmed. Review the current job before making another change.")
    val review = state.review?.takeIf { it.ref.key == ref.key }
    OutlineButton("Review current job", { model.reviewJob(ref) }, enabled = ref.key !in state.busy && review?.loading != true)
    if (review?.loading == true) CronText("Loading current job...")
    review?.job?.let { job ->
        CronText("Current saved job", primary = true)
        CronText(job.title)
        CronText(job.schedule)
        CronText(job.prompt.ifBlank { job.script.orEmpty() })
        CronText(job.deliver)
        CronText("Status: ${job.state.ifBlank { if (job.enabled) "enabled" else "paused" }}")
        job.model?.let { CronText(listOfNotNull(job.provider, it).joinToString(" / ")) }
        CronText("Your draft is kept. Nothing is submitted again.")
        OutlineButton("I reviewed the current job", { model.acknowledgeJobOutcome(review.ref) }, enabled = !state.stale)
    }
}

@Composable internal fun CronStateDot(job: CronJob) {
    val tokens = HermesTheme.tokens
    val color = when (cronState(job)) {
        "enabled", "scheduled", "running" -> tokens.accent
        "paused" -> tokens.statusWarning
        "error" -> tokens.destructive
        else -> tokens.textQuaternary
    }
    StatusDot(color, filled = true, contentDescription = null, size = if (cronState(job) == "running") 6.dp else 4.dp)
}

@Composable private fun CronStatePill(job: CronJob) {
    val state = cronState(job)
    val tokens = HermesTheme.tokens
    val ink = when (state) {
        "enabled", "scheduled", "running" -> tokens.accent
        "paused" -> tokens.statusWarning
        "error" -> tokens.destructive
        else -> tokens.textTertiary
    }
    // PanelPill has good/warn/bad tones; the shared settings Pill only has muted/primary.
    Text(safe(if (state == "error") "last run failed" else state),
        color = ink, style = HermesTheme.type.scaffold.copy(fontWeight = FontWeight.Medium),
        modifier = Modifier.background(ink.copy(alpha = 0.10f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
}

/** Codicons 0.0.45 names/codepoints; scoped here until other surfaces use them. */
internal enum class CronGlyph(val codepoint: String) { Play("\uEB2C"), Pause("\uEAD1"), Zap("\uEA86") }
@Composable internal fun CronActionGlyph(icon: CronGlyph, enabled: Boolean = true) {
    Text(icon.codepoint, style = TextStyle(fontFamily = CodiconFont, fontSize = 14.sp, lineHeight = 14.sp),
        color = if (enabled) HermesTheme.tokens.textSecondary else HermesTheme.tokens.textQuaternary,
        modifier = Modifier.clearAndSetSemantics {})
}
@Composable private fun CronActionButton(label: String, icon: CronGlyph, enabled: Boolean = true, primary: Boolean = false, loading: Boolean = false, wip: Boolean = false, action: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (primary) {
            Row(Modifier.heightIn(min = 48.dp).background(HermesTheme.tokens.accent.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
                .clickable(enabled = enabled && !loading, onClick = action).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (loading) CircularProgressIndicator(Modifier.size(14.dp), color = HermesTheme.tokens.accent, strokeWidth = 1.dp)
                else CronActionGlyph(icon, enabled)
                CronText(label, primary = true)
            }
        } else {
            Row(Modifier.heightIn(min = 48.dp).semantics { if (wip) contentDescription = "$label. $WIP_SPOKEN" }.clickable(enabled = enabled, onClick = action).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CronActionGlyph(icon, enabled)
                CronText(label)
            }
        }
        if (wip) WipPill()
    }
}

@Composable private fun Meta(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CronText(label, Modifier.width(100.dp)); CronText(value, Modifier.weight(1f), primary = true)
    }
}
@Composable internal fun CronRuns(state: CronUiState, model: CronViewModel, ref: CronJobRef, onOpenRun: (CronRun) -> Unit, compact: Boolean = false) {
    val history = state.histories[ref.key]
    SectionLabel("Run history" + if (history != null && !history.loading) " · ${history.rows.size}" else "", Modifier.semantics { contentDescription = "Run history" })
    if (ref.key in state.pending) CronText("Queued run", Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cron-queued").padding(12.dp))
    if (history == null || history.loading) CronText("Loading cron jobs...")
    history?.failure?.let { CronText("Failed to load run history. Refresh to try again.") }
    if (history != null && !history.loading && history.failure == null && history.rows.isEmpty() && ref.key !in state.pending) CronText("No runs yet")
    val clock = LocalCronDisplayClock.current
    history?.rows?.sortedByDescending { it.lastActive ?: it.startedAt ?: 0.0 }?.let { rows -> if (compact) rows.take(5) else rows }?.forEach { run ->
        val label = if (compact) clock.runTime(run.lastActive ?: run.startedAt, compact = true) else run.title.ifBlank { run.preview }.ifBlank { run.id }
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (run.isConversation) Modifier.clickable { model.openRun(ref, run, onOpenRun) } else Modifier)
            .padding(horizontal = 8.dp, vertical = 8.dp)) {
            CronText(label, primary = true)
            if (!compact) CronText(clock.runTime(run.lastActive ?: run.startedAt))
        }
    }
}
@Composable internal fun CronDialogs(state: CronUiState, model: CronViewModel) {
    state.editor?.let { CronEditor(state, it, model) }
    state.deleteTarget?.let { ref ->
        val busy = ref.key in state.busy
        AlertDialog(
            onDismissRequest = { model.cancelDelete(ref) },
            containerColor = HermesTheme.tokens.cardSurface,
            title = { CronText("Delete cron job?", primary = true) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CronText("This will remove ${safe(ref.job.title)} permanently. It will stop firing immediately.")
                state.failure?.let { CronText(failureCopy(it)) }
            } },
            confirmButton = { PrimaryButton(if (busy) "Deleting..." else "Delete", { model.confirmDelete(ref) }, enabled = !busy && ref.key !in state.locked, variant = FilledActionVariant.Destructive) },
            dismissButton = { OutlineButton("Cancel", { model.cancelDelete(ref) }, enabled = !busy) },
        )
    }
}
