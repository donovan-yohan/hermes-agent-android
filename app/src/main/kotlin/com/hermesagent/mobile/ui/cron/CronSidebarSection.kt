package com.hermesagent.mobile.ui.cron

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.cron.*
import com.hermesagent.mobile.ui.common.*
import com.hermesagent.mobile.ui.theme.HermesTheme

/** Non-lazy content: parent sidebar supplies its bounded scrolling container.
 * Parent must pass visible=false for a closed drawer which remains composed. */
@Composable
fun CronSidebarSection(
    state: CronUiState,
    model: CronViewModel,
    onManage: (CronJob) -> Unit,
    onOpenRun: (CronRun) -> Unit,
    visible: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var count by rememberSaveable(state.endpoint, state.profile) { mutableIntStateOf(3) }
    CronVisibility(model, visible && expanded)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Cron jobs" }.clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            PanelLabel("Scheduled jobs", Modifier.weight(1f))
            CronText("${state.jobs.size} ${if (state.jobs.size == 1) "job" else "jobs"}")
            HermesIconGlyph(if (expanded) HermesIcon.ChevronDown else HermesIcon.ChevronRight, color = HermesTheme.tokens.textTertiary)
        }
        if (expanded) {
            state.notice?.let { CronText(it) }
            if (state.loading) CronText("Loading cron jobs...")
            if (state.failure != null) CronText(failureCopy(state.failure))
            if (state.loaded && state.jobs.isEmpty()) CronText("No scheduled jobs yet")
            val sorted = sidebarJobs(state.jobs)
            val clock = LocalCronDisplayClock.current
            sorted.take(count).forEach { job ->
                val ref = remember(state.endpoint, state.revision, job) { model.reference(job) }
                if (ref != null) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        CronStateDot(job)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f).clickable { model.togglePeek(ref) }.padding(vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                CronText(job.title, primary = true)
                                HermesIconGlyph(if (state.peek?.key == ref.key) HermesIcon.ChevronDown else HermesIcon.ChevronRight)
                            }
                            val status = cronState(job)
                            val next = nextRunMillis(job.nextRunAt)
                            CronText(if (status in setOf("completed", "disabled", "error", "paused")) {
                                if (status == "error") "last run failed" else status
                            } else if (next == null) "—" else
                                (if (overdue(job, state.nowMillis)) "Overdue since " else "") + clock.relativeTime(next, state.nowMillis))
                        }
                        var menu by remember(ref) { mutableStateOf(false) }
                        Box {
                            HermesIconButton(HermesIcon.Ellipsis, "Cron job actions", onClick = { menu = true })
                            DropdownMenu(menu, { menu = false }, containerColor = HermesTheme.tokens.cardSurface) {
                                DropdownMenuItem(text = { CronText("Trigger now") }, leadingIcon = { CronActionGlyph(CronGlyph.Zap) }, enabled = ref.key !in state.busy && ref.key !in state.pending && ref.key !in state.locked, onClick = { menu = false; model.act(ref, CronAction.Trigger) })
                                val pauseAction = pauseResumeAction(job)
                                DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    CronText(if (pauseAction == CronAction.Resume) "Resume cron" else "Pause cron")
                                    if (pauseAction == null) WipPill()
                                } }, leadingIcon = { CronActionGlyph(if (pauseAction == CronAction.Resume) CronGlyph.Play else CronGlyph.Pause) }, enabled = pauseAction != null && ref.key !in state.busy && ref.key !in state.locked, onClick = { menu = false; pauseAction?.let { model.act(ref, it) } })
                                DropdownMenuItem(text = { CronText("Manage") }, leadingIcon = { HermesIconGlyph(HermesIcon.Watch) }, onClick = { menu = false; if (model.state.value.endpoint == ref.endpoint && model.state.value.revision == ref.revision) { model.select(ref); onManage(job) } })
                                Hairline()
                                DropdownMenuItem(text = { CronText("Delete") }, leadingIcon = { HermesIconGlyph(HermesIcon.Trash, color = HermesTheme.tokens.destructive) }, onClick = { menu = false; model.requestDelete(ref) })
                            }
                        }
                    }
                    state.peek?.takeIf { it.key == ref.key }?.let { CronRuns(state, model, it, onOpenRun, compact = true) }
                }
            }
            if (sorted.size > count) OutlineButton("Load ${minOf(10, sorted.size - count)} more", { count += 10 })
        }
    }
    // The core delete confirmation is intentionally not Bots' direct-delete behavior.
    if (visible) CronDialogs(state.copy(editor = null), model)
}
