package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Connection-owned, bounded loader for attached-image bytes. Images attached
 * from this phone are re-staged on the gateway's disk (`image.attach_bytes`),
 * so transcript thumbnails are fetched back over the gateway's authenticated
 * filesystem route — the same contract Desktop's `gatewayMediaDataUrl` uses
 * (`apps/desktop/src/lib/media.ts` @ `f82f2dba`). Callers never see a token,
 * origin, or ticket: the implementation resolves credentials per active leg,
 * exactly like the voice transport.
 */
interface GatewayImageLoader {
    suspend fun load(path: String): Result<ByteArray>
}
internal class OkHttpGatewayImageLoader(
    private val http: OkHttpClient,
    private val resolveEndpoint: () -> String?,
    private val resolveAuthorization: suspend () -> Pair<String, String>?,
    private val timeoutMillis: Long = 15_000,
) : GatewayImageLoader {
    override suspend fun load(path: String): Result<ByteArray> {
        val endpoint = resolveEndpoint() ?: return Result.failure(ImageUnavailable())
        val authorization = resolveAuthorization() ?: return Result.failure(ImageUnavailable())
        val base = (endpoint.trimEnd('/') + "/").toHttpUrlOrNull()
            ?: return Result.failure(ImageUnavailable())
        val url = base.newBuilder().addPathSegments("api/fs/download").apply {
            addQueryParameter("path", path)
        }.build()
        // Callers run on the compose main thread; the synchronous okhttp
        // execute() must never touch it (NetworkOnMainThreadException).
        return withContext(Dispatchers.IO) {
            val scoped = http.newBuilder()
                .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .build()
            try {
                val request = Request.Builder()
                    .url(url)
                    .header(authorization.first, authorization.second)
                    .get()
                    .build()
                scoped.newCall(request).execute().use { response ->
                    val body = response.body?.bytes()
                    if (response.isSuccessful && body != null && body.isNotEmpty()) {
                        Result.success(body)
                    } else {
                        Result.failure(ImageUnavailable())
                    }
                }
            } catch (_: IOException) {
                Result.failure(ImageUnavailable())
            }
        }
    }

    /** A fetch that can only produce a fallback chip — never a user-facing error. */
    class ImageUnavailable : Exception()
}
