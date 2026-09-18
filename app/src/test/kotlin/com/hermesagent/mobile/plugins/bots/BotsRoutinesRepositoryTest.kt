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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bot-scoped routines read, against the wire as the pin actually sends it.
 *
 * `cron.manage` is `tui_gateway/methods_tools.py:1075-1099` and the row is
 * `tools/cronjob_job_args.py:351-399` @
 * `d177b119e9c56c9ddc0b7379ffce52341ec06584`. Every job name, id and preview
 * in these fixtures is invented.
 */
class BotsRoutinesRepositoryTest {

    private class FakeHost(
        private val result: PluginHostResult,
        override val endpointGeneration: MutableStateFlow<Long> = MutableStateFlow(0L),
    ) : PluginHost {
        val calls = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun request(method: String, params: JsonObject): PluginHostResult {
            calls += method to params
            return result
        }

        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }

    private fun json(body: String) = Json.parseToJsonElement(body)

    private fun answer(body: String) = PluginHostResult.Success(json(body))

    private suspend fun load(
        body: String,
        profile: String = "ops",
        generation: Long = 0L,
    ): Pair<BotsRoutinesLoad, FakeHost> {
        val host = FakeHost(answer(body))
        return load(host, profile, generation) to host
    }

    private suspend fun load(host: FakeHost, profile: String, generation: Long = 0L) =
        BotsPluginRepository(host).loadRoutines(profile, generation)

    private val emptyList = """{"success":true,"count":0,"jobs":[]}"""

    // ── the request ───────────────────────────────────────────────────────────

    @Test
    fun `the read is one profile scoped list that asks for paused jobs too`() = runTest {
        val (load, host) = load(emptyList)

        assertEquals(BotsRoutinesLoad.Loaded(emptyList(), null), load)
        assertEquals(1, host.calls.size)
        assertEquals("cron.manage", host.calls.single().first)
        // `include_disabled:true` is asserted on the request, not only through
        // the parse: the Gateway hides paused rows by default, so a list that
        // omitted the flag would look like a store with no paused routines.
        assertEquals(
            buildJsonObject {
                put("action", JsonPrimitive("list"))
                put("include_disabled", JsonPrimitive(true))
                put("profile", JsonPrimitive("ops"))
            },
            host.calls.single().second,
        )
    }

    @Test
    fun `the profile goes out exactly as the roster spelled it`() = runTest {
        // The Gateway resolves the name against its own profile registry and
        // answers `4064 profile '<p>' not found` for anything it does not have
        // (`methods_tools.py:56-60`), so a slugged or lower-cased copy here
        // would address a profile that does not exist.
        val (_, host) = load(emptyList, profile = "Ops-Team")

        assertEquals(JsonPrimitive("Ops-Team"), host.calls.single().second["profile"])
    }

    @Test
    fun `no mutation of any kind is issued by a read`() = runTest {
        val (_, host) = load("""{"success":true,"count":1,"jobs":[{"job_id":"j1","name":"[bot:ops] Morning"}]}""")

        assertEquals(listOf("cron.manage"), host.calls.map { it.first })
        assertEquals(
            listOf("list"),
            host.calls.map { it.second["action"]?.let { action -> (action as JsonPrimitive).content } },
        )
    }

    @Test
    fun `disabled terminal and unknown wire records preserve mutation authority through repository load`() = runTest {
        for ((wire, state) in mapOf(
            "completed" to RoutineRunState.Completed,
            "error" to RoutineRunState.Failed,
            "future-state" to RoutineRunState.Unknown,
        )) {
            val (result, host) = load(
                """{"success":true,"scoped":"ops","jobs":[{"job_id":"retired","enabled":false,"state":"$wire","next_run_at":null}]}""",
            )
            val row = (result as BotsRoutinesLoad.Loaded).jobs.single()
            assertEquals(state, row.state)
            assertFalse(row.active)
            assertFalse(row.permits(RoutineAction.Pause))
            assertFalse(row.permits(RoutineAction.Resume))
            assertEquals(state != RoutineRunState.Unknown, row.permits(RoutineAction.Remove))
            assertEquals(JsonPrimitive(true), host.calls.single().second["include_disabled"])
        }
    }

    // ── parsing ───────────────────────────────────────────────────────────────

    @Test
    fun `a readable list carries the jobs and the scope echo`() = runTest {
        val (load, _) = load(
            """
            {"success":true,"count":2,"scoped":"ops","jobs":[
              {"job_id":"j1","name":"[bot:ops] Morning","schedule":"every 1440m","enabled":true,"state":"scheduled"},
              {"job_id":"j2","name":"[bot:ops] Nightly","schedule":"30m","enabled":false,"state":"paused"}
            ]}
            """,
        )

        val loaded = load as BotsRoutinesLoad.Loaded
        assertEquals("ops", loaded.scoped)
        assertEquals(listOf("j1", "j2"), loaded.jobs.map { it.id })
        assertEquals(listOf("Morning", "Nightly"), loaded.jobs.map { it.title })
        // A paused routine stays in the list: that is what `include_disabled`
        // asked for, and it is what keeps a paused routine from reading as
        // a deleted one.
        assertEquals(listOf(true, false), loaded.jobs.map { it.active })
    }

    @Test
    fun `a job without a usable job_id is dropped rather than invented`() = runTest {
        val (load, _) = load(
            """
            {"success":true,"count":5,"jobs":[
              {"job_id":"kept","name":"[bot:ops] Kept"},
              {"name":"[bot:ops] no id"},
              {"job_id":null,"name":"[bot:ops] null id"},
              {"job_id":7,"name":"[bot:ops] numeric id"},
              {"job_id":"","name":"[bot:ops] blank id"},
              {"job_id":true,"name":"[bot:ops] boolean id"}
            ]}
            """,
        )

        assertEquals(listOf("kept"), (load as BotsRoutinesLoad.Loaded).jobs.map { it.id })
    }

    @Test
    fun `an absent jobs member is an empty list and a non-array one is unreadable`() = runTest {
        // The distinction is the point: "the Gateway answered with no jobs" and
        // "the Gateway answered something this app cannot read as a list" are
        // different claims, and reporting the second as the first is how a
        // surface claims a store is empty when it is not.
        val (absent, _) = load("""{"success":true,"count":0}""")
        assertEquals(BotsRoutinesLoad.Loaded(emptyList(), null), absent)

        for (body in listOf("""{"success":true,"jobs":"nope"}""", """{"success":true,"jobs":{}}""")) {
            val (load, _) = load(body)
            assertEquals(body, BotsRoutinesLoad.Refused("The Gateway sent a routine list this app could not read."), load)
        }

        // A row that is not an object is dropped, never rendered from a guess.
        val (rows, _) = load("""{"success":true,"jobs":[1,"x",null,{"job_id":"kept"}]}""")
        assertEquals(listOf("kept"), (rows as BotsRoutinesLoad.Loaded).jobs.map { it.id })
    }

    @Test
    fun `an unreadable answer never carries the backend's own text`() = runTest {
        val (load, _) = load("""{"success":true,"jobs":"backendprose"}""")

        val refused = load as BotsRoutinesLoad.Refused
        assertFalse(refused.safeMessage.contains("backendprose"))
    }

    @Test
    fun `success false inside a good envelope is a rejection not a transport failure`() = runTest {
        // `cronjob()` reports its own failures with `success:false` inside a
        // perfectly successful JSON-RPC envelope (`methods_tools.py:1077-1085`
        // forwards it; `tools/cronjob_tools.py` builds it). Reading that as
        // transport failure is how a refused read looks like a broken network.
        val (load, _) = load("""{"success":false,"error":"backend prose","jobs":[]}""")

        assertEquals(BotsRoutinesLoad.Rejected, load)
    }

    @Test
    fun `a false success is only a literal false`() = runTest {
        // A stringified or numeric `success` is a shape this contract does not
        // have; it must not be read as a rejection when a list came with it.
        for (body in listOf(
            """{"success":"false","jobs":[{"job_id":"j1"}]}""",
            """{"success":0,"jobs":[{"job_id":"j1"}]}""",
            """{"success":null,"jobs":[{"job_id":"j1"}]}""",
        )) {
            val (load, _) = load(body)
            assertEquals(body, listOf("j1"), (load as BotsRoutinesLoad.Loaded).jobs.map { it.id })
        }
    }

    @Test
    fun `a malformed envelope is unreadable rather than an empty list`() = runTest {
        for (body in listOf("""[]""", """"nope"""", """null""")) {
            val (load, _) = load(body)
            assertTrue(body, load is BotsRoutinesLoad.Refused)
        }
    }

    // ── the host door ─────────────────────────────────────────────────────────

    @Test
    fun `an unknown method is the gateway-build answer`() = runTest {
        val host = FakeHost(PluginHostResult.UnavailableOnGateway)

        assertEquals(BotsRoutinesLoad.UnavailableOnGateway, load(host, "ops"))
        assertEquals(listOf("cron.manage"), host.calls.map { it.first })
    }

    @Test
    fun `a refusal carries this app's own sentence`() = runTest {
        val host = FakeHost(PluginHostResult.Refused(5023, "Hermes refused that Gateway request."))

        assertEquals(
            BotsRoutinesLoad.Refused("Hermes refused that Gateway request."),
            load(host, "ops"),
        )
    }

    @Test
    fun `a read bound to an endpoint the app has left is refused before the wire`() = runTest {
        // The generation moved and the door's own fence is what refuses: the
        // replacement Gateway must receive nothing about the previous bot.
        val host = FakeHost(answer(emptyList), endpointGeneration = MutableStateFlow(1L))

        val load = load(host, "ops", generation = 0L)

        assertTrue(load is BotsRoutinesLoad.Refused)
        assertEquals(emptyList<Pair<String, JsonObject>>(), host.calls)
    }

    // ── profile scoping ───────────────────────────────────────────────────────

    @Test
    fun `a scoped echo naming another profile refuses instead of showing its store`() = runTest {
        // Desktop falls back to tag filtering here (`cron.tsx:221-229`). This
        // app refuses: the reply is about a profile the person did not ask for,
        // and rendering any part of it would put another bot's routines under
        // this bot's name. Ledgered as drift in docs/parity/bot-routines.md.
        val (load, _) = load(
            """
            {"success":true,"scoped":"research","jobs":[
              {"job_id":"research-untagged","name":"ordinary research cronjob"},
              {"job_id":"ops-routine","name":"[bot:ops] Bot Mode routine"}
            ]}
            """,
        )

        assertEquals(BotsRoutinesLoad.MismatchedScope("ops"), load)
    }

    @Test
    fun `a matching scope echo is compared trimmed and case-insensitively`() = runTest {
        val (load, _) = load("""{"success":true,"scoped":" Ops ","jobs":[]}""", profile = "ops")

        // The echo is parsed trimmed and then compared through
        // `normalizedProfileName` — trimmed and lower-cased (`cron.tsx:250-252`)
        // — so a Gateway that echoes the name with surrounding space or
        // different case still matches and the list is not refused. The echo is
        // backend text this app compares, never one it renders.
        assertEquals("Ops", (load as BotsRoutinesLoad.Loaded).scoped)
    }

    @Test
    fun `an absent scope echo is a readable legacy answer`() = runTest {
        val (load, _) = load("""{"success":true,"jobs":[{"job_id":"j1","name":"[bot:ops] Morning"}]}""")

        val loaded = load as BotsRoutinesLoad.Loaded
        assertNull(loaded.scoped)
        assertEquals(listOf("j1"), loaded.jobs.map { it.id })
    }

    // ── selection ─────────────────────────────────────────────────────────────

    private fun job(id: String, name: String?, preview: String? = null) = RoutineRow(
        id = id,
        title = routineTitle(name),
        scheduleLabel = "",
        repeat = null,
        active = true,
        state = RoutineRunState.Scheduled,
        nextRunMillis = null,
        taggedBot = routineBot(name),
        legacyDelegated = routineBot(name) != null &&
            preview != null &&
            preview.startsWith("You are running the scheduled routine \""),
    )

    @Test
    fun `a scoped list shows every job in it, tagged or not`() {
        val jobs = listOf(
            job("legacy", "ordinary profile cronjob"),
            job("routine", "[bot:ops] Bot Mode routine"),
        )

        assertEquals(jobs, selectRoutineJobs(jobs, "ops", "ops"))
    }

    @Test
    fun `an unmarked list keeps the tag filter`() {
        // Older gateways ignore the `profile` param entirely, so the reply is
        // the launch-profile store and the `[bot:slug]` tag is the only honest
        // filter left.
        val jobs = listOf(
            job("legacy", "ordinary launch-profile cronjob"),
            job("routine", "[bot:ops] Bot Mode routine"),
            job("other", "[bot:research] Digest"),
        )

        assertEquals(listOf("routine"), selectRoutineJobs(jobs, null, "ops").map { it.id })
    }

    @Test
    fun `the tag filter matches case-insensitively and compares lower-cased`() {
        val jobs = listOf(job("routine", "[bot:OPS] Morning"))

        assertEquals(listOf("routine"), selectRoutineJobs(jobs, null, "Ops").map { it.id })
        assertEquals("ops", jobs.single().taggedBot)
    }

    @Test
    fun `a tag that is not a well-formed slug is not a tag`() {
        // Desktop's own pattern is `[a-z0-9][a-z0-9_-]*` and its regex is
        // case-insensitive (`cron.tsx:73`), so `[bot:Ops Team]` is NOT a bot
        // tag — the space breaks it, and the job is untagged, which puts it in
        // the `default` bucket. A name like `[bot:ops-team]` IS a tag.
        val notATag = listOf(job("j", "[bot:Ops Team] Morning"))

        assertNull(notATag.single().taggedBot)
        assertEquals(emptyList<RoutineRow>(), selectRoutineJobs(notATag, null, "ops"))
        assertEquals(listOf("j"), selectRoutineJobs(notATag, null, "default").map { it.id })

        val tagged = listOf(job("k", "[bot:ops-team] Morning"))
        assertEquals("ops-team", tagged.single().taggedBot)
        assertEquals(listOf("k"), selectRoutineJobs(tagged, null, "ops-team").map { it.id })
        // A tag for another bot still does not surface under this one.
        assertEquals(emptyList<RoutineRow>(), selectRoutineJobs(tagged, null, "ops"))
    }

    @Test
    fun `an untagged job belongs to the default bot alone`() {
        val legacy = job("legacy", "Existing reminder")

        assertEquals(listOf("legacy"), selectRoutineJobs(listOf(legacy), null, "default").map { it.id })
        assertEquals(emptyList<RoutineRow>(), selectRoutineJobs(listOf(legacy), null, "ops"))
    }

    @Test
    fun `a job whose name is only a tag still filters under that bot`() {
        val jobs = listOf(job("routine", "[bot:ops]"))

        assertEquals(listOf("routine"), selectRoutineJobs(jobs, null, "ops").map { it.id })
    }

    @Test
    fun `the filter hint only explains an empty view over a non-empty store`() {
        val all = listOf(job("j", "[bot:research] Digest"))

        assertEquals(BotsRoutinesCopy.FILTER_HINT, routineFilterHint(all, emptyList()))
        assertNull(routineFilterHint(all, all))
        assertNull(routineFilterHint(emptyList(), emptyList()))
    }
}
