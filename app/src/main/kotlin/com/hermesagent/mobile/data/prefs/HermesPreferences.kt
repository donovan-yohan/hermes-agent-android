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
import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.ConnectionRegistry
import com.hermesagent.mobile.data.connections.ConnectionRegistryCodec
import com.hermesagent.mobile.data.connections.ConnectionRegistryStore
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.connections.newConnectionId
import com.hermesagent.mobile.data.profiles.DEFAULT_PROFILE
import com.hermesagent.mobile.data.profiles.ProfileScope
import com.hermesagent.mobile.data.profiles.normalizeProfileKey
import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.HostProfileStore
import com.hermesagent.mobile.data.gateway.ActiveGatewayRoute
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.gateway.LocalGatewayProfile
import com.hermesagent.mobile.data.gateway.GatewayInstallStore
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfileStore
import com.hermesagent.mobile.plugins.PluginDecisionStore
import com.hermesagent.mobile.plugins.PluginDecisionsCodec
import com.hermesagent.mobile.plugins.PluginKeyValueStore
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom

internal val Context.hermesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "hermes",
    produceMigrations = { listOf(DropImportedKeyName, AdoptConnectionRegistry) },
)

/** The key an earlier build wrote the imported key's display name under. */
private val LEGACY_IMPORTED_KEY_NAME = stringPreferencesKey("host.single.importedKeyName")

/**
 * The single-connection keys this store used before it kept a registry.
 *
 * They are named here, once, because exactly one thing is allowed to read them
 * — [AdoptConnectionRegistry], which moves them into row one and removes them.
 * Nothing else may resurrect a second copy of a connection.
 */
private val LEGACY_HOST = stringPreferencesKey("host.single.host")
private val LEGACY_PORT = intPreferencesKey("host.single.port")
private val LEGACY_USERNAME = stringPreferencesKey("host.single.username")
private val LEGACY_REMOTE_HERMES_PROFILE = stringPreferencesKey("host.single.remoteHermesProfile")
private val LEGACY_AUTH_METHOD = stringPreferencesKey("host.single.authMethod")
private val LEGACY_ACCEPTED_FINGERPRINT = stringPreferencesKey("host.single.acceptedFingerprint")
private val LEGACY_CONNECTION_MODE = stringPreferencesKey("gateway.single.connectionMode")
private val LEGACY_REMOTE_GATEWAY_URL = stringPreferencesKey("gateway.single.remote.url")
private val LEGACY_REMOTE_GATEWAY_PROVIDER = stringPreferencesKey("gateway.single.remote.provider")

internal val CONNECTIONS = stringPreferencesKey("connections.v1.saved")
internal val ACTIVE_CONNECTION_ID = stringPreferencesKey("connections.v1.activeId")
internal val PLUGIN_DECISIONS = stringPreferencesKey("hermes.plugin.decisions.v1")

/** What an unnamed connection is called until someone renames it. */
internal const val DEFAULT_CONNECTION_LABEL = "Gateway"

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
 * Turns the one connection an earlier build saved into row one of the registry,
 * active, with nothing dropped.
 *
 * Every field the single-connection keys held — host, port, username, remote
 * Hermes profile, auth method, accepted fingerprint, Gateway URL, sign-in
 * provider, and which route was selected — becomes that row's, so an install
 * that upgrades stays connected to the same place with the same trust. The
 * legacy keys are then removed in the same edit: two copies of a connection is
 * two answers to "where am I connected", and the second one is always the
 * stale one.
 *
 * A device with nothing saved gets the same shape rather than an empty
 * registry, so the rest of the app never has to ask whether a connection
 * exists — only whether it has been filled in. The per-install Gateway
 * ownership id deliberately does *not* move: it namespaces this app's remote
 * processes on a host, not an endpoint, and one install has exactly one.
 *
 * Written as a [DataMigration] for the same reason as [DropImportedKeyName]:
 * it is the one hook that runs before the first read, exactly once.
 */
internal object AdoptConnectionRegistry : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = !currentData.contains(CONNECTIONS)

    override suspend fun migrate(currentData: Preferences): Preferences {
        val mode = currentData[LEGACY_CONNECTION_MODE]
            ?.let { stored -> GatewayConnectionMode.entries.firstOrNull { it.name == stored } }
            ?: GatewayConnectionMode.Remote
        val row = SavedConnection(
            id = newConnectionId(),
            label = DEFAULT_CONNECTION_LABEL,
            kind = ConnectionKind.of(mode),
            remote = RemoteGatewayProfile(
                baseUrl = currentData[LEGACY_REMOTE_GATEWAY_URL].orEmpty(),
                provider = currentData[LEGACY_REMOTE_GATEWAY_PROVIDER].orEmpty(),
            ),
            host = HostProfile(
                host = currentData[LEGACY_HOST] ?: HostProfile().host,
                port = currentData[LEGACY_PORT] ?: HostProfile().port,
                username = currentData[LEGACY_USERNAME] ?: HostProfile().username,
                remoteHermesProfile = currentData[LEGACY_REMOTE_HERMES_PROFILE]
                    ?: HostProfile().remoteHermesProfile,
                authMethod = currentData[LEGACY_AUTH_METHOD]
                    ?.let { stored -> AuthMethod.entries.firstOrNull { it.name == stored } ?: AuthMethod.Password }
                    ?: HostProfile().authMethod,
                acceptedFingerprint = currentData[LEGACY_ACCEPTED_FINGERPRINT],
            ),
        )
        return currentData.toMutablePreferences().apply {
            this[CONNECTIONS] = ConnectionRegistryCodec.encode(listOf(row))
            this[ACTIVE_CONNECTION_ID] = row.id
            remove(LEGACY_HOST)
            remove(LEGACY_PORT)
            remove(LEGACY_USERNAME)
            remove(LEGACY_REMOTE_HERMES_PROFILE)
            remove(LEGACY_AUTH_METHOD)
            remove(LEGACY_ACCEPTED_FINGERPRINT)
            remove(LEGACY_CONNECTION_MODE)
            remove(LEGACY_REMOTE_GATEWAY_URL)
            remove(LEGACY_REMOTE_GATEWAY_PROVIDER)
        }
    }

    override suspend fun cleanUp() = Unit
}

/**
 * Everything this connection/appearance preference store puts on disk.
 *
 * The list is short by design, and every entry is non-secret:
 * - the chosen theme and light/dark mode, and whether an empty chat draws the
 *   intro splash;
 * - the session sidebar's grouping mode and its active Hermes-profile scope;
 * - the saved connections, each one a random local id, a label, a route, the
 *   Remote Gateway's non-secret URL/provider, and the SSH host, port, username,
 *   remote Hermes profile, auth *method* and accepted host-key fingerprint;
 * - which saved connection is active;
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
 * declare its scope in its own key"). Connections are now a list, so the scope
 * is `connections.v1.*`: one versioned document of saved rows plus the id of
 * the active one. The single-connection `host.single.*` / `gateway.single.*`
 * keys they replace were **migrated, not overloaded** — see
 * [AdoptConnectionRegistry] — and nothing reads them any more.
 *
 * A registry row holds only the same non-secret fields. A Remote row's sign-in
 * is not one of them: it lives in that row's own Keystore-encrypted slot,
 * named after the row id, and removing the row erases it.
 */
class HermesPreferences(private val context: Context) :
    HostProfileStore,
    GatewayInstallStore,
    RemoteGatewayProfileStore,
    ConnectionRegistryStore,
    SidebarViewStore,
    ProfileScopeStore,
    ComposerControlsStore,
    PluginDecisionStore,
    PluginKeyValueStore {

    val appearance: Flow<AppearanceSelection> = context.hermesDataStore.data.map { prefs ->
        AppearanceSelection(
            themeName = prefs[THEME_NAME] ?: BuiltinThemes.DEFAULT_NAME,
            mode = prefs[THEME_MODE]?.toThemeMode() ?: HermesThemeMode.System,
        )
    }

    /**
     * Appearance's `Intro Splash` (`apps/desktop/src/i18n/en.ts:588` @
     * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
     *
     * Desktop's `$introSplash` defaults to on (`store/intro-splash.ts:8`), so an
     * absent key here is on too. Stored as a string, like every other flag in
     * this store, so the whole document keeps one encoding.
     */
    val introSplash: Flow<Boolean> = context.hermesDataStore.data.map { prefs ->
        prefs[INTRO_SPLASH] != "false"
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
    /**
     * The saved set and which row this device is on.
     *
     * Every single-connection reader below is a projection of this registry's
     * *active* row, so there is exactly one answer to "where is this app
     * connected" and no second copy that can drift out of date.
     */
    override val connectionRegistry: Flow<ConnectionRegistry> =
        context.hermesDataStore.data.map(::registryOf)

    override val connectionRegistryWritable: Flow<Boolean> =
        context.hermesDataStore.data.map { prefs -> ConnectionRegistryCodec.isWritable(prefs[CONNECTIONS]) }

    override val hostProfile: Flow<HostProfile> = connectionRegistry.map { it.active?.host ?: FRESH }

    override val remoteGatewayProfile: Flow<RemoteGatewayProfile> =
        connectionRegistry.map { it.active?.remoteProfile ?: RemoteGatewayProfile() }

    override val gatewayConnectionMode: Flow<GatewayConnectionMode> =
        connectionRegistry.map { it.active?.kind?.mode ?: GatewayConnectionMode.Remote }

    override val localGatewayProfile: Flow<LocalGatewayProfile> =
        connectionRegistry.map { it.active?.localProfile ?: LocalGatewayProfile() }

    /**
     * The three route projections above as one value, taken from one read of
     * one row so they cannot be observed disagreeing. `distinctUntilChanged`
     * because a commit that touched some other preference is not a route
     * change, and the surfaces downstream re-render on every emission.
     *
     * The identity is the resolved row, not the marker: a marker naming a row
     * that is gone resolves to the first row, and the first row is what every
     * other projection here is of.
     */
    override val activeGatewayRoute: Flow<ActiveGatewayRoute> = connectionRegistry
        .map { registry ->
            val active = registry.active
            ActiveGatewayRoute(
                connectionId = active?.id,
                mode = active?.kind?.mode ?: GatewayConnectionMode.Remote,
                remote = active?.remoteProfile ?: RemoteGatewayProfile(),
            )
        }
        .distinctUntilChanged()

    /**
     * One authoritative scope for sticky new-draft controls. It follows the
     * saved route/profile values rather than a ViewModel-owned label, so a
     * connection edit cannot carry a prior Gateway's paid-model selection.
     */
    override val activeScope: Flow<ComposerControlsScope> = context.hermesDataStore.data.map(::composerScope)

    suspend fun setTheme(name: String) = context.hermesDataStore.edit { it[THEME_NAME] = name }

    suspend fun setMode(mode: HermesThemeMode) =
        context.hermesDataStore.edit { it[THEME_MODE] = mode.name }

    suspend fun setIntroSplash(on: Boolean) =
        context.hermesDataStore.edit { it[INTRO_SPLASH] = on.toString() }

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
        editActiveConnection { active -> active.copy(host = profile) }
    }

    /**
     * The caller cannot choose which Keystore slot it writes to: the active
     * row's own id is stamped back in, so a profile that travelled through the
     * UI can never point a sign-in at another connection's slot.
     *
     * The same stamp decides whether there is a write at all. The route form
     * has no discrete save — it persists on every keystroke — so one of its
     * writes can still be in flight when a switch moves the marker, and the
     * row a character was typed against is the only thing that tells that
     * apart from an edit of the row now active. A profile stamped for some
     * other row is dropped here, inside the transaction that reads the marker,
     * which is the only place the two can be compared without a gap. A blank
     * stamp is a caller with no row in mind — the pre-registry migration path
     * and every test fixture — and still writes wherever the marker points.
     */
    override suspend fun saveRemoteGatewayProfile(profile: RemoteGatewayProfile) {
        editActiveConnection { active ->
            if (profile.secretSlotId.isNotBlank() && profile.secretSlotId != active.id) {
                active
            } else {
                active.copy(remote = RemoteGatewayProfile(baseUrl = profile.baseUrl, provider = profile.provider))
            }
        }
    }

    /**
     * Stamped like the profile write, and for the same reason — see
     * [RemoteGatewayProfileStore.saveGatewayConnectionMode]. The comparison
     * happens inside the transaction that resolves the marker, which is the
     * only place the caller's row and the active row can be compared without a
     * gap between reading one and writing the other.
     */
    override suspend fun saveGatewayConnectionMode(
        mode: GatewayConnectionMode,
        expectedConnectionId: String?,
    ): Boolean {
        var written = false
        editActiveConnection { active ->
            if (expectedConnectionId != null && expectedConnectionId != active.id) {
                active
            } else {
                written = true
                active.copy(kind = ConnectionKind.of(mode))
            }
        }
        return written
    }

    /** Inserts a new row or replaces one by id. Which row is active is a separate decision. */
    override suspend fun saveConnection(connection: SavedConnection) {
        editRegistry { rows, activeId ->
            val index = rows.indexOfFirst { it.id == connection.id }
            val next = if (index >= 0) {
                rows.toMutableList().also { it[index] = connection }
            } else {
                rows + connection
            }
            next to (activeId ?: connection.id)
        }
    }

    /**
     * Removes a row, moving the active marker to the first survivor when the
     * removed row was the active one. Removing the last row is refused: this
     * app is always configured for exactly one connection, and an empty
     * registry would only be re-seeded on the next write.
     */
    override suspend fun removeConnection(id: String) {
        editRegistry { rows, activeId ->
            val next = rows.filterNot { it.id == id }
            if (next.isEmpty()) {
                rows to activeId
            } else {
                next to (activeId?.takeIf { it != id } ?: next.first().id)
            }
        }
    }

    override suspend fun setActiveConnection(id: String) {
        editRegistry { rows, activeId ->
            rows to (if (rows.any { it.id == id }) id else activeId)
        }
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

    override fun visibleModels(scope: ComposerControlsScope): Flow<Set<String>?> =
        context.hermesDataStore.data.map { prefs ->
            ModelVisibilityCodec.decode(prefs[modelVisibilityKeyFor(scope)])
        }

    override suspend fun saveVisibleModels(scope: ComposerControlsScope, keys: Set<String>) {
        context.hermesDataStore.edit { prefs ->
            prefs[modelVisibilityKeyFor(scope)] = ModelVisibilityCodec.encode(keys)
        }
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

    /**
     * The composer's scope follows the *active* row's endpoint and profile, so
     * a saved model pick or a parked queue can never cross a gateway boundary.
     * The identity strings are deliberately unchanged from the
     * single-connection build: an install that upgrades keeps the queue and the
     * preferences it already had.
     */
    private fun composerScope(prefs: Preferences): ComposerControlsScope {
        val active = registryOf(prefs).active ?: return ComposerControlsScope(
            connectionIdentity = "remote:unconfigured",
            profileIdentity = "default",
        )
        return when (active.kind) {
            ConnectionKind.Remote -> ComposerControlsScope(
                connectionIdentity = "remote:" +
                    active.remote.normalizedBaseUrl.orEmpty().ifBlank { "unconfigured" },
                profileIdentity = active.remote.provider.trim().lowercase().ifBlank { "default" },
            )

            ConnectionKind.Ssh -> ComposerControlsScope(
                connectionIdentity = "ssh:" + active.host.username.trim() + "@" +
                    active.host.host.trim().lowercase() + ":" + active.host.port,
                profileIdentity = active.host.remoteHermesProfile.trim().ifBlank { "default" },
            )

            // A Hermes on this device serves one profile per running process,
            // and the person picks it when they start it in Termux. The address
            // is therefore the whole scope.
            ConnectionKind.Local -> ComposerControlsScope(
                connectionIdentity = "local:" +
                    active.local.normalizedBaseUrl.orEmpty().ifBlank { "unconfigured" },
                profileIdentity = "default",
            )
        }
    }

    private fun registryOf(prefs: Preferences): ConnectionRegistry = ConnectionRegistry(
        connections = ConnectionRegistryCodec.decode(prefs[CONNECTIONS]),
        activeId = prefs[ACTIVE_CONNECTION_ID],
    )

    /**
     * One atomic decode → mutate → encode, so the rows and the active marker
     * never disagree.
     *
     * A stored document this build cannot read is left exactly as it is. It
     * belongs to a newer build; reading it as "no connections" is this build's
     * ignorance, and writing a fresh document over it would make a downgrade
     * permanent. Refusing the write is the only answer that keeps the newer
     * build's data recoverable.
     */
    private suspend fun editRegistry(
        transform: (rows: List<SavedConnection>, activeId: String?) -> Pair<List<SavedConnection>, String?>,
    ) {
        context.hermesDataStore.edit { prefs ->
            if (!ConnectionRegistryCodec.isWritable(prefs[CONNECTIONS])) return@edit
            val registry = registryOf(prefs)
            val (rows, activeId) = transform(registry.connections, registry.activeId)
            prefs[CONNECTIONS] = ConnectionRegistryCodec.encode(rows)
            val resolved = activeId?.takeIf { id -> rows.any { it.id == id } } ?: rows.firstOrNull()?.id
            if (resolved == null) prefs.remove(ACTIVE_CONNECTION_ID) else prefs[ACTIVE_CONNECTION_ID] = resolved
        }
    }

    /**
     * Edits whichever row this device is on, seeding row one when a store has
     * somehow reached a write with no rows at all. The connection form always
     * has a row to write to, and it is always the one the app is using.
     */
    private suspend fun editActiveConnection(transform: (SavedConnection) -> SavedConnection) {
        editRegistry { rows, activeId ->
            if (rows.isEmpty()) {
                val seeded = transform(
                    SavedConnection(newConnectionId(), DEFAULT_CONNECTION_LABEL, ConnectionKind.Remote),
                )
                listOf(seeded) to seeded.id
            } else {
                val index = rows.indexOfFirst { it.id == activeId }.takeIf { it >= 0 } ?: 0
                val updated = rows.toMutableList().also { it[index] = transform(it[index]).copy(id = it[index].id) }
                updated to updated[index].id
            }
        }
    }

    override val pluginDecisions: Flow<Map<String, Boolean>> = context.hermesDataStore.data.map { prefs ->
        PluginDecisionsCodec.decode(prefs[PLUGIN_DECISIONS])
    }

    override suspend fun savePluginDecision(id: String, enabled: Boolean) {
        context.hermesDataStore.edit { prefs ->
            val current = PluginDecisionsCodec.decode(prefs[PLUGIN_DECISIONS]).toMutableMap()
            current[id] = enabled
            prefs[PLUGIN_DECISIONS] = PluginDecisionsCodec.encode(current)
        }
    }

    override suspend fun read(scopedKey: String): String? {
        return context.hermesDataStore.data.first()[stringPreferencesKey(scopedKey)]
    }

    override suspend fun write(scopedKey: String, value: String?) {
        context.hermesDataStore.edit { prefs ->
            val key = stringPreferencesKey(scopedKey)
            if (value == null) {
                prefs.remove(key)
            } else {
                prefs[key] = value
            }
        }
    }

    private companion object {
        /** What an install with nothing saved yet gets. */
        val FRESH = HostProfile()

        val THEME_NAME = stringPreferencesKey("appearance.theme")
        val THEME_MODE = stringPreferencesKey("appearance.mode")
        val INTRO_SPLASH = stringPreferencesKey("appearance.introSplash")
        val SIDEBAR_GROUPING = stringPreferencesKey("sidebar.grouping")
        val PROFILE_ACTIVE = stringPreferencesKey("sidebar.profile.active")
        val PROFILE_SHOW_ALL = stringPreferencesKey("sidebar.profile.showAll")
        val GATEWAY_OWNERSHIP_ID = stringPreferencesKey("gateway.install.ownershipId")

        fun String.toThemeMode(): HermesThemeMode =
            HermesThemeMode.entries.firstOrNull { it.name == this } ?: HermesThemeMode.System

        fun String.isOwnershipId(): Boolean =
            length == 32 && all { it in "0123456789abcdef" }
    }
}

private fun composerControlsKey(scope: ComposerControlsScope) =
    stringPreferencesKey("composer.controls.v1.${scope.storageKey()}")

private fun modelVisibilityKeyFor(scope: ComposerControlsScope) =
    stringPreferencesKey("composer.visibleModels.v1.${scope.storageKey()}")

/**
 * The stored shortlist: a versioned document holding `provider::model` keys and
 * the hide-all sentinels that go with them.
 *
 * Closed the same way [ComposerControlsCodec] is — a malformed or future
 * document decodes to null, which is "never customised", so a downgrade shows
 * the curated default rather than an arbitrary subset. An empty array is
 * meaningful and is **not** null: it is "every provider hidden", which is a
 * choice the sentinels exist to preserve.
 */
internal object ModelVisibilityCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(keys: Set<String>): String = json.encodeToString(
        JsonObject(
            mapOf(
                "version" to JsonPrimitive("1"),
                "keys" to JsonArray(keys.sorted().map { JsonPrimitive(it) }),
            ),
        ),
    )

    fun decode(raw: String?): Set<String>? = runCatching {
        if (raw.isNullOrBlank()) return null
        val root = json.parseToJsonElement(raw).jsonObject
        if (root["version"]?.jsonPrimitive?.content != "1") return null
        val keys = root["keys"] as? JsonArray ?: return null
        keys.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }.toSet()
    }.getOrNull()
}

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
