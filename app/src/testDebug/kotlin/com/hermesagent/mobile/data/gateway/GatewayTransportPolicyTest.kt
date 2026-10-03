package com.hermesagent.mobile.data.gateway

import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.HermesApplication
import java.io.IOException
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
    fun publicHttpIsRejectedBeforeSendingCredentials() {
        var sent = false
        val client = applicationClient().newBuilder().addInterceptor { chain ->
            sent = true
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("".toResponseBody()).build()
        }.build()
        val failure = runCatching {
            client.newCall(Request.Builder().url("http://gateway.example/api/status")
                .header("Authorization", "Bearer fixture").build()).execute().close()
        }.exceptionOrNull()
        assertTrue("Public HTTP must fail before any transport", failure is IOException)
        assertFalse(sent)
    }

    @Test
    fun privateHttpCannotSendCredentialsThroughASystemProxy() {
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
    fun platformConfigAllowsTheGuardedTailnetTransport() {
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
