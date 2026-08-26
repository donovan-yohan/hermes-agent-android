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
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.ssh.HostProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
        preferences.saveGatewayConnectionMode(GatewayConnectionMode.Ssh)

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
    fun `active composer scope follows the selected remote route and provider`() = runBlocking {
        try {
            preferences.saveGatewayConnectionMode(GatewayConnectionMode.Remote)
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

    private companion object {
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
