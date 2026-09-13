package com.hermesagent.mobile.data.notifications

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preference model, which is Desktop's
 * (`apps/desktop/src/store/native-notifications.ts:31-49` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 */
class NotificationSettingsTest {

    @Test
    fun `the ported registry keeps Desktop's kinds and Desktop's order`() {
        assertEquals(
            listOf("approval", "input", "turnDone", "turnError", "backgroundDone", "credits", "plugin"),
            NotificationKind.entries.map(NotificationKind::key).take(7),
        )
    }

    /**
     * Appended, never interleaved: the persisted name is the key, so the enum
     * may be reordered — but keeping Desktop's seven contiguous and in their
     * own order is what makes the first assertion readable as a port.
     */
    @Test
    fun `the mobile-only kinds come after Desktop's, and are the ones this app can raise`() {
        assertEquals(
            listOf("connectionLost", "stillWaiting"),
            NotificationKind.entries.map(NotificationKind::key).drop(7),
        )
        assertEquals(
            listOf("approval", "input", "turnDone", "turnError", "connectionLost", "stillWaiting"),
            SETTABLE_KINDS.map(NotificationKind::key),
        )
    }

    /**
     * The three left out have no mobile source at all — no backgrounded
     * terminal, no credit ledger, no desktop plugin host — and the ledger
     * classifies them `non-goal`. They stay in the enum because it is also the
     * persisted shape.
     */
    @Test
    fun `the Desktop-only kinds are carried but never offered`() {
        for (kind in listOf(NotificationKind.BackgroundDone, NotificationKind.Credits, NotificationKind.Plugin)) {
            assertFalse(kind.hasMobileSource)
            assertFalse(kind in SETTABLE_KINDS)
        }
    }

    @Test
    fun `a preview is on by default and is its own preference`() {
        assertTrue(NotificationSettings().preview)
        assertFalse(NotificationSettings(preview = false).preview)
        // Independent of the kinds: turning the preview off silences no kind.
        assertTrue(NotificationSettings(preview = false).allows(NotificationKind.Approval))
    }

    @Test
    fun `every kind defaults to on`() {
        val settings = NotificationSettings()
        assertTrue(NotificationKind.entries.all(settings::allows))
    }

    @Test
    fun `the master switch silences every kind`() {
        val settings = NotificationSettings(enabled = false)
        assertTrue(NotificationKind.entries.none(settings::allows))
    }

    @Test
    fun `one kind off leaves the others alone`() {
        val settings = NotificationSettings(
            kinds = mapOf(NotificationKind.TurnDone to false),
        )
        assertFalse(settings.allows(NotificationKind.TurnDone))
        assertTrue(settings.allows(NotificationKind.Approval))
    }

    @Test
    fun `blocking prompts are loud and everything else is not`() {
        assertEquals(APPROVALS_CHANNEL_ID, NotificationKind.Approval.channelId)
        assertEquals(APPROVALS_CHANNEL_ID, NotificationKind.Input.channelId)
        assertEquals(RESPONSES_CHANNEL_ID, NotificationKind.TurnDone.channelId)
        assertEquals(RESPONSES_CHANNEL_ID, NotificationKind.TurnError.channelId)
    }

    @Test
    fun `a saved preference round-trips`() = runTest {
        val store = TransientNotificationPreferences()

        store.setNotificationKind(NotificationKind.TurnDone, false)
        store.setNotificationsEnabled(false)

        val settings = store.notificationSettings.first()
        assertFalse(settings.enabled)
        assertEquals(false, settings.kinds[NotificationKind.TurnDone])
        assertEquals(true, settings.kinds[NotificationKind.Approval])
    }

    @Test
    fun `the permission is remembered as asked exactly once`() = runTest {
        val store = TransientNotificationPreferences()
        assertFalse(store.notificationPermissionAsked.first())

        store.markNotificationPermissionAsked()

        assertTrue(store.notificationPermissionAsked.first())
    }

    @Test
    fun `a session title reaches the shade redacted, single-line and bounded`() {
        val title = "deploy to admin@prod.internal:hunter2\nsecond line"
        val safe = title.notificationSafeTitle()

        assertFalse(safe.contains("hunter2"))
        assertFalse(safe.contains('\n'))
        assertTrue(safe.length <= MAX_NOTIFICATION_TITLE)
        assertEquals("x".repeat(MAX_NOTIFICATION_TITLE), "x".repeat(500).notificationSafeTitle())
    }
}
