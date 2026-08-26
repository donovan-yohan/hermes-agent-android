package com.hermesagent.mobile.data.connections

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
 * The order is the whole point, and it is Desktop's
 * (`apps/desktop/src/store/connections.ts:153-225` and
 * `store/gateway-switch.ts:47-96` @ `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`):
 *
 * 1. **Leave the old endpoint through the door it came in.** The existing
 *    [GatewayConnectionController.disconnect] is what tears the socket down, so
 *    a turn that was running is left visibly disconnected rather than silently
 *    dropped. It is not stopped: work on the gateway you switched away from
 *    keeps running there, exactly as Desktop says it does.
 * 2. **Forget what the old endpoint told us.** Sessions, transcripts and the
 *    project catalog belong to that machine; a different one can recycle their
 *    ids, so they are cleared rather than merged.
 * 3. **Move the marker.** Writing the active row is what re-points the SSH
 *    profile, the Remote profile, the route, the composer scope and the queue
 *    — all projections of the active row, so there is one write, not five.
 * 4. **Come up on the new endpoint.** The app-scoped route follower dials it;
 *    this controller only waits, bounded, so the switcher can hold a pending
 *    state while it happens. A route that cannot dial itself — Managed SSH,
 *    whose credential is in-memory and dies with the connection — is not
 *    waited on, because nothing is coming.
 *
 * Which session is shown afterwards is deliberately not decided here: the chat
 * surface picks the new endpoint's most recently active session once that
 * endpoint's own list arrives.
 */
internal class ConnectionSwitchController(
    private val store: ConnectionRegistryStore,
    private val gateway: GatewayConnectionController,
    private val cache: SessionCache,
    private val settleTimeoutMillis: Long = SETTLE_TIMEOUT_MILLIS,
) {
    private val switching = Mutex()
    private val pending = MutableStateFlow<String?>(null)

    /** The row being switched to, or null. One at a time; a second tap waits. */
    val pendingConnectionId: StateFlow<String?> = pending.asStateFlow()

    suspend fun select(id: String) {
        switching.withLock {
            val registry = store.connectionRegistry.first()
            val target = registry.connections.firstOrNull { it.id == id } ?: return
            if (registry.active?.id == target.id) return

            pending.value = target.id
            try {
                leaveCurrentEndpoint()
                store.setActiveConnection(target.id)
                if (target.kind == ConnectionKind.Remote && target.remote.isValid) {
                    withTimeoutOrNull(settleTimeoutMillis) {
                        gateway.state.first { state ->
                            state.status == GatewayConnectionStatus.Connected ||
                                state.status == GatewayConnectionStatus.NeedsAttention
                        }
                    }
                }
            } finally {
                pending.value = null
            }
        }
    }

    /**
     * Steps 1 and 2 on their own, for the other way an endpoint changes:
     * re-addressing the connection this device is already on.
     *
     * Editing an address is the same event as picking another row — the
     * sessions on screen are the old machine's either way — so it goes through
     * the same teardown rather than a second, slightly different one.
     */
    suspend fun leaveCurrentEndpoint() {
        gateway.disconnect()
        cache.resetForEndpointSwitch()
    }

    private companion object {
        /**
         * How long the switcher shows "connecting" before it stops claiming to
         * know. The connection keeps trying; only the pending badge gives up.
         */
        const val SETTLE_TIMEOUT_MILLIS = 20_000L
    }
}
