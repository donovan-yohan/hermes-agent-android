package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A mutex per key, created on first use and never removed for the lifetime of
 * the owner. Entries are tiny (one Mutex each) and bounded by the number of
 * distinct sessions this process touches, so cleanup would only add races.
 *
 * The point is per-session serialization — e.g. an attachment stage-then-submit
 * must not interleave with a queue drain for the same session — without making
 * unrelated sessions wait behind one global lock.
 */
internal class KeyedMutex<K : Any> {
    private val locks = HashMap<K, Mutex>()
    private val creationLock = Any()

    suspend fun <T> withLock(key: K, block: suspend () -> T): T {
        val mutex = synchronized(creationLock) { locks.getOrPut(key) { Mutex() } }
        return mutex.withLock { block() }
    }

    /** Wait at most [timeoutMillis] to acquire this key; never times out [block]. */
    suspend fun <T : Any> withLockWithin(
        key: K,
        timeoutMillis: Long,
        block: suspend () -> T,
    ): T? {
        require(timeoutMillis > 0)
        val mutex = synchronized(creationLock) { locks.getOrPut(key) { Mutex() } }
        val acquired = withTimeoutOrNull(timeoutMillis) {
            mutex.lock()
            true
        } == true
        if (!acquired) return null
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
