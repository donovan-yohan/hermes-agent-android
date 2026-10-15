package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** sessions.py:583-597,843-874,923-931 + hermes_state_runtime.py:250-320 @ 379cf1220fe289b5ecfe8bcf61cd4d5126e140cd. */
class GatewayMutationContractTest {
    @Test
    fun `every sidebar edit reads owner snapshot and sends identified revision fenced write`() = runTest {
        for (field in listOf("title", "pinned", "archived", "unread")) {
            val http = MutationHttp()
            val client = GatewayRestClient { http }
            val result = when (field) {
                "title" -> client.updateSession("public-session", title = "Renamed", profile = "work")
                "pinned" -> client.updateSession("public-session", pinned = true, profile = "work")
                "archived" -> client.updateSession("public-session", archived = true, profile = "work")
                else -> client.updateSession("public-session", unread = true, profile = "work")
            }
            assertTrue("$field must accept target receipt", result is GatewayRestResult.Success)
            assertEquals(listOf("GET", "PATCH"), http.requests.map { it.method })
            assertEquals("api/sessions/public-session/mutation-snapshot", http.requests.first().path)
            assertEquals(mapOf("profile" to "work"), http.requests.first().query)
            val body = http.bodies.last()
            assertEquals(JsonPrimitive(7), body["expected_revision"])
            assertEquals(JsonPrimitive(3), body["expected_generation"])
            assertTrue(body.getValue("request_id").jsonPrimitive.content.isNotBlank())
            assertEquals(JsonPrimitive("work"), body["profile"])
        }
    }

    @Test
    fun `delete uses revision and exact backend execution generation and retains deleted ids`() = runTest {
        val http = MutationHttp()
        val result = GatewayRestClient { http }.deleteSession("public-session", "work")
        assertTrue(result is GatewayRestResult.Success)
        assertEquals(listOf("GET", "DELETE"), http.requests.map { it.method })
        val query = http.requests.last().query
        assertEquals("7", query["expected_revision"])
        assertEquals("3", query["expected_generation"])
        assertTrue(query.getValue("request_id").isNotBlank())
        assertEquals("work", query["profile"])
    }

    @Test
    fun `ambiguous write retry retains identical request and revision without rereading snapshot`() = runTest {
        val http = MutationHttp().apply { refuseWrite = 200 }
        val client = GatewayRestClient { http }
        assertTrue(client.updateSession("public-session", pinned = true) is GatewayRestResult.Failed)
        val first = http.bodies.last()
        http.refuseWrite = null
        assertTrue(client.updateSession("public-session", pinned = true) is GatewayRestResult.Success)
        assertEquals(1, http.requests.count { it.method == "GET" })
        assertEquals(first, http.bodies.last())
    }

    @Test
    fun `snapshot refusal never falls through to unguarded writer`() = runTest {
        for (status in listOf(404, 409, 403, 503)) {
            val http = MutationHttp().apply { refuseSnapshot = status }
            assertTrue(GatewayRestClient { http }.updateSession("public-session", pinned = true) is GatewayRestResult.Failed)
            assertEquals(listOf("GET"), http.requests.map { it.method })
        }
    }

    @Test
    fun `confirmed conflict sends once and explicit retry obtains new revision`() = runTest {
        val http = MutationHttp().apply { refuseWrite = 409 }
        val client = GatewayRestClient { http }
        assertTrue(client.updateSession("public-session", pinned = true) is GatewayRestResult.Failed)
        assertEquals(listOf("GET", "PATCH"), http.requests.map { it.method })
        val firstId = http.bodies.last()["request_id"]
        http.refuseWrite = null
        assertTrue(client.updateSession("public-session", pinned = true) is GatewayRestResult.Success)
        assertEquals(2, http.requests.count { it.method == "GET" })
        assertNotEquals(firstId, http.bodies.last()["request_id"])
    }

    @Test
    fun `another explicit intent is allowed and acknowledged write retires old ambiguous identity`() = runTest {
        val http = MutationHttp().apply { refuseWrite = 200 }
        val client = GatewayRestClient { http }
        assertTrue(client.updateSession("public-session", title = "A") is GatewayRestResult.Failed)
        val oldId = http.bodies.last()["request_id"]
        http.refuseWrite = null
        assertTrue(client.updateSession("public-session", pinned = true) is GatewayRestResult.Success)
        assertTrue(client.updateSession("public-session", title = "A") is GatewayRestResult.Success)
        assertNotEquals(oldId, http.bodies.last()["request_id"])
        assertEquals(3, http.requests.count { it.method == "GET" })
    }

    @Test
    fun `explicit ambiguous retry survives reconnect only within the same owner endpoint scope`() = runTest {
        val first = MutationHttp("public-endpoint-a").apply { refuseWrite = 200 }
        var active: GatewayHttp = first
        val client = GatewayRestClient { active }
        assertTrue(client.updateSession("public-session", pinned = true, profile = "work") is GatewayRestResult.Failed)
        val original = first.bodies.last()
        val replacement = MutationHttp("public-endpoint-a")
        active = replacement
        assertTrue(client.updateSession("public-session", pinned = true, profile = "work") is GatewayRestResult.Success)
        assertEquals("same endpoint retry must not remint the operation", listOf("PATCH"), replacement.requests.map { it.method })
        assertEquals(original, replacement.bodies.last())

        replacement.refuseWrite = 200
        assertTrue(client.updateSession("public-session", title = "Public title", profile = "work") is GatewayRestResult.Failed)
        val ambiguous = replacement.bodies.last()
        val otherProfile = MutationHttp("public-endpoint-a")
        active = otherProfile
        assertTrue(client.updateSession("public-session", title = "Public title", profile = "other") is GatewayRestResult.Success)
        assertEquals(listOf("GET", "PATCH"), otherProfile.requests.map { it.method })
        assertNotEquals(ambiguous["request_id"], otherProfile.bodies.last()["request_id"])
        val otherEndpoint = MutationHttp("public-endpoint-b")
        active = otherEndpoint
        assertTrue(client.updateSession("public-session", title = "Public title", profile = "work") is GatewayRestResult.Success)
        assertEquals(listOf("GET", "PATCH"), otherEndpoint.requests.map { it.method })
        assertNotEquals(ambiguous["request_id"], otherEndpoint.bodies.last()["request_id"])
    }

    private class MutationHttp(override val mutationScope: String? = null) : GatewayHttp {
        val requests = mutableListOf<GatewayHttpRequest>()
        val bodies = mutableListOf<JsonObject>()
        var refuseSnapshot: Int? = null
        var refuseWrite: Int? = null
        override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
            requests += request
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            val body = if (buffer.size == 0L) JsonObject(emptyMap()) else Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            bodies += body
            val response = when (request.method) {
                "GET" -> {
                    refuseSnapshot?.let { return GatewayHttpResult.Rejected(it, "Refused", if (it >= 400) """{"detail":"revision_conflict"}""".toByteArray() else ByteArray(0)) }
                    """{"session_id":"public-session","exists":true,"runtime_revision":7,"runtime_generation":3}"""
                }
                "PATCH" -> {
                    refuseWrite?.let { return GatewayHttpResult.Rejected(it, "Refused", if (it >= 400) """{"detail":"revision_conflict"}""".toByteArray() else ByteArray(0)) }
                    buildJsonObject {
                        put("ok", true); put("session_id", "public-session"); put("revision", 8)
                        put("execution_generation", 3); put("operation", "sidebar")
                        body.filterKeys { it in setOf("title", "pinned", "archived", "unread") }.forEach { (key, value) -> put(key, value) }
                    }.toString()
                }
                else -> """{"ok":true,"session_id":"public-session","revision":8,"execution_generation":3,"operation":"delete","deleted_ids":["public-session","public-child"]}"""
            }
            return GatewayHttpResult.Success(200, response.toByteArray())
        }
    }
}
