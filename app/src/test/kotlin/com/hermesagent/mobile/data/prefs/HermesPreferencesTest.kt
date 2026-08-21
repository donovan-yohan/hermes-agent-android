package com.hermesagent.mobile.data.prefs

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.data.composer.ComposerModelSelection
import com.hermesagent.mobile.data.composer.FastMode
import com.hermesagent.mobile.data.composer.ReasoningEffort
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

    private val preferences = HermesPreferences(ApplicationProvider.getApplicationContext())

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
    fun `shared Gateway route round-trips only non-secret connection metadata`() = runBlocking {
        val remote = RemoteGatewayProfile(
            baseUrl = "https://gateway.example/hermes",
            provider = "fixture-provider",
        )

        preferences.saveRemoteGatewayProfile(remote)
        preferences.saveGatewayConnectionMode(GatewayConnectionMode.Ssh)

        assertEquals(remote, preferences.remoteGatewayProfile.first())
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
            """{"version":"1","model":"future-model","provider":"future","reasoning":"ultra","fast":"turbo"}""",
        )

        assertEquals("future-model", restored?.selection?.model)
        assertEquals(ReasoningEffort.Unknown("ultra"), restored?.reasoning)
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
