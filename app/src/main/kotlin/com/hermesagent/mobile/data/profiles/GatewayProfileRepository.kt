package com.hermesagent.mobile.data.profiles

import com.hermesagent.mobile.data.gateway.GatewayRpcClient
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a profile surface may ask for. Read-only: editing profiles is not ported. */
interface ProfileRepository {
    val roster: StateFlow<ProfileRosterState>

    /** Best-effort. A failure leaves the last good roster in place. */
    suspend fun refreshProfiles(): Boolean

    /**
     * The Gateway connection changed. A new connection strands in-flight
     * answers; losing one drops a roster that described a Gateway that is gone.
     */
    fun connectionChanged(state: GatewayProfileConnectionState) = Unit
}

/** What a connection edge means for a cached roster. */
enum class GatewayProfileConnectionState {
    /** A new or re-dialing connection: strand answers, keep the last good roster. */
    Changed,

    /** The Gateway is gone: the roster described something that no longer exists. */
    Gone,
}

/**
 * `profiles.list` over the live Gateway.
 *
 * The handler sits in the Gateway's slow-method lane
 * (`tui_gateway/server.py:297-305` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`)
 * because it walks each profile's skill tree and opens each profile's
 * `state.db`. So it gets its own generous budget at the RPC boundary
 * (`gatewayRpcTimeoutMillis`), matching Desktop's own
 * `STARTUP_REQUEST_TIMEOUT_MS` for `/api/profiles`
 * (`apps/desktop/src/hermes.ts:77-88`), and one refresh at a time — never a
 * lock any session traffic waits behind.
 *
 * `include_sessions` is off: the per-profile last-session preview is the only
 * expensive half of that handler and nothing here renders it.
 */
internal class GatewayProfileRepository(
    private val rpc: () -> GatewayRpcClient?,
    private val cache: ProfileRosterCache = ProfileRosterCache(),
) : ProfileRepository {

    private val refreshMutex = Mutex()

    override val roster: StateFlow<ProfileRosterState> = cache.state

    /**
     * A new or reconnecting Gateway strands in-flight answers; only losing the
     * connection outright drops the roster. A reconnect deliberately keeps the
     * last good one — the rail is the only way out of a profile scope, and
     * blanking it for the length of a slow-lane call would strand the reader in
     * a scope they cannot leave.
     */
    override fun connectionChanged(state: GatewayProfileConnectionState) {
        when (state) {
            GatewayProfileConnectionState.Gone -> cache.clear()
            GatewayProfileConnectionState.Changed -> cache.invalidate()
        }
    }

    override suspend fun refreshProfiles(): Boolean = refreshMutex.withLock {
        val client = rpc() ?: return false
        val epoch = cache.currentEpoch()
        val result = try {
            client.request("profiles.list", buildJsonObject { put("include_sessions", JsonPrimitive(false)) })
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return false
        }
        val rows = parseProfileList(result) ?: return false
        cache.publish(epoch, rows)
    }
}

/**
 * Parses `profiles.list` (`tui_gateway/methods_profiles.py:205-249`).
 *
 * A row without a usable `name` is dropped rather than invented; a malformed
 * envelope answers null so the caller keeps its last good roster.
 */
internal fun parseProfileList(result: JsonElement): List<HermesProfile>? {
    val root = result as? JsonObject ?: return null
    val rows = root["profiles"] as? JsonArray ?: return null
    return rows.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val name = row.text("name")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        HermesProfile(
            name = name,
            path = row.text("path").orEmpty(),
            isDefault = row.flag("is_default"),
            model = row.text("model")?.trim()?.takeIf(String::isNotEmpty),
            provider = row.text("provider")?.trim()?.takeIf(String::isNotEmpty),
            description = row.text("description").orEmpty().trim(),
            displayName = row.text("display_name").orEmpty().trim(),
            skillCount = row.text("skill_count")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            hasEnv = row.flag("has_env"),
            uiMetaColor = (row["ui_meta"] as? JsonObject)?.text("color"),
            hasAvatar = row.flag("has_avatar"),
        )
    }
}

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

private fun JsonObject.flag(name: String): Boolean = when (val value = this[name]) {
    is JsonPrimitive -> value.content.equals("true", ignoreCase = true) || value.content == "1"
    else -> false
}

/** The roster a ViewModel or preview gets when no Gateway profile source is wired. */
object NoProfileRepository : ProfileRepository {
    private val empty = kotlinx.coroutines.flow.MutableStateFlow(ProfileRosterState())
    override val roster: StateFlow<ProfileRosterState> = empty
    override suspend fun refreshProfiles(): Boolean = false
}
