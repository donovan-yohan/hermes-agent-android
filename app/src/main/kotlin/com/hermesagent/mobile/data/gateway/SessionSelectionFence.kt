package com.hermesagent.mobile.data.gateway

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A UI selection lifetime, not a runtime capability. Invalidation and wire dispatch share this lock. */
class SessionSelectionFence {
    private val lock = Any()
    private var epoch = 0L

    fun invalidate() = synchronized(lock) { epoch += 1 }

    fun capture(endpoint: Long, sessionId: String, profile: String?): SessionSelectionLease = synchronized(lock) {
        val acceptedEpoch = epoch
        SessionSelectionLease(endpoint, sessionId, profile) { send ->
            synchronized(lock) { epoch == acceptedEpoch && send() }
        }
    }
}

/** Carries the UI intent through nested resume/submit calls without a replacement-target lookup. */
class SessionSelectionLease internal constructor(
    private val endpoint: Long,
    private val sessionId: String,
    private val profile: String?,
    private val gate: ((() -> Boolean) -> Boolean),
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SessionSelectionLease>

    internal fun requireCurrent(endpoint: Long, sessionId: String, profile: String?) {
        if (this.endpoint != endpoint || this.sessionId != sessionId || this.profile != profile || !gate { true }) {
            throw GatewayRpcException("The selected session changed. Your message was not sent.")
        }
    }

    internal fun dispatch(send: () -> Boolean): Boolean = gate(send)
}
