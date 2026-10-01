package com.hermesagent.mobile.data.connections

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Saved row plus address revision, not a process generation or transient forwarded port. */
internal fun SavedConnection.botStorageIdentity(): String? {
    if (id.isBlank()) return null
    val address = when (kind) {
        ConnectionKind.Remote -> listOf(remote.normalizedBaseUrl ?: return null)
        ConnectionKind.Local -> listOf(local.normalizedBaseUrl ?: return null)
        ConnectionKind.Ssh -> {
            if (host.host.isBlank() || host.username.isBlank()) return null
            listOf(host.host.trim().lowercase(), host.port.toString(), host.username.trim(),
                host.remoteHermesProfile.trim().ifBlank { "default" })
        }
    }
    // Structured encoding avoids delimiter collisions. Labels, themes and credentials are
    // not address revisions. Revisiting the same saved address restores its own sections.
    val identity = JsonArray((listOf(id, kind.name) + address).map(::JsonPrimitive)).toString()
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
