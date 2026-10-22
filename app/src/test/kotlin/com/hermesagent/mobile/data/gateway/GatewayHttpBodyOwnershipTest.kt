package com.hermesagent.mobile.data.gateway

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

/** Real loopback socket: headers arrive, then the chunked body deliberately stalls. */
class GatewayHttpBodyOwnershipTest {
    @Test fun `cancellation racing body completion disposes success and refusal byte ownership`() = runBlocking {
        for (refusal in listOf(false, true)) {
            val bytes = "synthetic response bytes".encodeToByteArray()
            val consuming = CompletableDeferred<Unit>()
            val idle = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK").body(okhttp3.ResponseBody.create(null, "fixture")).build()
            }.build()
            client.dispatcher.idleCallback = Runnable { idle.complete(Unit) }
            val call = client.newCall(okhttp3.Request.Builder().url("https://synthetic.invalid/api/logs").build())
            val job = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                call.awaitAdmission({ it() }) { response -> response.use {
                    consuming.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS))
                    if (refusal) GatewayHttpResult.Rejected(401, "refused", bytes)
                    else GatewayHttpResult.Success(200, bytes)
                } }
            }
            try {
                withTimeout(10000) { consuming.await() }
                job.cancel()
                release.countDown()
                withTimeout(10000) { idle.await(); job.join() }
                assertTrue(call.isCanceled())
                assertTrue(bytes.all { it == 0.toByte() })
            } finally {
                release.countDown(); call.cancel(); job.cancel()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun `unknown length successful body is bounded while streaming and closed`() = runBlocking {
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        val source = object : ForwardingSource(Buffer().writeUtf8("x".repeat(65))) {
            override fun close() { closed.set(true); super.close() }
        }.buffer()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(object : ResponseBody() {
                    override fun contentType(): okhttp3.MediaType? = null
                    override fun contentLength() = -1L
                    override fun source() = source
                }).build()
        }.build()
        try {
            val transport = OkHttpGatewayHttp(client, { "https://synthetic.invalid" }, { "Authorization" to "synthetic" })
            val result = transport.executeAtDispatch(GatewayHttpRequest("/api/logs", "GET", null, 5000, maxResponseBytes = 64)) { it() }
            assertTrue(result is GatewayHttpResult.Rejected)
            assertEquals(200, (result as GatewayHttpResult.Rejected).statusCode)
            assertEquals(OVERSIZE_MESSAGE, result.safeMessage)
            assertTrue(closed.get())
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun `guarded body read remains cancellable after response headers`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val release = CountDownLatch(1)
        val reading = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val active = AtomicReference<Call>()
        val serverThread = Thread {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { /* consume headers */ }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n4\r\n{\"li\r\n".toByteArray())
                        flush()
                    }
                    release.await(20, TimeUnit.SECONDS)
                }
            } catch (_: java.net.SocketException) { /* test cleanup */ }
        }.apply { isDaemon = true; start() }
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { active.set(call) }
        }).addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body!!
            val source = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    reading.complete(Unit)
                    return super.read(sink, byteCount)
                }
                override fun close() { try { super.close() } finally { closed.complete(Unit) } }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        }.build()
        val transport = OkHttpGatewayHttp(client, { "http://127.0.0.1:${server.localPort}" }, { "Authorization" to "synthetic" })
        val job = async {
            transport.executeAtDispatch(GatewayHttpRequest("/api/logs", "GET", null, 15000, maxResponseBytes = 524288)) { it() }
        }
        try {
            withTimeout(10000) { reading.await() }
            job.cancel()
            withTimeout(3000) { closed.await(); job.join() }
            assertTrue(active.get().isCanceled())
            assertTrue(job.isCancelled)
        } finally {
            job.cancel(); active.get()?.cancel()
            release.countDown(); server.close(); serverThread.join(5000)
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
