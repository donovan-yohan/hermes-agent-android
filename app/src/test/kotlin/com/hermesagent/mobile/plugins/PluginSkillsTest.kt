package com.hermesagent.mobile.plugins

import com.hermesagent.mobile.data.gateway.EndpointDispatchFence
import com.hermesagent.mobile.data.gateway.OkHttpGatewayHttp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PluginSkillsTest {
    @Test fun `individual toggle uses delta and only fresh installed truth proves success`() = runTest {
        var enabled = true
        val requests = mutableListOf<Request>()
        val transport = transport { request ->
            requests += request
            if (request.method == "PUT") {
                val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                assertEquals("""{"name":"Mixed-Case","enabled":false,"profile":"writer"}""", body)
                enabled = false
                """{"ok":true,"name":"Mixed-Case","enabled":false}"""
            } else """[{"name":"Mixed-Case","enabled":$enabled}]"""
        }
        val api = GatewayPluginSkills(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence())
        val result = api.openScope(0, "writer").toggleInstalled("Mixed-Case", false)
        assertEquals(SkillsResult.Success(listOf(InstalledSkill("Mixed-Case", false))), result)
        assertEquals(listOf("GET", "PUT", "GET"), requests.map { it.method })
        assertEquals(listOf("/api/skills", "/api/skills/toggle", "/api/skills"), requests.map { it.url.encodedPath })
        assertTrue(requests.all { it.url.queryParameter("profile") == "writer" })
    }

    @Test fun `essential disable echoed receipt does not certify success`() = runTest {
        val transport = transport { request ->
            if (request.method == "PUT") """{"ok":true,"name":"hermes-agent","enabled":false}"""
            else """[{"name":"hermes-agent","enabled":true}]"""
        }
        val api = GatewayPluginSkills(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence())
        assertEquals(SkillsResult.Failure(SkillsFailure.Unconfirmed, true),
            api.openScope(0, "writer").toggleInstalled("hermes-agent", false))
    }

    private fun transport(body: (Request) -> String) = OkHttpGatewayHttp(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body(chain.request()).toResponseBody()).build()
        }.build(), { "https://gateway.example" }, { "Authorization" to "synthetic" },
    )

    @Test fun `installed list is named scoped and drops raw metadata`() = runTest {
        val requests = mutableListOf<Request>()
        val transport = OkHttpGatewayHttp(OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""[{"name":"Mixed-Case","enabled":true,"path":"/private/path","description":"Authorization: Bearer hidden"}]""".toResponseBody()).build()
        }.build(), { "https://gateway.example" }, { "Authorization" to "synthetic" })
        val api = GatewayPluginSkills(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence())
        val result = api.openScope(0, "writer").listInstalled()
        assertEquals(SkillsResult.Success(listOf(InstalledSkill("Mixed-Case", true))), result)
        assertEquals("/api/skills", requests.single().url.encodedPath)
        assertEquals("writer", requests.single().url.queryParameter("profile"))
        assertFalse(result.toString().contains("hidden"))
        assertFalse(result.toString().contains("private"))
    }
}
