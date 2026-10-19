package com.hermesagent.mobile.data.cron

import android.icu.text.BreakIterator
import java.util.Locale

/** Raw editable values. Redact only when rendering; never round-trip display text. */
data class CronJob(
    val id: String,
    val profile: String,
    val name: String = "",
    val prompt: String = "",
    val schedule: String = "",
    val scheduleDisplay: String = "",
    val deliver: String = "local",
    val model: String? = null,
    val provider: String? = null,
    val enabled: Boolean = true,
    val state: String = "scheduled",
    val lastRunAt: String? = null,
    val nextRunAt: String? = null,
    val lastError: String? = null,
    val script: String? = null,
    val noAgent: Boolean = false,
) {
    val title: String get() = name.trim().ifEmpty {
        prompt.trim().truncateTitle().ifEmpty {
            script.orEmpty().trim().truncateTitle().ifEmpty { id.ifEmpty { "Cron job" } }
        }
    }
    val scriptOnly: Boolean get() = noAgent && !script.isNullOrBlank()
}

/** Desktop job-state.ts uses 60 grapheme clusters, keeping emoji and marks intact. */
private fun String.truncateTitle(): String {
    if (isEmpty()) return this
    val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(this@truncateTitle) }
    var end = boundaries.first()
    repeat(60) {
        val next = boundaries.next()
        if (next == BreakIterator.DONE) return this
        end = next
    }
    return if (boundaries.next() == BreakIterator.DONE) this else substring(0, end) + "…"
}

data class CronRun(
    val id: String,
    val profile: String,
    val source: String = "",
    val title: String = "",
    val preview: String = "",
    val startedAt: Double? = null,
    val lastActive: Double? = null,
    val endedAt: Double? = null,
    val schedulerOwned: Boolean? = null,
    val isActive: Boolean? = null,
) {
    // History navigation follows Desktop's row discriminator, not restored-tab
    // admission heuristics: only the producer's literal cron source is a session.
    val isConversation: Boolean get() = source == "cron"
}

data class CronDraft(
    val name: String = "",
    val prompt: String = "",
    val schedule: String = "0 9 * * *",
    val deliver: String = "local",
    val model: String? = null,
    val provider: String? = null,
) {
    companion object {
        fun from(job: CronJob): CronDraft = CronDraft(
            job.name, job.prompt, job.schedule, job.deliver, job.model, job.provider,
        )
    }
}

enum class CronAction { Pause, Resume, Trigger, Delete }

data class CronDeliveryTarget(val id: String, val name: String, val homeTargetSet: Boolean = true)

data class CronBlueprintField(
    val name: String,
    val type: String,
    val label: String,
    val defaultValue: String? = null,
    val options: List<String> = emptyList(),
    val optional: Boolean = false,
    val strict: Boolean = true,
    val help: String = "",
)

data class CronBlueprint(
    val key: String,
    val title: String,
    val description: String = "",
    val fields: List<CronBlueprintField> = emptyList(),
    val plugin: String? = null,
)

sealed interface CronResult<out T> {
    data class Success<T>(val value: T) : CronResult<T>
    data class Failure(
        val kind: CronFailure,
        val mutationMayHaveApplied: Boolean = false,
        val savedJobId: String? = null,
    ) : CronResult<Nothing>
}

enum class CronFailure {
    InvalidScope, StaleScope, Unavailable, Refused, Unreachable, InvalidResponse,
    Unconfirmed, SavedRegistrationFailed,
}

interface CronApi {
    fun openScope(expectedEndpoint: Long, profile: String): CronScope
}

interface CronScope : AutoCloseable {
    suspend fun jobs(): CronResult<List<CronJob>>
    suspend fun detail(jobId: String): CronResult<CronJob>
    suspend fun runs(jobId: String, limit: Int = 20): CronResult<List<CronRun>>
    suspend fun deliveryTargets(): CronResult<List<CronDeliveryTarget>>
    suspend fun blueprints(): CronResult<List<CronBlueprint>>
    suspend fun create(draft: CronDraft): CronResult<CronJob>
    suspend fun update(job: CronJob, draft: CronDraft): CronResult<CronJob>
    suspend fun act(job: CronJob, action: CronAction): CronResult<Unit>
    suspend fun instantiate(key: String, values: Map<String, String>): CronResult<CronJob>
    override fun close()
}
