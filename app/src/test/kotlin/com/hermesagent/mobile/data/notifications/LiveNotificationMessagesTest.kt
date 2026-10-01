package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveNotificationMessagesTest {
    private val scope = LiveNotificationScope(7, "runtime", 1)
    private val reply = AssistantTurn("reply", "First", 0, streaming = true)
    private fun state() = LiveNotificationMessages(7, mapOf("chat" to scope), mapOf("chat" to LiveNotificationMessage(scope, reply)))

    @Test fun `streaming revision and final reply use observed prose not timestamp`() {
        val first = state()
        assertEquals("First", first.previewFor("chat"))
        val delta = first.copy(messages = mapOf("chat" to LiveNotificationMessage(scope, reply.copy(markdown = "First second", atMillis = -1))))
        assertEquals("First second", delta.previewFor("chat"))
        val final = delta.copy(messages = mapOf("chat" to LiveNotificationMessage(scope, reply.copy(markdown = "Final", streaming = false))))
        assertEquals("Final", final.previewFor("chat"))
    }

    @Test fun `turn turnover runtime replacement and reconnect reject stale prose`() {
        val first = state()
        for (next in listOf(scope.copy(turnGeneration = 2), scope.copy(runtimeSessionId = "other"), scope.copy(connectionGeneration = 8))) {
            assertNull(first.copy(scopes = mapOf("chat" to next)).previewFor("chat"))
        }
        assertNull(first.copy(connectionGeneration = 8).previewFor("chat"))
        assertNull(first.copy(scopes = emptyMap()).previewFor("chat"))
        assertNull(first.previewFor("other-chat"))
    }

    @Test fun `user role accepted but tools and failed assistant never supply prose`() {
        fun withEntry(entry: com.hermesagent.mobile.data.session.TranscriptEntry) =
            state().copy(messages = mapOf("chat" to LiveNotificationMessage(scope, entry))).previewFor("chat")
        assertEquals("New request", withEntry(UserTurn("u", "New request", 1)))
        assertNull(withEntry(ToolActivity("tool", "terminal", "private output", ToolState.Running)))
        assertNull(withEntry(reply.copy(error = "private error")))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `projection observes live deltas but never hydrated old transcript`() = kotlinx.coroutines.test.runTest {
        val messages = kotlinx.coroutines.flow.MutableStateFlow(state())
        val cache = kotlinx.coroutines.flow.MutableStateFlow(com.hermesagent.mobile.data.session.SessionCacheState())
        val projection = GatewayActivityProjection(
            report = { com.hermesagent.mobile.data.gateway.LiveSessionSnapshot.Unavailable },
            connected = kotlinx.coroutines.flow.MutableStateFlow(true),
            hints = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(),
            sessions = cache,
            pendingInputs = kotlinx.coroutines.flow.MutableStateFlow(emptyMap()),
            activeTurns = kotlinx.coroutines.flow.MutableStateFlow(setOf("chat")),
            liveMessages = messages,
        )
        projection.start(backgroundScope)
        testScheduler.runCurrent()
        assertEquals("First", projection.activity.value.children.single().liveMessagePreview)
        messages.value = state().copy(messages = mapOf("chat" to LiveNotificationMessage(scope, reply.copy(markdown = "Streaming update"))))
        testScheduler.runCurrent()
        assertEquals("Streaming update", projection.activity.value.children.single().liveMessagePreview)
        messages.value = messages.value.copy(scopes = mapOf("chat" to scope.copy(turnGeneration = 2)))
        cache.value = cache.value.copy(transcripts = mapOf("chat" to listOf(reply.copy(markdown = "Stale hydrated reply"))))
        testScheduler.runCurrent()
        assertNull(projection.activity.value.children.single().liveMessagePreview)
    }

    @Test fun `preview is plain redacted single line bounded and omits executable blocks`() {
        assertEquals("Answer link password=<redacted>", "# **Answer** [link](https://private)\npassword=abcdefgh `command`".notificationSafePreview())
        assertNull("```shell\nprivate command\n```".notificationSafePreview())
        assertEquals(MAX_NOTIFICATION_PREVIEW, "x".repeat(900).notificationSafePreview()!!.length)
    }
}
