package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.SshCredential
import com.hermesagent.mobile.restoreSavedRemoteGateway
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteGatewayTest {

    @Test
    fun `browser sign-in timeout becomes an actionable authentication failure`() = runTest {
        val pending = async {
            runCatching {
                withGatewayLoginTimeout(1_000L) { awaitCancellation() }
            }.exceptionOrNull()
        }

        runCurrent()
        advanceTimeBy(1_000L)

        val failure = pending.await()
        assertTrue(failure is GatewayAuthException)
        assertEquals(408, (failure as GatewayAuthException).statusCode)
        assertEquals("Sign-in timed out. Try again.", failure.message)
    }

    @Test
    fun `remote retry classification stops only on local auth failures and refusals`() {
        assertFalse(GatewayAuthException("Sign in.").isRetryableRemoteConnectionFailure())
        assertFalse(GatewayAuthException("Unauthorized.", 401).isRetryableRemoteConnectionFailure())
        assertFalse(GatewayAuthException("Refused.", 403).isRetryableRemoteConnectionFailure())
        assertTrue(GatewayAuthException("Timed out.", 408).isRetryableRemoteConnectionFailure())
        assertTrue(GatewayAuthException("Rate limited.", 429).isRetryableRemoteConnectionFailure())
        assertTrue(GatewayAuthException("Unavailable.", 503).isRetryableRemoteConnectionFailure())
        assertTrue(java.io.IOException("offline").isRetryableRemoteConnectionFailure())
    }

    @Test
    fun `remote urls normalize prefixes but reject credentials query and fragments`() {
        assertEquals("https://gateway.example/hermes", normalizeRemoteGatewayUrl(" https://gateway.example/hermes/ "))
        assertEquals(null, normalizeRemoteGatewayUrl("http://127.0.0.1:9119"))
        assertEquals(null, normalizeRemoteGatewayUrl("https://user:secret@gateway.example"))
        assertEquals(null, normalizeRemoteGatewayUrl("https://gateway.example?token=secret"))
        assertEquals(null, normalizeRemoteGatewayUrl("https://gateway.example/#fragment"))
    }

    @Test
    fun `cached sign-in mints a fresh ticket for each websocket without opening browser`() = runTest {
        val api = FakeAuthApi()
        val store = MemoryTokenStore(VALID_TOKENS)
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = store,
            login = GatewayNativeLogin { _, _ -> error("browser login must not run") },
            nowSeconds = { 1_000L },
        )
        val browser = GatewayBrowserLauncher { error("browser must not open") }

        val first = authenticator.ticket(PROFILE, browser)
        val second = authenticator.ticket(PROFILE, browser)

        assertEquals("ticket-1", first)
        assertEquals("ticket-2", second)
        assertEquals(2, api.ticketTokens.size)
        assertTrue(api.ticketTokens.all { it == "access-fixture" })
    }

    @Test
    fun `native auth wire uses bearer REST and never sends access token in URL`() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val responses = ArrayDeque(
            listOf(
                """{"auth_required":true,"auth_flows":["native_pkce"]}""",
                """{"access_token":"access-wire","refresh_token":"refresh-wire","expires_at":9000,"provider":"fixture","user_id":"user"}""",
                """{"ticket":"single-use-wire"}""",
            ),
        )
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responses.removeFirst().toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val api = OkHttpGatewayNativeAuthApi(http)

        val status = api.status("https://gateway.example/hermes")
        val tokens = api.exchange("https://gateway.example/hermes", "code-wire", "verifier-wire")
        val ticket = api.mintWebSocketTicket("https://gateway.example/hermes", tokens.accessToken)

        assertTrue(status.authRequired)
        assertEquals("single-use-wire", ticket)
        assertEquals(
            listOf("/hermes/api/status", "/hermes/auth/native/token", "/hermes/api/auth/ws-ticket"),
            requests.map { it.url.encodedPath },
        )
        assertEquals(listOf("GET", "POST", "POST"), requests.map { it.method })
        assertTrue(requests[1].bodyAsUtf8().contains("\"code_verifier\":\"verifier-wire\""))
        assertEquals("Bearer access-wire", requests[2].header("Authorization"))
        assertTrue(requests.all { it.url.queryParameter("token") == null && it.url.queryParameter("ticket") == null })
    }

    @Test
    fun `expired sign-in refreshes once and stores replacement before minting ticket`() = runTest {
        val api = FakeAuthApi().apply { refreshed = VALID_TOKENS.copy(accessToken = "access-refreshed") }
        val store = MemoryTokenStore(VALID_TOKENS.copy(expiresAt = 1_020L))
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = store,
            login = GatewayNativeLogin { _, _ -> error("refresh should avoid browser login") },
            nowSeconds = { 1_000L },
        )

        val ticket = authenticator.ticket(PROFILE, GatewayBrowserLauncher {})

        assertEquals("ticket-1", ticket)
        assertEquals("refresh-fixture", api.refreshToken)
        assertEquals("access-refreshed", store.tokens?.accessToken)
        assertEquals("access-refreshed", api.ticketTokens.single())
    }

    @Test
    fun `noninteractive restore never opens browser when sign in is missing`() = runTest {
        var loginCalls = 0
        val authenticator = NativeGatewayAuthenticator(
            api = FakeAuthApi(),
            store = MemoryTokenStore(null),
            login = GatewayNativeLogin { _, _ -> loginCalls += 1; VALID_TOKENS },
            nowSeconds = { 1_000L },
        )

        val failure = runCatching { authenticator.ticket(PROFILE, browser = null) }.exceptionOrNull()

        assertTrue(failure is GatewayAuthException)
        assertTrue(failure?.message.orEmpty().contains("Sign in"))
        assertEquals(0, loginCalls)
    }

    @Test
    fun `loopback login binds before browser launch and validates state with PKCE`() = runBlocking {
        val api = FakeAuthApi()
        val authorize = AtomicReference<String>()
        val callbackThread = AtomicReference<Thread>()
        val login = LoopbackGatewayNativeLogin(api)

        val tokens = login.login(PROFILE, GatewayBrowserLauncher { url ->
            authorize.set(url)
            val parsed = requireNotNull(url.toHttpUrlOrNull())
            val redirect = requireNotNull(parsed.queryParameter("redirect_uri"))
            val state = requireNotNull(parsed.queryParameter("state"))
            callbackThread.set(Thread {
                URL("$redirect?code=code-fixture&state=$state").readText()
            }.also(Thread::start))
        })
        callbackThread.get()?.join(5_000)

        val authorization = requireNotNull(authorize.get()).toHttpUrlOrNull()
        assertNotNull(authorization)
        assertEquals("S256", authorization?.queryParameter("code_challenge_method"))
        assertTrue(requireNotNull(authorization?.queryParameter("redirect_uri")).startsWith("http://127.0.0.1:"))
        assertEquals("code-fixture", api.exchangedCode)
        assertTrue(api.exchangedVerifier.orEmpty().length >= 43)
        assertEquals(VALID_TOKENS, tokens)
    }

    @Test
    fun `loopback login ignores an idle browser probe before the real callback`() = runBlocking {
        val api = FakeAuthApi()
        val callbackThread = AtomicReference<Thread>()
        val login = LoopbackGatewayNativeLogin(api, callbackReadTimeoutMillis = 50)

        val tokens = login.login(PROFILE, GatewayBrowserLauncher { url ->
            val parsed = requireNotNull(url.toHttpUrlOrNull())
            val redirect = requireNotNull(parsed.queryParameter("redirect_uri"))
            val state = requireNotNull(parsed.queryParameter("state"))
            val redirectUrl = requireNotNull(redirect.toHttpUrlOrNull())
            val idleProbe = Socket("127.0.0.1", redirectUrl.port)
            callbackThread.set(Thread {
                idleProbe.use { Thread.sleep(100) }
                URL("$redirect?code=code-after-probe&state=$state").readText()
            }.also(Thread::start))
        })
        callbackThread.get()?.join(5_000)

        assertEquals("code-after-probe", api.exchangedCode)
        assertEquals(VALID_TOKENS, tokens)
    }

    @Test
    fun `remote manager opens no SSH owner and closes only its rpc`() = runTest {
        val api = FakeAuthApi()
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = MemoryTokenStore(VALID_TOKENS),
            login = GatewayNativeLogin { _, _ -> error("not used") },
            nowSeconds = { 1_000L },
        )
        val rpc = FakeRpc()
        var sshOpened = false
        var openedBaseUrl: String? = null
        var openedTicket: String? = null
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            sshOpen = { _, _ -> sshOpened = true; error("SSH must not open") },
            remoteConnector = RemoteGatewayConnector(authenticator) { baseUrl, ticket ->
                openedBaseUrl = baseUrl
                openedTicket = ticket
                rpc
            },
        )

        val result = manager.connectRemote(PROFILE, GatewayBrowserLauncher {})

        assertTrue(result is GatewayConnectResult.Connected)
        assertFalse(sshOpened)
        assertEquals("https://gateway.example/hermes", openedBaseUrl)
        assertEquals("ticket-1", openedTicket)
        assertEquals(listOf("session.list"), rpc.calls)
        manager.disconnect()
        assertTrue(rpc.closedByClient)
        assertEquals(GatewayConnectionStatus.Disconnected, manager.state.value.status)
    }

    @Test
    fun `process startup restores only a valid saved remote route`() = runTest {
        val connection = RecordingConnectionController()
        val valid = MemoryRemoteProfileStore(GatewayConnectionMode.Remote, PROFILE)

        val restore = backgroundScope.launch { restoreSavedRemoteGateway(valid, connection) }
        runCurrent()
        restoreSavedRemoteGateway(
            MemoryRemoteProfileStore(GatewayConnectionMode.Remote, RemoteGatewayProfile()),
            connection,
        )
        restoreSavedRemoteGateway(MemoryRemoteProfileStore(GatewayConnectionMode.Ssh, PROFILE), connection)

        assertEquals(listOf(PROFILE), connection.restored)
        valid.mode.value = GatewayConnectionMode.Ssh
        runCurrent()
        restore.join()
        assertEquals(1, connection.disconnects)
    }

    @Test
    fun `startup restore is cancelled when the saved route changes`() = runTest {
        val store = MemoryRemoteProfileStore(GatewayConnectionMode.Remote, PROFILE)
        val connection = BlockingRestoreConnectionController()
        val restore = backgroundScope.launch { restoreSavedRemoteGateway(store, connection) }
        connection.restoreStarted.await()

        store.profile.value = PROFILE.copy(baseUrl = "https://replacement.example")
        runCurrent()

        restore.join()
        assertTrue(connection.restoreCancelled)
        assertEquals(1, connection.disconnects)
    }

    @Test
    fun `remote socket closure reconnects with a fresh single use ticket`() = runTest {
        val api = FakeAuthApi()
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = MemoryTokenStore(VALID_TOKENS),
            login = GatewayNativeLogin { _, _ -> error("cached restore must not open login") },
            nowSeconds = { 1_000L },
        )
        val first = FakeRpc()
        val second = FakeRpc()
        val pending = ArrayDeque(listOf(first, second))
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> pending.removeFirst() },
            reconnectWait = {},
        )
        manager.connectRemote(PROFILE, GatewayBrowserLauncher {})
        runCurrent()

        first.failFromServer()
        runCurrent()

        assertEquals(listOf("access-fixture", "access-fixture"), api.ticketTokens)
        assertTrue(first.closedByClient)
        assertEquals(listOf("session.list"), second.calls)
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
        manager.disconnect()
    }

    @Test
    fun `network recovery restores the desired remote route once`() = runTest {
        val api = FakeAuthApi()
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = MemoryTokenStore(VALID_TOKENS),
            login = GatewayNativeLogin { _, _ -> error("cached restore must not open login") },
            nowSeconds = { 1_000L },
        )
        val first = FakeRpc()
        val second = FakeRpc()
        val pending = ArrayDeque(listOf(first, second))
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> pending.removeFirst() },
            reconnectWait = {},
        )
        manager.connectRemote(PROFILE, GatewayBrowserLauncher {})

        manager.networkAvailabilityChanged(false)
        runCurrent()
        assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
        manager.networkAvailabilityChanged(true)
        runCurrent()

        assertEquals(2, api.ticketTokens.size)
        assertTrue(first.closedByClient)
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
        manager.disconnect()
    }

    @Test
    fun `repeated remote failures keep retrying instead of stranding the user`() = runTest {
        val api = FakeAuthApi()
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = MemoryTokenStore(VALID_TOKENS),
            login = GatewayNativeLogin { _, _ -> error("cached restore must not open login") },
            nowSeconds = { 1_000L },
        )
        var attempts = 0
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("shared mode has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ ->
                attempts += 1
                FakeRpc()
            },
            reconnectWait = {},
            reconnectJitter = { 0.0 },
        )
        manager.connectRemote(PROFILE, GatewayBrowserLauncher {})
        runCurrent()
        val firstAttempt = attempts

        // Simulate the readiness failure the way the manager observes it: the
        // opened socket closes server-side before session.list completes.
        repeat(10) {
            (manager.client.value as? FakeRpc)?.failFromServer() ?: return@repeat
            runCurrent()
            runCurrent()
        }

        assertTrue(
            "reconnect loop must keep attempting (first=$firstAttempt)",
            attempts >= firstAttempt + 3,
        )
        manager.disconnect()
    }

    @Test
    fun `foreground nudge redials a dead remote route immediately`() = runTest {
        val api = FakeAuthApi()
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = MemoryTokenStore(VALID_TOKENS),
            login = GatewayNativeLogin { _, _ -> error("cached restore must not open login") },
            nowSeconds = { 1_000L },
        )
        val first = FakeRpc()
        val second = FakeRpc()
        val pending = ArrayDeque<GatewayRpcClient>(listOf(first, second))
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("shared mode has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> pending.removeFirst() },
            reconnectWait = {},
            reconnectJitter = { 0.0 },
        )
        manager.connectRemote(PROFILE, GatewayBrowserLauncher {})
        runCurrent()
        first.failFromServer()
        runCurrent()

        // Simulate the app returning to the foreground after the socket died.
        manager.nudgeRemoteReconnect()
        runCurrent()

        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
        assertTrue(second.calls.isNotEmpty())
        manager.disconnect()
    }

    @Test
    fun `nudge never disturbs a healthy connection or an explicit disconnect`() = runTest {
        val api = FakeAuthApi()
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = MemoryTokenStore(VALID_TOKENS),
            login = GatewayNativeLogin { _, _ -> error("cached restore must not open login") },
            nowSeconds = { 1_000L },
        )
        val rpc = FakeRpc()
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("shared mode has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> rpc },
            reconnectWait = {},
        )
        manager.connectRemote(PROFILE, GatewayBrowserLauncher {})
        runCurrent()
        manager.nudgeRemoteReconnect()
        runCurrent()
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)

        manager.disconnect()
        manager.nudgeRemoteReconnect()
        runCurrent()
        assertEquals(GatewayConnectionStatus.Disconnected, manager.state.value.status)
    }

    @Test
    fun `network loss cancels a delayed remote retry before ticket mint`() = runTest {
        val api = FakeAuthApi()
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = MemoryTokenStore(VALID_TOKENS),
            login = GatewayNativeLogin { _, _ -> error("cached restore must not open login") },
            nowSeconds = { 1_000L },
        )
        val first = FakeRpc()
        val retryWaiting = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> first },
            reconnectWait = {
                retryWaiting.complete(Unit)
                releaseRetry.await()
            },
        )
        manager.connectRemote(PROFILE, GatewayBrowserLauncher {})
        runCurrent()
        first.failFromServer()
        retryWaiting.await()

        manager.networkAvailabilityChanged(false)
        runCurrent()
        releaseRetry.complete(Unit)
        runCurrent()

        assertEquals(1, api.ticketTokens.size)
        assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
        assertTrue(manager.state.value.message.orEmpty().contains("Waiting for a network"))
        manager.disconnect()
    }

    @Test
    fun `explicit disconnect prevents a stale remote readiness result from publishing`() = runTest {
        val api = FakeAuthApi()
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = MemoryTokenStore(VALID_TOKENS),
            login = GatewayNativeLogin { _, _ -> error("cached restore must not open login") },
            nowSeconds = { 1_000L },
        )
        val rpc = BlockingReadinessRpc()
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> rpc },
        )
        val connect = backgroundScope.async {
            manager.connectRemote(PROFILE, GatewayBrowserLauncher {})
        }
        rpc.requestStarted.await()

        val disconnect = backgroundScope.launch { manager.disconnect() }
        runCurrent()
        rpc.releaseRequest.complete(Unit)
        runCurrent()
        disconnect.join()

        assertTrue(connect.isCancelled)
        assertTrue(rpc.closedByClient)
        assertEquals(null, manager.client.value)
        assertEquals(GatewayConnectionStatus.Disconnected, manager.state.value.status)
    }

    @Test
    fun `network loss blocks a new remote open before its ticket is minted`() = runTest {
        val api = FakeAuthApi()
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = MemoryTokenStore(VALID_TOKENS),
            login = GatewayNativeLogin { _, _ -> error("cached restore must not open login") },
            nowSeconds = { 1_000L },
        )
        val first = BlockingReadinessRpc()
        val second = FakeRpc()
        val pending = ArrayDeque<GatewayRpcClient>(listOf(first, second))
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> pending.removeFirst() },
        )
        val firstConnect = backgroundScope.async {
            manager.connectRemote(PROFILE, GatewayBrowserLauncher {})
        }
        first.requestStarted.await()

        manager.networkAvailabilityChanged(false)
        val secondConnect = backgroundScope.async {
            manager.connectRemote(PROFILE, GatewayBrowserLauncher {})
        }
        runCurrent()
        first.releaseRequest.complete(Unit)
        runCurrent()

        assertTrue(firstConnect.isCancelled)
        assertTrue(secondConnect.isCancelled)
        assertTrue(first.closedByClient)
        assertFalse(second.closedByClient)
        assertEquals(1, api.ticketTokens.size)
        assertEquals(null, manager.client.value)
        assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
        assertTrue(manager.state.value.message.orEmpty().contains("Waiting for a network"))
    }

    @Test
    fun `a replacement profile supersedes an in-flight remote readiness check`() = runTest {
        val api = FakeAuthApi()
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = MemoryTokenStore(VALID_TOKENS),
            login = GatewayNativeLogin { _, _ -> error("cached restore must not open login") },
            nowSeconds = { 1_000L },
        )
        val first = BlockingReadinessRpc()
        val second = FakeRpc()
        val pending = ArrayDeque<GatewayRpcClient>(listOf(first, second))
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> pending.removeFirst() },
        )
        val firstConnect = backgroundScope.async {
            manager.connectRemote(PROFILE, GatewayBrowserLauncher {})
        }
        first.requestStarted.await()

        val replacement = PROFILE.copy(baseUrl = "https://replacement.example/hermes/")
        val secondConnect = backgroundScope.async {
            manager.connectRemote(replacement, GatewayBrowserLauncher {})
        }
        runCurrent()
        first.releaseRequest.complete(Unit)
        runCurrent()

        assertTrue(firstConnect.isCancelled)
        assertEquals(GatewayConnectResult.Connected, secondConnect.await())
        assertTrue(first.closedByClient)
        assertTrue(manager.client.value === second)
        assertEquals(2, api.ticketTokens.size)
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
        manager.disconnect()
    }

    @Test
    fun `a stale delayed reconnect cannot cancel a replacement profile connect`() = runTest {
        val api = FakeAuthApi()
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = MemoryTokenStore(VALID_TOKENS),
            login = GatewayNativeLogin { _, _ -> error("cached restore must not open login") },
            nowSeconds = { 1_000L },
        )
        val first = FakeRpc()
        val replacementRpc = BlockingReadinessRpc()
        val pending = ArrayDeque<GatewayRpcClient>(listOf(first, replacementRpc))
        val retryWaiting = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> pending.removeFirst() },
            reconnectWait = {
                retryWaiting.complete(Unit)
                withContext(NonCancellable) { releaseRetry.await() }
            },
        )
        manager.connectRemote(PROFILE, GatewayBrowserLauncher {})
        runCurrent()
        first.failFromServer()
        retryWaiting.await()

        val replacement = PROFILE.copy(baseUrl = "https://replacement.example/hermes/")
        val replacementConnect = backgroundScope.async {
            manager.connectRemote(replacement, GatewayBrowserLauncher {})
        }
        replacementRpc.requestStarted.await()
        releaseRetry.complete(Unit)
        runCurrent()

        assertFalse(replacementConnect.isCancelled)
        replacementRpc.releaseRequest.complete(Unit)
        runCurrent()

        assertEquals(GatewayConnectResult.Connected, replacementConnect.await())
        assertTrue(manager.client.value === replacementRpc)
        assertEquals(2, api.ticketTokens.size)
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
        manager.disconnect()
    }

    @Test
    fun `ungated or desktop-only gateway fails closed before token use`() = runTest {
        val api = FakeAuthApi().apply { status = GatewayAuthStatus(authRequired = false, authFlows = emptySet()) }
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = MemoryTokenStore(VALID_TOKENS),
            login = GatewayNativeLogin { _, _ -> error("not used") },
        )

        val failure = runCatching { authenticator.ticket(PROFILE, GatewayBrowserLauncher {}) }.exceptionOrNull()

        assertTrue(failure is GatewayAuthException)
        assertTrue(failure?.message.orEmpty().contains("auth gate"))
        assertTrue(api.ticketTokens.isEmpty())
    }

    private fun okhttp3.Request.bodyAsUtf8(): String {
        val buffer = okio.Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private class FakeAuthApi : GatewayNativeAuthApi {
        var status = GatewayAuthStatus(authRequired = true, authFlows = setOf("native_pkce"))
        var refreshed: GatewayNativeTokens? = null
        var refreshToken: String? = null
        var exchangedCode: String? = null
        var exchangedVerifier: String? = null
        val ticketTokens = mutableListOf<String>()

        override suspend fun status(baseUrl: String): GatewayAuthStatus = status

        override suspend fun exchange(baseUrl: String, code: String, verifier: String): GatewayNativeTokens {
            exchangedCode = code
            exchangedVerifier = verifier
            return VALID_TOKENS
        }

        override suspend fun refresh(
            baseUrl: String,
            refreshToken: String,
            provider: String,
        ): GatewayNativeTokens? {
            this.refreshToken = refreshToken
            return refreshed
        }

        override suspend fun mintWebSocketTicket(baseUrl: String, accessToken: String): String {
            ticketTokens += accessToken
            return "ticket-${ticketTokens.size}"
        }
    }

    private class MemoryTokenStore(var tokens: GatewayNativeTokens?) : GatewayTokenStore {
        override suspend fun load(baseUrl: String): GatewayNativeTokens? = tokens
        override suspend fun save(baseUrl: String, tokens: GatewayNativeTokens) {
            this.tokens = tokens
        }
        override suspend fun clear(baseUrl: String) {
            tokens = null
        }
    }

    private class MemoryRemoteProfileStore(
        mode: GatewayConnectionMode,
        profile: RemoteGatewayProfile,
    ) : RemoteGatewayProfileStore {
        override val remoteGatewayProfile = MutableStateFlow(profile)
        override val gatewayConnectionMode = MutableStateFlow(mode)
        val profile: MutableStateFlow<RemoteGatewayProfile> = remoteGatewayProfile
        val mode: MutableStateFlow<GatewayConnectionMode> = gatewayConnectionMode
        override suspend fun saveRemoteGatewayProfile(profile: RemoteGatewayProfile) {
            remoteGatewayProfile.value = profile
        }
        override suspend fun saveGatewayConnectionMode(mode: GatewayConnectionMode) {
            gatewayConnectionMode.value = mode
        }
    }

    private class RecordingConnectionController : GatewayConnectionController {
        override val state = MutableStateFlow(GatewayConnectionState())
        val restored = mutableListOf<RemoteGatewayProfile>()
        var disconnects = 0

        override suspend fun connect(profile: HostProfile, credential: SshCredential): GatewayConnectResult =
            error("SSH must not be used during remote restore")

        override suspend fun connectRemote(
            profile: RemoteGatewayProfile,
            browser: GatewayBrowserLauncher,
        ): GatewayConnectResult = error("interactive login must not run during restore")

        override suspend fun restoreRemote(profile: RemoteGatewayProfile): GatewayConnectResult {
            restored += profile
            return GatewayConnectResult.Connected
        }

        override suspend fun forgetRemoteAuthentication(profile: RemoteGatewayProfile) = Unit
        override suspend fun disconnect() {
            disconnects += 1
        }
    }

    private class BlockingRestoreConnectionController : GatewayConnectionController {
        override val state = MutableStateFlow(GatewayConnectionState())
        val restoreStarted = CompletableDeferred<Unit>()
        var restoreCancelled = false
        var disconnects = 0

        override suspend fun connect(profile: HostProfile, credential: SshCredential): GatewayConnectResult =
            error("SSH must not be used during remote restore")

        override suspend fun connectRemote(
            profile: RemoteGatewayProfile,
            browser: GatewayBrowserLauncher,
        ): GatewayConnectResult = error("interactive login must not run during restore")

        override suspend fun restoreRemote(profile: RemoteGatewayProfile): GatewayConnectResult {
            restoreStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                restoreCancelled = true
            }
        }

        override suspend fun forgetRemoteAuthentication(profile: RemoteGatewayProfile) = Unit

        override suspend fun disconnect() {
            disconnects += 1
        }
    }

    private class FakeRpc : GatewayRpcClient {
        override val events = MutableSharedFlow<GatewayEvent>()
        override val closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val calls = mutableListOf<String>()
        var closedByClient = false

        override suspend fun request(method: String, params: JsonObject): JsonElement {
            calls += method
            return buildJsonObject {}
        }

        override fun close() {
            closedByClient = true
        }

        fun failFromServer() {
            closed.tryEmit(Unit)
        }
    }

    private class BlockingReadinessRpc : GatewayRpcClient {
        override val events = MutableSharedFlow<GatewayEvent>()
        override val closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        var closedByClient = false

        override suspend fun request(method: String, params: JsonObject): JsonElement {
            requestStarted.complete(Unit)
            withContext(NonCancellable) { releaseRequest.await() }
            return buildJsonObject {}
        }

        override fun close() {
            closedByClient = true
        }
    }

    /** Readiness round trip always fails: the retry loop's honest subject. */
    private class FailingAtReadinessRpc : GatewayRpcClient {
        override val events = MutableSharedFlow<GatewayEvent>()
        override val closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var closedByClient = false

        override suspend fun request(method: String, params: JsonObject): JsonElement =
            throw GatewayConnectionException("The Gateway could not be reached.")

        override fun close() {
            closedByClient = true
        }
    }

    private companion object {
        val PROFILE = RemoteGatewayProfile("https://gateway.example/hermes/", provider = "fixture-provider")
        val VALID_TOKENS = GatewayNativeTokens(
            accessToken = "access-fixture",
            refreshToken = "refresh-fixture",
            expiresAt = 10_000L,
            provider = "fixture-provider",
            userId = "fixture-user",
        )
    }
}
