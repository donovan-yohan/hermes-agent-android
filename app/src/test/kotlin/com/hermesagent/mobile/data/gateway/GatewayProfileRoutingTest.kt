package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `profile` parameter on the session RPCs, and the unified view's fan-out.
 *
 * Contract at `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`:
 * `session.create` (`tui_gateway/methods_session.py:38-43`), `session.list`
 * (`:163-165`) and `session.resume` (`:322-325`) each take an optional
 * `profile`; a blank one resolves to the launch profile
 * (`tui_gateway/server.py:1519-1533`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayProfileRoutingTest {

    @Test
    fun `the default scope sends the request a single-profile install sends today`() = runTest {
        val rpc = FakeProfileRpc()
        val repository = repository(SessionCache(), rpc, backgroundScope)
        runCurrent()

        repository.refreshSessions()

        // The whole request object, not just the absent `profile`: an install
        // that never touches the rail must send byte-for-byte what it sent
        // before this feature existed.
        val preFeatureRequest = buildJsonObject {
            put("limit", JsonPrimitive(100))
            put("include_hidden", JsonPrimitive(false))
        }
        val listed = rpc.calls.filter { it.first == "session.list" }
        assertTrue(listed.isNotEmpty())
        assertTrue(listed.all { it.second == preFeatureRequest })
    }

    @Test
    fun `a named scope carries its profile on list and create`() = runTest {
        val rpc = FakeProfileRpc()
        val repository = repository(SessionCache(), rpc, backgroundScope)
        runCurrent()
        repository.setProfileRouting(ProfileRouting(activeProfile = "work", listProfiles = listOf("work")))

        repository.refreshSessions()
        repository.createSession(null)

        assertEquals("work", rpc.calls.last { it.first == "session.list" }.second.text("profile"))
        assertEquals("work", rpc.calls.last { it.first == "session.create" }.second.text("profile"))
    }

    @Test
    fun `the unified scope fans out and rows from every profile accumulate`() = runTest {
        val cache = SessionCache()
        val rpc = FakeProfileRpc()
        rpc.sessionListByProfile = mapOf(
            null to listOf("launch-row"),
            "work" to listOf("work-row"),
        )
        val repository = repository(cache, rpc, backgroundScope)
        runCurrent()
        // The connection's own bootstrap refresh has already run by now; this
        // assertion is about the refresh the new scope asks for.
        rpc.calls.clear()
        repository.setProfileRouting(ProfileRouting(listProfiles = listOf(null, "work")))

        repository.refreshSessions()

        assertEquals(listOf(null, "work"), rpc.calls.filter { it.first == "session.list" }.map { it.second.text("profile") })
        assertEquals(setOf("launch-row", "work-row"), cache.state.value.sessions.keys)
        // `session.list` compact rows carry no profile at the pin, so a row
        // out of a named profile's own state.db is stamped with the profile
        // that was asked for; the launch leg stays unstamped, which is the
        // `default` bucket by the filter's own rule.
        assertEquals("work", cache.session("work-row")?.remoteProfile)
        assertNull(cache.session("launch-row")?.remoteProfile)
    }

    @Test
    fun `a profile a session event already named survives a later listing`() = runTest {
        val cache = SessionCache()
        cache.upsertSession(
            SessionSummary(
                id = "known-row",
                title = "Known",
                preview = "",
                lastActiveAtMillis = 0,
                remoteProfile = "lab",
            ),
        )
        val rpc = FakeProfileRpc()
        // The launch-profile leg stamps nothing, and `session.list`'s compact
        // rows carry no profile (`methods_session.py:204-214`), so this row
        // would lose its owner if the merge did not keep it.
        rpc.sessionListByProfile = mapOf(null to listOf("known-row"))
        val repository = repository(cache, rpc, backgroundScope)
        runCurrent()

        repository.refreshSessions()

        assertEquals("lab", cache.session("known-row")?.remoteProfile)
    }

    @Test
    fun `a profile the Gateway cannot resolve does not steal the launch profile's rows`() = runTest {
        val cache = SessionCache()
        val rpc = FakeProfileRpc()
        // `_profile_home` answers None for an unresolvable profile and
        // `_profile_db` hands back the launch handle
        // (`tui_gateway/server.py:1476-1491,1519-1533`), so the named leg
        // returns exactly the launch profile's rows rather than failing.
        rpc.sessionListByProfile = mapOf(
            null to listOf("launch-row"),
            "gone" to listOf("launch-row"),
        )
        val repository = repository(cache, rpc, backgroundScope)
        runCurrent()
        repository.setProfileRouting(ProfileRouting(listProfiles = listOf(null, "gone")))

        repository.refreshSessions()

        assertNull(cache.session("launch-row")?.remoteProfile)
    }

    @Test
    fun `a genuinely separate profile's rows are still stamped`() = runTest {
        val cache = SessionCache()
        val rpc = FakeProfileRpc()
        rpc.sessionListByProfile = mapOf(
            null to listOf("launch-row"),
            "work" to listOf("launch-row", "work-row"),
        )
        val repository = repository(cache, rpc, backgroundScope)
        runCurrent()
        repository.setProfileRouting(ProfileRouting(listProfiles = listOf(null, "work")))

        repository.refreshSessions()

        // Only the row the launch leg already claimed is spared; a row that is
        // genuinely that profile's still gets its owner.
        assertNull(cache.session("launch-row")?.remoteProfile)
        assertEquals("work", cache.session("work-row")?.remoteProfile)
    }

    @Test
    fun `one profile refusing does not discard the profiles that answered`() = runTest {
        val cache = SessionCache()
        val rpc = FakeProfileRpc()
        rpc.sessionListByProfile = mapOf(null to listOf("launch-row"))
        rpc.failListForProfile = "work"
        val repository = repository(cache, rpc, backgroundScope)
        runCurrent()
        repository.setProfileRouting(ProfileRouting(listProfiles = listOf(null, "work")))

        repository.refreshSessions()

        assertEquals(setOf("launch-row"), cache.state.value.sessions.keys)
    }

    @Test
    fun `a single-profile scope still surfaces its own failure`() = runTest {
        val rpc = FakeProfileRpc()
        rpc.failListForProfile = "work"
        val repository = repository(SessionCache(), rpc, backgroundScope)
        runCurrent()
        repository.setProfileRouting(ProfileRouting(activeProfile = "work", listProfiles = listOf("work")))

        // Assert inside the suspend body: runBlocking here would block the
        // test thread against its own virtual-time scheduler.
        val failure = runCatching { repository.refreshSessions() }.exceptionOrNull()
        assertTrue(failure is GatewayRpcException)
    }

    @Test
    fun `resume reaches the profile that owns the row, not the scope on screen`() = runTest {
        val cache = SessionCache()
        cache.upsertSession(
            SessionSummary(
                id = "lab-row",
                title = "Lab",
                preview = "",
                lastActiveAtMillis = 0,
                remoteProfile = "lab",
            ),
        )
        val rpc = FakeProfileRpc()
        val repository = repository(cache, rpc, backgroundScope)
        runCurrent()
        // Browsing everything: the scope names no single profile at all.
        repository.setProfileRouting(ProfileRouting(activeProfile = null, listProfiles = listOf(null, "lab")))

        repository.openSession("lab-row")

        assertEquals("lab", rpc.calls.last { it.first == "session.resume" }.second.text("profile"))
    }

    @Test
    fun `resume of a default-profile row sends no profile at all`() = runTest {
        val cache = SessionCache()
        cache.upsertSession(
            SessionSummary(id = "home-row", title = "Home", preview = "", lastActiveAtMillis = 0),
        )
        val rpc = FakeProfileRpc()
        val repository = repository(cache, rpc, backgroundScope)
        runCurrent()

        repository.openSession("home-row")

        assertNull(rpc.calls.last { it.first == "session.resume" }.second["profile"])
    }

    private fun repository(
        cache: SessionCache,
        rpc: FakeProfileRpc,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = LiveGatewaySessionRepository(
        cache,
        MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
        MutableStateFlow<GatewayRpcClient?>(rpc),
        scope,
    ) { 1_000L }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content

    /** Answers only what these routing assertions need; every id here is invented. */
    private class FakeProfileRpc : GatewayRpcClient {
        private val eventChannel = Channel<GatewayEvent>(capacity = 8)
        override val events = eventChannel.receiveAsFlow()
        val calls = mutableListOf<Pair<String, JsonObject>>()

        /** Requested `profile` (null = omitted) to the rows it answers with. */
        var sessionListByProfile: Map<String?, List<String>> = emptyMap()
        var failListForProfile: String? = null

        override suspend fun request(method: String, params: JsonObject): JsonElement {
            calls += method to params
            val profile = (params["profile"] as? JsonPrimitive)?.content
            return when (method) {
                "session.list" -> {
                    if (profile != null && profile == failListForProfile) {
                        throw GatewayRpcException("this profile is unavailable")
                    }
                    Json.parseToJsonElement(sessionListJson(sessionListByProfile[profile].orEmpty()))
                }
                "session.create" -> Json.parseToJsonElement(
                    """{"session_id":"runtime-new","stored_session_id":"durable-new","session":{"id":"durable-new","title":"New"}}""",
                )
                "session.resume" -> Json.parseToJsonElement("""{"session_id":"runtime-1"}""")
                "session.history" -> Json.parseToJsonElement("""{"messages":[],"count":0}""")
                else -> Json.parseToJsonElement("{}")
            }
        }

        override fun close() = eventChannel.close().let { }

        /** Exactly the fields `methods_session.py:204-214` emits, and no others. */
        private fun sessionListJson(ids: List<String>): String {
            val body = ids.joinToString(",") { id ->
                """{"id":"$id","title":"$id","preview":"","started_at":0,"message_count":1,"source":"desktop"}"""
            }
            return """{"sessions":[$body]}"""
        }


    }
}
