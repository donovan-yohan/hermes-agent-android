package com.hermesagent.mobile.data.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.MainActivity
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What actually reaches the OS.
 *
 * Robolectric because the interesting parts are all platform objects: the two
 * channels and their importances, the extras a shade renders, the public
 * version a locked screen renders instead, and the exact intent an action
 * button will fire. None of those are assertable against an interface.
 *
 * The stock [Application] is deliberate: this exercises the surface, and
 * standing up the whole process-scoped Gateway graph to do it would make an
 * unrelated connection failure look like a notification bug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidNotificationSurfaceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `two channels are registered, loud for blocking prompts and default for the rest`() {
        AndroidNotificationSurface(context)

        val approvals = manager.getNotificationChannel(APPROVALS_CHANNEL_ID)
        assertEquals(NotificationCopy.APPROVALS_CHANNEL_NAME, approvals.name)
        assertEquals(NotificationCopy.APPROVALS_CHANNEL_DESCRIPTION, approvals.description)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, approvals.importance)

        val responses = manager.getNotificationChannel(RESPONSES_CHANNEL_ID)
        assertEquals(NotificationCopy.RESPONSES_CHANNEL_NAME, responses.name)
        assertEquals(NotificationCopy.RESPONSES_CHANNEL_DESCRIPTION, responses.description)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, responses.importance)
    }

    @Test
    fun `an approval renders Desktop's title, the conversation, and Desktop's two buttons`() {
        AndroidNotificationSurface(context).post(approvalPost())

        val notification = posted(NotificationKind.Approval, SESSION)
        assertEquals(NotificationCopy.APPROVAL_TITLE, notification.title())
        assertEquals("Refactor the parser", notification.text())
        assertEquals(APPROVALS_CHANNEL_ID, notification.channelId)
        assertEquals(groupKey(SESSION), notification.group)
        assertEquals(
            listOf(NotificationCopy.APPROVE_ACTION, NotificationCopy.REJECT_ACTION),
            notification.actions.map { it.title.toString() },
        )
    }

    @Test
    fun `a locked screen is told the kind and nothing about the conversation`() {
        AndroidNotificationSurface(context).post(approvalPost())

        val notification = posted(NotificationKind.Approval, SESSION)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)

        val public = notification.publicVersion
        assertNotNull(public)
        assertEquals(NotificationCopy.APPROVAL_TITLE, public.title())
        assertNull(public.text())
    }

    @Test
    fun `nothing a Gateway sent beyond the session title is anywhere in the notification`() {
        AndroidNotificationSurface(context).post(approvalPost())

        val rendered = posted(NotificationKind.Approval, SESSION).let { "${it.title()} ${it.text()}" }
        // The command, the description and the request id are all things the
        // surface is never handed; this asserts the seam, not a filter.
        assertFalse(rendered.contains("rm"))
        assertFalse(rendered.contains(REQUEST_ID))
        assertFalse(rendered.contains(RUNTIME))
    }

    @Test
    fun `the Approve button fires the exact request it was built for`() {
        AndroidNotificationSurface(context).post(approvalPost())

        val action = posted(NotificationKind.Approval, SESSION).actions.first()
        val intent = shadowOf(action.actionIntent).savedIntent

        assertEquals(ACTION_RESPOND_TO_APPROVAL, intent.action)
        assertEquals(
            NotificationActionReceiver::class.java.name,
            intent.component?.className,
        )
        assertEquals(CHOICE_APPROVE, intent.getStringExtra(EXTRA_CHOICE))
        assertEquals(REQUEST_ID, intent.getStringExtra(EXTRA_REQUEST_ID))
        assertEquals(RUNTIME, intent.getStringExtra(EXTRA_RUNTIME_SESSION_ID))
        assertEquals(SESSION, intent.getStringExtra(EXTRA_DURABLE_SESSION_ID))
        assertEquals(7L, intent.getLongExtra(EXTRA_CONNECTION_GENERATION, -1L))
    }

    @Test
    fun `Reject sends the Gateway's deny, never a permanent grant`() {
        AndroidNotificationSurface(context).post(approvalPost())

        val reject = posted(NotificationKind.Approval, SESSION).actions[1]
        assertEquals(CHOICE_DENY, shadowOf(reject.actionIntent).savedIntent.getStringExtra(EXTRA_CHOICE))
    }

    @Test
    fun `tapping a notification opens that conversation`() {
        AndroidNotificationSurface(context).post(approvalPost())

        val intent = shadowOf(posted(NotificationKind.Approval, SESSION).contentIntent).savedIntent
        assertEquals(ACTION_OPEN_SESSION, intent.action)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(SESSION, intent.getStringExtra(EXTRA_DURABLE_SESSION_ID))
    }

    @Test
    fun `a finished turn is quieter and rides the other channel`() {
        AndroidNotificationSurface(context).post(
            NotificationPost(
                kind = NotificationKind.TurnDone,
                durableSessionId = SESSION,
                title = NotificationCopy.TURN_DONE_TITLE,
                body = "Refactor the parser",
            ),
        )

        val notification = posted(NotificationKind.TurnDone, SESSION)
        assertEquals(RESPONSES_CHANNEL_ID, notification.channelId)
        assertTrue(notification.actions.isNullOrEmpty())
    }

    @Test
    fun `a conversation's notifications arrive as one group with a summary`() {
        val surface = AndroidNotificationSurface(context)
        surface.post(approvalPost())
        surface.post(
            NotificationPost(
                kind = NotificationKind.TurnDone,
                durableSessionId = SESSION,
                title = NotificationCopy.TURN_DONE_TITLE,
                body = "Refactor the parser",
            ),
        )

        val summary = shadowOf(manager).getNotification(summaryTag(SESSION), NOTIFICATION_ID)
        assertNotNull(summary)
        assertEquals(groupKey(SESSION), summary.group)
        assertTrue(summary.flags and Notification.FLAG_GROUP_SUMMARY != 0)
    }

    @Test
    fun `a finished turn's group summary does not borrow the approval channel's urgency`() {
        AndroidNotificationSurface(context).post(
            NotificationPost(
                kind = NotificationKind.TurnDone,
                durableSessionId = SESSION,
                title = NotificationCopy.TURN_DONE_TITLE,
                body = "Refactor the parser",
            ),
        )

        val summary = shadowOf(manager).getNotification(summaryTag(SESSION), NOTIFICATION_ID)
        assertEquals(RESPONSES_CHANNEL_ID, summary.channelId)
        assertEquals(Notification.GROUP_ALERT_CHILDREN, summary.groupAlertBehavior)
    }

    @Test
    fun `an approval's summary rides the approvals channel and still never alerts on its own`() {
        AndroidNotificationSurface(context).post(approvalPost())

        val summary = shadowOf(manager).getNotification(summaryTag(SESSION), NOTIFICATION_ID)
        assertEquals(APPROVALS_CHANNEL_ID, summary.channelId)
        assertEquals(Notification.GROUP_ALERT_CHILDREN, summary.groupAlertBehavior)
    }

    @Test
    fun `withdrawing the last notification withdraws its summary too`() {
        val surface = AndroidNotificationSurface(context)
        surface.post(approvalPost())

        surface.clear(NotificationKind.Approval, SESSION)

        assertNull(shadowOf(manager).getNotification(childTag(NotificationKind.Approval, SESSION), NOTIFICATION_ID))
        assertNull(shadowOf(manager).getNotification(summaryTag(SESSION), NOTIFICATION_ID))
    }

    @Test
    fun `opening a conversation withdraws everything filed under it`() {
        val surface = AndroidNotificationSurface(context)
        surface.post(approvalPost())

        surface.clearSession(SESSION)

        assertEquals(0, shadowOf(manager).size())
    }

    @Test
    fun `a connection that moved on drops the buttons and says where to answer`() {
        val surface = AndroidNotificationSurface(context)
        surface.post(approvalPost())

        surface.degradeApproval(SESSION)

        val notification = posted(NotificationKind.Approval, SESSION)
        assertEquals(NotificationCopy.APPROVAL_TITLE, notification.title())
        assertEquals(NotificationCopy.OPEN_TO_RESPOND, notification.text())
        assertTrue(notification.actions.isNullOrEmpty())
    }

    @Test
    fun `without the runtime grant nothing is posted and nothing throws`() {
        shadowOf(manager).setNotificationsEnabled(false)

        AndroidNotificationSurface(context).post(approvalPost())

        assertEquals(0, shadowOf(manager).size())
    }

    private fun posted(kind: NotificationKind, durableSessionId: String): Notification =
        shadowOf(manager).getNotification(childTag(kind, durableSessionId), NOTIFICATION_ID)

    private fun Notification.title(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun Notification.text(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private companion object {
        const val SESSION = "durable-1"
        const val RUNTIME = "runtime-1"
        const val REQUEST_ID = "req-abc"

        /** The surface's own id; tags carry the identity, so one id is enough. */
        const val NOTIFICATION_ID = 0x48

        fun approvalPost() = NotificationPost(
            kind = NotificationKind.Approval,
            durableSessionId = SESSION,
            title = NotificationCopy.APPROVAL_TITLE,
            body = "Refactor the parser",
            approval = ApprovalTarget(
                key = PendingInputKey(
                    connectionGeneration = 7L,
                    runtimeSessionId = RUNTIME,
                    requestId = REQUEST_ID,
                    kind = PendingInputKind.Approval,
                ),
                durableSessionId = SESSION,
            ),
        )
    }
}
