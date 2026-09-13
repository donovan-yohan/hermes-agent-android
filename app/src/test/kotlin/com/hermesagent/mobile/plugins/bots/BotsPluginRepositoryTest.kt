package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostEvent
import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The roster's Gateway read.
 *
 * `profiles.list` is `tui_gateway/methods_profiles.py:237-254` @
 * `564aef2946c436500a5e80ee117b66b789b3f99a`; every name, path and preview in
 * these fixtures is invented.
 */
class BotsPluginRepositoryTest {

    private class FakeHost(private val result: PluginHostResult) : PluginHost {
        var lastMethod: String? = null
        var lastParams: JsonObject? = null

        override suspend fun request(method: String, params: JsonObject): PluginHostResult {
            lastMethod = method
            lastParams = params
            return result
        }

        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }

    private fun json(body: String) = Json.parseToJsonElement(body)

    private val twoBots = """
        {
          "profiles": [
            {
              "name": "default",
              "display_name": "Hermes",
              "description": "the primary profile",
              "has_avatar": true,
              "last_session": {"id": "s-1", "last_active": 1800000000, "preview": "older"},
              "worker_session": {
                "id": "w-1",
                "source": "kanban",
                "title": "a kanban worker",
                "last_active": 1800000600
              },
              "canonical_session": {
                "id": "c-1",
                "resolved_id": "c-2",
                "last_active": 1800000500,
                "preview": "newer"
              }
            },
            {
              "name": "researcher",
              "description": "",
              "last_session": {"id": "s-2", "last_active": 1799999999, "preview": "hi"}
            },
            {"display_name": "no name here"}
          ],
          "bot_mode_protocol": true
        }
    """.trimIndent()

    // ── parsing ───────────────────────────────────────────────────────────────

    @Test
    fun `a roster row without a name is dropped, not invented`() {
        val rows = parseBotsRoster(json(twoBots))

        assertNotNull(rows)
        assertEquals(listOf("default", "researcher"), rows!!.map { it.name })
    }

    @Test
    fun `the profile fields the roster renders survive the parse`() {
        val row = parseBotsRoster(json(twoBots))!!.first()

        assertEquals("default", row.name)
        assertEquals("Hermes", row.displayName)
        assertEquals("the primary profile", row.description)
        assertTrue(row.hasAvatar)
        assertNull(row.connectionId)
    }

    @Test
    fun `a row without avatars reports none`() {
        val row = parseBotsRoster(json(twoBots))!![1]

        assertFalse(row.hasAvatar)
    }

    @Test
    fun `the fresher session is the row's activity and seconds become milliseconds`() {
        val row = parseBotsRoster(json(twoBots))!!.first()

        assertEquals("newer", botActivitySession(row)?.preview)
        assertEquals("c-1", botActivitySession(row)?.id)
        assertEquals("c-2", botActivitySession(row)?.resolvedId)
        assertEquals(1_800_000_500_000L, row.lastActiveMillis)
    }

    @Test
    fun `a gateway without a canonical chat degrades to the last session`() {
        val row = parseBotsRoster(json(twoBots))!![1]

        assertEquals("hi", botActivitySession(row)?.preview)
        assertEquals(1_799_999_999_000L, row.lastActiveMillis)
    }

    @Test
    fun `the worker session comes off the wire with the row`() {
        // `worker_session` is on every row whenever `include_sessions` is on
        // (`methods_profiles.py:216` @ the pin) and carries the same
        // `last_active`; dropping it is what makes a working profile read idle.
        val row = parseBotsRoster(json(twoBots))!!.first()

        assertEquals("w-1", row.workerSession?.id)
        assertEquals(1_800_000_600L, row.workerSession?.lastActiveSeconds)
    }

    @Test
    fun `a gateway that sends no worker session leaves it absent`() {
        assertNull(parseBotsRoster(json(twoBots))!![1].workerSession)
    }

    @Test
    fun `a fractional last_active off SQLite still becomes millis`() {
        // The Gateway reads these straight out of SQLite, where the column is
        // `REAL` (`hermes_state_common.py:319` @ the pin), so the JSON is
        // `1700000900.5` — `toLongOrNull()` answers null for it, which read as
        // "no activity" for every row and silently killed the worker signal.
        val row = parseBotsRoster(
            json(
                """
                {"profiles": [{
                  "name": "a",
                  "last_session": {"id": "s", "last_active": 1800000500.5, "preview": "hi"},
                  "worker_session": {"id": "w", "source": "kanban", "last_active": 1800000600.25}
                }]}
                """,
            ),
        )!!.single()

        assertEquals(1_800_000_500L, row.lastSession?.lastActiveSeconds)
        assertEquals(1_800_000_500_000L, row.lastActiveMillis)
        assertEquals(1_800_000_600L, row.workerSession?.lastActiveSeconds)
        assertTrue(workerActiveAt(row, nowMillis = 1_800_000_650_000L))
    }

    @Test
    fun `an integral float last_active reads the same as an integer one`() {
        // Python serialises a whole float as `1800000500.0`, so even a session
        // whose stamp has no fraction has to survive the read.
        val row = parseBotsRoster(
            json("""{"profiles": [{"name": "a", "last_session": {"last_active": 1800000500.0}}]}"""),
        )!!.single()

        assertEquals(1_800_000_500L, row.lastSession?.lastActiveSeconds)
    }

    @Test
    fun `a malformed envelope answers null so the caller keeps its last roster`() {
        assertNull(parseBotsRoster(json("""{"profiles": "nope"}""")))
        assertNull(parseBotsRoster(json("{}")))
        assertNull(parseBotsRoster(json("[]")))
        assertNull(parseBotsRoster(json("\"nope\"")))
    }

    @Test
    fun `a row that is not an object is dropped`() {
        val rows = parseBotsRoster(json("""{"profiles": [1, "x", null, {"name": "kept"}]}"""))

        assertEquals(listOf("kept"), rows!!.map { it.name })
    }

    @Test
    fun `a missing last_active reads as no activity rather than the epoch`() {
        val row = parseBotsRoster(
            json("""{"profiles": [{"name": "a", "last_session": {"preview": "hi"}}]}"""),
        )!!.single()

        assertEquals(0L, botActivitySession(row)?.lastActiveSeconds)
        assertNull(row.lastActiveMillis)
    }

    // ── the host door ─────────────────────────────────────────────────────────

    @Test
    fun `a good answer loads the roster and asks for sessions`() = runTest {
        val host = FakeHost(PluginHostResult.Success(json(twoBots)))

        val load = BotsPluginRepository(host).loadRoster()

        assertTrue(load is BotsRosterLoad.Loaded)
        assertEquals(listOf("default", "researcher"), (load as BotsRosterLoad.Loaded).rows.map { it.name })
        assertEquals("profiles.list", host.lastMethod)
        assertEquals(JsonPrimitive(true), host.lastParams?.get("include_sessions"))
    }

    @Test
    fun `an unknown method is the gateway-build answer`() = runTest {
        val load = BotsPluginRepository(FakeHost(PluginHostResult.UnavailableOnGateway)).loadRoster()

        assertEquals(BotsRosterLoad.UnavailableOnGateway, load)
    }

    @Test
    fun `a refusal carries this app's own sentence`() = runTest {
        val load = BotsPluginRepository(
            FakeHost(PluginHostResult.Refused(500, "Try again.")),
        ).loadRoster()

        assertEquals(BotsRosterLoad.Refused("Try again."), load)
    }

    @Test
    fun `an unreadable answer refuses rather than loading nothing`() = runTest {
        val load = BotsPluginRepository(
            FakeHost(PluginHostResult.Success(json("""{"profiles": "nope"}"""))),
        ).loadRoster()

        val refused = load as BotsRosterLoad.Refused
        // The backend's own text never reaches the surface.
        assertFalse(refused.safeMessage.contains("nope"))
    }

    @Test
    fun `an empty roster loads as an empty list, not as a failure`() = runTest {
        val load = BotsPluginRepository(
            FakeHost(PluginHostResult.Success(buildJsonObject { put("profiles", Json.parseToJsonElement("[]")) })),
        ).loadRoster()

        assertEquals(BotsRosterLoad.Loaded(emptyList()), load)
    }

    // ── canonical Bot Chat lookup ────────────────────────────────────────────

    @Test
    fun `canonical lookup sends the exact hidden profile scoped request and prefers resolved id`() = runTest {
        val host = FakeHost(
            PluginHostResult.Success(json("""{"sessions":[{"id":"old-tip","resolved_id":"durable-tip","title":"Bot Chat"}]}""")),
        )

        assertEquals(BotChatLookup.Found("durable-tip"), BotsPluginRepository(host).findCanonicalChat("bot-a", null))
        assertEquals("session.list", host.lastMethod)
        assertEquals(
            buildJsonObject {
                put("profile", JsonPrimitive("bot-a"))
                put("title", JsonPrimitive("Bot Chat"))
                put("limit", JsonPrimitive(200))
                put("include_hidden", JsonPrimitive(true))
            },
            host.lastParams,
        )
    }

    @Test
    fun `canonical lookup falls back to id and never emits a create or submit`() = runTest {
        val host = FakeHost(
            PluginHostResult.Success(json("""{"sessions":[{"id":"durable-tip","title":"Bot Chat"}]}""")),
        )

        assertEquals(BotChatLookup.Found("durable-tip"), BotsPluginRepository(host).findCanonicalChat("bot-a", null))
        assertEquals(listOf("session.list"), listOfNotNull(host.lastMethod))
    }

    @Test
    fun `canonical lookup fails closed for absent ambiguous malformed refused or unavailable answers`() = runTest {
        suspend fun lookup(result: PluginHostResult, canonicalId: String? = null) =
            BotsPluginRepository(FakeHost(result)).findCanonicalChat("bot-a", canonicalId)

        assertEquals(BotChatLookup.Missing, lookup(PluginHostResult.Success(json("""{"sessions":[]}"""))))
        assertEquals(BotChatLookup.Unsafe, lookup(PluginHostResult.Success(json("""{"sessions":[]}""")), "roster-tip"))
        assertEquals(BotChatLookup.Unsafe, lookup(PluginHostResult.Success(json("""{"sessions":[{"id":"x","title":"Other"}]}"""))))
        assertEquals(BotChatLookup.Unsafe, lookup(PluginHostResult.Success(json("""{"sessions":[{"id":"x","title":"Bot Chat"},{"id":"y","title":"Bot Chat"}]}"""))))
        assertEquals(BotChatLookup.Unsafe, lookup(PluginHostResult.Success(json("""{"sessions":[null]}"""))))
        assertEquals(BotChatLookup.Unsafe, lookup(PluginHostResult.Refused(500, "nope")))
        assertEquals(BotChatLookup.Unsafe, lookup(PluginHostResult.UnavailableOnGateway))
    }
}
