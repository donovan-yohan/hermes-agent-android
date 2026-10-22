package com.hermesagent.mobile.ui.sessions

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SessionExportDestinationTest {
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
