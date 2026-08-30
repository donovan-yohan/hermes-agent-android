package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.gateway.ApprovalPending
import com.hermesagent.mobile.data.gateway.ClarifyPending
import com.hermesagent.mobile.data.gateway.GatewayTurnOutcome
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputKind
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.session.SessionCacheState
import com.hermesagent.mobile.data.session.SessionSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gating rules, on virtual time.
 *
 * Every assertion here is about *whether* a notification fires, never about
 * how it looks — the surface is a recorder. These are the rules that are
 * cheap to get subtly wrong and expensive to debug on a device: a completion
 * alert that fires for every background session, an approval that stays silent
 * because the app happened to be open on another conversation, or a reconnect
 * that re-announces an hour-old prompt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionNotifierTest {

    @Test
    fun `an approval for an off-screen session fires while the app is foregrounded`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(true)
        world.presence.visibleSessionChanged("on-screen")
        world.start()
        world.leaveQuietWindow()

        world.pendingInputs.value = approval("other")
        runCurrent()

        assertEquals(listOf(NotificationKind.Approval to "other"), world.surface.posted())
    }

    @Test
    fun `an approval for the session on screen stays silent while the app is foregrounded`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(true)
        world.presence.visibleSessionChanged("visible")
        world.start()
        world.leaveQuietWindow()

        world.pendingInputs.value = approval("visible")
        runCurrent()

        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())
    }

    @Test
    fun `an approval for the session on screen fires once the app is backgrounded`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.presence.visibleSessionChanged("visible")
        world.start()
        world.leaveQuietWindow()

        world.pendingInputs.value = approval("visible")
        runCurrent()

        assertEquals(listOf(NotificationKind.Approval to "visible"), world.surface.posted())
    }

    @Test
    fun `a question is an attention kind and breaks through the same way`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(true)
        world.presence.visibleSessionChanged("on-screen")
        world.start()
        world.leaveQuietWindow()

        world.pendingInputs.value = clarify("other")
        runCurrent()

        assertEquals(listOf(NotificationKind.Input to "other"), world.surface.posted())
    }

    @Test
    fun `a finished turn needs the app away and the session active, not one or the other`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(true)
        world.presence.visibleSessionChanged("visible")
        world.start()
        world.leaveQuietWindow()

        // Away, but a different conversation is selected: Desktop drops this so
        // a busy gateway cannot raise one alert per background session.
        world.presence.applicationForegroundChanged(false)
        runCurrent()
        world.turns.emit(GatewayTurnOutcome("other", failed = false))
        runCurrent()
        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())

        // Selected, but the user is looking at it.
        world.presence.applicationForegroundChanged(true)
        runCurrent()
        world.turns.emit(GatewayTurnOutcome("visible", failed = false))
        runCurrent()
        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())

        // Away and selected.
        world.presence.applicationForegroundChanged(false)
        runCurrent()
        world.turns.emit(GatewayTurnOutcome("visible", failed = false))
        runCurrent()
        assertEquals(listOf(NotificationKind.TurnDone to "visible"), world.surface.posted())
    }

    @Test
    fun `a failed turn does not claim Hermes finished`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.presence.visibleSessionChanged("visible")
        world.start()
        world.leaveQuietWindow()

        world.turns.emit(GatewayTurnOutcome("visible", failed = true))
        runCurrent()

        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())
    }

    @Test
    fun `a replayed prompt inside the post-connect quiet window is not announced`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()

        world.socketOpens.emit(Unit)
        runCurrent()
        advanceTimeBy(3_999)
        world.pendingInputs.value = approval("parked")
        runCurrent()
        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())

        // One millisecond past the window, a genuinely new prompt is news again.
        advanceTimeBy(2)
        world.pendingInputs.value = approval("parked") + approval("fresh")
        runCurrent()
        assertEquals(listOf(NotificationKind.Approval to "fresh"), world.surface.posted())
    }

    @Test
    fun `every socket open reopens the quiet window`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()
        world.leaveQuietWindow()

        world.socketOpens.emit(Unit)
        runCurrent()
        advanceTimeBy(1_000)
        world.pendingInputs.value = approval("parked")
        runCurrent()

        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())
    }

    @Test
    fun `the same session and kind cannot fire twice inside one second`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.presence.visibleSessionChanged("chat")
        world.start()
        world.leaveQuietWindow()

        world.turns.emit(GatewayTurnOutcome("chat", failed = false))
        runCurrent()
        advanceTimeBy(999)
        world.turns.emit(GatewayTurnOutcome("chat", failed = false))
        runCurrent()
        assertEquals(1, world.surface.posted().size)

        advanceTimeBy(2)
        world.turns.emit(GatewayTurnOutcome("chat", failed = false))
        runCurrent()
        assertEquals(2, world.surface.posted().size)
    }

    @Test
    fun `the throttle map evicts rather than growing`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()
        world.leaveQuietWindow()

        // Distinct sessions, each past the window, so nothing is ever throttled
        // and every entry has had its chance to be pruned.
        repeat(50) { index ->
            world.pendingInputs.value = approval("session-$index")
            runCurrent()
            advanceTimeBy(1_001)
        }

        assertEquals(50, world.surface.posted().size)
    }

    @Test
    fun `a resolved prompt withdraws its notification`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()
        world.leaveQuietWindow()

        world.pendingInputs.value = approval("chat")
        runCurrent()
        world.pendingInputs.value = emptyMap()
        runCurrent()

        assertEquals(listOf(NotificationKind.Approval to "chat"), world.surface.cleared)
    }

    @Test
    fun `losing the connection withdraws what that connection had parked`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()
        world.leaveQuietWindow()

        world.pendingInputs.value = approval("chat")
        runCurrent()
        // The repository empties its pending map on every client change.
        world.pendingInputs.value = emptyMap()
        runCurrent()

        assertEquals(listOf(NotificationKind.Approval to "chat"), world.surface.cleared)
    }

    @Test
    fun `opening a session clears everything grouped under it`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()
        world.leaveQuietWindow()

        world.pendingInputs.value = approval("chat")
        runCurrent()

        world.presence.applicationForegroundChanged(true)
        world.presence.visibleSessionChanged("chat")
        runCurrent()

        assertEquals(listOf("chat"), world.surface.clearedSessions)
    }

    @Test
    fun `a prompt seen on screen is raised again once the user leaves`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(true)
        world.presence.visibleSessionChanged("chat")
        world.start()
        world.leaveQuietWindow()

        // Arrives while the user is looking straight at it: correctly silent.
        world.pendingInputs.value = approval("chat")
        runCurrent()
        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())

        // They walk away and it is still parked. Recording the suppressed
        // notification as though it had been posted is what used to make this
        // unreachable for the rest of the connection.
        world.presence.applicationForegroundChanged(false)
        runCurrent()
        world.pendingInputs.value = approval("chat") + approval("other")
        runCurrent()

        assertTrue(NotificationKind.Approval to "chat" in world.surface.posted())
    }

    @Test
    fun `opening a conversation and leaving it again does not bury a still-parked prompt`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.presence.visibleSessionChanged("chat")
        world.start()
        world.leaveQuietWindow()

        world.pendingInputs.value = approval("chat")
        runCurrent()
        assertEquals(1, world.surface.posts.size)

        // Opened: the notification is withdrawn.
        world.presence.applicationForegroundChanged(true)
        runCurrent()
        assertEquals(listOf("chat"), world.surface.clearedSessions)

        // Backgrounded again, prompt still blocking the agent.
        world.presence.applicationForegroundChanged(false)
        runCurrent()
        advanceTimeBy(1_001)
        world.pendingInputs.value = approval("chat") + approval("other")
        runCurrent()

        assertTrue(NotificationKind.Approval to "chat" in world.surface.posted())
    }

    @Test
    fun `a superseding request repoints the buttons even inside the throttle window`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()
        world.leaveQuietWindow()

        world.pendingInputs.value = approval("chat", requestId = "first")
        runCurrent()
        // Well inside the one-second throttle: the Gateway replaced the
        // request, and a button still pointing at "first" would answer nothing.
        advanceTimeBy(100)
        world.pendingInputs.value = approval("chat", requestId = "second")
        runCurrent()

        val posts = world.surface.posts
        assertEquals(2, posts.size)
        assertEquals("first", posts[0].approval?.key?.requestId)
        assertEquals("second", posts[1].approval?.key?.requestId)
    }

    @Test
    fun `a replayed prompt stays old news for the rest of the connection`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()

        world.socketOpens.emit(Unit)
        runCurrent()
        world.pendingInputs.value = approval("parked")
        runCurrent()
        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())

        // The window passes and an unrelated prompt arrives. The replayed one
        // is still not something that just happened.
        advanceTimeBy(5_000)
        world.pendingInputs.value = approval("parked") + approval("fresh")
        runCurrent()

        assertEquals(listOf(NotificationKind.Approval to "fresh"), world.surface.posted())
    }

    @Test
    fun `a new socket makes its own replay old news, not the previous one's`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()
        world.leaveQuietWindow()

        // Parked and announced on this connection.
        world.pendingInputs.value = approval("chat")
        runCurrent()
        assertEquals(1, world.surface.posts.size)

        // Reconnect: the repository empties its map, the socket reopens, and
        // the replay that follows is suppressed.
        world.pendingInputs.value = emptyMap()
        runCurrent()
        world.socketOpens.emit(Unit)
        runCurrent()
        world.pendingInputs.value = approval("chat")
        runCurrent()
        assertEquals(1, world.surface.posts.size)

        // Past the window, a genuinely new prompt on that same session fires.
        advanceTimeBy(5_000)
        world.pendingInputs.value = approval("chat") + clarify("chat")
        runCurrent()

        assertTrue(NotificationKind.Input to "chat" in world.surface.posted())
    }

    @Test
    fun `a superseding request repoints the same notification instead of stacking a second`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()
        world.leaveQuietWindow()

        world.pendingInputs.value = approval("chat", requestId = "first")
        runCurrent()
        advanceTimeBy(1_001)
        world.pendingInputs.value = approval("chat", requestId = "second")
        runCurrent()

        val posts = world.surface.posts
        assertEquals(2, posts.size)
        assertEquals("first", posts[0].approval?.key?.requestId)
        assertEquals("second", posts[1].approval?.key?.requestId)
        assertTrue(world.surface.cleared.isEmpty())
    }

    @Test
    fun `a notification carries the session title and never the request itself`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.sessions.value = SessionCacheState(
            sessions = mapOf("chat" to summary("chat", "Refactor the parser")),
        )
        world.start()
        world.leaveQuietWindow()

        world.pendingInputs.value = approval("chat", command = "rm -rf /srv//data")
        runCurrent()

        val post = world.surface.posts.single()
        assertEquals(NotificationCopy.APPROVAL_TITLE, post.title)
        assertEquals("Refactor the parser", post.body)
    }

    @Test
    fun `an untitled session still says what is waiting`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()
        world.leaveQuietWindow()

        world.pendingInputs.value = clarify("chat")
        runCurrent()

        assertEquals(NotificationCopy.INPUT_BODY, world.surface.posts.single().body)
    }

    @Test
    fun `the master switch and the per-kind switch each silence a kind on their own`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.presence.visibleSessionChanged("chat")
        world.settings.value = NotificationSettings(enabled = false)
        world.start()
        world.leaveQuietWindow()

        world.pendingInputs.value = approval("chat")
        runCurrent()
        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())

        world.settings.value = NotificationSettings(
            enabled = true,
            kinds = NotificationKind.entries.associateWith { it != NotificationKind.Approval },
        )
        // Settings arrive on the same merged stream as everything else, so the
        // change is landed before the prompt that it is supposed to silence.
        runCurrent()
        world.pendingInputs.value = emptyMap()
        runCurrent()
        world.pendingInputs.value = approval("chat")
        runCurrent()
        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())

        world.settings.value = NotificationSettings()
        runCurrent()
        world.pendingInputs.value = emptyMap()
        runCurrent()
        advanceTimeBy(1_001)
        world.pendingInputs.value = approval("chat")
        runCurrent()
        assertEquals(listOf(NotificationKind.Approval to "chat"), world.surface.posted())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class World(private val test: kotlinx.coroutines.test.TestScope) {
    val pendingInputs = MutableStateFlow<Map<PendingInputKey, PendingInputRequest>>(emptyMap())
    val turns = MutableSharedFlow<GatewayTurnOutcome>(extraBufferCapacity = 16)
    val socketOpens = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val sessions = MutableStateFlow(SessionCacheState())
    val settings = MutableStateFlow(NotificationSettings())
    val presence = NotificationPresence()
    val surface = RecordingNotificationSurface()

    fun start() {
        SessionNotifier(
            pendingInputs = pendingInputs,
            turnOutcomes = turns,
            sessions = sessions,
            socketOpens = socketOpens,
            presence = presence,
            settingsFlow = settings,
            surface = surface,
            // Virtual time, so a four-second window costs nothing to test.
            clock = { test.testScheduler.currentTime },
        ).start(test.backgroundScope)
        test.testScheduler.runCurrent()
    }

    /** Past the quiet window that construction itself opens. */
    fun leaveQuietWindow() {
        test.testScheduler.advanceTimeBy(4_001)
        test.testScheduler.runCurrent()
    }
}

private fun summary(id: String, title: String) = SessionSummary(
    id = id,
    title = title,
    preview = "",
    lastActiveAtMillis = 0L,
)

private fun approval(
    durableSessionId: String,
    requestId: String = "req-$durableSessionId",
    command: String = "",
): Map<PendingInputKey, PendingInputRequest> {
    val key = PendingInputKey(1L, "runtime-$durableSessionId", requestId, PendingInputKind.Approval)
    return mapOf(
        key to ApprovalPending(
            key = key,
            durableSessionId = durableSessionId,
            runtimeSessionId = key.runtimeSessionId,
            command = command,
            description = "",
            choices = listOf("once", "deny"),
        ),
    )
}

private fun clarify(durableSessionId: String): Map<PendingInputKey, PendingInputRequest> {
    val key = PendingInputKey(1L, "runtime-$durableSessionId", "req-$durableSessionId", PendingInputKind.Clarify)
    return mapOf(
        key to ClarifyPending(
            key = key,
            durableSessionId = durableSessionId,
            runtimeSessionId = key.runtimeSessionId,
            question = "Which branch?",
        ),
    )
}
