package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.HostProfileStore
import com.hermesagent.mobile.data.ssh.SshCredential
import com.hermesagent.mobile.restoreSavedRemoteGateway
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
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

    /**
     * RFC 8252 §8.9. The loopback port is reachable by every process on the
     * device, so a request that cannot prove it came from this authorization
     * must not be able to do anything — least of all end the sign-in. Each of
     * these is an unauthenticated kill attempt that used to work, or would have.
     */
    @Test
    fun `an unauthenticated caller cannot end a sign-in, and the real callback still lands`() = runBlocking {
        val api = FakeAuthApi()
        val trace = SignInTrace()
        val login = LoopbackGatewayNativeLogin(
            api,
            log = GatewaySignInLog(trace::record),
            loginTimeoutMillis = FIXTURE_LOGIN_TIMEOUT_MILLIS,
        )
        val attacks = AtomicReference<List<String>>(emptyList())
        val callbackThread = AtomicReference<Thread>()

        val tokens = login.login(PROFILE, GatewayBrowserLauncher { url ->
            val parsed = requireNotNull(url.toHttpUrlOrNull())
            val redirect = requireNotNull(parsed.queryParameter("redirect_uri"))
            val port = requireNotNull(redirect.toHttpUrlOrNull()).port
            val state = requireNotNull(parsed.queryParameter("state"))
            callbackThread.set(
                Thread {
                    attacks.set(
                        listOf(
                            // A refusal with no state at all: the one-request kill.
                            rawRequest(port, "GET /callback?error=access_denied HTTP/1.1"),
                            // A refusal that guessed everything except state.
                            rawRequest(port, "GET /callback?error=access_denied&state=guessed HTTP/1.1"),
                            // Not a callback, not even a GET.
                            rawRequest(port, "POST /callback HTTP/1.1"),
                            rawRequest(port, "garbage"),
                            // A request line past the bound, which used to throw.
                            rawRequest(port, "GET /callback?x=" + "a".repeat(9_000) + " HTTP/1.1"),
                            // And a code with the wrong state.
                            rawRequest(port, "GET /callback?code=stolen&state=wrong HTTP/1.1"),
                        ),
                    )
                    // Only now, the real one.
                    readCallback("$redirect?code=code-fixture&state=$state")
                }.also(Thread::start),
            )
        })
        callbackThread.get()?.join(15_000)

        assertEquals("the sign-in survives every one of them", VALID_TOKENS, tokens)
        assertEquals("code-fixture", api.exchangedCode)
        for (served in attacks.get()) {
            assertTrue("an unauthenticated request gets the neutral page", served.contains("404 Not Found"))
            assertFalse("and is told nothing about the sign-in", served.contains("Signed in"))
            assertFalse(served.contains("Sign-in not accepted"))
        }
        // No step past the gate was ever reached for them, and the one callback
        // that could prove itself is the only one that was received at all.
        assertEquals(1, trace.snapshot().count { it == GatewaySignInStep.CallbackReceived.toString() })
        assertEquals(1, trace.snapshot().count { it == GatewaySignInStep.CallbackAccepted.toString() })
    }

    @Test
    fun `a refusal that carries the right state is surfaced`() = runBlocking {
        val api = FakeAuthApi()
        val trace = SignInTrace()
        val login = LoopbackGatewayNativeLogin(
            api,
            log = GatewaySignInLog(trace::record),
            loginTimeoutMillis = FIXTURE_LOGIN_TIMEOUT_MILLIS,
        )
        val page = AtomicReference<String>()
        val callbackThread = AtomicReference<Thread>()

        val failure = runCatching {
            login.login(PROFILE, GatewayBrowserLauncher { url ->
                val parsed = requireNotNull(url.toHttpUrlOrNull())
                val redirect = requireNotNull(parsed.queryParameter("redirect_uri"))
                val state = requireNotNull(parsed.queryParameter("state"))
                callbackThread.set(
                    Thread {
                        page.set(readCallback("$redirect?error=access_denied&state=$state"))
                    }.also(Thread::start),
                )
            })
        }.exceptionOrNull()
        callbackThread.get()?.join(5_000)

        assertTrue(failure is GatewayAuthException)
        assertEquals(GatewaySignInCopy.REFUSED, failure?.message)
        assertNull("a refused sign-in is never dialled", api.exchangedCode)
        val served = requireNotNull(page.get())
        assertTrue(served.contains("Sign-in not accepted"))
        assertFalse("the refusal page must not claim a sign-in", served.contains("Signed in"))
        assertEquals(
            listOf(
                GatewaySignInStep.ListenerBound.toString(),
                // No platform to hold in a JVM test; the step is logged either
                // way so a device trace always says which it was.
                GatewaySignInStep.ForegroundUnavailable.toString(),
                GatewaySignInStep.BrowserUnbound.toString(),
                GatewaySignInStep.CallbackReceived.toString(),
                GatewaySignInStep.CallbackRefused.toString(),
            ),
            trace.snapshot(),
        )
    }

    @Test
    fun `the signed-in page and the hand back to the app come only after validation`() = runBlocking {
        val trace = SignInTrace()
        val api = TracingAuthApi(trace)
        val login = LoopbackGatewayNativeLogin(api, log = GatewaySignInLog(trace::record), loginTimeoutMillis = FIXTURE_LOGIN_TIMEOUT_MILLIS)
        val page = AtomicReference<String>()
        val browser = TracingBrowser(trace) { redirect, state ->
            page.set(readCallback("$redirect?code=code-fixture&state=$state"))
        }

        val tokens = login.login(PROFILE, browser)
        browser.await()

        assertEquals(VALID_TOKENS, tokens)
        assertTrue(requireNotNull(page.get()).contains("Signed in to Hermes"))
        assertEquals(
            listOf(
                GatewaySignInStep.ListenerBound.toString(),
                GatewaySignInStep.ForegroundUnavailable.toString(),
                GatewaySignInStep.BrowserBound.toString(),
                TracingBrowser.OPENED,
                GatewaySignInStep.CallbackReceived.toString(),
                GatewaySignInStep.CallbackAccepted.toString(),
                // Logged after the coroutine resumes, which is what makes
                // "accepted, then nothing" tell you something on a device.
                GatewaySignInStep.CodeReceived.toString(),
                // And straight to the wire. Coming forward belongs after the
                // tokens are persisted, which is a layer up
                // ([NativeGatewayAuthenticator.signIn]), never in this window.
                TracingAuthApi.EXCHANGED,
            ),
            trace.snapshot(),
        )
        assertFalse("the login driver must not try to come forward", browser.returnedToApp)
        // Same `finally` as the listener: the binding that kept this process
        // runnable is not left behind once the flow is over.
        assertTrue("the browser binding must be released with the listener", browser.bindingClosed)
    }

    @Test
    fun `a callback listener that closes mid-flow surfaces an actionable failure`() = runBlocking {
        val api = FakeAuthApi()
        val trace = SignInTrace()
        val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 100 }
        val login = LoopbackGatewayNativeLogin(
            api,
            log = GatewaySignInLog(trace::record),
            openListener = { listener },
            loginTimeoutMillis = FIXTURE_LOGIN_TIMEOUT_MILLIS,
        )
        val callbackThread = AtomicReference<Thread>()

        val failure = runCatching {
            login.login(PROFILE, GatewayBrowserLauncher { url ->
                val redirect = requireNotNull(requireNotNull(url.toHttpUrlOrNull()).queryParameter("redirect_uri"))
                callbackThread.set(
                    Thread {
                        listener.close()
                        runCatching { readCallback("$redirect?code=code-fixture&state=whatever") }
                    }.also(Thread::start),
                )
            })
        }.exceptionOrNull()
        callbackThread.get()?.join(5_000)

        assertTrue(failure is GatewayAuthException)
        assertEquals(GatewaySignInCopy.LISTENER_CLOSED, failure?.message)
        assertNull(api.exchangedCode)
        assertTrue(trace.snapshot().contains(GatewaySignInStep.ListenerClosed.toString()))
    }

    @Test
    fun `an authorization code the Gateway will not redeem says so and does not retry`() = runTest {
        var calls = 0
        val refusing = OkHttpGatewayNativeAuthApi(
            OkHttpClient.Builder().addInterceptor { chain ->
                calls += 1
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(400)
                    .message("Bad Request")
                    .body("""{"detail":"Invalid or expired authorization code."}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }.build(),
        )

        val failure = runCatching {
            refusing.exchange("https://gateway.example/hermes", "stale-code", "verifier")
        }.exceptionOrNull()

        assertTrue(failure is GatewayAuthException)
        assertEquals(GatewaySignInCopy.EXPIRED_CODE, failure?.message)
        // The Gateway answered, so the code is spent. Presenting it again could
        // only ever be refused again.
        assertEquals("a refusal is never retried", 1, calls)
        // Only a fresh sign-in mints another code, so the redial loop must stop.
        assertFalse(requireNotNull(failure).isRetryableRemoteConnectionFailure())
    }

    @Test
    fun `abandoning a sign-in that reached the browser says so instead of going quiet`() = runTest {
        val handedOff = CompletableDeferred<Unit>()
        val authenticator = NativeGatewayAuthenticator(
            api = FakeAuthApi(),
            store = MemoryTokenStore(null),
            login = GatewayNativeLogin { _, browser ->
                browser.open("https://gateway.example/hermes/auth/native/authorize")
                awaitCancellation()
            },
            nowSeconds = { 1_000L },
        )
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> FakeRpc() },
        )

        manager.startRemoteSignIn(PROFILE, GatewayBrowserLauncher { handedOff.complete(Unit) })
        runCurrent()
        assertTrue("the flow must have reached the browser", handedOff.isCompleted)

        manager.cancelRemoteSignIn()
        runCurrent()

        assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
        assertEquals(GatewaySignInCopy.CANCELLED, manager.state.value.message)
    }

    /**
     * The emulator repro for #114 in the shape a JVM test can hold: a valid
     * callback that lands while the app is in the background, which used to
     * serve its "Signed in" page and then produce no token request at all.
     *
     * Backgrounding here is the app's own lifecycle signal, not a process
     * freeze and not a cancellation — those two are covered by
     * `a sign-in outlives the screen that started it` and
     * `abandoning a sign-in never publishes the connection it opened`. What
     * this pins is narrower and still worth pinning: nothing on the
     * foreground/background edge fences an interactive open, so the exchange
     * crosses that edge and the connection still comes up.
     */
    @Test
    fun `a callback that lands while the app is backgrounded still exchanges the code`() = runTest {
        val api = FakeAuthApi()
        val callbackLanded = CompletableDeferred<Unit>()
        val handedOff = CompletableDeferred<Unit>()
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = MemoryTokenStore(null),
            login = GatewayNativeLogin { profile, browser ->
                browser.open("https://gateway.example/hermes/auth/native/authorize")
                handedOff.complete(Unit)
                // The person is in the browser. Nothing happens here until the
                // callback lands, which is the whole window under test.
                callbackLanded.await()
                api.exchange(requireNotNull(profile.normalizedBaseUrl), "code-while-backgrounded", "verifier")
            },
            nowSeconds = { 1_000L },
        )
        val rpc = FakeRpc()
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> rpc },
        )

        manager.startRemoteSignIn(PROFILE, GatewayBrowserLauncher {})
        runCurrent()
        assertTrue(handedOff.isCompleted)

        // Android stops the app behind the browser.
        manager.applicationForegroundChanged(false)
        runCurrent()

        callbackLanded.complete(Unit)
        runCurrent()

        assertEquals("the token exchange must happen while the app is away", "code-while-backgrounded", api.exchangedCode)
        assertEquals(listOf("session.list"), rpc.calls)
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
        manager.disconnect()
    }

    /** The same edge, on the leg that reports rather than connects. */
    @Test
    fun `a refusal that lands while the app is backgrounded is still surfaced`() = runTest {
        val handedOff = CompletableDeferred<Unit>()
        val callbackLanded = CompletableDeferred<Unit>()
        val authenticator = NativeGatewayAuthenticator(
            api = FakeAuthApi(),
            store = MemoryTokenStore(null),
            login = GatewayNativeLogin { _, browser ->
                browser.open("https://gateway.example/hermes/auth/native/authorize")
                handedOff.complete(Unit)
                callbackLanded.await()
                throw GatewayAuthException(GatewaySignInCopy.REFUSED)
            },
            nowSeconds = { 1_000L },
        )
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> FakeRpc() },
        )

        manager.startRemoteSignIn(PROFILE, GatewayBrowserLauncher {})
        runCurrent()
        assertTrue(handedOff.isCompleted)
        manager.applicationForegroundChanged(false)
        runCurrent()

        callbackLanded.complete(Unit)
        runCurrent()

        assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
        assertEquals(GatewaySignInCopy.REFUSED, manager.state.value.message)
    }

    /**
     * The fix itself, proved by contrast rather than asserted.
     *
     * Both halves run the identical sign-in against the identical manager. The
     * only difference is who owns the coroutine: the first is the old shape,
     * started from the screen's scope, and dies when Android destroys that
     * screen behind the open browser — the callback then has nothing to come
     * back to and no token is ever requested, though it is at least reported
     * now rather than silent. The second is what ships, owned by the process,
     * and completes through the same destruction.
     */
    @Test
    fun `a sign-in outlives the screen that started it, where the old shape died with it`() = runTest {
        suspend fun signIn(
            start: (GatewayConnectionManager, GatewayBrowserLauncher) -> Unit,
        ): Pair<FakeAuthApi, GatewayConnectionManager> {
            val api = FakeAuthApi()
            val handedOff = CompletableDeferred<Unit>()
            val callbackLanded = CompletableDeferred<Unit>()
            val authenticator = NativeGatewayAuthenticator(
                api = api,
                store = MemoryTokenStore(null),
                login = GatewayNativeLogin { profile, browser ->
                    browser.open("https://gateway.example/hermes/auth/native/authorize")
                    handedOff.complete(Unit)
                    callbackLanded.await()
                    api.exchange(requireNotNull(profile.normalizedBaseUrl), "code-after-destroy", "verifier")
                },
                nowSeconds = { 1_000L },
            )
            val manager = GatewayConnectionManager(
                scope = backgroundScope,
                installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
                remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> FakeRpc() },
            )
            start(manager, GatewayBrowserLauncher {})
            runCurrent()
            assertTrue(handedOff.isCompleted)
            callbackLanded.complete(Unit)
            runCurrent()
            return api to manager
        }

        // The old shape: the screen owns the coroutine, and Android destroys it
        // while the person is still in the browser.
        val screenScope = CoroutineScope(coroutineContext + Job())
        val (oldApi, oldManager) = signIn { manager, browser ->
            screenScope.launch(start = CoroutineStart.UNDISPATCHED) { manager.connectRemote(PROFILE, browser) }
            screenScope.cancel()
        }
        assertNull("this is the bug: the callback comes back to nothing", oldApi.exchangedCode)
        // It is at least no longer silent — the abandonment is now reported
        // rather than published as a bare `Disconnected`. But a message is not a
        // sign-in, which is exactly why ownership had to move.
        assertEquals(GatewayConnectionStatus.NeedsAttention, oldManager.state.value.status)
        assertEquals(GatewaySignInCopy.CANCELLED, oldManager.state.value.message)

        // What ships: the process owns it, and the same destruction changes
        // nothing about the sign-in.
        val rebuiltScreenScope = CoroutineScope(coroutineContext + Job())
        val (newApi, newManager) = signIn { manager, browser ->
            manager.startRemoteSignIn(PROFILE, browser)
            rebuiltScreenScope.cancel()
        }
        assertEquals("code-after-destroy", newApi.exchangedCode)
        assertEquals(GatewayConnectionStatus.Connected, newManager.state.value.status)
        newManager.disconnect()
    }

    @Test
    fun `abandoning a sign-in never publishes the connection it opened`() = runTest {
        val released = CompletableDeferred<Unit>()
        val handedOff = CompletableDeferred<Unit>()
        val authenticator = NativeGatewayAuthenticator(
            api = FakeAuthApi(),
            store = MemoryTokenStore(null),
            login = GatewayNativeLogin { _, browser ->
                browser.open("https://gateway.example/hermes/auth/native/authorize")
                handedOff.complete(Unit)
                withContext(NonCancellable) { released.await() }
                VALID_TOKENS
            },
            nowSeconds = { 1_000L },
        )
        val rpc = FakeRpc()
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> rpc },
        )

        manager.startRemoteSignIn(PROFILE, GatewayBrowserLauncher {})
        runCurrent()
        assertTrue(handedOff.isCompleted)

        // Abandoned while the sign-in is uncancellable, so it finishes and
        // arrives at the publish phase already cancelled.
        manager.cancelRemoteSignIn()
        released.complete(Unit)
        runCurrent()

        assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
        assertEquals(GatewaySignInCopy.CANCELLED, manager.state.value.message)
        assertNull("an abandoned sign-in must not leave a live connection", manager.client.value)
        assertTrue("the socket it opened has to be put down", rpc.closedByClient)
    }

    /**
     * The shape #114's device run never had: a crash in this app's own sign-in
     * plumbing must name itself, not borrow the copy for an unreachable host.
     *
     * A device that will not bind a loopback port is the concrete case — an
     * emulator image with a restrictive policy, or a port the kernel refuses —
     * and it lands above the first breadcrumb, which is exactly where a whole
     * device run was spent failing to localize.
     */
    @Test
    fun `a listener this device will not bind is reported as our own failure, not the host's`() = runBlocking {
        val api = FakeAuthApi()
        val trace = SignInTrace()
        val login = LoopbackGatewayNativeLogin(
            api,
            log = trace.asLog(),
            openListener = { throw java.net.BindException("EACCES (Permission denied)") },
            loginTimeoutMillis = FIXTURE_LOGIN_TIMEOUT_MILLIS,
        )

        val failure = runCatching {
            login.login(PROFILE, GatewayBrowserLauncher { error("the browser must never be reached") })
        }.exceptionOrNull()

        assertTrue(failure is GatewayAuthException)
        assertEquals(GatewaySignInCopy.START_FAILED, failure?.message)
        assertNull(api.exchangedCode)
        // And the one line a device run needs: the step, and the type that broke it.
        assertEquals(
            listOf("${GatewaySignInStep.SignInStartFailed} (java.net.BindException)"),
            trace.snapshot(),
        )
    }

    @Test
    fun `a browser that will not launch is reported as a browser problem`() = runBlocking {
        val api = FakeAuthApi()
        val trace = SignInTrace()
        val login = LoopbackGatewayNativeLogin(
            api,
            log = trace.asLog(),
            loginTimeoutMillis = FIXTURE_LOGIN_TIMEOUT_MILLIS,
        )

        val failure = runCatching {
            login.login(
                PROFILE,
                // What a real platform throws when an Application context starts
                // an Activity the system will not let it start.
                GatewayBrowserLauncher { throw IllegalStateException("no activity") },
            )
        }.exceptionOrNull()

        assertTrue(failure is GatewayAuthException)
        assertEquals(GatewaySignInCopy.BROWSER_LAUNCH_FAILED, failure?.message)
        assertNull(api.exchangedCode)
        assertEquals(
            listOf(
                GatewaySignInStep.ListenerBound.toString(),
                // No platform to hold in a JVM test; the step is logged either
                // way so a device trace always says which it was.
                GatewaySignInStep.ForegroundUnavailable.toString(),
                GatewaySignInStep.BrowserUnbound.toString(),
                "${GatewaySignInStep.BrowserLaunchFailed} (java.lang.IllegalStateException)",
            ),
            trace.snapshot(),
        )
    }

    @Test
    fun `a browser service that will not bind costs the protection, never the sign-in`() = runBlocking {
        val api = FakeAuthApi()
        val trace = SignInTrace()
        val login = LoopbackGatewayNativeLogin(
            api,
            log = trace.asLog(),
            loginTimeoutMillis = FIXTURE_LOGIN_TIMEOUT_MILLIS,
        )
        val callbackThread = AtomicReference<Thread>()

        val tokens = login.login(
            PROFILE,
            object : GatewayBrowserLauncher {
                override suspend fun bindForSignIn(): AutoCloseable =
                    throw SecurityException("not allowed to bind that service")

                override suspend fun open(url: String) {
                    val parsed = requireNotNull(url.toHttpUrlOrNull())
                    val redirect = requireNotNull(parsed.queryParameter("redirect_uri"))
                    val state = requireNotNull(parsed.queryParameter("state"))
                    callbackThread.set(
                        Thread { readCallback("$redirect?code=code-unbound&state=$state") }.also(Thread::start),
                    )
                }
            },
        )
        callbackThread.get()?.join(15_000)

        assertEquals("the sign-in completes unprotected rather than not at all", VALID_TOKENS, tokens)
        assertEquals("code-unbound", api.exchangedCode)
        val steps = trace.snapshot()
        assertTrue(steps.contains("${GatewaySignInStep.BrowserBindFailed} (java.lang.SecurityException)"))
        assertTrue(steps.contains(GatewaySignInStep.BrowserUnbound.toString()))
        assertTrue(steps.contains(GatewaySignInStep.CallbackAccepted.toString()))
    }

    @Test
    fun `a crash in our own plumbing never tells the person to check their host`() = runTest {
        val appFailures = mutableListOf<String>()
        val authenticator = NativeGatewayAuthenticator(
            api = FakeAuthApi(),
            store = MemoryTokenStore(null),
            login = GatewayNativeLogin { _, _ -> throw NullPointerException("a bug in this app") },
            nowSeconds = { 1_000L },
        )
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> FakeRpc() },
            logAppFailure = { appFailures += it },
        )

        val result = manager.connectRemote(PROFILE, GatewayBrowserLauncher {})

        assertTrue(result is GatewayConnectResult.Failed)
        val message = manager.state.value.message
        assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
        assertFalse(
            "the host is the one thing that is demonstrably fine here",
            message.orEmpty().contains("Check the host"),
        )
        assertTrue(message.orEmpty().contains("problem in the app"))
        assertEquals(listOf("java.lang.NullPointerException"), appFailures)
    }

    /**
     * The r3 blocker: a person who takes ten seconds to type a password.
     *
     * By then Android has withdrawn this app's background activity-launch
     * grace, so bringing it forward fails — and it used to fail *between* the
     * callback being accepted and the code being spent, which threw away a
     * sign-in that had already succeeded and left no error behind it. Coming
     * forward is now the last thing that happens and cannot cost anything.
     */
    @Test
    fun `a blocked return to the app never costs a sign-in that already succeeded`() = runTest {
        val trace = SignInTrace()
        val store = MemoryTokenStore(null)
        val authenticator = NativeGatewayAuthenticator(
            api = FakeAuthApi(),
            store = store,
            login = GatewayNativeLogin { _, _ -> VALID_TOKENS },
            nowSeconds = { 1_000L },
            log = trace.asLog(),
        )

        val ticket = authenticator.ticket(
            PROFILE,
            object : GatewayBrowserLauncher {
                override suspend fun open(url: String) = Unit

                override suspend fun returnToApp(): Unit =
                    throw SecurityException("Background activity launch blocked!")
            },
        )

        assertEquals("the connection still comes up", "ticket-1", ticket)
        assertEquals("and the sign-in is on disk", VALID_TOKENS, store.tokens)
        assertEquals(
            listOf(
                GatewaySignInStep.TokensStored.toString(),
                "${GatewaySignInStep.ReturnBlocked} (java.lang.SecurityException)",
            ),
            trace.snapshot(),
        )
    }

    /**
     * The commoner shape, and the one nothing can detect: Android logs
     * "Background activity launch blocked!" and `startActivity` returns
     * normally. There is no failure to handle, so the only defence is that the
     * sign-in never depended on it.
     */
    @Test
    fun `a return to the app that is silently ignored still finishes the sign-in`() = runTest {
        val trace = SignInTrace()
        val store = MemoryTokenStore(null)
        var attempted = false
        val authenticator = NativeGatewayAuthenticator(
            api = FakeAuthApi(),
            store = store,
            login = GatewayNativeLogin { _, _ -> VALID_TOKENS },
            nowSeconds = { 1_000L },
            log = trace.asLog(),
        )

        val ticket = authenticator.ticket(
            PROFILE,
            object : GatewayBrowserLauncher {
                override suspend fun open(url: String) = Unit

                override suspend fun returnToApp() {
                    attempted = true
                }
            },
        )

        assertTrue("it is still attempted", attempted)
        assertEquals("ticket-1", ticket)
        assertEquals(VALID_TOKENS, store.tokens)
        assertEquals(listOf(GatewaySignInStep.TokensStored.toString()), trace.snapshot())
    }

    @Test
    fun `the sign-in is spent and stored strictly before the app tries to come forward`() = runTest {
        val order = SignInTrace()
        val authenticator = NativeGatewayAuthenticator(
            api = FakeAuthApi(),
            store = object : GatewayTokenStore {
                override suspend fun load(slot: GatewaySecretSlot): GatewayNativeTokens? = null

                override suspend fun save(slot: GatewaySecretSlot, tokens: GatewayNativeTokens) {
                    order.record("stored")
                }

                override suspend fun clear(slot: GatewaySecretSlot) = Unit
            },
            login = GatewayNativeLogin { _, _ ->
                order.record("exchanged")
                VALID_TOKENS
            },
            nowSeconds = { 1_000L },
        )

        authenticator.ticket(
            PROFILE,
            object : GatewayBrowserLauncher {
                override suspend fun open(url: String) = Unit

                override suspend fun returnToApp() = order.record("came forward")
            },
        )

        // The code is single use and expires in 120 s. Everything up to
        // "stored" is irreversible work on a deadline; what follows is a
        // courtesy that is allowed to fail.
        assertEquals(listOf("exchanged", "stored", "came forward"), order.snapshot())
    }

    /**
     * The r4 blocker. Leaving for a Custom Tab makes Android re-evaluate the
     * default network, and the monitor reports the new one — about five seconds
     * later, which is why a four-second redirect completed and a six-second one
     * did not.
     *
     * A browser round trip is a person typing a password. It is expected to
     * span network events, it is bounded by its own five-minute timeout, and the
     * authorization code it is waiting for is single use with a 120 s life. A
     * network edge must not spend it.
     */
    @Test
    fun `a network event while the browser is open never cancels the sign-in`() = runTest {
        val api = FakeAuthApi()
        val handedOff = CompletableDeferred<Unit>()
        val callbackLanded = CompletableDeferred<Unit>()
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = MemoryTokenStore(null),
            login = GatewayNativeLogin { profile, browser ->
                browser.open("https://gateway.example/hermes/auth/native/authorize")
                handedOff.complete(Unit)
                callbackLanded.await()
                api.exchange(requireNotNull(profile.normalizedBaseUrl), "code-across-network-event", "verifier")
            },
            nowSeconds = { 1_000L },
        )
        val rpc = FakeRpc()
        val cancels = mutableListOf<String>()
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> rpc },
            reconnectWait = {},
            logConnectEvent = { cancels += it },
        )

        manager.startRemoteSignIn(PROFILE, GatewayBrowserLauncher {})
        runCurrent()
        assertTrue("the flow must have reached the browser", handedOff.isCompleted)

        // The person is in the browser and the default network is re-evaluated.
        manager.applicationForegroundChanged(false)
        manager.networkAvailabilityChanged(true)
        runCurrent()

        // And the same through the coarser seam, which callers that cannot tell
        // loss from recovery use and which cancels on exactly the same grounds.
        manager.networkChanged()
        runCurrent()

        // Then they finish typing and the callback lands.
        callbackLanded.complete(Unit)
        runCurrent()

        assertEquals("the code must still be spent", "code-across-network-event", api.exchangedCode)
        assertEquals(listOf("session.list"), rpc.calls)
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
        assertEquals(
            "nothing may have invalidated or abandoned it on the way",
            emptyList<String>(),
            cancels.filter { it.contains("invalidated") || it.contains("abandoned") },
        )
        manager.disconnect()
    }

    /**
     * A cancel bumps the connect-intent generation, which is what stops a
     * superseded attempt overwriting the state of the one that replaced it —
     * and which also means a cancelled connect publishes nothing anywhere. Two
     * device runs were spent on a cancel nobody could see.
     */
    @Test
    fun `a cancelled connect names who cancelled it`() = runTest {
        val events = mutableListOf<String>()
        val handedOff = CompletableDeferred<Unit>()
        val authenticator = NativeGatewayAuthenticator(
            api = FakeAuthApi(),
            store = MemoryTokenStore(null),
            login = GatewayNativeLogin { _, browser ->
                browser.open("https://gateway.example/hermes/auth/native/authorize")
                handedOff.complete(Unit)
                awaitCancellation()
            },
            nowSeconds = { 1_000L },
        )
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> FakeRpc() },
            logConnectEvent = { events += it },
        )

        manager.startRemoteSignIn(PROFILE, GatewayBrowserLauncher {})
        runCurrent()
        assertTrue(handedOff.isCompleted)

        manager.disconnect()
        runCurrent()

        assertTrue(
            events.contains("connect intent invalidated by an explicit disconnect (live job: true)"),
        )
    }



    /**
     * The exemption is a counter, and a counter that never comes back down is
     * worse than no exemption at all: every later network edge would be
     * silently ignored for the life of the process.
     */
    @Test
    fun `the network exemption lifts once the sign-in is over`() = runTest {
        val events = mutableListOf<String>()
        val handedOff = CompletableDeferred<Unit>()
        val callbackLanded = CompletableDeferred<Unit>()
        val api = FakeAuthApi()
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = MemoryTokenStore(null),
            login = GatewayNativeLogin { _, browser ->
                browser.open("https://gateway.example/hermes/auth/native/authorize")
                handedOff.complete(Unit)
                callbackLanded.await()
                VALID_TOKENS
            },
            nowSeconds = { 1_000L },
        )
        val parked = BlockingReadinessRpc()
        val rpcs = ArrayDeque(listOf<GatewayRpcClient>(FakeRpc(), parked))
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            // Endless supply, and the real virtual-time backoff: the cancelled
            // restore below arms a retry loop, and a zero wait plus an exhausted
            // queue would spin it forever inside `runCurrent`.
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ ->
                if (rpcs.isEmpty()) FakeRpc() else rpcs.removeFirst()
            },
            logConnectEvent = { events += it },
        )

        // One whole sign-in, so the counter goes up and has to come back down.
        manager.startRemoteSignIn(PROFILE, GatewayBrowserLauncher {})
        runCurrent()
        assertTrue(handedOff.isCompleted)
        callbackLanded.complete(Unit)
        runCurrent()
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)

        // Now an open that is *not* a sign-in, parked at its readiness round
        // trip. A counter stuck positive would exempt this too, and the edge
        // below would cancel nothing.
        val restore = backgroundScope.launch { manager.restoreRemote(PROFILE) }
        parked.requestStarted.await()
        manager.networkAvailabilityChanged(true)
        runCurrent()

        assertTrue(
            "a network edge must cancel an ordinary open again",
            events.contains("connect intent invalidated by a network availability edge (live job: true)"),
        )
        parked.releaseRequest.complete(Unit)
        restore.cancel()
    }

    /**
     * The one path that cancelled a sign-in without touching the connect intent,
     * and therefore without tripping any of the intent instrumentation: the
     * screen abandoning it. On a device this looked exactly like the flow simply
     * stopping after `callback accepted`.
     */
    @Test
    fun `a sign-in abandoned by the screen names the screen`() = runTest {
        val events = mutableListOf<String>()
        val handedOff = CompletableDeferred<Unit>()
        val authenticator = NativeGatewayAuthenticator(
            api = FakeAuthApi(),
            store = MemoryTokenStore(null),
            login = GatewayNativeLogin { _, browser ->
                browser.open("https://gateway.example/hermes/auth/native/authorize")
                handedOff.complete(Unit)
                awaitCancellation()
            },
            nowSeconds = { 1_000L },
        )
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> FakeRpc() },
            logConnectEvent = { events += it },
        )

        manager.startRemoteSignIn(PROFILE, GatewayBrowserLauncher {})
        runCurrent()
        assertTrue(handedOff.isCompleted)

        manager.cancelRemoteSignIn()
        runCurrent()

        assertTrue(events.contains("sign-in abandoned by the screen"))
        assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
        assertEquals(GatewaySignInCopy.CANCELLED, manager.state.value.message)
    }

    /**
     * A superseded open publishes nothing, so that a dead attempt cannot
     * overwrite its replacement — which also meant an interactive sign-in could
     * be killed by a bare invalidation and say nothing at all. It now speaks
     * when nothing took over.
     *
     * Driven through [GatewayConnectionManager.connectRemote], which does not
     * claim the sign-in exemption: every invalidator reachable from the app is
     * exempt now, which is the point, and this is what remains testable.
     */
    @Test
    fun `an interactive open killed by an invalidation is never left silent`() = runTest {
        val handedOff = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val authenticator = NativeGatewayAuthenticator(
            api = FakeAuthApi(),
            store = MemoryTokenStore(null),
            login = GatewayNativeLogin { _, browser ->
                browser.open("https://gateway.example/hermes/auth/native/authorize")
                handedOff.complete(Unit)
                awaitCancellation()
            },
            nowSeconds = { 1_000L },
        )
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> FakeRpc() },
            logConnectEvent = { events += it },
        )

        val attempt = backgroundScope.launch { manager.connectRemote(PROFILE, GatewayBrowserLauncher {}) }
        handedOff.await()

        manager.networkChanged()
        runCurrent()

        assertTrue(
            events.any { it.startsWith("connect intent invalidated by a network change") },
        )
        assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
        assertNotNull("a spent sign-in must not end in silence", manager.state.value.message)
        attempt.cancel()
    }

    /**
     * r6's dark window, lit. The device sequence ended
     * `callback accepted -> authorization code in hand -> nothing, 96 s`, with
     * no cancellation and no freeze: the exchange itself stalled with no bytes
     * sent and no exception. These four steps say which half of it stalled.
     */
    @Test
    fun `the token exchange reports every step it passes through`() = runTest {
        val trace = SignInTrace()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """{"access_token":"a","refresh_token":"r","expires_at":9000,"provider":"p","user_id":"u"}"""
                        .toResponseBody("application/json".toMediaType()),
                )
                .build()
        }.build()

        OkHttpGatewayNativeAuthApi(http, log = trace.asLog())
            .exchange("https://gateway.example/hermes", "code", "verifier")

        assertEquals(
            listOf(
                GatewaySignInStep.ExchangeStarting.toString(),
                GatewaySignInStep.ExchangeDispatched.toString(),
                GatewaySignInStep.ExchangeRequesting.toString(),
                GatewaySignInStep.ExchangeAnswered.toString(),
            ),
            trace.snapshot(),
        )
    }

    /**
     * The bound that actually stops a stall. `connectTimeout` does not cover
     * name resolution — `Dns.SYSTEM` is `InetAddress.getAllByName`, which blocks
     * in the platform resolver with no timeout of its own — so a client with
     * OkHttp's defaults can sit in one call forever. It did.
     */
    @Test
    fun `the auth wire bounds the whole call, not just the connect`() {
        assertEquals(
            "OkHttp's own default is unbounded, which is the bug",
            0,
            OkHttpClient().callTimeoutMillis,
        )
        assertEquals(
            OkHttpGatewayNativeAuthApi.AUTH_CALL_TIMEOUT_MILLIS.toInt(),
            OkHttpGatewayNativeAuthApi(OkHttpClient()).callTimeoutMillis,
        )
    }

    @Test
    fun `an exchange that never answers ends in a message, not silence`() = runBlocking {
        val trace = SignInTrace()
        val login = LoopbackGatewayNativeLogin(
            ParkingExchangeApi(),
            log = trace.asLog(),
            loginTimeoutMillis = FIXTURE_LOGIN_TIMEOUT_MILLIS,
            exchangeTimeoutMillis = 200L,
        )
        val callbackThread = AtomicReference<Thread>()

        val failure = runCatching {
            login.login(PROFILE, GatewayBrowserLauncher { url ->
                val parsed = requireNotNull(url.toHttpUrlOrNull())
                val redirect = requireNotNull(parsed.queryParameter("redirect_uri"))
                val state = requireNotNull(parsed.queryParameter("state"))
                callbackThread.set(
                    Thread { readCallback("$redirect?code=code-parked&state=$state") }.also(Thread::start),
                )
            })
        }.exceptionOrNull()
        callbackThread.get()?.join(15_000)

        assertTrue(failure is GatewayAuthException)
        assertEquals(GatewaySignInCopy.EXCHANGE_TIMED_OUT, failure?.message)
        val steps = trace.snapshot()
        assertTrue(steps.contains(GatewaySignInStep.CodeReceived.toString()))
        assertTrue("the silence has to end in a named step", steps.contains(GatewaySignInStep.ExchangeTimedOut.toString()))
    }

    /** An exchange that parks forever, the way a stalled resolver does. */
    private class ParkingExchangeApi : GatewayNativeAuthApi {
        override suspend fun status(baseUrl: String) =
            GatewayAuthStatus(authRequired = true, authFlows = setOf("native_pkce"))

        override suspend fun exchange(baseUrl: String, code: String, verifier: String): GatewayNativeTokens =
            awaitCancellation()

        override suspend fun refresh(
            baseUrl: String,
            refreshToken: String,
            provider: String,
        ): GatewayNativeTokens = error("a parked exchange never rotates")

        override suspend fun mintWebSocketTicket(baseUrl: String, accessToken: String) = "ticket-parked"
    }

    /**
     * The r6 stall, turned into a sign-in instead of a polite failure.
     *
     * The first attempt dies before the Gateway ever answers — name resolution
     * on a handle the default-network re-evaluation killed. By the time the call
     * timeout expires the new network resolves immediately, so the same request
     * simply works, and the code still has most of its 120 s left.
     */
    @Test
    fun `an exchange that never reached the Gateway is presented once more`() = runTest {
        val trace = SignInTrace()
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls += 1
            if (calls == 1) throw java.net.SocketTimeoutException("timeout")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """{"access_token":"access-retried","refresh_token":"r","expires_at":9000,"provider":"p","user_id":"u"}"""
                        .toResponseBody("application/json".toMediaType()),
                )
                .build()
        }.build()

        val tokens = OkHttpGatewayNativeAuthApi(http, log = trace.asLog())
            .exchange("https://gateway.example/hermes", "code-unanswered", "verifier")

        assertEquals(2, calls)
        assertEquals("access-retried", tokens.accessToken)
        assertTrue(trace.snapshot().contains(GatewaySignInStep.ExchangeRetrying.toString()))
    }

    @Test
    fun `an exchange the Gateway never answers twice gives up rather than looping`() = runTest {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { _ ->
            calls += 1
            throw java.net.SocketTimeoutException("timeout")
        }.build()

        val failure = runCatching {
            OkHttpGatewayNativeAuthApi(http).exchange("https://gateway.example/hermes", "code", "verifier")
        }.exceptionOrNull()

        assertEquals("exactly one retry, never a loop", 2, calls)
        assertTrue(failure is GatewayUnansweredException)
    }

    /**
     * r7 was spent on `failed (GatewayUnansweredException)` — the name of this
     * codebase's own envelope, which says only "the call failed", which was
     * already known. The chain is the answer; the messages inside it are never
     * safe to log.
     */
    @Test
    fun `a failure renders as its whole cause chain, types only`() {
        val wrapped = GatewayUnansweredException(
            java.net.SocketException("connect failed to gateway.example:8443"),
        )
        assertEquals(
            "com.hermesagent.mobile.data.gateway.GatewayUnansweredException < java.net.SocketException",
            throwableChain(wrapped),
        )

        val deep = IllegalStateException(
            "outer",
            java.io.IOException("middle", java.net.SocketException("inner")),
        )
        val chain = throwableChain(deep)
        assertEquals(
            "java.lang.IllegalStateException < java.io.IOException < java.net.SocketException",
            chain,
        )
        assertFalse("a host must never reach logcat", chain.contains("gateway.example"))
        assertFalse(chain.contains("outer"))

        // And it is bounded, so a pathological chain cannot become the log.
        var nested: Throwable = IllegalStateException("deepest")
        repeat(12) { nested = java.io.IOException("layer", nested) }
        assertTrue(throwableChain(nested).endsWith("..."))
    }

    /**
     * An immediate retry down the same pooled socket is not a second attempt,
     * it is the same attempt twice — r7 measured 2 ms between them and an
     * identical failure. The second one has to be able to differ.
     */
    @Test
    fun `the one retry evicts its pool and waits for a usable network first`() = runTest {
        val order = SignInTrace()
        val waits = mutableListOf<Long>()
        var calls = 0
        val shared = OkHttpClient.Builder().addInterceptor { chain ->
            calls += 1
            order.record("attempt-$calls")
            if (calls == 1) throw java.net.SocketException("never answered")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """{"access_token":"a","refresh_token":"r","expires_at":9000,"provider":"p","user_id":"u"}"""
                        .toResponseBody("application/json".toMediaType()),
                )
                .build()
        }.build()
        val api = OkHttpGatewayNativeAuthApi(
            shared,
            networkGate = GatewayNetworkGate { millis ->
                waits += millis
                order.record("network gate")
            },
        )

        api.exchange("https://gateway.example/hermes", "code", "verifier")

        assertEquals(listOf("attempt-1", "network gate", "attempt-2"), order.snapshot())
        assertEquals(listOf(OkHttpGatewayNativeAuthApi.RETRY_NETWORK_WAIT_MILLIS), waits)
        // Evicting is only safe because the auth legs own their pool; the shared
        // one belongs to a live session's RPC and media.
        assertNotSame(
            "auth legs must never evict the shared connection pool",
            shared.connectionPool,
            api.connectionPool,
        )
    }

    /**
     * The r8 fix, from the side that has to hold: Android 17 blocks a cached
     * app's uid from the network entirely, so the hold must be claimed while
     * the tap still has the app in front — before the browser opens — and it
     * must be given back on every way out.
     */
    @Test
    fun `the foreground hold is taken before the browser opens and released on success`() = runBlocking {
        val trace = SignInTrace()
        val foreground = RecordingForeground(trace)
        val api = FakeAuthApi()
        val login = LoopbackGatewayNativeLogin(
            api,
            log = trace.asLog(),
            loginTimeoutMillis = FIXTURE_LOGIN_TIMEOUT_MILLIS,
            foreground = foreground,
        )
        val callbackThread = AtomicReference<Thread>()

        val tokens = login.login(PROFILE, GatewayBrowserLauncher { url ->
            trace.record(BROWSER_OPENED)
            val parsed = requireNotNull(url.toHttpUrlOrNull())
            val redirect = requireNotNull(parsed.queryParameter("redirect_uri"))
            val state = requireNotNull(parsed.queryParameter("state"))
            callbackThread.set(
                Thread { readCallback("$redirect?code=code-foreground&state=$state") }.also(Thread::start),
            )
        })
        callbackThread.get()?.join(15_000)

        assertEquals(VALID_TOKENS, tokens)
        val steps = trace.snapshot()
        assertTrue(
            "the hold is worthless once the app is already behind the browser",
            steps.indexOf(HELD) < steps.indexOf(BROWSER_OPENED),
        )
        assertTrue(steps.contains(GatewaySignInStep.ForegroundHeld.toString()))
        assertEquals(1, foreground.held)
        assertEquals(1, foreground.released)
    }

    @Test
    fun `a refused sign-in releases the foreground hold`() = runBlocking {
        val foreground = RecordingForeground(SignInTrace())
        val login = LoopbackGatewayNativeLogin(
            FakeAuthApi(),
            loginTimeoutMillis = FIXTURE_LOGIN_TIMEOUT_MILLIS,
            foreground = foreground,
        )
        val callbackThread = AtomicReference<Thread>()

        val failure = runCatching {
            login.login(PROFILE, GatewayBrowserLauncher { url ->
                val parsed = requireNotNull(url.toHttpUrlOrNull())
                val redirect = requireNotNull(parsed.queryParameter("redirect_uri"))
                val state = requireNotNull(parsed.queryParameter("state"))
                callbackThread.set(
                    Thread { readCallback("$redirect?error=access_denied&state=$state") }.also(Thread::start),
                )
            })
        }.exceptionOrNull()
        callbackThread.get()?.join(15_000)

        assertEquals(GatewaySignInCopy.REFUSED, (failure as? GatewayAuthException)?.message)
        assertEquals(1, foreground.released)
    }

    @Test
    fun `a sign-in that times out releases the foreground hold`() = runBlocking {
        val foreground = RecordingForeground(SignInTrace())
        val login = LoopbackGatewayNativeLogin(
            FakeAuthApi(),
            loginTimeoutMillis = 200L,
            foreground = foreground,
        )

        val failure = runCatching {
            login.login(PROFILE, GatewayBrowserLauncher { })
        }.exceptionOrNull()

        assertTrue(failure is GatewayAuthException)
        assertEquals(1, foreground.released)
    }

    @Test
    fun `an abandoned sign-in releases the foreground hold`() = runBlocking {
        val foreground = RecordingForeground(SignInTrace())
        val opened = CompletableDeferred<Unit>()
        val login = LoopbackGatewayNativeLogin(
            FakeAuthApi(),
            loginTimeoutMillis = FIXTURE_LOGIN_TIMEOUT_MILLIS,
            foreground = foreground,
        )

        val running = launch(Dispatchers.IO) {
            runCatching { login.login(PROFILE, GatewayBrowserLauncher { opened.complete(Unit) }) }
        }
        opened.await()
        running.cancelAndJoin()

        assertEquals(1, foreground.held)
        assertEquals("a cancelled sign-in must not strand a foreground service", 1, foreground.released)
    }

    /** Records the hold without a platform to hold onto. */
    private class RecordingForeground(private val trace: SignInTrace) : GatewaySignInForeground {
        var held = 0
            private set
        var released = 0
            private set

        override fun hold(): AutoCloseable {
            held += 1
            trace.record(HELD)
            return AutoCloseable {
                released += 1
                trace.record("foreground released")
            }
        }
    }

    /** Sends one hand-written request line, for the shapes no HTTP client will send. */
    private fun rawRequest(port: Int, requestLine: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = FIXTURE_SOCKET_TIMEOUT_MILLIS
            socket.getOutputStream().apply {
                write("$requestLine\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            socket.getInputStream().bufferedReader().readText()
        }

    /**
     * Reads a callback page including the body of a refusal, which
     * `URL.readText()` throws away.
     */
    private fun readCallback(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            // A fixture that cannot be served must fail in seconds with a real
            // message rather than block a browser-thread join forever.
            connectTimeout = FIXTURE_SOCKET_TIMEOUT_MILLIS
            readTimeout = FIXTURE_SOCKET_TIMEOUT_MILLIS
        }
        return try {
            val stream = runCatching { connection.inputStream }.getOrNull() ?: connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * One ordered trace of the whole hand-off — breadcrumbs, browser and wire —
     * which is how "the page was written before the check" is caught at all.
     * Synchronized because the callback arrives on a browser thread.
     */
    private class SignInTrace {
        private val events = mutableListOf<String>()

        @Synchronized
        fun record(event: String) {
            events += event
        }

        /** The typed breadcrumb seam, rendered into the same ordered trace. */
        fun record(step: GatewaySignInStep) = record(step.toString())

        /** The seam a test injects, so a failed step and its type both land. */
        fun asLog(): GatewaySignInLog = object : GatewaySignInLog {
            override fun step(step: GatewaySignInStep) = record(step)

            override fun failed(step: GatewaySignInStep, cause: Throwable) =
                // The shipped breadcrumb logs the qualified name
                // (`AndroidGatewaySignInLog.failed`); assert what ships.
                record("$step (${cause.javaClass.name})")
        }

        @Synchronized
        fun snapshot(): List<String> = events.toList()
    }

    /** A browser that records its own half of the trace and drives the callback. */
    private class TracingBrowser(
        private val trace: SignInTrace,
        private val callback: (redirect: String, state: String) -> Unit,
    ) : GatewayBrowserLauncher {
        private val thread = AtomicReference<Thread>()
        var bindingClosed = false
            private set

        override suspend fun bindForSignIn(): AutoCloseable = AutoCloseable { bindingClosed = true }

        override suspend fun open(url: String) {
            trace.record(OPENED)
            val parsed = requireNotNull(url.toHttpUrlOrNull())
            val redirect = requireNotNull(parsed.queryParameter("redirect_uri"))
            val state = requireNotNull(parsed.queryParameter("state"))
            thread.set(Thread { callback(redirect, state) }.also(Thread::start))
        }

        var returnedToApp = false
            private set

        override suspend fun returnToApp() {
            returnedToApp = true
            trace.record(RETURNED)
        }

        fun await() {
            thread.get()?.join(5_000)
        }

        companion object {
            const val OPENED = "browser: sign-in page opened"
            const val RETURNED = "browser: app brought forward"
        }
    }

    private class TracingAuthApi(private val trace: SignInTrace) : GatewayNativeAuthApi {
        override suspend fun status(baseUrl: String) =
            GatewayAuthStatus(authRequired = true, authFlows = setOf("native_pkce"))

        override suspend fun exchange(baseUrl: String, code: String, verifier: String): GatewayNativeTokens {
            trace.record(EXCHANGED)
            return VALID_TOKENS
        }

        override suspend fun refresh(baseUrl: String, refreshToken: String, provider: String): GatewayNativeTokens? =
            error("an interactive sign-in must not rotate")

        override suspend fun mintWebSocketTicket(baseUrl: String, accessToken: String): String = "ticket-trace"

        companion object {
            const val EXCHANGED = "wire: authorization code exchanged"
        }
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
        // Restoring never tears anything down. Whoever changed the saved route
        // owns that: the Gateways form and the connection switcher both leave
        // the old endpoint before the route moves, and a re-arm that also
        // disconnected could kill the connection it had just opened.
        assertEquals(0, connection.disconnects)
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
        assertEquals("teardown belongs to whoever changed the route", 0, connection.disconnects)
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
    fun `network recovery starts a fresh failure episode`() = runTest {
        val first = FakeRpc()
        val retryPermits = Channel<Unit>(Channel.UNLIMITED)
        var attempts = 0
        var virtualNow = 0L
        val manager = remoteManager(
            reconnectWait = { retryPermits.receive() },
            reconnectJitter = { 0.0 },
            nowMillis = { virtualNow },
        ) { _, _ ->
            attempts += 1
            if (attempts == 1) first else FailingAtReadinessRpc()
        }
        manager.connectRemote(PROFILE, GatewayBrowserLauncher {})
        runCurrent()
        first.failFromServer()
        runCurrent()

        manager.networkAvailabilityChanged(false)
        runCurrent()
        virtualNow = 60_000L
        manager.networkAvailabilityChanged(true)
        runCurrent()
        retryPermits.send(Unit)
        runCurrent()

        assertEquals(2, attempts)
        // A fresh episode is Connecting with no escalated copy carried over.
        assertEquals(GatewayConnectionState(GatewayConnectionStatus.Connecting), manager.state.value)
        manager.disconnect()
    }

    @Test
    fun `repeated remote failures keep retrying instead of stranding the user`() = runTest {
        val first = FakeRpc()
        val retryPermits = Channel<Unit>(Channel.UNLIMITED)
        val observedDelays = mutableListOf<Long>()
        var attempts = 0
        val manager = remoteManager(
            reconnectWait = { millis ->
                observedDelays += millis
                retryPermits.receive()
            },
            reconnectJitter = { 0.5 },
        ) { _, _ ->
            attempts += 1
            if (attempts == 1) first else FailingAtReadinessRpc()
        }
        manager.connectRemote(PROFILE, GatewayBrowserLauncher {})
        runCurrent()
        val firstAttempt = attempts

        first.failFromServer()
        runCurrent()
        repeat(8) {
            retryPermits.send(Unit)
            runCurrent()
        }

        assertEquals(
            "retry loop must keep attempting past the former three-step ladder",
            firstAttempt + 8,
            attempts,
        )
        assertEquals(
            listOf(150L, 300L, 600L, 1_200L, 2_400L, 4_800L, 7_500L, 7_500L),
            observedDelays.take(8),
        )
        manager.disconnect()
    }

    @Test
    fun `a failed cold-start restore arms the retry loop`() = runTest {
        // The reviewer's blocker: before this PR the initial-open failure
        // path returned Failed without scheduling anything, so a saved route
        // on an unreachable host stranded the app on a red state until the
        // user tapped something.
        val retryPermits = Channel<Unit>(Channel.UNLIMITED)
        val recovered = FakeRpc()
        var attempts = 0
        val manager = remoteManager(
            reconnectWait = { retryPermits.receive() },
            reconnectJitter = { 0.0 },
        ) { _, _ ->
            attempts += 1
            if (attempts == 1) FailingAtReadinessRpc() else recovered
        }
        // Production restores during Application.onCreate, before the first
        // Activity advances ProcessLifecycleOwner to STARTED.
        manager.applicationForegroundChanged(false)

        val result = manager.restoreRemote(PROFILE)
        runCurrent()
        assertTrue(result is GatewayConnectResult.Failed)
        assertEquals(1, attempts)
        assertEquals(GatewayConnectionStatus.Disconnected, manager.state.value.status)

        manager.applicationForegroundChanged(true)
        runCurrent()
        assertEquals(GatewayConnectionStatus.Connecting, manager.state.value.status)
        retryPermits.send(Unit)
        runCurrent()

        assertEquals(2, attempts)
        assertTrue(manager.client.value === recovered)
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
        manager.disconnect()
    }

    @Test
    fun `foreground resume re-arms while a fenced retry intent unwinds`() = runTest {
        val first = FakeRpc()
        val recovered = FakeRpc()
        val retryPermits = Channel<Unit>(Channel.UNLIMITED)
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var attempts = 0
        val manager = remoteManager(
            reconnectWait = { retryPermits.receive() },
            reconnectJitter = { 0.0 },
            beforeReconnectCancellationCleanup = {
                cleanupStarted.complete(Unit)
                releaseCleanup.await()
            },
        ) { _, _ ->
            attempts += 1
            if (attempts == 1) first else recovered
        }
        manager.connectRemote(PROFILE, GatewayBrowserLauncher {})
        runCurrent()
        first.failFromServer()
        runCurrent()

        manager.applicationForegroundChanged(false)
        retryPermits.send(Unit)
        runCurrent()
        cleanupStarted.await()

        // The foreground nudge observes the automatic intent that is still in
        // cancellation cleanup and yields. Cleanup must re-arm after releasing
        // that intent instead of leaving the route stranded on Disconnected.
        manager.applicationForegroundChanged(true)
        runCurrent()
        releaseCleanup.complete(Unit)
        runCurrent()
        retryPermits.send(Unit)
        runCurrent()

        assertEquals(2, attempts)
        assertTrue(manager.client.value === recovered)
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
        manager.disconnect()
    }

    @Test
    fun `sustained failure escalates the message but keeps retrying`() = runTest {
        val first = FakeRpc()
        val recovered = BlockingReadinessRpc()
        val retryPermits = Channel<Unit>(Channel.UNLIMITED)
        var attempts = 0
        var retriesFail = true
        var virtualNow = 0L
        val manager = remoteManager(
            reconnectWait = { retryPermits.receive() },
            reconnectJitter = { 0.0 },
            nowMillis = { virtualNow },
        ) { _, _ ->
            attempts += 1
            when {
                attempts == 1 -> first
                retriesFail -> FailingAtReadinessRpc()
                else -> recovered
            }
        }
        manager.connectRemote(PROFILE, GatewayBrowserLauncher {})
        runCurrent()
        first.failFromServer()
        runCurrent()

        virtualNow = 46_000L
        retryPermits.send(Unit)
        runCurrent()

        assertEquals(
            GatewayConnectionStatus.NeedsAttention,
            manager.state.value.status,
        )
        assertTrue(
            "expected escalated copy, got: ${manager.state.value.message}",
            manager.state.value.message?.startsWith("Still trying to reach the Gateway.") == true,
        )
        val attemptsAtEscalation = attempts

        retriesFail = false
        retryPermits.send(Unit)
        recovered.requestStarted.await()

        assertEquals(
            "escalated UI must stay latched while a retry is in flight",
            GatewayConnectionState(
                GatewayConnectionStatus.NeedsAttention,
                "Still trying to reach the Gateway. Check your connection or the host.",
            ),
            manager.state.value,
        )
        recovered.releaseRequest.complete(Unit)
        runCurrent()

        assertEquals(attemptsAtEscalation + 1, attempts)
        assertTrue(manager.client.value === recovered)
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)

        // Success starts a fresh failure episode even when the recovered
        // connection did not stay up for the 30-second stability threshold.
        retriesFail = true
        virtualNow = 47_000L
        recovered.failFromServer()
        runCurrent()
        retryPermits.send(Unit)
        runCurrent()
        assertEquals(GatewayConnectionState(GatewayConnectionStatus.Connecting), manager.state.value)
        manager.disconnect()
    }

    @Test
    fun `background pauses automatic retries until foreground resume`() = runTest {
        val first = FakeRpc()
        val recovered = FakeRpc()
        val retryPermits = Channel<Unit>(Channel.UNLIMITED)
        var attempts = 0
        val manager = remoteManager(
            reconnectWait = { retryPermits.receive() },
            reconnectJitter = { 0.0 },
        ) { _, _ ->
            attempts += 1
            if (attempts == 1) first else recovered
        }
        manager.connectRemote(PROFILE, GatewayBrowserLauncher {})
        runCurrent()
        first.failFromServer()
        runCurrent()

        manager.applicationForegroundChanged(false)
        retryPermits.send(Unit)
        runCurrent()

        assertEquals("background transition must fence the delayed dial", 1, attempts)

        manager.applicationForegroundChanged(true)
        runCurrent()
        retryPermits.send(Unit)
        runCurrent()

        assertEquals(2, attempts)
        assertTrue(manager.client.value === recovered)
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
        manager.disconnect()
    }

    @Test
    fun `background gate never blocks an explicit remote open`() = runTest {
        val rpc = FakeRpc()
        var attempts = 0
        val manager = remoteManager(reconnectWait = {}) { _, _ ->
            attempts += 1
            rpc
        }
        manager.applicationForegroundChanged(false)

        val result = manager.connectRemote(PROFILE, GatewayBrowserLauncher {})

        assertEquals(GatewayConnectResult.Connected, result)
        assertEquals(1, attempts)
        assertTrue(manager.client.value === rpc)
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
    fun `foreground nudge does not replay a terminal remote failure`() = runTest {
        var attempts = 0
        val manager = remoteManager(reconnectWait = {}, reconnectJitter = { 0.0 }) { _, _ ->
            attempts += 1
            FailingAtReadinessRpc(
                GatewayAuthException("Sign in to this Gateway before reconnecting.", statusCode = 401),
            )
        }

        val result = manager.connectRemote(PROFILE, GatewayBrowserLauncher {})
        assertTrue(result is GatewayConnectResult.Failed && !result.retryable)
        assertEquals(1, attempts)

        manager.nudgeRemoteReconnect()
        runCurrent()

        assertEquals(1, attempts)
        assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
        manager.disconnect()
    }

    @Test
    fun `foreground nudge cannot replace an explicit remote open`() = runTest {
        val api = FakeAuthApi()
        val first = BlockingReadinessRpc()
        val replacementRpc = BlockingReadinessRpc()
        val unusedFallback = FakeRpc()
        val pending = ArrayDeque<GatewayRpcClient>(listOf(first, replacementRpc, unusedFallback))
        val manager = remoteManager(
            api = api,
            reconnectWait = {},
            reconnectJitter = { 0.0 },
        ) { _, _ -> pending.removeFirst() }
        val firstConnect = backgroundScope.async {
            manager.connectRemote(PROFILE, GatewayBrowserLauncher {})
        }
        first.requestStarted.await()

        val replacement = PROFILE.copy(baseUrl = "https://replacement.example/hermes/")
        val replacementConnect = backgroundScope.async {
            manager.connectRemote(replacement, GatewayBrowserLauncher {})
        }
        // Queue the replacement's admission and then the foreground nudge
        // behind the first open's mutex. When the first open releases it, the
        // nudge wins the narrow gap between the replacement's two lock passes.
        manager.nudgeRemoteReconnect()
        runCurrent()
        first.releaseRequest.complete(Unit)
        runCurrent()

        assertTrue(firstConnect.isCancelled)
        assertFalse(replacementConnect.isCancelled)
        assertTrue("explicit replacement must reach readiness", replacementRpc.requestStarted.isCompleted)
        replacementRpc.releaseRequest.complete(Unit)
        runCurrent()

        assertEquals(GatewayConnectResult.Connected, replacementConnect.await())
        assertTrue(manager.client.value === replacementRpc)
        assertTrue(unusedFallback.calls.isEmpty())
        assertEquals(2, api.ticketTokens.size)
        manager.disconnect()
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

    /** Cached-token authenticator: a restore in these tests must never open a login. */
    @Test
    fun `a Local refusal never publishes over a live connection on another route`() = runTest {
        val rpc = FakeRpc()
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { error("the Remote route owns no process") },
            remoteConnector = RemoteGatewayConnector(cachedAuthenticator(FakeAuthApi())) { _, _ -> rpc },
            localConnector = LocalGatewayConnector(
                tokens = object : GatewaySessionTokenStore {
                    override suspend fun loadSessionToken(slot: GatewaySecretSlot) = SessionTokenRead.Absent

                    override suspend fun saveSessionToken(slot: GatewaySecretSlot, token: ByteArray) =
                        error("a refusal stores nothing")

                    override suspend fun clearSessionToken(slot: GatewaySecretSlot) =
                        error("a refusal erases nothing")
                },
                health = { _, _ -> error("a refusal must not dial") },
                rpcOpen = { _, _ -> error("a refusal must not dial") },
            ),
        )

        assertTrue(manager.connectRemote(PROFILE, GatewayBrowserLauncher {}) is GatewayConnectResult.Connected)

        // A saved Local row with no token has a sentence to publish, and the
        // loopback flags say nothing at all about the Remote leg that is up:
        // fenced on those alone, this replaces a live connection's state with
        // advice about a row this app is not even on.
        val refused = manager.restoreLocal(
            LocalGatewayProfile("http://127.0.0.1:9119", secretSlotId = "row-local"),
        )

        assertNull("the refusal stood down", refused)
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
        assertNull("and said nothing over the live route", manager.state.value.message)

        manager.disconnect()
    }

    private fun cachedAuthenticator(api: FakeAuthApi) = NativeGatewayAuthenticator(
        api = api,
        store = MemoryTokenStore(VALID_TOKENS),
        login = GatewayNativeLogin { _, _ -> error("cached restore must not open login") },
        nowSeconds = { 1_000L },
    )

    /**
     * Shared-mode manager over a cached-token remote connector. Defaults mirror
     * the production ones, so a test that omits a seam is not silently made
     * deterministic; everything a test asserts on stays a parameter.
     */
    private fun kotlinx.coroutines.test.TestScope.remoteManager(
        api: FakeAuthApi = FakeAuthApi(),
        reconnectWait: suspend (Long) -> Unit,
        reconnectJitter: () -> Double = { kotlin.random.Random.nextDouble() },
        nowMillis: () -> Long = System::currentTimeMillis,
        beforeReconnectCancellationCleanup: suspend () -> Unit = {},
        openRpc: suspend (String, String) -> GatewayRpcClient,
    ) = GatewayConnectionManager(
        scope = backgroundScope,
        installStore = GatewayInstallStore { error("shared mode has no process ownership") },
        remoteConnector = RemoteGatewayConnector(cachedAuthenticator(api), openRpc),
        reconnectWait = reconnectWait,
        reconnectJitter = reconnectJitter,
        nowMillis = nowMillis,
        beforeReconnectCancellationCleanup = beforeReconnectCancellationCleanup,
    )

    @Test
    fun `two rotations of the same credential spend the one-time refresh token once`() = runTest {
        // `/auth/native/refresh` returns a new access/refresh pair and 401s a
        // refresh token every provider rejects, reuse-detected included
        // (hermes-agent @ f82f2dbabd9e66b714f2b4f8a40447fe0c13e732,
        // `hermes_cli/dashboard_auth/routes.py:1027-1079`). Two callers that
        // POST the same one-time token race each other into a rejection, and
        // race each other's `store.save`. Load, refresh and save are therefore
        // one critical section.
        val gate = CompletableDeferred<Unit>()
        val api = GatedRefreshApi(gate)
        val store = MemoryTokenStore(VALID_TOKENS)
        val authenticator = NativeGatewayAuthenticator(api, store, { _, _ -> error("no sign-in here") })

        val first = async { authenticator.refreshAccessToken(PROFILE) }
        runCurrent()
        val second = async { authenticator.refreshAccessToken(PROFILE) }
        runCurrent()
        assertEquals("a second caller must wait, not re-POST", 1, api.refreshCalls)

        gate.complete(Unit)
        assertTrue("the rotating caller", first.await())
        assertTrue("the caller that waited", second.await())

        // Exactly one network rotation, spending exactly the stale token once.
        assertEquals(1, api.refreshCalls)
        assertEquals(listOf(VALID_TOKENS.refreshToken), api.presented)
        // And both callers end up looking at the same rotated credential.
        assertEquals(ROTATED_TOKENS, store.tokens)
        assertEquals(ROTATED_TOKENS, authenticator.tokens(PROFILE))
    }

    @Test
    fun `a relay rotation and a reconnect ticket share one rotation rather than racing it`() = runTest {
        // The two entry points C2 names: `ticket()` on the reconnect path and
        // `refreshAccessToken()` from a refused REST leg. They must not both
        // present the same retired token.
        val gate = CompletableDeferred<Unit>()
        val api = GatedRefreshApi(gate)
        val store = MemoryTokenStore(EXPIRED_TOKENS)
        val authenticator = NativeGatewayAuthenticator(api, store, { _, _ -> error("no sign-in here") })

        val reconnect = async { authenticator.ticket(PROFILE, browser = null) }
        runCurrent()
        val relayRotation = async { authenticator.refreshAccessToken(PROFILE) }
        runCurrent()

        gate.complete(Unit)
        assertEquals("ticket-1", reconnect.await())
        assertTrue(relayRotation.await())

        assertEquals(1, api.refreshCalls)
        assertEquals(listOf(EXPIRED_TOKENS.refreshToken), api.presented)
        // The ticket is minted with the rotated bearer, never the retired one.
        assertEquals(listOf(ROTATED_TOKENS.accessToken), api.ticketTokens)
        assertEquals(ROTATED_TOKENS, store.tokens)
    }

    @Test
    fun `a sign-out that lands mid-rotation is not undone by the refresh it raced`() = runTest {
        // signOut deliberately does not take the rotation lock — a person
        // tapping sign out must not wait on a network refresh — so the rotation
        // has to re-check the store before it writes. Otherwise a rotation
        // already on the wire writes a live credential back over the clear.
        val gate = CompletableDeferred<Unit>()
        val api = GatedRefreshApi(gate)
        val store = MemoryTokenStore(VALID_TOKENS)
        val authenticator = NativeGatewayAuthenticator(api, store, { _, _ -> error("no sign-in here") })

        val rotating = async { authenticator.refreshAccessToken(PROFILE) }
        runCurrent()
        assertEquals("the refresh must already be on the wire", 1, api.refreshCalls)

        authenticator.signOut(PROFILE)
        gate.complete(Unit)

        assertFalse("a rotation cannot succeed into a store that was cleared", rotating.await())
        assertEquals(null, store.tokens)
    }

    @Test
    fun `a sign-in that lands mid-rotation is not overwritten by the refresh it raced`() = runTest {
        // Sign-out and interactive sign-in both write to the store outside the
        // rotation lock, so a rotation can come back off the wire to a store
        // that is non-null and holds someone else's credential. A store that
        // merely has *something* in it is not the store this rotation started
        // from, and writing into it hands the person who just signed in a
        // credential minted from the identity they signed out of.
        val gate = CompletableDeferred<Unit>()
        val api = GatedRefreshApi(gate)
        val store = MemoryTokenStore(VALID_TOKENS)
        val authenticator = NativeGatewayAuthenticator(api, store, { _, _ -> OTHER_ACCOUNT_TOKENS })

        val rotating = async { authenticator.refreshAccessToken(PROFILE) }
        runCurrent()
        assertEquals("the refresh must already be on the wire", 1, api.refreshCalls)

        // The production sequence, through the real entry points: signing out
        // clears, and the sign-in that follows takes `ticket()`'s empty-store
        // branch straight to `store.save` — neither one waits on the rotation.
        authenticator.signOut(PROFILE)
        assertEquals("ticket-1", authenticator.ticket(PROFILE, GatewayBrowserLauncher {}))
        assertEquals(OTHER_ACCOUNT_TOKENS, store.tokens)

        gate.complete(Unit)

        assertFalse(
            "a rotation cannot succeed into a credential it did not start from",
            rotating.await(),
        )
        assertEquals("the credential the person signed in as", OTHER_ACCOUNT_TOKENS, store.tokens)
        assertEquals("and no second rotation was spent chasing it", 1, api.refreshCalls)
    }

    @Test
    fun `a rotation never resurrects a credential that was signed out before it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = GatedRefreshApi(gate)
        val store = MemoryTokenStore(VALID_TOKENS)
        val authenticator = NativeGatewayAuthenticator(api, store, { _, _ -> error("no sign-in here") })

        val observed = requireNotNull(authenticator.tokens(PROFILE))
        authenticator.signOut(PROFILE)

        assertFalse(authenticator.refreshAccessToken(PROFILE))
        assertEquals(0, api.refreshCalls)
        assertEquals(null, store.tokens)
        // The caller's stale view of the world is not an argument for writing
        // it back.
        assertEquals(VALID_TOKENS, observed)
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

    /**
     * Counts refresh POSTs and parks the first one, so a second caller has to
     * decide what to do while a rotation is genuinely in flight.
     */
    private class GatedRefreshApi(private val gate: CompletableDeferred<Unit>) : GatewayNativeAuthApi {
        val presented = mutableListOf<String>()
        val ticketTokens = mutableListOf<String>()
        val refreshCalls: Int get() = presented.size

        override suspend fun status(baseUrl: String) =
            GatewayAuthStatus(authRequired = true, authFlows = setOf("native_pkce"))

        override suspend fun exchange(baseUrl: String, code: String, verifier: String): GatewayNativeTokens =
            error("a rotation must never fall through to an interactive sign-in")

        override suspend fun refresh(
            baseUrl: String,
            refreshToken: String,
            provider: String,
        ): GatewayNativeTokens {
            presented += refreshToken
            gate.await()
            return ROTATED_TOKENS
        }

        override suspend fun mintWebSocketTicket(baseUrl: String, accessToken: String): String {
            ticketTokens += accessToken
            return "ticket-${ticketTokens.size}"
        }
    }

    private class MemoryTokenStore(var tokens: GatewayNativeTokens?) : GatewayTokenStore {
        override suspend fun load(slot: GatewaySecretSlot): GatewayNativeTokens? = tokens
        override suspend fun save(slot: GatewaySecretSlot, tokens: GatewayNativeTokens) {
            this.tokens = tokens
        }
        override suspend fun clear(slot: GatewaySecretSlot) {
            tokens = null
        }
    }

    @Test
    fun `a Gateway counts as configured only on the route that could connect`() = runTest {
        val hosts = MemoryHostProfileStore()
        val remote = MemoryRemoteProfileStore(
            GatewayConnectionMode.Remote,
            RemoteGatewayProfile(),
        )

        // A fresh install: neither route has anything to connect to.
        assertFalse(gatewayConfigured(remote, hosts).first())

        // Only the selected route can produce a connection, so only its own
        // profile decides — the same pair `restoreSavedRemoteGateway` gates on.
        hosts.hostProfile.value = HostProfile(host = "hermes.example", username = "ada")
        assertFalse(gatewayConfigured(remote, hosts).first())

        remote.profile.value = RemoteGatewayProfile(baseUrl = "https://hermes.example")
        assertTrue(gatewayConfigured(remote, hosts).first())

        // A URL that never parses is not a Gateway to connect to.
        remote.profile.value = RemoteGatewayProfile(baseUrl = "not a url")
        assertFalse(gatewayConfigured(remote, hosts).first())

        // And the managed SSH route reads its own saved host.
        remote.mode.value = GatewayConnectionMode.Ssh
        assertTrue(gatewayConfigured(remote, hosts).first())
    }

    private class MemoryHostProfileStore : HostProfileStore {
        override val hostProfile = MutableStateFlow(HostProfile())
        override suspend fun saveHostProfile(profile: HostProfile) {
            hostProfile.value = profile
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
        override suspend fun saveGatewayConnectionMode(
            mode: GatewayConnectionMode,
            expectedConnectionId: String?,
        ): Boolean {
            gatewayConnectionMode.value = mode
            return true
        }
    }

    private class RecordingConnectionController : GatewayConnectionController {
        override val state = MutableStateFlow(GatewayConnectionState())
        val restored = mutableListOf<RemoteGatewayProfile>()
        var disconnects = 0

        override suspend fun connect(profile: HostProfile, credential: SshCredential): GatewayConnectResult =
            error("SSH must not be used during remote restore")

        override fun startRemoteSignIn(profile: RemoteGatewayProfile, browser: GatewayBrowserLauncher) =
            error("interactive sign-in must not run during restore")

        override fun cancelRemoteSignIn() = Unit

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

        override fun startRemoteSignIn(profile: RemoteGatewayProfile, browser: GatewayBrowserLauncher) =
            error("interactive sign-in must not run during restore")

        override fun cancelRemoteSignIn() = Unit

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
        override val closed = MutableSharedFlow<GatewayCloseCause>(extraBufferCapacity = 1)
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
            closed.tryEmit(GatewayCloseCause.TransportFailure)
        }
    }

    private class BlockingReadinessRpc : GatewayRpcClient {
        override val events = MutableSharedFlow<GatewayEvent>()
        override val closed = MutableSharedFlow<GatewayCloseCause>(extraBufferCapacity = 1)
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

        fun failFromServer() {
            closed.tryEmit(GatewayCloseCause.TransportFailure)
        }
    }

    /** Readiness round trip always fails: the retry loop's honest subject. */
    private class FailingAtReadinessRpc(
        private val failure: Throwable = GatewayConnectionException("The Gateway could not be reached."),
    ) : GatewayRpcClient {
        override val events = MutableSharedFlow<GatewayEvent>()
        override val closed = MutableSharedFlow<GatewayCloseCause>(extraBufferCapacity = 1)
        var closedByClient = false

        override suspend fun request(method: String, params: JsonObject): JsonElement = throw failure

        override fun close() {
            closedByClient = true
        }
    }

    private companion object {
        /** Production waits five minutes for a person; a fixture must not. */
        const val FIXTURE_LOGIN_TIMEOUT_MILLIS = 15_000L
        const val HELD = "foreground held"
        const val BROWSER_OPENED = "browser opened"
        const val FIXTURE_SOCKET_TIMEOUT_MILLIS = 5_000

        val PROFILE = RemoteGatewayProfile("https://gateway.example/hermes/", provider = "fixture-provider")
        val VALID_TOKENS = GatewayNativeTokens(
            accessToken = "access-fixture",
            refreshToken = "refresh-fixture",
            expiresAt = 10_000L,
            provider = "fixture-provider",
            userId = "fixture-user",
        )

        /** Already lapsed, so `ticket()` takes its rotation branch. */
        val EXPIRED_TOKENS = VALID_TOKENS.copy(expiresAt = 0L)

        /**
         * A different sign-in landing on the same Gateway: a credential no
         * rotation of [VALID_TOKENS] is entitled to overwrite.
         */
        val OTHER_ACCOUNT_TOKENS = GatewayNativeTokens(
            accessToken = "access-other-account",
            refreshToken = "refresh-other-account",
            expiresAt = 30_000L,
            provider = "fixture-provider",
            userId = "other-fixture-user",
        )

        /** What one successful rotation hands back — including a *new* refresh token. */
        val ROTATED_TOKENS = GatewayNativeTokens(
            accessToken = "access-rotated",
            refreshToken = "refresh-rotated",
            expiresAt = 20_000L,
            provider = "fixture-provider",
            userId = "fixture-user",
        )
    }
}
