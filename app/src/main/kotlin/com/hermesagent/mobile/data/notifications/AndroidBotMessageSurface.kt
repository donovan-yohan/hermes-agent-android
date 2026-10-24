package com.hermesagent.mobile.data.notifications

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person

/** Bot reply presentation is separate from prompt/activity delivery. */
internal class AndroidBotMessageSurface(context: Context) {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    private data class Card(val conversation: MessageConversation, val sender: String)
    private val histories = linkedMapOf<Card, List<BotMessage>>()

    init { registerChannels(this.context) }

    @Synchronized
    fun postMessage(message: BotMessage, preview: Boolean, avatar: android.graphics.Bitmap? = null) {
        if (!manager.areNotificationsEnabled()) return
        val sender = Person.Builder().setName(message.senderName.notificationSafeTitle())
            .setKey(message.senderId).setBot(true)
            .setIcon(avatar?.let(androidx.core.graphics.drawable.IconCompat::createWithBitmap)).build()
        val card = Card(message.conversation, message.senderId)
        val safe = message.copy(text = if (preview) message.text.notificationSafePreview() ?: "New message" else "New message")
        val history = if (preview) (histories[card].orEmpty().filterNot { it.messageId == message.messageId } + safe).takeLast(8)
            else listOf(safe)
        histories[card] = history
        while (histories.size > 64) histories.remove(histories.keys.first())
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            .setConversationTitle(message.conversationTitle.notificationSafeTitle())
            .setGroupConversation(message.conversation.kind == MessageConversationKind.Room)
        for (entry in history) style.addMessage(entry.text, entry.atMillis, sender)
        val public = NotificationCompat.Builder(context, RESPONSES_CHANNEL_ID)
            .setSmallIcon(AndroidNotificationSurface.SMALL_ICON).setContentTitle("New message").build()
        val notification = NotificationCompat.Builder(context, RESPONSES_CHANNEL_ID)
            .setSmallIcon(AndroidNotificationSurface.SMALL_ICON).setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setAutoCancel(true)
            .setLargeIcon(avatar)
            .setContentIntent(openIntent(message))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build()
        manager.notify(messageTag(card.conversation, card.sender), AndroidNotificationSurface.NOTIFICATION_ID, notification)
    }

    @Synchronized
    fun clearConversation(conversation: MessageConversation) {
        val prefix = messageConversationTag(conversation, includeProfile = false)
        for (posted in manager.activeNotifications) {
            if (posted.tag?.startsWith(prefix) == true) manager.cancel(posted.tag, posted.id)
        }
        val cards = histories.keys.filter { it.conversation.endpoint == conversation.endpoint &&
            it.conversation.kind == conversation.kind && it.conversation.id == conversation.id }
        for (card in cards) {
            manager.cancel(messageTag(card.conversation, card.sender), AndroidNotificationSurface.NOTIFICATION_ID)
            histories.remove(card)
        }
    }

    @Synchronized
    fun clearAll() {
        for (posted in manager.activeNotifications) {
            if (posted.tag?.startsWith("hermes.message:") == true) manager.cancel(posted.tag, posted.id)
        }
        for (card in histories.keys.toList()) manager.cancel(messageTag(card.conversation, card.sender), AndroidNotificationSurface.NOTIFICATION_ID)
        histories.clear()
    }

    private fun openIntent(message: BotMessage): android.app.PendingIntent? {
        val c = message.conversation
        val target = when (c.kind) {
            MessageConversationKind.Session -> c.profile?.let { NotificationTarget.Session(c.endpoint, c.id, it) }
            MessageConversationKind.Room -> NotificationTarget.Room(c.endpoint, c.id)
        } ?: return null
        val intent = target.writeTo(android.content.Intent(context, com.hermesagent.mobile.MainActivity::class.java))
            .setData(android.net.Uri.Builder().scheme("hermes-notification").authority("conversation")
                .appendPath(messageTag(c, message.senderId)).build())
            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return android.app.PendingIntent.getActivity(context, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
    }
}

private fun messageConversationTag(conversation: MessageConversation, includeProfile: Boolean = true): String = "hermes.message:" +
    (listOf(conversation.endpoint, conversation.kind.name, conversation.id) +
        if (includeProfile) listOf(conversation.profile.orEmpty()) else emptyList())
        .joinToString("") { "${it.length}:$it" }

internal fun messageTag(conversation: MessageConversation, sender: String): String =
    messageConversationTag(conversation) + "${sender.length}:$sender"
