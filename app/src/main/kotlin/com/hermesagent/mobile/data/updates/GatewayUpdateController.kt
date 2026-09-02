package com.hermesagent.mobile.data.updates

import com.hermesagent.mobile.data.gateway.GatewayAction
import com.hermesagent.mobile.data.gateway.GatewayActionStatus
import com.hermesagent.mobile.data.gateway.GatewayRestResult
import com.hermesagent.mobile.data.gateway.GatewayUpdateCheck
import com.hermesagent.mobile.data.gateway.GatewayUpdateReceipt
import com.hermesagent.mobile.data.gateway.GatewayUpdateStart
import com.hermesagent.mobile.data.ssh.redact
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Where an apply has got to. Desktop's `UpdateApplyState.stage`
 * (`apps/desktop/src/store/updates.ts:26-38` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`), minus the four stages only its
 * own client-side updater reaches (`fetch`, `pydeps`, `rebuild`, `guiSkew`).
 */
enum class GatewayUpdateStage { Idle, Prepare, Pull, Restart, Done, Manual, Error }

/**
 * The one-line status beside the stage. A key, not a sentence: the sentences are
 * Desktop's (`en.ts:2668-2675` @ the pin) and belong with the rest of the
 * surface's copy, not in the engine that drives it.
 */
enum class GatewayUpdateStatusKey { Preparing, Pulling, Restarting, NotAvailable, Failed, NoReturn }

/**
 * What the update sheet paints, and the only thing this controller publishes.
 *
 * Every Gateway-authored string in here — [message], [command], [log] — has
 * already been through [redact] and a per-line cap. Doing it at this boundary
 * rather than at the `Text(...)` is deliberate: a surface that forgets is a
 * token on a screen, and there is exactly one place backend text enters this
 * state.
 */
data class GatewayUpdateState(
    val applying: Boolean = false,
    val stage: GatewayUpdateStage = GatewayUpdateStage.Idle,
    val status: GatewayUpdateStatusKey? = null,
    /** The host's own refusal text, for [GatewayUpdateStage.Manual]. */
    val message: String? = null,
    /** The remediation command the host named, for [GatewayUpdateStage.Manual]. */
    val command: String? = null,
    /** The action log tail, newest last. Never more than [MAX_LOG_LINES]. */
    val log: List<String> = emptyList(),
    /**
     * The durable receipt for the run that just finished, read once when the
     * apply reaches a terminal stage.
     *
     * Held rather than rendered: it carries `pre_update`/`post_update` versions
     * and `hermes serve`'s own recovery buckets
     * (`hermes_cli/update_receipt.py:135-155` @ the pin), and Desktop has no
     * string for any of it — inventing one would be exactly the drift the parity
     * gate exists to catch. It is state a later slice renders, or a bug report
     * reads.
     */
    val receipt: GatewayUpdateReceipt? = null,
) {
    /** The latest log line, which is what the applying view centres. */
    val latestLogLine: String? get() = log.lastOrNull()
}

/**
 * Applies a backend update and survives the backend restarting underneath it.
 *
 * A port of Desktop's `runBackendUpdate` (`store/updates.ts:638-766` @ the
 * pin), which is the *robust* path for `POST /api/hermes/update` — the one the
 * updates overlay and the `Update Hermes` toast action drive. Desktop's System
 * panel has a second, cruder path that fire-and-forgets the same POST and polls
 * for 21.6 s (`app/command-center/index.tsx:266-301`); that one has nowhere to
 * put a six-minute outcome, which on a phone is every outcome.
 *
 * Three things make this app-scoped rather than a ViewModel's:
 *
 * 1. **Leaving the screen must not cancel an apply.** The person who taps
 *    `Update now` is going to put the phone down. A coroutine in
 *    `viewModelScope` dies on the next configuration change, silently, halfway
 *    through a `git pull` on somebody's server.
 * 2. **The backend restarts mid-flight, and that is the normal path.** The
 *    poll stops answering, the deadline extends, and success is re-derived from
 *    the durable action id or the receipt rather than from liveness
 *    (`web_server.py:4814-4839`, `:5890-5920`).
 * 3. **Success has to redial.** The update restarts the gateway and strands the
 *    socket; over a tunnel the old TCP connection dies with no close event, so
 *    the connection still reads open while every RPC hangs (`updates.ts:543-550`).
 *
 * @param wait injected so the whole six-minute budget runs on virtual time.
 * @param nowMillis injected for the same reason; the deadlines are wall clock.
 * @param redial [GatewayUpdateRedial]; called exactly once, on a successful apply.
 */
internal class GatewayUpdateController(
    private val scope: CoroutineScope,
    private val api: GatewaySystemApi,
    private val redial: GatewayUpdateRedial,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val wait: suspend (Long) -> Unit = { millis -> delay(millis) },
) {
    private val _state = MutableStateFlow(GatewayUpdateState())
    val state: StateFlow<GatewayUpdateState> = _state.asStateFlow()

    /**
     * The apply in flight, or null. Desktop's `backendUpdateInFlight`
     * (`updates.ts:636,768-778`): a second tap joins the first rather than
     * starting a second `hermes update` on the host.
     */
    private var applyJob: Job? = null

    /** The last check, for the legacy no-`action_id` fallback (`updates.ts:649-653`). */
    private var lastCheck: GatewayUpdateCheck? = null

    /** Records what a check saw, so a later apply can fall back on it. */
    fun rememberCheck(check: GatewayUpdateCheck) {
        lastCheck = check
    }

    /**
     * Start, or join, an apply.
     *
     * Returns the job so a caller can await it; nothing in the app does, because
     * the state flow is the whole contract.
     */
    fun apply(): Job {
        applyJob?.takeIf { it.isActive }?.let { return it }
        val started = scope.launch { runApply() }
        applyJob = started
        started.invokeOnCompletion { if (applyJob === started) applyJob = null }
        return started
    }

    /**
     * Forget everything about this Gateway.
     *
     * Hooked to the connection switch, which is the one wholesale clear this app
     * has: the next backend is a different machine whose version, receipt and
     * half-finished update have nothing to do with this one's. An apply in
     * flight is cancelled with it — its host is no longer the host this device
     * is on, so there is nothing left for it to report to.
     */
    fun reset() {
        applyJob?.cancel()
        applyJob = null
        lastCheck = null
        _state.value = GatewayUpdateState()
    }

    /**
     * Forget a finished apply, so the sheet opens on a fresh check next time.
     *
     * Desktop's `resetUpdateApplyState` on the way out of `error`, `restart`,
     * `manual` or `guiSkew` (`app/updates-overlay.tsx:92-97` @ the pin). An
     * apply still in flight is untouched: this is the person dismissing a
     * *report*, and the report of a running update is not theirs to discard.
     */
    fun dismissTerminalState() {
        if (_state.value.applying) return
        _state.value = GatewayUpdateState()
    }

    private suspend fun runApply() {
        _state.value = GatewayUpdateState(
            applying = true,
            stage = GatewayUpdateStage.Prepare,
            status = GatewayUpdateStatusKey.Preparing,
        )

        val previousCheck = lastCheck
        val requestedTargetSha = previousCheck?.commits?.firstOrNull()?.sha
        val previousVersion = previousCheck?.currentVersion?.takeIf { previousCheck.updateAvailable }

        val started = when (val result = api.startUpdate()) {
            is GatewayRestResult.Failed -> return terminal(GatewayUpdateStatusKey.Failed)
            is GatewayRestResult.Success -> result.value
        }
        val applyStartedAtMillis = nowMillis()

        // HTTP 200 with `ok:false` is the host refusing to update itself in
        // place (`web_server.py:5088-5124` @ the pin) — a terminal answer with
        // a remediation attached, not a failure to retry.
        if (started is GatewayUpdateStart.Refused) {
            _state.value = GatewayUpdateState(
                applying = false,
                stage = GatewayUpdateStage.Manual,
                status = GatewayUpdateStatusKey.NotAvailable,
                message = started.message?.let(::safeBackendLine),
                command = safeBackendLine(started.updateCommand ?: DEFAULT_UPDATE_COMMAND),
            )
            readReceipt()
            return
        }
        val actionId = (started as GatewayUpdateStart.Started).actionId

        publish { it.copy(applying = true, stage = GatewayUpdateStage.Pull, status = GatewayUpdateStatusKey.Pulling) }

        var last: GatewayActionStatus? = null
        // Backups, dependency repair and builds legitimately take minutes; the
        // generous cap is a guard against a stuck action, not a schedule
        // (`updates.ts:674-675`).
        val actionDeadline = nowMillis() + ACTION_BUDGET_MILLIS
        var deadline = actionDeadline
        var reconnecting = false

        while (nowMillis() < deadline) {
            wait(POLL_INTERVAL_MILLIS)

            val polled = api.actionStatus(GatewayAction.HermesUpdate, POLL_LINES)
            if (polled is GatewayRestResult.Failed) {
                // Desktop's `catch`. This app can tell "nothing reached the
                // host" (status 0) from "the host answered 502" and
                // deliberately does not fork on it: a backend that is
                // restarting behind a reverse proxy produces both, and they are
                // the same fact — the update is between processes.
                if (!reconnecting) {
                    reconnecting = true
                    deadline = nowMillis() + RETURN_BUDGET_MILLIS
                    publish {
                        it.copy(
                            applying = true,
                            stage = GatewayUpdateStage.Restart,
                            status = GatewayUpdateStatusKey.Restarting,
                        )
                    }
                }
                continue
            }
            val status = (polled as GatewayRestResult.Success).value
            last = status
            ingest(status)

            if (status.running) {
                if (reconnecting) {
                    reconnecting = false
                    // Resets to the original action deadline computed before the
                    // blackout started (faithful port of `updates.ts:704` @
                    // `3ca096de5f8183cb2e0ec23673f294d5978656a3`), so a blackout
                    // that consumed most of the action budget leaves only whatever
                    // remainder was not elapsed.
                    deadline = actionDeadline
                    publish {
                        it.copy(
                            applying = true,
                            stage = GatewayUpdateStage.Pull,
                            status = GatewayUpdateStatusKey.Pulling,
                        )
                    }
                }
                continue
            }

            if (status.exitCode == 0L || (status.exitCode == null && completedAfterRestart(status, actionId))) {
                return finish(returned = true)
            }

            // The receipt is the durable, structured truth about the run, and
            // the only thing that can answer across a restart the log rotated
            // away (`web_server.py:5890-5920` @ the pin).
            if (status.exitCode == null && receiptProvesOutcome(status, applyStartedAtMillis)) {
                return finish(returned = status.receipt?.outcome == RECEIPT_SUCCESS)
            }

            // A Gateway older than the durable action id cannot prove anything
            // about itself, so ask the update check whether the host moved.
            if (actionId == null && status.exitCode == null) {
                when (val recheck = api.checkUpdate(force = true)) {
                    is GatewayRestResult.Success ->
                        if (legacyReachedTarget(recheck.value, requestedTargetSha, previousVersion)) {
                            return finish(returned = true)
                        }

                    is GatewayRestResult.Failed -> continue
                }
            }

            if (status.exitCode != null) break
        }

        // Two ways to arrive here, and they are different facts. A terminal
        // non-zero exit is a failed update. A budget that ran out *while the
        // host was not answering* is an update whose outcome nobody knows —
        // which on the Remote route over a tunnel, the topology this app is
        // built around (`docs/adr/0002-shared-remote-gateway.md`), is the
        // ordinary way an apply ends rather than the exceptional one. Desktop
        // reports `failed` for both because its own backend is usually on the
        // same machine; saying "failed" to someone whose server is probably
        // fine is the phone's worst answer, so each of its two strings is used
        // for the state it was written for.
        terminal(
            if (last?.exitCode == null && reconnecting) {
                GatewayUpdateStatusKey.NoReturn
            } else {
                GatewayUpdateStatusKey.Failed
            },
        )
    }

    /**
     * Desktop's `finishBackendApply` (`updates.ts:538-568`).
     *
     * The redial is the point. A successful update restarted the gateway on the
     * host; over a tunnel the old TCP connection can die with no close event,
     * so this client's socket reads open while every RPC hangs, and the person
     * force-quits the app to recover.
     */
    private suspend fun finish(returned: Boolean) {
        if (!returned) return terminal(GatewayUpdateStatusKey.NoReturn)
        publish { it.copy(applying = false, stage = GatewayUpdateStage.Done, status = null) }
        readReceipt()
        // Best effort, both of them: the apply already succeeded, and neither a
        // stale behind-count nor a socket that was fine anyway is a reason to
        // report failure.
        (api.checkUpdate(force = true) as? GatewayRestResult.Success)?.let { lastCheck = it.value }
        redial.redialAfterBackendUpdate()
    }

    private suspend fun terminal(status: GatewayUpdateStatusKey) {
        publish { it.copy(applying = false, stage = GatewayUpdateStage.Error, status = status) }
        readReceipt()
    }

    /** Best effort. A 404 here is the host saying no update was ever recorded. */
    private suspend fun readReceipt() {
        val receipt = (api.updateReceipt() as? GatewayRestResult.Success)?.value ?: return
        publish { it.copy(receipt = receipt) }
    }

    /** Non-blank lines only, newest last, capped (`updates.ts:571-590`). */
    private fun ingest(status: GatewayActionStatus) {
        val lines = status.lines
            .filter { it.isNotBlank() }
            .map(::safeBackendLine)
            .takeLast(MAX_LOG_LINES)
        if (lines.isEmpty()) return
        publish { it.copy(log = lines) }
    }

    private fun publish(update: (GatewayUpdateState) -> GatewayUpdateState) {
        _state.value = update(_state.value)
    }

    private companion object {
        /**
         * The host writes this into `update.log` when the run it started
         * finishes, and the status route replays it even after the process that
         * spawned the child has been restarted by the update itself
         * (`web_server.py:4814-4839,5831-5833` @ the pin). Matched against the
         * *raw* line, before redaction: a marker this app rewrote is a marker it
         * can no longer recognise.
         */
        fun completedAfterRestart(status: GatewayActionStatus, actionId: String?): Boolean {
            if (actionId == null) return false
            val marker = "=== hermes-update completed $actionId ==="
            return status.lines.any { it == marker }
        }

        /**
         * Whether the receipt attached to this poll describes *this* apply
         * (`updates.ts:599-618`).
         *
         * A receipt that has not finished proves nothing yet, and one that
         * started before this apply describes a previous update. The 60-second
         * slack absorbs clock skew between this phone and the host.
         */
        fun receiptProvesOutcome(status: GatewayActionStatus, applyStartedAtMillis: Long): Boolean {
            val receipt = status.receipt ?: return false
            if (receipt.finishedAt == null || receipt.startedAt == null) return false
            if (receipt.outcome !in RECEIPT_TERMINAL_OUTCOMES) return false
            val startedAt = parseIsoMillis(receipt.startedAt) ?: return false
            return startedAt >= applyStartedAtMillis - RECEIPT_CLOCK_SLACK_MILLIS
        }

        /**
         * The check-based fallback for a Gateway with no durable action id
         * (`updates.ts:620-634`).
         */
        fun legacyReachedTarget(
            check: GatewayUpdateCheck,
            targetSha: String?,
            previousVersion: String?,
        ): Boolean {
            if (check.behind == 0L) return true
            if (previousVersion != null && check.currentVersion != previousVersion) return true
            return targetSha != null &&
                check.commits.isNotEmpty() &&
                check.commits.none { it.sha == targetSha }
        }

        /**
         * The host writes `datetime.now(timezone.utc).isoformat()`
         * (`update_receipt.py:53`), which is `+00:00` rather than `Z`. Both
         * spellings are accepted, and anything else is simply not a timestamp —
         * Desktop's `Date.parse` returns `NaN` there and this returns null, and
         * both mean "the receipt proves nothing".
         */
        fun parseIsoMillis(raw: String): Long? =
            runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()

        const val RECEIPT_SUCCESS = "success"
        val RECEIPT_TERMINAL_OUTCOMES = setOf(RECEIPT_SUCCESS, "partial", "failed")
        const val RECEIPT_CLOCK_SLACK_MILLIS = 60_000L
    }
}

/**
 * What a finished backend update asks of the connection layer.
 *
 * One method, and it is the whole reason this seam exists: the controller must
 * not import the connection manager, and the connection manager must not know
 * what an update is.
 */
internal fun interface GatewayUpdateRedial {
    suspend fun redialAfterBackendUpdate()
}

/**
 * Redact a Gateway-authored line and cap it.
 *
 * Both halves matter. Redaction is the security half — an action log is the
 * output of somebody's shell and can carry a bearer token or a session token
 * verbatim. The cap is the product half: a build log line is unbounded, and one
 * `pip` line can be thousands of characters, which on a phone is a paragraph
 * where a status line belongs.
 */
internal fun safeBackendLine(raw: String): String =
    redact(raw).replace('\n', ' ').replace('\r', ' ').trim().take(MAX_BACKEND_LINE_CHARS)

/** Desktop's own default when the host names no remediation (`updates.ts:660`). */
internal const val DEFAULT_UPDATE_COMMAND = "hermes update"

/** `BACKEND_ACTION_POLL_MS` (`updates.ts:534`). */
internal const val POLL_INTERVAL_MILLIS = 1_500L

/** `BACKEND_ACTION_MAX_MS` (`updates.ts:535`). */
internal const val ACTION_BUDGET_MILLIS = 6 * 60 * 1_000L

/** `BACKEND_RETURN_MAX_MS` (`updates.ts:536`). */
internal const val RETURN_BUDGET_MILLIS = 4 * 60 * 1_000L

/** What Desktop asks the action-status route for during an apply (`updates.ts:684`). */
internal const val POLL_LINES = 2_000

/**
 * How much log this app keeps. Desktop keeps fifty (`updates.ts:577`); the
 * sheet renders four (`updates-overlay.tsx:391`). Fifty is the number kept
 * because that is what a person scrolls back through on Desktop — here nothing
 * scrolls, so the ceiling exists only to stop an unbounded action log becoming
 * unbounded app state.
 */
internal const val MAX_LOG_LINES = 50

/** One status line, not a paragraph. */
internal const val MAX_BACKEND_LINE_CHARS = 240
