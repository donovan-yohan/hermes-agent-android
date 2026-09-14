package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostEvent
import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.coroutines.flow.MutableStateFlow
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

    // ── canonical Bot Chat open-or-create (Phase B) ───────────────────────────

    /**
     * A host that answers per method from a script and records every call.
     *
     * `onCall` runs between the answer and the caller's next step, which is
     * what lets an endpoint fence be flipped mid-sequence.
     */
    private class ScriptedHost(
        override val endpointGeneration: MutableStateFlow<Long> = MutableStateFlow(0L),
    ) : PluginHost {
        private val answers = mutableMapOf<String, ArrayDeque<PluginHostResult>>()
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var onCall: ((String) -> Unit)? = null

        fun answer(method: String, vararg results: PluginHostResult) {
            answers[method] = ArrayDeque(results.toList())
        }

        override suspend fun request(method: String, params: JsonObject): PluginHostResult {
            calls += method to params
            onCall?.invoke(method)
            return answers[method]?.removeFirstOrNull() ?: error("no scripted answer for $method")
        }

        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }

    private fun sessions(body: String) = PluginHostResult.Success(json(body))

    private fun emptyRegistry() = sessions("""{"sessions":[]}""")

    private fun registryRow(resolvedId: String? = null, id: String = "root") =
        sessions(
            """{"sessions":[{"id":"$id"${if (resolvedId != null) ""","resolved_id":"$resolvedId"""" else ""},"title":"Bot Chat"}]}""",
        )

    private fun created(storedId: String = "created-durable", runtimeId: String = "created-runtime") =
        PluginHostResult.Success(
            json("""{"session_id":"$runtimeId","stored_session_id":"$storedId","messages":[]}"""),
        )

    @Test
    fun `an existing canonical chat opens without ever creating`() = runTest {
        val host = ScriptedHost().apply { answer("session.list", registryRow(resolvedId = "durable-tip")) }

        val open = BotsPluginRepository(host).openCanonicalChat("bot-a", null)

        assertEquals(BotChatOpen.Opened("durable-tip"), open)
        assertEquals(listOf("session.list"), host.calls.map { it.first })
    }

    @Test
    fun `a registry that twice confirms no chat creates one titled hidden and profile following`() = runTest {
        val host = ScriptedHost().apply {
            answer("session.list", emptyRegistry(), emptyRegistry())
            answer("session.create", created())
            answer("session.title", PluginHostResult.Success(json("""{"pending":false,"title":"Bot Chat"}""")))
        }

        val open = BotsPluginRepository(host).openCanonicalChat("bot-a", null)

        assertEquals(BotChatOpen.Opened("created-durable"), open)
        // The whole sequence, in order: two reads (adopt-before-mint), the
        // create, and the eager title that materializes the row. No prompt is
        // submitted anywhere in it — Android's open path ships no kickoff.
        assertEquals(
            listOf("session.list", "session.list", "session.create", "session.title"),
            host.calls.map { it.first },
        )
        assertEquals(
            buildJsonObject {
                put("profile", JsonPrimitive("bot-a"))
                put("title", JsonPrimitive("Bot Chat"))
                put("hidden", JsonPrimitive(true))
                put("follow_profile_config", JsonPrimitive(true))
            },
            host.calls[2].second,
        )
        assertEquals(
            buildJsonObject {
                put("session_id", JsonPrimitive("created-runtime"))
                put("title", JsonPrimitive("Bot Chat"))
            },
            host.calls[3].second,
        )
    }

    @Test
    fun `a create race is reconciled by re-reading and adopting the exact title winner`() = runTest {
        val host = ScriptedHost().apply {
            answer("session.list", emptyRegistry(), emptyRegistry(), registryRow(resolvedId = "winner-tip", id = "winner-root"))
            answer("session.create", created())
            // The loser's eager title hits the winner's row.
            answer("session.title", PluginHostResult.Refused(4022, "Title 'Bot Chat' is already in use by session winner-root"))
        }

        val open = BotsPluginRepository(host).openCanonicalChat("bot-a", null)

        assertEquals(BotChatOpen.Opened("winner-tip"), open)
        assertEquals(
            listOf("session.list", "session.list", "session.create", "session.title", "session.list"),
            host.calls.map { it.first },
        )
    }

    @Test
    fun `a title write that cannot be made and confirms no winner fails closed and creates nothing more`() = runTest {
        val host = ScriptedHost().apply {
            answer("session.list", emptyRegistry(), emptyRegistry(), emptyRegistry())
            answer("session.create", created())
            answer("session.title", PluginHostResult.Refused(5007, "disk said no"))
        }

        val open = BotsPluginRepository(host).openCanonicalChat("bot-a", null)

        assertEquals(BotChatOpen.Unsafe, open)
        assertEquals(
            listOf("session.list", "session.list", "session.create", "session.title", "session.list"),
            host.calls.map { it.first },
        )
        assertEquals(1, host.calls.count { it.first == "session.create" })
    }

    @Test
    fun `no create is sent after a refused unavailable ambiguous or malformed read`() = runTest {
        // Every answer here is something other than a confirmed-absent
        // registry. A zero-row answer without a roster claim is deliberately
        // not in this list: that is the one licence to create, and
        // `a registry that twice confirms no chat creates one...` owns it.
        val cases = listOf(
            PluginHostResult.Refused(500, "backend prose"),
            PluginHostResult.UnavailableOnGateway,
            sessions("""{"sessions":[{"id":"x","title":"Other"}]}"""),
            sessions("""{"sessions":[{"id":"x","title":"Bot Chat"},{"id":"y","title":"Bot Chat"}]}"""),
            PluginHostResult.Success(json("""{"sessions":"nope"}""")),
        )
        for (result in cases) {
            val host = ScriptedHost().apply { answer("session.list", result) }
            val open = BotsPluginRepository(host).openCanonicalChat("bot-a", null)

            assertEquals("$result", BotChatOpen.Unsafe, open)
            assertEquals("$result", listOf("session.list"), host.calls.map { it.first })
        }

        // The roster-backed zero-row answer: the registry says nothing while
        // the roster says a chat exists, so absence is unconfirmed.
        val rosterBacked = ScriptedHost().apply { answer("session.list", emptyRegistry()) }
        assertEquals(
            BotChatOpen.Unsafe,
            BotsPluginRepository(rosterBacked).openCanonicalChat("bot-a", "roster-tip"),
        )
        assertEquals(listOf("session.list"), rosterBacked.calls.map { it.first })
    }

    @Test
    fun `a creation answer without both ids is never adopted`() = runTest {
        val host = ScriptedHost().apply {
            answer("session.list", emptyRegistry(), emptyRegistry())
            answer("session.create", PluginHostResult.Success(json("""{"session_id":"runtime-only"}""")))
        }

        assertEquals(BotChatOpen.Unsafe, BotsPluginRepository(host).openCanonicalChat("bot-a", null))
        assertEquals(listOf("session.list", "session.list", "session.create"), host.calls.map { it.first })
    }

    @Test
    fun `the endpoint fence stops the sequence before every later call`() = runTest {
        // Flip the fence during the Nth call of the sequence; nothing after it
        // may reach the Gateway, and the attempt answers Unsafe. The sizes are
        // the calls sent up to and including the one the switch landed on.
        val stops = listOf(
            "session.list#2" to 2,
            "session.create#1" to 3,
            "session.title#1" to 4,
            "session.list#3" to 5,
        )
        for ((stopAt, expectedCalls) in stops) {
            val endpoint = MutableStateFlow(0L)
            val host = ScriptedHost(endpoint).apply {
                answer("session.list", emptyRegistry(), emptyRegistry(), registryRow(resolvedId = "winner-tip"))
                answer("session.create", created())
                answer("session.title", PluginHostResult.Refused(5007, "title rejected"))
                val seen = mutableMapOf<String, Int>()
                onCall = { method ->
                    val ordinal = seen.merge(method, 1, Int::plus)!!
                    if ("$method#$ordinal" == stopAt) endpoint.value = 1L
                }
            }

            val open = BotsPluginRepository(host).openCanonicalChat("bot-a", null, expectedEndpointGeneration = 0L)

            assertEquals(stopAt, BotChatOpen.Unsafe, open)
            assertEquals(stopAt, expectedCalls, host.calls.size)
        }
    }
}
