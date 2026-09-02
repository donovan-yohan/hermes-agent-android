package com.hermesagent.mobile.data.updates

import com.hermesagent.mobile.data.gateway.DEFAULT_ACTION_LINES
import com.hermesagent.mobile.data.gateway.GatewayAction
import com.hermesagent.mobile.data.gateway.GatewayActionStatus
import com.hermesagent.mobile.data.gateway.GatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayRestClient
import com.hermesagent.mobile.data.gateway.GatewayRestResult
import com.hermesagent.mobile.data.gateway.GatewayRestartStart
import com.hermesagent.mobile.data.gateway.GatewayStatusSummary
import com.hermesagent.mobile.data.gateway.GatewayUpdateCheck
import com.hermesagent.mobile.data.gateway.GatewayUpdateReceipt
import com.hermesagent.mobile.data.gateway.GatewayUpdateStart

/**
 * The System panel's six host calls, as the surfaces above them need them.
 *
 * Narrow on purpose, and for the reason `RelayChannelReader` is narrow: a seam
 * this small is what lets the whole six-minute update state machine run on
 * virtual time in a unit test, without a transport, a credential or a clock.
 * Only [GatewayRestResult] crosses it, so nothing above ever reads a status
 * code out of a transport.
 */
internal interface GatewaySystemApi {
    suspend fun status(): GatewayRestResult<GatewayStatusSummary>

    suspend fun checkUpdate(force: Boolean): GatewayRestResult<GatewayUpdateCheck>

    suspend fun startUpdate(): GatewayRestResult<GatewayUpdateStart>

    suspend fun actionStatus(
        action: GatewayAction,
        lines: Int = DEFAULT_ACTION_LINES,
    ): GatewayRestResult<GatewayActionStatus>

    suspend fun updateReceipt(): GatewayRestResult<GatewayUpdateReceipt>

    suspend fun restartGateway(): GatewayRestResult<GatewayRestartStart>
}

/**
 * [GatewaySystemApi] over the connection-owned REST client.
 *
 * The transport is borrowed per call through [http], exactly as
 * `GatewaySessionRepository` borrows it: a client built before a reconnect
 * cannot keep speaking to a connection that has since gone away, which matters
 * more here than anywhere else — this is the one surface whose whole job is to
 * survive the backend restarting itself.
 */
internal class RestGatewaySystemApi(private val http: () -> GatewayHttp?) : GatewaySystemApi {
    private val rest = GatewayRestClient(http = http)

    override suspend fun status() = rest.status()

    override suspend fun checkUpdate(force: Boolean) = rest.checkHermesUpdate(force)

    override suspend fun startUpdate() = rest.startHermesUpdate()

    override suspend fun actionStatus(action: GatewayAction, lines: Int) =
        rest.actionStatus(action, lines)

    override suspend fun updateReceipt() = rest.updateReceipt()

    // Unscoped: the panel restarts the gateway the active connection is on,
    // which is the profile the Gateway resolves for itself when none is named
    // (`hermes_cli/web_server.py:4989` @
    // `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
    override suspend fun restartGateway() = rest.restartGateway()
}
