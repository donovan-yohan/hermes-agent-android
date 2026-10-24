package com.hermesagent.mobile.data.notifications

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GatewayNotificationCatchUpTest {
    @Test fun recoveredSenderIdentityMatchesLiveOwnerProfileForDurableDedupe() = runTest {
        val store = MemoryNotificationCheckpoints()
        val inbox = NotificationInbox(store)
        val reader = FakeNotificationTransport()
        GatewayNotificationCatchUp(store).run(reader, inbox) {}
        val live = BotMessage(MessageConversation(reader.endpoint, MessageConversationKind.Session, reader.session.appId, "bot"),
            "2", "bot", "Bot", "bot", "Conversation", "live reply", 1000)
        assertTrue(inbox.accept(live) {})
        reader.rows = listOf(row(1, "assistant"), row(2, "assistant"))
        GatewayNotificationCatchUp(store).run(reader, inbox) { fail("live/recovery duplicate") }
    }

    @Test fun recordsInitialInventoryCompletionWithoutDiscardingContinuation() = runTest {
        val store = MemoryNotificationCheckpoints()
        val reader = FakeNotificationTransport()
        GatewayNotificationCatchUp(store).run(reader, NotificationInbox(store)) {}
        assertTrue(store.value.sessionSweepCompleted)
        assertTrue(store.value.roomSweepCompleted)
    }

    @Test fun groupOnlyMemberMessageNotTurnSettlementAndPeerNeverAliasesLocalProfile() = runTest {
        val store = MemoryNotificationCheckpoints()
        store.save("saved-endpoint", NotificationCheckpoint(enrolledAt = 1000, rooms = mapOf("room" to 1)))
        val reader = FakeNotificationTransport().apply {
            room = NotificationRoom("room", "Group", 4, listOf(NotificationRoomMember("peer", "Peer", null)), "gw")
            events = listOf(NotificationRoomEvent(2, "user", "message.user", null, "prompt", 1),
                NotificationRoomEvent(3, "reply", "message.member", "peer", "reply", 1),
                NotificationRoomEvent(4, "done", "turn.settled", "peer", "Finished", 1))
        }
        val results = mutableListOf<BotMessage>()
        GatewayNotificationCatchUp(store).run(reader, NotificationInbox(store)) { results += it }
        assertEquals(listOf("reply"), results.map { it.messageId })
        assertNull(results.single().senderProfile)
        assertEquals(4L, store.value.rooms["room"])
        reader.room = reader.room!!.copy(latest = 1)
        GatewayNotificationCatchUp(store).run(reader, NotificationInbox(store)) { fail("regression replay") }
        assertEquals(1L, store.value.rooms["room"])
    }

    @Test fun freshReplyDuringInitialEnrollmentIsNotSilentlyBaselined() = runTest {
        val store = MemoryNotificationCheckpoints()
        val reader = FakeNotificationTransport().apply {
            session = session.copy(startedAtMillis = 2000)
            rows = listOf(row(1, "assistant", 1))
        }
        val delivered = mutableListOf<BotMessage>()
        GatewayNotificationCatchUp(store, nowMillis = { 1000 }).run(reader, NotificationInbox(store)) { delivered += it }
        assertEquals(listOf("1"), delivered.map { it.messageId })
    }
    @Test fun malformedPageCannotAdvancePastMissingMessageAndRetryUsesSameOffset() = runTest {
        val store = MemoryNotificationCheckpoints()
        val reader = FakeNotificationTransport()
        val delivered = mutableListOf<BotMessage>()
        GatewayNotificationCatchUp(store).run(reader, NotificationInbox(store)) { delivered += it }
        reader.rows = listOf(row(1, "assistant"), row(3, "assistant"), row(2, "assistant"))
        try { GatewayNotificationCatchUp(store).run(reader, NotificationInbox(store)) { delivered += it }; fail() }
        catch (_: IllegalArgumentException) { }
        assertTrue(delivered.isEmpty())
        reader.rows = listOf(row(1, "assistant"), row(2, "assistant"), row(3, "assistant"))
        GatewayNotificationCatchUp(store).run(reader, NotificationInbox(store)) { delivered += it }
        assertEquals(listOf("2", "3"), delivered.map { it.messageId })
    }

    @Test fun newlyDiscoveredRoomContinuationNeverReplaysPreEnrollmentHistory() = runTest {
        val store = MemoryNotificationCheckpoints()
        store.save("saved-endpoint", NotificationCheckpoint(enrolledAt = 1000))
        val reader = FakeNotificationTransport()
        reader.room = NotificationRoom("room", "Group", 5, listOf(NotificationRoomMember("peer", "Peer", null)), "gw")
        reader.events = (1L..5L).map { NotificationRoomEvent(it, "event-$it", "message.member", "peer", "text", if (it == 5L) 2000 else 1) }
        val delivered = mutableListOf<BotMessage>()
        repeat(5) { GatewayNotificationCatchUp(store, NotificationReadBudget(pages = 4, pageSize = 1)).run(reader, NotificationInbox(store)) { delivered += it } }
        assertEquals(listOf("event-5"), delivered.map { it.messageId })
    }
    @Test fun boundedSessionPagesContinueAcrossRestartWithoutLosingIds() = runTest {
        val store = MemoryNotificationCheckpoints()
        val reader = FakeNotificationTransport()
        val delivered = mutableListOf<BotMessage>()
        val budget = NotificationReadBudget(pages = 4, pageSize = 2)
        GatewayNotificationCatchUp(store, budget).run(reader, NotificationInbox(store)) { delivered += it }
        reader.rows = (1L..8L).map { row(it, if (it == 3L) "tool" else "assistant", 1) }
        repeat(5) { GatewayNotificationCatchUp(store, budget).run(reader, NotificationInbox(store)) { delivered += it } }
        assertEquals(listOf("2", "4", "5", "6", "7", "8"), delivered.map { it.messageId }.sorted())
    }
    @Test fun responseAfterEndpointSwitchPublishesNothingAndCloses() = runTest {
        val reader = FakeNotificationTransport().apply { afterMessages = { current = false } }
        try { GatewayNotificationCatchUp(MemoryNotificationCheckpoints()).run(reader, NotificationInbox(MemoryNotificationCheckpoints())) { fail("stale") }; fail() }
        catch (_: IllegalStateException) { }
        assertTrue(reader.closed)
    }

    @Test fun newDesktopSessionAfterEnrollmentNotifiesFirstReplyEvenWithRegressingClock() = runTest {
        val store = MemoryNotificationCheckpoints()
        val reader = FakeNotificationTransport()
        val results = mutableListOf<BotMessage>()
        val poller = GatewayNotificationCatchUp(store, nowMillis = { 1000 })
        poller.run(reader, NotificationInbox(store)) { results += it }
        reader.session = reader.session.copy(appId = "new-app-id", rawId = "desktop-created", startedAtMillis = 2000)
        reader.rows = listOf(row(5, "assistant", 1))
        poller.run(reader, NotificationInbox(store)) { results += it }
        assertEquals(listOf("5"), results.map { it.messageId })
    }

    @Test fun baselineThenDesktopReplyUsesPersistedIdsNotReadWatermarkOrClock() = runTest {
        val store = MemoryNotificationCheckpoints()
        val inbox = NotificationInbox(store)
        val reader = FakeNotificationTransport()
        val delivered = mutableListOf<BotMessage>()
        GatewayNotificationCatchUp(store).run(reader, inbox) { delivered += it }
        assertTrue(delivered.isEmpty())
        reader.rows = listOf(row(1, "assistant", 500), row(2, "tool"), row(3, "user"), row(4, "assistant", 1))
        GatewayNotificationCatchUp(store).run(reader, inbox) { delivered += it }
        assertEquals(listOf("4"), delivered.map { it.messageId })
        assertEquals(1L, delivered.single().atMillis)
        assertTrue(reader.closed)
        GatewayNotificationCatchUp(store).run(reader, inbox) { delivered += it }
        assertEquals(1, delivered.size)
    }
}

internal fun row(id: Long, role: String, at: Long = 1000) = RecoveredSessionMessage(id, role, "reply-$id", at)
internal class FakeNotificationTransport : NotificationReadTransport {
    override val endpoint = "saved-endpoint"
    var current = true
    var closed = false
    var rows = listOf(row(1, "assistant", 500))
    var session = NotificationSession("qualified-session", "raw-session", "bot", "owner", "Bot", "Conversation")
    var afterMessages: () -> Unit = {}
    var room: NotificationRoom? = null
    var events: List<NotificationRoomEvent> = emptyList()
    override fun isCurrent() = current
    override suspend fun sessions(offset: Long, limit: Int) = NotificationSessionPage(listOf(session), null)
    override suspend fun messages(session: NotificationSession, offset: Long, limit: Int): NotificationMessagePage {
        val values = rows.asReversed().drop(offset.toInt()).take(limit).asReversed()
        afterMessages(); return NotificationMessagePage(values)
    }
    override suspend fun rooms(offset: Long, limit: Int) = NotificationRoomPage(listOfNotNull(room), null)
    override suspend fun roomLog(room: NotificationRoom, since: Long, limit: Int): NotificationRoomLog {
        val page = events.filter { it.seq > since }.take(limit)
        val cursor = page.lastOrNull()?.seq ?: since
        return NotificationRoomLog(page, cursor, cursor < room.latest, room.authority)
    }
    override fun close() { closed = true }
}
