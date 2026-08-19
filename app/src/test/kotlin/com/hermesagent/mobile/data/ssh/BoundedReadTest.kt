package com.hermesagent.mobile.data.ssh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.InputStream

class BoundedReadTest {

    @Test
    fun `returns a bounded caller-owned copy`() {
        val bytes = ByteArray(32) { it.toByte() }

        val read = bytes.inputStream().readBounded(7)

        assertArrayEquals(bytes.copyOf(7), read)
    }

    @Test
    fun `an input failure is propagated`() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("picker stopped reading")
        }

        assertThrows(IOException::class.java) { failing.readBounded(32) }
    }
}
