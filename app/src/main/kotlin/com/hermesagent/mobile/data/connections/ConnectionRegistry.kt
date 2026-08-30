package com.hermesagent.mobile.data.connections

import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.gateway.LocalGatewayProfile
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.gateway.normalizeLocalGatewayUrl
import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.ssh.HostProfile
import java.text.Normalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Which endpoint shape a saved connection is.
 *
 * Desktop registers four kinds (`apps/desktop/src/app/settings/connections-registry.tsx:26-31`
 * @ `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`). Android ships three: `cloud`
 * has no Android sign-in. `local` means something different here than it does
 * on Desktop — Desktop's local runtime is the one its own app manages, while
 * this one is a Hermes the person runs in Termux on this same phone and this
 * app only connects to. Persisted by [Enum.name], never ordinal.
 */
enum class ConnectionKind {
    Remote,
    Ssh,
    Local,
    ;

    val mode: GatewayConnectionMode
        get() = when (this) {
            Remote -> GatewayConnectionMode.Remote
            Ssh -> GatewayConnectionMode.Ssh
            Local -> GatewayConnectionMode.Local
        }

    companion object {
        fun of(mode: GatewayConnectionMode): ConnectionKind = when (mode) {
            GatewayConnectionMode.Remote -> Remote
            GatewayConnectionMode.Ssh -> Ssh
            GatewayConnectionMode.Local -> Local
        }

        /** An unrecognised stored name is a Remote gateway, never a keyless SSH route. */
        fun fromStoredName(raw: String?): ConnectionKind =
            entries.firstOrNull { it.name == raw } ?: Remote
    }
}

/**
 * One saved connection.
 *
 * The endpoint fields are the two existing non-secret types, reused rather than
 * re-declared: [HostProfile] carries the SSH host/port/username/remote
 * profile/auth method/accepted fingerprint and owns the fingerprint rule, and
 * [RemoteGatewayProfile] carries the Gateway URL and optional sign-in provider.
 * Neither type can hold a password, passphrase, private key or token, so a
 * registry row cannot either — the secret for a Remote row lives in its own
 * Keystore-encrypted slot keyed by [id].
 */
data class SavedConnection(
    val id: String,
    val label: String,
    val kind: ConnectionKind,
    val remote: RemoteGatewayProfile = RemoteGatewayProfile(),
    val host: HostProfile = HostProfile(),
    val local: LocalGatewayProfile = LocalGatewayProfile(),
) {
    /**
     * The remote profile stamped with this row's secret slot, so the Keystore
     * entry follows the connection rather than the URL. Deleting the row zeroes
     * exactly this slot.
     */
    val remoteProfile: RemoteGatewayProfile get() = remote.copy(secretSlotId = id)

    /** The same rule for the Local route's session token: one row, one slot. */
    val localProfile: LocalGatewayProfile get() = local.copy(secretSlotId = id)

    /**
     * The one stored address, whichever addressed kind this row is. Both routes
     * are a URL, so both are persisted in one field rather than two that a row
     * could disagree with itself across.
     */
    internal val endpointUrl: String
        get() = when (kind) {
            ConnectionKind.Local -> local.baseUrl
            ConnectionKind.Remote, ConnectionKind.Ssh -> remote.baseUrl
        }

    /**
     * Human-readable, non-secret endpoint — Desktop's `connectionEndpoint`
     * (`apps/desktop/src/lib/connection-display.ts:61-75` @ `f82f2dba`).
     * Null when this row has not been given an endpoint yet.
     */
    val endpoint: String?
        get() = when (kind) {
            ConnectionKind.Ssh -> host.destination.takeIf(String::isNotBlank)
            ConnectionKind.Remote -> remote.baseUrl.trim().takeIf(String::isNotBlank)
            ConnectionKind.Local -> local.displayEndpoint
        }

    /** How this row proves who the user is. Never the secret, only the method. */
    val authModeLabel: String
        get() = when (kind) {
            ConnectionKind.Remote -> BROWSER_SIGN_IN
            ConnectionKind.Local -> SESSION_TOKEN
            ConnectionKind.Ssh -> when (host.authMethod) {
                AuthMethod.TailscaleSsh -> "Tailscale SSH"
                AuthMethod.Password -> "Password"
                AuthMethod.PrivateKey -> "Private key"
            }
        }

    /**
     * Whether the app-scoped route follower can bring this row up with nobody
     * present.
     *
     * A Remote row has a stored sign-in and a Local row a stored session token,
     * so each can be restored from disk — provided it names an address this app
     * can still use. Managed SSH's credential is created by the connection and
     * dies with it, so nothing is ever coming for it unattended.
     *
     * One rule, two readers: [com.hermesagent.mobile.data.connections.ConnectionSwitchController]
     * decides whether a switch is worth holding a pending badge for, and the
     * Gateways list decides whether a row that is now active has to explain why
     * nothing dialled. Restating it as a `kind ==` check in either place is how
     * the two drift the first time a kind is added.
     */
    val restorable: Boolean
        get() = when (kind) {
            ConnectionKind.Remote -> remote.isValid
            ConnectionKind.Local -> local.isValid
            ConnectionKind.Ssh -> false
        }

    companion object {
        const val BROWSER_SIGN_IN: String = "Browser sign-in"

        /**
         * The Local route's whole boundary: on loopback there is no TLS, no
         * sign-in and no host key, so the dashboard session token is what tells
         * this app apart from anything else on the phone.
         */
        const val SESSION_TOKEN: String = "Session token"
    }
}

/** The saved set plus which row this device is on. */
data class ConnectionRegistry(
    val connections: List<SavedConnection> = emptyList(),
    val activeId: String? = null,
) {
    val active: SavedConnection?
        get() = connections.firstOrNull { it.id == activeId } ?: connections.firstOrNull()
}

/**
 * Where the saved connections live. One writer, one store: the same DataStore
 * that already owns every non-secret connection preference.
 */
interface ConnectionRegistryStore {
    val connectionRegistry: Flow<ConnectionRegistry>

    /**
     * False when what is stored was written by a build this one cannot read, or
     * cannot be parsed at all. Every write is refused while it is false — see
     * [com.hermesagent.mobile.data.connections.ConnectionRegistryCodec.isWritable]
     * — so the surface has to say so rather than appear to save and not.
     */
    val connectionRegistryWritable: Flow<Boolean> get() = flowOf(true)

    /** Inserts a new row or replaces an existing one by id. Never changes which row is active. */
    suspend fun saveConnection(connection: SavedConnection)

    /**
     * Removes a row. Removing the active row moves the marker to the first
     * remaining row; removing the last row is refused, because this app always
     * has exactly one connection it is configured for.
     */
    suspend fun removeConnection(id: String)

    suspend fun setActiveConnection(id: String)
}

/** Desktop shows search once a registry gets long (`connection-display.ts:3` @ `f82f2dba`). */
const val CONNECTION_SEARCH_THRESHOLD: Int = 8

/**
 * Dedupe key for a Gateway URL — Desktop's `normalizeGatewayUrl`
 * (`connections-registry.tsx:90-92` @ `f82f2dba`): trim, drop trailing
 * slashes, lowercase. Deliberately looser than
 * `normalizeRemoteGatewayUrl`, which additionally refuses anything that is not
 * a usable HTTPS origin: two rows still collide before either is valid.
 */
fun normalizeGatewayUrl(url: String): String = url.trim().trimEnd('/').lowercase()

/**
 * Dedupe key for the one SSH destination field — Desktop's `sshCompositeKey`
 * (`connections-registry.tsx:99-118` @ `f82f2dba`): normalized to
 * `user@host:port` with the default port made explicit, so `box` and `box:22`
 * collide.
 */
fun sshCompositeKey(composite: String): String {
    val raw = composite.trim().lowercase()
    if (raw.isEmpty()) return ""
    val at = raw.lastIndexOf('@')
    val user = if (at > 0) raw.substring(0, at) else ""
    val rest = if (at >= 0) raw.substring(at + 1) else raw
    val port = PORT_SUFFIX.find(rest)
    val host = port?.groupValues?.get(1) ?: rest
    if (host.isEmpty()) return ""
    return "$user@$host:${port?.groupValues?.get(2) ?: DEFAULT_SSH_PORT}"
}

private val PORT_SUFFIX = Regex("^(.*):(\\d+)$")
private const val DEFAULT_SSH_PORT = "22"

/**
 * Dedupe key for a loopback Gateway address.
 *
 * Desktop allows exactly one local connection because its local runtime is the
 * one its own app manages (`connections-registry.tsx` `duplicateLocal`,
 * `en.ts:753` @ `f82f2dba`). Here the person can run more than one
 * `hermes serve` on this phone, on different ports, so the rule is per address
 * instead — but `127.0.0.1`, `localhost` and `[::1]` on one port are one
 * server, and collapsing them is what stops the same Hermes being saved three
 * times under three spellings. An address this app cannot use collides with
 * nothing.
 */
fun localGatewayKey(url: String): String {
    val address = normalizeLocalGatewayUrl(url)?.toHttpUrlOrNull() ?: return ""
    return "loopback:${address.port}${address.encodedPath.trimEnd('/')}"
}

/**
 * The row [candidate] collides with, or null.
 *
 * Desktop's rule (`connections-registry.tsx:120-168` @ `f82f2dba`), minus the
 * kinds Android does not ship: remote rows are duplicates when their normalized
 * URLs match; SSH rows are duplicates on `user@host:port` plus the remote
 * Hermes profile; Local rows are duplicates on the loopback address, which is
 * where this deviates from Desktop's one-local-connection rule and why
 * [localGatewayKey] says so.
 */
fun findDuplicateConnection(
    candidate: SavedConnection,
    connections: List<SavedConnection>,
): SavedConnection? = when (candidate.kind) {
    ConnectionKind.Remote -> {
        val key = normalizeGatewayUrl(candidate.remote.baseUrl)
        if (key.isEmpty()) {
            null
        } else {
            connections.firstOrNull {
                it.kind == ConnectionKind.Remote &&
                    it.id != candidate.id &&
                    normalizeGatewayUrl(it.remote.baseUrl) == key
            }
        }
    }

    ConnectionKind.Local -> {
        val key = localGatewayKey(candidate.local.baseUrl)
        if (key.isEmpty()) {
            null
        } else {
            connections.firstOrNull {
                it.kind == ConnectionKind.Local &&
                    it.id != candidate.id &&
                    localGatewayKey(it.local.baseUrl) == key
            }
        }
    }

    ConnectionKind.Ssh -> {
        val key = sshCompositeKey(candidate.host.destination)
        if (key.isEmpty()) {
            null
        } else {
            val profile = candidate.host.remoteHermesProfile.trim()
            connections.firstOrNull {
                it.kind == ConnectionKind.Ssh &&
                    it.id != candidate.id &&
                    sshCompositeKey(it.host.destination) == key &&
                    it.host.remoteHermesProfile.trim() == profile
            }
        }
    }
}

/**
 * One stable, human-readable order — Desktop's `sortConnectionsForDisplay`
 * (`connection-display.ts:11-23` @ `f82f2dba`), Local anchor included now that
 * a Local row can be created: a Hermes on *this* device sorts above every
 * gateway that is somewhere else, whatever either is called, because it is the
 * one row whose reachability the person controls from the phone in their hand.
 * The label comparison is numeric-aware and
 * case-insensitive, as Desktop's collator is, so `Gateway 2` sorts before
 * `Gateway 10`; the id breaks ties so the order never depends on input order.
 */
fun sortConnectionsForDisplay(connections: List<SavedConnection>): List<SavedConnection> =
    connections.sortedWith(
        compareByDescending<SavedConnection> { it.kind == ConnectionKind.Local }
            .thenBy(NATURAL_ORDER, SavedConnection::label)
            .thenBy(NATURAL_ORDER, SavedConnection::id),
    )

/**
 * Search the non-secret details a person can see or remember about a gateway —
 * Desktop's `connectionMatchesQuery` (`connection-display.ts:29-58` @
 * `f82f2dba`). Every whitespace-separated needle must match.
 */
fun connectionMatchesQuery(
    connection: SavedConnection,
    query: String,
    aliases: List<String> = emptyList(),
): Boolean {
    val needles = normalizeSearchText(query).trim().split(WHITESPACE).filter(String::isNotEmpty)
    if (needles.isEmpty()) return true
    val haystack = normalizeSearchText(
        buildList {
            add(connection.label)
            connection.endpoint?.let(::add)
            if (connection.kind == ConnectionKind.Ssh) {
                add(connection.host.username)
                add(connection.host.host)
                add(connection.host.port.toString())
                add(connection.host.remoteHermesProfile)
            } else {
                add(connection.remote.provider)
            }
            addAll(aliases)
        }.filter(String::isNotBlank).joinToString(" "),
    )
    return needles.all(haystack::contains)
}

private val WHITESPACE = Regex("\\s+")
private val COMBINING_MARKS = Regex("\\p{M}")

private fun normalizeSearchText(value: String): String =
    COMBINING_MARKS.replace(Normalizer.normalize(value, Normalizer.Form.NFKD), "").lowercase()

/**
 * Case-insensitive, digit-run aware comparison. `Intl.Collator` gives Desktop
 * this for free; on Android it has to be spelled out, and spelling it out is
 * also what makes it testable without a locale.
 */
private val NATURAL_ORDER: Comparator<String> = Comparator { left, right ->
    var l = 0
    var r = 0
    var verdict = 0
    while (verdict == 0 && l < left.length && r < right.length) {
        val leftDigit = left[l].isDigit()
        if (leftDigit && right[r].isDigit()) {
            val leftEnd = left.digitRunEnd(l)
            val rightEnd = right.digitRunEnd(r)
            val leftRun = left.substring(l, leftEnd).trimStart('0')
            val rightRun = right.substring(r, rightEnd).trimStart('0')
            verdict = when {
                leftRun.length != rightRun.length -> leftRun.length - rightRun.length
                else -> leftRun.compareTo(rightRun)
            }
            l = leftEnd
            r = rightEnd
        } else {
            verdict = left[l].lowercaseChar().compareTo(right[r].lowercaseChar())
            l++
            r++
        }
    }
    if (verdict != 0) verdict else (left.length - l) - (right.length - r)
}

private fun String.digitRunEnd(from: Int): Int {
    var end = from
    while (end < length && this[end].isDigit()) end++
    return end
}
