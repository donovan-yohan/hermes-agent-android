package com.hermesagent.mobile.ui.gateway

import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import org.junit.Assert.*
import org.junit.Test

class PrivateGatewayCopyTest {
    @Test
    fun invalidRemoteAddressOffersHttpOrHttps() {
        val state = GatewaySettingsUiState(remote = RemoteGatewayProfile(baseUrl = "ftp://gateway.example"))
        assertEquals("Use an HTTP or HTTPS Gateway URL.", state.remoteUrlError)
        assertEquals(state.remoteUrlError, ConnectionsCopy.INVALID_URL)
    }

    @Test
    fun remotePlaceholderAcceptsDesktopHttpLanHostname() {
        assertEquals("http://homelab.lan:9119", ConnectionsCopy.URL_PLACEHOLDER)
        assertNull(GatewaySettingsUiState(remote = RemoteGatewayProfile(baseUrl = ConnectionsCopy.URL_PLACEHOLDER)).remoteUrlError)
    }

    @Test
    fun remoteDescriptionDoesNotClaimHttpsIsMandatory() {
        assertEquals("A Hermes gateway reachable over HTTP(S) — LAN, Tailscale, or the internet.", ConnectionsCopy.KIND_REMOTE_DESC)
    }
}
