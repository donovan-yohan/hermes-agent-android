package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.gateway.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSilentAuthTest {
    @Test fun concurrentSilentCallersSpendRefreshTokenOnlyOnce() = runTest {
        val store = Tokens().apply { value = tokens("expired", 10) }
        val api = Auth().apply { gate = CompletableDeferred() }
        val auth = NativeGatewayAuthenticator(api, store, { _, _ -> error("browser forbidden") }, nowSeconds = { 100 })
        val first = async { auth.backgroundAccessToken(profile) }
        runCurrent()
        val second = async { auth.backgroundAccessToken(profile) }
        runCurrent()
        assertEquals(1, api.refreshes)
        api.gate!!.complete(Unit)
        assertEquals("rotated", first.await())
        assertEquals("rotated", second.await())
        assertEquals(1, api.refreshes)
    }

    @Test fun freshCredentialsAvoidRefreshAndExpiredCredentialsRotateWithoutLogin() = runTest {
        val store = Tokens()
        val api = Auth()
        val auth = NativeGatewayAuthenticator(api, store, { _, _ -> error("browser forbidden") }, nowSeconds = { 100 })
        assertEquals("fresh", auth.backgroundAccessToken(profile))
        assertEquals(0, api.refreshes)
        store.value = tokens("expired", 10)
        assertEquals("rotated", auth.backgroundAccessToken(profile))
        assertEquals(1, api.refreshes)
        api.refuse = true
        store.value = tokens("expired-again", 10)
        assertNull(auth.backgroundAccessToken(profile))
        store.value = null
        assertNull(auth.backgroundAccessToken(profile))
    }
    @Test fun signoutDuringSilentRotationCannotResurrectCredential() = runTest {
        val store = Tokens().apply { value = tokens("expired", 10) }
        val api = Auth().apply { gate = CompletableDeferred() }
        val auth = NativeGatewayAuthenticator(api, store, { _, _ -> error("browser forbidden") }, nowSeconds = { 100 })
        val result = async { auth.backgroundAccessToken(profile) }
        runCurrent()
        auth.signOut(profile)
        api.gate!!.complete(Unit)
        assertNull(result.await())
        assertNull(store.value)
    }
    private class Tokens : GatewayTokenStore {
        var value: GatewayNativeTokens? = tokens("fresh", 1000)
        override suspend fun load(slot: GatewaySecretSlot) = value
        override suspend fun save(slot: GatewaySecretSlot, tokens: GatewayNativeTokens) { value = tokens }
        override suspend fun clear(slot: GatewaySecretSlot) { value = null }
    }
    private class Auth : GatewayNativeAuthApi {
        var refreshes = 0
        var refuse = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun status(baseUrl: String) = error("not needed")
        override suspend fun exchange(baseUrl: String, code: String, verifier: String) = error("no login")
        override suspend fun refresh(baseUrl: String, refreshToken: String, provider: String): GatewayNativeTokens? {
            refreshes++; gate?.await()
            return if (refuse) null else tokens("rotated", 1000)
        }
        override suspend fun mintWebSocketTicket(baseUrl: String, accessToken: String) = error("REST only")
    }
    companion object {
        private val profile = RemoteGatewayProfile("https://gateway.invalid", secretSlotId = "row")
        private fun tokens(access: String, expires: Long) = GatewayNativeTokens(access, "refresh-$access", expires, "provider", "user")
    }
}
