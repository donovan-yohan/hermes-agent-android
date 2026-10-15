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
        val lastSequence: Long, val subscriptionId: String, val storedId: String, val authorityEpoch: Long)
    private val stateLock = Any()
    private val descriptorLocks = KeyedMutex<String>()
    private val descriptors = mutableMapOf<String, JsonObject>()
    private val snapshots = mutableMapOf<Owner, Snapshot>()
    private data class MutationKey(val owner: Owner, val operation: String, val payload: JsonObject)
    private data class ModelConfirmation(val token: String, var sent: Boolean = false)
    private val mutations = mutableMapOf<MutationKey, JsonObject>()
    private val modelConfirmations = mutableMapOf<Pair<Owner, JsonObject>, ModelConfirmation>()
    private val mutationLocks = KeyedMutex<Owner>()
    private val preSnapshotEvents = mutableListOf<GatewayEvent>()
    private val prompts = mutableMapOf<Pair<Owner, String>, JsonObject>()
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
        mutation(method, params)?.let { (operation, payload) ->
            return executeMutation(method, params, operation, payload, send)
        }
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
            "approval.respond", "clarify.respond" -> {
                val owner = owner(profile, params.text("session_id") ?: unusable())
                val id = params.text("prompt_id") ?: params.text("request_id") ?: unusable()
                val prompt = synchronized(stateLock) {
                    prompts[owner to id]?.takeIf { it.number("execution_generation") == snapshots[owner]?.generation }
                } ?: throw GatewayRpcException("This prompt is no longer available. Reopen this session.")
                if (params.number("execution_generation")?.let { it != prompt.number("execution_generation") } == true)
                    throw GatewayRpcException("This prompt is no longer available. Reopen this session.")
                val field = if (method == "approval.respond") "choice" else "answer"
                val result = send(method, buildJsonObject {
                    put("session_id", owner.sessionId); put("prompt_id", id)
                    put("execution_generation", prompt.getValue("execution_generation")); put(field, params[field] ?: unusable())
                    params["profile"]?.let { put("profile", it) }
                }).objectResult()
                if (result.text("prompt_id") != id || result.text("status") !in setOf("resolved", "already_resolved"))
                    throw GatewayRpcException("The Gateway returned an unusable prompt response.", requestMayHaveBeenAccepted = true)
                synchronized(stateLock) { prompts.remove(owner to id) }
                result
            }
            "prompt.submit" -> {
                val owner = owner(profile, params.text("session_id") ?: unusable())
                synchronized(stateLock) { snapshots[owner] } ?: throw GatewayRpcException("Reopen this session before sending a message.")
                val truncated = params.keys.any { it in TRUNCATE_KEYS }
                if (truncated) {
                    val row = params.number("truncate_before_row_id")?.takeIf { it > 0 }
                    if (row != null) executeMutation("session.rewind", params, "rewind", buildJsonObject { put("target_message_id", row) }, send)
                    else if (params.keys.any { it.startsWith("truncate_before_") })
                        throw GatewayRpcException("This message is not saved yet; wait for it to settle, then edit it again.")
                }
                synchronized(stateLock) { mutations.keys.removeAll { it.owner == owner } }
                val plain = JsonObject(params.filterKeys { it !in TRUNCATE_KEYS })
                val prepared = if (plain.text("submission_id").isNullOrBlank() && plain.text("input_id").isNullOrBlank())
                    JsonObject(plain + ("submission_id" to JsonPrimitive(UUID.randomUUID().toString()))) else plain
                admission(send(method, prepared).objectResult(), prepared, owner)
            }
            "session.interrupt", "session.steer", "session.redirect" -> {
                val owner = owner(profile, params.text("session_id") ?: unusable())
                val state = synchronized(stateLock) { snapshots[owner] }
                    ?: throw GatewayRpcException("Reopen this session before controlling its turn.")
                if (params.number("execution_generation")?.let { it != state.generation } == true)
                    throw GatewayRpcException("This turn is no longer available. Reopen this session.")
                val prepared = JsonObject(params + ("execution_generation" to JsonPrimitive(state.generation)))
                send(method, prepared)
            }
            else -> send(method, params)
        }
    }

    private fun mutation(method: String, params: JsonObject): Pair<String, JsonObject>? {
        fun payload(operation: String, field: String, value: JsonElement?) = operation to buildJsonObject {
            value?.let { put(field, it) }
        }
        return when (method) {
            "session.title" -> payload("rename", "title", params["title"])
            "session.archive" -> payload("archive", "archived", params["archived"])
            "session.delete" -> "delete" to buildJsonObject { }
            "session.rewind" -> payload("rewind", "target_message_id", params["target_message_id"])
            "session.compress" -> payload("compress", "focus", params["focus_topic"])
            "session.branch" -> {
                val boundary = params.number("through_message_id")?.takeIf { it > 0 }
                if (boundary == null && "count" in params)
                    throw GatewayRpcException("This message is not saved yet; wait for it to settle, then branch from it again.")
                "branch" to buildJsonObject { boundary?.let { put("through_message_id", it) } }
            }
            "session.branch_stored", "session.branch_whole" -> payload("branch", "title", params["title"])
            "session.workspace.move", "session.cwd.set" -> payload("workspace", "cwd", params["cwd"])
            "config.set" -> when (params.text("key")) {
                "reasoning" -> if (params.text("scope") != "global" && params.text("value") in REASONING_EFFORTS)
                    payload("reasoning", "effort", params["value"]) else null
                "model" -> {
                    val words = params.text("value").orEmpty().trim().split(Regex("\\s+"))
                    val flags = if (words.lastOrNull() == "--session") words.dropLast(1) else words
                    if (flags.size == 3 && flags[1] == "--provider" && !flags[0].startsWith("-") && !flags[2].startsWith("-"))
                        "model" to buildJsonObject { put("model", flags[0]); put("provider", flags[2]) } else null
                }
                else -> null
            }
            "slash.exec" -> {
                val command = params.text("command").orEmpty().trim().trimStart('/')
                val name = command.substringBefore(' ')
                val arg = command.substringAfter(' ', "").trim()
                when (name) {
                    "branch" -> payload("branch", "title", arg.takeIf(String::isNotEmpty)?.let(::JsonPrimitive))
                    "compress" -> payload("compress", "focus", arg.takeIf(String::isNotEmpty)?.let(::JsonPrimitive))
                    "model" -> if (arg.isNotBlank() && !arg.startsWith('-') && !arg.any(Char::isWhitespace))
                        payload("model", "model", JsonPrimitive(arg)) else null
                    else -> null
                }
            }
            else -> null
        }
    }

    private suspend fun executeMutation(method: String, params: JsonObject, operation: String, suppliedPayload: JsonObject,
        send: suspend (String, JsonObject) -> JsonElement): JsonObject {
        val sid = params.text("parent_session_id") ?: params.text("session_id") ?: params.text("session_key") ?: unusable()
        val owner = owner(params.text("profile").orEmpty(), sid)
        return mutationLocks.withLock(owner) {
            val payload = synchronized(stateLock) {
                val confirmation = modelConfirmations[owner to suppliedPayload]
                if (operation == "model" && confirmation != null &&
                    (params["confirm_expensive_model"] == JsonPrimitive(true) || confirmation.sent)) {
                    confirmation.sent = true
                    JsonObject(suppliedPayload + ("confirm" to JsonPrimitive(confirmation.token)))
                } else suppliedPayload
            }
            val key = MutationKey(owner, operation, payload)
            val prepared = synchronized(stateLock) {
                mutations.keys.removeAll { it.owner == owner && it != key }
                mutations.getOrPut(key) {
                    val state = snapshots[owner] ?: throw GatewayRpcException("Reopen this session before editing it.")
                    buildJsonObject {
                        put("session_id", sid); put("request_id", UUID.randomUUID().toString()); put("expected_revision", state.revision)
                        if (operation in GENERATION_MUTATIONS) put("expected_generation", state.generation)
                        put("operation", operation); put("payload", payload)
                        params["profile"]?.let { put("profile", it) }
                    }
                }
            }
            fun uncertain(): Nothing = throw GatewayRpcException("The Gateway returned an unusable mutation receipt.", requestMayHaveBeenAccepted = true)
            val value = try { send("session.mutate", prepared) as? JsonObject ?: uncertain() }
            catch (failure: GatewayRpcError) {
                synchronized(stateLock) { mutations.remove(key) }
                throw failure
            }
            val readOnly = (operation == "model" && value.text("status") == "confirmation_required") ||
                (operation == "compress" && value.text("status") in setOf("preview", "noop"))
            if (value.text("session_id") != sid || value.text("operation") != operation ||
                (value.number("revision")?.let { it >= 0 } != true && !readOnly)) uncertain()
            if (operation == "branch" && (value.text("branched_session_id").isNullOrBlank() || value.number("copied_messages")?.let { it >= 0 } != true)) uncertain()
            if (value.text("status") == "confirmation_required" && value.text("confirm").isNullOrBlank()) uncertain()
            synchronized(stateLock) {
                mutations.remove(key)
                value.number("revision")?.let { revision -> snapshots[owner]?.let { previous ->
                    snapshots[owner] = previous.copy(revision = maxOf(previous.revision, revision))
                } }
                if (operation == "model") {
                    if (payload.text("confirm") != null) modelConfirmations.remove(owner to suppliedPayload)
                    if (value.text("status") == "confirmation_required") {
                        val token = value.text("confirm")?.takeIf(String::isNotBlank) ?: uncertain()
                        modelConfirmations[owner to suppliedPayload] = ModelConfirmation(token)
                    }
                }
            }
            val route = owner.profile.takeIf(String::isNotEmpty)
            val projected = sessionIdentity.projectMetadata(value, route)
            val result = buildJsonObject {
                projected.forEach { (key, item) -> put(key, item) }
                if (value.text("status") == "confirmation_required") put("confirm_required", true)
                else put("ok", true)
                if (operation == "reasoning") value["effort"]?.let { put("value", it) }
                if (operation == "branch") {
                    val child = value.text("branched_session_id")?.takeIf(String::isNotBlank) ?: unusable()
                    val childKey = sessionIdentity.qualify(child, route)
                    put("session_id", childKey); put("stored_session_id", childKey)
                    put("parent_session_id", sessionIdentity.qualify(sid, route))
                    value["copied_messages"]?.let { put("message_count", it) }
                }
                if (method == "slash.exec") {
                    put("type", "exec")
                    put("output", value.text("confirm_message") ?: when (operation) {
                        "model" -> "model: ${value.text("model").orEmpty()}"
                        "branch" -> "branch: ${value.text("branched_session_id").orEmpty()}"
                        "compress" -> if (value.text("status") == "preview") (value["lines"] as? JsonArray).orEmpty()
                            .mapNotNull { (it as? JsonPrimitive)?.content }.joinToString("\n") else "compress: ${value.text("target_session_id") ?: sid}"
                        else -> "$operation: ok"
                    })
                }
            }
            if (operation == "compress" && value.text("status") != "preview") {
                val resumed = snapshot(send("session.resume", buildJsonObject {
                    put("session_id", sid); put("display_projection", true); params["profile"]?.let { put("profile", it) }
                }).objectResult(), owner.profile, requireDisplay = true)
                JsonObject(result + mapOf("messages" to resumed.getValue("messages"), "info" to resumed.getValue("info")))
            } else result
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
        val authority = value.number("authority_epoch")?.takeIf { it >= 0 } ?: unusable()
        val epoch = value.text("replay_epoch")?.takeIf(String::isNotBlank) ?: unusable()
        val sequence = value.number("last_sequence")?.takeIf { it >= 0 } ?: unusable()
        val subscription = value.text("subscription_id")?.takeIf(String::isNotBlank) ?: unusable()
        val messages = value["display_messages"] as? JsonArray ?: (value["messages"] as? JsonArray)
            ?.takeIf { !requireDisplay && it.isEmpty() } ?: unusable()
        val route = profile.takeIf(String::isNotEmpty)
        val actual = info.text("profile_name")
        val ownerId = synchronized(stateLock) { descriptors.getValue(profile).text("profile_id") } ?: unusable()
        if (info.text("profile_id")?.let { it != ownerId } == true) unusable()
        sessionIdentity.bind(route, ownerId, actual)
        synchronized(stateLock) {
            val key = owner(profile, sid)
            val previous = snapshots[key]
            val stale = previous != null && previous.authorityEpoch == authority && previous.replayEpoch == epoch &&
                (previous.lastSequence > sequence || previous.generation > generation)
            snapshots[key] = if (stale) previous!!.copy(subscriptionId = subscription) else
                Snapshot(revision, generation, epoch, sequence, subscription, stored, authority)
            if (!stale) {
                prompts.keys.removeAll { it.first == key }
                (value["prompts"] as? JsonArray).orEmpty().forEach { element ->
                    val prompt = element as? JsonObject ?: unusable()
                    val id = prompt.text("prompt_id") ?: unusable()
                    if (prompt.number("execution_generation") != generation) unusable()
                    prompts[key to id] = prompt
                }
            }
        }
        synchronized(stateLock) {
            val waiting = preSnapshotEvents.filter { it.runtimeSessionId == sid }
            preSnapshotEvents.removeAll(waiting.toSet())
            waiting.forEach(::event)
        }
        val current = synchronized(stateLock) { snapshots.getValue(owner(profile, sid)) }
        val currentPrompts = synchronized(stateLock) { JsonArray(prompts.filterKeys { it.first == owner(profile, sid) }.values.toList()) }
        val currentInfo = mapOf("execution_generation" to JsonPrimitive(current.generation), "last_sequence" to JsonPrimitive(sequence))
        return JsonObject(sessionIdentity.projectRow(value, route) + currentInfo + mapOf(
            "revision" to JsonPrimitive(current.revision), "prompts" to currentPrompts,
            "messages" to messages,
            "session_key" to JsonPrimitive(sessionIdentity.qualify(stored, route, actual)),
            "info" to JsonObject(sessionIdentity.projectRow(info, route) + value.filterKeys { it in SNAPSHOT_INFO_FIELDS } + currentInfo +
                ("stored_session_id" to JsonPrimitive(sessionIdentity.qualify(stored, route, actual))) +
                ("backend_session_id" to JsonPrimitive(stored))),
        ))
    }

    private fun owner(profile: String, sid: String): Owner = synchronized(stateLock) {
        val confirmed = descriptors[profile]?.text("profile_id") ?: unusable()
        val route = descriptors.entries.first { it.value.text("profile_id") == confirmed }.key
        Owner(route, sid)
    }

    fun serverRequest(request: GatewayServerRequest): GatewayServerRequest {
        if (!announced || request.runtimeSessionId == null) return request
        val route = fixedProfile
        if (sessionIdentity.ownerId(route) == null) return request.copy(authorityNative = true)
        return request.copy(runtimeSessionId = sessionIdentity.qualify(request.runtimeSessionId, route), authorityNative = true)
    }

    /** Applies per-owner execution/replay fences before any consumer sees a frame. */
    fun event(event: GatewayEvent): GatewayEvent? {
        if (!announced || event.runtimeSessionId == null) return event
        val payload = event.payload as? JsonObject ?: JsonObject(emptyMap())
        return synchronized(stateLock) {
            val matching = snapshots.keys.filter { it.sessionId == event.runtimeSessionId }
            val owner = matching.singleOrNull()
            val route = owner?.profile ?: fixedProfile
            if (owner == null) {
                if (preSnapshotEvents.size >= 1024) throw GatewayRpcException("The Gateway event stream exceeded its safe buffer.")
                preSnapshotEvents += event
            }
            val generation = event.executionGeneration ?: payload.number("execution_generation")
            if (owner != null) {
                val previous = snapshots.getValue(owner)
                if (event.authorityEpoch?.let { it < previous.authorityEpoch } == true ||
                    generation?.let { it < previous.generation } == true) return@synchronized null
                if (event.replayEpoch != null && event.replayEpoch != previous.replayEpoch ||
                    event.seq?.let { it > previous.lastSequence + 1 } == true) {
                    return@synchronized event.copy(type = "session.replay_gap", payload = JsonObject(emptyMap()),
                        runtimeSessionId = sessionIdentity.qualify(event.runtimeSessionId, route), canonical = true)
                }
                if (event.seq?.let { it <= previous.lastSequence } == true) return@synchronized null
                if (generation != null && generation > previous.generation) prompts.keys.removeAll { it.first == owner }
                snapshots[owner] = previous.copy(generation = generation ?: previous.generation,
                    authorityEpoch = event.authorityEpoch ?: previous.authorityEpoch,
                    lastSequence = event.seq ?: previous.lastSequence,
                    revision = maxOf(previous.revision, payload.number("revision") ?: previous.revision))
                payload.text("prompt_id")?.let { id ->
                    when (event.type) {
                        "approval.request", "clarify.request" -> prompts[owner to id] = JsonObject(payload +
                            ("execution_generation" to JsonPrimitive(generation ?: previous.generation)))
                        "approval.settled", "clarify.settled" -> prompts.remove(owner to id)
                    }
                }
            }
            // A descriptor/channel owns this provenance even before resume establishes a binding.
            if (sessionIdentity.ownerId(route) == null) return@synchronized event.copy(canonical = true)
            event.copy(runtimeSessionId = sessionIdentity.qualify(event.runtimeSessionId, route), canonical = true,
                executionGeneration = generation,
                payload = if (event.type in setOf("session.info", "session.updated")) sessionIdentity.projectMetadata(payload, route) else payload)
        }
    }

    private fun admission(value: JsonObject, params: JsonObject, owner: Owner): JsonObject {
        fun uncertain(): Nothing = throw GatewayRpcException("The Gateway returned an unusable admission receipt.", requestMayHaveBeenAccepted = true)
        val ref = value["ref"] as? JsonObject ?: uncertain()
        val ownerId = synchronized(stateLock) { descriptors.getValue(owner.profile).text("profile_id") }
        if (ref.text("session_id") != owner.sessionId || ref.text("profile_id") != ownerId ||
            value.text("admission_id").isNullOrBlank() || value.number("sequence")?.let { it >= 0 } != true ||
            value.text("status") !in setOf("queued", "started", "unknown", "terminal")) uncertain()
        val authority = value.number("authority_epoch")?.takeIf { it >= 0 } ?: uncertain()
        val generation = if (value["execution_generation"] == JsonNull) null else
            value.number("execution_generation")?.takeIf { it >= 0 } ?: uncertain()
        synchronized(stateLock) {
            val previous = snapshots[owner] ?: uncertain()
            if (authority < previous.authorityEpoch) uncertain()
            snapshots[owner] = previous.copy(authorityEpoch = authority, generation = when {
                generation == null -> previous.generation
                authority > previous.authorityEpoch -> generation
                else -> maxOf(previous.generation, generation)
            })
        }
        return JsonObject(value + mapOf("session_id" to JsonPrimitive(sessionIdentity.qualify(owner.sessionId, owner.profile.takeIf(String::isNotEmpty))),
            "submission_id" to (params["submission_id"] ?: params.getValue("input_id"))))
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
        private val TRUNCATE_KEYS = setOf("confirm_truncate", "confirm_empty_truncate", "confirm_deep_truncate", "truncate_before_row_id",
            "truncate_before_message_id", "truncate_before_user_ordinal", "rebind_survivor_row_ids")
        private val GENERATION_MUTATIONS = setOf("delete", "branch", "compress", "rewind", "reset", "model", "reasoning", "workspace")
        private val REASONING_EFFORTS = setOf("none", "false", "disabled", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
        private val CREATE_FIELDS = setOf("cwd", "model", "provider", "toolsets", "reasoning", "profile", "title",
            "hidden", "follow_profile_config", "service_tier", "cwd_explicit")
        private val CREATE_INPUTS = CREATE_FIELDS + setOf("request_id", "source", "reasoning_effort", "fast")
        private val SNAPSHOT_INFO_FIELDS = setOf("replay_epoch", "last_sequence", "execution_generation", "running")
    }
}
