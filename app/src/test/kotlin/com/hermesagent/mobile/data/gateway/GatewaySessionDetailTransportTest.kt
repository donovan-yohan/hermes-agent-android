package com.hermesagent.mobile.data.gateway

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class GatewaySessionDetailTransportTest {
    @Test fun `detail encodes one path segment and never follows credential bearing redirects`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<List<String>>())
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = thread {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val reader = socket.getInputStream().bufferedReader()
                    val lines = mutableListOf<String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        lines += line
                    }
                    requests += lines
                    val response = if (requests.size == 1) {
                        "HTTP/1.1 302 Found\r\nLocation: /escaped\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    } else {
                        "HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    }
                    socket.getOutputStream().write(response.toByteArray())
                }
            } catch (_: SocketException) { /* test closes listener */ }
        }
        try {
            val transport = OkHttpGatewayHttp(OkHttpClient(),
                { "http://127.0.0.1:${server.localPort}/root" }, { "X-Hermes-Token" to "synthetic-detail-token" })
            val result = GatewayRestClient { transport }.sessionDetail("cron a?#%", "work")
            assertEquals(302, (result as GatewayRestResult.Failed).statusCode)
            assertEquals(1, requests.size)
            assertEquals("GET /root/api/sessions/cron%20a%3F%23%25?profile=work HTTP/1.1", requests.single().first())
            assertTrue(requests.single().any { it == "X-Hermes-Token: synthetic-detail-token" })
        } finally {
            server.close()
            worker.join(3_000)
        }
    }

    @Test fun `detail captures transport before queued IO and refuses mismatched owner or id`() = runTest {
        val calls = mutableListOf<String>()
        fun transport(name: String) = object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
                calls += name
                return GatewayHttpResult.Success(200, """{"id":"id","profile":"work","ended_at":0}""".toByteArray())
            }
        }
        var selected = transport("first")
        val client = GatewayRestClient(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) { selected }
        val result = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { client.sessionDetail("id", "work") }
        selected = transport("replacement")
        testScheduler.runCurrent()
        assertTrue(result.await() is GatewayRestResult.Success)
        assertEquals(listOf("first"), calls)
        for (body in listOf("{}", "[]", """{"id":"other","profile":"work"}""", """{"id":"id","profile":"other"}""")) {
            val bad = GatewayRestClient { object : GatewayHttp {
                override suspend fun execute(request: GatewayHttpRequest) = GatewayHttpResult.Success(200, body.toByteArray())
            } }.sessionDetail("id", "work")
            assertTrue(bad is GatewayRestResult.Failed)
        }
    }
}
