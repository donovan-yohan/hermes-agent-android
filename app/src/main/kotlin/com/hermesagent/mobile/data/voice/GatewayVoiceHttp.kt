package com.hermesagent.mobile.data.voice

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One bounded authenticated HTTP request to the active Gateway. */
class VoiceHttpRequest(
    val path: String,
    val method: String,
    val body: RequestBody?,
    val timeoutMillis: Long,
)

sealed interface VoiceHttpResult {
    /** Response body bytes; ownership transfers to the caller, who must wipe. */
    class Success(val statusCode: Int, val bodyBytes: ByteArray) : VoiceHttpResult
    class Rejected(val statusCode: Int, val safeMessage: String) : VoiceHttpResult
}

/**
 * Connection-owned authenticated HTTP transport for the audio routes. The
 * manager resolves credentials per active leg (SSH loopback session token or
 * remote native bearer); callers never see a token, URL origin, or ticket.
 */
interface GatewayVoiceHttp {
    suspend fun execute(request: VoiceHttpRequest): VoiceHttpResult
}

internal class OkHttpGatewayVoiceHttp(
    private val http: OkHttpClient,
    private val resolveEndpoint: () -> String?,
    private val resolveAuthorization: suspend () -> Pair<String, String>?,
) : GatewayVoiceHttp {
    override suspend fun execute(request: VoiceHttpRequest): VoiceHttpResult {
        val endpoint = resolveEndpoint()
            ?: return VoiceHttpResult.Rejected(0, "Reconnect to the Gateway before using voice.")
        val authorization = resolveAuthorization()
            ?: return VoiceHttpResult.Rejected(0, "Reconnect to the Gateway before using voice.")
        val scoped = http.newBuilder()
            .callTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        return try {
            val builder = Request.Builder()
                .url(endpoint.trimEnd('/') + "/" + request.path.trimStart('/'))
                .header(authorization.first, authorization.second)
            when (request.method.uppercase()) {
                "POST" -> builder.post(request.body ?: error("POST requires a body"))
                "GET" -> builder.get()
                else -> return VoiceHttpResult.Rejected(0, "Voice transport got an unsupported request.")
            }
            scoped.newCall(builder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    VoiceHttpResult.Success(response.code, response.body?.bytes() ?: ByteArray(0))
                } else {
                    VoiceHttpResult.Rejected(
                        response.code,
                        when (response.code) {
                            401, 403 -> "Hermes did not accept this connection for voice. Reconnect and try again."
                            in 500..599 -> "The voice provider on this Gateway failed. Try again."
                            else -> "Hermes refused that voice request."
                        },
                    )
                }
            }
        } catch (_: IOException) {
            VoiceHttpResult.Rejected(0, "The voice route could not be reached. Check the Gateway and try again.")
        }
    }
}
