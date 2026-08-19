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
    val out = ByteArrayOutputStream(minOf(limit, DEFAULT_CHUNK))
    val chunk = ByteArray(DEFAULT_CHUNK)
    var remaining = limit

    while (remaining > 0) {
        val read = read(chunk, 0, minOf(chunk.size, remaining))
        if (read < 0) break
        out.write(chunk, 0, read)
        remaining -= read
    }

    return out.toByteArray()
}

private const val DEFAULT_CHUNK = 4096
