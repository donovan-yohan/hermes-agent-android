package com.hermesagent.mobile.data.gateway

import java.util.Base64
import kotlinx.serialization.json.*

/** Endpoint-local owner bindings; keys are injective tuples, never backend identifiers.
 * Owner identity is confirmed by runtime.describe / a captured owner snapshot,
 * not by whichever sidebar happens to be visible. */
internal class GatewaySessionIdentity(private var endpointNamespace: String = "isolated") {
    data class Address(val ownerId: String, val profile: String?, val rawId: String)
    private val lock = Any()
    private val owners = mutableMapOf<String, String>()
    private val addresses = mutableMapOf<String, Address>()

    fun bindEndpoint(namespace: String) = synchronized(lock) {
        require(namespace.isNotBlank())
        if (namespace != endpointNamespace) { addresses.clear(); endpointNamespace = namespace }
    }

    fun bind(profile: String?, ownerId: String, name: String? = null) = synchronized(lock) {
        require(ownerId.isNotBlank())
        val alias = profile.orEmpty()
        if (owners[alias]?.let { it != ownerId } == true) {
            throw GatewayRpcException("The session owner changed. Reconnect, then try again.")
        }
        owners[alias] = ownerId
        name?.takeIf(String::isNotBlank)?.let {
            if (owners[it]?.let { previous -> previous != ownerId } == true) {
                throw GatewayRpcException("The Gateway returned a different session owner.")
            }
            owners[it] = ownerId
        }
    }

    fun qualify(rawId: String, profile: String?, actualName: String? = null): String = synchronized(lock) {
        val owner = owners[profile.orEmpty()] ?: throw GatewayRpcException("Reopen this profile before its sessions.")
        if (actualName != null) bind(profile, owner, actualName)
        val key = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(
            JsonArray(listOf(JsonPrimitive(endpointNamespace), JsonPrimitive(owner), JsonPrimitive(rawId))).toString().toByteArray(Charsets.UTF_8))
        addresses[key] = Address(owner, actualName ?: profile, rawId)
        key
    }

    fun address(key: String, profile: String? = null): Address? = synchronized(lock) {
        val address = addresses[key] ?: run {
            if (!key.startsWith(PREFIX)) return@synchronized null
            val tuple = runCatching {
                Json.parseToJsonElement(Base64.getUrlDecoder().decode(key.removePrefix(PREFIX)).toString(Charsets.UTF_8)) as? JsonArray
            }.getOrNull()
            val values = tuple?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
            if (values?.size != 3 || values[0] != endpointNamespace ||
                values[1] !in owners.values || values[2].isBlank()) {
                throw GatewayRpcException("Reopen this session before continuing.")
            }
            val route = if (profile != null) profile else owners.entries.firstOrNull { it.value == values[1] }?.key?.takeIf(String::isNotEmpty)
            Address(values[1], route, values[2]).also { addresses[key] = it }
        }
        if (profile != null && owners[profile]?.let { it != address.ownerId } != false) {
            throw GatewayRpcException("This session belongs to another profile.")
        }
        address
    }

    fun ownerId(profile: String?): String? = synchronized(lock) { owners[profile.orEmpty()] }

    fun projectMetadata(row: JsonObject, profile: String?): JsonObject = projectFields(row, profile, SESSION_FIELDS - "id")
    fun projectRow(row: JsonObject, profile: String?): JsonObject = projectFields(row, profile, SESSION_FIELDS)
    private fun projectFields(row: JsonObject, profile: String?, fields: Set<String>): JsonObject {
        val actual = (row["profile"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: (row["profile_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return buildJsonObject {
            row.forEach { (key, value) ->
                if (key in fields && value is JsonPrimitive && value.isString && value.content.isNotEmpty()) {
                    put(key, qualify(value.content, profile, actual))
                } else put(key, value)
            }
            (actual ?: profile)?.let { put("profile", it) }
            (row["stored_session_id"] ?: row["id"] ?: row["session_id"])?.let {
                put("backend_session_id", it)
            }
            row["lineage_root"]?.let { put("backend_lineage_root", it) }
        }
    }

    companion object {
        private const val PREFIX = "hermes-session-v2:"
        private val SESSION_FIELDS = setOf("id", "session_id", "stored_session_id", "session_key", "resumed",
            "lineage_root", "lineage_root_id", "root_session_id", "resolved_id", "parent_session_id", "target_session_id")
    }
}

/** Implemented by the single session adapter shared by repository and plugins. */
internal interface AuthorityGatewayRpcClient {
    val authoritySessionIdentity: GatewaySessionIdentity?
    fun bindSessionEndpoint(namespace: String)
    fun bindSessionOwner(profile: String, identity: GatewaySessionIdentity)
}

/** Responses retain both request identity and originating owner channel. */
internal interface GatewayOwnedServerRequestResponder {
    suspend fun respondToServerRequestAtOwner(runtimeId: String?, id: String, result: JsonObject,
        dispatch: (() -> Boolean) -> Boolean)
    suspend fun failServerRequestAtOwner(runtimeId: String?, id: String, code: Int, message: String,
        dispatch: (() -> Boolean) -> Boolean)
}
