package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeyedMutexTest {

    @Test
    fun `an uncontended key runs the block immediately`() = runTest {
        val mutexes = KeyedMutex<String>()

        val result = mutexes.withLockWithin("a", 1_000L) { "ran" }

        assertEquals("ran", result)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `the wait bounds acquisition only and never the block`() = runTest {
        val mutexes = KeyedMutex<String>()

        val result = mutexes.withLockWithin("a", 1_000L) {
            delay(5_000L)
            "slow but finished"
        }

        assertEquals("slow but finished", result)
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    fun `a key held elsewhere times out and is still usable afterwards`() = runTest {
        val mutexes = KeyedMutex<String>()
        val release = CompletableDeferred<Unit>()
        val holder = async { mutexes.withLock("a") { release.await() } }
        runCurrent()

        val timedOut = async { mutexes.withLockWithin("a", 1_000L) { "never" } }
        advanceTimeBy(1_000L)
        runCurrent()

        assertNull(timedOut.await())
        assertEquals(1_000L, testScheduler.currentTime)

        // The timed-out attempt must not have leaked the lock: once the real
        // holder lets go, the key still serves the next caller.
        release.complete(Unit)
        holder.await()
        assertEquals("acquired", mutexes.withLockWithin("a", 1_000L) { "acquired" })
        assertEquals("after", mutexes.withLock("a") { "after" })
    }

    @Test
    fun `withLock and withLockWithin serialize on the same key`() = runTest {
        val mutexes = KeyedMutex<String>()
        val order = mutableListOf<String>()

        val first = async {
            mutexes.withLock("a") {
                order += "first-in"
                delay(200L)
                order += "first-out"
            }
        }
        runCurrent()
        val second = async {
            mutexes.withLockWithin("a", 1_000L) {
                order += "second-in"
                "done"
            }
        }
        runCurrent()

        assertEquals(listOf("first-in"), order)
        assertEquals("done", second.await())
        first.await()
        assertEquals(listOf("first-in", "first-out", "second-in"), order)

        // A different key is never made to wait behind this one.
        val other = mutexes.withLockWithin("b", 1_000L) { "other" }
        assertEquals("other", other)
    }
}
