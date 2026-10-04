package com.hermesagent.mobile.plugins

import com.hermesagent.mobile.data.gateway.EndpointDispatchFence
import com.hermesagent.mobile.data.gateway.OkHttpGatewayHttp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PluginMcpTest {
    @Test fun `named delta projects inventory and requires matching readback`() = runTest {
        var enabled = true
        val requests = mutableListOf<Request>()
        val transport = transport { request ->
            requests += request
            if (request.method == "PUT") {
                assertEquals("/api/mcp/servers/Mixed%20%23%3F%25/enabled", request.url.encodedPath)
                assertEquals("""{"enabled":false,"profile":"writer"}""",
                    okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                enabled = false
                """{"ok":true,"name":"Mixed #?%","enabled":false}"""
            } else """{"servers":[{"name":"Mixed #?%","enabled":$enabled,"source":"config","url":"URL_CANARY","command":"CMD_CANARY","args":["ARG_CANARY"],"env":{"x":"ENV_CANARY"}}]}"""
        }
        val host = GatewayPluginHost(backgroundScope, MutableStateFlow(null), http = { transport })
        val result = host.mcp.openScope(0, "writer").setEnabled("Mixed #?%", false)
        assertEquals(McpResult.Success(listOf(McpServer("Mixed #?%", false, McpSource.Config))), result)
        assertEquals(listOf("GET", "PUT", "GET"), requests.map { it.method })
        assertTrue(requests.filter { it.method == "GET" }.all { it.url.encodedPath == "/api/mcp/servers" })
        assertTrue(requests.all { it.url.queryParameter("profile") == "writer" })
        assertFalse(result.toString().contains("CANARY"))
    }

    @Test fun `readback must still name a config server not a replacement plugin`() = runTest {
        var reads = 0
        val transport = transport { request ->
            if (request.method == "PUT") """{"ok":true,"name":"server","enabled":false}"""
            else {
                reads++
                """{"servers":[{"name":"server","enabled":${reads == 1},"source":"${if (reads == 1) "config" else "plugin"}"}]}"""
            }
        }
        val scope = GatewayPluginMcp(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence()).openScope(0, "writer")
        assertEquals(McpResult.Failure(McpFailure.Unconfirmed, true), scope.setEnabled("server", false))
    }

    @Test fun `malformed receipts and mismatched readbacks remain uncertain without replay`() = runTest {
        val inventory = """{"servers":[{"name":"server","enabled":true,"source":"config"}]}"""
        for (receipt in listOf(
            "not-json-CANARY", "{}", """{"ok":"true","name":"server","enabled":false}""",
            """{"ok":true,"name":"other","enabled":false}""",
            """{"ok":true,"name":"server","enabled":"false"}""",
            """{"ok":true,"name":"server","enabled":true}""",
            """{"ok":true,"name":"server","enabled":false}""",
        )) {
            val methods = mutableListOf<String>()
            val transport = transport { request ->
                methods += request.method
                if (request.method == "PUT") receipt else inventory
            }
            val scope = GatewayPluginMcp(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence()).openScope(0, "writer")
            val result = scope.setEnabled("server", false)
            assertTrue(result is McpResult.Failure)
            assertTrue((result as McpResult.Failure).mutationMayHaveApplied)
            assertEquals(1, methods.count { it == "PUT" })
            assertFalse(result.toString().contains("CANARY"))
        }
    }

    @Test fun `unknown provenance is projected read only and raw metadata never escapes`() = runTest {
        val transport = transport {
            """{"servers":[{"name":"server","enabled":true,"source":"SOURCE_CANARY","url":"URL_CANARY","command":"CMD_CANARY","args":["ARGS_CANARY"],"env":{"key":"ENV_CANARY"}}]}"""
        }
        val scope = GatewayPluginMcp(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence()).openScope(0, "writer")
        val result = scope.listServers()
        assertEquals(McpResult.Success(listOf(McpServer("server", true, McpSource.Other))), result)
        assertFalse(result.toString().contains("CANARY"))
    }

    private fun transport(body: (Request) -> String) = OkHttpGatewayHttp(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body(chain.request()).toResponseBody()).build()
        }.build(), { "https://gateway.example" }, { "Authorization" to "synthetic" },
    )
}
