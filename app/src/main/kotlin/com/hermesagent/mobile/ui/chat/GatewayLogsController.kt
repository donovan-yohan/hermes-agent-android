package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.gateway.GatewayLogsResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GatewayLogsState(val generation: Long, val phase: Phase, val result: GatewayLogsResult? = null) {
    enum class Phase { Consent, Loading, Finished }
}

/** Identity is a retention fence only. The backend log is NOT scoped to this session/profile. */
internal data class GatewayLogsIdentity(
    val endpoint: Long, val connection: Long, val session: String?, val profile: String?,
    val navigation: Long = 0, val profileRevision: Long = 0,
)

internal class GatewayLogsController(
    private val scope: CoroutineScope,
    private val identity: () -> GatewayLogsIdentity,
    private val connected: () -> Boolean,
    private val read: suspend (() -> Boolean) -> GatewayLogsResult,
) {
    private val lock = Any()
    private var generation = 0L
    private var captured: GatewayLogsIdentity? = null
    private val mutableState = MutableStateFlow<GatewayLogsState?>(null)
    val state = mutableState.asStateFlow()

    fun open() = synchronized(lock) {
        if (!connected() || mutableState.value?.phase == GatewayLogsState.Phase.Loading) return@synchronized
        captured = identity()
        mutableState.value = GatewayLogsState(++generation, GatewayLogsState.Phase.Consent)
    }

    fun dismiss() = synchronized(lock) {
        generation++
        captured = null
        mutableState.value = null
    }

    private fun owns(expected: Long) = generation == expected && captured == identity() && connected()

    fun invalidateIfChanged() = synchronized(lock) {
        if (captured != null && !owns(generation)) dismiss()
    }

    fun confirm(expected: Long) {
        synchronized(lock) {
            if (!owns(expected)) { invalidateIfChanged(); return }
            if (mutableState.value?.phase != GatewayLogsState.Phase.Consent) return
            mutableState.value = GatewayLogsState(expected, GatewayLogsState.Phase.Loading)
        }
        scope.launch {
            val current = { synchronized(lock) { owns(expected) } }
            if (!current()) return@launch
            val result = try { read(current) } catch (_: Exception) { GatewayLogsResult.Failed }
            synchronized(lock) {
                if (owns(expected)) mutableState.value = GatewayLogsState(expected, GatewayLogsState.Phase.Finished, result)
                else invalidateIfChanged()
            }
        }
    }
}
