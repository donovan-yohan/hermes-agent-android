package com.hermesagent.mobile.data.prefs

import com.hermesagent.mobile.data.profiles.ProfileScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The active profile scope is UI-only authority that outlives the screen, so it
 * is a saved view preference exactly like the sidebar grouping — Desktop stores
 * its own half in `localStorage` (`apps/desktop/src/store/profile.ts:433-439` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`). It is never Gateway truth and
 * never project or session authority.
 */
interface ProfileScopeStore {
    val profileScope: Flow<ProfileScope>
    suspend fun saveProfileScope(scope: ProfileScope)
}

/** Per-ViewModel default for tests and previews that own no persistent store. */
internal class TransientProfileScopeStore(
    initial: ProfileScope = ProfileScope(),
) : ProfileScopeStore {
    private val state = MutableStateFlow(initial)
    override val profileScope: Flow<ProfileScope> = state

    override suspend fun saveProfileScope(scope: ProfileScope) {
        state.value = scope
    }
}
