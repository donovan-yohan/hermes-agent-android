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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
    fun `a finished turn notifies for any session when backgrounded and stays silent while foregrounded`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(true)
        world.presence.visibleSessionChanged("visible")
        world.start()
        world.leaveQuietWindow()

        // Away: finishing turn for any session notifies.
        world.presence.applicationForegroundChanged(false)
        runCurrent()
        world.turns.emit(GatewayTurnOutcome("other", failed = false))
        runCurrent()
        assertEquals(listOf(NotificationKind.TurnDone to "other"), world.surface.posted())

        // Selected, but the user is looking at it.
        world.presence.applicationForegroundChanged(true)
        runCurrent()
        world.turns.emit(GatewayTurnOutcome("visible", failed = false))
        runCurrent()
        assertEquals(listOf(NotificationKind.TurnDone to "other"), world.surface.posted())

        // Away and selected.
        world.presence.applicationForegroundChanged(false)
        runCurrent()
        world.turns.emit(GatewayTurnOutcome("visible", failed = false))
        runCurrent()
        assertEquals(
            listOf(
                NotificationKind.TurnDone to "other",
                NotificationKind.TurnDone to "visible",
            ),
            world.surface.posted(),
        )
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
    fun `a replayed prompt inside the post-connect quiet window is suppressed immediately and deferred until expiry`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()

        world.socketOpens.emit(Unit)
        runCurrent()
        advanceTimeBy(3_999)
        world.pendingInputs.value = approval("parked")
        runCurrent()
        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())

        // The quiet window timer expires at 4,000ms, firing the deferred prompt without a new emission.
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(NotificationKind.Approval to "parked"), world.surface.posted())

        // A fresh prompt arriving after the window fires immediately.
        world.pendingInputs.value = approval("parked") + approval("fresh")
        runCurrent()
        assertEquals(
            listOf(
                NotificationKind.Approval to "parked",
                NotificationKind.Approval to "fresh",
            ),
            world.surface.posted(),
        )
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
    fun `a replayed prompt already notified pre-disconnect is not re-announced on reconnect`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()
        world.leaveQuietWindow()

        // Parked and announced on this connection.
        world.pendingInputs.value = approval("parked")
        runCurrent()
        assertEquals(listOf(NotificationKind.Approval to "parked"), world.surface.posted())

        // Reconnect: replayed prompt was already notified pre-disconnect, so it is not re-announced.
        world.pendingInputs.value = emptyMap()
        runCurrent()
        world.socketOpens.emit(Unit)
        runCurrent()
        world.pendingInputs.value = approval("parked")
        runCurrent()
        assertEquals(1, world.surface.posts.size)

        // The window passes and an unrelated prompt arrives. The replayed one
        // does not re-notify, but the fresh one does.
        advanceTimeBy(5_000)
        world.pendingInputs.value = approval("parked") + approval("fresh")
        runCurrent()

        assertEquals(
            listOf(
                NotificationKind.Approval to "parked",
                NotificationKind.Approval to "fresh",
            ),
            world.surface.posted(),
        )
    }

    @Test
    fun `superseding a prompt that was replayed into the quiet window still notifies`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()

        world.socketOpens.emit(Unit)
        runCurrent()
        // Parked before this socket existed, replayed on connect: deferred during quiet window.
        world.pendingInputs.value = approval("chat", requestId = "replayed")
        runCurrent()
        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())

        // The quiet window passes (replayed fires) and the Gateway replaces it with a new request.
        advanceTimeBy(5_000)
        world.pendingInputs.value = approval("chat", requestId = "replacement")
        runCurrent()

        val posts = world.surface.posts
        assertEquals(2, posts.size)
        assertEquals("replayed", posts[0].approval?.key?.requestId)
        assertEquals("replacement", posts[1].approval?.key?.requestId)
    }

    @Test
    fun `replayed prompts from a previous connection stay deduplicated after the new quiet window expires`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()
        world.leaveQuietWindow()

        // Parked and announced on this connection.
        world.pendingInputs.value = approval("chat")
        runCurrent()
        assertEquals(1, world.surface.posts.size)

        // Reconnect: the repository empties its map, the socket reopens, and
        // the replay that follows is deduplicated.
        world.pendingInputs.value = emptyMap()
        runCurrent()
        world.socketOpens.emit(Unit)
        runCurrent()
        world.pendingInputs.value = approval("chat")
        runCurrent()
        assertEquals(1, world.surface.posts.size)

        // Past the window, the replayed prompt does not re-fire, but a genuinely new prompt on that session fires.
        advanceTimeBy(5_000)
        world.pendingInputs.value = approval("chat") + clarify("chat")
        runCurrent()

        assertEquals(
            listOf(
                NotificationKind.Approval to "chat",
                NotificationKind.Input to "chat",
            ),
            world.surface.posted(),
        )
        assertEquals(2, world.surface.posts.size)
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

    @Test
    fun `pending input arrives during quiet window, still outstanding at expiry notifies exactly once at expiry`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()

        world.socketOpens.emit(Unit)
        runCurrent()
        advanceTimeBy(1_000)
        world.pendingInputs.value = approval("pending-1")
        runCurrent()
        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())

        // Quiet window expires at 4,000ms from socket open (3,001ms more).
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(listOf(NotificationKind.Approval to "pending-1"), world.surface.posted())

        // Advancing further does not cause duplicate firing.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(NotificationKind.Approval to "pending-1"), world.surface.posted())
    }

    @Test
    fun `pending input arrives during quiet window but is resolved or cleared before expiry never notifies`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()

        world.socketOpens.emit(Unit)
        runCurrent()
        advanceTimeBy(1_000)
        world.pendingInputs.value = approval("transient")
        runCurrent()
        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())

        // Resolved before quiet window closes.
        advanceTimeBy(1_000)
        world.pendingInputs.value = emptyMap()
        runCurrent()

        // Quiet window expires.
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())
    }

    @Test
    fun `item notified before disconnect, replayed after reconnect is not re-notified`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()
        world.leaveQuietWindow()

        // Notified before disconnect.
        world.pendingInputs.value = approval("durable-1")
        runCurrent()
        assertEquals(listOf(NotificationKind.Approval to "durable-1"), world.surface.posted())

        // Disconnect and reconnect.
        world.pendingInputs.value = emptyMap()
        runCurrent()
        world.socketOpens.emit(Unit)
        runCurrent()

        // Replayed during quiet window.
        world.pendingInputs.value = approval("durable-1")
        runCurrent()

        // Quiet window expires.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, world.surface.posts.size)
    }

    @Test
    fun `backgrounded with visible null allows TurnDone for a finishing session to notify`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.presence.visibleSessionChanged(null)
        world.start()
        world.leaveQuietWindow()

        world.turns.emit(GatewayTurnOutcome("bg-session", failed = false))
        runCurrent()

        assertEquals(listOf(NotificationKind.TurnDone to "bg-session"), world.surface.posted())
    }

    @Test
    fun `foregrounded prevents TurnDone from notifying for any session`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(true)
        world.presence.visibleSessionChanged("visible-session")
        world.start()
        world.leaveQuietWindow()

        // Turn finishes for active on-screen session -> silent.
        world.turns.emit(GatewayTurnOutcome("visible-session", failed = false))
        runCurrent()
        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())

        // Turn finishes for another session while foregrounded -> still silent.
        world.turns.emit(GatewayTurnOutcome("other-session", failed = false))
        runCurrent()
        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())
    }

    @Test
    fun `a turn outcome does not wipe dedupe so a pre-disconnect prompt is not re-announced on reconnect`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()
        world.leaveQuietWindow()

        // Prompt is notified.
        world.pendingInputs.value = approval("s1", requestId = "req-A")
        runCurrent()
        assertEquals(listOf(NotificationKind.Approval to "s1"), world.surface.posted())

        // A turn finishes for the session while the prompt is still pending.
        world.turns.emit(GatewayTurnOutcome("s1", failed = false))
        runCurrent()

        // Disconnect and reconnect: req-A is replayed on the new socket.
        world.pendingInputs.value = emptyMap()
        runCurrent()
        world.socketOpens.emit(Unit)
        runCurrent()
        world.pendingInputs.value = approval("s1", requestId = "req-A")
        runCurrent()

        // Quiet window expires: prompt must NOT re-announce.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, world.surface.posts.size) // 1 Approval + 1 TurnDone
        assertEquals(
            listOf(
                NotificationKind.Approval to "s1",
                NotificationKind.TurnDone to "s1",
            ),
            world.surface.posted(),
        )
    }

    @Test
    fun `prompt deferred while session is visible fires when user navigates to another session in foreground`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(true)
        world.presence.visibleSessionChanged("session-a")
        world.start()

        // Socket opens, prompt arrives for session-a while visible: suppressed.
        world.socketOpens.emit(Unit)
        runCurrent()
        advanceTimeBy(1_000)
        world.pendingInputs.value = approval("session-a")
        runCurrent()
        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())

        // Quiet window expires while session-a is still on screen: still suppressed.
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())

        // User navigates to session-b while staying foregrounded: session-a prompt is now off-screen and fires.
        world.presence.visibleSessionChanged("session-b")
        runCurrent()
        assertEquals(listOf(NotificationKind.Approval to "session-a"), world.surface.posted())
    }

    @Test
    fun `two outstanding prompts announced pre-disconnect and replayed one event at a time on reconnect do not re-announce`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()
        world.leaveQuietWindow()

        // Two outstanding prompts announced pre-disconnect.
        world.pendingInputs.value = approval("s1", requestId = "req-1") + clarify("s2")
        runCurrent()
        assertEquals(2, world.surface.posts.size)
        assertEquals(
            listOf(
                NotificationKind.Approval to "s1",
                NotificationKind.Input to "s2",
            ),
            world.surface.posted(),
        )

        // Disconnect: repository wipes pending map to emptyMap().
        world.pendingInputs.value = emptyMap()
        runCurrent()

        // Reconnect: socket opens, opening quiet window.
        world.socketOpens.emit(Unit)
        runCurrent()

        // Replayed ONE EVENT AT A TIME ({A} -> {A, B}).
        world.pendingInputs.value = approval("s1", requestId = "req-1")
        runCurrent()
        world.pendingInputs.value = approval("s1", requestId = "req-1") + clarify("s2")
        runCurrent()

        // Quiet window expires: neither re-announces. Exact post count remains 2.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, world.surface.posts.size)
        assertEquals(
            listOf(
                NotificationKind.Approval to "s1",
                NotificationKind.Input to "s2",
            ),
            world.surface.posted(),
        )
    }

    @Test
    fun `a stale expiry that reaches the collector after a new window opened is ignored`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        // Wakes at T=4,000 before the notifier registers window 1's timer, so the socket
        // open reaches the merged collector ahead of window 1's already-emitted expiry.
        // Cancelling the timer cannot retract that emission, which is why the generation
        // token — and not the cancel — is what keeps the fresh window shut.
        backgroundScope.launch {
            delay(4_000)
            world.socketOpens.emit(Unit)
        }
        runCurrent()
        world.start()

        advanceTimeBy(4_000)
        runCurrent()

        world.pendingInputs.value = approval("raced")
        runCurrent()
        assertEquals(emptyList<Pair<NotificationKind, String>>(), world.surface.posted())

        advanceTimeBy(4_001)
        runCurrent()
        assertEquals(listOf(NotificationKind.Approval to "raced"), world.surface.posted())
    }

    @Test
    fun `a reconnect whose empty map is conflated away does not re-announce every parked prompt`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()
        world.leaveQuietWindow()

        world.pendingInputs.value = approval("s1") + clarify("s2")
        runCurrent()
        assertEquals(2, world.surface.posts.size)

        // Reconnect. The repository empties its map and the new socket replays
        // both prompts under a fresh connection generation, but `pendingInputs`
        // is conflating, so the collector only ever sees the replay: the whole
        // previous generation looks resolved in a single step.
        world.socketOpens.emit(Unit)
        world.pendingInputs.value = emptyMap()
        world.pendingInputs.value =
            approval("s1", connectionGeneration = 2L) + clarify("s2", connectionGeneration = 2L)
        runCurrent()

        // The new quiet window expires: neither prompt is news.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, world.surface.posts.size)
    }

    @Test
    fun `a prompt observed to resolve is news again when it parks a second time`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(false)
        world.start()
        world.leaveQuietWindow()

        // Two prompts on two sessions, both announced.
        world.pendingInputs.value = approval("s1") + clarify("s2")
        runCurrent()
        assertEquals(2, world.surface.posts.size)

        // The approval is answered while the question stays parked, so the
        // approval's identity is observed to resolve and stops being deduplicated.
        world.pendingInputs.value = clarify("s2")
        runCurrent()
        advanceTimeBy(2_000)

        // It parks again: a new question, not a replay of an answered one.
        world.pendingInputs.value = approval("s1") + clarify("s2")
        runCurrent()

        assertEquals(3, world.surface.posts.size)
        assertEquals(
            listOf(
                NotificationKind.Approval to "s1",
                NotificationKind.Input to "s2",
                NotificationKind.Approval to "s1",
            ),
            world.surface.posted(),
        )
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
    connectionGeneration: Long = 1L,
): Map<PendingInputKey, PendingInputRequest> {
    val key = PendingInputKey(
        connectionGeneration,
        "runtime-$durableSessionId",
        requestId,
        PendingInputKind.Approval,
    )
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

private fun clarify(
    durableSessionId: String,
    connectionGeneration: Long = 1L,
): Map<PendingInputKey, PendingInputRequest> {
    val key = PendingInputKey(
        connectionGeneration,
        "runtime-$durableSessionId",
        "req-$durableSessionId",
        PendingInputKind.Clarify,
    )
    return mapOf(
        key to ClarifyPending(
            key = key,
            durableSessionId = durableSessionId,
            runtimeSessionId = key.runtimeSessionId,
            question = "Which branch?",
        ),
    )
}
