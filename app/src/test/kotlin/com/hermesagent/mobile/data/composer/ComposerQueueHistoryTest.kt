package com.hermesagent.mobile.data.composer

import androidx.lifecycle.SavedStateHandle
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.UserTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerQueueHistoryTest {
    @Test
    fun `history reads only current session nonblank user turns newest first and restores empty draft`() {
        val cache = SessionCache().apply {
            setTranscript(
                "a",
                listOf(
                    UserTurn("a-1", "old user", 1),
                    AssistantTurn("a-2", "assistant", 2),
                    UserTurn("a-3", "", 3),
                    UserTurn("a-4", "new user", 4),
                ),
            )
            setTranscript("b", listOf(UserTurn("b-1", "other session", 5)))
        }
        val history = ComposerHistoryController(cache, TransientComposerHistoryBrowseStore())

        assertEquals(ComposerDraftChange.Unchanged, history.browseOlder("a", "typed remains"))
        assertEquals(ComposerDraftChange.Changed("new user"), history.browseOlder("a", ""))
        assertEquals(ComposerDraftChange.Changed("old user"), history.browseOlder("a", "new user"))
        assertEquals(ComposerDraftChange.Changed("new user"), history.browseNewer("a"))
        assertEquals(ComposerDraftChange.Changed(""), history.browseNewer("a"))
        assertEquals(ComposerDraftChange.Unchanged, history.browseNewer("a"))
    }

    @Test
    fun `browse state uses SavedStateHandle only`() {
        val handle = SavedStateHandle()
        val store = SavedStateComposerHistoryBrowseStore(handle)
        store.put("durable", ComposerHistoryBrowseState(cursor = 2, draftSnapshot = "draft"))

        assertEquals(ComposerHistoryBrowseState(2, "draft"), store.get("durable"))
        store.remove("durable")
        assertEquals(null, store.get("durable"))
    }

    @Test
    fun `bounded local undo redo resets scope and never mutates authoritative transcript`() {
        val cache = SessionCache().apply {
            setTranscript("durable", listOf(UserTurn("u1", "authoritative", 1)))
        }
        val originalTranscript = cache.state.value.transcripts
        val history = ComposerHistoryController(cache, TransientComposerHistoryBrowseStore(), maxUndoDepth = 2)

        history.recordOrdinaryEdit("durable", "", "a")
        history.recordOrdinaryEdit("durable", "a", "ab")
        history.recordOrdinaryEdit("durable", "ab", "abc")
        assertEquals(ComposerDraftChange.Changed("ab"), history.undo("durable", "abc"))
        assertEquals(ComposerDraftChange.Changed("a"), history.undo("durable", "ab"))
        assertEquals(ComposerDraftChange.Unchanged, history.undo("durable", "a"))
        assertEquals(ComposerDraftChange.Changed("ab"), history.redo("durable", "a"))
        assertTrue(history.undoRedoState("durable").canUndo)

        history.rehome("durable", "tip")
        assertFalse(history.undoRedoState("durable").canUndo)
        assertFalse(history.undoRedoState("tip").canRedo)
        assertSame("history must not write back into SessionCache", originalTranscript, cache.state.value.transcripts)
    }
}
