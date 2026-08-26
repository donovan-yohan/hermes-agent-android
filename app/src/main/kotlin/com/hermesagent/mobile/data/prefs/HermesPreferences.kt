package com.hermesagent.mobile.data.prefs

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hermesagent.mobile.data.composer.ComposerModelSelection
import com.hermesagent.mobile.data.composer.FastMode
import com.hermesagent.mobile.data.composer.ReasoningEffort
import com.hermesagent.mobile.data.profiles.DEFAULT_PROFILE
import com.hermesagent.mobile.data.profiles.ProfileScope
import com.hermesagent.mobile.data.profiles.normalizeProfileKey
import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.HostProfileStore
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.gateway.GatewayInstallStore
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfileStore
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom

private val Context.hermesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "hermes",
    produceMigrations = { listOf(DropImportedKeyName) },
)

/** The key an earlier build wrote the imported key's display name under. */
private val LEGACY_IMPORTED_KEY_NAME = stringPreferencesKey("host.single.importedKeyName")

/**
 * Removes a display name an earlier build left behind.
 *
 * The name is useless without the key, which is memory-only and never survives
 * a restart, and a document name can identify a target or an organisation
 * (`acme-prod-root.pem`). So it stops being written *and* the value that is
 * already on disk goes, rather than sitting there until the next save happens
 * to overwrite it — a process that dies between an import and the first probe
 * result would otherwise leave it indefinitely.
 *
 * Written as a [DataMigration] because that is the one hook that runs before
 * the first read of the store, exactly once, whatever wakes it first.
 */
internal object DropImportedKeyName : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData.contains(LEGACY_IMPORTED_KEY_NAME)

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply { remove(LEGACY_IMPORTED_KEY_NAME) }

    override suspend fun cleanUp() = Unit
}

/**
 * Everything this connection/appearance preference store puts on disk.
 *
 * The list is short by design, and every entry is non-secret:
 * - the chosen theme and light/dark mode;
 * - the session sidebar's grouping mode and active profile scope;
 * - the selected Gateway route and the Remote Gateway's non-secret URL/provider;
 * - SSH host, port, username, remote Hermes profile, auth *method*, and the
 *   accepted host-key fingerprint;
 * - one random per-install Gateway ownership id.
 * - scoped, manual new-draft composer model/provider/reasoning/fast choices.
 *
 * That is the whole list for this store. Private text-only composer drafts live
 * in a separate, no-backup `SessionDraftStore`; they never become connection
 * preferences. Product UI does not carry the storage lecture; the
 * review workflow and this typed store are the detailed authority. The
 * imported key's display name is deliberately **not** on it: see
 * [DropImportedKeyName].
 *
 * Passwords, passphrases and private keys are **not** here and have no code
 * path that could put them here — they live in [com.hermesagent.mobile.data.ssh.SshCredential],
 * which is built in the UI, handed to one SSH attempt, and cleared.
 *
 * Keys carry their scope, per `apps/desktop/AGENTS.md` ("Persisted state must
 * declare its scope in its own key"). This slice has exactly one host profile, so
 * the scope is `host.single.*`; when profiles become a list the key becomes
 * `host.<id>.*` and the single-profile keys are migrated, not overloaded.
 */
class HermesPreferences(private val context: Context) :
    HostProfileStore,
    GatewayInstallStore,
    RemoteGatewayProfileStore,
    SidebarViewStore,
    ProfileScopeStore,
    ComposerControlsStore {

    val appearance: Flow<AppearanceSelection> = context.hermesDataStore.data.map { prefs ->
        AppearanceSelection(
            themeName = prefs[THEME_NAME] ?: BuiltinThemes.DEFAULT_NAME,
            mode = prefs[THEME_MODE]?.toThemeMode() ?: HermesThemeMode.System,
        )
    }

    override val sidebarGrouping: Flow<SidebarGrouping> = context.hermesDataStore.data.map { prefs ->
        prefs[SIDEBAR_GROUPING]
            ?.let { stored -> SidebarGrouping.entries.firstOrNull { it.name == stored } }
            ?: SidebarGrouping.Date
    }

    /**
     * The scope the rail last left the sidebar in. An unrecognised or blank
     * profile name normalises to the Gateway's own profile, which is what a
     * fresh install carries — so a profile deleted on the host degrades to the
     * default scope rather than to an empty sidebar.
     */
    override val profileScope: Flow<ProfileScope> = context.hermesDataStore.data.map { prefs ->
        ProfileScope(
            activeProfile = normalizeProfileKey(prefs[PROFILE_ACTIVE]),
            showAllProfiles = prefs[PROFILE_SHOW_ALL] == "true",
        )
    }

    /**
     * A key that is absent means nothing has ever been saved, so the fresh
     * [HostProfile] defaults answer — including Tailscale SSH as the starting
     * auth method. A key that is *present* is the user's own past choice and
     * always wins; [toAuthMethod] is what handles a value this build does not
     * know.
     */
    override val hostProfile: Flow<HostProfile> = context.hermesDataStore.data.map { prefs ->
        HostProfile(
            host = prefs[HOST] ?: FRESH.host,
            port = prefs[PORT] ?: FRESH.port,
            username = prefs[USERNAME] ?: FRESH.username,
            remoteHermesProfile = prefs[REMOTE_HERMES_PROFILE] ?: FRESH.remoteHermesProfile,
            authMethod = prefs[AUTH_METHOD]?.toAuthMethod() ?: FRESH.authMethod,
            acceptedFingerprint = prefs[ACCEPTED_FINGERPRINT],
        )
    }

    override val remoteGatewayProfile: Flow<RemoteGatewayProfile> = context.hermesDataStore.data.map { prefs ->
        RemoteGatewayProfile(
            baseUrl = prefs[REMOTE_GATEWAY_URL].orEmpty(),
            provider = prefs[REMOTE_GATEWAY_PROVIDER].orEmpty(),
        )
    }

    override val gatewayConnectionMode: Flow<GatewayConnectionMode> = context.hermesDataStore.data.map { prefs ->
        prefs[GATEWAY_CONNECTION_MODE]
            ?.let { stored -> GatewayConnectionMode.entries.firstOrNull { it.name == stored } }
            ?: GatewayConnectionMode.Remote
    }

    /**
     * One authoritative scope for sticky new-draft controls. It follows the
     * saved route/profile values rather than a ViewModel-owned label, so a
     * connection edit cannot carry a prior Gateway's paid-model selection.
     */
    override val activeScope: Flow<ComposerControlsScope> = context.hermesDataStore.data.map(::composerScope)

    suspend fun setTheme(name: String) = context.hermesDataStore.edit { it[THEME_NAME] = name }

    suspend fun setMode(mode: HermesThemeMode) =
        context.hermesDataStore.edit { it[THEME_MODE] = mode.name }

    override suspend fun saveSidebarGrouping(grouping: SidebarGrouping) {
        context.hermesDataStore.edit { prefs -> prefs[SIDEBAR_GROUPING] = grouping.name }
    }

    override suspend fun saveProfileScope(scope: ProfileScope) {
        context.hermesDataStore.edit { prefs ->
            val active = normalizeProfileKey(scope.activeProfile)
            if (active == DEFAULT_PROFILE) prefs.remove(PROFILE_ACTIVE) else prefs[PROFILE_ACTIVE] = active
            if (scope.showAllProfiles) prefs[PROFILE_SHOW_ALL] = "true" else prefs.remove(PROFILE_SHOW_ALL)
        }
    }

    /**
     * Persists the non-secret, *saved* fields only. Callers cannot pass a
     * secret in — the type will not carry one — and the one non-secret field
     * that is screen state rather than saved state is dropped here.
     */
    override suspend fun saveHostProfile(profile: HostProfile) {
        context.hermesDataStore.edit { prefs ->
            prefs[HOST] = profile.host
            prefs[PORT] = profile.port
            prefs[USERNAME] = profile.username
            if (profile.remoteHermesProfile.isBlank()) prefs.remove(REMOTE_HERMES_PROFILE)
            else prefs[REMOTE_HERMES_PROFILE] = profile.remoteHermesProfile
            prefs[AUTH_METHOD] = profile.authMethod.name
            profile.acceptedFingerprint?.let { prefs[ACCEPTED_FINGERPRINT] = it }
                ?: prefs.remove(ACCEPTED_FINGERPRINT)
        }
    }

    override suspend fun saveRemoteGatewayProfile(profile: RemoteGatewayProfile) {
        context.hermesDataStore.edit { prefs ->
            if (profile.baseUrl.isBlank()) prefs.remove(REMOTE_GATEWAY_URL)
            else prefs[REMOTE_GATEWAY_URL] = profile.baseUrl
            if (profile.provider.isBlank()) prefs.remove(REMOTE_GATEWAY_PROVIDER)
            else prefs[REMOTE_GATEWAY_PROVIDER] = profile.provider
        }
    }

    override suspend fun saveGatewayConnectionMode(mode: GatewayConnectionMode) {
        context.hermesDataStore.edit { prefs -> prefs[GATEWAY_CONNECTION_MODE] = mode.name }
    }

    /**
     * The scope digest belongs in the preference key, not the value: neither a
     * catalog nor an endpoint/profile identifier is replicated into a saved
     * composer choice. Defaults from the Gateway deliberately remain absent.
     */
    override fun preference(scope: ComposerControlsScope): Flow<NewDraftComposerPreference?> =
        context.hermesDataStore.data.map { prefs ->
            ComposerControlsCodec.decode(prefs[composerControlsKey(scope)])
        }

    override suspend fun saveManual(scope: ComposerControlsScope, preference: NewDraftComposerPreference) {
        context.hermesDataStore.edit { prefs ->
            prefs[composerControlsKey(scope)] = ComposerControlsCodec.encode(preference.asManual())
        }
    }

    override suspend fun clearManual(scope: ComposerControlsScope) {
        context.hermesDataStore.edit { prefs -> prefs.remove(composerControlsKey(scope)) }
    }

    /** Per-install ownership namespace; excluded from backup with the DataStore. */
    override suspend fun ownershipId(): String {
        var resolved: String? = null
        context.hermesDataStore.edit { prefs ->
            resolved = prefs[GATEWAY_OWNERSHIP_ID]?.takeIf { it.isOwnershipId() } ?: ByteArray(16)
                .also(SecureRandom()::nextBytes)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                .also { prefs[GATEWAY_OWNERSHIP_ID] = it }
        }
        return requireNotNull(resolved)
    }

    private fun composerScope(prefs: Preferences): ComposerControlsScope {
        val mode = prefs[GATEWAY_CONNECTION_MODE]
            ?.let { raw -> GatewayConnectionMode.entries.firstOrNull { it.name == raw } }
            ?: GatewayConnectionMode.Remote
        return when (mode) {
            GatewayConnectionMode.Remote -> ComposerControlsScope(
                connectionIdentity = "remote:" + RemoteGatewayProfile(
                    baseUrl = prefs[REMOTE_GATEWAY_URL].orEmpty(),
                ).normalizedBaseUrl.orEmpty().ifBlank { "unconfigured" },
                profileIdentity = prefs[REMOTE_GATEWAY_PROVIDER].orEmpty().trim().lowercase().ifBlank { "default" },
            )
            GatewayConnectionMode.Ssh -> ComposerControlsScope(
                connectionIdentity = "ssh:" + prefs[USERNAME].orEmpty().trim() + "@" +
                    prefs[HOST].orEmpty().trim().lowercase() + ":" + (prefs[PORT] ?: FRESH.port),
                profileIdentity = prefs[REMOTE_HERMES_PROFILE].orEmpty().trim().ifBlank { "default" },
            )
        }
    }

    private companion object {
        /** What an install with nothing saved yet gets. */
        val FRESH = HostProfile()

        val THEME_NAME = stringPreferencesKey("appearance.theme")
        val THEME_MODE = stringPreferencesKey("appearance.mode")
        val SIDEBAR_GROUPING = stringPreferencesKey("sidebar.grouping")
        val PROFILE_ACTIVE = stringPreferencesKey("sidebar.profile.active")
        val PROFILE_SHOW_ALL = stringPreferencesKey("sidebar.profile.showAll")
        val HOST = stringPreferencesKey("host.single.host")
        val PORT = intPreferencesKey("host.single.port")
        val USERNAME = stringPreferencesKey("host.single.username")
        val REMOTE_HERMES_PROFILE = stringPreferencesKey("host.single.remoteHermesProfile")
        val AUTH_METHOD = stringPreferencesKey("host.single.authMethod")
        val ACCEPTED_FINGERPRINT = stringPreferencesKey("host.single.acceptedFingerprint")
        val GATEWAY_OWNERSHIP_ID = stringPreferencesKey("gateway.install.ownershipId")
        val GATEWAY_CONNECTION_MODE = stringPreferencesKey("gateway.single.connectionMode")
        val REMOTE_GATEWAY_URL = stringPreferencesKey("gateway.single.remote.url")
        val REMOTE_GATEWAY_PROVIDER = stringPreferencesKey("gateway.single.remote.provider")

        fun String.toThemeMode(): HermesThemeMode =
            HermesThemeMode.entries.firstOrNull { it.name == this } ?: HermesThemeMode.System

        /**
         * Persisted by name, so entries can be reordered or added without
         * rewriting an existing install's choice. A name this build does not
         * recognise falls back to Password rather than to the fresh default:
         * quietly moving someone onto a keyless method is the one wrong answer.
         */
        fun String.toAuthMethod(): AuthMethod =
            AuthMethod.entries.firstOrNull { it.name == this } ?: AuthMethod.Password

        fun String.isOwnershipId(): Boolean =
            length == 32 && all { it in "0123456789abcdef" }
    }
}

private fun composerControlsKey(scope: ComposerControlsScope) =
    stringPreferencesKey("composer.controls.v1.${scope.storageKey()}")

/** A closed, versioned value: malformed or future data fails closed to Gateway defaults. */
internal object ComposerControlsCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(preference: NewDraftComposerPreference): String = json.encodeToString(
        JsonObject(
            buildMap {
                put("version", JsonPrimitive("1"))
                preference.selection?.takeIf { it.isSpecified }?.let { selection ->
                    put("model", JsonPrimitive(selection.model))
                    selection.provider.takeIf(String::isNotBlank)?.let { put("provider", JsonPrimitive(it)) }
                }
                preference.reasoning?.let { put("reasoning", JsonPrimitive(it.wireValue)) }
                preference.fast?.let { put("fast", JsonPrimitive(it.wireValue)) }
            },
        ),
    )

    fun decode(raw: String?): NewDraftComposerPreference? = runCatching {
        if (raw.isNullOrBlank()) return null
        val root = json.parseToJsonElement(raw).jsonObject
        if (root["version"]?.jsonPrimitive?.content != "1") return null
        val model = root["model"]?.jsonPrimitive?.content?.trim().orEmpty()
        NewDraftComposerPreference(
            selection = model.takeIf(String::isNotEmpty)?.let {
                ComposerModelSelection(
                    model = it,
                    provider = root["provider"]?.jsonPrimitive?.content.orEmpty(),
                    source = ComposerModelSelection.Source.Manual,
                )
            },
            reasoning = ReasoningEffort.fromWire(root["reasoning"]?.jsonPrimitive?.content),
            fast = FastMode.fromWire(root["fast"]?.jsonPrimitive?.content),
        )
    }.getOrNull()
}
