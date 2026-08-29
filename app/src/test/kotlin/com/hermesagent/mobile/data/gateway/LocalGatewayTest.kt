package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.connections.localGatewayKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Local route: a Hermes the person runs on this same phone.
 *
 * Two things are being proved here. The address rule, because loopback is the
 * only place this app permits cleartext and the normalizer is what decides
 * whether an address *is* loopback — a rule that guessed would quietly widen
 * that permission. And the connect path, because on loopback the session token
 * is the entire boundary: what happens when it is missing, when it is refused,
 * and what a disconnect is allowed to touch.
 *
 * No token here is a real credential, and every address is loopback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalGatewayTest {

    @Test
    fun `the address rule accepts loopback and defaults the port`() {
        val accepted = mapOf(
            "http://127.0.0.1:9119" to "http://127.0.0.1:9119",
            // The port is optional; the default is the one `hermes serve` uses.
            "http://127.0.0.1" to "http://127.0.0.1:9119",
            "  http://localhost:9119/  " to "http://localhost:9119",
            "http://LOCALHOST:9119" to "http://localhost:9119",
            "http://[::1]:9119" to "http://[::1]:9119",
            "http://[::1]" to "http://[::1]:9119",
            // Two more spellings of loopback that the URL parser folds into the
            // canonical ones. They are accepted because they *are* this device,
            // and folding them is what keeps one server from being three rows.
            "http://[0:0:0:0:0:0:0:1]:9119" to "http://[::1]:9119",
            "http://[::ffff:127.0.0.1]:9119" to "http://127.0.0.1:9119",
            // Named explicitly, so it stays named rather than becoming the
            // scheme default and re-normalizing to 9119 on the next pass.
            "http://127.0.0.1:80" to "http://127.0.0.1:80",
            "http://127.0.0.1:9119/hermes/" to "http://127.0.0.1:9119/hermes",
            // The scheme is case-insensitive; only its abbreviated forms are refused.
            "HTTP://127.0.0.1:9200" to "http://127.0.0.1:9200",
        )

        accepted.forEach { (raw, expected) ->
            assertEquals(raw, expected, normalizeLocalGatewayUrl(raw))
            assertEquals(
                "normalizing a normalized address must be a no-op",
                expected,
                normalizeLocalGatewayUrl(expected),
            )
        }
    }

    @Test
    fun `the address rule refuses everything that is not this device`() {
        val refused = listOf(
            // A LAN address is not loopback, and cleartext to it leaves the phone.
            "http://10.0.0.1:9119",
            "http://192.168.1.10:9119",
            "http://127.0.0.1.example.invalid:9119",
            // A loopback Hermes serves plain HTTP; `https` describes another
            // server, and silently downgrading would connect to neither.
            "https://127.0.0.1:9119",
            "ws://127.0.0.1:9119",
            "file:///dev/null",
            // Userinfo, a query and a fragment are all somebody describing
            // something this route does not have. The third one is the reason
            // userinfo is refused rather than ignored: read quickly it looks
            // like loopback, and the host it actually names is not.
            "http://user:secret@127.0.0.1:9119",
            "http://user@127.0.0.1:9119",
            "http://127.0.0.1@evil.invalid:9119",
            "http://127.0.0.1:9119?token=fixture",
            "http://127.0.0.1:9119?",
            "http://127.0.0.1:9119#fixture",
            // Addresses that mean loopback to some resolvers and nothing to
            // this one. Refusing is the whole rule: an address this app cannot
            // prove is loopback is an address it will not permit cleartext to.
            "http://0x7f.0.0.1:9119",
            "http://127.1:9119",
            "http://2130706433:9119",
            "http://127.0.0.1.:9119",
            // No scheme at all: refuse rather than assume one.
            "127.0.0.1:9119",
            // Abbreviated schemes the URL parser accepts and this rule must
            // not. Each of these names port 9200; read through an authority
            // that is not there, the port silently became 9119, the row was
            // saved against 9119, and the session token typed for the Hermes on
            // 9200 would have been stored for — and presented to — whatever
            // process was listening on 9119. Any app on this phone can bind
            // that port without a permission.
            "http:127.0.0.1:9200",
            "http:/127.0.0.1:9200",
            "http:localhost:9200",
            "http:[::1]:9200",
            // The parser ends the authority at a backslash and this rule's port
            // cut does not, so the first names port 80 and the second names 92 —
            // each one a different server on this device than the person typed,
            // with their session token bound to it.
            "http://127.0.0.1\\evil:9200",
            "http://127.0.0.1\\:9200",
            "http://127.0.0.1:92\\00",
            "http://127.0.0.1:9200\\evil",
            "",
            "   ",
        )

        (refused + ("http:" + "\\".repeat(2) + "127.0.0.1:9200")).forEach { raw ->
            assertNull("$raw must be refused, not guessed at", normalizeLocalGatewayUrl(raw))
        }
    }

    @Test
    fun `one Hermes on one port is one row, however it was spelled`() {
        val canonical = localGatewayKey("http://127.0.0.1:9119")

        assertEquals(canonical, localGatewayKey("http://localhost:9119"))
        assertEquals(canonical, localGatewayKey("http://[::1]:9119"))
        assertEquals("the port is the server", canonical, localGatewayKey("http://127.0.0.1"))
        assertFalse(canonical == localGatewayKey("http://127.0.0.1:9200"))
        assertEquals("an unusable address collides with nothing", "", localGatewayKey("http://10.0.0.1:9119"))
    }

    @Test
    fun `connected means health, socket and an authenticated round trip all passed`() = runTest {
        val leg = LocalLeg()
        val manager = manager(leg)

        val result = manager.connectLocal(PROFILE)

        assertTrue("expected Connected, got $result", result is GatewayConnectResult.Connected)
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
        assertEquals("http://127.0.0.1:9119", leg.healthCheckedUrl)
        assertEquals(TOKEN, leg.healthToken)
        assertEquals("http://127.0.0.1:9119", leg.socketUrl)
        assertEquals("the socket is authenticated with the same token", TOKEN, leg.socketToken)
        assertEquals(listOf("session.list"), leg.rpc.calls)
        assertNotNull("the REST leg comes up with the socket", manager.gatewayHttp.value)
        assertNotNull(manager.imageLoader.value)

        manager.disconnect()
        runCurrent()

        assertEquals(GatewayConnectionStatus.Disconnected, manager.state.value.status)
        assertTrue("the socket is closed", leg.rpc.socketClosed)
        assertNull(manager.gatewayHttp.value)
        assertNull(manager.imageLoader.value)
        assertEquals(
            "a disconnect touches no process: this app owns none here",
            0,
            leg.tokenStore.erasures,
        )
    }

    @Test
    fun `the leg carries the session header, and the token it was handed is zeroed`() = runTest {
        val leg = LocalLeg()

        val opened = leg.connector().open(PROFILE)

        assertEquals(SESSION_TOKEN_HEADER to TOKEN, opened.authorization())
        val handed = requireNotNull(leg.tokenStore.lastHandedOut)
        assertArrayEquals("the bytes the store handed out do not outlive the open", ByteArray(handed.size), handed)
        assertFalse("nor does the leg print the token", opened.toString().contains(TOKEN))
    }

    @Test
    fun `a refused session token is its own failure, and never answered with a second credential`() = runTest {
        val leg = LocalLeg(healthStatus = 401)
        val manager = manager(leg)

        val result = manager.connectLocal(PROFILE)

        assertTrue(result is GatewayConnectResult.Failed)
        assertEquals(LocalGatewayCopy.TOKEN_REFUSED, (result as GatewayConnectResult.Failed).message)
        assertFalse("a refused token is a wrong token, not a retry", result.retryable)
        assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
        assertEquals(LocalGatewayCopy.TOKEN_REFUSED, manager.state.value.message)
        assertEquals("the dashboard scrape is not a fallback after a refusal", 0, leg.scrapes)
        assertNull("and no socket was opened", leg.socketUrl)
        assertNull(manager.gatewayHttp.value)
    }

    @Test
    fun `readiness is the authenticated round trip, not the socket`() = runTest {
        val leg = LocalLeg()
        leg.rpc.failRequests = true
        val manager = manager(leg)

        val result = manager.connectLocal(PROFILE)

        assertTrue(result is GatewayConnectResult.Failed)
        assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
        assertTrue("the half-open socket is closed", leg.rpc.socketClosed)
        assertNull("and nothing was published over it", manager.gatewayHttp.value)
        assertNull(manager.client.value)
    }

    @Test
    fun `a Gateway that is not answering says so, and says what to do`() = runTest {
        val leg = LocalLeg(healthStatus = 503)
        val manager = manager(leg)

        val result = manager.connectLocal(PROFILE)

        assertTrue(result is GatewayConnectResult.Failed)
        assertEquals(LocalGatewayCopy.NOT_ANSWERING, (result as GatewayConnectResult.Failed).message)
        assertNull(leg.socketUrl)
    }

    @Test
    fun `a token is required, and the dashboard scrape is only tried when none is stored`() = runTest {
        val withoutScrape = LocalLeg()
        withoutScrape.tokenStore.stored = null

        val refusal = manager(withoutScrape).connectLocal(PROFILE)

        assertTrue(refusal is GatewayConnectResult.Failed)
        assertEquals(LocalGatewayCopy.TOKEN_MISSING, (refusal as GatewayConnectResult.Failed).message)

        val withScrape = LocalLeg(scraped = TOKEN)
        withScrape.tokenStore.stored = null

        assertTrue(manager(withScrape).connectLocal(PROFILE) is GatewayConnectResult.Connected)
        assertEquals(1, withScrape.scrapes)
        assertEquals(TOKEN, withScrape.socketToken)

        // A stored token is the answer; the scrape is a convenience for the
        // person who has not saved one yet, not a second place to look.
        val stored = LocalLeg(scraped = "scraped-token-fixture")

        assertTrue(manager(stored).connectLocal(PROFILE) is GatewayConnectResult.Connected)
        assertEquals(0, stored.scrapes)
    }

    @Test
    fun `an unusable address is refused before anything is read or dialled`() = runTest {
        val leg = LocalLeg()
        val manager = manager(leg)

        val result = manager.connectLocal(LocalGatewayProfile("http://10.0.0.1:9119", secretSlotId = "row-local"))

        assertTrue(result is GatewayConnectResult.Failed)
        assertEquals(LocalGatewayCopy.INVALID_URL, (result as GatewayConnectResult.Failed).message)
        assertEquals("no slot was read", 0, leg.tokenStore.reads)
        assertNull(leg.healthCheckedUrl)
    }

    @Test
    fun `losing the network never disconnects a Gateway on this device`() = runTest {
        val leg = LocalLeg()
        val manager = manager(leg, managerScope = backgroundScope)
        assertTrue(manager.connectLocal(PROFILE) is GatewayConnectResult.Connected)

        // Airplane mode is exactly where someone is when the only Hermes they
        // have is the one in Termux beside them. Loopback does not travel over
        // the network that just went away.
        manager.networkAvailabilityChanged(false)
        runCurrent()

        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
        assertFalse(leg.rpc.socketClosed)

        manager.networkChanged()
        runCurrent()

        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
        assertFalse(leg.rpc.socketClosed)

        manager.disconnect()
        runCurrent()
    }

    @Test
    fun `a stored token this row may not use is never answered with a scraped one`() = runTest {
        // What a re-addressed row leaves behind: the slot holds a token minted
        // for the address the row used to name, and the binding refuses it. The
        // binding would mean nothing if the connector then read a credential off
        // whatever now answers at the new address.
        val leg = LocalLeg(scraped = "scraped-token-fixture")
        leg.tokenStore.refuses = true

        val result = manager(leg).connectLocal(PROFILE)

        assertTrue(result is GatewayConnectResult.Failed)
        assertEquals(LocalGatewayCopy.TOKEN_MISSING, (result as GatewayConnectResult.Failed).message)
        assertEquals("a refusal is not an empty slot", 0, leg.scrapes)
        assertNull(leg.socketUrl)
    }

    @Test
    fun `losing the network mid-dial never cancels a Gateway on this device`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val leg = LocalLeg(healthGate = gate)
        val manager = manager(leg, managerScope = backgroundScope)

        val dialling = async { manager.connectLocal(PROFILE) }
        runCurrent()

        // The edge arrives while the readiness check is still in flight, which
        // is where the connect intent lives and where cancelling it would abort
        // a dial that was going to succeed.
        manager.networkAvailabilityChanged(false)
        manager.networkChanged()
        runCurrent()

        gate.complete(Unit)

        assertTrue("the dial survived the edge, got ${dialling.await()}", dialling.await() is GatewayConnectResult.Connected)
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
        manager.disconnect()
        runCurrent()
    }

    @Test
    fun `a Hermes that stops in Termux is reported as a closed connection, not a missing network`() = runTest {
        val leg = LocalLeg()
        val manager = manager(leg, managerScope = backgroundScope)
        assertTrue(manager.connectLocal(PROFILE) is GatewayConnectResult.Connected)

        manager.networkAvailabilityChanged(false)
        runCurrent()
        leg.rpc.dropSocket()
        runCurrent()

        assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
        assertEquals(
            "waiting for a network is advice nothing can act on here",
            "The Gateway connection closed. Reconnect to continue.",
            manager.state.value.message,
        )
        manager.disconnect()
        runCurrent()
    }

    @Test
    fun `the wire contract is the one pinned Hermes serves`() {
        val base = "http://127.0.0.1:9119"
        val token = TOKEN.toByteArray(Charsets.US_ASCII)

        val health = localGatewayHealthRequest(base, token)
        assertEquals("$base/api/health", health.url.toString())
        assertEquals("GET", health.method)
        assertEquals(TOKEN, health.header(SESSION_TOKEN_HEADER))

        // The socket authenticates in the query, which is where the Gateway
        // looks for it, and which `redact` already knows to hide.
        val socket = localGatewayWebSocketUrl(base, token)
        assertEquals("$base/api/ws?token=$TOKEN", socket.toString())
        assertEquals(TOKEN, socket.queryParameter("token"))

        // A base URL with a path keeps it: the route is joined onto the address
        // the person saved, not onto its root.
        assertEquals(
            "http://localhost:9200/hermes/api/health",
            localGatewayHealthRequest("http://localhost:9200/hermes", token).url.toString(),
        )
    }

    private fun TestScope.manager(
        leg: LocalLeg,
        managerScope: CoroutineScope = backgroundScope,
    ) = GatewayConnectionManager(
        scope = managerScope,
        installStore = GatewayInstallStore { "0123456789abcdef0123456789abcdef" },
        localConnector = leg.connector(),
    )

    /** Everything the Local route talks to, recorded. */
    private class LocalLeg(
        private val healthStatus: Int = 200,
        private val scraped: String? = null,
        /** Held open to keep a connect suspended inside the readiness check. */
        val healthGate: CompletableDeferred<Unit>? = null,
    ) {
        val tokenStore = FakeSessionTokenStore(TOKEN.toByteArray(Charsets.US_ASCII))
        val rpc = LocalRpc()
        var healthCheckedUrl: String? = null
        var healthToken: String? = null
        var socketUrl: String? = null
        var socketToken: String? = null
        var scrapes = 0

        fun connector() = LocalGatewayConnector(
            tokens = tokenStore,
            health = { url, token ->
                healthGate?.await()
                healthCheckedUrl = url
                healthToken = token.toString(Charsets.US_ASCII)
                when {
                    healthStatus == 401 || healthStatus == 403 ->
                        throw GatewayAuthException(LocalGatewayCopy.TOKEN_REFUSED, healthStatus)

                    healthStatus !in 200..299 ->
                        throw GatewayConnectionException(LocalGatewayCopy.NOT_ANSWERING)

                    else -> Unit
                }
            },
            rpcOpen = { url, token ->
                socketUrl = url
                socketToken = token.toString(Charsets.US_ASCII)
                rpc
            },
            scraper = { _ ->
                scrapes += 1
                scraped?.toByteArray(Charsets.US_ASCII)
            },
        )
    }

    private class FakeSessionTokenStore(var stored: ByteArray?) : GatewaySessionTokenStore {
        var reads = 0
        var erasures = 0

        /** Something is stored that this row may not use, as a re-address leaves. */
        var refuses = false

        /** The exact array a caller was given, so a test can watch it die. */
        var lastHandedOut: ByteArray? = null

        override suspend fun loadSessionToken(slot: GatewaySecretSlot): SessionTokenRead {
            reads += 1
            return when {
                refuses -> SessionTokenRead.Refused
                else -> stored?.copyOf()
                    ?.also { lastHandedOut = it }
                    ?.let(SessionTokenRead::Found)
                    ?: SessionTokenRead.Absent
            }
        }

        override suspend fun saveSessionToken(slot: GatewaySecretSlot, token: ByteArray) {
            stored = token.copyOf()
            token.fill(0)
        }

        override suspend fun clearSessionToken(slot: GatewaySecretSlot) {
            erasures += 1
            stored = null
        }
    }

    private class LocalRpc : GatewayRpcClient {
        override val events = MutableSharedFlow<GatewayEvent>()
        override val closed = MutableSharedFlow<Unit>(replay = 1)
        val calls = mutableListOf<String>()
        var failRequests = false
        var socketClosed = false

        override suspend fun request(method: String, params: JsonObject): JsonElement {
            calls += method
            if (failRequests) throw GatewayRpcException("The gateway connection closed.")
            return Json.parseToJsonElement("""{"sessions":[]}""")
        }

        override fun close() {
            socketClosed = true
        }

        /** What a `hermes serve` that exited in Termux looks like from here. */
        fun dropSocket() {
            closed.tryEmit(Unit)
        }
    }

    private companion object {
        const val TOKEN = "fixture-session-token_not-a-real-one"
        val PROFILE = LocalGatewayProfile(DEFAULT_LOCAL_GATEWAY_URL, secretSlotId = "row-local")
    }
}
