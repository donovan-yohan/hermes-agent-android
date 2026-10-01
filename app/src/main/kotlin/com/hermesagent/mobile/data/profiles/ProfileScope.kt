package com.hermesagent.mobile.data.profiles

import com.hermesagent.mobile.data.session.SessionSummary

/**
 * Which profile the sidebar is showing, and what new work targets.
 *
 * Desktop computes this from a persisted "show all" flag over the live
 * gateway's profile (`apps/desktop/src/store/profile.ts:437-448` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`). Android talks to one Gateway, so
 * the concrete half is app state too: UI-only authority, persisted as a
 * preference, never sent to the Gateway as anything but the `profile`
 * parameter below.
 *
 * @param activeProfile the concrete profile new chats belong to. [DEFAULT_PROFILE]
 *   means the host's literal default profile and is what an install that has
 *   never touched the rail carries.
 * @param showAllProfiles Desktop's opt-in unified browse view
 *   (`store/profile.ts:437`). It deliberately leaves [activeProfile] alone, so
 *   a new chat started while browsing still lands in the profile that was
 *   active. Leaving the view is a profile pick like any other, and the rail's
 *   only exit is the default profile — Desktop's own behaviour
 *   (`app/chat/sidebar/profile-switcher.tsx:205-210`).
 */
data class ProfileScope(
    val activeProfile: String = DEFAULT_PROFILE,
    val showAllProfiles: Boolean = false,
) {
    /** `default`, a named profile, or [ALL_PROFILES] (`store/profile.ts:446-448`). */
    val key: String get() = if (showAllProfiles) ALL_PROFILES else normalizeProfileKey(activeProfile)

    val isAll: Boolean get() = showAllProfiles

    /** True while the scope is the literal default profile and not the unified view. */
    val isDefault: Boolean get() = !showAllProfiles && normalizeProfileKey(activeProfile) == DEFAULT_PROFILE

    /**
     * The `profile` parameter for a session RPC that acts on this scope's
     * concrete profile — `session.create` (`tui_gateway/methods_session.py:42`)
     * and the scoped `session.list` (`:163`).
     *
     * Blank retains the legacy omitted/launch scope. Explicit `default` must
     * stay explicit on a named launch (tui_gateway/server.py:522-534 @
     * 333898b353c27e57dbd9446f631f7fb0a5aa5918).
     */
    val sessionProfileParam: String? get() = activeProfile.trim().takeIf(String::isNotEmpty)
}

/**
 * Which `session.list` calls one scope needs.
 *
 * A single-profile scope is one request. The unified view has no server-side
 * union on this transport — Desktop's is the dashboard REST route
 * `/api/profiles/sessions?profile=all` (`apps/desktop/src/hermes.ts:533-559`),
 * which the RPC lane has no twin for — so it fans out: the default profile,
 * then each named profile, each carrying its own `profile` parameter. Rows
 * accumulate in the backend-authoritative cache; nothing is dropped between
 * calls.
 *
 * Enumerate literal default once, independently of the Gateway's launch name.
 * Legacy callers may still explicitly request an omitted launch leg; the
 * repository preserves their fallback handling for old, unstamped RPC rows.
 */
fun sessionListProfiles(scope: ProfileScope, roster: List<HermesProfile>): List<String?> = when {
    !scope.showAllProfiles -> listOf(scope.sessionProfileParam)
    else -> buildList {
        add(DEFAULT_PROFILE)
        roster.asSequence()
            .filterNot(HermesProfile::isDefault)
            .map { normalizeProfileKey(it.name) }
            .filter { it != DEFAULT_PROFILE }
            .distinct()
            .forEach(::add)
    }
}

/**
 * The sessions visible in one sidebar profile scope, or the whole list for the
 * unified view. Port of `apps/desktop/src/app/chat/sidebar/profile-scope.ts:5-13`,
 * including its rule that a legacy row with no profile counts as `default`.
 */
fun filterSessionsByProfileScope(sessions: List<SessionSummary>, profileScope: String): List<SessionSummary> {
    if (profileScope == ALL_PROFILES) return sessions
    val scope = normalizeProfileKey(profileScope)
    return sessions.filter { normalizeProfileKey(it.remoteProfile) == scope }
}
