package com.hermesagent.mobile.data.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidBotMessageSurfaceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun message(id: String = "1", sender: String = "bot-a") = BotMessage(
        MessageConversation("saved-row-revision", MessageConversationKind.Room, "room"), id, sender,
        "Research bot", null, "Study group", "**Safe prose** password=abcdefgh", 1234,
    )

    @Test fun openingConversationWithdrawsAllItsSendersWithoutClearingOtherChats() {
        val surface = AndroidBotMessageSurface(context)
        surface.postMessage(message(), true)
        surface.postMessage(message("peer", "peer"), true)
        surface.postMessage(message().copy(conversation = message().conversation.copy(id = "other")), true)
        surface.clearConversation(message().conversation)
        assertEquals(1, manager.activeNotifications.size)
        surface.clearAll()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun openingAfterProcessRestartClearsPreviouslyPostedCards() {
        AndroidBotMessageSurface(context).postMessage(message().copy(conversation = message().conversation.copy(profile = "bot")), true)
        AndroidBotMessageSurface(context).clearConversation(message().conversation)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun clearingAfterProcessRestartRemovesOnlyBotMessageCards() {
        AndroidBotMessageSurface(context).postMessage(message(), true)
        AndroidNotificationSurface(context).postTest("Other notification", "Keep this")
        AndroidBotMessageSurface(context).clearAll()
        assertEquals(listOf(AndroidNotificationSurface.TEST_TAG), manager.activeNotifications.map { it.tag })
    }

    @Test fun sendersHaveIndependentCardsWithBoundedReplacementHistory() {
        val surface = AndroidBotMessageSurface(context)
        repeat(12) { surface.postMessage(message(it.toString()), true) }
        surface.postMessage(message("peer-1", "peer-b"), true)
        assertEquals(2, manager.activeNotifications.size)
        val histories = manager.activeNotifications.map {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification)!!.messages
        }
        assertEquals(listOf(1, 8), histories.map { it.size }.sorted())
    }

    @Test fun previewOffErasesPreviouslyVisibleHistory() {
        val surface = AndroidBotMessageSurface(context)
        surface.postMessage(message(), true)
        surface.postMessage(message("2"), false)
        val notification = manager.activeNotifications.single().notification
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
        assertEquals(listOf("New message"), style.messages.map { it.text.toString() })
        assertFalse(notification.extras.toString().contains("Safe prose"))
    }

    @Test fun messageAvatarAndTypedTapAreCarriedByTheBotPerson() {
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        AndroidBotMessageSurface(context).postMessage(message(), true, bitmap)
        val notification = manager.activeNotifications.single().notification
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
        assertNotNull(style.messages.single().person!!.icon)
        assertNotNull(notification.getLargeIcon())
        val intent = org.robolectric.Shadows.shadowOf(notification.contentIntent).savedIntent
        assertEquals(NotificationTarget.Room("saved-row-revision", "room"), notificationTarget(intent))
        assertTrue(org.robolectric.Shadows.shadowOf(notification.contentIntent).isImmutable)
        assertNull(notification.publicVersion.getLargeIcon())
    }

    @Test fun passiveWorkingCardCanReuseTheAcceptedAvatarPresenter() {
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        AndroidNotificationSurface(context, avatarForSession = { bitmap }).postActivity(listOf(
            NotificationActivityChild("session", "Bot", "Working…")))
        assertNotNull(manager.activeNotifications.single().notification.getLargeIcon())
        assertNull(manager.activeNotifications.single().notification.publicVersion.getLargeIcon())
    }

    @Test fun senderIsABotAndMessagesUseNativeConversationStyle() {
        AndroidBotMessageSurface(context).postMessage(message(), preview = true)
        val notification = manager.activeNotifications.single().notification
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
        assertTrue(style.messages.single().person!!.isBot)
        assertEquals("Research bot", style.messages.single().person!!.name)
        assertEquals("Study group", style.conversationTitle)
        assertEquals("Safe prose password=<redacted>", style.messages.single().text.toString())
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNull(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertFalse(notification.publicVersion.extras.toString().contains("Study group"))
        assertEquals(RESPONSES_CHANNEL_ID, notification.channelId)
    }
}
