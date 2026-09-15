package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.gateway.LiveSession
import com.hermesagent.mobile.data.gateway.LiveSessionSnapshot
import com.hermesagent.mobile.data.gateway.LiveSessionStatus
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputKind
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.gateway.SudoPending
import com.hermesagent.mobile.data.session.ProjectCatalogState
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.session.SessionCacheState
import com.hermesagent.mobile.data.session.SessionSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayActivityTest {

    @Test
    fun `projection unions report pending and local turns with deterministic authority`() {
        val children = gatewayActivityChildren(
            snapshot = LiveSessionSnapshot.Reported(
                listOf(live("reported", LiveSessionStatus.Working, 2), live("both", LiveSessionStatus.Working, 1)),
            ),
            sessions = mapOf("pending" to summary("pending", "Pending", 4), "local" to summary("local", "Local", 3)),
            projects = ProjectCatalogState(),
            pendingInputs = pending("both") + pending("pending"),
            activeTurns = setOf("both", "local"),
        )

        assertEquals(listOf("pending", "local", "reported", "both"), children.map(GatewayActivityChild::durableSessionId))
        // A parked prompt wins over the registry's own status for that chat.
        assertEquals(LiveSessionStatus.Waiting, children.single { it.durableSessionId == "both" }.status)
        assertEquals(LiveSessionStatus.Waiting, children.single { it.durableSessionId == "pending" }.status)
        assertEquals(LiveSessionStatus.Working, children.single { it.durableSessionId == "local" }.status)
    }

    @Test
    fun `projection falls back to local knowledge and drops finished reported rows`() {
        val localOnly = gatewayActivityChildren(
            LiveSessionSnapshot.Unavailable,
            mapOf("local" to summary("local", "Local", 0)),
            ProjectCatalogState(),
            emptyMap(),
            setOf("local"),
        )
        val finished = gatewayActivityChildren(
            LiveSessionSnapshot.Reported(emptyList()), emptyMap(), ProjectCatalogState(), emptyMap(), emptySet(),
        )

        assertEquals(listOf("local"), localOnly.map(GatewayActivityChild::durableSessionId))
        assertTrue(finished.isEmpty())
    }

    @Test
    fun `projection chooses smallest existing non-home project even when its label is blank`() {
        val projects = ProjectCatalogState(
            projects = mapOf(
                "a" to ProjectSummary("a", "", null),
                "b" to ProjectSummary("b", "Larger", null),
                "home" to ProjectSummary("home", "Home", null, isHome = true),
            ),
            memberships = mapOf("a" to listOf("s"), "b" to listOf("s"), "home" to listOf("s")),
        )
        val child = gatewayActivityChildren(
            LiveSessionSnapshot.Reported(listOf(live("s", LiveSessionStatus.Working, 0))),
            emptyMap(), projects, emptyMap(), emptySet(),
        ).single()

        assertEquals(null, child.projectLabel)

        val labelled = gatewayActivityChildren(
            LiveSessionSnapshot.Reported(listOf(live("s", LiveSessionStatus.Working, 0))),
            emptyMap(),
            projects.copy(projects = projects.projects + ("a" to ProjectSummary("a", "Smallest", null))),
            emptyMap(), emptySet(),
        ).single()
        val homeOnly = gatewayActivityChildren(
            LiveSessionSnapshot.Reported(listOf(live("s", LiveSessionStatus.Working, 0))),
            emptyMap(),
            ProjectCatalogState(projects = mapOf("home" to ProjectSummary("home", "Home", null, isHome = true)), memberships = mapOf("home" to listOf("s"))),
            emptyMap(), emptySet(),
        ).single()
        assertEquals("Smallest", labelled.projectLabel)
        assertEquals(null, homeOnly.projectLabel)
    }

    @Test
    fun `projection sorts equal timestamps by durable id and pending beats local turn`() {
        val children = gatewayActivityChildren(
            LiveSessionSnapshot.Unavailable,
            emptyMap(),
            ProjectCatalogState(),
            pending("same"),
            setOf("same", "b", "a"),
        )

        assertEquals(listOf("a", "b", "same"), children.map(GatewayActivityChild::durableSessionId))
        assertEquals(LiveSessionStatus.Waiting, children.last().status)
    }

    @Test
    fun `follower rejects an in-flight stale report across disconnect and reconnect`() = runTest {
        val connected = MutableStateFlow(false)
        val hints = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val sessions = MutableStateFlow(SessionCacheState(sessions = mapOf("local" to summary("local", "Local", 0))))
        val pending = MutableStateFlow<Map<PendingInputKey, PendingInputRequest>>(emptyMap())
        val turns = MutableStateFlow(setOf("local"))
        var calls = 0
        val delayed = CompletableDeferred<LiveSessionSnapshot>()
        val projection = GatewayActivityProjection(
            report = {
                calls++
                when (calls) {
                    1 -> LiveSessionSnapshot.Reported(listOf(live("remote", LiveSessionStatus.Working, 2)))
                    2 -> delayed.await()
                    else -> LiveSessionSnapshot.Reported(listOf(live("fresh", LiveSessionStatus.Working, 2)))
                }
            },
            connected = connected,
            hints = hints,
            sessions = sessions,
            pendingInputs = pending,
            activeTurns = turns,
            pollIntervalMillis = 100,
        )
        projection.start(backgroundScope)
        runCurrent()
        assertEquals(0, calls)
        assertEquals(listOf("local"), projection.activity.value.children.map(GatewayActivityChild::durableSessionId))

        connected.value = true
        runCurrent()
        assertEquals(1, calls)
        assertEquals(listOf("remote", "local"), projection.activity.value.children.map(GatewayActivityChild::durableSessionId))
        hints.tryEmit(Unit)
        runCurrent()
        assertEquals(2, calls)
        connected.value = false
        runCurrent()
        assertEquals(listOf("local"), projection.activity.value.children.map(GatewayActivityChild::durableSessionId))
        connected.value = true
        runCurrent()
        assertEquals(2, calls)
        delayed.complete(LiveSessionSnapshot.Reported(listOf(live("stale", LiveSessionStatus.Working, 9))))
        runCurrent()
        assertEquals(3, calls)
        assertEquals(listOf("fresh", "local"), projection.activity.value.children.map(GatewayActivityChild::durableSessionId))
    }

    @Test
    fun `follower coalesces hints and retains local work after an unavailable answer`() = runTest {
        val connected = MutableStateFlow(false)
        val hints = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val sessions = MutableStateFlow(SessionCacheState(sessions = mapOf("local" to summary("local", "Local", 0))))
        val pending = MutableStateFlow<Map<PendingInputKey, PendingInputRequest>>(emptyMap())
        val turns = MutableStateFlow(setOf("local"))
        var calls = 0
        var running = 0
        var maxRunning = 0
        val heldHint = CompletableDeferred<LiveSessionSnapshot>()
        val projection = GatewayActivityProjection(
            report = {
                calls++
                running++
                maxRunning = maxOf(maxRunning, running)
                try {
                    when (calls) {
                        1 -> LiveSessionSnapshot.Reported(listOf(live("remote", LiveSessionStatus.Working, 2)))
                        2 -> heldHint.await()
                        else -> LiveSessionSnapshot.Unavailable
                    }
                } finally {
                    running--
                }
            },
            connected = connected,
            hints = hints,
            sessions = sessions,
            pendingInputs = pending,
            activeTurns = turns,
            pollIntervalMillis = 100,
        )
        projection.start(backgroundScope)
        runCurrent()
        hints.tryEmit(Unit)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(0, calls)

        connected.value = true
        runCurrent()
        assertEquals(1, calls)
        hints.tryEmit(Unit)
        runCurrent()
        assertEquals(2, calls)
        hints.tryEmit(Unit)
        hints.tryEmit(Unit)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(1, maxRunning)
        heldHint.complete(LiveSessionSnapshot.Reported(listOf(live("hint", LiveSessionStatus.Working, 3))))
        runCurrent()
        assertEquals(3, calls)
        assertEquals(1, maxRunning)
        assertEquals(listOf("local"), projection.activity.value.children.map(GatewayActivityChild::durableSessionId))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(4, calls)
    }

    private fun live(id: String, status: LiveSessionStatus, at: Long) = LiveSession(id, "", "", status, at)
    private fun summary(id: String, title: String, at: Long) = SessionSummary(id, title, "", at)

    private fun pending(id: String): Map<PendingInputKey, PendingInputRequest> {
        val key = PendingInputKey(1, "runtime-$id", "request-$id", PendingInputKind.Sudo)
        return mapOf(key to SudoPending(key, id, key.runtimeSessionId))
    }
}
