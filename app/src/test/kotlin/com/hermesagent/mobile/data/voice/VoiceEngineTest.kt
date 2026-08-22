package com.hermesagent.mobile.data.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEngineTest {
    @Test
    fun `mic ownership is exclusive`() = runBlocking {
        val engine = VoiceEngine(Dispatchers.Unconfined)
        assertTrue(engine.acquireMic())
        val second = async { engine.acquireMic() }
        assertFalse(second.await())
        engine.releaseMic()
        assertTrue(engine.acquireMic())
    }

    @Test
    fun `sequencer invalidates stale slots`() {
        val sequencer = PlaybackSequencer()
        val first = sequencer.next()
        val second = sequencer.next()
        assertTrue(sequencer.isCurrent(second))
        assertFalse(sequencer.isCurrent(first))
        sequencer.invalidate()
        assertFalse(sequencer.isCurrent(second))
    }

    @Test
    fun `captured audio close wipes bytes`() {
        val audio = CapturedAudio("audio/wav", ByteArray(16) { 1 })
        audio.close()
        assertTrue(audio.bytes.all { it == 0.toByte() })
    }

    @Test
    fun `speech audio close wipes bytes`() {
        val audio = SpeechAudio("audio/mpeg", ByteArray(8) { 2 })
        audio.close()
        assertTrue(audio.bytes.all { it == 0.toByte() })
    }
}
