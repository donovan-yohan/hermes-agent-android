package com.hermesagent.mobile.ui.gateway

import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import org.junit.Assert.*
import org.junit.Test

class PrivateGatewayCopyTest {
    @Test
    fun invalidRemoteAddressOffersHttpsOrPrivateIp() {
        val state = GatewaySettingsUiState(remote = RemoteGatewayProfile(baseUrl = "http://gateway.example"))
        assertEquals("Use HTTPS or a private Gateway IP address.", state.remoteUrlError)
        assertEquals(state.remoteUrlError, ConnectionsCopy.INVALID_URL)
    }

    @Test
    fun remoteDescriptionDoesNotClaimHttpsIsMandatory() {
        assertEquals("A Hermes gateway reachable over LAN, Tailscale, or the internet.", ConnectionsCopy.KIND_REMOTE_DESC)
    }
}
