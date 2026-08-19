package com.hermesagent.mobile.data.ssh

import java.io.InputStream

/**
 * Read at most [limit] bytes.
 *
 * `InputStream.readNBytes` would say this in one call but is API 33+, and this
 * app's floor is 26. The bound is the point either way: neither a remote shell
 * nor a file the user picked may stream unbounded data into memory.
 *
 * The scratch array is fixed at [limit]. A growable stream would abandon old
 * backing arrays as it expands, leaving unreachable copies of key bytes that no
 * later wipe can reach. This shape makes one caller-owned copy, then wipes the
 * only scratch array on every return and failure path.
 */
fun InputStream.readBounded(limit: Int): ByteArray {
    require(limit >= 0) { "limit must not be negative" }
    val scratch = ByteArray(limit)
    var count = 0

    try {
        while (count < limit) {
            val read = read(scratch, count, limit - count)
            if (read < 0) break
            if (read == 0) {
                val one = read()
                if (one < 0) break
                scratch[count++] = one.toByte()
            } else {
                count += read
            }
        }

        // The caller owns this copy and is responsible for wiping it.
        return scratch.copyOf(count)
    } finally {
        scratch.fill(0)
    }
}
