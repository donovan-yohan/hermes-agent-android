package com.hermesagent.mobile.data.gateway

import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.HermesApplication
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = HermesApplication::class, sdk = [28])
class GatewayTransportPolicyTest {
    private fun applicationClient(): OkHttpClient {
        val app = ApplicationProvider.getApplicationContext<HermesApplication>()
        val getter = HermesApplication::class.java.getDeclaredMethod("getHttp")
        getter.isAccessible = true
        return getter.invoke(app) as OkHttpClient
    }

    @Test
    fun httpHostnamesReachTheApplicationTransport() {
        val requests = mutableListOf<Request>()
        val client = applicationClient().newBuilder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("".toResponseBody()).build()
        }.build()
        for (base in listOf("http://gateway.example:9120", "http://gateway.synthetic-tailnet.ts.net:9120")) {
            client.newCall(Request.Builder().url("$base/api/status")
                .header("Authorization", "Bearer fixture").build()).execute().use {
                assertEquals(200, it.code)
            }
        }
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.header("Authorization") == "Bearer fixture" })
    }

    @Test
    fun httpWebSocketHostnameReachesApplicationDnsWithHostnameIntact() {
        for (host in listOf("gateway.example", "gateway.synthetic-tailnet.ts.net")) {
            val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
            val finished = java.util.concurrent.CountDownLatch(1)
            val client = applicationClient().newBuilder().dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    seen += hostname
                    throw java.net.UnknownHostException("Synthetic DNS stop; no socket opened")
                }
            }).build()
            val socket = client.newWebSocket(
                Request.Builder().url("ws://$host:9120/api/ws?ticket=fixture").build(),
                object : okhttp3.WebSocketListener() {
                    override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: Response?) {
                        finished.countDown()
                    }
                },
            )
            assertTrue("WebSocket attempt finishes", finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            socket.cancel()
            assertEquals(listOf(host), seen.toList())
        }
    }

    @Test
    fun sharedClientDoesNotUseSystemProxy() {
        assertEquals(java.net.Proxy.NO_PROXY, applicationClient().proxy)
    }

    @Test
    fun privateAndLoopbackHttpReachTheTransport() {
        val client = applicationClient().newBuilder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("fixture".toResponseBody()).build()
        }.build()
        for (host in listOf("100.64.0.1", "10.0.0.1", "172.16.0.1", "192.168.0.1",
            "[fd7a:115c:a1e0::1]", "127.0.0.1", "localhost", "[::1]")) {
            client.newCall(Request.Builder().url("http://$host:9119/api/status").build()).execute().use {
                assertEquals(200, it.code)
            }
        }
    }

    @Test
    fun platformConfigAllowsHttpAndWebSocketTransport() {
        val xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.File("src/main/res/xml/network_security_config.xml"))
        val base = xml.getElementsByTagName("base-config").item(0) as org.w3c.dom.Element
        assertEquals("true", base.getAttribute("cleartextTrafficPermitted"))
    }

    @Test
    fun sharedClientDoesNotFollowCredentialRedirects() {
        assertFalse(applicationClient().followRedirects)
        assertFalse(applicationClient().followSslRedirects)
    }
}
