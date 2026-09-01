package com.hermesagent.mobile.data.gateway

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.data.notifications.NotificationCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TurnForegroundServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `turn protection notification channel is created at low importance`() {
        val controller = Robolectric.buildService(TurnForegroundService::class.java)
        controller.create()

        val channel = manager.getNotificationChannel(TurnForegroundService.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationCopy.TURN_PROTECTION_CHANNEL_NAME, channel.name)
        assertEquals(NotificationCopy.TURN_PROTECTION_CHANNEL_DESCRIPTION, channel.description)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun `onStartCommand starts foreground with dataSync type and valid notification copy`() {
        val controller = Robolectric.buildService(TurnForegroundService::class.java)
        val service = controller.create().get()
        val intent = Intent(context, TurnForegroundService::class.java)
        val result = service.onStartCommand(intent, 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        val shadowService = shadowOf(service)
        assertEquals(TurnForegroundService.NOTIFICATION_ID, shadowService.lastForegroundNotificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.foregroundServiceType)

        val notification = shadowService.lastForegroundNotification
        assertNotNull(notification)
        assertEquals(TurnForegroundService.CHANNEL_ID, notification.channelId)
        assertEquals(
            NotificationCopy.TURN_PROTECTION_TITLE,
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            NotificationCopy.TURN_PROTECTION_BODY,
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test
    fun `onStartCommand catches refused start, stops self and notifies failure callback`() {
        var failureReported = false
        TurnForegroundService.onServiceFailure = { failureReported = true }

        try {
            val controller = Robolectric.buildService(TurnForegroundService::class.java)
            val service = controller.create().get()
            shadowOf(service).setThrowInStartForeground(IllegalStateException("Foreground start not allowed"))

            val intent = Intent(context, TurnForegroundService::class.java)
            val result = service.onStartCommand(intent, 0, 1)

            assertEquals(Service.START_NOT_STICKY, result)
            val shadowService = shadowOf(service)
            assertTrue(shadowService.isStoppedBySelf)
            assertTrue(failureReported)
        } finally {
            TurnForegroundService.onServiceFailure = null
        }
    }

    @Test
    fun `onTimeout stops service cleanly and notifies failure callback`() {
        var failureReported = false
        TurnForegroundService.onServiceFailure = { failureReported = true }

        try {
            val controller = Robolectric.buildService(TurnForegroundService::class.java)
            val service = controller.create().get()
            service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

            val shadowService = shadowOf(service)
            assertTrue(shadowService.isStoppedBySelf)
            assertTrue(failureReported)
        } finally {
            TurnForegroundService.onServiceFailure = null
        }
    }

    @Test
    fun `stop keeps the registered failure callback so a later refusal is still reported`() {
        val previous = TurnForegroundService.onServiceFailure
        val callback: () -> Unit = { }
        TurnForegroundService.onServiceFailure = callback

        try {
            val started = TurnForegroundService.start(context)
            assertTrue(started)
            TurnForegroundService.stop(context)

            // The controller registers once and re-takes protection after a normal
            // stop, so clearing here would silence every later refused start.
            assertSame(callback, TurnForegroundService.onServiceFailure)
        } finally {
            TurnForegroundService.onServiceFailure = previous
        }
    }
}
