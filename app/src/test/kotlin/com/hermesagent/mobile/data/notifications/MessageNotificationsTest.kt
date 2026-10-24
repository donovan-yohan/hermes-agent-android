package com.hermesagent.mobile.data.notifications

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MessageNotificationsTest {
    private val conversation = MessageConversation("endpoint", MessageConversationKind.Room, "room")
    private fun message() = BotMessage(conversation, "persisted-1", "member", "Bot", null, "Room", "Reply", 1)

    @Test fun settingsReadCannotPublishAfterEndpointSwitch() = runTest {
        var endpoint = "endpoint"
        var delivered = false
        val preferences = object : NotificationPreferenceStore by TransientNotificationPreferences() {
            override val notificationSettings = kotlinx.coroutines.flow.flow {
                endpoint = "replacement"
                emit(NotificationSettings())
            }
        }
        val notifier = MessageNotifications({ m, delivery -> delivery(m); true }, { endpoint }, preferences,
            NotificationPresence(), { _, _ -> delivered = true })
        try { notifier.accept(message()); fail("stale delivery must remain unacknowledged") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertFalse(delivered)
    }

    @Test fun liveOutcomeRequiresPersistedIdentityAndCapturedEndpoint() = runTest {
        val posts = mutableListOf<BotMessage>()
        val notifier = MessageNotifications({ m, deliver -> deliver(m); true }, { "endpoint" },
            TransientNotificationPreferences(), NotificationPresence(), { m, _ -> posts += m })
        val outcome = com.hermesagent.mobile.data.gateway.GatewayTurnOutcome("qualified", false, "Exact reply",
            completedMessageId = "42", completedMessageOwner = "bot", completedAtMillis = 123,
            endpointGeneration = 7)
        notifier.acceptOutcome(outcome, com.hermesagent.mobile.plugins.bots.BotStorageEndpoint("endpoint", 8), "Title", "Bot")
        notifier.acceptOutcome(outcome.copy(completedMessageId = null), com.hermesagent.mobile.plugins.bots.BotStorageEndpoint("endpoint", 7), "Title", "Bot")
        assertTrue(posts.isEmpty())
        notifier.acceptOutcome(outcome, com.hermesagent.mobile.plugins.bots.BotStorageEndpoint("endpoint", 7), "Title", "Bot")
        assertEquals("42", posts.single().messageId)
        assertEquals("bot", posts.single().senderProfile)
        assertEquals("Exact reply", posts.single().text)
        assertEquals(MessageConversation("endpoint", MessageConversationKind.Session, "qualified", "bot"), posts.single().conversation)
    }

    @Test fun implicitLaunchAndConfirmedProfileSuppressTheSameOwnerQualifiedChat() = runTest {
        val identity = com.hermesagent.mobile.data.gateway.GatewaySessionIdentity("synthetic-endpoint")
        identity.bind(null, "owner-home")
        identity.bind("bot", "owner-home")
        val implicit = identity.qualify("same-raw-id", null)
        val confirmed = identity.qualify("same-raw-id", "bot", "bot")
        assertEquals(implicit, confirmed)
        val presence = NotificationPresence().apply {
            applicationForegroundChanged(true)
            visibleConversationChanged(MessageConversation("endpoint", MessageConversationKind.Session, implicit, null))
        }
        var admissions = 0
        var posts = 0
        val notifier = MessageNotifications({ m, deliver -> admissions++; deliver(m); true }, { "endpoint" },
            TransientNotificationPreferences(), presence, { _, _ -> posts++ })
        notifier.accept(message().copy(conversation = MessageConversation("endpoint", MessageConversationKind.Session, confirmed, "bot")))
        assertEquals(1, admissions)
        assertEquals("optional profile enrichment must not unsuppress the visible actual chat", 0, posts)
    }

    @Test fun equalRawSessionIdFromAnotherOwnerDoesNotSuppressItsReply() = runTest {
        val identity = com.hermesagent.mobile.data.gateway.GatewaySessionIdentity("synthetic-endpoint")
        identity.bind("alpha", "alpha-home")
        identity.bind("beta", "beta-home")
        val alpha = identity.qualify("same-raw-id", "alpha")
        val beta = identity.qualify("same-raw-id", "beta")
        assertNotEquals(alpha, beta)
        val presence = NotificationPresence().apply {
            applicationForegroundChanged(true)
            visibleConversationChanged(MessageConversation("endpoint", MessageConversationKind.Session, alpha, "alpha"))
        }
        val posts = mutableListOf<BotMessage>()
        val notifier = MessageNotifications({ m, deliver -> deliver(m); true }, { "endpoint" },
            TransientNotificationPreferences(), presence, { m, _ -> posts += m })
        notifier.accept(message().copy(conversation = MessageConversation("endpoint", MessageConversationKind.Session, beta, "beta")))
        assertEquals(beta, posts.single().conversation.id)
    }

    @Test fun wrongEndpointNeverEntersInbox() = runTest {
        var admissions = 0
        val notifier = MessageNotifications({ _, _ -> admissions++; true }, { "other" },
            TransientNotificationPreferences(), NotificationPresence(), { _, _ -> fail("posted") })
        notifier.accept(message())
        assertEquals(0, admissions)
    }

    @Test fun visibleConversationIsConsumedWithoutPostingButBackgroundStillPosts() = runTest {
        val presence = NotificationPresence()
        presence.applicationForegroundChanged(true)
        presence.visibleConversationChanged(conversation)
        val seen = mutableSetOf<String>()
        var posts = 0
        val notifier = MessageNotifications({ m, deliver ->
            if (seen.add(m.messageId)) { deliver(m); true } else false
        }, { "endpoint" }, TransientNotificationPreferences(), presence, { _, _ -> posts++ })
        notifier.accept(message())
        assertEquals(setOf("persisted-1"), seen)
        assertEquals(0, posts)
        presence.applicationForegroundChanged(false)
        notifier.accept(message())
        assertEquals(0, posts)
        notifier.accept(message().copy(messageId = "persisted-2"))
        assertEquals(1, posts)
    }
}
