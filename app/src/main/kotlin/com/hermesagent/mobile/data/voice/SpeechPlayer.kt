package com.hermesagent.mobile.data.voice

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One playback sequence owner. Every stop/cancel increments the sequence so a
 * stale completion cannot revive output — the Desktop voice-playback
 * invariant. Temporary audio files live under cacheDir/voice, mode-private,
 * deleted in finally.
 */
class SpeechPlayer(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : AutoCloseable {
    private val sequencer = PlaybackSequencer()
    private val mutex = Mutex()
    private val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private val voiceDir = File(context.cacheDir, "voice").apply { mkdirs() }

    enum class Phase { Idle, Preparing, Speaking }

    @Volatile var phase: Phase = Phase.Idle
        private set

    /**
     * Plays complete audio bytes; returns when playback finishes or is
     * superseded. The temp file is deleted in finally; bytes are wiped by the
     * caller's ownership of [audio].
     */
    suspend fun play(audio: SpeechAudio): Boolean = withContext(ioDispatcher) {
        val slot = sequencer.next()
        phase = Phase.Preparing
        val temp = File.createTempFile("speech", ".bin", voiceDir)
        try {
            temp.writeBytes(audio.bytes)
            val finished = Mutex(true)
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
                        finished.unlock()
                    }
                }
            })
            withContext(ioDispatcher) {
                player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(temp)))
                player.prepare()
                player.play()
            }
            phase = Phase.Speaking
            // A superseded slot must not wait on its own listener.
            if (sequencer.isCurrent(slot)) {
                finished.lock()
            }
            sequencer.isCurrent(slot)
        } finally {
            runCatching { player.clearMediaItems() }
            runCatching { temp.delete() }
            if (sequencer.isCurrent(slot)) phase = Phase.Idle
        }
    }

    /** Immediate stop: invalidates the live slot and any pending fallback. */
    fun stop() {
        sequencer.invalidate()
        phase = Phase.Idle
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
    }

    override fun close() {
        stop()
        player.release()
    }
}
