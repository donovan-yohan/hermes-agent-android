package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.gateway.DiagnosticsResult
import com.hermesagent.mobile.data.session.safeTurnErrorDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Ephemeral only: neither consent nor private support links survive process recreation. */
data class SendDiagnosticsState(
    val generation: Long,
    val phase: Phase,
    val result: DiagnosticsResult? = null,
) {
    enum class Phase { Consent, Uploading, Finished }
}

/** Per-upload consent also gates the actual wire hand-off, not just coroutine launch. */
internal class SendDiagnosticsController(
    private val scope: CoroutineScope,
    private val endpoint: () -> Long,
    private val connection: () -> Long,
    private val connected: () -> Boolean,
    private val upload: suspend (String, Long, (() -> Boolean) -> Boolean) -> DiagnosticsResult,
) {
    private val lock = Any()
    private var generation = 0L
    private data class Request(val endpoint: Long, val connection: Long, val context: String)
    private var request: Request? = null
    private val mutableState = MutableStateFlow<SendDiagnosticsState?>(null)
    val state = mutableState.asStateFlow()

    fun open(context: String) = synchronized(lock) {
        // A new tap cannot replace an in-flight upload and silently license a second one.
        if (mutableState.value?.phase == SendDiagnosticsState.Phase.Uploading) return@synchronized
        generation++
        request = Request(endpoint(), connection(), safeTurnErrorDetails(context))
        mutableState.value = SendDiagnosticsState(generation, SendDiagnosticsState.Phase.Consent)
    }

    fun dismiss() = synchronized(lock) {
        generation++
        request = null
        mutableState.value = null
    }

    fun invalidateIfChanged() = synchronized(lock) {
        val current = request ?: return@synchronized
        if (!owns(current)) dismiss()
    }

    private fun owns(current: Request) = current.endpoint == endpoint() &&
        current.connection == connection() && connected()

    fun confirm(expectedGeneration: Long) {
        val captured = synchronized(lock) {
            val current = request ?: return
            val state = mutableState.value ?: return
            if (state.generation != expectedGeneration || state.phase != SendDiagnosticsState.Phase.Consent) return
            if (!owns(current)) { dismiss(); return }
            mutableState.value = state.copy(phase = SendDiagnosticsState.Phase.Uploading)
            current
        }
        scope.launch {
            var dispatched = false
            val result = try {
                upload(captured.context, captured.endpoint) { send ->
                    synchronized(lock) {
                        if (generation != expectedGeneration || !owns(captured) || dispatched) false
                        else {
                            // Spend once even when the transport rejects the send. Never auto-retry.
                            dispatched = true
                            send()
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                DiagnosticsResult.Failed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DiagnosticsResult.Failed
            }
            synchronized(lock) {
                if (generation == expectedGeneration && owns(captured)) {
                    mutableState.value = SendDiagnosticsState(expectedGeneration, SendDiagnosticsState.Phase.Finished, result)
                }
            }
        }
    }
}
