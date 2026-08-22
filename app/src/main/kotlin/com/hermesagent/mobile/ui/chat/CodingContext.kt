package com.hermesagent.mobile.ui.chat

/**
 * A separate authority boundary for Desktop's local git/worktree strip.
 *
 * Desktop can query its authenticated local `desktop-git.ts` / `/api/git`
 * facade. Android's current Gateway protocol deliberately has no equivalent
 * authorized remote repository-status transport, so the production provider
 * is [Unavailable]. Keeping that fact as a type prevents a future composer
 * surface from inferring a branch, path, or worktree from a session cwd.
 */
fun interface CodingContextProvider {
    fun contextFor(durableSessionId: String?): CodingContext

    data object Unavailable : CodingContextProvider {
        override fun contextFor(durableSessionId: String?): CodingContext = CodingContext.Unavailable
    }
}

sealed interface CodingContext {
    /** No authorized Android transport can make a repository-status claim. */
    data object Unavailable : CodingContext

    /** Reserved for a future, explicitly authorized Gateway capability. */
    data class Available(
        val branch: String,
        val worktreeLabel: String? = null,
    ) : CodingContext
}
