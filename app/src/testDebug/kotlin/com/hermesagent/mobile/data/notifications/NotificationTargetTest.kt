package com.hermesagent.mobile.data.notifications

import android.app.Application
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationTargetTest {
    @Test fun sessionAndRoomRoundTripWithoutLosingOwnerOrEndpoint() {
        val session = NotificationTarget.Session("saved-row", "owner-qualified-app-id", "local-bot")
        val room = NotificationTarget.Room("saved-row", "backend-room")
        for (target in listOf(session, room)) assertEquals(target, notificationTarget(target.writeTo(Intent())))
        assertNull(notificationTarget(Intent()))
        assertNull(notificationTarget(NotificationTarget.Session("", "id", "bot").writeTo(Intent())))
    }
}
