package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.connections.*
import com.hermesagent.mobile.data.gateway.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Captures a SAVED route, not the live manager: constructing this reader never
 * starts the application graph. The caller must fence the exact saved row. */
internal object SavedGatewayNotificationTransport {
    // GatewayConnection.bindSessionNamespace: foreground app-session keys use
    // route/row/normalized-address, distinct from BotStorageEndpoint.stableId.
    private fun sessionNamespace(row: SavedConnection, base: String): String {
        val tuple = kotlinx.serialization.json.JsonArray(listOf(
            if (row.kind == ConnectionKind.Local) "local" else "remote", row.id, base,
        ).map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()
        return java.security.MessageDigest.getInstance("SHA-256").digest(tuple.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    suspend fun capture(row: SavedConnection, authenticator: NativeGatewayAuthenticator,
        localTokens: GatewaySessionTokenStore, isCurrent: () -> Boolean): NotificationReadTransport? {
        if (!row.restorable || !isCurrent()) return null
        val endpoint = row.botStorageIdentity() ?: return null
        val base = when (row.kind) {
            ConnectionKind.Remote -> row.remoteProfile.normalizedBaseUrl
            ConnectionKind.Local -> row.localProfile.normalizedBaseUrl
            ConnectionKind.Ssh -> null
        } ?: return null
        suspend fun localToken(): ByteArray? {
            if (!isCurrent()) return null
            val slot = row.localProfile.secretSlot ?: return null
            val found = localTokens.loadSessionToken(slot) as? SessionTokenRead.Found ?: return null
            return found.token
        }
        suspend fun authorization(): Pair<String, String>? {
            if (!isCurrent()) return null
            return when (row.kind) {
                ConnectionKind.Remote -> authenticator.backgroundAccessToken(row.remoteProfile)?.let { "Authorization" to "Bearer $it" }
                ConnectionKind.Local -> localToken()?.let { bytes ->
                    try { SESSION_TOKEN_HEADER to bytes.toString(Charsets.US_ASCII) } finally { bytes.fill(0) }
                }
                ConnectionKind.Ssh -> null
            }
        }
        // Prove a stored credential exists before creating any network resource.
        authorization() ?: return null
        if (!isCurrent()) return null
        val closed = AtomicBoolean(false)
        val current = { !closed.get() && isCurrent() }
        val client = gatewayTransportClient()
        val http = OkHttpGatewayHttp(client, { base.takeIf { current() } }, { authorization() }, mutationScope = endpoint)
        var rpc: NotificationReadRpc? = null
        val creation = Mutex()
        return GatewayNotificationReadTransport(endpoint, http, { method, params ->
            creation.withLock {
                check(current())
                val reader = rpc ?: run {
                    val url = when (row.kind) {
                        ConnectionKind.Remote -> {
                            val ticket = authenticator.ticket(row.remoteProfile, browser = null)
                            check(current())
                            remoteGatewayWebSocketUrl(base, ticket)
                        }
                        ConnectionKind.Local -> {
                            val token = localToken() ?: error("No stored local credential.")
                            try { localGatewayWebSocketUrl(base, token) } finally { token.fill(0) }
                        }
                        ConnectionKind.Ssh -> error("Managed SSH cannot run unattended.")
                    }
                    check(current())
                    NotificationReadRpc.connect(client, url.toString(), current).also { rpc = it }
                }
                reader.request(method, params)
            }
        }, current, { closed.set(true); rpc?.close() }, sessionNamespace = sessionNamespace(row, base))
    }
}
