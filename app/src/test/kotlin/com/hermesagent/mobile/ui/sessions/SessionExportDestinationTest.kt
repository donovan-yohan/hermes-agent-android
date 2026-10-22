package com.hermesagent.mobile.ui.sessions

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runCurrent

class SessionExportDestinationTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `cancel before write starts still wipes the owned buffer`() = kotlinx.coroutines.test.runTest {
        val bytes = "synthetic transcript".toByteArray()
        var opened = false
        val job = launchSessionExportWrite(this, bytes) { opened = true }
        job.cancel()
        runCurrent()
        assertFalse(opened)
        assertTrue(bytes.all { it == 0.toByte() })
    }
    @Test fun `selected destination receives exact bytes and closes`() {
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        val bytes = "{\"messages\":[]}".toByteArray()
        writeSessionExport(bytes) { output }
        assertArrayEquals(bytes, output.toByteArray())
        assertTrue(closed)
    }
    @Test fun `unavailable destination never reports success`() {
        assertTrue(runCatching { writeSessionExport(byteArrayOf(1)) { null } }.isFailure)
    }
    @Test fun `write failure still closes provider stream`() {
        var closed = false
        val output = object : java.io.OutputStream() {
            override fun write(value: Int) { throw IOException("synthetic refusal") }
            override fun close() { closed = true }
        }
        assertTrue(runCatching { writeSessionExport(byteArrayOf(1)) { output } }.isFailure)
        assertTrue(closed)
    }
}
