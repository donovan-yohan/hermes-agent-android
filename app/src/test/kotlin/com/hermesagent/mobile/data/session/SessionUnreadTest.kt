package com.hermesagent.mobile.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two unread sources, resolved in one place.
 *
 * Desktop resolves them once so the sidebar, the tabs and the switcher cannot
 * disagree about what a session is doing
 * (`apps/desktop/src/store/session-dot-state.ts:19-23,125-158` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`). Every expectation below is
 * transcribed from that priority list, not derived from production code.
 */
class SessionUnreadTest {

    /**
     * Both sources claim the same tier — "there is something here you haven't
     * opened" — so either one lights the dot.
     */
    @Test
    fun `either unread source lights the dot`() {
        assertEquals(SessionStatus.Unread, row(unread = true).displayStatus())
        assertEquals(SessionStatus.Unread, row(status = SessionStatus.Unread).displayStatus())
        assertEquals(
            SessionStatus.Unread,
            row(status = SessionStatus.Unread, unread = true).displayStatus(),
        )
    }

    /**
     * A Gateway that omits `unread` has said nothing, and nothing is read. The
     * opposite reading would paint a dot on every row of every backend whose
     * list contract predates the watermark.
     */
    @Test
    fun `an unsaid watermark is read`() {
        assertEquals(SessionStatus.Idle, row().displayStatus())
        assertEquals(SessionStatus.Idle, row(unread = false).displayStatus())
    }

    /**
     * Everything louder outranks both sources: `claim(unread)` runs before
     * `claim(background)`, `claim(working)` and `claim(attention)`
     * (`session-dot-state.ts:131-180`), and each pass overwrites the one above.
     */
    @Test
    fun `a louder state outranks the watermark`() {
        listOf(
            SessionStatus.Background,
            SessionStatus.Working,
            SessionStatus.Stalled,
            SessionStatus.NeedsInput,
        ).forEach { status ->
            assertEquals(status.name, status, row(status = status, unread = true).displayStatus())
        }
    }

    /**
     * The read-state *menu item* asks a different question from the dot, and
     * asks it of the raw sources: Desktop's `unread || isUnread`
     * (`app/chat/sidebar/session-actions-menu.tsx:314-315,319` @ the pin). A
     * row that is working, backgrounded, stalled or waiting on input and also
     * carries the watermark is still unread — resolving it through
     * [displayStatus] instead would offer `Mark as unread` on a row that is
     * already unread, with no way left to clear it.
     */
    @Test
    fun `the menu item reads the raw sources, not the resolved dot`() {
        listOf(
            SessionStatus.Background,
            SessionStatus.Working,
            SessionStatus.Stalled,
            SessionStatus.NeedsInput,
        ).forEach { status ->
            val row = row(status = status, unread = true)
            assertTrue(status.name, row.isUnread())
            // And the dot still says the louder thing.
            assertEquals(status.name, status, row.displayStatus())
        }
        assertTrue(row(unread = true).isUnread())
        assertTrue(row(status = SessionStatus.Unread).isUnread())
        assertFalse(row().isUnread())
        assertFalse(row(unread = false).isUnread())
        // A header with no open session has nothing to mark.
        assertFalse(null.isUnread())
    }

    private fun row(
        status: SessionStatus = SessionStatus.Idle,
        unread: Boolean? = null,
    ) = SessionSummary(
        id = "s-1",
        title = "Session",
        preview = "",
        lastActiveAtMillis = 0L,
        status = status,
        unread = unread,
    )
}
