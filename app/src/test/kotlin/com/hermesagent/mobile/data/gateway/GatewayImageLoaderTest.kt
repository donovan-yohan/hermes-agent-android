package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayImageLoaderTest {
    private fun loader(body: ResponseBody, status: Int = 200) = OkHttpGatewayImageLoader(
        http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("synthetic").body(body).build()
        }.build(),
        resolveEndpoint = { "https://gateway.example" },
        resolveAuthorization = { "Authorization" to "synthetic" },
    )

    @Test
    fun `successful image bytes are preserved`() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        assertArrayEquals(bytes, loader(bytes.toResponseBody()).load("image.png").getOrThrow())
    }

    @Test
    fun `failed response is closed without reading its body`() = runTest {
        val source = Buffer().writeUtf8("error payload")
        var closed = false
        val trackedSource = object : ForwardingSource(source) {
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = trackedSource
        }
        assertTrue(loader(body, 500).load("image.png").isFailure)
        assertEquals(13L, source.size)
        assertTrue(closed)
    }

    @Test
    fun `unknown length image exceeding the transport ceiling is refused`() = runTest {
        val source = Buffer().write(ByteArray((DEFAULT_MAX_RESPONSE_BYTES + 2).toInt()) { 1 })
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = source
        }
        assertTrue(loader(body).load("image.png").isFailure)
        assertEquals(1L, source.size)
    }

    @Test
    fun `bounded reader accepts its exact ceiling and refuses one more byte`() {
        assertArrayEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3).toResponseBody().readBounded(3))
        assertEquals(null, byteArrayOf(1, 2, 3, 4).toResponseBody().readBounded(3))
    }
}
