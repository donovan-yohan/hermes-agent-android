package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class GatewayHttpDispatchTest {
    @Test fun `guarded transport neither follows redirects nor replays retryable PUT`() = runTest {
        for (status in listOf(302, 307, 408, 503)) {
            val count = java.util.concurrent.atomic.AtomicInteger()
            val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
            val worker = Thread {
                try {
                    while (!server.isClosed) server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        var length = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { input.read() }
                        val code = if (count.incrementAndGet() == 1) status else 200
                        val extra = when (code) { 302, 307 -> "Location: /escape\r\n"; 503 -> "Retry-After: 0\r\n"; else -> "" }
                        socket.getOutputStream().write("HTTP/1.1 $code synthetic\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                } catch (_: java.net.SocketException) { /* test server closed */ }
            }.apply { start() }
            try {
                val transport = OkHttpGatewayHttp(OkHttpClient(),
                    { "http://127.0.0.1:${server.localPort}" }, { "x-HeRmEs-SeSsIoN-ToKeN" to "synthetic" })
                val result = transport.executeAtDispatch(GatewayHttpRequest("/api/skills/toggle", "PUT",
                    okhttp3.RequestBody.create(null, "{}"), 5000)) { send -> send() }
                assertTrue(result is GatewayHttpResult.Rejected)
                assertEquals(status, (result as GatewayHttpResult.Rejected).statusCode)
                assertEquals(1, count.get())
            } finally { server.close(); worker.join(5000) }
        }
    }

    @Test fun `revocation during credential wait prevents actual OkHttp admission`() = runTest {
        val credentials = CompletableDeferred<Unit>()
        var exchanges = 0
        val transport = OkHttpGatewayHttp(
            OkHttpClient.Builder().addInterceptor { chain ->
                exchanges++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("[]".toResponseBody()).build()
            }.build(),
            { "https://gateway.example" },
            { credentials.await(); "Authorization" to "synthetic" },
        )
        val fence = EndpointDispatchFence()
        val lease = fence.leaseAt(0) { true }!!
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            transport.executeAtDispatch(GatewayHttpRequest("/api/skills", "GET", null, 1000)) { send ->
                fence.dispatchIfCurrent(lease, { true }, send)
            }
        }
        fence.invalidate()
        credentials.complete(Unit)
        assertTrue(result.await() is GatewayHttpResult.Rejected)
        assertEquals(0, exchanges)
    }
}
