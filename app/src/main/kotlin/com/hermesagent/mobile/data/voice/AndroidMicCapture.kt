package com.hermesagent.mobile.data.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded 16 kHz mono PCM capture on AudioRecord. Raw samples stay in memory,
 * are capped by [VoicePolicy.MAX_RAW_AUDIO_BYTES], and are zeroed on close.
 * This is the wake/barge path; dictation STT uses the encoded recorder
 * adapter so the provider receives a normal audio container.
 */
class AndroidMicCapture(
    private val ioDispatcher: CoroutineDispatcher,
    private val sampleRate: Int = 16_000,
) {
    private val recording = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var buffer = ByteArray(0)

    val isRecording: Boolean get() = recording.get()

    @SuppressLint("MissingPermission")
    suspend fun start(): Boolean = withContext(ioDispatcher) {
        if (recording.get()) return@withContext true
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return@withContext false
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            )
        } catch (_: SecurityException) {
            return@withContext false
        } catch (_: UnsupportedOperationException) {
            return@withContext false
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return@withContext false
        }
        record = audioRecord
        buffer = ByteArray(0)
        recording.set(true)
        audioRecord.startRecording()
        true
    }

    /**
     * Drains one chunk into the retained buffer, respecting the raw cap.
     * Returns the newest normalized level (0..1) for metering.
     */
    suspend fun pump(): Float = withContext(ioDispatcher) {
        val audioRecord = record ?: return@withContext 0f
        if (!recording.get()) return@withContext 0f
        val chunk = ByteArray(4_096)
        val read = runCatching { audioRecord.read(chunk, 0, chunk.size) }.getOrDefault(0)
        if (read <= 0) return@withContext 0f
        if (buffer.size + read > VoicePolicy.MAX_RAW_AUDIO_BYTES) {
            stop()
            return@withContext 0f
        }
        buffer += chunk.copyOf(read)
        var sum = 0L
        for (i in 0 until read step 2) {
            val sample = ((chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)).toShort().toInt()
            sum += sample * sample
        }
        val rms = kotlin.math.sqrt(sum.toDouble() / (read / 2))
        (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    suspend fun stop(): ByteArray = withContext(ioDispatcher) {
        recording.set(false)
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        val captured = buffer
        buffer = ByteArray(0)
        captured
    }

    suspend fun close() {
        stop().fill(0)
    }
}
