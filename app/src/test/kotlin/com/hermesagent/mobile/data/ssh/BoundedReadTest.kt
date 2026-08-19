package com.hermesagent.mobile.data.ssh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun `a key-sized read uses one scratch array and wipes it`() {
        val source = ByteArray(8_193) { (it % 251).toByte() }
        val scratchArrays = mutableListOf<ByteArray>()
        val stream = object : InputStream() {
            var offset = 0

            override fun read(): Int = if (offset < source.size) source[offset++].toInt() and 0xff else -1

            override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
                if (scratchArrays.none { it === target }) scratchArrays += target
                if (offset >= source.size) return -1
                val count = minOf(length, source.size - offset)
                source.copyInto(target, targetOffset, offset, offset + count)
                offset += count
                return count
            }
        }

        val read = stream.readBounded(source.size)

        assertArrayEquals(source, read)
        assertTrue("a bounded read must not grow through abandoned scratch arrays", scratchArrays.size == 1)
        assertTrue("the scratch array must be wiped before returning", scratchArrays.single().all { it == 0.toByte() })
    }

    @Test
    fun `an input failure is propagated`() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("picker stopped reading")
        }

        assertThrows(IOException::class.java) { failing.readBounded(32) }
    }
}
