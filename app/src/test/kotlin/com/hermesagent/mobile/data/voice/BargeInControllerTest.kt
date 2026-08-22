package com.hermesagent.mobile.data.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class BargeInControllerTest {
    private var level = 0f
    private var speechFired = false
    private var now = 0L

    private fun controller(scope: CoroutineScope) = BargeInController(
        scope = scope,
        delayMillis = { millis -> now += millis; kotlinx.coroutines.delay(millis) },
        nowMillis = { now },
        readLevel = { level },
        onSpeech = { speechFired = true },
    )

    @Test
    fun `sustained speech fires exactly once`() = kotlinx.coroutines.test.runTest(EmptyCoroutineContext) {
        val scope = CoroutineScope(coroutineContext + Job())
        val c = controller(scope)
        c.arm()
        runCurrent()
        // Sustained loud input
        repeat(20) {
            level = 0.5f
            now += 50
            advanceTimeBy(50)
            runCurrent()
            if (speechFired) return@runTest
        }
        assertTrue(speechFired)
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `quiet audio never triggers`() = kotlinx.coroutines.test.runTest(EmptyCoroutineContext) {
        val scope = CoroutineScope(coroutineContext + Job())
        val c = controller(scope)
        c.arm()
        repeat(30) {
            level = 0.01f
            now += 50
            advanceTimeBy(50)
            runCurrent()
        }
        assertFalse(speechFired)
        c.disarm()
        scope.coroutineContext[Job]?.cancel()
    }
}
