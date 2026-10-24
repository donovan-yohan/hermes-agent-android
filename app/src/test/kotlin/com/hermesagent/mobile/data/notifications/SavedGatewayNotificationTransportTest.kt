package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.connections.*
import com.hermesagent.mobile.data.gateway.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SavedGatewayNotificationTransportTest {
    @Test fun storedLocalTokenConstructsReachableReaderWithoutStartingAnything() = runTest {
        val tokens = object : GatewayTokenStore {
            override suspend fun load(slot: GatewaySecretSlot): GatewayNativeTokens? = null
            override suspend fun save(slot: GatewaySecretSlot, tokens: GatewayNativeTokens) = error("no write")
            override suspend fun clear(slot: GatewaySecretSlot) = error("no clear")
        }
        val api = object : GatewayNativeAuthApi {
            override suspend fun status(baseUrl: String) = error("no network yet")
            override suspend fun exchange(baseUrl: String, code: String, verifier: String) = error("no login")
            override suspend fun refresh(baseUrl: String, refreshToken: String, provider: String): GatewayNativeTokens? = error("no refresh")
            override suspend fun mintWebSocketTicket(baseUrl: String, accessToken: String): String? = error("no remote")
        }
        val bytes = "fixture".toByteArray()
        val local = object : GatewaySessionTokenStore {
            override suspend fun loadSessionToken(slot: GatewaySecretSlot): SessionTokenRead = SessionTokenRead.Found(bytes)
            override suspend fun saveSessionToken(slot: GatewaySecretSlot, token: ByteArray) = error("no write")
            override suspend fun clearSessionToken(slot: GatewaySecretSlot) = error("no clear")
        }
        val row = SavedConnection("local-row", "Local", ConnectionKind.Local, local = LocalGatewayProfile("http://127.0.0.1:9119"))
        val reader = SavedGatewayNotificationTransport.capture(row, NativeGatewayAuthenticator(api, tokens, { _, _ -> error("no login") }), local) { true }
        assertNotNull(reader)
        assertEquals(row.botStorageIdentity(), reader!!.endpoint)
        assertTrue(bytes.all { it == 0.toByte() })
        reader.close()
        assertFalse(reader.isCurrent())
    }

    @Test fun sshAndMissingStoredCredentialsNeverOpenSocketOrBrowser() = runTest {
        val tokens = object : GatewayTokenStore {
            override suspend fun load(slot: GatewaySecretSlot): GatewayNativeTokens? = null
            override suspend fun save(slot: GatewaySecretSlot, tokens: GatewayNativeTokens) = error("no write")
            override suspend fun clear(slot: GatewaySecretSlot) = error("no clear")
        }
        val api = object : GatewayNativeAuthApi {
            override suspend fun status(baseUrl: String) = error("no network without credential")
            override suspend fun exchange(baseUrl: String, code: String, verifier: String) = error("no browser")
            override suspend fun refresh(baseUrl: String, refreshToken: String, provider: String): GatewayNativeTokens? = error("no refresh")
            override suspend fun mintWebSocketTicket(baseUrl: String, accessToken: String): String? = error("no socket")
        }
        val local = object : GatewaySessionTokenStore {
            override suspend fun loadSessionToken(slot: GatewaySecretSlot): SessionTokenRead = SessionTokenRead.Absent
            override suspend fun saveSessionToken(slot: GatewaySecretSlot, token: ByteArray) = error("no write")
            override suspend fun clearSessionToken(slot: GatewaySecretSlot) = error("no clear")
        }
        val auth = NativeGatewayAuthenticator(api, tokens, { _, _ -> error("no login") })
        val remote = SavedConnection("row", "Saved", ConnectionKind.Remote, remote = RemoteGatewayProfile("https://gateway.invalid"))
        assertNull(SavedGatewayNotificationTransport.capture(remote, auth, local) { true })
        assertNull(SavedGatewayNotificationTransport.capture(remote.copy(kind = ConnectionKind.Ssh), auth, local) { true })
        assertNull(SavedGatewayNotificationTransport.capture(remote, auth, local) { false })
    }
}
