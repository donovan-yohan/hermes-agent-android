package com.hermesagent.mobile

import android.content.Intent
import android.os.Bundle
import com.hermesagent.mobile.data.notifications.NotificationTarget
import com.hermesagent.mobile.data.notifications.notificationTarget
import com.hermesagent.mobile.data.notifications.writeTo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HermesApplication::class)
class NotificationPendingTapTest {
    @Test fun unfulfilledColdTapSurvivesSavedActivityState() {
        val target = NotificationTarget.Room("saved-endpoint", "room-id")
        val intent = target.writeTo(Intent())
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).create().get()
        val state = Bundle()
        val method = MainActivity::class.java.getDeclaredMethod("onSaveInstanceState", Bundle::class.java)
        method.isAccessible = true
        method.invoke(activity, state)
        val preserved = state.getParcelable<Intent>("notification.pending.target")
        assertEquals(target, notificationTarget(preserved))
        val replacement = Robolectric.buildActivity(MainActivity::class.java, Intent()).create(state).get()
        val savedAgain = Bundle()
        method.invoke(replacement, savedAgain)
        assertEquals(target, notificationTarget(savedAgain.getParcelable<Intent>("notification.pending.target")))
    }
}
