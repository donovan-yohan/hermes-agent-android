package com.hermesagent.mobile.data.ssh

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Where a picked key document is read, and what each refusal looks like.
 *
 * The thread is the point of the first case. The Storage Access Framework
 * delivers its result to an Activity callback on the main thread, and both
 * halves of reading one — `openInputStream` and the display-name query — are
 * IPC to a provider that may have to fetch the file first. Nothing about that
 * is visible on a local file, which is why it needs a gate rather than a
 * device pass.
 */
class KeyDocumentTest {

    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, IO_THREAD) }
    private val io = executor.asCoroutineDispatcher()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun `the document read and the name query both leave the caller's thread`() = runBlocking {
        val readOn = AtomicReference<String>()
        val queriedOn = AtomicReference<String>()
        val caller = Thread.currentThread().name

        val document = readKeyDocument(
            io = io,
            openStream = {
                readOn.set(Thread.currentThread().name)
                ByteArrayInputStream(PEM.toByteArray())
            },
            displayName = {
                queriedOn.set(Thread.currentThread().name)
                "id_ed25519"
            },
        )

        assertTrue(document is KeyDocument.Read)
        // The coroutines debug agent suffixes thread names, so match the prefix.
        assertTrue("the read must happen on the injected dispatcher", readOn.get().startsWith(IO_THREAD))
        assertTrue("and so must the name query", queriedOn.get().startsWith(IO_THREAD))
        assertNotEquals("neither may run on the picker's callback thread", caller, readOn.get())
    }

    @Test
    fun `a document inside the cap comes back with its bytes and its name`() = runBlocking {
        val document = readKeyDocument(io, { ByteArrayInputStream(PEM.toByteArray()) }, { "id_ed25519" })

        val read = document as KeyDocument.Read
        assertEquals(PEM, String(read.bytes, Charsets.UTF_8))
        assertEquals("id_ed25519", read.displayName)
    }

    @Test
    fun `a provider that cannot open the document is refused, not ignored`() = runBlocking {
        val document = readKeyDocument(io, { null }, { "id_ed25519" })

        assertEquals(KeyImportProblem.Unreadable, (document as KeyDocument.Refused).problem)
    }

    @Test
    fun `a read that throws part way through is refused, not half a key`() = runBlocking {
        val document = readKeyDocument(io, { FailingStream() }, { "id_ed25519" })

        assertEquals(KeyImportProblem.Unreadable, (document as KeyDocument.Refused).problem)
    }

    @Test
    fun `an oversized document is refused whole, never truncated into a key`() = runBlocking {
        val huge = ByteArray(MAX_KEY_BYTES + 1) { 'x'.code.toByte() }

        val document = readKeyDocument(io, { ByteArrayInputStream(huge) }, { "disk.img" })

        assertEquals(KeyImportProblem.TooLarge, (document as KeyDocument.Refused).problem)
    }

    @Test
    fun `the stream is closed even when the document is refused for size`() = runBlocking {
        val stream = ClosingStream(ByteArray(MAX_KEY_BYTES + 1))

        readKeyDocument(io, { stream }, { "disk.img" })

        assertTrue("a Uri stream must not outlive the read", stream.closed)
    }

    @Test
    fun `a name query that throws leaves the bytes usable`() = runBlocking {
        val document = readKeyDocument(io, { ByteArrayInputStream(PEM.toByteArray()) }) {
            throw IllegalStateException("provider died")
        }

        assertEquals("", (document as KeyDocument.Read).displayName)
        assertEquals(PEM, String(document.bytes, Charsets.UTF_8))
    }

    private class FailingStream : InputStream() {
        override fun read(): Int = throw IOException("the provider went away")
        override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("the provider went away")
    }

    private class ClosingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private companion object {
        const val IO_THREAD = "hermes-key-import-test"

        val PEM = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            not-a-real-key
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()
    }
}
