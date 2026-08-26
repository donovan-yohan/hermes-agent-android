package com.hermesagent.mobile.data.connections

import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.SshDestination
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

/**
 * The registry's on-disk form: a closed, versioned JSON document holding only
 * the non-secret fields this store is already allowed to keep.
 *
 * A document this build cannot read decodes to an empty list rather than
 * throwing, and one unreadable row is skipped rather than discarding the rows
 * around it — a corrupt entry must not cost someone every other gateway they
 * saved. DataStore writes are atomic, so a half-written document is not a case
 * that can occur; a *future* document is, and this fails closed for it.
 */
internal object ConnectionRegistryCodec {
    private const val VERSION = "1"
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(connections: List<SavedConnection>): String = buildJsonObject {
        put("version", JsonPrimitive(VERSION))
        put(
            "connections",
            JsonArray(
                connections.map { connection ->
                    buildJsonObject {
                        put("id", JsonPrimitive(connection.id))
                        put("label", JsonPrimitive(connection.label))
                        put("kind", JsonPrimitive(connection.kind.name))
                        connection.remote.baseUrl.takeIf(String::isNotBlank)
                            ?.let { put("url", JsonPrimitive(it)) }
                        connection.remote.provider.takeIf(String::isNotBlank)
                            ?.let { put("provider", JsonPrimitive(it)) }
                        connection.host.host.takeIf(String::isNotBlank)
                            ?.let { put("host", JsonPrimitive(it)) }
                        put("port", JsonPrimitive(connection.host.port))
                        connection.host.username.takeIf(String::isNotBlank)
                            ?.let { put("username", JsonPrimitive(it)) }
                        connection.host.remoteHermesProfile.takeIf(String::isNotBlank)
                            ?.let { put("remoteHermesProfile", JsonPrimitive(it)) }
                        put("authMethod", JsonPrimitive(connection.host.authMethod.name))
                        connection.host.acceptedFingerprint
                            ?.let { put("acceptedFingerprint", JsonPrimitive(it)) }
                    }
                },
            ),
        )
    }.toString()

    fun decode(raw: String?): List<SavedConnection> {
        if (raw.isNullOrBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrNull() ?: return emptyList()
        if (root.text("version") != VERSION) return emptyList()
        val rows = root["connections"] as? JsonArray ?: return emptyList()
        return rows.mapNotNull { element -> (element as? JsonObject)?.let(::decodeRow) }
    }

    private fun decodeRow(row: JsonObject): SavedConnection? {
        val id = row.text("id")?.takeIf(String::isNotBlank) ?: return null
        val label = row.text("label")?.takeIf(String::isNotBlank) ?: return null
        return SavedConnection(
            id = id,
            label = label,
            kind = ConnectionKind.fromStoredName(row.text("kind")),
            remote = RemoteGatewayProfile(
                baseUrl = row.text("url").orEmpty(),
                provider = row.text("provider").orEmpty(),
            ),
            host = HostProfile(
                host = row.text("host").orEmpty(),
                port = (row["port"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..65535 }
                    ?: SshDestination.DEFAULT_PORT,
                username = row.text("username").orEmpty(),
                remoteHermesProfile = row.text("remoteHermesProfile").orEmpty(),
                // Persisted by name; an unrecognised name falls back to Password
                // rather than to a keyless method, exactly as the single-profile
                // store does.
                authMethod = AuthMethod.entries.firstOrNull { it.name == row.text("authMethod") }
                    ?: AuthMethod.Password,
                acceptedFingerprint = row.text("acceptedFingerprint")?.takeIf(String::isNotBlank),
            ),
        )
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * A random local row id. It names a slot on this device — never an endpoint,
 * an account, or anything the Gateway ever sees — which is what lets the
 * Keystore entry for a connection be deleted with the connection.
 */
internal fun newConnectionId(random: SecureRandom = SecureRandom()): String =
    ByteArray(8).also(random::nextBytes).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
