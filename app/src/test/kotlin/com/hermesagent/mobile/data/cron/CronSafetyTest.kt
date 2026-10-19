package com.hermesagent.mobile.data.cron

import com.hermesagent.mobile.data.gateway.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class CronSafetyTest {
    @Test fun `queued actual transport cannot dispatch after endpoint ABA`() = runTest {
        val io = QueueDispatcher()
        val calls = AtomicInteger()
        val transport = actualTransport(io) { calls.incrementAndGet(); 200 to "[]" }
        val endpoint = MutableStateFlow(0L)
        val fence = EndpointDispatchFence()
        val scope = GatewayCronApi({ transport }, endpoint, fence).openScope(0, "writer")
        val task = async { scope.jobs() }
        runCurrent()
        fence.invalidate(); endpoint.value = 1
        fence.invalidate(); endpoint.value = 2
        io.drain(); runCurrent()
        assertEquals(CronResult.Failure(CronFailure.StaleScope), task.await())
        assertEquals(0, calls.get())
    }

    @Test fun `close reopen fences credentials suspended before actual mutation enqueue`() = runTest {
        val atMutation = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val auth = AtomicInteger()
        val methods = mutableListOf<String>()
        val transport = actualTransport(auth = {
            if (auth.incrementAndGet() == 2) { atMutation.complete(Unit); release.await() }
            "Authorization" to "synthetic"
        }) { request -> methods += request.method; 200 to """[{"id":"job","profile":"writer"}]""" }
        val api = GatewayCronApi({ transport }, MutableStateFlow(0L), EndpointDispatchFence())
        val original = api.openScope(0, "writer")
        val task = async { original.act(CronJob("job", "writer"), CronAction.Trigger) }
        atMutation.await()
        original.close()
        api.openScope(0, "writer").close()
        release.complete(Unit)
        assertEquals(CronResult.Failure(CronFailure.StaleScope), task.await())
        assertEquals(listOf("GET"), methods)
    }

    @Test fun `held mutation response cannot publish or start readback after close and replacement`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val wire = CronWire { request, _ ->
            if (request.method == "POST") { started.complete(Unit); release.await(); CronWire.ok("""{"id":"job","profile":"writer"}""") }
            else CronWire.ok("""[{"id":"job","profile":"writer"}]""")
        }
        val api = GatewayCronApi({ wire }, MutableStateFlow(0L), EndpointDispatchFence())
        val scope = api.openScope(0, "writer")
        val task = async { scope.act(CronJob("job", "writer"), CronAction.Pause) }
        started.await()
        scope.close()
        release.complete(Unit)
        assertEquals(CronResult.Failure(CronFailure.StaleScope, true), task.await())
        assertEquals(2, wire.requests.size)
        assertTrue(wire.buffers.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test fun `real socket mutation neither redirects nor replays retryable responses`() = runTest {
        for (code in listOf(302, 307, 408, 503)) {
            val calls = AtomicInteger()
            val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
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
                        calls.incrementAndGet()
                        val extra = when (code) { 302, 307 -> "Location: /escape\r\n"; 503 -> "Retry-After: 0\r\n"; else -> "" }
                        socket.getOutputStream().write("HTTP/1.1 $code synthetic\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                } catch (_: java.net.SocketException) { /* listener closed by the test */ }
            }.apply { start() }
            try {
                val transport = OkHttpGatewayHttp(OkHttpClient(), { "http://127.0.0.1:${server.localPort}" }, { "Authorization" to "synthetic" })
                val result = GatewayCronApi({ transport }, MutableStateFlow(0L), EndpointDispatchFence())
                    .openScope(0, "writer").create(CronDraft(prompt = "synthetic"))
                assertEquals(CronResult.Failure(CronFailure.Refused, true), result)
                assertEquals(1, calls.get())
            } finally {
                server.close()
                worker.join(5_000)
            }
        }
    }

    @Test fun `all and mismatched owners cannot mutate and unguarded HTTP never executes`() = runTest {
        val wire = CronWire { _, _ -> error("must not dispatch") }
        val api = GatewayCronApi({ wire }, MutableStateFlow(0L), EndpointDispatchFence())
        assertEquals(CronResult.Failure(CronFailure.InvalidScope), api.openScope(0, "writer").act(CronJob("id", "other"), CronAction.Delete))
        assertEquals(CronResult.Failure(CronFailure.InvalidScope), api.openScope(0, "all").create(CronDraft(prompt = "raw")))
        assertEquals(CronResult.Failure(CronFailure.InvalidScope), api.openScope(0, "current").jobs())
        val unguarded = object : GatewayHttp { override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult = error("unguarded") }
        assertEquals(CronResult.Failure(CronFailure.Unavailable), GatewayCronApi({ unguarded }, MutableStateFlow(0L), EndpointDispatchFence()).openScope(0, "writer").jobs())
    }

    @Test fun `post-admission cancellation reports unknown outcome instead of losing mutation receipt`() = runTest {
        val wire = CronWire { _, _ -> throw CancellationException("transport wait cancelled") }
        assertEquals(CronResult.Failure(CronFailure.Unconfirmed, true), GatewayCronApi({ wire }, MutableStateFlow(0L), EndpointDispatchFence()).openScope(0, "writer").create(CronDraft(prompt = "raw")))
    }

    @Test fun `bodyless delete cannot replay 503 retry-after zero`() = runTest {
        val deletes = AtomicInteger()
        val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val method = input.readLine().substringBefore(' ')
                    while (!input.readLine().isNullOrEmpty()) { }
                    val body = if (method == "GET") """[{"id":"job","profile":"writer"}]""" else ""
                    val code = if (method == "DELETE") { deletes.incrementAndGet(); 503 } else 200
                    socket.getOutputStream().write("HTTP/1.1 $code synthetic\r\nRetry-After: 0\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                }
            } catch (_: java.net.SocketException) { }
        }.apply { start() }
        try {
            val transport = OkHttpGatewayHttp(OkHttpClient(), { "http://127.0.0.1:${server.localPort}" }, { "Authorization" to "synthetic" })
            val result = GatewayCronApi({ transport }, MutableStateFlow(0L), EndpointDispatchFence()).openScope(0, "writer")
                .act(CronJob("job", "writer"), CronAction.Delete)
            // The interceptor refuses OkHttp's attempted follow-up before it
            // reaches the socket. Its lost response is conservatively uncertain.
            assertEquals(CronResult.Failure(CronFailure.Unreachable, true), result)
            assertEquals(1, deletes.get())
        } finally { server.close(); worker.join(5_000) }
    }

    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue += block }
        fun drain() { while (true) (queue.poll() ?: return).run() }
    }

    private fun actualTransport(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        auth: suspend () -> Pair<String, String>? = { "Authorization" to "synthetic" },
        reply: (Request) -> Pair<Int, String>,
    ) = OkHttpGatewayHttp(OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = reply(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
            .body(body.toResponseBody()).build()
    }.build(), { "https://gateway.example" }, auth, dispatcher)
}
