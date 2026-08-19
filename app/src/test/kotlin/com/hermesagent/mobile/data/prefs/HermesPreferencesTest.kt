package com.hermesagent.mobile.data.prefs

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
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
 * What the store keeps, and the one field it deliberately drops.
 *
 * The SSH screen prints a closed list of what is saved — host, port, username,
 * method, fingerprint — and a closed list on a security screen is a promise. An
 * imported key's display name used to be a sixth entry that the copy never
 * named: useless without the key, which is memory-only and cannot survive a
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
        assertEquals(AuthMethod.PrivateKey, loaded.authMethod)
        assertEquals(FINGERPRINT, loaded.acceptedFingerprint)
    }

    @Test
    fun `an imported key display name is never written and never read back`() = runBlocking {
        preferences.saveHostProfile(SAVED.copy(importedKeyName = "acme-prod-root.pem"))

        assertNull(
            "the screen's enumeration of what is saved has to be the whole list",
            preferences.hostProfile.first().importedKeyName,
        )
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

    private companion object {
        val LEGACY = stringPreferencesKey("host.single.importedKeyName")
        val HOST = stringPreferencesKey("host.single.host")

        /** Not a real fingerprint, and not from a real host. */
        const val FINGERPRINT = "SHA256:0pXQ0M2fEXAMPLEfingerprintDEMOonlyNOTreal01"

        val SAVED = HostProfile(
            host = "test-host",
            port = 2222,
            username = "test-user",
            authMethod = AuthMethod.PrivateKey,
            acceptedFingerprint = FINGERPRINT,
        )
    }
}
