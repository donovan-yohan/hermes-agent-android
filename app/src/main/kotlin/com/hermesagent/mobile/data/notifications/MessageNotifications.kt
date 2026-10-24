package com.hermesagent.mobile.data.notifications

import kotlinx.coroutines.flow.first

internal class MessageNotifications(
    private val acceptOnce: suspend (BotMessage, suspend (BotMessage) -> Unit) -> Boolean,
    private val currentEndpoint: () -> String?,
    private val preferences: NotificationPreferenceStore,
    private val presence: NotificationPresence,
    private val post: suspend (BotMessage, Boolean) -> Unit,
) {
    suspend fun acceptOutcome(outcome: com.hermesagent.mobile.data.gateway.GatewayTurnOutcome,
        endpoint: com.hermesagent.mobile.plugins.bots.BotStorageEndpoint?, title: String, senderName: String) {
        if (endpoint == null || outcome.endpointGeneration != endpoint.generation || outcome.failed) return
        val id = outcome.completedMessageId ?: return // Never promote synthetic stream identities.
        val owner = outcome.completedMessageOwner ?: return
        val text = outcome.assistantMessagePreview ?: return
        val at = outcome.completedAtMillis ?: return
        accept(BotMessage(MessageConversation(endpoint.stableId, MessageConversationKind.Session,
            outcome.durableSessionId, owner), id, owner, senderName, owner, title, text, at))
    }

    suspend fun accept(message: BotMessage) {
        if (currentEndpoint() != message.conversation.endpoint) return
        acceptOnce(message, ::deliver)
    }

    /** Catch-up already owns inbox admission. Do not recursively acquire the same inbox mutex. */
    suspend fun deliver(accepted: BotMessage) {
        val settings = preferences.notificationSettings.first()
        if (currentEndpoint() != accepted.conversation.endpoint) throw kotlinx.coroutines.CancellationException("Notification endpoint changed")
        if (!settings.allows(NotificationKind.TurnDone)) return
        val visible = presence.visibleConversation.value
        if (presence.appForegrounded.value && visible?.endpoint == accepted.conversation.endpoint &&
            visible.kind == accepted.conversation.kind && visible.id == accepted.conversation.id) return
        post(accepted, settings.preview)
    }
}
