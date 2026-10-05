package com.hermesagent.mobile.device

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Test-only current caller-user snapshot; never a focused-dialog match or historical join. */
internal object CurrentAnrCandidate {
    enum class Status { CURRENT_ANR_CANDIDATE, UNKNOWN }
    enum class Cause { NONE, NOT_ATTESTED, ALREADY_ATTEMPTED, NO_RECORD, MULTIPLE, INVALID_RECORD,
        OVERFLOW, ACQUISITION_FAILED, CLEANUP_FAILED, TIMEOUT }
    enum class Role { APP_PROCESS, SYSTEM_UI, UNKNOWN }
    enum class Reason { INPUT_DISPATCH_TIMEOUT, UNKNOWN }
    data class Result(val status: Status = Status.UNKNOWN, val cause: Cause = Cause.NONE,
                      val role: Role = Role.UNKNOWN, val reason: Reason = Reason.UNKNOWN)
    data class Identity(val pid: Int, val uid: Int, val name: String)
    data class Row(val condition: Int, val name: String?, val pid: Int, val uid: Int, val shortMsg: String?)
    fun unknown(cause: Cause) = Result(cause = cause)
    private fun bounded(text: String, cap: Int) = text.length <= cap &&
        text.none { Character.isISOControl(it) || it == '\u2028' || it == '\u2029' || it.isSurrogate() }

    fun reduce(rows: List<Row?>?, app: Identity): Result {
        if (rows == null || rows.isEmpty()) return unknown(Cause.NO_RECORD)
        if (rows.size > 32) return unknown(Cause.OVERFLOW)
        var sole: Row? = null
        var count = 0
        for (row in rows) {
            if (row == null || row.condition !in 0..2 || row.pid < 0 || row.uid < 0 ||
                row.name.isNullOrBlank() || !bounded(row.name, 256) ||
                (row.shortMsg != null && !bounded(row.shortMsg, 4096))) return unknown(Cause.INVALID_RECORD)
            if (row.condition == 2) { count++; sole = row }
        }
        if (count > 1) return unknown(Cause.MULTIPLE)
        val row = sole ?: return unknown(Cause.NO_RECORD)
        val role = when {
            row.pid == app.pid && row.uid == app.uid && row.name == app.name -> Role.APP_PROCESS
            // Exact sourced process-name role, not a proven package, user or dialog mapping.
            row.name == "com.android.systemui" -> Role.SYSTEM_UI
            else -> Role.UNKNOWN
        }
        val msg = row.shortMsg
        val prefix = "ANR Input dispatching timed out"
        val reason = if (msg == prefix || (msg != null && msg.startsWith("$prefix (") && msg.endsWith(")")))
            Reason.INPUT_DISPATCH_TIMEOUT else Reason.UNKNOWN
        return Result(Status.CURRENT_ANR_CANDIDATE, role = role, reason = reason)
    }

    interface Api {
        fun adopt()
        fun query(): Result
        fun drop()
    }

    /** One instance per instrumentation process. No cancellation or waiting-thread permission drop. */
    class Collector(
        private val launch: (() -> Unit) -> Unit = { work ->
            Thread(work, "current-anr-candidate").apply { isDaemon = true }.start()
        },
        private val await: (CountDownLatch, Long) -> Boolean = { latch, millis ->
            latch.await(millis, TimeUnit.MILLISECONDS)
        },
    ) {
        private val attempted = AtomicBoolean(false)
        fun collect(attested: Boolean, api: Api): Result {
            if (!attested) return unknown(Cause.NOT_ATTESTED)
            if (!attempted.compareAndSet(false, true)) return unknown(Cause.ALREADY_ATTEMPTED)
            val done = CountDownLatch(1)
            val publication = AtomicReference<Result?>(null)
            try {
                launch {
                    var result = unknown(Cause.ACQUISITION_FAILED)
                    try {
                        api.adopt()
                        result = api.query()
                    } catch (_: Throwable) {
                        // Never expose exception text, even if adoption had a remote side effect.
                    } finally {
                        try { api.drop() } catch (_: Throwable) { result = unknown(Cause.CLEANUP_FAILED) }
                        // A timeout closes the slot permanently. Acceptance requires drop to return.
                        publication.compareAndSet(null, result)
                        done.countDown()
                    }
                }
                if (await(done, 2500)) return publication.get() ?: unknown(Cause.ACQUISITION_FAILED)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
                publication.set(unknown(Cause.ACQUISITION_FAILED))
                return unknown(Cause.ACQUISITION_FAILED)
            }
            val timeout = unknown(Cause.TIMEOUT)
            publication.set(timeout)
            return timeout
        }
    }
}
