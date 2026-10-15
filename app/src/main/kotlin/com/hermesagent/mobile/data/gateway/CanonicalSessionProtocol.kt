package com.hermesagent.mobile.data.gateway

import java.util.UUID
import kotlinx.serialization.json.*

/** One authenticated socket's authority adapter. Sources:
 * apps/desktop/src/api/canonical-protocol.ts:248-274,401-435,606-623 and
 * gateway/session_controls.py:345-356,420-473 @ 379cf1220fe289b5ecfe8bcf61cd4d5126e140cd.
 * Route names and version prose never select this protocol. */
internal class CanonicalSessionProtocol(identity: GatewaySessionIdentity = GatewaySessionIdentity()) {
    var sessionIdentity: GatewaySessionIdentity = identity
        private set
    private var fixedProfile: String? = null
    fun bindOwner(profile: String, identity: GatewaySessionIdentity) {
        synchronized(stateLock) {
            check(snapshots.isEmpty()) { "Cannot move an attached session to another owner channel." }
            check(fixedProfile == null || fixedProfile == profile)
            fixedProfile = profile
            sessionIdentity = identity
            descriptors.clear()
        }
    }
    private data class Owner(val profile: String, val sessionId: String)
    private data class Snapshot(val revision: Long, val generation: Long, val replayEpoch: String,
        val lastSequence: Long, val subscriptionId: String, val storedId: String)
    private val stateLock = Any()
    private val descriptorLocks = KeyedMutex<String>()
    private val descriptors = mutableMapOf<String, JsonObject>()
    private val snapshots = mutableMapOf<Owner, Snapshot>()
    private val creates = mutableMapOf<JsonObject, String>()
    @Volatile var announced: Boolean = false
        private set

    fun observeReady(type: String, payload: JsonElement) {
        if (type == "gateway.ready" && (payload as? JsonObject)?.get("session_authority") == JsonPrimitive(true)) {
            announced = true
        }
    }

    suspend fun request(method: String, supplied: JsonObject,
        send: suspend (String, JsonObject) -> JsonElement): JsonElement {
        val bound = fixedProfile
        if (bound != null && supplied.text("profile")?.let { it != bound } == true) {
            throw GatewayRpcException("This session channel belongs to another profile.")
        }
        val input = if (bound == null) supplied else JsonObject(supplied + ("profile" to JsonPrimitive(bound)))
        if (!announced) return send(method, input)
        ensureOwner(input.text("profile"), send)
        val address = input.text("session_id")?.let { sessionIdentity.address(it, input.text("profile")) }
        val params = if (address != null) buildJsonObject {
            input.forEach { (key, value) -> put(key, value) }
            put("session_id", address.rawId)
            address.profile?.let { put("profile", it) }
        } else input
        val profile = params.text("profile").orEmpty()
        ensureOwner(params.text("profile"), send)
        if (method == "runtime.describe") return synchronized(stateLock) { descriptors.getValue(profile) }
        return when (method) {
            "session.create" -> {
                val prepared = prepareCreate(params)
                val result = send(method, prepared).objectResult()
                val projected = snapshot(result, profile, requireDisplay = false)
                synchronized(stateLock) { creates.entries.removeAll { it.value == prepared.text("request_id") } }
                projected
            }
            "session.resume", "session.activate", "session.history" -> {
                val prepared = buildJsonObject {
                    put("session_id", params["session_id"] ?: unusable())
                    params["profile"]?.let { put("profile", it) }
                    put("display_projection", true)
                }
                val result = snapshot(send("session.resume", prepared).objectResult(), profile, requireDisplay = true)
                if (method == "session.history") buildJsonObject {
                    put("messages", result.getValue("messages")); put("count", result.getValue("message_count"))
                } else result
            }
            "session.list", "session.active_list" -> {
                val prepared = if (method == "session.list") JsonObject(params + ("scope" to JsonPrimitive("stored"))) else params
                val result = send(method, prepared).objectResult()
                val rows = result["sessions"] as? JsonArray ?: unusable()
                JsonObject(result + ("sessions" to JsonArray(rows.map {
                    sessionIdentity.projectRow(it as? JsonObject ?: unusable(), params.text("profile"))
                })))
            }
            "projects.tree", "projects.project_sessions" -> projectMembership(send(method, params), params.text("profile"))
            "session.interrupt", "session.steer", "session.redirect" -> {
                val owner = Owner(profile, params.text("session_id") ?: unusable())
                val state = synchronized(stateLock) { snapshots[owner] }
                    ?: throw GatewayRpcException("Reopen this session before controlling its turn.")
                val prepared = JsonObject(params + ("execution_generation" to JsonPrimitive(state.generation)))
                send(method, prepared)
            }
            else -> send(method, params)
        }
    }

    private suspend fun ensureOwner(route: String?, send: suspend (String, JsonObject) -> JsonElement) {
        val profile = route.orEmpty()
        descriptorLocks.withLock(profile) {
            if (synchronized(stateLock) { profile !in descriptors }) {
                val description = send("runtime.describe", buildJsonObject {
                    route?.let { put("profile", it) }
                }) as? JsonObject ?: unusable()
                val capabilities = (description["capabilities"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                val owner = description.text("profile_id")?.takeIf(String::isNotBlank) ?: unusable()
                if ("durable-admission-v1" !in capabilities || "event-replay-v1" !in capabilities) unusable()
                sessionIdentity.bind(route, owner, description.text("profile_name"))
                synchronized(stateLock) { descriptors[profile] = description }
            }
        }
    }

    private fun prepareCreate(input: JsonObject): JsonObject {
        val unsupported = input.keys - CREATE_INPUTS
        if (unsupported.isNotEmpty()) throw GatewayRpcException("These session options are unavailable on this Gateway.")
        val fields = buildJsonObject {
            input.filterKeys { it in CREATE_FIELDS }.forEach { (key, value) -> put(key, value) }
            input["reasoning_effort"]?.let { put("reasoning", it) }
            if (input["service_tier"] != null) put("service_tier", input.getValue("service_tier"))
            else when (input["fast"]) {
                JsonPrimitive(true) -> put("service_tier", "priority")
                JsonPrimitive(false) -> put("service_tier", "normal")
                else -> Unit
            }
            if (input.text("cwd")?.isNotEmpty() == true && "cwd_explicit" !in input) put("cwd_explicit", true)
        }
        val id = input.text("request_id") ?: synchronized(stateLock) { creates.getOrPut(fields) { UUID.randomUUID().toString() } }
        return buildJsonObject {
            fields.forEach { (key, value) -> put(key, value) }
            put("source", "gui"); put("request_id", id)
        }
    }

    private fun snapshot(value: JsonObject, profile: String, requireDisplay: Boolean): JsonObject {
        val sid = value.text("session_id")?.takeIf(String::isNotBlank) ?: unusable()
        val stored = value.text("stored_session_id")?.takeIf(String::isNotBlank) ?: unusable()
        val info = value["info"] as? JsonObject ?: unusable()
        if (info.text("desktop_protocol") != "hermes-gateway-v1") unusable()
        val revision = value.number("revision")?.takeIf { it >= 0 } ?: unusable()
        val generation = value.number("execution_generation")?.takeIf { it >= 0 } ?: unusable()
        val epoch = value.text("replay_epoch")?.takeIf(String::isNotBlank) ?: unusable()
        val sequence = value.number("last_sequence")?.takeIf { it >= 0 } ?: unusable()
        val subscription = value.text("subscription_id")?.takeIf(String::isNotBlank) ?: unusable()
        val messages = value["display_messages"] as? JsonArray ?: (value["messages"] as? JsonArray)
            ?.takeIf { !requireDisplay && it.isEmpty() } ?: unusable()
        synchronized(stateLock) {
            snapshots[Owner(profile, sid)] = Snapshot(revision, generation, epoch, sequence, subscription, stored)
        }
        val route = profile.takeIf(String::isNotEmpty)
        val actual = info.text("profile_name")
        val ownerId = synchronized(stateLock) { descriptors.getValue(profile).text("profile_id") } ?: unusable()
        if (info.text("profile_id")?.let { it != ownerId } == true) unusable()
        sessionIdentity.bind(route, ownerId, actual)
        return JsonObject(sessionIdentity.projectRow(value, route) + mapOf(
            "messages" to messages,
            "session_key" to JsonPrimitive(sessionIdentity.qualify(stored, route, actual)),
            "info" to JsonObject(sessionIdentity.projectRow(info, route) + value.filterKeys { it in SNAPSHOT_INFO_FIELDS } +
                ("stored_session_id" to JsonPrimitive(sessionIdentity.qualify(stored, route, actual))) +
                ("backend_session_id" to JsonPrimitive(stored))),
        ))
    }

    private fun projectMembership(value: JsonElement, profile: String?): JsonElement {
        fun visit(element: JsonElement, sessionRows: Boolean = false): JsonElement = when (element) {
            is JsonArray -> JsonArray(element.map { visit(it, sessionRows) })
            is JsonObject -> {
                val base = if (sessionRows) sessionIdentity.projectRow(element, profile) else element
                JsonObject(base.mapValues { (key, item) ->
                    if (key in setOf("sessions", "preview_sessions", "recent_sessions")) visit(item, true)
                    else if (item is JsonArray || item is JsonObject) visit(item) else item
                })
            }
            else -> element
        }
        return visit(value)
    }

    private fun JsonElement.objectResult(): JsonObject = this as? JsonObject ?: unusable()
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    private fun unusable(): Nothing = throw GatewayRpcException("The Gateway returned an unusable session response.")

    companion object {
        private val CREATE_FIELDS = setOf("cwd", "model", "provider", "toolsets", "reasoning", "profile", "title",
            "hidden", "follow_profile_config", "service_tier", "cwd_explicit")
        private val CREATE_INPUTS = CREATE_FIELDS + setOf("request_id", "source", "reasoning_effort", "fast")
        private val SNAPSHOT_INFO_FIELDS = setOf("replay_epoch", "last_sequence", "execution_generation", "running")
    }
}
