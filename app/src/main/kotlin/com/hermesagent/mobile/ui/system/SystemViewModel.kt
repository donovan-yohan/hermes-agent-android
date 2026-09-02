package com.hermesagent.mobile.ui.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesagent.mobile.data.gateway.DEFAULT_ACTION_LINES
import com.hermesagent.mobile.data.gateway.GatewayAction
import com.hermesagent.mobile.data.gateway.GatewayActionStatus
import com.hermesagent.mobile.data.gateway.GatewayRestResult
import com.hermesagent.mobile.data.gateway.GatewayStatusSummary
import com.hermesagent.mobile.data.gateway.GatewayUpdateCheck
import com.hermesagent.mobile.data.updates.CommitGroup
import com.hermesagent.mobile.data.updates.GatewaySystemApi
import com.hermesagent.mobile.data.updates.GatewayUpdateController
import com.hermesagent.mobile.data.updates.GatewayUpdateStage
import com.hermesagent.mobile.data.updates.GatewayUpdateState
import com.hermesagent.mobile.data.updates.buildCommitChangelog
import com.hermesagent.mobile.data.updates.safeBackendLine
import com.hermesagent.mobile.data.updates.totalItems
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How a host action the panel started is going (`en.ts:1569-1571` @ the pin). */
enum class SystemActionPhase { Running, Done, Failed }

/**
 * The action progress line. [action] is the host's own action name, which is
 * one of exactly two fixed strings — never anything a Gateway wrote.
 */
data class SystemActionState(val action: String, val phase: SystemActionPhase)

/**
 * The updates sheet's idle branches, mirroring `DesktopUpdateStatus`
 * (`apps/desktop/src/store/updates.ts:351-364` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 *
 * `null` on [SystemUiState.check] means no check has ever completed, which is a
 * different sheet from [failed] — a check that ran and could not reach the
 * update source.
 */
data class UpdateCheckState(
    val supported: Boolean,
    /** The host's own explanation, redacted and capped. */
    val message: String?,
    val updateAvailable: Boolean,
    val behind: Int,
    /** Grouped, capped and tidied exactly as Desktop's sheet groups them. */
    val changelog: List<CommitGroup>,
    /** Items past what [changelog] shows, for the "+ N more" line. */
    val moreChanges: Int,
    /** The check ran and failed. Desktop's `error: 'check-failed'`. */
    val failed: Boolean,
) {
    /**
     * Whether the sheet has an update to offer.
     *
     * Desktop's own test, and deliberately not just the flag
     * (`updates-overlay.tsx:69-70`): a host that reports `behind: 3` and omits
     * `update_available` is still behind, and the sheet must not tell someone
     * they are up to date because one field went missing.
     */
    val offersUpdate: Boolean get() = updateAvailable || behind > 0
}

/** Everything the System panel and its updates sheet paint. */
data class SystemUiState(
    val status: GatewayStatusSummary? = null,
    /** A failed status load, in this app's or the transport's own words. */
    val statusError: String? = null,
    val action: SystemActionState? = null,
    /** A failed action, in Desktop's own words. Desktop's `systemError`. */
    val actionError: String? = null,
    /** Whether the updates sheet is open. */
    val sheetOpen: Boolean = false,
    val checking: Boolean = false,
    val check: UpdateCheckState? = null,
    val apply: GatewayUpdateState = GatewayUpdateState(),
) {
    /** A second action must not start while one is running (Desktop's own guard). */
    val actionRunning: Boolean get() = action?.phase == SystemActionPhase.Running

    /** An apply in flight owns the sheet: nothing dismisses it, not even back. */
    val applyLocked: Boolean get() = apply.applying
}

/**
 * The System panel's state, and the restart poll that is its only loop.
 *
 * The *update* engine deliberately is not here: it is app-scoped
 * ([GatewayUpdateController]), because a six-minute apply must outlive the
 * screen that started it. This ViewModel projects its state and asks it to
 * start; it never owns it.
 *
 * @param wait injected so the restart poll's eighteen ticks run on virtual time.
 */
internal class SystemViewModel(
    private val api: GatewaySystemApi,
    private val updates: GatewayUpdateController,
    private val wait: suspend (Long) -> Unit = { millis -> delay(millis) },
) : ViewModel() {

    private val own = MutableStateFlow(OwnState())

    private var actionJob: Job? = null
    private var checkJob: Job? = null

    val uiState: StateFlow<SystemUiState> = combine(own, updates.state) { state, apply ->
        SystemUiState(
            status = state.status,
            statusError = state.statusError,
            action = state.action,
            actionError = state.actionError,
            sheetOpen = state.sheetOpen,
            checking = state.checking,
            check = state.check,
            apply = apply,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SystemUiState())

    /** The screen appeared, or the person came back to it. */
    fun refresh() {
        viewModelScope.launch { loadStatus() }
    }

    /**
     * Restart the messaging gateway and watch the child that does it.
     *
     * Desktop's `runSystemAction('restart')` (`command-center/index.tsx:266-301`
     * @ the pin), cadence included: eighteen polls, 1200 ms apart, stopping the
     * moment the child is no longer running. `exit_code == 0` is a successful
     * *handoff* to the supervisor and not a running gateway
     * (`hermes_cli/web_server.py:4598-4604`), which is why the panel refreshes
     * `/api/status` afterwards rather than claiming anything itself.
     */
    fun restartGateway() {
        if (uiState.value.actionRunning) return
        actionJob?.cancel()
        actionJob = viewModelScope.launch {
            own.update { it.copy(actionError = null) }
            val started = when (val result = api.restartGateway()) {
                is GatewayRestResult.Failed ->
                    return@launch own.update { it.copy(actionError = result.safeMessage) }

                is GatewayRestResult.Success -> result.value
            }
            own.update {
                it.copy(action = SystemActionState(safeActionName(started.name), SystemActionPhase.Running))
            }

            var settled: GatewayActionStatus? = null
            for (attempt in 0 until RESTART_POLL_ATTEMPTS) {
                wait(RESTART_POLL_INTERVAL_MILLIS)
                val polled = api.actionStatus(GatewayAction.GatewayRestart, DEFAULT_ACTION_LINES)
                // A poll that failed is not an answer: Desktop's loop throws out
                // of the whole action there, and this one keeps ticking, because
                // the restart it is watching is exactly the kind of work that
                // makes one poll miss.
                if (polled !is GatewayRestResult.Success) continue
                val status = polled.value
                own.update {
                    it.copy(action = SystemActionState(safeActionName(status.name), status.phase()))
                }
                if (!status.running) {
                    settled = status
                    break
                }
            }
            // Desktop leaves the line reading `running` when its eighteen polls
            // run out (`:286-297`) — the truthful thing to say, because the
            // child is still going. Its synthesised "waiting for status" log
            // line has no home here: this surface renders no log.
            val finished = settled
            if (finished != null && finished.exitCode != 0L) {
                own.update { it.copy(actionError = SystemCopy.GATEWAY_RESTART_FAILED) }
            }
            loadStatus()
        }
    }

    /** Open the updates sheet and check, forcing past the host's six-hour cache. */
    fun openUpdates() {
        own.update { it.copy(sheetOpen = true) }
        checkForUpdates()
    }

    /** Desktop's `Try again`, and what opening the sheet does. */
    fun checkForUpdates() {
        if (own.value.checking) return
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            own.update { it.copy(checking = true) }
            // Desktop always forces (`store/updates.ts:374`): a person who
            // opened this sheet is asking now, not six hours ago.
            when (val result = api.checkUpdate(force = true)) {
                is GatewayRestResult.Success -> {
                    updates.rememberCheck(result.value)
                    own.update { it.copy(checking = false, check = result.value.toCheckState()) }
                }

                is GatewayRestResult.Failed -> own.update {
                    it.copy(
                        checking = false,
                        // Desktop keeps whatever `supported` it already knew and
                        // raises `check-failed` over it (`updates.ts:380-386`);
                        // a check that could not run has not learned that this
                        // host cannot update itself.
                        check = (it.check ?: UNKNOWN_CHECK).copy(failed = true),
                    )
                }
            }
        }
    }

    /** `Update now`. Hands off to the app-scoped engine and stops caring. */
    fun applyUpdate() {
        updates.apply()
    }

    /**
     * `Maybe later`, `Done`, and the sheet's own dismiss.
     *
     * Refused while an apply is in flight, which is Desktop's rule too
     * (`updates-overlay.tsx:85-98,112`): the work continues regardless of the
     * sheet, and a sheet that can be dismissed mid-apply is one a person
     * dismisses and then cannot find again.
     */
    fun closeUpdates() {
        if (uiState.value.applyLocked) return
        own.update { it.copy(sheetOpen = false) }
        // Desktop resets the apply state on the way out of a terminal stage
        // (`updates-overlay.tsx:92-97`), so re-opening the sheet asks the host
        // again rather than re-showing an outcome the person has read and
        // dismissed.
        updates.dismissTerminalState()
    }

    private suspend fun loadStatus() {
        when (val result = api.status()) {
            is GatewayRestResult.Success ->
                own.update { it.copy(status = result.value.sanitized(), statusError = null) }

            is GatewayRestResult.Failed ->
                own.update { it.copy(statusError = result.safeMessage) }
        }
    }

    /** Everything this ViewModel owns, as opposed to what it projects. */
    private data class OwnState(
        val status: GatewayStatusSummary? = null,
        val statusError: String? = null,
        val action: SystemActionState? = null,
        val actionError: String? = null,
        val sheetOpen: Boolean = false,
        val checking: Boolean = false,
        val check: UpdateCheckState? = null,
    )

    companion object {
        /** Desktop's own cadence (`command-center/index.tsx:274-284` @ the pin). */
        internal const val RESTART_POLL_ATTEMPTS = 18
        internal const val RESTART_POLL_INTERVAL_MILLIS = 1_200L

        /**
         * The shape a failed first check leaves behind. `supported` starts true
         * for the same reason Desktop's does: a check that never ran has not
         * discovered anything about this host's install method.
         */
        private val UNKNOWN_CHECK = UpdateCheckState(
            supported = true,
            message = null,
            updateAvailable = false,
            behind = 0,
            changelog = emptyList(),
            moreChanges = 0,
            failed = true,
        )

        internal fun factory(
            api: GatewaySystemApi,
            updates: GatewayUpdateController,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SystemViewModel(api, updates) as T
        }
    }
}

/**
 * The backend's version is a string the Gateway wrote, and it reaches a screen
 * and a screen reader. It is short by every convention and by nothing that
 * enforces one, so it is redacted and bounded like every other backend-authored
 * string here — [MAX_VERSION_CHARS] rather than the log line's cap, because a
 * version that needs a paragraph is not a version.
 */
private fun GatewayStatusSummary.sanitized(): GatewayStatusSummary =
    copy(version = safeBackendLine(version).take(MAX_VERSION_CHARS))

/**
 * The host's own action name, which is one of two fixed strings — bounded
 * anyway, because "the host only ever sends two values" is a claim about a
 * host this app does not run.
 */
private fun safeActionName(raw: String): String = safeBackendLine(raw).take(MAX_ACTION_NAME_CHARS)

private const val MAX_VERSION_CHARS = 64
private const val MAX_ACTION_NAME_CHARS = 64

private fun GatewayActionStatus.phase(): SystemActionPhase = when {
    running -> SystemActionPhase.Running
    exitCode == 0L -> SystemActionPhase.Done
    else -> SystemActionPhase.Failed
}

/**
 * The check as the sheet reads it (`store/updates.ts:351-364` @ the pin).
 *
 * `behind` clamps at zero there, and the changelog is grouped here rather than
 * in the composable so the "+ N more" arithmetic and the list it is measured
 * against are one calculation.
 */
private fun GatewayUpdateCheck.toCheckState(): UpdateCheckState {
    val groups = buildCommitChangelog(commits.map { safeBackendLine(it.summary) })
    val shown = groups.totalItems()
    val clampedBehind = (behind ?: 0L).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return UpdateCheckState(
        supported = canApply,
        message = message?.let(::safeBackendLine),
        updateAvailable = updateAvailable,
        behind = clampedBehind,
        changelog = groups,
        moreChanges = (clampedBehind - shown).coerceAtLeast(0),
        failed = false,
    )
}

/** The stage that has an apply in it, for the sheet's phase decision. */
internal fun GatewayUpdateState.isApplyingPhase(): Boolean =
    applying || stage == GatewayUpdateStage.Restart
