package com.hermesagent.mobile.data.connections

import com.hermesagent.mobile.data.draft.SessionDraftStore
import com.hermesagent.mobile.data.draft.TransientSessionDraftStore
import com.hermesagent.mobile.data.gateway.GatewayConnectionController
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.session.SessionCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Re-homes this device to one saved connection.
 *
 * The order is the whole point, and it is Desktop’s
 * (`apps/desktop/src/store/connections.ts:153-225` and
 * `store/gateway-switch.ts:47-96` @ `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`):
 *
 * 1. **Leave the old endpoint through the door it came in.** The existing
 *    [GatewayConnectionController.disconnect] is what tears the socket down, so
 *    a turn that was running is left visibly disconnected rather than silently
 *    dropped. It is not stopped: work on the gateway you switched away from
 *    keeps running there, exactly as Desktop says it does.
 * 2. **Forget what the old endpoint told us.** Sessions, transcripts, the
 *    project catalog and the private draft text all belong to that machine; a
 *    different one can recycle their ids, so they are cleared rather than
 *    merged or re-pointed.
 * 3. **Move the marker.** Writing the active row is what re-points the SSH
 *    profile, the Remote profile, the route, the composer scope and the queue
 *    — all projections of the active row, so there is one write, not five.
 * 4. **Come up on the new endpoint.** The app-scoped route follower dials it;
 *    this controller only waits, bounded, so the switcher can hold a pending
 *    state while it happens. A route that cannot dial itself — Managed SSH,
 *    whose credential is in-memory and dies with the connection — is not
 *    waited on, because nothing is coming.
 *
 * Every entry point takes one lock, so a teardown can never interleave with a
 * switch and land after the connection that switch just opened.
 *
 * Which session is shown afterwards is deliberately not decided here: the chat
 * surface picks the new endpoint’s most recently active session once that
 * endpoint’s own list arrives.
 */
internal class ConnectionSwitchController(
    private val store: ConnectionRegistryStore,
    private val gateway: GatewayConnectionController,
    private val cache: SessionCache,
    /**
     * Draft text is keyed by durable session id and nothing else, and two
     * gateways can hand out the same one. Leaving an endpoint drops the drafts
     * that belonged to it rather than letting them prefill another machine’s
     * composer under a recycled id.
     */
    private val drafts: SessionDraftStore = TransientSessionDraftStore(),
    private val settleTimeoutMillis: Long = SETTLE_TIMEOUT_MILLIS,
) {
    private val switching = Mutex()
    private val pending = MutableStateFlow<String?>(null)
    private val rearm = MutableStateFlow(0L)

    /** The row being switched to, or null. One at a time; a second caller waits. */
    val pendingConnectionId: StateFlow<String?> = pending.asStateFlow()

    /**
     * Bumped when the active row’s own address changed, so the app-scoped route
     * follower re-dials a row whose id did not move. The follower keys on this
     * rather than on the route values because editing a URL is somebody typing,
     * and a re-dial per keystroke is a network round trip per character.
     */
    val routeGeneration: StateFlow<Long> = rearm.asStateFlow()

    suspend fun select(id: String) {
        switching.withLock {
            val registry = store.connectionRegistry.first()
            val target = registry.connections.firstOrNull { it.id == id } ?: return
            if (registry.active?.id == target.id) return

            pending.value = target.id
            try {
                leaveLocked()
                store.setActiveConnection(target.id)
                awaitSettle(target)
            } finally {
                pending.value = null
            }
        }
    }

    /**
     * Re-address the connection this device is already on: leave the old
     * address, persist the new one, and come up on it.
     *
     * [save] runs inside the lock because the persisted row is what the route
     * follower reads when it re-arms, and because a removal arriving between
     * the teardown and the re-dial would tear down the wrong thing.
     */
    suspend fun readdressActive(save: suspend () -> Unit) {
        switching.withLock {
            pending.value = store.connectionRegistry.first().active?.id
            try {
                leaveLocked()
                save()
                rearm.value += 1
                awaitSettle(store.connectionRegistry.first().active)
            } finally {
                pending.value = null
            }
        }
    }

    /**
     * Steps 1 and 2 on their own, for a caller that changes where this device
     * is pointed without going through [select] or [readdressActive] — most of
     * all, removing the row it is on.
     *
     * It takes the same lock, so a removal that arrives mid-switch waits rather
     * than tearing down the connection that switch had just opened.
     */
    suspend fun leaveCurrentEndpoint() {
        switching.withLock { leaveLocked() }
    }

    private suspend fun leaveLocked() {
        gateway.disconnect()
        cache.resetForEndpointSwitch()
        drafts.clear()
    }

    private suspend fun awaitSettle(target: SavedConnection?) {
        if (target == null || target.kind != ConnectionKind.Remote || !target.remote.isValid) return
        withTimeoutOrNull(settleTimeoutMillis) {
            gateway.state.first { state ->
                state.status == GatewayConnectionStatus.Connected ||
                    state.status == GatewayConnectionStatus.NeedsAttention
            }
        }
    }

    private companion object {
        /**
         * How long the switcher shows “connecting” before it stops claiming to
         * know. The connection keeps trying; only the pending badge gives up.
         */
        const val SETTLE_TIMEOUT_MILLIS = 20_000L
    }
}
