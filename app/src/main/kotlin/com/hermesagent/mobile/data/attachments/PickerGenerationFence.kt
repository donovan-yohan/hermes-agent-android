package com.hermesagent.mobile.data.attachments

/**
 * The composer world a picker was launched in: the connection generation plus
 * the durable session that owned the composer. A picker outlives a reconnect,
 * an endpoint switch or a session change, and a result from an old world must
 * never attach into the new one.
 */
data class AttachmentPickScope(val generation: Long, val sessionId: String?)

/**
 * Fences one Activity result to the scope that launched it, and keeps that
 * scope available so the caller can re-check it after its own asynchronous
 * work (reading a picker's metadata is a suspension point).
 */
class PickerGenerationFence(private val currentScope: () -> AttachmentPickScope) {
    private var pending: AttachmentPickScope? = null

    fun begin() {
        pending = currentScope()
    }

    /**
     * The scope this result belongs to, once, or null when nothing was launched
     * or the world already moved before the result arrived.
     */
    fun accept(): AttachmentPickScope? {
        val launched = pending ?: return null
        pending = null
        return launched.takeIf { it == currentScope() }
    }

    /** True only while [scope] is still the world the composer is in. */
    fun holds(scope: AttachmentPickScope): Boolean = scope == currentScope()

    fun invalidate() {
        pending = null
    }
}
