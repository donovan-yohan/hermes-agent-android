package com.hermesagent.mobile.data.themes

import com.hermesagent.mobile.data.prefs.ComposerControlsScope
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    suspend fun ingest(payload: JsonObject, apply: Boolean, expectedGeneration: Long, expectedScope: ComposerControlsScope): String? = mutex.withLock {
        ingestLocked(payload, apply, expectedGeneration, expectedScope)
    }

    /** Disk failures are retryable; cancellation still propagates to the collector. */
    suspend fun ingestAndApply(
        payload: JsonObject,
        apply: Boolean,
        expectedGeneration: Long,
        expectedScope: ComposerControlsScope,
        persist: suspend (String) -> Boolean,
    ): Boolean = mutex.withLock {
        val target = ingestLocked(payload, apply, expectedGeneration, expectedScope) ?: return@withLock false
        if (generation() != expectedGeneration || currentScope() != expectedScope) return@withLock false
        val saved = try {
            persist(target)
        } catch (_: IOException) {
            false
        }
        if (saved) repository.acknowledgeBackendSkinApply(target, expectedGeneration)
        saved
    }

    private suspend fun ingestLocked(payload: JsonObject, apply: Boolean, expectedGeneration: Long, expectedScope: ComposerControlsScope): String? {
        if (generation() != expectedGeneration) return null
        val scope = currentScope()
        if (scope != expectedScope) return null
        if (!restoreLocked(scope, expectedGeneration)) return null
        val name = (payload["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (name == "default" || BuiltinThemes.ALL.any { it.name == name }) {
            val target = if (name == "default") "nous" else requireNotNull(name)
            return target.takeIf { repository.requestBackendSkinApply(target, apply, expectedGeneration) }
        }
        val theme = parseBackendSkin(payload) ?: return null
        val snapshot = (payloads.filterNot { it["name"] == payload["name"] } + payload)
            .takeLast(BackendSkinCache.MAX_SKINS)
        val cached = withContext(Dispatchers.IO) {
            cache.write(scope.connectionIdentity, scope.profileIdentity, snapshot)
        }
        if (!cached || generation() != expectedGeneration || currentScope() != scope) return null
        payloads = snapshot
        val requestApply = repository.ingestBackendSkin(theme, apply, expectedGeneration)
        return theme.name.takeIf { requestApply }
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
            parseBackendSkin(payload)?.let { repository.ingestBackendSkin(it, apply = false, expectedGeneration = expectedGeneration) }
        }
        owner = scope
        ownerGeneration = expectedGeneration
        payloads = restored
        return true
    }
}
