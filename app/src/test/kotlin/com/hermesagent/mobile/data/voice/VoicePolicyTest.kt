package com.hermesagent.mobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePolicyTest {
    @Test
    fun `wire budget accounts for base64 expansion`() {
        val raw = 3_000_000
        val budget = VoicePolicy.wireBudgetBytes(raw)
        assertEquals(3_000_000 / 3 * 4 + 4096, budget)
        assertTrue(budget > raw)
    }

    @Test
    fun `audio timeout scales with size and stays capped`() {
        assertTrue(VoicePolicy.audioTimeoutMillis(0) == VoicePolicy.AUDIO_TIMEOUT_BASE_MILLIS)
        assertTrue(VoicePolicy.audioTimeoutMillis(Int.MAX_VALUE) == VoicePolicy.AUDIO_TIMEOUT_MAX_MILLIS)
    }

    @Test
    fun `stop phrase matches whole utterance only`() {
        val phrases = listOf("stop listening", "end voice")
        assertTrue(VoicePolicy.isStopUtterance("Stop listening", phrases))
        assertTrue(VoicePolicy.isStopUtterance("  stop   listening ", phrases))
        assertTrue(VoicePolicy.isStopUtterance("end voice", phrases))
        // Substantive speech containing stop words stays a message.
        assertFalse(VoicePolicy.isStopUtterance("stop the container", phrases))
        assertFalse(VoicePolicy.isStopUtterance("please stop listening to me now", phrases))
        assertFalse(VoicePolicy.isStopUtterance("", phrases))
    }
}
