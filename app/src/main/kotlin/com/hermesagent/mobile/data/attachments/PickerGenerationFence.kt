package com.hermesagent.mobile.data.attachments

/**
 * Fences one Activity result to the connection generation it was launched
 * under. A picker outlives a reconnect or endpoint switch; a result from the
 * old world must never attach into the new one.
 */
class PickerGenerationFence(private val currentGeneration: () -> Long) {
    private var pending: Long? = null

    fun begin() {
        pending = currentGeneration()
    }

    /** True only for the first result after [begin] and only in the same generation. */
    fun accept(): Boolean {
        val launched = pending ?: return false
        pending = null
        return launched == currentGeneration()
    }

    fun invalidate() {
        pending = null
    }
}
