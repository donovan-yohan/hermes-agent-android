package com.hermesagent.mobile.data.profiles

/**
 * Hermes profiles: independent Hermes environments (config, skills, SOUL.md)
 * that the Gateway can serve from one backend.
 *
 * Ported from Desktop at `3ca096de5f8183cb2e0ec23673f294d5978656a3`:
 * `apps/desktop/src/store/profile.ts:22-33,423` and the row shape
 * `tui_gateway/methods_profiles.py:204-249`.
 *
 * Desktop's own switch mechanism is a per-profile Electron backend pool
 * (`store/profile.ts:303`) and does not port. The portable equivalent is the
 * `profile` parameter the session RPCs already accept, which is what
 * [ProfileScope] resolves.
 */

/** Desktop's canonical unified-scope key (`store/profile.ts:423`). */
const val ALL_PROFILES = "__all__"

/** The root `~/.hermes` profile, and this app's "the Gateway's own" sentinel. */
const val DEFAULT_PROFILE = "default"

/**
 * Canonical key for a profile: trimmed, empty becomes `default`
 * (`store/profile.ts:22-26`). Used everywhere a session's owning profile is
 * compared against a scope; never for display.
 */
fun normalizeProfileKey(name: String?): String = (name ?: "").trim().ifEmpty { DEFAULT_PROFILE }

/**
 * One roster row.
 *
 * Field names follow `profiles.list` (`tui_gateway/methods_profiles.py:205-249`).
 * [hasEnv] is deliberately kept even though that handler never sends it: only
 * the REST twin does (`hermes_cli/web_server.py:14498`), and parsing a field the
 * pinned RPC omits keeps its shape covered rather than inventing one later.
 */
data class HermesProfile(
    val name: String,
    val path: String = "",
    val isDefault: Boolean = false,
    val model: String? = null,
    val provider: String? = null,
    val description: String = "",
    val displayName: String = "",
    val skillCount: Int = 0,
    /** `.env` presence. Absent from `profiles.list` at the pin; see the class note. */
    val hasEnv: Boolean = false,
    /**
     * The one `ui_meta` key this client reads. The pinned backend fixes no
     * `ui_meta` vocabulary — it stores whatever `profile.yaml` holds
     * (`methods_profiles.py:221-236`) — so anything else is retained by the
     * server and ignored here rather than guessed at.
     */
    val uiMetaColor: String? = null,
    val hasAvatar: Boolean = false,
) {
    val key: String get() = normalizeProfileKey(name)

    /**
     * Presentation only: `display_name` when set, else the canonical name
     * (`store/profile.ts:31-33`). Never used for comparison or routing.
     */
    val label: String get() = displayName.trim().ifEmpty { name }
}
