package com.hermesagent.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.cron.*
import com.hermesagent.mobile.ui.cron.*
import com.hermesagent.mobile.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import com.hermesagent.mobile.data.composer.ModelCatalog
import com.hermesagent.mobile.data.composer.ModelProvider
import com.hermesagent.mobile.data.composer.ModelOption

/** Synthetic in-memory API, production controller/screen. Never contacts a Gateway or cron store.
 * Editor keeps FLAG_SECURE: an editor device capture is deliberately unavailable, not weakened.
 */
class CronParityActivity : ComponentActivity() {
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fixture = intent.getStringExtra("visual_parity_state") ?: "populated"
        require(fixture in setOf("populated", "empty", "error", "editor", "detail", "sidebar", "queued", "delete-confirm", "script", "blueprints"))
        val api = SyntheticCronApi(fixture)
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = owner,
            nowMillis = { Instant.parse("2026-10-19T09:00:00Z").toEpochMilli() },
            loadModels = { ModelCatalog(providers = listOf(ModelProvider("synthetic", "Synthetic fixture provider", models = listOf(ModelOption("synthetic-model"))))) })
        val mode = if (intent.getStringExtra("visual_parity_theme") == "light") HermesThemeMode.Light else HermesThemeMode.Dark
        setContent {
            val state by model.state.collectAsState()
            LaunchedEffect(Unit) {
                model.show("default")
                model.state.first { it.loaded || it.failure != null }
                when (fixture) {
                    "detail", "queued" -> model.select(api.job)
                    "script" -> model.select(api.scriptJob)
                    "editor" -> model.edit(api.job)
                    "delete-confirm" -> model.requestDelete(api.job)
                    "sidebar" -> model.togglePeek(api.job)
                }
                if (fixture == "queued") model.act(api.job, CronAction.Trigger)
            }
            CompositionLocalProvider(LocalCronDisplayClock provides CronDisplayClock(Locale.US, ZoneId.of("UTC"))) {
              HermesTheme(AppearanceSelection("mono", mode)) {
                if (fixture == "sidebar") Column(Modifier.fillMaxSize().background(HermesTheme.tokens.sidebarSurface).padding(16.dp).verticalScroll(rememberScrollState())) {
                    CronSidebarSection(state, model, {}, {}, visible = true)
                } else CronScreen(state, model, {})
              }
            }
        }
    }
    override fun onDestroy() { owner.cancel(); super.onDestroy() }
}

internal class SyntheticCronApi(private val fixture: String) : CronApi {
    val job = CronJob("report-daily", "default", "Status report", "Prepare a status report", "*/15 * * * *", "Every 15 minutes", "local", nextRunAt = "2026-10-20T09:00:00Z")
    val scriptJob = CronJob("script-check", "default", "Script check", "", "0 9 * * *", "Every day at 9:00 AM", "local", nextRunAt = "2026-10-20T09:00:00Z", script = "printf scheduled", noAgent = true)
    private var jobs = if (fixture in setOf("empty", "error")) emptyList() else listOf(job, scriptJob)
    private val runs = listOf(
        CronRun("local-cron-active", "default", source = "cron", title = "Status report — active", preview = "Synthetic scheduler-owned run", startedAt = 1792396800.0, lastActive = 1792396800.0, isActive = true, schedulerOwned = true),
        CronRun("local-cron-completed", "default", source = "cron", title = "Status report", preview = "Synthetic completed run", startedAt = 1792342800.0, lastActive = 1792342860.0, endedAt = 1792342860.0, isActive = false, schedulerOwned = false),
    )
    private val scriptRuns = listOf(CronRun("synthetic-script-output", "default", source = "cron_output", title = "scheduled", startedAt = 1792342800.0, lastActive = 1792342860.0, endedAt = 1792342860.0))
    private val blueprint = CronBlueprint("synthetic-recipe", "Synthetic Recipe", "Synthetic field-schema example; not a backend recipe.", listOf(
        CronBlueprintField("time", "time", "Time", "09:00", help = "UTC schedule time"),
        CronBlueprintField("cadence", "enum", "Cadence", "daily", listOf("daily", "weekly")),
        CronBlueprintField("weekdays", "weekdays", "Weekdays", "1-5", listOf("1-5", "0,6", "*")),
        CronBlueprintField("topic", "text", "Topic", "Status report", help = "Synthetic topic"),
        CronBlueprintField("deliver", "enum", "Deliver to", "origin", listOf("origin", "local")),
    ))
    override fun openScope(expectedEndpoint: Long, profile: String) = object : CronScope {
        override suspend fun jobs(): CronResult<List<CronJob>> = if (fixture == "error") CronResult.Failure(CronFailure.Unreachable) else CronResult.Success(jobs)
        override suspend fun detail(jobId: String) = CronResult.Success(jobs.find { it.id == jobId } ?: job)
        override suspend fun runs(jobId: String, limit: Int) = CronResult.Success((if (jobId == scriptJob.id) scriptRuns else runs).take(limit))
        override suspend fun deliveryTargets() = CronResult.Success(listOf(CronDeliveryTarget("local", "This desktop"), CronDeliveryTarget("telegram", "Telegram"), CronDeliveryTarget("slack", "Slack", homeTargetSet = false)))
        override suspend fun blueprints() = CronResult.Success(if (fixture in setOf("empty", "error")) emptyList() else listOf(blueprint))
        override suspend fun create(draft: CronDraft): CronResult<CronJob> = CronResult.Success(job.copy(id = "synthetic-created", name = draft.name, prompt = draft.prompt, schedule = draft.schedule, scheduleDisplay = draft.schedule, deliver = draft.deliver, model = draft.model, provider = draft.provider).also { jobs = jobs + it })
        override suspend fun update(job: CronJob, draft: CronDraft) = CronResult.Success(job.copy(name = draft.name, prompt = draft.prompt, schedule = draft.schedule, deliver = draft.deliver, model = draft.model, provider = draft.provider).also { updated -> jobs = jobs.map { if (it.id == job.id) updated else it } })
        override suspend fun act(job: CronJob, action: CronAction): CronResult<Unit> {
            if (fixture == "queued" && action == CronAction.Trigger) awaitCancellation()
            jobs = when (action) {
                CronAction.Delete -> jobs.filterNot { it.id == job.id }
                CronAction.Pause -> jobs.map { if (it.id == job.id) it.copy(enabled = false, state = "paused") else it }
                CronAction.Resume -> jobs.map { if (it.id == job.id) it.copy(enabled = true, state = "scheduled") else it }
                CronAction.Trigger -> jobs
            }
            return CronResult.Success(Unit)
        }
        override suspend fun instantiate(key: String, values: Map<String, String>) = CronResult.Success(job.copy(id = "synthetic-blueprint-created", name = "Synthetic Recipe result", prompt = "Synthetic topic: ${values["topic"]}", schedule = "0 9 * * *", scheduleDisplay = "Every day at 9:00 AM", deliver = values["deliver"].orEmpty()).also { jobs = jobs + it })
        override fun close() = Unit
    }
}
