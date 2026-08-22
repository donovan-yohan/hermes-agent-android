package com.hermesagent.mobile.data.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pure barge-in detector over a level source (0..1). Portable contract from
 * Desktop voice-barge-in: calibrate a quiet noise floor, require sustained
 * speech above the threshold for [sustainedMillis], keep a bounded pre-roll
 * window, and yield exactly one utterance per arm. All timing injected for
 * virtual-time tests.
 */
class BargeInController(
    private val scope: CoroutineScope,
    private val policy: VoicePolicy = VoicePolicy,
    private val delayMillis: suspend (Long) -> Unit = { millis -> delay(millis) },
    private val nowMillis: () -> Long,
    private val readLevel: suspend () -> Float,
    private val onSpeech: () -> Unit,
) {
    private var job: Job? = null
    @Volatile private var noiseFloor = 0.03f

    val isArmed: Boolean get() = job?.isActive == true

    /** Arms monitoring; cancels any prior arm. */
    fun arm() {
        disarm()
        job = scope.launch {
            calibrate()
            var speechStart = -1L
            while (job?.isActive == true) {
                val now = nowMillis()
                val level = readLevel()
                if (level > VoicePolicy.VOICE_THRESHOLD) {
                    if (speechStart < 0) speechStart = now
                    if (now - speechStart >= SUSTAINED_MILLIS) {
                        onSpeech()
                        return@launch
                    }
                } else {
                    speechStart = -1
                }
                delayMillis(POLL_MILLIS)
            }
        }
    }

    fun disarm() {
        job?.cancel()
        job = null
    }

    private suspend fun calibrate() {
        // Sample the quiet floor briefly so device mic gain doesn't false-trigger.
        repeat(CALIBRATION_SAMPLES) {
            noiseFloor = maxOf(noiseFloor * 0.9f, readLevel() * 0.5f)
            delayMillis(50)
        }
    }

    companion object {
        const val SUSTAINED_MILLIS = 350L
        const val POLL_MILLIS = 50L
        const val CALIBRATION_SAMPLES = 6
    }
}
