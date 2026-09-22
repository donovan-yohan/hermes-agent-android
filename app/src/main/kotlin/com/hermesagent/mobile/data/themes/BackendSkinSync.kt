package com.hermesagent.mobile.data.themes

import com.hermesagent.mobile.data.prefs.ComposerControlsScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Serializes disk restoration and live definitions; no disk operation runs on the UI thread. */
internal class BackendSkinSync(
    private val cache: BackendSkinCache,
    private val repository: GatewayThemeRepository,
    private val currentScope: suspend () -> ComposerControlsScope,
    private val generation: () -> Long,
) {
    private val mutex = Mutex()
    private var owner: ComposerControlsScope? = null
    private var ownerGeneration: Long? = null
    private var payloads = emptyList<JsonObject>()

    suspend fun restore() = mutex.withLock {
        restoreLocked(currentScope(), generation())
    }

    /** Null means register/cache only, or reject stale ownership; otherwise the caller may apply. */
    suspend fun ingest(payload: JsonObject, apply: Boolean, expectedGeneration: Long): String? = mutex.withLock {
        if (generation() != expectedGeneration) return@withLock null
        val scope = currentScope()
        if (!restoreLocked(scope, expectedGeneration)) return@withLock null
        val theme = parseBackendSkin(payload) ?: return@withLock null
        payloads = payloads.filterNot { it["name"] == payload["name"] } + payload
        val requestApply = repository.ingestBackendSkin(theme, apply)
        val snapshot = payloads
        withContext(Dispatchers.IO) {
            cache.write(scope.connectionIdentity, scope.profileIdentity, snapshot)
        }
        if (generation() != expectedGeneration || currentScope() != scope) return@withLock null
        theme.name.takeIf { requestApply }
    }

    private suspend fun restoreLocked(scope: ComposerControlsScope, expectedGeneration: Long): Boolean {
        if (generation() != expectedGeneration) return false
        if (owner == scope && ownerGeneration == expectedGeneration) return true
        val restored = withContext(Dispatchers.IO) {
            cache.read(scope.connectionIdentity, scope.profileIdentity)
        }
        if (generation() != expectedGeneration || currentScope() != scope) return false
        if (owner != null && owner != scope) repository.resetForEndpointSwitch()
        restored.forEach { payload ->
            parseBackendSkin(payload)?.let { repository.ingestBackendSkin(it, apply = false) }
        }
        owner = scope
        ownerGeneration = expectedGeneration
        payloads = restored
        return true
    }
}
