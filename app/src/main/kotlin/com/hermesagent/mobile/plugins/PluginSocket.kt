package com.hermesagent.mobile.plugins

/**
 * The plugin WebSocket door — the live twin of [PluginRest].
 *
 * Direct Kotlin port of Desktop's `pluginSocket`
 * (`apps/desktop/src/api/plugins.ts:57-95` @
 * `564aef2946c436500a5e80ee117b66b789b3f99a`).
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
