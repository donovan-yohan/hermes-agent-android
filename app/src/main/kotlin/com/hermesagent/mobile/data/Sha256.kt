package com.hermesagent.mobile.data

import java.security.MessageDigest

/** Pure UTF-8 digest encoding; callers own identity construction and domain separators. */
internal fun sha256Utf8Hex(value: String): String = buildString(64) {
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        append("0123456789abcdef"[unsigned ushr 4])
        append("0123456789abcdef"[unsigned and 0xf])
    }
}
