package com.hermesagent.mobile.data.gateway

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Owner-channel boundary. Native requests lack owner/epoch in
 * gateway/session_sudo_prompt.py:80-93; Desktop stamps socket provenance in
 * apps/desktop/src/store/gateway.ts:1075-1086 and forbids double attachment in
 * apps/desktop/src/store/gateway-profile-request.test.ts:848-873
 * @ 379cf1220fe289b5ecfe8bcf61cd4d5126e140cd. */
internal class ProfileGatewayRpcClient(
    private val primary: GatewayRpcClient,
    parentScope: CoroutineScope,
    private val openProfile: suspend (String) -> GatewayRpcClient,
) : EndpointDispatchingGatewayRpcClient, GatewayServerRequestResponder, AuthorityGatewayRpcClient,
    GatewayOwnedServerRequestResponder {
    private data class Leg(val client: GatewayRpcClient, val profile: String?)
    private data class RequestOrigin(val runtimeId: String?, val id: String)
    private val lock = Any()
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val stopped = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val children = mutableMapOf<String, Leg>()
    private val origins = mutableMapOf<RequestOrigin, GatewayRpcClient>()
    private val creations = mutableSetOf<Job>()
    private val ownerLocks = KeyedMutex<String>()
    private val eventBuffer = Channel<GatewayEvent>(512)
    private val requestBuffer = Channel<GatewayServerRequest>(128)
    private val eventListeners = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 512)
    private val requestListeners = MutableSharedFlow<GatewayServerRequest>(extraBufferCapacity = 128)
    private val closedFlow = MutableSharedFlow<GatewayCloseCause>(replay = 1)
    override val authoritySessionIdentity get() = (primary as? AuthorityGatewayRpcClient)?.authoritySessionIdentity
    override fun bindSessionEndpoint(namespace: String) { (primary as? AuthorityGatewayRpcClient)?.bindSessionEndpoint(namespace) }
    override fun bindSessionOwner(profile: String, identity: GatewaySessionIdentity): Unit =
        error("Only an unattached socket may become an owner channel.")
    override val closed: Flow<GatewayCloseCause> = closedFlow
    override val events: Flow<GatewayEvent> = flow { startPump(); emitAll(eventListeners) }
    override val serverRequests: Flow<GatewayServerRequest> = flow { startPump(); emitAll(requestListeners) }

    init { observe(Leg(primary, null)) }

    override suspend fun request(method: String, params: JsonObject): JsonElement =
        requestAtEndpointDispatch(method, params) { send -> send() }

    override suspend fun requestAtEndpointDispatch(method: String, params: JsonObject,
        dispatch: (() -> Boolean) -> Boolean): JsonElement {
        ensureOpen()
        val leg = channel(method, params, dispatch)
        ensureOpen()
        return send(leg.client, method, params, dispatch)
    }

    private suspend fun send(client: GatewayRpcClient, method: String, params: JsonObject,
        dispatch: (() -> Boolean) -> Boolean): JsonElement {
        val bounded = client as? EndpointDispatchingGatewayRpcClient
            ?: throw GatewayRpcException("This Gateway cannot fence an owner channel request.")
        return bounded.requestAtEndpointDispatch(method, params, dispatch)
    }

    private suspend fun channel(method: String, params: JsonObject,
        dispatch: (() -> Boolean) -> Boolean): Leg {
        val identity = authoritySessionIdentity ?: return Leg(primary, null)
        // Inventory/descriptor calls never attach a session. Every session
        // writer/reader with a target, and create itself, uses one owner leg.
        if (method != "session.create" && "session_id" !in params) return Leg(primary, null)
        val id = (params["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val explicit = (params["profile"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val primaryDescription = send(primary, "runtime.describe", JsonObject(emptyMap()), dispatch) as? JsonObject
            ?: throw GatewayRpcException("The Gateway returned an unusable owner description.")
        val primaryOwner = primaryDescription.text("profile_id") ?: throw GatewayRpcException("The Gateway did not identify its owner.")
        // Rebind a retained opaque key only after this endpoint has confirmed
        // the captured profile again. Never put the opaque key on the wire.
        val explicitDescription = explicit?.let { route ->
            send(primary, "runtime.describe", buildJsonObject { put("profile", route) }, dispatch) as? JsonObject
                ?: throw GatewayRpcException("The Gateway returned an unusable owner description.")
        }
        val address = id?.let { identity.address(it, explicit) }
        val profile = address?.profile ?: explicit ?: return Leg(primary, null)
        val described = if (explicit == profile && explicitDescription != null) explicitDescription else
            send(primary, "runtime.describe", buildJsonObject { put("profile", profile) }, dispatch) as? JsonObject
                ?: throw GatewayRpcException("The Gateway returned an unusable owner description.")
        val owner = described.text("profile_id") ?: throw GatewayRpcException("The Gateway did not identify its owner.")
        if (owner == primaryOwner) return Leg(primary, null)
        return ownerLocks.withLock(owner) {
            synchronized(lock) { children[owner] }?.let { return@withLock it }
            coroutineScope {
                val creation = checkNotNull(currentCoroutineContext()[Job])
                synchronized(lock) { ensureOpen(); creations += creation }
                var child: GatewayRpcClient? = null
                try {
                    child = openProfile(profile)
                    ensureOpen()
                    val authority = child as? AuthorityGatewayRpcClient
                        ?: throw GatewayRpcException("This Gateway cannot open an owner channel.")
                    authority.bindSessionOwner(profile, identity)
                    send(child, "session.list", buildJsonObject { put("profile", profile); put("limit", 1) }, dispatch)
                    val confirmed = send(child, "runtime.describe", buildJsonObject { put("profile", profile) }, dispatch) as? JsonObject
                    if (confirmed?.text("profile_id") != owner) throw GatewayRpcException("The Gateway returned another profile owner.")
                    child.advertiseServerRequests()
                    val leg = Leg(child, profile)
                    synchronized(lock) { ensureOpen(); children[owner] = leg }
                    observe(leg)
                    child = null // graph owns it after publication
                    leg
                } finally {
                    child?.close()
                    synchronized(lock) { creations -= creation }
                }
            }
        }
    }

    private fun observe(leg: Leg) {
        follow {
            leg.client.events.collect { event ->
                // One root owns connection-wide hints/skin/readiness. A child
                // adds session authority, never another global ready boundary.
                if (leg.profile != null && event.runtimeSessionId == null) return@collect
                val identity = authoritySessionIdentity
                val projected = if (identity != null && event.runtimeSessionId != null) {
                    event.copy(runtimeSessionId = identity.qualify(event.runtimeSessionId, leg.profile),
                        payload = if (event.type in setOf("session.info", "session.updated") && event.payload is JsonObject)
                            identity.projectMetadata(event.payload, leg.profile) else event.payload)
                } else event
                if (eventBuffer.trySend(projected).isFailure) closeGraph(GatewayCloseCause.Orderly)
            }
        }
        follow {
            leg.client.serverRequests.collect { request ->
                val identity = authoritySessionIdentity
                val projected = if (identity != null && request.runtimeSessionId != null) {
                    request.copy(runtimeSessionId = identity.qualify(request.runtimeSessionId, leg.profile))
                } else request
                synchronized(lock) { origins[RequestOrigin(projected.runtimeSessionId, projected.id)] = leg.client }
                if (requestBuffer.trySend(projected).isFailure) closeGraph(GatewayCloseCause.Orderly)
            }
        }
        follow { leg.client.closed.collect { closeGraph(it) } }
    }

    private fun follow(block: suspend () -> Unit) = scope.launch {
        try { block() } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            closeGraph(GatewayCloseCause.Orderly)
        }
    }

    override suspend fun respondToServerRequest(id: String, result: JsonObject) {
        val origin = uniqueOrigin(id)
        respondToServerRequestAtOwner(origin.runtimeId, id, result) { send -> send() }
    }
    override suspend fun failServerRequest(id: String, code: Int, message: String) {
        val origin = uniqueOrigin(id)
        failServerRequestAtOwner(origin.runtimeId, id, code, message) { send -> send() }
    }
    private fun uniqueOrigin(id: String): RequestOrigin = synchronized(lock) {
        origins.keys.filter { it.id == id }.singleOrNull()
            ?: throw GatewayRpcException("This request is no longer answerable on its owner channel.")
    }
    override suspend fun respondToServerRequestAtOwner(runtimeId: String?, id: String, result: JsonObject,
        dispatch: (() -> Boolean) -> Boolean) {
        val key = RequestOrigin(runtimeId, id)
        val client = synchronized(lock) { ensureOpen(); origins[key] }
            ?: throw GatewayRpcException("This request is no longer answerable on its owner channel.")
        if (client is GatewayOwnedServerRequestResponder) client.respondToServerRequestAtOwner(runtimeId, id, result, dispatch)
        else throw GatewayRpcException("This request cannot be answered on its owner channel.")
        synchronized(lock) { origins.remove(key) }
    }
    override suspend fun failServerRequestAtOwner(runtimeId: String?, id: String, code: Int, message: String,
        dispatch: (() -> Boolean) -> Boolean) {
        val key = RequestOrigin(runtimeId, id)
        val client = synchronized(lock) { ensureOpen(); origins[key] }
            ?: throw GatewayRpcException("This request is no longer answerable on its owner channel.")
        if (client is GatewayOwnedServerRequestResponder) client.failServerRequestAtOwner(runtimeId, id, code, message, dispatch)
        else throw GatewayRpcException("This request cannot be answered on its owner channel.")
        synchronized(lock) { origins.remove(key) }
    }

    private fun startPump() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { drain(eventBuffer, eventListeners) }
        scope.launch { drain(requestBuffer, requestListeners) }
    }
    private suspend fun <T> drain(buffer: Channel<T>, listeners: MutableSharedFlow<T>) {
        while (true) {
            listeners.subscriptionCount.first { it > 0 }
            val value = buffer.receiveCatching().getOrNull() ?: return
            if (!listeners.tryEmit(value)) { closeGraph(GatewayCloseCause.Orderly); return }
        }
    }
    private fun ensureOpen() { if (stopped.get()) throw GatewayRpcException("The gateway connection is closed.") }
    override fun close() = closeGraph(GatewayCloseCause.Orderly)
    private fun closeGraph(cause: GatewayCloseCause) {
        if (!stopped.compareAndSet(false, true)) return
        closedFlow.tryEmit(cause)
        val legs = synchronized(lock) {
            creations.toList().forEach { it.cancel() }
            (children.values.map { it.client } + primary).distinct().also { children.clear(); origins.clear() }
        }
        legs.forEach { runCatching { it.close() } }
        job.cancel()
        eventBuffer.close(); requestBuffer.close()
    }
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
