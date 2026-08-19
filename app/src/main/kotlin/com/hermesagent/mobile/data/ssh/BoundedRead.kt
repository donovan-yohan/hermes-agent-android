package com.hermesagent.mobile.data.ssh

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Read at most [limit] bytes.
 *
 * `InputStream.readNBytes` would say this in one call but is API 33+, and this
 * app's floor is 26. The bound is the point either way: neither a remote shell
 * nor a file the user picked may stream unbounded data into memory.
 */
fun InputStream.readBounded(limit: Int): ByteArray {
    require(limit >= 0) { "limit must not be negative" }
    val out = WipeableByteArrayOutputStream(minOf(limit, DEFAULT_CHUNK))
    val chunk = ByteArray(DEFAULT_CHUNK)
    var remaining = limit

    try {
        while (remaining > 0) {
            val read = read(chunk, 0, minOf(chunk.size, remaining))
            if (read < 0) break
            out.write(chunk, 0, read)
            remaining -= read
        }

        // `toByteArray` deliberately makes the one copy the caller owns. The
        // caller is responsible for wiping it; this method wipes every scratch
        // buffer before returning or propagating an I/O failure.
        return out.toByteArray()
    } finally {
        chunk.fill(0)
        out.wipe()
    }
}

private const val DEFAULT_CHUNK = 4096

/** ByteArrayOutputStream keeps its backing array after [toByteArray]. */
private class WipeableByteArrayOutputStream(size: Int) : ByteArrayOutputStream(size) {
    fun wipe() {
        buf.fill(0)
        reset()
    }
}
