package com.hermesagent.mobile.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.HostProfileStore
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.hermesDataStore: DataStore<Preferences> by preferencesDataStore(name = "hermes")

/**
 * Everything this app puts on disk.
 *
 * The list is short by design, and every entry is non-secret:
 * - the chosen theme and light/dark mode;
 * - host, port, username, auth *method*, and the accepted host-key fingerprint.
 *
 * Passwords, passphrases and private keys are **not** here and have no code
 * path that could put them here — they live in [com.hermesagent.mobile.data.ssh.SshCredential],
 * which is built in the UI, handed to a probe, and cleared.
 *
 * Keys carry their scope, per `apps/desktop/AGENTS.md` ("Persisted state must
 * declare its scope in its own key"). Phase 1 has exactly one host profile, so
 * the scope is `host.single.*`; when profiles become a list the key becomes
 * `host.<id>.*` and the single-profile keys are migrated, not overloaded.
 */
class HermesPreferences(private val context: Context) : HostProfileStore {

    val appearance: Flow<AppearanceSelection> = context.hermesDataStore.data.map { prefs ->
        AppearanceSelection(
            themeName = prefs[THEME_NAME] ?: BuiltinThemes.DEFAULT_NAME,
            mode = prefs[THEME_MODE]?.toThemeMode() ?: HermesThemeMode.System,
        )
    }

    override val hostProfile: Flow<HostProfile> = context.hermesDataStore.data.map { prefs ->
        HostProfile(
            host = prefs[HOST] ?: "",
            port = prefs[PORT] ?: 22,
            username = prefs[USERNAME] ?: "",
            authMethod = prefs[AUTH_METHOD]?.toAuthMethod() ?: AuthMethod.Password,
            acceptedFingerprint = prefs[ACCEPTED_FINGERPRINT],
            importedKeyName = prefs[IMPORTED_KEY_NAME],
        )
    }

    suspend fun setTheme(name: String) = context.hermesDataStore.edit { it[THEME_NAME] = name }

    suspend fun setMode(mode: HermesThemeMode) =
        context.hermesDataStore.edit { it[THEME_MODE] = mode.name }

    /** Persists the non-secret fields only. Callers cannot pass a secret in. */
    override suspend fun saveHostProfile(profile: HostProfile) {
        context.hermesDataStore.edit { prefs ->
            prefs[HOST] = profile.host
            prefs[PORT] = profile.port
            prefs[USERNAME] = profile.username
            prefs[AUTH_METHOD] = profile.authMethod.name
            profile.acceptedFingerprint?.let { prefs[ACCEPTED_FINGERPRINT] = it }
                ?: prefs.remove(ACCEPTED_FINGERPRINT)
            profile.importedKeyName?.let { prefs[IMPORTED_KEY_NAME] = it }
                ?: prefs.remove(IMPORTED_KEY_NAME)
        }
    }

    private companion object {
        val THEME_NAME = stringPreferencesKey("appearance.theme")
        val THEME_MODE = stringPreferencesKey("appearance.mode")
        val HOST = stringPreferencesKey("host.single.host")
        val PORT = intPreferencesKey("host.single.port")
        val USERNAME = stringPreferencesKey("host.single.username")
        val AUTH_METHOD = stringPreferencesKey("host.single.authMethod")
        val ACCEPTED_FINGERPRINT = stringPreferencesKey("host.single.acceptedFingerprint")
        val IMPORTED_KEY_NAME = stringPreferencesKey("host.single.importedKeyName")

        fun String.toThemeMode(): HermesThemeMode =
            HermesThemeMode.entries.firstOrNull { it.name == this } ?: HermesThemeMode.System

        fun String.toAuthMethod(): AuthMethod =
            AuthMethod.entries.firstOrNull { it.name == this } ?: AuthMethod.Password
    }
}
