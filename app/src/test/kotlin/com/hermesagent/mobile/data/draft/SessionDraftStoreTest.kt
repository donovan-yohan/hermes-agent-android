package com.hermesagent.mobile.data.draft

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDraftStoreTest {
    @Test
    fun `codec fails closed for corrupt and oversized data`() {
        assertTrue(SessionDraftCodec.decode("not json").isEmpty())
        assertTrue(SessionDraftCodec.decode("x".repeat(70 * 1024)).isEmpty())
    }

    @Test
    fun `codec keeps versioned text only in insertion order`() {
        val drafts = linkedMapOf("a" to "one", "b" to "two")
        assertEquals(drafts, SessionDraftCodec.decode(SessionDraftCodec.encode(drafts)))
    }

    @Test
    fun `store removes blanks keeps mru cap and does not overwrite destination on rehome`() = runTest {
        val store = TransientSessionDraftStore()
        for (index in 1..51) store.replace("session-$index", "draft-$index")
        assertEquals(50, store.drafts.first().size)
        assertTrue("oldest is evicted", "session-1" !in store.drafts.first())
        store.replace("session-2", "touched")
        assertEquals("session-2", store.drafts.first().keys.last())
        store.replace("source", "source draft")
        store.replace("destination", "destination draft")
        store.migrateIfDestinationEmpty("source", "destination")
        assertEquals("destination draft", store.drafts.first()["destination"])
        assertEquals("source draft", store.drafts.first()["source"])
        store.replace("destination", "")
        assertTrue("blank draft is removed", "destination" !in store.drafts.first())
        store.migrateIfDestinationEmpty("source", "destination")
        assertEquals("source draft", store.drafts.first()["destination"])
        assertTrue("a successful rehome removes the obsolete key", "source" !in store.drafts.first())
        store.replace("oversized", "x".repeat(70 * 1024))
        assertTrue("a record the codec cannot restore must not be persisted", "oversized" !in store.drafts.first())

        val pendingWriteStore = TransientSessionDraftStore()
        pendingWriteStore.migrateIfDestinationEmpty("pending-source", "pending-tip", "not debounced yet")
        assertEquals("not debounced yet", pendingWriteStore.drafts.first()["pending-tip"])
        pendingWriteStore.replace("stale-source", "persisted earlier")
        pendingWriteStore.migrateIfDestinationEmpty("stale-source", "fresh-tip", "newer local edit")
        assertEquals("newer local edit", pendingWriteStore.drafts.first()["fresh-tip"])
    }
}
