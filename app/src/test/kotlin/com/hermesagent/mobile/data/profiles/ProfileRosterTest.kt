package com.hermesagent.mobile.data.profiles

import com.hermesagent.mobile.data.gateway.GatewayEvent
import com.hermesagent.mobile.data.gateway.GatewayRpcClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `profiles.list` parsing and the roster's authority rules, over the payload
 * `tui_gateway/methods_profiles.py:196-246` builds at
 * `29112bef099274229cadff79cdff7bf7b99c4b77`.
 *
 * Every path or name in these fixtures is invented; nothing here corresponds to
 * a real host, profile or person.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileRosterTest {

    @Test
    fun `parses the pinned profiles list payload`() {
        val rows = requireNotNull(parseProfileList(json(PROFILES_LIST)))

        assertEquals(listOf("default", "lab"), rows.map(HermesProfile::name))
        val default = rows[0]
        assertTrue(default.isDefault)
        assertEquals("/example/home/.hermes", default.path)
        assertEquals("a-model", default.model)
        assertEquals("a-provider", default.provider)
        assertEquals(7, default.skillCount)
        // profiles.list never sends has_env; only the REST twin does
        // (hermes_cli/web_server.py:14475). Absent must read as false, not true.
        assertFalse(default.hasEnv)

        val lab = rows[1]
        assertEquals("Lab bench", lab.label)
        assertFalse(lab.isDefault)
        assertNull(lab.model)
        assertEquals("#3355ff", lab.uiMetaColor)
        assertTrue(lab.hasAvatar)
    }

    @Test
    fun `a row without a name is dropped rather than invented`() {
        val rows = requireNotNull(parseProfileList(json("""{"profiles":[{"path":"/x"},{"name":"lab"}]}""")))

        assertEquals(listOf("lab"), rows.map(HermesProfile::name))
    }

    @Test
    fun `a malformed envelope keeps the caller's last good roster`() {
        assertNull(parseProfileList(json("""{"ok":true}""")))
        assertNull(parseProfileList(json("""[]""")))
    }

    @Test
    fun `the label falls back to the canonical name`() {
        assertEquals("lab", HermesProfile(name = "lab").label)
        assertEquals("lab", HermesProfile(name = "lab", displayName = "  ").label)
    }

    @Test
    fun `a cleared field stays cleared rather than being resurrected`() {
        val cache = ProfileRosterCache()
        cache.publish(cache.currentEpoch(), listOf(HermesProfile(name = "lab", model = "a-model", skillCount = 4)))

        // profiles.list emits every field of every row, so a row that now says
        // "no model" is the host clearing it, not an answer going quiet.
        cache.publish(cache.currentEpoch(), listOf(HermesProfile(name = "lab")))

        val row = cache.state.value.profiles.single()
        assertNull(row.model)
        assertEquals(0, row.skillCount)
    }

    @Test
    fun `the set of profiles is the answer's, not an accumulation`() {
        val cache = ProfileRosterCache()
        cache.publish(cache.currentEpoch(), listOf(HermesProfile(name = "lab"), HermesProfile(name = "work")))
        cache.publish(cache.currentEpoch(), listOf(HermesProfile(name = "lab")))

        assertEquals(listOf("lab"), cache.state.value.profiles.map(HermesProfile::name))
    }

    @Test
    fun `an unchanged answer keeps reference identity`() {
        val cache = ProfileRosterCache()
        cache.publish(cache.currentEpoch(), listOf(HermesProfile(name = "lab")))
        val first = cache.state.value

        cache.publish(cache.currentEpoch(), listOf(HermesProfile(name = "lab")))

        assertSame(first, cache.state.value)
    }

    @Test
    fun `an answer from a previous connection cannot clobber the roster`() {
        val cache = ProfileRosterCache()
        val strandedEpoch = cache.currentEpoch()
        cache.invalidate()
        cache.publish(cache.currentEpoch(), listOf(HermesProfile(name = "lab")))

        assertFalse(cache.publish(strandedEpoch, listOf(HermesProfile(name = "ghost"))))
        assertEquals(listOf("lab"), cache.state.value.profiles.map(HermesProfile::name))
    }

    @Test
    fun `losing the connection drops a roster that described a Gateway that is gone`() {
        val cache = ProfileRosterCache()
        val repository = GatewayProfileRepository(rpc = { null }, cache = cache)
        cache.publish(cache.currentEpoch(), listOf(HermesProfile(name = "lab")))

        repository.connectionChanged(GatewayProfileConnectionState.Gone)

        assertEquals(emptyList<HermesProfile>(), cache.state.value.profiles)
        assertFalse(cache.state.value.loaded)
    }

    @Test
    fun `a reconnect keeps the rail rather than blanking the only way out of a scope`() {
        val cache = ProfileRosterCache()
        val repository = GatewayProfileRepository(rpc = { null }, cache = cache)
        cache.publish(cache.currentEpoch(), listOf(HermesProfile(name = "lab")))
        val stranded = cache.currentEpoch()

        repository.connectionChanged(GatewayProfileConnectionState.Changed)

        assertEquals(listOf("lab"), cache.state.value.profiles.map(HermesProfile::name))
        assertTrue(cache.state.value.loaded)
        // The in-flight answer from the previous connection is still stranded.
        assertFalse(cache.publish(stranded, listOf(HermesProfile(name = "ghost"))))
    }

    @Test
    fun `the roster is read with include_sessions off and published once it answers`() = runTest(
        StandardTestDispatcher(),
    ) {
        val client = FakeRpcClient()
        val cache = ProfileRosterCache()
        val repository = GatewayProfileRepository(rpc = { client }, cache = cache)

        val refresh = launch { repository.refreshProfiles() }
        runCurrent()

        assertEquals("profiles.list", client.calls.single().first)
        assertEquals("false", client.calls.single().second["include_sessions"].toString())
        assertFalse(cache.state.value.loaded)

        client.answer.complete(json(PROFILES_LIST))
        refresh.join()

        assertTrue(cache.state.value.loaded)
        assertEquals(listOf("default", "lab"), cache.state.value.profiles.map(HermesProfile::name))
    }

    @Test
    fun `a failed refresh keeps the last good roster`() = runTest(StandardTestDispatcher()) {
        val cache = ProfileRosterCache()
        cache.publish(cache.currentEpoch(), listOf(HermesProfile(name = "lab")))
        val client = FakeRpcClient().apply { failure = IllegalStateException("timed out") }
        val repository = GatewayProfileRepository(rpc = { client }, cache = cache)

        assertFalse(repository.refreshProfiles())
        advanceUntilIdle()

        assertEquals(listOf("lab"), cache.state.value.profiles.map(HermesProfile::name))
        assertTrue(cache.state.value.loaded)
    }

    @Test
    fun `no Gateway means no request and no roster claim`() = runTest(StandardTestDispatcher()) {
        val cache = ProfileRosterCache()
        val repository = GatewayProfileRepository(rpc = { null }, cache = cache)

        assertFalse(repository.refreshProfiles())
        assertFalse(cache.state.value.loaded)
    }

    @Test
    fun `profiles list gets the slow lane's own budget, not the generic one`() {
        // tui_gateway/server.py:263-271 keeps profiles.list off the WS reader
        // thread precisely because it is seconds-scale; Desktop budgets the
        // same call at 60s (apps/desktop/src/hermes.ts:88).
        assertEquals(
            60_000L,
            com.hermesagent.mobile.data.gateway.gatewayRpcTimeoutMillis("profiles.list"),
        )
        assertEquals(
            15_000L,
            com.hermesagent.mobile.data.gateway.gatewayRpcTimeoutMillis("session.list"),
        )
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private class FakeRpcClient : GatewayRpcClient {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val answer = CompletableDeferred<JsonElement>()
        var failure: Throwable? = null
        override val events: Flow<GatewayEvent> = emptyFlow()

        override suspend fun request(method: String, params: JsonObject): JsonElement {
            calls += method to params
            failure?.let { throw it }
            return answer.await()
        }

        override fun close() = Unit
    }
}

/** One recorded `profiles.list` result, field for field with the pinned handler. */
private const val PROFILES_LIST = """
{
  "profiles": [
    {
      "name": "default",
      "path": "/example/home/.hermes",
      "is_default": true,
      "model": "a-model",
      "provider": "a-provider",
      "description": "",
      "display_name": "",
      "skill_count": 7,
      "has_avatar": false
    },
    {
      "name": "lab",
      "path": "/example/home/.hermes-lab",
      "is_default": false,
      "model": null,
      "provider": null,
      "description": "Bench experiments.",
      "display_name": "Lab bench",
      "skill_count": 0,
      "ui_meta": {"color": "#3355ff", "hermes-bots": {"chat": "x"}},
      "has_avatar": true
    }
  ],
  "bot_mode_protocol": true
}
"""
