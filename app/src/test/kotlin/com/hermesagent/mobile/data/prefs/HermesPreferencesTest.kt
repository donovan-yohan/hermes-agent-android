package com.hermesagent.mobile.data.prefs

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.data.composer.ComposerModelSelection
import com.hermesagent.mobile.data.composer.FastMode
import com.hermesagent.mobile.data.composer.ReasoningEffort
import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.ConnectionRegistryCodec
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.gateway.ActiveGatewayRoute
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.gateway.LocalGatewayProfile
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.ssh.HostProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the store keeps, and the migration that removes one legacy field.
 *
 * The store's closed list is host, port, username, optional remote profile,
 * method, fingerprint, and a per-install ownership id. An imported key's
 * display name used to be an extra entry: useless without the key, which is
 * memory-only and cannot survive a
 * restart, and capable of naming a target or an organisation all on its own
 * (`acme-prod-root.pem`). It is now screen state, and any value an earlier
 * build wrote is removed before the first read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HermesPreferencesTest {

    private val context: android.content.Context = ApplicationProvider.getApplicationContext()
    private val preferences = HermesPreferences(context)

    @Test
    fun `the saved profile round-trips every field the screen says is saved`() = runBlocking {
        preferences.saveHostProfile(SAVED)

        val loaded = preferences.hostProfile.first()

        assertEquals("test-host", loaded.host)
        assertEquals(2222, loaded.port)
        assertEquals("test-user", loaded.username)
        assertEquals("test-profile", loaded.remoteHermesProfile)
        assertEquals(AuthMethod.PrivateKey, loaded.authMethod)
        assertEquals(FINGERPRINT, loaded.acceptedFingerprint)
    }

    @Test
    fun `Remote Gateway route round-trips only non-secret connection metadata`() = runBlocking {
        val remote = RemoteGatewayProfile(
            baseUrl = "https://gateway.example/hermes",
            provider = "fixture-provider",
        )

        preferences.saveRemoteGatewayProfile(remote)
        preferences.saveGatewayConnectionMode(GatewayConnectionMode.Ssh, expectedConnectionId = null)

        val loaded = preferences.remoteGatewayProfile.first()
        assertEquals(remote.baseUrl, loaded.baseUrl)
        assertEquals(remote.provider, loaded.provider)
        // The slot is the active row's id, and it is the store's answer rather
        // than the caller's: a profile that travelled through the UI must not
        // be able to name another connection's Keystore entry.
        assertEquals(preferences.connectionRegistry.first().active?.id, loaded.secretSlotId)
        assertEquals(GatewayConnectionMode.Ssh, preferences.gatewayConnectionMode.first())
    }

    @Test
    fun `sidebar grouping round-trips as a non-secret view preference`() = runBlocking {
        try {
            preferences.saveSidebarGrouping(SidebarGrouping.Project)

            assertEquals(SidebarGrouping.Project, preferences.sidebarGrouping.first())
        } finally {
            preferences.saveSidebarGrouping(SidebarGrouping.Date)
        }
    }

    @Test
    fun `a display name an earlier build stored is removed before the first read`() = runBlocking {
        val stored = preferencesOf(
            LEGACY to "acme-prod-root.pem",
            stringPreferencesKey("host.single.host") to "test-host",
        )

        assertTrue("a stored name is exactly what has to trigger this", DropImportedKeyName.shouldMigrate(stored))
        val migrated = DropImportedKeyName.migrate(stored)

        assertNull(migrated[LEGACY])
        assertEquals("nothing else in the store may be disturbed", "test-host", migrated[HOST])
    }

    @Test
    fun `a store that never held a display name is left alone`() = runBlocking {
        val stored = preferencesOf(HOST to "test-host")

        assertFalse(DropImportedKeyName.shouldMigrate(stored))
    }

    @Test
    fun `the one connection an earlier build saved becomes row one, active, with nothing dropped`() = runBlocking {
        val stored = preferencesOf(
            stringPreferencesKey("gateway.single.connectionMode") to "Ssh",
            HOST to "test-host",
            intPreferencesKey("host.single.port") to 2222,
            stringPreferencesKey("host.single.username") to "test-user",
            stringPreferencesKey("host.single.remoteHermesProfile") to "test-profile",
            stringPreferencesKey("host.single.authMethod") to "PrivateKey",
            stringPreferencesKey("host.single.acceptedFingerprint") to FINGERPRINT,
            stringPreferencesKey("gateway.single.remote.url") to "https://gateway.example/hermes",
            stringPreferencesKey("gateway.single.remote.provider") to "fixture-provider",
        )

        assertTrue("a store with no registry is exactly what has to trigger this", AdoptConnectionRegistry.shouldMigrate(stored))
        val migrated = AdoptConnectionRegistry.migrate(stored)

        val rows = ConnectionRegistryCodec.decode(migrated[CONNECTIONS])
        val row = rows.single()
        assertEquals("the row is the active one", row.id, migrated[ACTIVE_CONNECTION_ID])
        assertEquals(ConnectionKind.Ssh, row.kind)
        assertEquals("test-host", row.host.host)
        assertEquals(2222, row.host.port)
        assertEquals("test-user", row.host.username)
        assertEquals("test-profile", row.host.remoteHermesProfile)
        assertEquals(AuthMethod.PrivateKey, row.host.authMethod)
        assertEquals("trust survives the move, or the next connect is a surprise", FINGERPRINT, row.host.acceptedFingerprint)
        assertEquals("https://gateway.example/hermes", row.remote.baseUrl)
        assertEquals("fixture-provider", row.remote.provider)
    }

    @Test
    fun `the single-connection keys are gone once they are row one`() = runBlocking {
        val stored = preferencesOf(
            HOST to "test-host",
            stringPreferencesKey("gateway.single.remote.url") to "https://gateway.example/hermes",
        )

        val migrated = AdoptConnectionRegistry.migrate(stored)

        assertNull("two copies of a connection is one copy too many", migrated[HOST])
        assertNull(migrated[stringPreferencesKey("gateway.single.remote.url")])
        assertFalse("and the migration does not run twice", AdoptConnectionRegistry.shouldMigrate(migrated))
    }

    @Test
    fun `a device with nothing saved still gets one row rather than an empty registry`() = runBlocking {
        val migrated = AdoptConnectionRegistry.migrate(preferencesOf())

        val row = ConnectionRegistryCodec.decode(migrated[CONNECTIONS]).single()
        assertEquals(ConnectionKind.Remote, row.kind)
        assertEquals(row.id, migrated[ACTIVE_CONNECTION_ID])
        assertEquals("", row.remote.baseUrl)
    }

    @Test
    fun `a registry document this build cannot read is never written over`() = runBlocking {
        val newer = """{"version":"2","connections":[{"id":"kept","label":"Written by a newer build"}]}"""
        assertFalse("this build cannot read it", ConnectionRegistryCodec.isWritable(newer))
        assertTrue("an absent document is a fresh install", ConnectionRegistryCodec.isWritable(null))
        assertTrue(ConnectionRegistryCodec.isWritable("""{"version":"1","connections":[]}"""))
        assertFalse("and neither is a document that does not parse", ConnectionRegistryCodec.isWritable("{"))
    }

    @Test
    fun `an unreadable registry refuses every write rather than reseeding over it`() = runBlocking {
        val newer = """{"version":"2","connections":[{"id":"kept","label":"Written by a newer build"}]}"""
        context.hermesDataStore.edit { prefs -> prefs[CONNECTIONS] = newer }
        try {
            // Reading it honestly shows nothing: this build does not understand
            // the document. Writing would make a downgrade permanent.
            assertTrue(preferences.connectionRegistry.first().connections.isEmpty())

            preferences.saveConnection(SavedConnection("new-row", "Alpha", ConnectionKind.Remote))
            preferences.saveHostProfile(HostProfile("test-host", 22, "test-user"))
            preferences.setActiveConnection("new-row")

            assertEquals(
                "the newer build's document is exactly as it left it",
                newer,
                context.hermesDataStore.data.first()[CONNECTIONS],
            )
        } finally {
            context.hermesDataStore.edit { prefs -> prefs.remove(CONNECTIONS) }
        }
    }

    @Test
    fun `the single-connection readers are projections of the active row`() = runBlocking {
        val remote = SavedConnection(
            id = "fixture-remote",
            label = "Alpha",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile("https://gateway-a.example/hermes", "alpha"),
        )
        val ssh = SavedConnection(
            id = "fixture-ssh",
            label = "Beta",
            kind = ConnectionKind.Ssh,
            host = HostProfile("test-host", 2222, "test-user", authMethod = AuthMethod.Password),
        )
        try {
            preferences.saveConnection(remote)
            preferences.saveConnection(ssh)
            preferences.setActiveConnection(remote.id)

            assertEquals(GatewayConnectionMode.Remote, preferences.gatewayConnectionMode.first())
            assertEquals("https://gateway-a.example/hermes", preferences.remoteGatewayProfile.first().baseUrl)
            assertEquals(
                "the sign-in slot follows the row, never the URL",
                remote.id,
                preferences.remoteGatewayProfile.first().secretSlotId,
            )

            preferences.setActiveConnection(ssh.id)

            assertEquals(GatewayConnectionMode.Ssh, preferences.gatewayConnectionMode.first())
            assertEquals("test-host", preferences.hostProfile.first().host)
            assertEquals(2222, preferences.hostProfile.first().port)
        } finally {
            preferences.removeConnection(ssh.id)
            preferences.removeConnection(remote.id)
        }
    }

    @Test
    fun `editing the connection form writes the active row rather than a second copy`() = runBlocking {
        val first = SavedConnection("fixture-a", "Alpha", ConnectionKind.Remote)
        val second = SavedConnection("fixture-b", "Beta", ConnectionKind.Remote)
        try {
            preferences.saveConnection(first)
            preferences.saveConnection(second)
            preferences.setActiveConnection(second.id)

            preferences.saveRemoteGatewayProfile(RemoteGatewayProfile("https://gateway-b.example", "beta"))

            val rows = preferences.connectionRegistry.first().connections
            assertEquals("https://gateway-b.example", rows.first { it.id == second.id }.remote.baseUrl)
            assertEquals("the row nobody edited is untouched", "", rows.first { it.id == first.id }.remote.baseUrl)
            assertEquals("no third row appears", 2, rows.count { it.id == first.id || it.id == second.id })
        } finally {
            preferences.removeConnection(second.id)
            preferences.removeConnection(first.id)
        }
    }

    /**
     * The route form persists on every keystroke, so one of its writes can be in
     * flight when the switcher moves the marker. The row a character was typed
     * against travels with the profile, and it is the only thing that tells that
     * write apart from a legitimate edit of the row now active.
     */
    @Test
    fun `a route edit stamped for a row that is no longer active is dropped, not redirected`() = runBlocking {
        val first = SavedConnection("fixture-a", "Alpha", ConnectionKind.Remote)
        val second = SavedConnection("fixture-b", "Beta", ConnectionKind.Remote)
        try {
            preferences.saveConnection(first)
            preferences.saveConnection(second)
            preferences.setActiveConnection(second.id)

            preferences.saveRemoteGatewayProfile(
                RemoteGatewayProfile("https://typed-into-alpha.example", "alpha", secretSlotId = first.id),
            )

            val rows = preferences.connectionRegistry.first().connections
            assertEquals("the row it landed on is untouched", "", rows.first { it.id == second.id }.remote.baseUrl)
            assertEquals("and it is not redirected to the row it was for", "", rows.first { it.id == first.id }.remote.baseUrl)

            // A caller naming the active row, or naming no row at all, still writes.
            preferences.saveRemoteGatewayProfile(
                RemoteGatewayProfile("https://gateway-b.example", "beta", secretSlotId = second.id),
            )
            assertEquals(
                "https://gateway-b.example",
                preferences.connectionRegistry.first().connections.first { it.id == second.id }.remote.baseUrl,
            )
        } finally {
            preferences.removeConnection(second.id)
            preferences.removeConnection(first.id)
        }
    }

    /**
     * The route is read by a surface that renders the row, its kind and its
     * address at once. Handed out as three flows it reaches that surface as
     * three changes, one of which pairs the row just switched to with the route
     * and address of the row before it — and a keystroke landing in that gap
     * pins the surface on the old address. One value has no such gap.
     */
    @Test
    fun `a switch hands out one route, never a new row's kind over the last row's address`() = runBlocking {
        val first = SavedConnection(
            "fixture-a",
            "Alpha",
            ConnectionKind.Remote,
            remote = RemoteGatewayProfile("https://alpha.example", "alpha"),
        )
        val second = SavedConnection(
            "fixture-b",
            "Beta",
            ConnectionKind.Local,
            local = LocalGatewayProfile("http://127.0.0.1:9119"),
        )
        try {
            preferences.saveConnection(first)
            preferences.saveConnection(second)
            preferences.setActiveConnection(first.id)

            val seen = mutableListOf<ActiveGatewayRoute>()
            val collector = launch(Dispatchers.Unconfined) {
                preferences.activeGatewayRoute.collect { seen += it }
            }
            try {
                awaitRoute(seen, first.id)
                val before = seen.size

                preferences.setActiveConnection(second.id)
                awaitRoute(seen, second.id)

                assertEquals("one commit, one route", 1, seen.size - before)
                val route = seen.last()
                assertEquals(second.id, route.connectionId)
                assertEquals("the kind of the row it names", GatewayConnectionMode.Local, route.mode)
                assertEquals("and that row's Gateway URL, which is none", "", route.remote.baseUrl)
                assertEquals("stamped for the row it came from", second.id, route.remote.secretSlotId)
            } finally {
                collector.cancel()
            }
        } finally {
            preferences.removeConnection(second.id)
            preferences.removeConnection(first.id)
        }
    }

    @Test
    fun `a route change stamped for a row that is no longer active is dropped, not redirected`() = runBlocking {
        val first = SavedConnection("fixture-a", "Alpha", ConnectionKind.Remote)
        val second = SavedConnection("fixture-b", "Beta", ConnectionKind.Remote)
        try {
            preferences.saveConnection(first)
            preferences.saveConnection(second)
            preferences.setActiveConnection(second.id)

            val written = preferences.saveGatewayConnectionMode(GatewayConnectionMode.Local, first.id)

            assertFalse("the caller is told its change went nowhere", written)
            val rows = preferences.connectionRegistry.first().connections
            assertEquals("the row it landed on keeps its kind", ConnectionKind.Remote, rows.first { it.id == second.id }.kind)
            assertEquals("and so does the row it was for", ConnectionKind.Remote, rows.first { it.id == first.id }.kind)

            assertTrue(preferences.saveGatewayConnectionMode(GatewayConnectionMode.Local, second.id))
            assertEquals(
                ConnectionKind.Local,
                preferences.connectionRegistry.first().connections.first { it.id == second.id }.kind,
            )
        } finally {
            preferences.removeConnection(second.id)
            preferences.removeConnection(first.id)
        }
    }

    @Test
    fun `removing the active row moves the marker instead of leaving it dangling`() = runBlocking {
        val first = SavedConnection("fixture-a", "Alpha", ConnectionKind.Remote)
        val second = SavedConnection("fixture-b", "Beta", ConnectionKind.Remote)
        try {
            preferences.saveConnection(first)
            preferences.saveConnection(second)
            preferences.setActiveConnection(second.id)

            preferences.removeConnection(second.id)

            val registry = preferences.connectionRegistry.first()
            assertFalse(registry.connections.any { it.id == second.id })
            assertEquals(registry.connections.first().id, registry.active?.id)
        } finally {
            preferences.removeConnection(second.id)
            preferences.removeConnection(first.id)
        }
    }

    @Test
    fun `ownership id is stable per install and has no endpoint identity`() = runBlocking {
        val first = preferences.ownershipId()
        val second = preferences.ownershipId()

        assertEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{32}")))
        assertFalse(first.contains("test-host"))
        assertFalse(first.contains("test-user"))
    }

    @Test
    fun `manual composer controls restore only for the matching connection profile scope`() = runBlocking {
        val firstScope = ComposerControlsScope("remote:fixture-a", "profile-a")
        val secondScope = ComposerControlsScope("remote:fixture-b", "profile-a")
        val saved = NewDraftComposerPreference(
            selection = ComposerModelSelection("reasoner-v3", "acme", ComposerModelSelection.Source.Manual),
            reasoning = ReasoningEffort.High,
            fast = FastMode.Fast,
        )
        try {
            preferences.clearManual(firstScope)
            preferences.clearManual(secondScope)
            preferences.saveManual(firstScope, saved)

            val restored = preferences.preference(firstScope).first()
            assertEquals("reasoner-v3", restored?.selection?.model)
            assertEquals(ComposerModelSelection.Source.Manual, restored?.selection?.source)
            assertEquals(ReasoningEffort.High, restored?.reasoning)
            assertEquals(FastMode.Fast, restored?.fast)
            assertNull(preferences.preference(secondScope).first())
        } finally {
            preferences.clearManual(firstScope)
            preferences.clearManual(secondScope)
        }
    }

    @Test
    fun `composer controls codec fails closed for a future version and preserves unknown safe values`() {
        assertNull(ComposerControlsCodec.decode("""{"version":"2","model":"ignored"}"""))

        val restored = ComposerControlsCodec.decode(
            """{"version":"1","model":"future-model","provider":"future","reasoning":"future-level","fast":"turbo"}""",
        )

        assertEquals("future-model", restored?.selection?.model)
        assertEquals(ReasoningEffort.Unknown("future-level"), restored?.reasoning)
        assertEquals(FastMode.Unknown("turbo"), restored?.fast)
    }

    @Test
    fun `a saved model shortlist restores only for its own connection profile scope`() = runBlocking {
        val firstScope = ComposerControlsScope("remote:fixture-visible-a", "profile-a")
        val secondScope = ComposerControlsScope("remote:fixture-visible-b", "profile-a")
        val keys = setOf("acme::alpha", "acme::", "openai::gpt")

        // Never customised is null, which is what makes the curated default the
        // default rather than an empty picker.
        assertNull(preferences.visibleModels(firstScope).first())

        preferences.saveVisibleModels(firstScope, keys)

        assertEquals(keys, preferences.visibleModels(firstScope).first())
        // Another Gateway is another catalog: its keys would name models this
        // one does not serve.
        assertNull(preferences.visibleModels(secondScope).first())

        // "Every provider hidden" is a choice, and it is not "never customised".
        preferences.saveVisibleModels(firstScope, setOf("acme::"))
        assertEquals(setOf("acme::"), preferences.visibleModels(firstScope).first())
    }

    @Test
    fun `the model shortlist codec fails closed for a future version and keeps the sentinels`() {
        assertNull(ModelVisibilityCodec.decode(null))
        assertNull(ModelVisibilityCodec.decode("not json"))
        assertNull(ModelVisibilityCodec.decode("""{"version":"2","keys":["acme::alpha"]}"""))
        assertNull(ModelVisibilityCodec.decode("""{"version":"1"}"""))

        val encoded = ModelVisibilityCodec.encode(setOf("openai::gpt", "acme::alpha", "acme::"))
        assertEquals(setOf("openai::gpt", "acme::alpha", "acme::"), ModelVisibilityCodec.decode(encoded))
        // An explicit empty document is "everything hidden", not "no document".
        assertEquals(emptySet<String>(), ModelVisibilityCodec.decode(ModelVisibilityCodec.encode(emptySet())))
    }

    @Test
    fun `active composer scope follows the selected remote route and provider`() = runBlocking {
        try {
            preferences.saveGatewayConnectionMode(GatewayConnectionMode.Remote, expectedConnectionId = null)
            preferences.saveRemoteGatewayProfile(RemoteGatewayProfile("https://gateway-a.example/hermes/", "alpha"))
            val first = preferences.activeScope.first()
            preferences.saveRemoteGatewayProfile(RemoteGatewayProfile("https://gateway-b.example/hermes", "beta"))
            val second = preferences.activeScope.first()

            assertFalse(first == second)
            assertEquals(ComposerControlsScope("remote:https://gateway-a.example/hermes", "alpha"), first)
            assertEquals(ComposerControlsScope("remote:https://gateway-b.example/hermes", "beta"), second)
        } finally {
            preferences.saveRemoteGatewayProfile(RemoteGatewayProfile())
        }
    }

    /** DataStore emits on its own scope; this waits for the route under test to arrive. */
    private suspend fun awaitRoute(seen: List<ActiveGatewayRoute>, id: String) {
        withTimeout(ROUTE_TIMEOUT_MILLIS) {
            while (seen.lastOrNull()?.connectionId != id) delay(POLL_MILLIS)
        }
    }

    private companion object {
        const val ROUTE_TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 5L

        val LEGACY = stringPreferencesKey("host.single.importedKeyName")
        val HOST = stringPreferencesKey("host.single.host")

        /** Not a real fingerprint, and not from a real host. */
        const val FINGERPRINT = "SHA256:0pXQ0M2fEXAMPLEfingerprintDEMOonlyNOTreal01"

        val SAVED = HostProfile(
            host = "test-host",
            port = 2222,
            username = "test-user",
            remoteHermesProfile = "test-profile",
            authMethod = AuthMethod.PrivateKey,
            acceptedFingerprint = FINGERPRINT,
        )
    }
}
