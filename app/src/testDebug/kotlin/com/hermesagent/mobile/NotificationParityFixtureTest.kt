package com.hermesagent.mobile

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.data.notifications.AndroidNotificationSurface
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationParityFixtureTest {
    @Test fun previewReachesRealSinkAndPublicVersionStaysGeneric() = verify(true)
    @Test fun previewOffNeverIncludesLatestMessage() = verify(false)

    private fun verify(preview: Boolean) = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        startNotificationParityFixture(backgroundScope, AndroidNotificationSurface(context), preview) { testScheduler.currentTime }
        runCurrent()
        advanceTimeBy(4_201)
        runCurrent()
        val posted = manager.activeNotifications.map { it.notification }
            .filter { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        assertEquals(2, posted.size)
        val bodies = posted.map { it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() }
        assertEquals(preview, bodies.contains(NOTIFICATION_FIXTURE_LATEST))
        assertEquals(preview, bodies.contains(NOTIFICATION_FIXTURE_COMPLETED))
        assertFalse(bodies.contains("Stale registry preview"))
        posted.forEach { notification ->
            assertNotNull(notification.publicVersion)
            val publicText = notification.publicVersion.extras.toString()
            assertFalse(publicText.contains(NOTIFICATION_FIXTURE_LATEST))
            assertFalse(publicText.contains(NOTIFICATION_FIXTURE_COMPLETED))
            assertFalse(publicText.contains("Synthetic planning"))
        }
    }
}
