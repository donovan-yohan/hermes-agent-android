package com.hermesagent.mobile.ui.system

import com.hermesagent.mobile.data.gateway.OkHttpGatewayHttp
import com.hermesagent.mobile.data.gateway.gatewayTransportClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import okio.buffer

class SystemLogsTransportTest {
    @Test fun `production authenticated transport sends the selected scope and bounds oversized tails`() = runBlocking {
        var oversized = false
        val requests = mutableListOf<okhttp3.Request>()
        val client = gatewayTransportClient().newBuilder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (oversized) "x".repeat(524289) else """{"lines":["INFO ready"]}""")
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val transport = OkHttpGatewayHttp(client, { "https://synthetic.invalid" }, { "Authorization" to "Bearer synthetic" })
        val owners = MutableStateFlow(SystemLogsOwner(transport, 1, "work"))
        val controller = SystemLogsController(this, owners) { owners.value }
        try {
            controller.resume()
            withTimeout(10000) { controller.state.first { it.lines.isNotEmpty() } }
            assertEquals("Bearer synthetic", requests[0].header("Authorization"))
            assertEquals("/api/logs", requests[0].url.encodedPath)
            assertEquals("200", requests[0].url.queryParameter("lines"))
            assertEquals("work", requests[0].url.queryParameter("profile"))
            assertNull(requests[0].url.queryParameter("level"))
            oversized = true
            controller.selectLevel("error")
            withTimeout(10000) { controller.state.first { it.error != null } }
            assertEquals("ERROR", requests.last().url.queryParameter("level"))
            assertTrue(controller.state.value.lines.isEmpty())
        } finally {
            controller.pause()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun `pause after headers cancels held chunked body and closes response promptly`() = runBlocking {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val release = java.util.concurrent.CountDownLatch(1)
        val reading = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val activeCall = java.util.concurrent.atomic.AtomicReference<okhttp3.Call>()
        val worker = Thread {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { /* consume request headers */ }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n4\r\n{\"li\r\n".toByteArray())
                        flush()
                    }
                    release.await(20, java.util.concurrent.TimeUnit.SECONDS)
                }
            } catch (_: java.net.SocketException) { /* fixture cleanup */ }
        }.apply { isDaemon = true; start() }
        val client = gatewayTransportClient().newBuilder()
            .eventListener(object : okhttp3.EventListener() {
                override fun callStart(call: okhttp3.Call) { activeCall.set(call) }
            })
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body!!
                val source = object : okio.ForwardingSource(body.source()) {
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        reading.complete(Unit)
                        return super.read(sink, byteCount)
                    }
                    override fun close() { try { super.close() } finally { closed.complete(Unit) } }
                }
                response.newBuilder().body(object : okhttp3.ResponseBody() {
                    private val buffered = source.buffer()
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = buffered
                }).build()
            }.build()
        val transport = OkHttpGatewayHttp(client, { "http://127.0.0.1:${server.localPort}" }, { "Authorization" to "synthetic" })
        val owners = MutableStateFlow(SystemLogsOwner(transport, 1, null))
        val controller = SystemLogsController(this, owners) { owners.value }
        try {
            controller.resume()
            withTimeout(10000) { reading.await() }
            controller.pause()
            withTimeout(3000) { closed.await() }
            assertTrue(activeCall.get().isCanceled())
            assertEquals(SystemLogsState(), controller.state.value)
        } finally {
            controller.pause()
            activeCall.get()?.cancel()
            release.countDown(); server.close(); worker.join(5000)
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun `pause during credential resolution prevents actual enqueue`() = runBlocking {
        val resolving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var exchanges = 0
        val client = gatewayTransportClient().newBuilder().addInterceptor { chain ->
            exchanges++
            error("request admitted after pause: ${chain.request().method}")
        }.build()
        val transport = OkHttpGatewayHttp(client, { "https://synthetic.invalid" }, {
            resolving.complete(Unit)
            release.await()
            "Authorization" to "synthetic"
        })
        val owners = MutableStateFlow(SystemLogsOwner(transport, 1, null))
        val controller = SystemLogsController(this, owners) { owners.value }
        controller.resume()
        withTimeout(10000) { resolving.await() }
        controller.pause()
        release.complete(Unit)
        assertEquals(0, exchanges)
        assertEquals(SystemLogsState(), controller.state.value)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
