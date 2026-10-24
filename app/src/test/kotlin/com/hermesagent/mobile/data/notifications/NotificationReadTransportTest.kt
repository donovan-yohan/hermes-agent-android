package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.gateway.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NotificationReadTransportTest {
    @Test fun descriptorWithoutNameResolvesExactServingOwnerFromProducerRoster() = runTest {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val transport = GatewayNotificationReadTransport("endpoint", inventoryHttp(), { method, params ->
            calls += method to params
            when (method) {
                "runtime.describe" -> descriptor()
                "profiles.list" -> roster("bot" to "owner", "other" to "other-owner")
                "session.list" -> Json.parseToJsonElement("""{"sessions":[{"session_id":"hidden","title":"Bot Chat"}]}""")
                else -> error(method)
            }
        }, { true }, {})
        val session = transport.sessions(0, 10).sessions.single()
        assertEquals("bot", session.profile)
        assertEquals("bot", session.senderName)
        assertEquals("owner", session.ownerId)
        assertEquals("hidden", GatewaySessionIdentity("endpoint").apply { bind("bot", "owner") }
            .address(session.appId, "bot")!!.rawId)
        assertEquals(JsonPrimitive(false), calls.single { it.first == "profiles.list" }.second["include_sessions"])
        assertTrue(calls.filter { it.first == "runtime.describe" }.all { it.second.isEmpty() })
    }

    @Test fun unmatchedOrAmbiguousServingRosterRefusesHistoryReads() = runTest {
        val invalid = listOf(
            roster("bot" to "different-owner"),
            roster("bot" to "owner", "alias" to "owner"),
            roster("bot" to "owner", "bot" to "different-owner"),
        )
        for (answer in invalid) {
            var reads = 0
            val http = object : GatewayHttp {
                override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
                    reads++; error("unconfirmed history")
                }
            }
            val transport = GatewayNotificationReadTransport("endpoint", http, { method, _ ->
                when (method) {
                    "runtime.describe" -> descriptor()
                    "profiles.list" -> answer
                    else -> error(method)
                }
            }, { true }, {})
            try { transport.sessions(0, 10); fail("invalid roster accepted") }
            catch (_: IllegalArgumentException) { }
            catch (_: IllegalStateException) { }
            assertEquals(0, reads)
        }
    }

    @Test fun scopedOwnerMustMatchDiscoveredRosterPath() = runTest {
        val transport = GatewayNotificationReadTransport("endpoint", inventoryHttp(
            """[{"id":"foreign","profile":"other"}]""", total = 1), { method, params ->
            when (method) {
                "runtime.describe" -> descriptor()
                "profiles.list" -> if (params["profile"] == null) roster("bot" to "owner", "other" to "other-owner")
                    else roster("other" to "changed-owner", "bot" to "owner")
                else -> error(method)
            }
        }, { true }, {})
        try { transport.sessions(0, 10); fail("changed owner accepted") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    @Test fun hiddenOnlyCountDoesNotBlockCanonicalDiscoveryAtEmptyRecentPage() = runTest {
        for (offset in listOf(0L, 2L)) {
            val requests = mutableListOf<GatewayHttpRequest>()
            val http = object : GatewayHttp {
                override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
                    requests += request
                    return inventoryHttp(total = 5).execute(request)
                }
            }
            val transport = GatewayNotificationReadTransport("endpoint", http, { method, _ ->
                when (method) {
                    "runtime.describe" -> descriptor()
                    "profiles.list" -> roster("bot" to "owner")
                    "session.list" -> Json.parseToJsonElement("""{"sessions":[{"session_id":"hidden","id":"hidden","title":"Bot Chat"}]}""")
                    else -> error(method)
                }
            }, { true }, {})
            val recent = transport.sessions(offset, 2)
            assertEquals(1L shl 50, recent.next)
            val hidden = transport.sessions(recent.next!!, 2)
            assertEquals(listOf("hidden"), hidden.sessions.map { it.rawId })
            assertEquals("owner", hidden.sessions.single().ownerId)
            assertNull(hidden.next)
            assertEquals(listOf(offset.toString()), requests.map { it.query.getValue("offset") })
        }
    }

    @Test fun pinnedBackfillOverflowKeepsBoundedWindowsAndEventuallyReadsOldPins() = runTest {
        val requests = mutableListOf<Long>()
        val all = Json.parseToJsonElement("""[{"id":"recent-a","profile":"bot"},{"id":"recent-b","profile":"bot"},{"id":"recent-c","profile":"bot"},{"id":"recent-d","profile":"bot"},{"id":"old-pin-a","profile":"bot","pinned":true},{"id":"old-pin-b","profile":"bot","pinned":true},{"id":"old-pin-c","profile":"bot","pinned":true}]""").jsonArray
        val http = object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
                val offset = request.query.getValue("offset").toLong()
                val limit = request.query.getValue("limit").toInt()
                requests += offset
                // _pinned_window @ 046094df: requested window, then ALL later pins.
                val rows = all.drop(offset.toInt()).take(limit) + all.drop(offset.toInt() + limit)
                    .filter { it.jsonObject["pinned"] == JsonPrimitive(true) }
                return GatewayHttpResult.Success(200, buildJsonObject {
                    put("sessions", JsonArray(rows)); put("total", all.size)
                    put("offset", offset); put("limit", limit); put("errors", buildJsonArray {})
                    put("storage", buildJsonObject {})
                }.toString().toByteArray())
            }
        }
        val discovered = mutableListOf<String>()
        var offset = 0L
        for (expected in listOf(listOf("recent-a", "recent-b"), listOf("recent-c", "recent-d"),
            listOf("old-pin-a", "old-pin-b"), listOf("old-pin-c"))) {
            // A saved cursor must work with a fresh transport, not cached overflow.
            val transport = GatewayNotificationReadTransport("endpoint", http, { method, _ ->
                when (method) {
                    "runtime.describe" -> descriptor()
                    "profiles.list" -> roster("bot" to "owner")
                    "session.list" -> Json.parseToJsonElement("""{"sessions":[]}""")
                    else -> error(method)
                }
            }, { true }, {})
            val page = transport.sessions(offset, 2)
            assertTrue(page.sessions.size <= 2)
            assertEquals(expected, page.sessions.map { it.rawId })
            discovered += page.sessions.map { it.rawId }
            offset = page.next!!
            transport.close()
        }
        assertEquals(listOf(0L, 2L, 4L, 6L), requests)
        assertEquals(1L shl 50, offset)
        assertEquals(all.map { it.jsonObject.getValue("id").jsonPrimitive.content }, discovered)
    }

    @Test fun partialProfileInventoryDoesNotAcknowledgeMissingHistory() = runTest {
        val http = object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest) = GatewayHttpResult.Success(200,
                """{"sessions":[],"total":0,"limit":10,"offset":0,"errors":[{"profile":"bot","error":"unavailable"}],"storage":{}}""".toByteArray())
        }
        val transport = GatewayNotificationReadTransport("endpoint", http, { method, _ ->
            when (method) {
                "runtime.describe" -> descriptor()
                "profiles.list" -> roster("bot" to "owner")
                else -> error(method)
            }
        }, { true }, {})
        try { transport.sessions(0, 10); fail("partial inventory accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun fullRecentPageStillContinuesToNamedHiddenBotChat() = runTest {
        val methods = mutableListOf<Pair<String, JsonObject>>()
        val http = object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest) = GatewayHttpResult.Success(200,
                """{"sessions":[{"id":"raw","profile":"bot","title":"Desktop"}],"total":1,"limit":1,"offset":0}""".toByteArray())
        }
        val transport = GatewayNotificationReadTransport("endpoint", http, { method, params ->
            methods += method to params
            Json.parseToJsonElement(when (method) {
                "runtime.describe" -> descriptor().toString()
                "profiles.list" -> if (params["profile"] != null) roster("other" to "other-owner", "bot" to "owner").toString()
                    else roster("bot" to "owner", "other" to "other-owner").toString()
                "session.list" -> if (params["profile"] != null) """{"sessions":[{"session_id":"hidden-other","id":"hidden-other","title":"Bot Chat"}]}"""
                    else """{"sessions":[]}"""
                else -> error(method)
            })
        }, { true }, {})
        var page = transport.sessions(0, 1)
        assertNotNull(page.next)
        val sessions = page.sessions.toMutableList()
        repeat(3) {
            page.next?.let { next -> page = transport.sessions(next, 1); sessions += page.sessions }
        }
        assertEquals(setOf("raw", "hidden-other"), sessions.map { it.rawId }.toSet())
        assertEquals("other", sessions.single { it.rawId == "hidden-other" }.profile)
        assertNull(page.next)
    }

    @Test fun discoversDesktopAndHiddenBotChatWithoutAdoptingEither() = runTest {
        val requests = mutableListOf<GatewayHttpRequest>()
        val calls = mutableListOf<String>()
        val http = object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
                requests += request
                val body = if (request.path.endsWith("/messages")) """{"session_id":"raw-desktop","profile":"bot","messages":[{"id":9,"role":"assistant","content":"hello","timestamp":2}],"pagination":{"limit":10,"offset":0,"order":"latest","returned":1}}"""
                else """{"sessions":[{"id":"raw-desktop","profile":"bot","title":"Desktop only","started_at":1}],"total":1,"limit":10,"offset":0}"""
                return GatewayHttpResult.Success(200, body.toByteArray())
            }
        }
        val transport = GatewayNotificationReadTransport("endpoint", http, { method, params ->
            calls += method
            when (method) {
                "runtime.describe" -> descriptor()
                "profiles.list" -> roster("bot" to "owner")
                "session.list" -> {
                    assertEquals("Bot Chat", params["title"]?.jsonPrimitive?.content)
                    assertEquals(true, params["include_hidden"]?.jsonPrimitive?.boolean)
                    Json.parseToJsonElement("""{"sessions":[{"session_id":"raw-hidden","id":"raw-hidden","title":"Bot Chat"}]}""")
                }
                else -> error(method)
            }
        }, { true }, {})
        val sessions = transport.sessions(0, 10).sessions
        assertEquals(setOf("raw-desktop", "raw-hidden"), sessions.map { it.rawId }.toSet())
        assertEquals("api/profiles/sessions", requests.first().path)
        assertEquals("all", requests.first().query["profile"])
        assertTrue(sessions.all { it.appId.startsWith("hermes-session-v2:") && it.profile == "bot" })
        val message = transport.messages(sessions.first(), 0, 10).messages.single()
        assertEquals(9L, message.id)
        assertEquals("false", requests.last().query["inline_images"])
        assertEquals("latest", requests.last().query["order"])
        assertTrue(requests.all { it.method == "GET" && it.maxResponseBytes <= 524288 && it.isCurrent() })
        assertFalse(calls.any { it in setOf("session.resume", "session.create", "session.open", "session.mark_read") })
    }

    private fun descriptor() = Json.parseToJsonElement("""{"instance_id":"fixture-instance","profile_id":"owner","authority_epoch":"fixture-epoch","capabilities":["durable-admission-v1","event-replay-v1"],"session_create":{"sources":["gui"],"parameters":["title"]},"skin":{}}""")

    // gateway/session_group_controls.py:256-348 @ 046094df: serving row first,
    // discovered siblings are metadata only until a scoped profiles.list confirms them.
    private fun roster(vararg profiles: Pair<String, String>) = buildJsonObject {
        put("profiles", buildJsonArray {
            profiles.forEachIndexed { index, (name, path) -> add(buildJsonObject {
                put("name", name); put("path", path); put("is_default", name == "default")
                put("model", ""); put("provider", ""); put("description", "")
                put("display_name", name); put("skill_count", 0)
                if (index == 0) { put("ui_meta_revisions", buildJsonObject {}); put("has_avatar", false) }
            }) }
        })
        put("bot_mode_protocol", true)
    }

    private fun inventoryHttp(rows: String = "[]", total: Long = 0) = object : GatewayHttp {
        override suspend fun execute(request: GatewayHttpRequest) = GatewayHttpResult.Success(200,
            """{"sessions":$rows,"total":$total,"limit":${request.query.getValue("limit")},"offset":${request.query.getValue("offset")},"errors":[],"storage":{}}""".toByteArray())
    }
}
