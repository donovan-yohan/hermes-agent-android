package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.notifications.GatewayActivity
import com.hermesagent.mobile.data.notifications.GatewayActivityCounts
import com.hermesagent.mobile.data.notifications.counts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface TurnProtectionServiceHost {
    fun startService(counts: GatewayActivityCounts): Boolean
    fun updateService(counts: GatewayActivityCounts) = Unit
    fun stopService()
    fun onServiceRefused(callback: () -> Unit) = Unit
}

/**
 * Manages the lifecycle of the turn-scoped foreground service.
 *
 * Runs only while there is activity worth protecting, keeping the process
 * unfrozen and the gateway socket alive across backgrounding.
 *
 * Initiates service start only while the app is in the foreground (Android 14+
 * dataSync FGS start rule), and stops after a short linger grace when all active
 * work completes to avoid start/stop flapping across back-to-back turns.
 * Stops immediately on deliberate connection closure (endpoint switch, sign-out)
 * or unrecoverable connection failure.
 */
internal class TurnProtectionController(
    private val activity: StateFlow<GatewayActivity>,
    private val pendingInputs: StateFlow<Map<PendingInputKey, PendingInputRequest>>,
    private val connectionState: StateFlow<GatewayConnectionState>,
    private val appForegrounded: StateFlow<Boolean>,
    private val serviceHost: TurnProtectionServiceHost,
    private val onProtectionActiveChanged: (Boolean) -> Unit = {},
    private val lingerGraceMillis: Long = DEFAULT_LINGER_GRACE_MILLIS,
    private val maxHoldMillis: Long = DEFAULT_MAX_HOLD_MILLIS,
    private val needsAttentionGraceMillis: Long = DEFAULT_NEEDS_ATTENTION_GRACE_MILLIS,
) {
    private val mutex = Mutex()
    private var isServiceActive = false
    private var lingerJob: Job? = null
    private var holdCeilingJob: Job? = null
    private var needsAttentionGraceJob: Job? = null
    private var lastServiceCounts: GatewayActivityCounts? = null

    fun start(scope: CoroutineScope): Job {
        armRefusalReporting(scope)
        return scope.launch {
            combine(
                activity,
                pendingInputs,
                connectionState,
                appForegrounded,
            ) { currentActivity, currentPending, currentConnection, isForeground ->
                StateSnapshot(
                    activity = currentActivity,
                    hasActiveWork = hasActiveWork(currentActivity, currentPending),
                    connectionStatus = currentConnection.status,
                    isForeground = isForeground,
                )
            }.distinctUntilChanged().collect { state ->
                mutex.withLock {
                    evaluateLocked(state, scope)
                }
            }
        }
    }

    private fun evaluateLocked(state: StateSnapshot, scope: CoroutineScope) {
        if (state.connectionStatus == GatewayConnectionStatus.Disconnected) {
            // Deliberate disconnect / endpoint switch / unrecoverable connection failure -> immediate stop
            cancelLingerLocked()
            cancelNeedsAttentionGraceLocked()
            if (isServiceActive) {
                stopProtectionLocked()
            }
            return
        }

        if (isServiceActive && lastServiceCounts != state.activity.counts) {
            serviceHost.updateService(state.activity.counts)
            lastServiceCounts = state.activity.counts
        }

        if (state.hasActiveWork) {
            // Active work in progress: cancel any pending stop from lingering
            cancelLingerLocked()
            if (!isServiceActive && state.isForeground) {
                startProtectionLocked(scope)
            }
        } else {
            // No active work
            if (isServiceActive && lingerJob == null) {
                val job = scope.launch {
                    if (lingerGraceMillis > 0) {
                        delay(lingerGraceMillis)
                    }
                    mutex.withLock {
                        if (coroutineContext[Job] === lingerJob) {
                            lingerJob = null
                            if (isServiceActive) {
                                stopProtectionLocked()
                            }
                        }
                    }
                }
                lingerJob = job
            }
        }

        if (state.connectionStatus == GatewayConnectionStatus.NeedsAttention) {
            // NeedsAttention is still-retrying/waiting for network: hold protection for a bounded grace
            if (isServiceActive && needsAttentionGraceJob == null) {
                val job = scope.launch {
                    if (needsAttentionGraceMillis > 0) {
                        delay(needsAttentionGraceMillis)
                    }
                    mutex.withLock {
                        if (coroutineContext[Job] === needsAttentionGraceJob) {
                            needsAttentionGraceJob = null
                            if (isServiceActive) {
                                stopProtectionLocked()
                            }
                        }
                    }
                }
                needsAttentionGraceJob = job
            }
        } else {
            // Connected or Connecting: cancel any non-connected grace timer
            cancelNeedsAttentionGraceLocked()
        }
    }

    /**
     * The refusal seam takes a single registrant, so re-arming on every protection
     * start keeps a refused start reachable even if a host drops its registration
     * when the service stops. Registration is idempotent.
     */
    private fun armRefusalReporting(scope: CoroutineScope) {
        serviceHost.onServiceRefused {
            scope.launch {
                mutex.withLock {
                    if (isServiceActive) {
                        isServiceActive = false
                        lastServiceCounts = null
                        cancelLingerLocked()
                        cancelHoldCeilingLocked()
                        cancelNeedsAttentionGraceLocked()
                        onProtectionActiveChanged(false)
                    }
                }
            }
        }
    }

    private fun startProtectionLocked(scope: CoroutineScope) {
        armRefusalReporting(scope)
        val counts = activity.value.counts
        val started = serviceHost.startService(counts)
        if (started) {
            isServiceActive = true
            lastServiceCounts = counts
            onProtectionActiveChanged(true)
            cancelHoldCeilingLocked()
            if (maxHoldMillis > 0) {
                // Each foreground start intentionally grants a fresh ceiling.
                val job = scope.launch {
                    delay(maxHoldMillis)
                    mutex.withLock {
                        if (coroutineContext[Job] === holdCeilingJob) {
                            holdCeilingJob = null
                            if (isServiceActive) {
                                stopProtectionLocked()
                            }
                        }
                    }
                }
                holdCeilingJob = job
            }
        } else {
            isServiceActive = false
            lastServiceCounts = null
            onProtectionActiveChanged(false)
        }
    }

    private fun stopProtectionLocked() {
        isServiceActive = false
        lastServiceCounts = null
        cancelHoldCeilingLocked()
        cancelLingerLocked()
        cancelNeedsAttentionGraceLocked()
        serviceHost.stopService()
        onProtectionActiveChanged(false)
    }

    private fun cancelLingerLocked() {
        val job = lingerJob
        lingerJob = null
        job?.cancel()
    }

    private fun cancelHoldCeilingLocked() {
        val job = holdCeilingJob
        holdCeilingJob = null
        job?.cancel()
    }

    private fun cancelNeedsAttentionGraceLocked() {
        val job = needsAttentionGraceJob
        needsAttentionGraceJob = null
        job?.cancel()
    }

    /**
     * The activity projection already unions Gateway-reported work with this
     * client's turns and pending requests. Its local union fails open while a
     * just-started turn has not reached the Gateway registry, and lets another
     * client's live work hold this connection open.
     */
    private fun hasActiveWork(
        currentActivity: GatewayActivity,
        pending: Map<PendingInputKey, PendingInputRequest>,
    ): Boolean = pending.isNotEmpty() || currentActivity.children.isNotEmpty()

    private data class StateSnapshot(
        val activity: GatewayActivity,
        val hasActiveWork: Boolean,
        val connectionStatus: GatewayConnectionStatus,
        val isForeground: Boolean,
    )

    companion object {
        const val DEFAULT_LINGER_GRACE_MILLIS = 5_000L
        const val DEFAULT_MAX_HOLD_MILLIS = 30 * 60 * 1000L
        const val DEFAULT_NEEDS_ATTENTION_GRACE_MILLIS = 3 * 60 * 1000L
    }
}
