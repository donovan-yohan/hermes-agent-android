package com.hermesagent.mobile.data.composer

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerQueueStoreTest {
    @Test
    fun `production queue file is profile-hashed and below Android no backup storage`() {
        val source = listOf(
            File("src/main/kotlin/com/hermesagent/mobile/data/composer/ComposerQueue.kt"),
            File("app/src/main/kotlin/com/hermesagent/mobile/data/composer/ComposerQueue.kt"),
        ).first(File::isFile).readText()

        assertTrue(source.contains("context.noBackupFilesDir"))
        assertTrue(source.contains("SHA-256"))
        assertTrue(!source.contains("preferencesDataStore("))
    }

    @Test
    fun `codec is versioned text-only and rejects unknown attachment-shaped records`() {
        val state = ComposerQueueState(
            linkedMapOf(
                "durable-a" to listOf(prompt("one", "first", 1)),
            ),
        )

        val encoded = requireNotNull(ComposerQueueCodec.encodeOrNull(state))
        assertEquals(state, ComposerQueueCodec.decode(encoded))
        assertTrue(ComposerQueueCodec.decode(encoded.replace("\"version\":\"1\"", "\"version\":\"9\"")).entriesByDurableId.isEmpty())
        assertTrue(
            ComposerQueueCodec.decode(
                """{"version":"1","sessions":[{"durableId":"durable-a","entries":[{"id":"one","text":"first","queuedAtMillis":1,"delivery":"ready","attachmentRefs":["content://private"]}]}]}""",
            ).entriesByDurableId.isEmpty(),
        )
    }

    @Test
    fun `store retains FIFO and updates deletes moves and migrates by durable id`() = runTest {
        val store = TransientComposerQueueStore()
        store.enqueue("from", prompt("one", "first", 20))
        store.enqueue("from", prompt("two", "second", 40))
        store.enqueue("to", prompt("three", "third", 30))

        assertEquals(ComposerQueueMutation.Applied, store.moveToHead("from", "two"))
        assertEquals(listOf("two", "one"), store.snapshot().entriesFor("from").map(QueuedPrompt::id))
        assertEquals(ComposerQueueMutation.Applied, store.update("from", "one", "first edited", "preview"))
        assertEquals("first edited", store.snapshot().entriesFor("from").last().text)
        assertEquals(ComposerQueueMutation.Applied, store.migrate("from", "to"))
        assertEquals(listOf("one", "three", "two"), store.snapshot().entriesFor("to").map(QueuedPrompt::id))
        assertTrue(store.snapshot().entriesFor("from").isEmpty())
        assertEquals(ComposerQueueMutation.Applied, store.remove("to", "three"))
        assertEquals(listOf("one", "two"), store.state.first().entriesFor("to").map(QueuedPrompt::id))
    }

    @Test
    fun `bounded queue refuses new intent without evicting an older prompt`() = runTest {
        val store = TransientComposerQueueStore()
        repeat(100) { index ->
            assertEquals(
                ComposerQueueMutation.Applied,
                store.enqueue("durable", prompt("id-$index", "prompt $index", index.toLong())),
            )
        }

        assertEquals(
            ComposerQueueMutation.CapacityReached,
            store.enqueue("durable", prompt("new", "must remain visible to caller", 99)),
        )
        assertEquals(100, store.snapshot().entriesFor("durable").size)
        assertEquals("id-0", store.snapshot().entriesFor("durable").first().id)
    }

    private fun prompt(id: String, text: String, queuedAtMillis: Long) =
        QueuedPrompt(id = id, text = text, queuedAtMillis = queuedAtMillis)
}
