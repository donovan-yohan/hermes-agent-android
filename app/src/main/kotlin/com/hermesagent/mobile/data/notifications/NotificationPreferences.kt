package com.hermesagent.mobile.data.notifications

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.hermesagent.mobile.data.prefs.hermesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Desktop's `NativeNotificationPrefs` (`store/native-notifications.ts:31-49` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`): a master switch plus one
 * boolean per kind, every one of them defaulting to on.
 *
 * Scope is per install, matching Desktop's "Per device" (`i18n/en.ts:432`) —
 * not per connection and not per profile. It is a view preference about this
 * phone, so it deliberately does not participate in the connection registry.
 */
data class NotificationSettings(
    val enabled: Boolean = true,
    val kinds: Map<NotificationKind, Boolean> = NotificationKind.entries.associateWith { true },
) {
    /** Desktop's gate, `native-notifications.ts:193`: master first, then the kind. */
    fun allows(kind: NotificationKind): Boolean = enabled && kinds[kind] != false
}

/**
 * Read by the notifier, written by the settings screen that does not exist
 * yet. Kept as its own interface so that screen is a pure UI change.
 */
interface NotificationPreferenceStore {
    val notificationSettings: Flow<NotificationSettings>

    /** True once the OS notification permission has been asked for, granted or not. */
    val notificationPermissionAsked: Flow<Boolean>

    suspend fun setNotificationsEnabled(enabled: Boolean)

    suspend fun setNotificationKind(kind: NotificationKind, on: Boolean)

    suspend fun markNotificationPermissionAsked()
}

private val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications.v1.enabled")
private val PERMISSION_ASKED = booleanPreferencesKey("notifications.v1.permissionAsked")

/**
 * Persisted by Desktop's kind *name*, never by ordinal, for the same reason the
 * SSH auth method is: the enum may be reordered, and an unreadable entry has to
 * fall back to the documented default rather than to whatever now sits at that
 * index.
 */
private fun kindKey(kind: NotificationKind) = booleanPreferencesKey("notifications.v1.kind.${kind.key}")

/**
 * Shares the one app DataStore; a second file would be a second thing to
 * migrate.
 *
 * Sharing it means `store.data` re-emits on every write to any unrelated
 * preference — a theme change, a draft flush, a connection edit — so both
 * projections end in `distinctUntilChanged`. Without it a notification
 * preference would look like it had changed every time something else did.
 */
class AndroidNotificationPreferences(context: Context) : NotificationPreferenceStore {
    private val store = context.applicationContext.hermesDataStore

    override val notificationSettings: Flow<NotificationSettings> = store.data.map { preferences ->
        NotificationSettings(
            enabled = preferences[NOTIFICATIONS_ENABLED] ?: true,
            kinds = NotificationKind.entries.associateWith { kind ->
                preferences[kindKey(kind)] ?: true
            },
        )
    }.distinctUntilChanged()

    override val notificationPermissionAsked: Flow<Boolean> =
        store.data.map { it[PERMISSION_ASKED] ?: false }.distinctUntilChanged()

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        store.edit { it[NOTIFICATIONS_ENABLED] = enabled }
    }

    override suspend fun setNotificationKind(kind: NotificationKind, on: Boolean) {
        store.edit { it[kindKey(kind)] = on }
    }

    override suspend fun markNotificationPermissionAsked() {
        store.edit { it[PERMISSION_ASKED] = true }
    }
}

/** Memory-only store for tests and for previews that own no DataStore. */
class TransientNotificationPreferences(
    initial: NotificationSettings = NotificationSettings(),
    permissionAsked: Boolean = false,
) : NotificationPreferenceStore {
    private val settings = MutableStateFlow(initial)
    private val asked = MutableStateFlow(permissionAsked)

    override val notificationSettings: Flow<NotificationSettings> = settings
    override val notificationPermissionAsked: Flow<Boolean> = asked

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        settings.value = settings.value.copy(enabled = enabled)
    }

    override suspend fun setNotificationKind(kind: NotificationKind, on: Boolean) {
        settings.value = settings.value.copy(kinds = settings.value.kinds + (kind to on))
    }

    override suspend fun markNotificationPermissionAsked() {
        asked.value = true
    }
}
