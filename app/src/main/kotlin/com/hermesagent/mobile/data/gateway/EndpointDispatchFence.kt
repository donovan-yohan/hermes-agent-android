package com.hermesagent.mobile.data.gateway

/**
 * Serializes leaving an endpoint with the one irreversible act that a
 * endpoint-bound operation performs: handing its frame to a Gateway RPC wire.
 *
 * A lease is captured while a caller observes an endpoint. Leaving that
 * endpoint calls [invalidate] before it tears the connection down. A client
 * that implements [EndpointDispatchingGatewayRpcClient] asks the lease to run
 * its actual wire send; the send runs only when the lease is still current and
 * the caller still owns the same live RPC. This is intentionally narrower than
 * a request lifetime: waiting for a response must never hold a connection
 * switch hostage, but a stale request must never start after the switch fence.
 */
internal class EndpointDispatchFence {
    private val lock = Any()
    private var generation = 0L

    /**
     * Capture a lease only if this fence still names [expectedGeneration]. The
     * switch increments this value before it increments SessionCache's matching
     * generation, so a request cannot acquire a fresh old-endpoint lease in the
     * small interval between those two operations.
     */
    fun leaseAt(expectedGeneration: Long, stillOwns: () -> Boolean): Long? = synchronized(lock) {
        generation.takeIf { it == expectedGeneration && stillOwns() }
    }

    /**
     * Make every previously captured lease ineligible before connection teardown
     * begins. The caller owns the ordering with its endpoint generation/cache
     * invalidation; this fence owns only dispatch admission.
     */
    fun invalidate() {
        synchronized(lock) { generation += 1 }
    }

    /**
     * Run [send] only while [lease] still names the current endpoint and
     * [stillOwns] still names the same live RPC. [send] must be the immediate,
     * non-suspending wire hand-off — not the request/response wait.
     */
    fun dispatchIfCurrent(
        lease: Long,
        stillOwns: () -> Boolean,
        send: () -> Boolean,
    ): Boolean = synchronized(lock) {
        lease == generation && stillOwns() && send()
    }
}

/**
 * A [GatewayRpcClient] that can place its actual wire hand-off behind an
 * [EndpointDispatchFence].
 *
 * Implementations must invoke [dispatch] exactly around the immediate,
 * non-suspending send that starts the request. They may await the response only
 * after it returns. This separates endpoint invalidation from ordinary request
 * semantics without pretending a pre-request ownership check is atomic.
 */
internal interface EndpointDispatchingGatewayRpcClient : GatewayRpcClient {
    suspend fun requestAtEndpointDispatch(
        method: String,
        params: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
        dispatch: (() -> Boolean) -> Boolean,
    ): kotlinx.serialization.json.JsonElement
}
