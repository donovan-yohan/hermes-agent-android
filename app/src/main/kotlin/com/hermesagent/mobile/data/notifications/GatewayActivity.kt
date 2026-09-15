package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.gateway.LiveSession
import com.hermesagent.mobile.data.gateway.LiveSessionSnapshot
import com.hermesagent.mobile.data.gateway.LiveSessionStatus
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.session.ProjectCatalogState
import com.hermesagent.mobile.data.session.SessionCacheState
import com.hermesagent.mobile.data.session.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/** One passive child of the activity group. Text is authoritative source text, unredacted. */
data class GatewayActivityChild(
    val durableSessionId: String,
    val title: String,
    val preview: String,
    val status: LiveSessionStatus,
    val projectLabel: String?,
    val lastActiveAtMillis: Long,
)

/** The whole passive projection for the one active connection. */
data class GatewayActivity(val children: List<GatewayActivityChild>) {
    val waitingCount: Int get() = children.count { it.status == LiveSessionStatus.Waiting }
    val workingCount: Int get() = children.size - waitingCount

    companion object { val Empty = GatewayActivity(emptyList()) }
}

/** Counts the foreground service's summary line renders. */
data class GatewayActivityCounts(val chats: Int, val waiting: Int)

val GatewayActivity.counts: GatewayActivityCounts
    get() = GatewayActivityCounts(chats = children.size, waiting = waitingCount)

/**
 * The projection, and nothing else: no Android, no timers, no state of its own.
 * Deterministic given its inputs.
 *
 * A parked prompt wins over the registry's own status for the same chat: the
 * prompt is held here and now, while the registry answer can be a backstop
 * interval old. Everything else is first-writer-wins in source order — the
 * Gateway's report, then this app's parked prompts, then its own live turns —
 * and a chat that leaves all three leaves the group.
 */
internal fun gatewayActivityChildren(
    snapshot: LiveSessionSnapshot,
    sessions: Map<String, SessionSummary>,
    projects: ProjectCatalogState,
    pendingInputs: Map<PendingInputKey, PendingInputRequest>,
    activeTurns: Set<String>,
): List<GatewayActivityChild> {
    val candidates = linkedMapOf<String, LiveSession>()
    if (snapshot is LiveSessionSnapshot.Reported) {
        snapshot.sessions.forEach { row -> candidates.putIfAbsent(row.durableSessionId, row) }
    }
    pendingInputs.values.forEach { pending ->
        // A prompt this app is holding is parked work whatever the last registry
        // poll said. The report can be a backstop interval old, and a chat whose
        // question nobody has answered is the one child that must not read as
        // "working" — the report's own `waiting` is the same fact arriving later.
        val reported = candidates[pending.durableSessionId]
        candidates[pending.durableSessionId] = when {
            reported == null -> cachedLiveSession(pending.durableSessionId, sessions, LiveSessionStatus.Waiting)
            reported.status == LiveSessionStatus.Waiting -> reported
            else -> reported.copy(status = LiveSessionStatus.Waiting)
        }
    }
    activeTurns.forEach { durableSessionId ->
        candidates.putIfAbsent(
            durableSessionId,
            cachedLiveSession(durableSessionId, sessions, LiveSessionStatus.Working),
        )
    }
    return candidates.values.map { candidate ->
        val cached = sessions[candidate.durableSessionId]
        GatewayActivityChild(
            durableSessionId = candidate.durableSessionId,
            title = candidate.title.ifEmpty { cached?.title.orEmpty() },
            preview = candidate.preview.ifEmpty { cached?.preview.orEmpty() },
            status = candidate.status,
            projectLabel = projectLabelFor(candidate.durableSessionId, projects),
            lastActiveAtMillis = candidate.lastActiveAtMillis,
        )
    }.sortedWith(
        compareByDescending<GatewayActivityChild> { it.lastActiveAtMillis }
            .thenBy(GatewayActivityChild::durableSessionId),
    )
}

private fun cachedLiveSession(
    durableSessionId: String,
    sessions: Map<String, SessionSummary>,
    status: LiveSessionStatus,
): LiveSession {
    val cached = sessions[durableSessionId]
    return LiveSession(
        durableSessionId = durableSessionId,
        title = cached?.title.orEmpty(),
        preview = cached?.preview.orEmpty(),
        status = status,
        lastActiveAtMillis = cached?.lastActiveAtMillis ?: 0L,
    )
}

private fun projectLabelFor(durableSessionId: String, projects: ProjectCatalogState): String? =
    projects.memberships.keys.asSequence()
        .filter { durableSessionId in projects.memberships[it].orEmpty() }
        .sorted()
        .mapNotNull { projectId ->
            projects.projects[projectId]
                ?.takeUnless { it.isHome }
        }
        .firstOrNull()
        ?.label
        ?.takeIf(String::isNotBlank)

/**
 * Follows the Gateway's live-session registry for one connection.
 *
 * Android-free and virtual-time testable. Fetches on every connection edge, on every
 * change hint (one refresh in flight, re-run once on the trailing edge), and on a backstop
 * interval. A disconnect publishes [LiveSessionSnapshot.Unavailable] immediately: the group
 * must clear on a dropped socket and on an endpoint switch, not linger.
 */
internal class GatewayActivityProjection(
    private val report: suspend () -> LiveSessionSnapshot,
    private val connected: StateFlow<Boolean>,
    /** `sessions.changed` ticks. */
    private val hints: Flow<Unit>,
    private val sessions: StateFlow<SessionCacheState>,
    private val pendingInputs: StateFlow<Map<PendingInputKey, PendingInputRequest>>,
    private val activeTurns: StateFlow<Set<String>>,
    private val pollIntervalMillis: Long = 30_000L,
) {
    private val snapshot = MutableStateFlow<LiveSessionSnapshot>(LiveSessionSnapshot.Unavailable)
    private val mutableActivity = MutableStateFlow(GatewayActivity.Empty)
    val activity: StateFlow<GatewayActivity> = mutableActivity.asStateFlow()

    fun start(scope: CoroutineScope): Job = scope.launch {
        coroutineScope {
            val refreshLock = Any()
            var refreshPending = false
            var refreshRunning = false
            var connectionEpoch = 0L

            fun requestRefresh() {
                val launchRunner = synchronized(refreshLock) {
                    if (!connected.value) {
                        false
                    } else {
                        refreshPending = true
                        if (refreshRunning) false else {
                            refreshRunning = true
                            true
                        }
                    }
                }
                if (!launchRunner) return
                launch {
                    while (true) {
                        val epoch = synchronized(refreshLock) {
                            if (!refreshPending) {
                                refreshRunning = false
                                return@launch
                            }
                            refreshPending = false
                            connectionEpoch
                        }
                        if (!connected.value) continue
                        val next = try {
                            report()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            LiveSessionSnapshot.Unavailable
                        }
                        synchronized(refreshLock) {
                            if (epoch == connectionEpoch && connected.value) snapshot.value = next
                        }
                    }
                }
            }

            launch {
                (connected as Flow<Boolean>).distinctUntilChanged().collect { isConnected ->
                    synchronized(refreshLock) {
                        connectionEpoch++
                        if (!isConnected) {
                            refreshPending = false
                            snapshot.value = LiveSessionSnapshot.Unavailable
                        }
                    }
                    if (isConnected) requestRefresh()
                }
            }
            launch { hints.collect { requestRefresh() } }
            launch {
                while (true) {
                    delay(pollIntervalMillis)
                    if (connected.value) requestRefresh()
                }
            }
            launch {
                combine(snapshot, sessions, pendingInputs, activeTurns) { live, cache, pending, turns ->
                    GatewayActivity(
                        gatewayActivityChildren(live, cache.sessions, cache.projects, pending, turns),
                    )
                }.collect { next ->
                    if (mutableActivity.value != next) mutableActivity.value = next
                }
            }
            awaitCancellation()
        }
    }
}
