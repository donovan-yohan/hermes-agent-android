package com.hermesagent.mobile.ui.gateway

import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.ConnectionRegistry
import com.hermesagent.mobile.data.connections.ConnectionRegistryStore
import com.hermesagent.mobile.data.connections.ConnectionSwitchController
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.gateway.GatewayBrowserLauncher
import com.hermesagent.mobile.data.gateway.GatewayConnectResult
import com.hermesagent.mobile.data.gateway.GatewayConnectionController
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.LocalGatewayCopy
import com.hermesagent.mobile.data.gateway.LocalGatewayProfile
import com.hermesagent.mobile.data.gateway.DEFAULT_LOCAL_GATEWAY_URL
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.SshCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `ConnectionsViewModel`: add/edit/remove against a real
 * [ConnectionSwitchController] over in-memory fakes.
 *
 * The point of most of these is ordering and refusal: a credential must be
 * erased before its row can disappear (even when the row's URL is unusable),
 * an unaddressable or duplicate URL must never reach the store, and only a
 * re-addressed *active* row should tear anything down — a rename must not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun useVirtualMain() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun releaseMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun `delete erases then removes`() = runTest(dispatcher) {
        val target = SavedConnection(
            id = "one",
            label = "Alpha",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://alpha.test"),
        )
        val other = SavedConnection(
            id = "two",
            label = "Beta",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://beta.test"),
        )
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(listOf(target, other), activeId = "two")
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        var forgottenBeforeRemoval = false
        store.onRemoveConnection = { id ->
            if (id == "one") {
                forgottenBeforeRemoval = gateway.forgotten.any { it.secretSlotId == "one" }
            }
        }

        subject.requestRemove("one")
        subject.confirmRemove()
        advanceUntilIdle()

        assertTrue("forget must land before the row is removed", forgottenBeforeRemoval)
        assertEquals(1, gateway.forgotten.size)
        assertEquals("one", gateway.forgotten.single().secretSlotId)
        assertNull(subject.uiState.value.connections.firstOrNull { it.id == "one" })
    }

    @Test
    fun `delete still erases a row whose URL is blank`() = runTest(dispatcher) {
        val blank = SavedConnection(
            id = "blank",
            label = "Blank",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = ""),
        )
        val other = SavedConnection(
            id = "two",
            label = "Beta",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://beta.test"),
        )
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(listOf(blank, other), activeId = "two")
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.requestRemove("blank")
        subject.confirmRemove()
        advanceUntilIdle()

        assertEquals(1, gateway.forgotten.size)
        assertEquals("blank", gateway.forgotten.single().secretSlotId)
        assertNull(subject.uiState.value.connections.firstOrNull { it.id == "blank" })
    }

    @Test
    fun `the last row cannot be removed`() = runTest(dispatcher) {
        val only = SavedConnection(
            id = "one",
            label = "Alpha",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://alpha.test"),
        )
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(listOf(only), activeId = "one")
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.requestRemove("one")
        subject.confirmRemove()
        advanceUntilIdle()

        assertTrue(gateway.calls.isEmpty())
        assertTrue(gateway.forgotten.isEmpty())
        assertEquals(listOf(only), subject.uiState.value.connections)
    }

    @Test
    fun `an invalid Remote URL is refused with product copy`() = runTest(dispatcher) {
        val existing = SavedConnection(
            id = "one",
            label = "Alpha",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://alpha.test"),
        )
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(listOf(existing), activeId = "one")
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginAdd()
        subject.editLabel("Alpha")
        subject.editUrl("not a url")
        subject.saveEditor()
        advanceUntilIdle()

        val editor = subject.uiState.value.editor
        assertNotNull(editor)
        assertEquals(ConnectionsCopy.INVALID_URL, editor?.error)
        assertEquals(listOf(existing), store.connectionRegistry.first().connections)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `a duplicate gateway URL is refused inline`() = runTest(dispatcher) {
        val one = SavedConnection(
            id = "one",
            label = "Alpha",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://alpha.test"),
        )
        val two = SavedConnection(
            id = "two",
            label = "Beta",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://beta.test"),
        )
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(listOf(one, two), activeId = "two")
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginEdit("one")
        subject.editUrl("https://beta.test")
        subject.saveEditor()
        advanceUntilIdle()

        val editor = subject.uiState.value.editor
        assertNotNull(editor)
        assertEquals(ConnectionsCopy.duplicateUrl("Beta"), editor?.error)
        assertEquals(
            listOf(one, two),
            store.connectionRegistry.first().connections,
        )
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `re-addressing the active row tears the old endpoint down`() = runTest(dispatcher) {
        val one = SavedConnection(
            id = "one",
            label = "Alpha",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://alpha.test"),
        )
        val two = SavedConnection(
            id = "two",
            label = "Beta",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://beta.test"),
        )
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(listOf(one, two), activeId = "one")
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginEdit("one")
        subject.editUrl("https://alpha2.test")
        subject.saveEditor()
        advanceUntilIdle()

        assertTrue("the old endpoint must be torn down", gateway.calls.contains("disconnect"))
        assertTrue(
            "the abandoned credential must be erased",
            gateway.forgotten.any { it.secretSlotId == "one" && it.baseUrl == "https://alpha.test" },
        )
        assertEquals(
            "https://alpha2.test",
            store.connectionRegistry.first().connections.first { it.id == "one" }.remote.baseUrl,
        )
    }

    @Test
    fun `a registry this build may not write says so instead of appearing to save`() = runTest(dispatcher) {
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(twoRows(), activeId = "one")
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()
        // What a downgrade looks like: the stored document belongs to a newer
        // build, so it is left untouched and every write is refused.
        store.writable.value = false
        advanceUntilIdle()

        subject.beginEdit("one")
        subject.editLabel("Renamed")
        subject.saveEditor()
        advanceUntilIdle()

        val editor = subject.uiState.value.editor
        assertNotNull("closing over a write that never happened looks like success", editor)
        assertEquals(ConnectionsCopy.REGISTRY_LOCKED, editor?.error)
        assertEquals("Renamed", editor?.label)
        assertEquals(
            "and nothing was written",
            "Alpha",
            store.connectionRegistry.value.connections.first { it.id == "one" }.label,
        )
        assertEquals(false, subject.uiState.value.writable)
    }

    @Test
    fun `a registry this build may not write refuses a removal too`() = runTest(dispatcher) {
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(twoRows(), activeId = "one")
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()
        store.writable.value = false
        advanceUntilIdle()

        subject.requestRemove("two")
        subject.confirmRemove()
        advanceUntilIdle()

        assertEquals(2, store.connectionRegistry.value.connections.size)
        assertEquals(emptyList<RemoteGatewayProfile>(), gateway.forgotten)
    }

    @Test
    fun `renaming the active row does not tear anything down`() = runTest(dispatcher) {
        val one = SavedConnection(
            id = "one",
            label = "Alpha",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://alpha.test"),
        )
        val two = SavedConnection(
            id = "two",
            label = "Beta",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://beta.test"),
        )
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(listOf(one, two), activeId = "one")
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginEdit("one")
        subject.editLabel("Alpha Renamed")
        subject.saveEditor()
        advanceUntilIdle()

        assertFalse(gateway.calls.contains("disconnect"))
        assertTrue(gateway.forgotten.isEmpty())
        assertEquals(
            "Alpha Renamed",
            store.connectionRegistry.first().connections.first { it.id == "one" }.label,
        )
    }

    @Test
    fun `choosing Local prefills the one address anyone starts, and choosing away takes it back`() =
        runTest(dispatcher) {
            val subject = buildSubject(MemoryRegistryStore(twoRows(), activeId = "one"), RecordingGateway())
            backgroundScope.launch { subject.uiState.collect { } }
            advanceUntilIdle()

            subject.beginAdd()
            subject.editKind(ConnectionKind.Local)
            assertEquals(DEFAULT_LOCAL_GATEWAY_URL, subject.uiState.value.editor?.url)

            // A loopback address left behind in a Remote form is a URL that
            // route will refuse, offered as if it were a suggestion.
            subject.editKind(ConnectionKind.Remote)
            assertEquals("", subject.uiState.value.editor?.url)
        }

    @Test
    fun `a Local row cannot be saved without the token that is its only boundary`() = runTest(dispatcher) {
        val store = MemoryRegistryStore(twoRows(), activeId = "one")
        val gateway = RecordingGateway()
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginAdd()
        subject.editKind(ConnectionKind.Local)
        subject.editLabel("This phone")
        subject.saveEditor()
        advanceUntilIdle()

        val editor = subject.uiState.value.editor
        assertNotNull("a refusal keeps the form open, with the typing still in it", editor)
        assertEquals(ConnectionsCopy.TOKEN_REQUIRED, editor?.error)
        assertEquals(2, store.connectionRegistry.value.connections.size)
        assertTrue(gateway.storedTokens.isEmpty())
    }

    @Test
    fun `saving a Local row stores its token bound to the address that row names`() = runTest(dispatcher) {
        val store = MemoryRegistryStore(twoRows(), activeId = "one")
        val gateway = RecordingGateway()
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginAdd()
        subject.editKind(ConnectionKind.Local)
        subject.editLabel("This phone")
        subject.editToken("demo-session-token")
        subject.saveEditor()
        advanceUntilIdle()

        assertNull("an accepted save closes the form", subject.uiState.value.editor)
        val saved = store.connectionRegistry.value.connections.first { it.kind == ConnectionKind.Local }
        assertEquals(DEFAULT_LOCAL_GATEWAY_URL, saved.local.baseUrl)
        assertEquals("127.0.0.1:9119", saved.endpoint)
        assertEquals(SavedConnection.SESSION_TOKEN, saved.authModeLabel)
        assertEquals(
            listOf("demo-session-token" to DEFAULT_LOCAL_GATEWAY_URL),
            gateway.storedTokens,
        )
    }

    @Test
    fun `two spellings of this device on one port are one gateway`() = runTest(dispatcher) {
        val existing = localRow("local", "This phone", DEFAULT_LOCAL_GATEWAY_URL)
        val store = MemoryRegistryStore(listOf(existing, twoRows().first()), activeId = "one")
        val gateway = RecordingGateway()
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginAdd()
        subject.editKind(ConnectionKind.Local)
        subject.editLabel("Same phone again")
        // localhost, 127.0.0.1 and [::1] on one port are one server.
        subject.editUrl("http://localhost:9119")
        subject.editToken("demo-session-token")
        subject.saveEditor()
        advanceUntilIdle()

        assertEquals(ConnectionsCopy.duplicateUrl("This phone"), subject.uiState.value.editor?.error)
        assertEquals(2, store.connectionRegistry.value.connections.size)
        assertTrue("nothing may be stored for a row that was not saved", gateway.storedTokens.isEmpty())
    }

    @Test
    fun `re-addressing a Local row asks for the token again, then rebinds the slot`() = runTest(dispatcher) {
        val existing = localRow("local", "This phone", DEFAULT_LOCAL_GATEWAY_URL)
        val store = MemoryRegistryStore(listOf(existing, twoRows().first()), activeId = "local")
        val gateway = RecordingGateway()
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginEdit("local")
        subject.editUrl("http://127.0.0.1:9200")
        subject.saveEditor()
        advanceUntilIdle()

        assertEquals(
            "the slot is bound to the address that minted it, so the old token cannot follow",
            ConnectionsCopy.TOKEN_READDRESSED,
            subject.uiState.value.editor?.error,
        )
        assertEquals(DEFAULT_LOCAL_GATEWAY_URL, store.connectionRegistry.value.connections.first { it.id == "local" }.local.baseUrl)

        subject.editToken("a-different-token")
        subject.saveEditor()
        advanceUntilIdle()

        assertEquals(
            "http://127.0.0.1:9200",
            store.connectionRegistry.value.connections.first { it.id == "local" }.local.baseUrl,
        )
        assertEquals(listOf("local"), gateway.forgottenLocal.map { it.secretSlotId })
        assertEquals(
            "the erase clears the one slot both tokens share, so it has to land first",
            listOf("forget-local", "save-token"),
            gateway.calls.filter { it == "forget-local" || it == "save-token" },
        )
        assertEquals(listOf("a-different-token" to "http://127.0.0.1:9200"), gateway.storedTokens)
        assertTrue("re-addressing the active row leaves the old endpoint", gateway.calls.contains("disconnect"))
    }

    @Test
    fun `renaming a Local row keeps the token it already has`() = runTest(dispatcher) {
        val existing = localRow("local", "This phone", DEFAULT_LOCAL_GATEWAY_URL)
        val store = MemoryRegistryStore(listOf(existing, twoRows().first()), activeId = "local")
        val gateway = RecordingGateway()
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginEdit("local")
        subject.editLabel("Pocket Hermes")
        subject.saveEditor()
        advanceUntilIdle()

        assertNull(subject.uiState.value.editor)
        assertTrue("a blank field on a saved row means keep what is stored", gateway.storedTokens.isEmpty())
        assertTrue(gateway.forgottenLocal.isEmpty())
        assertFalse(gateway.calls.contains("disconnect"))
    }

    @Test
    fun `an unusable loopback address is refused with product copy`() = runTest(dispatcher) {
        val store = MemoryRegistryStore(twoRows(), activeId = "one")
        val gateway = RecordingGateway()
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginAdd()
        subject.editKind(ConnectionKind.Local)
        subject.editLabel("Not this device")
        subject.editUrl("http://hermes.example.com:9119")
        subject.editToken("demo-session-token")
        subject.saveEditor()
        advanceUntilIdle()

        assertEquals(LocalGatewayCopy.INVALID_URL, subject.uiState.value.editor?.error)
        assertEquals(2, store.connectionRegistry.value.connections.size)
        assertTrue(gateway.storedTokens.isEmpty())
    }

    @Test
    fun `the kind is fixed once a row exists`() = runTest(dispatcher) {
        val store = MemoryRegistryStore(twoRows(), activeId = "one")
        val subject = buildSubject(store, RecordingGateway())
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginEdit("one")
        subject.editKind(ConnectionKind.Local)

        assertEquals(
            "the fields, the trust and the secret slot all belong to one kind",
            ConnectionKind.Remote,
            subject.uiState.value.editor?.kind,
        )
    }

    @Test
    fun `leaving the surface drops a typed token and keeps the rest of the form`() = runTest(dispatcher) {
        val subject = buildSubject(MemoryRegistryStore(twoRows(), activeId = "one"), RecordingGateway())
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginAdd()
        subject.editKind(ConnectionKind.Local)
        subject.editLabel("This phone")
        subject.editToken("demo-session-token")

        subject.releaseScreen()

        val editor = subject.uiState.value.editor
        assertEquals("", editor?.token)
        assertEquals("This phone", editor?.label)
        assertEquals(DEFAULT_LOCAL_GATEWAY_URL, editor?.url)
    }

    @Test
    fun `the editor never prints a session token`() {
        val editor = ConnectionEditorState(kind = ConnectionKind.Local, token = "demo-session-token")

        assertFalse("a crash report must not carry a live credential", editor.toString().contains("demo-session-token"))
        assertTrue(editor.toString().contains("token=<redacted>"))
    }

    @Test
    fun `a token that is only whitespace never replaces the one that works`() = runTest(dispatcher) {
        val existing = localRow("local", "This phone", DEFAULT_LOCAL_GATEWAY_URL)
        val store = MemoryRegistryStore(listOf(existing, twoRows().first()), activeId = "local")
        val gateway = RecordingGateway()
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        // The address has not moved, so the row is allowed to keep its saved
        // token — which is exactly the branch where a blank write would land.
        subject.beginEdit("local")
        subject.editToken("   ")
        subject.saveEditor()
        advanceUntilIdle()

        assertNull("whitespace is not a token, and not a reason to refuse either", subject.uiState.value.editor)
        assertTrue(
            "overwriting a working token with spaces is a 401 nobody can diagnose",
            gateway.storedTokens.isEmpty(),
        )
    }

    @Test
    fun `a pasted token keeps none of the whitespace the terminal gave it`() = runTest(dispatcher) {
        val store = MemoryRegistryStore(twoRows(), activeId = "one")
        val gateway = RecordingGateway()
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginAdd()
        subject.editKind(ConnectionKind.Local)
        subject.editLabel("This phone")
        // What a long-press paste out of a Termux terminal actually delivers.
        subject.editToken("  demo-session-token\n")
        subject.saveEditor()
        advanceUntilIdle()

        assertEquals(
            "the Gateway compares the header literally, so a stray newline is a permanent 401",
            listOf("demo-session-token" to DEFAULT_LOCAL_GATEWAY_URL),
            gateway.storedTokens,
        )
    }

    @Test
    fun `a token carrying something no header can express is refused, not mangled`() = runTest(dispatcher) {
        val store = MemoryRegistryStore(twoRows(), activeId = "one")
        val gateway = RecordingGateway()
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginAdd()
        subject.editKind(ConnectionKind.Local)
        subject.editLabel("This phone")
        // A smart quote out of a notes app; ASCII encoding would flatten it to
        // `?` and the refusal would arrive from the Gateway with no diagnosis.
        subject.editToken("demo\u2019session\u2019token")
        subject.saveEditor()
        advanceUntilIdle()

        assertEquals(ConnectionsCopy.TOKEN_UNREADABLE, subject.uiState.value.editor?.error)
        assertTrue(gateway.storedTokens.isEmpty())
    }

    @Test
    fun `a Keystore that refuses the token saves no row and gives the form back`() = runTest(dispatcher) {
        val store = MemoryRegistryStore(twoRows(), activeId = "one")
        val gateway = RecordingGateway().apply { failTokenWrites = true }
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginAdd()
        subject.editKind(ConnectionKind.Local)
        subject.editLabel("This phone")
        subject.editToken("demo-session-token")
        subject.saveEditor()
        advanceUntilIdle()

        val editor = subject.uiState.value.editor
        assertNotNull("losing the row and the form to a failed write is the worst outcome", editor)
        assertEquals(ConnectionsCopy.TOKEN_NOT_STORED, editor?.error)
        assertEquals("demo-session-token", editor?.token)
        assertEquals(
            "nothing is half-saved: no row either",
            2,
            store.connectionRegistry.value.connections.size,
        )
    }

    @Test
    fun `an address that only looks different leaves the live connection alone`() = runTest(dispatcher) {
        val existing = localRow("local", "This phone", DEFAULT_LOCAL_GATEWAY_URL)
        val store = MemoryRegistryStore(listOf(existing, twoRows().first()), activeId = "local")
        val gateway = RecordingGateway()
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginEdit("local")
        // Same server, one trailing slash. The token rule and the erase rule
        // both normalize, so the teardown rule has to as well.
        subject.editUrl("$DEFAULT_LOCAL_GATEWAY_URL/")
        subject.saveEditor()
        advanceUntilIdle()

        assertNull(subject.uiState.value.editor)
        assertFalse("nothing moved, so nothing may be torn down", gateway.calls.contains("disconnect"))
        assertTrue(gateway.forgottenLocal.isEmpty())
        assertTrue(gateway.storedTokens.isEmpty())
    }

    /** One saved Local row, its slot id stamped the way the registry stamps it. */
    private fun localRow(id: String, label: String, url: String) = SavedConnection(
        id = id,
        label = label,
        kind = ConnectionKind.Local,
        local = LocalGatewayProfile(baseUrl = url),
    )

    /** Two ordinary Remote rows; no real host, user or URL anywhere. */
    private fun twoRows() = listOf(
        SavedConnection("one", "Alpha", ConnectionKind.Remote, RemoteGatewayProfile("https://alpha.test")),
        SavedConnection("two", "Beta", ConnectionKind.Remote, RemoteGatewayProfile("https://beta.test")),
    )

    private fun buildSubject(store: MemoryRegistryStore, gateway: RecordingGateway): ConnectionsViewModel {
        val switch = ConnectionSwitchController(store, gateway, SessionCache(), settleTimeoutMillis = 50L)
        return ConnectionsViewModel(store, gateway, switch)
    }

    private class RecordingGateway : GatewayConnectionController {
        private val _state = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Disconnected))
        override val state: StateFlow<GatewayConnectionState> = _state.asStateFlow()

        /** Ordered record of every call this fake saw. */
        val calls = mutableListOf<String>()

        /** Every profile handed to [forgetRemoteAuthentication], in order. */
        val forgotten = mutableListOf<RemoteGatewayProfile>()

        /** Every Local profile whose session token was erased, in order. */
        val forgottenLocal = mutableListOf<LocalGatewayProfile>()

        /**
         * Every session token stored, as the text it was, paired with the slot
         * it was bound to. Recorded before the bytes are zeroed, which is what
         * lets a test assert both the value and that the caller's array was
         * taken over.
         */
        val storedTokens = mutableListOf<Pair<String, String>>()

        /** Stands in for a Keystore alias this device has invalidated. */
        var failTokenWrites = false

        override suspend fun connect(profile: HostProfile, credential: SshCredential): GatewayConnectResult =
            GatewayConnectResult.Connected

        override suspend fun connectRemote(
            profile: RemoteGatewayProfile,
            browser: GatewayBrowserLauncher,
        ): GatewayConnectResult = GatewayConnectResult.Connected

        override suspend fun forgetRemoteAuthentication(profile: RemoteGatewayProfile) {
            calls += "forget"
            forgotten += profile
        }

        override suspend fun forgetLocalAuthentication(profile: LocalGatewayProfile) {
            calls += "forget-local"
            forgottenLocal += profile
        }

        override suspend fun saveLocalSessionToken(profile: LocalGatewayProfile, token: ByteArray) {
            calls += "save-token"
            if (failTokenWrites) {
                token.fill(0)
                throw IllegalStateException("keystore refused")
            }
            storedTokens += token.toString(Charsets.US_ASCII) to (profile.normalizedBaseUrl ?: "")
            // The real store takes ownership and zeroes; the fake has to too, or
            // a test could pass against a copy production would have wiped.
            token.fill(0)
        }

        override suspend fun disconnect() {
            calls += "disconnect"
            // Publish Connected immediately so a caller's settle wait
            // resolves without burning virtual time.
            _state.value = GatewayConnectionState(GatewayConnectionStatus.Connected)
        }
    }

    private class MemoryRegistryStore(
        rows: List<SavedConnection>,
        activeId: String?,
    ) : ConnectionRegistryStore {
        private val registry = MutableStateFlow(ConnectionRegistry(rows, activeId))
        override val connectionRegistry: StateFlow<ConnectionRegistry> = registry.asStateFlow()

        /** False stands in for a stored document written by a newer build. */
        val writable = MutableStateFlow(true)
        override val connectionRegistryWritable: StateFlow<Boolean> = writable.asStateFlow()

        /** Fired at the start of [removeConnection], before the row is gone, for ordering assertions. */
        var onRemoveConnection: ((String) -> Unit)? = null

        override suspend fun saveConnection(connection: SavedConnection) {
            registry.update { current ->
                val index = current.connections.indexOfFirst { it.id == connection.id }
                val rows = if (index >= 0) {
                    current.connections.toMutableList().also { it[index] = connection }
                } else {
                    current.connections + connection
                }
                current.copy(connections = rows)
            }
        }

        override suspend fun removeConnection(id: String) {
            onRemoveConnection?.invoke(id)
            registry.update { current ->
                val rows = current.connections.filterNot { it.id == id }
                val active = if (current.activeId == id) rows.firstOrNull()?.id else current.activeId
                current.copy(connections = rows, activeId = active)
            }
        }

        override suspend fun setActiveConnection(id: String) {
            registry.update { it.copy(activeId = id) }
        }
    }
}
