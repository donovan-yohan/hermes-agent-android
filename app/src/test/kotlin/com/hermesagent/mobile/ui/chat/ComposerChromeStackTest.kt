package com.hermesagent.mobile.ui.chat

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CornerSize
import com.hermesagent.mobile.data.session.ComposerStatusState
import com.hermesagent.mobile.data.session.ComposerTodoState
import com.hermesagent.mobile.data.session.ComposerTodoStatus
import com.hermesagent.mobile.ui.common.JoinedEdge
import com.hermesagent.mobile.ui.common.combine
import com.hermesagent.mobile.ui.common.joinedEdge
import com.hermesagent.mobile.ui.common.joinedPaneShape
import com.hermesagent.mobile.ui.common.JoinedStackRadius
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer's chrome run: which surfaces are in it, where each one joins, and
 * which corners it is therefore allowed to round.
 *
 * Every combination the surface can actually produce is enumerated here — no
 * status strip, no coding row, neither, each alone, both — because the whole
 * point of the run is that a surface appearing or disappearing moves the joint
 * below it. The corners are read off the same functions the composables use, so
 * a change to the rule breaks this test rather than silently drawing a flat edge
 * against a rounded one.
 */
class ComposerChromeStackTest {

    @Test
    fun `neither strip nor coding row leaves the composer as the only pane`() {
        val panes = composerChromePanes(ComposerChromeInputs(hasStatusStack = false, hasCodingRow = false))

        assertEquals(listOf(ComposerChromePane.Composer), panes)
        assertEquals(JoinedEdge.Only, joinedEdge(0, panes.size))
    }

    @Test
    fun `status strip alone makes the composer the run's end`() {
        val panes = composerChromePanes(ComposerChromeInputs(hasStatusStack = true, hasCodingRow = false))

        assertEquals(listOf(ComposerChromePane.StatusStack, ComposerChromePane.Composer), panes)
        assertEquals(JoinedEdge.Start, joinedEdge(0, panes.size))
        assertEquals(JoinedEdge.End, joinedEdge(1, panes.size))
    }

    @Test
    fun `coding row alone makes the composer the run's end`() {
        val panes = composerChromePanes(ComposerChromeInputs(hasStatusStack = false, hasCodingRow = true))

        assertEquals(listOf(ComposerChromePane.CodingRow, ComposerChromePane.Composer), panes)
        assertEquals(JoinedEdge.Start, joinedEdge(0, panes.size))
        assertEquals(JoinedEdge.End, joinedEdge(1, panes.size))
    }

    @Test
    fun `both surfaces put the coding row in the middle and keep the composer at the end`() {
        val panes = composerChromePanes(ComposerChromeInputs(hasStatusStack = true, hasCodingRow = true))

        assertEquals(
            listOf(ComposerChromePane.StatusStack, ComposerChromePane.CodingRow, ComposerChromePane.Composer),
            panes,
        )
        assertEquals(JoinedEdge.Start, joinedEdge(0, panes.size))
        assertEquals(JoinedEdge.Middle, joinedEdge(1, panes.size))
        assertEquals(JoinedEdge.End, joinedEdge(2, panes.size))
    }

    @Test
    fun `the composer is always the last pane and never rounds its top`() {
        ComposerChromeInputsCombinations.forEach { inputs ->
            val panes = composerChromePanes(inputs)
            assertEquals(ComposerChromePane.Composer, panes.last())
            val composerEdge = joinedEdge(panes.lastIndex, panes.size)
            assertFalse("composer must never round its top", composerEdge == JoinedEdge.Start)
            assertTrue("composer is the run's bottom", composerEdge == JoinedEdge.End || composerEdge == JoinedEdge.Only)
        }
    }

    @Test
    fun `only the two outer panes of a run own a rounded corner`() {
        (1..3).forEach { count ->
            val rounded = (0 until count).map { index -> joinedEdge(index, count) }
                .count { edge -> edge != JoinedEdge.Middle }
            assertEquals("a run of $count rounds exactly its two outer corners", minOf(count, 2), rounded)
        }
    }

    @Test
    fun `a joint is square on both sides that meet there`() {
        val startShape = joinedPaneShape(JoinedEdge.Start, JoinedStackRadius)
        val middleShape = joinedPaneShape(JoinedEdge.Middle, JoinedStackRadius)
        val endShape = joinedPaneShape(JoinedEdge.End, JoinedStackRadius)

        assertEquals(CornerSize(0.dp), startShape.bottomStart)
        assertEquals(CornerSize(0.dp), startShape.bottomEnd)
        assertEquals(CornerSize(0.dp), middleShape.topStart)
        assertEquals(CornerSize(0.dp), middleShape.topEnd)
        assertEquals(CornerSize(0.dp), middleShape.bottomStart)
        assertEquals(CornerSize(0.dp), middleShape.bottomEnd)
        assertEquals(CornerSize(0.dp), endShape.topStart)
        assertEquals(CornerSize(0.dp), endShape.topEnd)
        assertEquals(CornerSize(JoinedStackRadius), startShape.topStart)
        assertEquals(CornerSize(JoinedStackRadius), endShape.bottomStart)
    }

    @Test
    fun `an inner run folds into the outer one rather than rounding twice`() {
        assertEquals(JoinedEdge.Start, JoinedEdge.Only.combine(JoinedEdge.Start))
        assertEquals(JoinedEdge.Middle, JoinedEdge.Start.combine(JoinedEdge.Middle))
        assertEquals(JoinedEdge.Start, JoinedEdge.Start.combine(JoinedEdge.Start))
        assertEquals(JoinedEdge.End, JoinedEdge.End.combine(JoinedEdge.End))
        assertEquals(JoinedEdge.End, JoinedEdge.End.combine(JoinedEdge.Only))
        assertEquals(
            JoinedEdge.Middle,
            JoinedEdge.Middle.combine(JoinedEdge.End),
        )
    }

    @Test
    fun `a status stack is only a pane of the run when it renders something`() {
        assertFalse(statusStackVisible(status = null, hasQueue = false))
        assertFalse(statusStackVisible(status = ComposerStatusState(), hasQueue = false))
        assertTrue(
            statusStackVisible(
                status = ComposerStatusState(
                    todos = listOf(ComposerTodoStatus("a", "A task", ComposerTodoState.Pending)),
                ),
                hasQueue = false,
            ),
        )
        assertTrue(statusStackVisible(status = ComposerStatusState(), hasQueue = true))
    }

    private companion object {
        val ComposerChromeInputsCombinations = listOf(
            ComposerChromeInputs(hasStatusStack = false, hasCodingRow = false),
            ComposerChromeInputs(hasStatusStack = true, hasCodingRow = false),
            ComposerChromeInputs(hasStatusStack = false, hasCodingRow = true),
            ComposerChromeInputs(hasStatusStack = true, hasCodingRow = true),
        )
    }
}

/**
 * Where a failed session open is classified, and which of the two notices the
 * composer must not repeat.
 *
 * The classifier is driven by the typed [SessionOpenState.Failed] the data lane
 * publishes, so no surface has to recognize a sentence. The composer's own line
 * must stay silent about that one notice, or the same sentence would appear in
 * two panes with two different controls.
 */
class SessionOpenFailurePlacementTest {

    @Test
    fun `a session that opens normally has no failure surface`() {
        assertNull(sessionOpenFailure(ChatUiState(sessionOpen = SessionOpenState.Idle)))
        assertNull(sessionOpenFailure(ChatUiState(sessionOpen = SessionOpenState.Opening("session-a"))))
    }

    @Test
    fun `a failed open is read from the typed state, not from the notice`() {
        val state = ChatUiState(
            sessionOpen = SessionOpenState.Failed(
                sessionId = "session-a",
                message = SESSION_OPEN_FAILED_COPY,
                detail = "TimeoutException: session.resume timed out",
            ),
        )

        val failure = sessionOpenFailure(state)
        assertEquals("session-a", failure?.sessionId)
        assertEquals(SESSION_OPEN_FAILED_COPY, failure?.message)
        assertEquals("TimeoutException: session.resume timed out", failure?.detail)
    }

    @Test
    fun `a notice with no failed open behind it keeps its home on the composer`() {
        // The refusal notice is the only one that carries an escape, and it is
        // never a failed open: it is reported in this chat, not about a chat that
        // never arrived.
        val refusal = ChatUiState(
            notice = ChatNotice(NOT_OWNED_NOTICE, ChatNoticeAction.StartNewSession),
            sessionOpen = SessionOpenState.Idle,
        )

        assertNull(sessionOpenFailure(refusal))
    }

    @Test
    fun `the open failure still reports a session that can be retried`() {
        val failure = SessionOpenState.Failed(sessionId = "session-a", message = SESSION_OPEN_FAILED_COPY)

        assertEquals("session-a", failure.sessionId)
    }
}
