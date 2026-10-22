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
