package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.gateway.ApprovalPending
import com.hermesagent.mobile.data.gateway.GatewayTurnOutcome
import com.hermesagent.mobile.data.gateway.LiveSessionStatus
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputKind
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.session.SessionCacheState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayActivityNotificationTest {

    @Test
    fun `activity posts immediately with safe text status and project`() = runTest {
        val world = World(this)
        world.start()

        world.activity.value = GatewayActivity(
            listOf(child(title = "password=" + "x".repeat(8) + "\nsecond line", project = "Mobile")),
        )
        runCurrent()

        assertEquals(
            NotificationActivityChild(
                durableSessionId = "chat-1",
                title = "password=<redacted> second line",
                statusLine = NotificationCopy.ACTIVITY_WORKING,
                projectLabel = "Mobile",
                preview = null,
            ),
            world.surface.latestActivity.single(),
        )
    }

    @Test
    fun `activity bounds text and falls back when the Gateway named no chat`() = runTest {
        val world = World(this)
        world.activity.value = GatewayActivity(
            listOf(
                child(
                    title = "t".repeat(MAX_NOTIFICATION_TITLE + 1),
                    project = "p".repeat(MAX_NOTIFICATION_PROJECT + 1),
                    preview = "v".repeat(MAX_NOTIFICATION_PREVIEW + 1),
                ),
                child(title = "", project = null, preview = "", durableSessionId = "chat-2"),
            ),
        )
        world.start()
        runCurrent()

        val rendered = world.surface.latestActivity.single { it.durableSessionId == "chat-1" }
        assertEquals(MAX_NOTIFICATION_TITLE, rendered.title.length)
        assertEquals(MAX_NOTIFICATION_PROJECT, rendered.projectLabel!!.length)
        assertNull(rendered.preview)
        val fallback = world.surface.latestActivity.single { it.durableSessionId == "chat-2" }
        assertEquals(NotificationCopy.ACTIVITY_CHILD_TITLE, fallback.title)
        assertNull(fallback.preview)
    }

    @Test
    fun `preview toggle and master switch update activity immediately`() = runTest {
        val world = World(this)
        world.activity.value = GatewayActivity(listOf(child()))
        world.start()
        runCurrent()
        assertNull(world.surface.latestActivity.single().preview)

        world.settings.value = NotificationSettings(preview = false)
        runCurrent()
        assertNull(world.surface.latestActivity.single().preview)

        world.settings.value = NotificationSettings(enabled = false)
        runCurrent()
        assertEquals(emptyList<NotificationActivityChild>(), world.surface.latestActivity)

        world.settings.value = NotificationSettings()
        runCurrent()
        assertNull(world.surface.latestActivity.single().preview)
    }

    @Test
    fun `unchanged activity is not reposted and opening a session does not withdraw it`() = runTest {
        val world = World(this)
        val activity = GatewayActivity(listOf(child()))
        world.activity.value = activity
        world.start()
        runCurrent()
        val calls = world.surface.activity.size

        world.activity.value = activity
        world.presence.applicationForegroundChanged(true)
        world.presence.visibleSessionChanged("chat-1")
        runCurrent()

        assertEquals(calls, world.surface.activity.size)
        assertEquals("chat-1", world.surface.latestActivity.single().durableSessionId)
        assertTrue("chat-1" in world.surface.clearedSessions)
    }

    @Test
    fun `changed activity with the same chat id reposts`() = runTest {
        val world = World(this)
        world.activity.value = GatewayActivity(listOf(child(title = "First")))
        world.start()
        runCurrent()
        val calls = world.surface.activity.size

        world.activity.value = GatewayActivity(listOf(child(title = "Updated")))
        runCurrent()

        assertEquals(calls + 1, world.surface.activity.size)
        assertEquals("Updated", world.surface.latestActivity.single().title)
    }

    @Test
    fun `completion alerts stay separate while activity children are posted`() = runTest {
        val world = World(this)
        world.activity.value = GatewayActivity(listOf(child()))
        world.start()
        world.leaveQuietWindow()

        world.turns.emit(GatewayTurnOutcome("chat-2", failed = false))
        runCurrent()

        assertEquals(listOf(NotificationKind.TurnDone to "chat-2"), world.surface.posted())
        assertEquals("chat-1", world.surface.latestActivity.single().durableSessionId)
    }

    @Test
    fun `approval alerts coexist and activity bypasses the alert throttle`() = runTest {
        val world = World(this)
        world.start()
        world.leaveQuietWindow()

        world.pendingInputs.value = approval("chat-2")
        runCurrent()
        world.activity.value = GatewayActivity(listOf(child()))
        runCurrent()

        assertEquals(listOf(NotificationKind.Approval to "chat-2"), world.surface.posted())
        assertEquals("chat-1", world.surface.latestActivity.single().durableSessionId)
    }

    @Test
    fun `cached transcript cannot claim freshness over registry and does not repost`() = runTest {
        val world = World(this)
        world.activity.value = GatewayActivity(listOf(child(durableSessionId = "bot-session")))
        world.start()
        world.sessions.value = SessionCacheState(transcripts = mapOf(
            "bot-session" to listOf(
                com.hermesagent.mobile.data.session.UserTurn("u", "Earlier request", 100),
                com.hermesagent.mobile.data.session.AssistantTurn("a", "Latest answer password=abcdefgh", 1),
                com.hermesagent.mobile.data.session.ToolActivity("t", "terminal", "secret command", com.hermesagent.mobile.data.session.ToolState.Running),
            ),
        ))
        runCurrent()
        assertNull(world.surface.latestActivity.single().preview)
        val posts = world.surface.activity.size
        world.sessions.value = world.sessions.value.copy(transcripts = mapOf(
            "bot-session" to listOf(
                com.hermesagent.mobile.data.session.AssistantTurn("a", "New streaming answer", 2, streaming = true),
            ),
        ))
        runCurrent()
        assertEquals(posts, world.surface.activity.size)
        assertNull(world.surface.latestActivity.single().preview)
        world.sessions.value = world.sessions.value.copy(transcripts = world.sessions.value.transcripts + (
            "other-session" to listOf(com.hermesagent.mobile.data.session.UserTurn("u", "Unrelated", 3))
        ))
        runCurrent()
        assertEquals(posts, world.surface.activity.size)
        world.settings.value = NotificationSettings(preview = false)
        runCurrent()
        assertNull(world.surface.latestActivity.single().preview)
    }

    @Test
    fun `untyped registry payload never reaches shade or changes dedup identity`() = runTest {
        val world = World(this)
        world.activity.value = GatewayActivity(listOf(child(preview = "terminal: private output")))
        world.start()
        runCurrent()
        val initial = world.surface.latestActivity.single()
        assertNull(initial.preview)
        assertEquals(NotificationCopy.ACTIVITY_WORKING, initial.statusLine)
        assertEquals("chat-1", initial.durableSessionId)
        val calls = world.surface.activity.size

        world.activity.value = GatewayActivity(listOf(child(preview = "different tool payload")))
        runCurrent()
        assertEquals(calls, world.surface.activity.size)

        world.activity.value = GatewayActivity(listOf(
            child(preview = "ignored").copy(status = LiveSessionStatus.Waiting),
        ))
        runCurrent()
        assertEquals(calls + 1, world.surface.activity.size)
        assertEquals(NotificationCopy.activityStatus(LiveSessionStatus.Waiting), world.surface.latestActivity.single().statusLine)
        assertEquals("chat-1", world.surface.latestActivity.single().durableSessionId)
    }

    @Test
    fun `live prose streams silently dedupes and obeys preview preference`() = runTest {
        val world = World(this)
        world.start()
        world.activity.value = GatewayActivity(listOf(child().copy(liveMessagePreview = "**First**")))
        runCurrent()
        assertEquals("First", world.surface.latestActivity.single().preview)
        val posts = world.surface.activity.size
        world.activity.value = GatewayActivity(listOf(child(preview = "tool output changed").copy(liveMessagePreview = "**First**")))
        runCurrent()
        assertEquals(posts, world.surface.activity.size)
        world.activity.value = GatewayActivity(listOf(child().copy(liveMessagePreview = "**First second**")))
        runCurrent()
        assertEquals("First second", world.surface.latestActivity.single().preview)
        world.settings.value = NotificationSettings(preview = false)
        runCurrent()
        assertNull(world.surface.latestActivity.single().preview)
        world.settings.value = NotificationSettings()
        runCurrent()
        assertEquals("First second", world.surface.latestActivity.single().preview)
        world.activity.value = GatewayActivity(listOf(child())) // New turn has no observed prose yet.
        runCurrent()
        assertNull(world.surface.latestActivity.single().preview)
    }

    private class World(private val test: kotlinx.coroutines.test.TestScope) {
        val pendingInputs = MutableStateFlow<Map<PendingInputKey, PendingInputRequest>>(emptyMap())
        val turns = MutableSharedFlow<GatewayTurnOutcome>(extraBufferCapacity = 1)
        val sessions = MutableStateFlow(SessionCacheState())
        val socketOpens = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val connected = MutableStateFlow(true)
        val activeTurns = MutableStateFlow<Set<String>>(emptySet())
        val activity = MutableStateFlow(GatewayActivity.Empty)
        val presence = NotificationPresence()
        val settings = MutableStateFlow(NotificationSettings())
        val surface = RecordingNotificationSurface()

        fun start() {
            SessionNotifier(
                pendingInputs = pendingInputs,
                turnOutcomes = turns,
                sessions = sessions,
                socketOpens = socketOpens,
                connected = connected,
                activeTurns = activeTurns,
                activity = activity,
                presence = presence,
                settingsFlow = settings,
                surface = surface,
                clock = { test.testScheduler.currentTime },
            ).start(test.backgroundScope)
            test.testScheduler.runCurrent()
        }

        fun leaveQuietWindow() {
            test.testScheduler.advanceTimeBy(4_001)
            test.testScheduler.runCurrent()
        }
    }

    private companion object {
        fun child(
            title: String = "Build status",
            project: String? = "Mobile",
            preview: String = "Done.",
            durableSessionId: String = "chat-1",
        ) = GatewayActivityChild(
            durableSessionId = durableSessionId,
            title = title,
            preview = preview,
            status = LiveSessionStatus.Working,
            projectLabel = project,
            lastActiveAtMillis = 1L,
        )

        fun approval(durableSessionId: String): Map<PendingInputKey, PendingInputRequest> {
            val key = PendingInputKey(
                1L,
                "runtime-$durableSessionId",
                "request-$durableSessionId",
                PendingInputKind.Approval,
            )
            return mapOf(
                key to ApprovalPending(
                    key = key,
                    durableSessionId = durableSessionId,
                    runtimeSessionId = key.runtimeSessionId,
                    command = "",
                    description = "",
                    choices = listOf("once", "deny"),
                ),
            )
        }
    }
}
