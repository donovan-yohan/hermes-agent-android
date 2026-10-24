package com.hermesagent.mobile.data.notifications

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class MessageConversationKind { Session, Room }
internal data class MessageConversation(val endpoint: String, val kind: MessageConversationKind, val id: String, val profile: String? = null)
internal data class BotMessage(val conversation: MessageConversation, val messageId: String, val senderId: String,
    val senderName: String, val senderProfile: String?, val conversationTitle: String, val text: String, val atMillis: Long)
internal data class NotificationSessionCursor(val highwater: Long, val offset: Long = 0, val ceiling: Long = 0, val minAt: Long? = null)
internal data class NotificationCheckpoint(
    val accepted: List<String> = emptyList(),
    val enrolledAt: Long? = null,
    val sessionSweepCompleted: Boolean = false,
    val roomSweepCompleted: Boolean = false,
    val sessions: Map<String, NotificationSessionCursor> = emptyMap(),
    val rooms: Map<String, Long> = emptyMap(),
    val roomCutoffs: Map<String, Long> = emptyMap(),
    val sessionOffset: Long = 0,
    val sessionIndex: Int = 0,
    val roomOffset: Long = 0,
    val roomIndex: Int = 0,
)
internal interface NotificationCheckpointStore {
    suspend fun load(endpoint: String): NotificationCheckpoint
    suspend fun save(endpoint: String, checkpoint: NotificationCheckpoint)
}
internal class NotificationInbox(private val store: NotificationCheckpointStore) {
    suspend fun accept(message: BotMessage, deliver: suspend (BotMessage) -> Unit): Boolean = serialization.withLock {
        val endpoint = message.conversation.endpoint
        val checkpoint = store.load(endpoint)
        val identity = message.identity()
        if (identity in checkpoint.accepted) return@withLock false
        deliver(message)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        store.save(endpoint, checkpoint.copy(accepted = (checkpoint.accepted + identity).takeLast(4096)))
        true
    }

    companion object {
        // All inbox and cursor transactions share this owner, including separately
        // constructed Android store instances. Never hold it across polling IO.
        internal val serialization = Mutex()
    }
}

private fun BotMessage.identity(): String {
    val fields = listOf(conversation.kind.name, conversation.id, conversation.profile.orEmpty(), senderId, messageId)
    val tuple = fields.joinToString("") { "${it.length}:$it" }
    return java.security.MessageDigest.getInstance("SHA-256").digest(tuple.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
