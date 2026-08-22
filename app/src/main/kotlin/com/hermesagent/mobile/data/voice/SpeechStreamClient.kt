package com.hermesagent.mobile.data.voice

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Streaming TTS over the Gateway's /api/audio/speak-stream WebSocket.
 * Auth: single-use 30s ticket minted from the native-auth bearer (remote leg)
 * or the loopback session token (SSH leg) — minted fresh per reply, never
 * reused. Frames follow the Desktop contract: {type:start}, binary PCM,
 * {type:end}; {type:fallback} before audio is the only legal auto-fallback.
 */
internal class OkHttpSpeechStream private constructor(
    private val socket: WebSocket,
    private val frames: Channel<SpeechStreamFrame>,
) : SpeechStream {
    private var sawAudio = false

    override suspend fun read(): SpeechStreamFrame? = frames.tryReceive().getOrNull()

    /** Sends incremental text; call before [close] with done=true at reply end. */
    fun sendText(delta: String) {
        socket.send("""{"text":${jsonString(delta)}}""")
    }

    fun sendDone() {
        socket.send("""{"done":true}""")
    }

    /** Barge-in: server-side stop. */
    fun sendStop() {
        socket.send("""{"stop":true}""")
    }

    override fun close() {
        frames.close()
        socket.cancel()
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    companion object {
        fun connect(
            http: OkHttpClient,
            url: String,
            ioDispatcher: CoroutineDispatcher,
            onClosed: () -> Unit = {},
        ): OkHttpSpeechStream = connect(http, url, ioDispatcher, onClosed)

        internal fun connect(
            http: OkHttpClient,
            url: String,
            ioDispatcher: CoroutineDispatcher,
            onClosed: () -> Unit,
            scoped: Boolean = true,
        ): OkHttpSpeechStream {
            val frames = Channel<SpeechStreamFrame>(capacity = Channel.UNLIMITED)
            val scopedClient = if (scoped) {
                http.newBuilder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build()
            } else {
                http
            }
            var sawAudio = false
            val socket = scopedClient.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        when {
                            text.contains("\"start\"") -> frames.trySend(SpeechStreamFrame.Start(16_000, 1))
                            text.contains("\"end\"") -> frames.trySend(SpeechStreamFrame.End)
                            text.contains("\"fallback\"") -> frames.trySend(SpeechStreamFrame.Fallback)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        sawAudio = true
                        frames.trySend(SpeechStreamFrame.Pcm(bytes.toByteArray()))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        frames.close()
                        onClosed()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        frames.close()
                        onClosed()
                    }
                },
            )
            return OkHttpSpeechStream(socket, frames)
        }
    }
}
