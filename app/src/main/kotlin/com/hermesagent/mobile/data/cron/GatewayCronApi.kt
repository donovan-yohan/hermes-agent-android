package com.hermesagent.mobile.data.cron

import com.hermesagent.mobile.data.gateway.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Core REST capability. No fallback to unguarded HTTP or Bots RPC.
 *
 * At the inspected upstream pin, item routes treat profile as a hint and can
 * fall back across stores. Inventory preflight and response-owner validation
 * prevent known-wrong admission/publication, NOT a server-side TOCTOU race.
 * Atomic owner isolation requires a strict backend item-route contract.
 */
internal class GatewayCronApi(
    private val http: () -> GatewayHttp?,
    private val endpoint: StateFlow<Long>,
    private val fence: EndpointDispatchFence,
) : CronApi {
    override fun openScope(expectedEndpoint: Long, profile: String): CronScope {
        if (!validProfile(profile) && profile != "all") return FailedCronScope(CronFailure.InvalidScope)
        val transport = http() as? EndpointDispatchingGatewayHttp ?: return FailedCronScope(CronFailure.Unavailable)
        val owns = { endpoint.value == expectedEndpoint && http() === transport }
        val lease = fence.leaseAt(expectedEndpoint, owns) ?: return FailedCronScope(CronFailure.StaleScope)
        return BoundCronScope(transport, profile, fence, lease, owns)
    }
}

private class FailedCronScope(private val failure: CronFailure) : CronScope {
    private fun fail() = CronResult.Failure(failure)
    override suspend fun jobs() = fail()
    override suspend fun detail(jobId: String) = fail()
    override suspend fun runs(jobId: String, limit: Int) = fail()
    override suspend fun deliveryTargets() = fail()
    override suspend fun blueprints() = fail()
    override suspend fun create(draft: CronDraft) = fail()
    override suspend fun update(job: CronJob, draft: CronDraft) = fail()
    override suspend fun act(job: CronJob, action: CronAction) = fail()
    override suspend fun instantiate(key: String, values: Map<String, String>) = fail()
    override fun close() = Unit
}

private class BoundCronScope(
    private val transport: EndpointDispatchingGatewayHttp,
    private val profile: String,
    private val fence: EndpointDispatchFence,
    private val lease: Long,
    private val owns: () -> Boolean,
) : CronScope {
    private val lock = Any()
    private var closed = false
    override fun close() { synchronized(lock) { closed = true } }
    private fun current() = fence.leaseAt(lease, owns) != null && synchronized(lock) { !closed && owns() }

    override suspend fun jobs(): CronResult<List<CronJob>> = owned { readJobs() }

    private suspend fun readJobs(): CronResult<List<CronJob>> = when (val reply = exchange("/api/cron/jobs")) {
        is CronResult.Failure -> reply
        is CronResult.Success -> try {
            val rows = reply.value as? JsonArray ?: error("inventory")
            val jobs = rows.map { parseJob(it) }
            require(jobs.map { it.profile to it.id }.distinct().size == jobs.size)
            require(profile == "all" || jobs.all { it.profile == profile })
            CronResult.Success(jobs)
        } catch (_: Exception) { CronResult.Failure(CronFailure.InvalidResponse) }
    }

    override suspend fun detail(jobId: String): CronResult<CronJob> = owned {
        when (val before = preflight(jobId)) {
            is CronResult.Failure -> before
            is CronResult.Success -> readJob(jobId)
        }
    }

    /** The server treats profile as a hint. Never authorize its cross-store fallback from a 404. */
    private suspend fun preflight(jobId: String): CronResult<CronJob> {
        if (profile == "all" || jobId.isEmpty() || jobId == "." || jobId == "..") return CronResult.Failure(CronFailure.InvalidScope)
        return when (val inventory = readJobs()) {
            is CronResult.Failure -> inventory
            is CronResult.Success -> inventory.value.singleOrNull { it.id == jobId && it.profile == profile }
                ?.let { CronResult.Success(it) } ?: CronResult.Failure(CronFailure.InvalidScope)
        }
    }

    private suspend fun readJob(jobId: String): CronResult<CronJob> = when (val reply = exchange(jobPath(jobId))) {
        is CronResult.Failure -> reply
        is CronResult.Success -> exactJob(reply.value, jobId)
    }

    private fun exactJob(value: JsonElement, jobId: String? = null): CronResult<CronJob> = try {
        val job = parseJob(value)
        require(job.profile == profile && (jobId == null || job.id == jobId))
        CronResult.Success(job)
    } catch (_: Exception) { CronResult.Failure(CronFailure.InvalidResponse) }

    private fun jobPath(jobId: String) = "/api/cron/jobs/${encodeSegment(jobId)}"
    override suspend fun runs(jobId: String, limit: Int): CronResult<List<CronRun>> = owned {
        if (limit !in 1..100) return@owned CronResult.Failure(CronFailure.InvalidScope)
        when (val before = preflight(jobId)) {
            is CronResult.Failure -> before
            is CronResult.Success -> decode(exchange("${jobPath(jobId)}/runs", mapOf("limit" to limit.toString()))) { value ->
                val rows = value.jsonObject.getValue("runs").jsonArray.map { element ->
                    val row = element.jsonObject
                    val source = row.text("source")
                    val owner = row.optionalText("profile") ?: profile.takeIf { source == "cron_output" } ?: error("owner")
                    require(owner == profile)
                    CronRun(row.text("id").also { require(it.isNotEmpty()) }, owner, source,
                        row.text("title"), row.text("preview"), row.number("started_at"),
                        row.number("last_active"), row.number("ended_at"),
                        row.optionalFlag("scheduler_owned"), row.optionalFlag("is_active"))
                }
                require(rows.map { it.id }.distinct().size == rows.size)
                rows
            }
        }
    }

    override suspend fun deliveryTargets(): CronResult<List<CronDeliveryTarget>> = owned {
        if (profile == "all") return@owned CronResult.Failure(CronFailure.InvalidScope)
        decode(exchange("/api/cron/delivery-targets")) { value ->
            value.jsonObject.getValue("targets").jsonArray.map { element ->
                val row = element.jsonObject
                CronDeliveryTarget(row.text("id").also { require(it.isNotEmpty()) }, row.text("name"), row.flag("home_target_set", true))
            }.also { rows -> require(rows.map { it.id }.distinct().size == rows.size) }
        }
    }

    override suspend fun blueprints(): CronResult<List<CronBlueprint>> = owned {
        if (profile == "all") return@owned CronResult.Failure(CronFailure.InvalidScope)
        decode(exchange("/api/cron/blueprints")) { value ->
            value.jsonObject.getValue("blueprints").jsonArray.map { element ->
                val row = element.jsonObject
                val fields = (row["fields"] as? JsonArray ?: error("fields")).map { entry ->
                    val field = entry.jsonObject
                    CronBlueprintField(field.text("name"), field.text("type"), field.text("label"),
                        field["default"]?.takeUnless { it == JsonNull }?.let { (it as? JsonPrimitive)?.content ?: it.toString() },
                        (field["options"] as? JsonArray ?: JsonArray(emptyList())).map { option ->
                            (option as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("option")
                        }, field.flag("optional", false), field.flag("strict", true), field.text("help"))
                }
                require(fields.all { it.name.isNotEmpty() && it.type.isNotEmpty() })
                require(fields.map { it.name }.distinct().size == fields.size)
                CronBlueprint(row.text("key").also { require(it.isNotEmpty()) }, row.text("title"), row.text("description"), fields, row.optionalText("plugin"))
            }.also { rows -> require(rows.map { it.key }.distinct().size == rows.size) }
        }
    }
    override suspend fun create(draft: CronDraft): CronResult<CronJob> = owned { operation ->
        if (profile == "all" || draft.prompt.isBlank() || draft.schedule.isBlank())
            return@owned CronResult.Failure(CronFailure.InvalidScope)
        val payload = draft.createPayload()
        when (val receipt = exchange("/api/cron/jobs", method = "POST", body = payload, operation = operation)) {
            is CronResult.Failure -> receipt
            is CronResult.Success -> when (val created = exactJob(receipt.value)) {
                is CronResult.Failure -> created
                is CronResult.Success -> {
                    operation.savedId = created.value.id
                    confirm(created.value.id) { matchesDraft(it, draft, created.value.schedule) }
                }
            }
        }
    }

    private suspend fun confirm(jobId: String, matches: (CronJob) -> Boolean): CronResult<CronJob> =
        when (val readback = detail(jobId)) {
            is CronResult.Success -> if (matches(readback.value)) readback else CronResult.Failure(CronFailure.Unconfirmed, savedJobId = jobId)
            is CronResult.Failure -> readback.copy(kind = if (readback.kind == CronFailure.StaleScope) CronFailure.StaleScope else CronFailure.Unconfirmed, savedJobId = jobId)
        }
    override suspend fun update(job: CronJob, draft: CronDraft): CronResult<CronJob> = owned { operation ->
        if (profile == "all" || job.profile != profile ||
            (draft.schedule != job.schedule && draft.schedule.isBlank()) || (!job.scriptOnly && draft.prompt.isBlank()))
            return@owned CronResult.Failure(CronFailure.InvalidScope)
        when (val before = preflight(job.id)) {
            is CronResult.Failure -> before
            is CronResult.Success -> {
                val updates = draft.updatesFrom(job)
                val receipt = exchange(jobPath(job.id), method = "PUT", body = buildJsonObject { put("updates", updates) }, operation = operation)
                when (receipt) {
                    is CronResult.Failure -> receipt
                    is CronResult.Success -> when (val accepted = exactJob(receipt.value, job.id)) {
                        is CronResult.Failure -> accepted
                        is CronResult.Success -> {
                            operation.savedId = job.id
                            confirm(job.id) { actual -> matchesUpdates(actual, updates, accepted.value) &&
                                actual.script == before.value.script && actual.noAgent == before.value.noAgent }
                        }
                    }
                }
            }
        }
    }
    override suspend fun act(job: CronJob, action: CronAction): CronResult<Unit> = owned { operation ->
        if (profile == "all" || job.profile != profile) return@owned CronResult.Failure(CronFailure.InvalidScope)
        val before = preflight(job.id)
        if (before is CronResult.Failure) return@owned before
        val original = (before as CronResult.Success).value
        val path = jobPath(job.id) + when (action) {
            CronAction.Pause -> "/pause"
            CronAction.Resume -> "/resume"
            CronAction.Trigger -> "/trigger"
            CronAction.Delete -> ""
        }
        val receipt = exchange(path, method = if (action == CronAction.Delete) "DELETE" else "POST",
            body = if (action == CronAction.Delete) null else buildJsonObject {}, operation = operation,
            // Like Desktop, this endpoint waits for the complete execution.
            // The UI's shorter queued-feedback ceiling is not its HTTP deadline.
            timeoutMillis = if (action == CronAction.Trigger) 86_400_000 else 20_000)
        if (receipt is CronResult.Failure) return@owned receipt
        val value = (receipt as CronResult.Success).value
        if (action == CronAction.Delete) {
            if ((value as? JsonObject)?.get("ok") != JsonPrimitive(true)) return@owned CronResult.Failure(CronFailure.Unconfirmed)
            // Upstream's delete receipt has no owner. A vanished-owner fallback
            // after preflight is indistinguishable from the intended deletion;
            // absence confirms this inventory only, not atomic server isolation.
            return@owned when (val inventory = readJobs()) {
                is CronResult.Failure -> CronResult.Failure(CronFailure.Unconfirmed)
                is CronResult.Success -> if (inventory.value.none { it.id == job.id }) CronResult.Success(Unit)
                    else CronResult.Failure(CronFailure.Unconfirmed)
            }
        }
        val accepted = exactJob(value, job.id)
        if (accepted is CronResult.Failure) return@owned accepted
        operation.savedId = job.id
        val receiptJob = (accepted as CronResult.Success).value
        if (action == CronAction.Trigger && !receiptJob.enabled && receiptJob.state == "completed") {
            when (val inventory = readJobs()) {
                is CronResult.Failure -> return@owned CronResult.Failure(CronFailure.Unconfirmed)
                is CronResult.Success -> if (inventory.value.none { it.id == job.id }) {
                    // Older producers may remove a finite job and return its pre-run
                    // record. Do not follow the item route's cross-owner fallback.
                    return@owned if (receiptJob.lastError.isNullOrBlank()) CronResult.Success(Unit)
                    else CronResult.Failure(CronFailure.Unconfirmed)
                }
            }
            // Current upstream retains completed records. Confirm the named
            // detail and changed run stamp, then classify its execution result.
        }
        val readback = confirm(job.id) { actual -> when (action) {
            CronAction.Pause -> !actual.enabled && actual.state == "paused"
            CronAction.Resume -> actual.enabled && actual.state != "paused"
            CronAction.Trigger -> actual.lastRunAt != null && actual.lastRunAt != original.lastRunAt
            CronAction.Delete -> false
        } }
        when (readback) {
            is CronResult.Failure -> readback
            is CronResult.Success -> if (action == CronAction.Trigger && !readback.value.lastError.isNullOrBlank())
                CronResult.Failure(CronFailure.Refused, savedJobId = job.id)
            else CronResult.Success(Unit)
        }
    }
    override suspend fun instantiate(key: String, values: Map<String, String>): CronResult<CronJob> = owned { operation ->
        if (profile == "all" || key.isEmpty()) return@owned CronResult.Failure(CronFailure.InvalidScope)
        val body = buildJsonObject { put("blueprint", key); putJsonObject("values") { values.forEach { (name, value) -> put(name, value) } } }
        when (val receipt = exchange("/api/cron/blueprints/instantiate", method = "POST", body = body, operation = operation)) {
            is CronResult.Failure -> receipt
            is CronResult.Success -> when (val created = exactJob(receipt.value)) {
                is CronResult.Failure -> created
                is CronResult.Success -> {
                    operation.savedId = created.value.id
                    confirm(created.value.id) { it == created.value }
                }
            }
        }
    }

    private class Operation(var dispatched: Boolean = false, var savedId: String? = null)
    private suspend fun <T> owned(block: suspend (Operation) -> CronResult<T>): CronResult<T> {
        currentCoroutineContext().ensureActive()
        if (!current()) return CronResult.Failure(CronFailure.StaleScope)
        val operation = Operation()
        val result = try { block(operation) }
        catch (cancelled: CancellationException) {
            // Once enqueued, cancellation is not evidence that the server did nothing.
            // An already-cancelled caller can still discard this result; its controller
            // must keep its pending/unknown mutation lock until a later explicit read.
            if (!operation.dispatched) throw cancelled
            CronResult.Failure(CronFailure.Unconfirmed)
        }
        catch (_: Exception) { CronResult.Failure(CronFailure.Unreachable) }
        return if (!current()) CronResult.Failure(CronFailure.StaleScope, operation.dispatched, operation.savedId)
        else if (result is CronResult.Failure) result.copy(mutationMayHaveApplied = operation.dispatched, savedJobId = result.savedJobId ?: operation.savedId)
        else result
    }

    private suspend fun exchange(
        path: String,
        query: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: JsonObject? = null,
        operation: Operation? = null,
        timeoutMillis: Long = 20_000,
    ): CronResult<JsonElement> {
        currentCoroutineContext().ensureActive()
        val request = GatewayHttpRequest(path, method, body?.toString()?.toRequestBody("application/json".toMediaType()), timeoutMillis,
            query = query + ("profile" to profile), maxResponseBytes = 4L * 1024 * 1024,
            captureEnvelope = operation != null, isCurrent = ::current)
        val result = transport.executeAtDispatch(request) { send ->
            fence.dispatchIfCurrent(lease, owns) { synchronized(lock) {
                !closed && send().also { admitted -> if (admitted) operation?.dispatched = true }
            } }
        }
        return when (result) {
            is GatewayHttpResult.Success -> result.consumeBody { bytes ->
                if (!current()) CronResult.Failure(CronFailure.StaleScope)
                else try { CronResult.Success(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))) }
                catch (_: Exception) { CronResult.Failure(CronFailure.InvalidResponse) }
            }
            is GatewayHttpResult.Rejected -> result.consumeEnvelope { bytes ->
                if (current() && result.statusCode == 424 && operation != null) {
                    val saved = try {
                        val envelope = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                        val detail = (envelope["detail"] as? JsonObject) ?: envelope
                        if (detail.flag("job_saved", false) && !detail.flag("scheduler_registered", true) && !detail.flag("retry_create", true))
                            detail.text("job_id").takeIf { it.isNotEmpty() } else null
                    } catch (_: Exception) { null }
                    operation.savedId = saved
                    CronResult.Failure(if (saved != null) CronFailure.SavedRegistrationFailed else CronFailure.Unconfirmed, savedJobId = saved)
                } else CronResult.Failure(if (!current()) CronFailure.StaleScope else when (result.statusCode) {
                    0 -> CronFailure.Unreachable
                    in 200..299 -> CronFailure.InvalidResponse
                    else -> CronFailure.Refused
                })
            }
        }
    }
}

private fun validProfile(value: String) = value !in setOf("all", "current") &&
    Regex("[a-z0-9][a-z0-9_-]{0,63}").matches(value)

private fun CronDraft.createPayload(): JsonObject = buildJsonObject {
    put("name", name); put("prompt", prompt); put("schedule", schedule); put("deliver", deliver)
    put("model", model?.takeUnless { it.isBlank() }?.let(::JsonPrimitive) ?: JsonNull)
    put("provider", if (model.isNullOrBlank()) JsonNull else provider?.takeUnless { it.isBlank() }?.let(::JsonPrimitive) ?: JsonNull)
}

private fun matchesDraft(job: CronJob, draft: CronDraft, normalizedSchedule: String): Boolean =
    job.name == draft.name.trim().ifEmpty { draft.prompt.trim().let { prompt ->
        // The Python producer slices Unicode code points, not UTF-16 units.
        prompt.substring(0, prompt.offsetByCodePoints(0, minOf(50, prompt.codePointCount(0, prompt.length))))
    }.trim().ifEmpty { "cron job" } } &&
    job.prompt in setOf(draft.prompt, draft.prompt.trim()) &&
    scheduleMatches(normalizedSchedule, job.schedule) && job.deliver == draft.deliver.trim().ifEmpty { "local" } &&
    job.model == draft.model?.trim()?.takeUnless { it.isBlank() } &&
    job.provider == draft.provider?.trim()?.takeUnless { it.isBlank() || draft.model.isNullOrBlank() }

private fun CronDraft.updatesFrom(job: CronJob): JsonObject = buildJsonObject {
    if (name != job.name) put("name", name)
    if (prompt != job.prompt && (!job.scriptOnly || prompt.isNotBlank())) put("prompt", prompt)
    // Do not replace a structured schedule just because an unrelated field changed.
    if (schedule != job.schedule) put("schedule", schedule)
    if (deliver != job.deliver) put("deliver", deliver)
    if (!job.scriptOnly && (model != job.model || provider != job.provider)) {
        put("model", model?.takeUnless { it.isBlank() }?.let(::JsonPrimitive) ?: JsonNull)
        put("provider", if (model.isNullOrBlank()) JsonNull else provider?.takeUnless { it.isBlank() }?.let(::JsonPrimitive) ?: JsonNull)
    }
}

private fun matchesUpdates(job: CronJob, updates: JsonObject, accepted: CronJob): Boolean = updates.all { (key, value) ->
    if (key == "schedule") return@all scheduleMatches(accepted.schedule, job.schedule)
    val actual: String? = when (key) {
        "name" -> job.name
        "prompt" -> job.prompt
        "schedule" -> job.schedule
        "deliver" -> job.deliver
        "model" -> job.model
        "provider" -> job.provider
        else -> return@all false
    }
    val requested = value.takeUnless { it == JsonNull }?.jsonPrimitive?.content
    actual == when (key) {
        // Clearing delegates fallback labels (prompt, skills, script, id) to
        // the producer; its normalized receipt still needs fresh confirmation.
        "name" -> requested?.trim()?.ifEmpty { accepted.name }
        "model", "provider" -> requested?.trim()?.takeUnless { it.isBlank() }
        "deliver" -> requested?.trim()?.ifEmpty { "local" }
        else -> requested
    }
}

private fun scheduleMatches(normalized: String, actual: String): Boolean =
    // The producer resolves natural phrases before cron expressions. Word-count
    // or token heuristics cannot distinguish them; use its parsed receipt and
    // require an exact match from the fresh persisted detail for either kind.
    actual == normalized

private fun encodeSegment(value: String): String = buildString {
    val hex = "0123456789ABCDEF"
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val n = byte.toInt() and 255
        if (n in 65..90 || n in 97..122 || n in 48..57 || n == 45 || n == 95 || n == 126) append(n.toChar())
        else { append('%'); append(hex[n ushr 4]); append(hex[n and 15]) }
    }
}

private fun JsonObject.text(key: String, default: String = ""): String {
    val value = this[key] ?: return default
    if (value == JsonNull) return default
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("string")
}
private fun JsonObject.optionalText(key: String): String? = if (this[key] == null || this[key] == JsonNull) null else text(key)
private fun JsonObject.flag(key: String, default: Boolean): Boolean = this[key]?.let {
    (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull ?: error("boolean")
} ?: default

private fun JsonObject.optionalFlag(key: String): Boolean? =
    if (this[key] == null || this[key] == JsonNull) null else flag(key, false)

private fun JsonObject.number(key: String): Double? = this[key]?.takeUnless { it == JsonNull }?.let {
    (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.doubleOrNull?.takeIf { n -> n.isFinite() } ?: error("number")
}

private inline fun <T> decode(result: CronResult<JsonElement>, parse: (JsonElement) -> T): CronResult<T> = when (result) {
    is CronResult.Failure -> result
    is CronResult.Success -> try { CronResult.Success(parse(result.value)) }
    catch (_: Exception) { CronResult.Failure(CronFailure.InvalidResponse) }
}

private fun parseJob(element: JsonElement): CronJob {
    val row = element as? JsonObject ?: error("job")
    val id = row.text("id").also { require(it.isNotEmpty()) }
    val profile = row.text("profile").also { require(validProfile(it)) }
    val schedule = when (val value = row["schedule"]) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.takeIf { it.isString }?.content ?: error("schedule")
        is JsonObject -> value.optionalText("expr") ?: value.optionalText("display") ?: row.text("schedule_display")
        else -> error("schedule")
    }
    return CronJob(id, profile, row.text("name"), row.text("prompt"), schedule,
        row.text("schedule_display"), row.text("deliver", "local"), row.optionalText("model"),
        row.optionalText("provider"), row.flag("enabled", true), row.text("state", "scheduled"),
        row.optionalText("last_run_at"), row.optionalText("next_run_at"), row.optionalText("last_error"),
        row.optionalText("script"), row.flag("no_agent", false))
}
