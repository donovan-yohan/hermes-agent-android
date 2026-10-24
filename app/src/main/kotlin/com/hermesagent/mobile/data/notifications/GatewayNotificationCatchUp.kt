package com.hermesagent.mobile.data.notifications

import java.io.Closeable
import kotlinx.coroutines.sync.withLock

internal data class NotificationSession(val appId: String, val rawId: String, val profile: String, val ownerId: String,
    val senderName: String, val title: String, val startedAtMillis: Long? = null)
internal data class RecoveredSessionMessage(val id: Long, val role: String, val text: String, val atMillis: Long)
internal data class NotificationSessionPage(val sessions: List<NotificationSession>, val next: Long?)
internal data class NotificationMessagePage(val messages: List<RecoveredSessionMessage>)
internal data class NotificationRoom(val id: String, val title: String, val latest: Long,
    val members: List<NotificationRoomMember>, val authority: String)
internal data class NotificationRoomMember(val id: String, val name: String, val profile: String?)
internal data class NotificationRoomEvent(val seq: Long, val id: String, val kind: String, val memberId: String?, val text: String, val atMillis: Long)
internal data class NotificationRoomPage(val rooms: List<NotificationRoom>, val next: Long?)
internal data class NotificationRoomLog(val events: List<NotificationRoomEvent>, val cursor: Long, val more: Boolean, val authority: String)
internal interface NotificationReadTransport : Closeable {
    val endpoint: String
    fun isCurrent(): Boolean
    suspend fun sessions(offset: Long, limit: Int): NotificationSessionPage
    suspend fun messages(session: NotificationSession, offset: Long, limit: Int): NotificationMessagePage
    suspend fun rooms(offset: Long, limit: Int): NotificationRoomPage
    suspend fun roomLog(room: NotificationRoom, since: Long, limit: Int): NotificationRoomLog
}
internal data class NotificationReadBudget(val pages: Int = 24, val pageSize: Int = 40, val timeoutMillis: Long = 45_000)

internal class GatewayNotificationCatchUp(
    private val store: NotificationCheckpointStore,
    private val budget: NotificationReadBudget = NotificationReadBudget(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    init { require(budget.pages >= 3 && budget.pageSize in 1..100 && budget.timeoutMillis > 0) }

    suspend fun run(transport: NotificationReadTransport, inbox: NotificationInbox, deliver: suspend (BotMessage) -> Unit) =
        run(transport, accept = { message -> inbox.accept(message, deliver) })

    /** accept is the SAME admission callback used by live delivery (A::accept).
     * Do not wrap A::accept in inbox.accept again: it owns persistent admission. */
    suspend fun run(transport: NotificationReadTransport, accept: suspend (BotMessage) -> Unit) {
        try {
            runs.withLock {
                kotlinx.coroutines.withTimeout(budget.timeoutMillis) { poll(transport, accept) }
            }
        } finally { transport.close() }
    }

    private suspend fun poll(t: NotificationReadTransport, accept: suspend (BotMessage) -> Unit) {
        fun checkCurrent() { check(t.isCurrent()) { "Notification endpoint changed." } }
        suspend fun load() = NotificationInbox.serialization.withLock { store.load(t.endpoint) }
        suspend fun update(change: (NotificationCheckpoint) -> NotificationCheckpoint) {
            NotificationInbox.serialization.withLock { checkCurrent(); store.save(t.endpoint, change(store.load(t.endpoint))) }
        }
        checkCurrent()
        val first = load().enrolledAt == null
        if (first) update { it.copy(enrolledAt = nowMillis()) }
        val enrolled = requireNotNull(load().enrolledAt)
        var remaining = budget.pages
        // Reserve a bounded lane for rooms even when transcripts have backlog.
        val sessionPages = (budget.pages * 2 / 3).coerceAtLeast(2)
        var spent = 0
        sessionLoop@ while (spent < sessionPages) {
            val state = load()
            checkCurrent()
            val page = t.sessions(state.sessionOffset, budget.pageSize)
            checkCurrent(); spent++; remaining--
            require(page.sessions.size <= budget.pageSize)
            require(page.next == null || page.next > state.sessionOffset)
            for ((index, session) in page.sessions.withIndex().drop(state.sessionIndex)) {
                if (spent >= sessionPages) break@sessionLoop
                val key = session.appId
                var cursor = load().sessions[key]
                while (spent < sessionPages) {
                    checkCurrent()
                    val offset = cursor?.offset ?: 0
                    val messages = t.messages(session, offset, budget.pageSize).messages
                    checkCurrent(); spent++; remaining--
                    require(messages.size <= budget.pageSize && messages.zipWithNext().all { (a, b) -> a.id < b.id })
                    val maxId = messages.lastOrNull()?.id ?: 0
                    if (cursor == null && first && (session.startedAtMillis ?: 0) <= enrolled && messages.none { it.atMillis >= enrolled }) {
                        update { it.copy(sessions = it.sessions + (key to NotificationSessionCursor(maxId))) }
                        cursor = NotificationSessionCursor(maxId)
                        break
                    }
                    val highwater = cursor?.highwater ?: 0
                    val ceiling = cursor?.ceiling?.takeIf { it > 0 } ?: maxId
                    val minAt = cursor?.minAt ?: if (cursor == null && (session.startedAtMillis ?: 0) <= enrolled) enrolled else null
                    for (message in messages) {
                        if (message.id <= highwater || message.id > ceiling || message.role != "assistant" || message.text.isBlank()) continue
                        // Only unknown histories use the enrollment cutoff. Known
                        // conversation ordering is exclusively the persisted ID.
                        if (minAt != null && message.atMillis < minAt) continue
                        checkCurrent()
                        accept(BotMessage(MessageConversation(t.endpoint, MessageConversationKind.Session, session.appId, session.profile),
                            message.id.toString(), session.profile, session.senderName, session.profile, session.title, message.text, message.atMillis))
                        checkCurrent()
                    }
                    val complete = messages.size < budget.pageSize || messages.any { it.id <= highwater }
                    cursor = if (complete) NotificationSessionCursor(maxOf(highwater, ceiling))
                        else NotificationSessionCursor(highwater, offset + messages.size, ceiling, minAt)
                    val saved = cursor
                    update { it.copy(sessions = it.sessions + (key to saved)) }
                    if (complete) break
                }
                if (cursor?.offset != 0L) break@sessionLoop
                update { it.copy(sessionIndex = index + 1) }
            }
            update { it.copy(sessionOffset = page.next ?: 0, sessionIndex = 0, sessionSweepCompleted = it.sessionSweepCompleted || page.next == null) }
            if (page.next == null) break
        }
        while (remaining > 1) {
            val state = load()
            checkCurrent()
            val page = t.rooms(state.roomOffset, budget.pageSize)
            checkCurrent(); remaining--
            require(page.rooms.size <= budget.pageSize && (page.next == null || page.next > state.roomOffset))
            for ((index, room) in page.rooms.withIndex().drop(state.roomIndex)) {
                var since = load().rooms[room.id]
                if (since != null && since > room.latest) {
                    update { it.copy(rooms = it.rooms + (room.id to room.latest), roomIndex = index + 1) }
                    continue
                }
                if (since == null) update { it.copy(roomCutoffs = it.roomCutoffs + (room.id to enrolled)) }
                val minAt = load().roomCutoffs[room.id]
                while ((since ?: 0) < room.latest) {
                    if (remaining <= 0) return
                    checkCurrent()
                    val log = t.roomLog(room, since ?: 0, budget.pageSize)
                    checkCurrent(); remaining--
                    require(log.authority == room.authority && log.cursor > (since ?: 0))
                    require(log.events.size <= budget.pageSize && log.events.lastOrNull()?.seq == log.cursor)
                    for (event in log.events) {
                        if (event.kind != "message.member") continue
                        val member = room.members.firstOrNull { it.id == event.memberId } ?: continue
                        if (minAt != null && event.atMillis < minAt) continue
                        checkCurrent()
                        accept(BotMessage(MessageConversation(t.endpoint, MessageConversationKind.Room, room.id), event.id,
                            member.id, member.name, member.profile, room.title, event.text, event.atMillis))
                        checkCurrent()
                    }
                    since = log.cursor
                    update { it.copy(rooms = it.rooms + (room.id to log.cursor)) }
                    if (!log.more) break
                }
                update { it.copy(rooms = it.rooms + (room.id to (since ?: 0)), roomIndex = index + 1,
                    roomCutoffs = it.roomCutoffs - room.id) }
            }
            update { it.copy(roomOffset = page.next ?: 0, roomIndex = 0, roomSweepCompleted = it.roomSweepCompleted || page.next == null) }
            if (page.next == null) break
        }
    }

    private companion object { val runs = kotlinx.coroutines.sync.Mutex() }
}
