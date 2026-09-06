package com.hermesagent.mobile.plugins

/**
 * The plugin WebSocket door — the live twin of [PluginRest].
 *
 * Direct Kotlin port of Desktop's `pluginSocket`
 * (`apps/desktop/src/api/plugins.ts:57-95` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 *
 * `path` is relative to `/api/plugins/<id>` ('/events'). Resolves to a no-op on
 * OAuth remotes (callers keep their polling fallback).
 */
interface PluginSocket {
    fun connect(
        pluginId: String,
        path: String,
        onMessage: (String) -> Unit,
    ): () -> Unit
}

/**
 * Production implementation of [PluginSocket].
 *
 * Checks path traversal and returns a no-op disposer on OAuth-gated /
 * unsupported socket connections.
 */
class GatewayPluginSocket(
    private val isOAuthLeg: () -> Boolean = { true },
) : PluginSocket {
    override fun connect(
        pluginId: String,
        path: String,
        onMessage: (String) -> Unit,
    ): () -> Unit {
        // Enforce path traversal bounds
        normalizePluginPathSuffix("PluginSocket", path)

        if (isOAuthLeg()) {
            // OAuth-gated remotes / unsupported: stay on polling fallback.
            return {}
        }

        // Live plugin socket protocol under token mode remains deferred (#73)
        return {}
    }
}
