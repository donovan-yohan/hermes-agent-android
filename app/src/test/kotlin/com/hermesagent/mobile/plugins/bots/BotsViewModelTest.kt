package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostEvent
import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The roster surface's state: filter composition, section filing, the
 * hidden/pinned treatment and the honest states.
 */
class BotsViewModelTest {

    private val now = 1_800_000_000_000L

    private class ScriptedHost(var result: PluginHostResult) : PluginHost {
        /** How many reads actually reached this endpoint. */
        var reads = 0

        override suspend fun request(method: String, params: JsonObject): PluginHostResult {
            reads += 1
            return result
        }

        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }

    /** A host whose read stays in flight until the test releases it. */
    private class GatedHost(
        private val gate: CompletableDeferred<Unit>,
        var result: PluginHostResult,
    ) : PluginHost {
        /** How many reads actually reached this endpoint. */
        var reads = 0

        override suspend fun request(method: String, params: JsonObject): PluginHostResult {
            reads += 1
            gate.await()
            return result
        }

        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }

    /**
     * A scope on this test's own scheduler, and deliberately not
     * `backgroundScope`: `advanceUntilIdle` never runs background-scope work,
     * so a ViewModel whose collectors these tests drive needs its own scope.
     */
    private fun TestScope.drivenScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

    private fun secondsAgo(seconds: Long): Long = (now / 1000L) - seconds

    private fun rosterJson(): String = """
        {
          "profiles": [
            {"name": "default", "display_name": "Hermes",
             "last_session": {"last_active": ${secondsAgo(10)}, "preview": "hello"}},
            {"name": "researcher", "description": "reads papers",
             "last_session": {"last_active": ${secondsAgo(3 * 24 * 3600)}, "preview": "papers"}},
            {"name": "writer",
             "last_session": {"last_active": ${secondsAgo(30L * 24 * 3600)}, "preview": "drafting"}}
          ]
        }
    """.trimIndent()

    private fun loadedHost() = ScriptedHost(PluginHostResult.Success(Json.parseToJsonElement(rosterJson())))

    /** A `profiles.list` answer with one row per name, for the switch's own fixture. */
    private fun namesRoster(vararg names: String): String =
        names.joinToString(prefix = """{"profiles": [""", postfix = "]}") { name -> """{"name": "$name"}""" }

    /** A ViewModel over the three-bot roster, already refreshed. */
    private suspend fun loadedViewModel(
        scope: CoroutineScope,
        metaByKey: Map<String, BotMeta> = emptyMap(),
        sections: List<BotSection> = emptyList(),
    ): BotsViewModel {
        val viewModel = BotsViewModel(
            repository = BotsPluginRepository(loadedHost()),
            scope = scope,
            clock = { now },
            sections = sections,
            metaByKey = metaByKey,
        )
        viewModel.refreshNow()
        return viewModel
    }

    @Test
    fun `the loaded roster is filed into user sections with Unassigned last`() = runTest {
        val researcherKey = BotRosterRow(name = "researcher").rosterKey
        val viewModel = loadedViewModel(
            scope = backgroundScope,
            sections = listOf(BotSection("s1", "Clients")),
            metaByKey = mapOf(researcherKey to BotMeta(sectionId = "s1")),
        )

        val state = viewModel.uiState.value
        assertEquals(BotsRosterPhase.Ready, state.phase)
        assertEquals(listOf("Clients", BotsRosterCopy.UNASSIGNED), state.sections.map { it.name })
        assertEquals(listOf("researcher"), state.sections[0].rows.map { it.name })
        assertEquals(listOf("default", "writer"), state.sections[1].rows.map { it.name })
        assertFalse(state.filteredToNothing)
    }

    @Test
    fun `an empty roster is the empty state`() = runTest {
        val host = ScriptedHost(PluginHostResult.Success(Json.parseToJsonElement("""{"profiles": []}""")))
        val viewModel = BotsViewModel(BotsPluginRepository(host), backgroundScope, clock = { now })

        viewModel.refreshNow()

        assertEquals(BotsRosterPhase.Empty, viewModel.uiState.value.phase)
    }

    @Test
    fun `search narrows the roster`() = runTest {
        val viewModel = loadedViewModel(backgroundScope)

        viewModel.setSearchQuery("papers")

        assertEquals(
            listOf("researcher"),
            viewModel.uiState.value.sections.flatMap { it.rows }.map { it.name },
        )
    }

    @Test
    fun `the query, the kind filter and the activity filter compose`() = runTest {
        val viewModel = loadedViewModel(backgroundScope)

        viewModel.setKindFilter(RosterKindFilter.Bots)
        viewModel.setActivityFilter(RosterActivityFilter.Active)
        assertEquals(
            listOf("default"),
            viewModel.uiState.value.sections.flatMap { it.rows }.map { it.name },
        )

        viewModel.setActivityFilter(RosterActivityFilter.Older)
        assertEquals(
            listOf("writer"),
            viewModel.uiState.value.sections.flatMap { it.rows }.map { it.name },
        )

        viewModel.setSearchQuery("researcher")
        assertTrue(viewModel.uiState.value.filteredToNothing)
        assertEquals(2, viewModel.uiState.value.presentation.activeFilterCount)
    }

    @Test
    fun `groups only selects nothing and clearing the filters restores the roster`() = runTest {
        val viewModel = loadedViewModel(backgroundScope)

        viewModel.setKindFilter(RosterKindFilter.Groups)
        assertTrue(viewModel.uiState.value.filteredToNothing)

        viewModel.clearFilters()
        val state = viewModel.uiState.value
        assertEquals(RosterKindFilter.All, state.kindFilter)
        assertEquals(RosterActivityFilter.All, state.activityFilter)
        assertEquals("", state.searchQuery)
        assertFalse(state.filteredToNothing)
    }

    @Test
    fun `a pinned row is marked for the row's pin glyph`() = runTest {
        val pinned = BotRosterRow(name = "writer").rosterKey
        val viewModel = loadedViewModel(
            scope = backgroundScope,
            metaByKey = mapOf(pinned to BotMeta(pinned = true)),
        )

        assertEquals(setOf(pinned), viewModel.uiState.value.pinnedKeys)
    }

    @Test
    fun `hiding every bot is its own state and revealing them draws them`() = runTest {
        val meta = listOf("default", "researcher", "writer")
            .associate { BotRosterRow(name = it).rosterKey to BotMeta(hidden = true) }
        val viewModel = loadedViewModel(scope = backgroundScope, metaByKey = meta)

        val hidden = viewModel.uiState.value
        assertTrue(hidden.presentation.allBotsHidden)
        assertEquals(emptyList<BotSectionBlock>(), hidden.sections)
        assertTrue(hidden.presentation.showHiddenSection)
        assertEquals(1, hidden.hiddenSections.size)

        viewModel.setHiddenExpanded(true)
        assertTrue(viewModel.uiState.value.presentation.showHiddenRows)
    }

    @Test
    fun `a refusal with no roster is the error state and keeps this app's sentence`() = runTest {
        val host = ScriptedHost(PluginHostResult.Refused(500, "The Gateway refused that request."))
        val viewModel = BotsViewModel(
            repository = BotsPluginRepository(host),
            scope = drivenScope(),
            clock = { now },
            connected = MutableStateFlow(true),
        )

        viewModel.refreshNow()

        val state = viewModel.uiState.value
        assertEquals(BotsRosterPhase.Refused, state.phase)
        assertEquals("The Gateway refused that request.", state.safeMessage)
    }

    @Test
    fun `presentation changes preserve a rosterless refusal`() = runTest {
        val host = ScriptedHost(PluginHostResult.Refused(500, "The Gateway refused that request."))
        val viewModel = BotsViewModel(
            repository = BotsPluginRepository(host),
            scope = backgroundScope,
            clock = { now },
            connected = MutableStateFlow(true),
        )

        viewModel.refreshNow()
        viewModel.setSearchQuery("researcher")

        val state = viewModel.uiState.value
        assertEquals(BotsRosterPhase.Refused, state.phase)
        assertEquals("The Gateway refused that request.", state.safeMessage)
    }

    @Test
    fun `a refusal with no connection waits for the gateway instead of failing`() = runTest {
        val host = ScriptedHost(PluginHostResult.Refused(0, "Reconnect to the Gateway and try again."))
        val connected = MutableStateFlow(false)
        val viewModel = BotsViewModel(
            repository = BotsPluginRepository(host),
            scope = drivenScope(),
            clock = { now },
            connected = connected,
        )

        viewModel.refreshNow()

        // Nothing was asked of a Gateway, so this is not the person's failure
        // to act on: it is the state the copy "Waiting for the gateway
        // connection…" was written for.
        assertEquals(BotsRosterPhase.Loading, viewModel.uiState.value.phase)
        assertNull(viewModel.uiState.value.safeMessage)
        assertFalse(viewModel.uiState.value.connectionUp)
    }

    @Test
    fun `a refusal uses the live connection instead of the queued ui mirror`() = runTest {
        val host = ScriptedHost(PluginHostResult.Refused(0, "Reconnect to the Gateway and try again."))
        val connected = MutableStateFlow(true)
        val viewModel = BotsViewModel(
            repository = BotsPluginRepository(host),
            scope = drivenScope(),
            clock = { now },
            connected = connected,
        )
        runCurrent()
        assertTrue(viewModel.uiState.value.connectionUp)

        // The transport closes synchronously, while the UI collector may still
        // be queued. Classification must read the transport truth directly.
        connected.value = false
        viewModel.refreshNow()

        assertEquals(BotsRosterPhase.Loading, viewModel.uiState.value.phase)
        assertNull(viewModel.uiState.value.safeMessage)
    }

    @Test
    fun `the connection arriving is what reads the roster`() = runTest {
        val connected = MutableStateFlow(false)
        val viewModel = BotsViewModel(
            repository = BotsPluginRepository(loadedHost()),
            scope = drivenScope(),
            clock = { now },
            connected = connected,
        )

        runCurrent()
        assertEquals(BotsRosterPhase.Loading, viewModel.uiState.value.phase)

        connected.value = true
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(BotsRosterPhase.Ready, state.phase)
        assertTrue(state.connectionUp)
        assertEquals(3, state.sections.flatMap { it.rows }.size)
    }

    @Test
    fun `a read that arrives mid-read is owed rather than dropped`() = runTest {
        // The cold start's own read can still be on the wire when the
        // connection lands; that edge's read is the one that matters, so it
        // must not be dropped as a duplicate.
        val gate = CompletableDeferred<Unit>()
        val host = GatedHost(gate, PluginHostResult.Refused(0, "Reconnect to the Gateway and try again."))
        val connected = MutableStateFlow(false)
        val viewModel = BotsViewModel(
            repository = BotsPluginRepository(host),
            scope = drivenScope(),
            clock = { now },
            connected = connected,
        )

        viewModel.refresh()
        runCurrent()

        // The connection lands while that first read is still in flight.
        host.result = PluginHostResult.Success(Json.parseToJsonElement(rosterJson()))
        connected.value = true
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(BotsRosterPhase.Ready, viewModel.uiState.value.phase)
    }

    @Test
    fun `a failed refresh keeps the last good roster`() = runTest {
        val host = loadedHost()
        val viewModel = BotsViewModel(BotsPluginRepository(host), backgroundScope, clock = { now })

        viewModel.refreshNow()
        host.result = PluginHostResult.Refused(0, "The Gateway did not answer in time.")
        viewModel.refreshNow()

        val state = viewModel.uiState.value
        assertEquals(BotsRosterPhase.Ready, state.phase)
        assertEquals(3, state.sections.flatMap { it.rows }.size)
        assertEquals("The Gateway did not answer in time.", state.safeMessage)
    }

    @Test
    fun `a keystroke does not dismiss a stale notice that is still true`() = runTest {
        val host = loadedHost()
        val viewModel = BotsViewModel(BotsPluginRepository(host), backgroundScope, clock = { now })

        viewModel.refreshNow()
        host.result = PluginHostResult.Refused(0, "The Gateway did not answer in time.")
        viewModel.refreshNow()
        assertTrue(viewModel.uiState.value.stale)

        // Re-deriving the same held list with a query is not an answer from the
        // Gateway, so the banner stays until one arrives.
        viewModel.setSearchQuery("res")

        assertTrue(viewModel.uiState.value.stale)

        host.result = PluginHostResult.Success(Json.parseToJsonElement(rosterJson()))
        viewModel.refreshNow()

        assertFalse(viewModel.uiState.value.stale)
    }

    @Test
    fun `an endpoint switch drops the held roster and the notice that described it`() = runTest {
        val host = loadedHost()
        val connected = MutableStateFlow(true)
        val endpoint = MutableStateFlow(0L)
        val attention = BotAttentionStore { now }
        val viewModel = BotsViewModel(
            repository = BotsPluginRepository(host),
            scope = drivenScope(),
            clock = { now },
            connected = connected,
            endpointGeneration = endpoint,
            attention = attention,
        )
        viewModel.refreshNow()
        host.result = PluginHostResult.Refused(0, "The Gateway did not answer in time.")
        viewModel.refreshNow()
        assertEquals(3, viewModel.uiState.value.sections.flatMap { it.rows }.size)
        assertTrue(viewModel.uiState.value.stale)

        // A badge the previous machine's row is wearing is the same class of
        // endpoint-scoped copy as the rows themselves — keyed by roster key
        // alone, so it must not paint on a same-named bot on the new endpoint.
        viewModel.noteAttention("researcher", "agent_blocked")
        assertEquals(setOf("researcher"), viewModel.uiState.value.attentionByKey.keys)

        // The switch, in the order the app performs it: the leg goes down first,
        // then the one wholesale clear runs. `resetForEndpointSwitch` is that
        // clear's only caller, and this is the generation the door publishes.
        connected.value = false
        advanceUntilIdle()
        endpoint.value = 1L

        // A presentation action can beat the generation collector by one
        // dispatcher turn. The public entry point owns the same boundary, so
        // it drops the old machine synchronously rather than rendering it once.
        viewModel.setSearchQuery("beta")

        // No row from the machine we left survives the boundary, and neither
        // does the banner that said those rows were old. The surface waits
        // rather than claiming the new Gateway has no bots.
        val dropped = viewModel.uiState.value
        assertEquals(BotsRosterPhase.Loading, dropped.phase)
        assertEquals(emptyList<BotSectionBlock>(), dropped.sections)
        assertNull(dropped.safeMessage)
        assertFalse(dropped.stale)
        assertTrue(dropped.attentionByKey.isEmpty())
        assertTrue(attention.entries.value.isEmpty())

        // The search box survives the switch, and another keystroke over the
        // dropped roster must not turn "nothing has been asked of this endpoint
        // yet" into "it answered, and it has no bots".
        advanceUntilIdle()
        viewModel.setSearchQuery("beta-")
        assertEquals(BotsRosterPhase.Loading, viewModel.uiState.value.phase)
        assertEquals(emptyList<BotSectionBlock>(), viewModel.uiState.value.sections)
        viewModel.setSearchQuery("")

        // The new endpoint answers for itself.
        host.result = PluginHostResult.Success(Json.parseToJsonElement(namesRoster("beta-only")))
        connected.value = true
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(BotsRosterPhase.Ready, state.phase)
        assertEquals(listOf("beta-only"), state.sections.flatMap { it.rows }.map { it.name })
    }

    @Test
    fun `a direct refresh drops old rows before the generation collector runs`() = runTest {
        val host = loadedHost()
        val endpoint = MutableStateFlow(0L)
        val viewModel = BotsViewModel(
            repository = BotsPluginRepository(host),
            scope = drivenScope(),
            clock = { now },
            connected = MutableStateFlow(true),
            endpointGeneration = endpoint,
        )
        viewModel.refreshNow()
        assertEquals(3, viewModel.uiState.value.sections.flatMap { it.rows }.size)

        endpoint.value = 1L
        host.result = PluginHostResult.Refused(0, "The Gateway did not answer in time.")
        // Do not run the collector: this direct call is intentionally first.
        viewModel.refreshNow()

        val state = viewModel.uiState.value
        assertEquals(BotsRosterPhase.Refused, state.phase)
        assertEquals(emptyList<BotSectionBlock>(), state.sections)
        assertEquals("The Gateway did not answer in time.", state.safeMessage)
    }

    @Test
    fun `presentation changes cannot turn a dead-leg wait into an empty answer`() = runTest {
        val host = loadedHost()
        val connected = MutableStateFlow(true)
        val viewModel = BotsViewModel(
            repository = BotsPluginRepository(host),
            scope = drivenScope(),
            clock = { now },
            connected = connected,
        )
        viewModel.refreshNow()
        // A live query and filter keep both controls reachable after the roster
        // becomes empty.
        viewModel.setSearchQuery("researcher")
        viewModel.setKindFilter(RosterKindFilter.Bots)
        host.result = PluginHostResult.Success(Json.parseToJsonElement("""{"profiles": []}"""))
        viewModel.refreshNow()
        assertEquals(BotsRosterPhase.Empty, viewModel.uiState.value.phase)

        connected.value = false
        runCurrent()
        host.result = PluginHostResult.Refused(0, "Reconnect to the Gateway and try again.")
        viewModel.surfaceResumed()
        advanceUntilIdle()
        assertEquals(BotsRosterPhase.Loading, viewModel.uiState.value.phase)

        viewModel.setSearchQuery("researchers")
        assertEquals(BotsRosterPhase.Loading, viewModel.uiState.value.phase)
        viewModel.setKindFilter(RosterKindFilter.Groups)
        assertEquals(BotsRosterPhase.Loading, viewModel.uiState.value.phase)
    }

    @Test
    fun `a dead leg replaces a rosterless refusal with the waiting state`() = runTest {
        val host = ScriptedHost(PluginHostResult.Refused(500, "The Gateway refused that request."))
        val connected = MutableStateFlow(true)
        val viewModel = BotsViewModel(
            repository = BotsPluginRepository(host),
            scope = drivenScope(),
            clock = { now },
            connected = connected,
        )
        viewModel.refreshNow()
        assertEquals(BotsRosterPhase.Refused, viewModel.uiState.value.phase)

        connected.value = false
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(BotsRosterPhase.Loading, state.phase)
        assertNull(state.safeMessage)
        assertFalse(state.connectionUp)
    }

    @Test
    fun `a reconnect without an endpoint switch keeps the last good list and its banner`() = runTest {
        val host = loadedHost()
        val connected = MutableStateFlow(true)
        val viewModel = BotsViewModel(
            repository = BotsPluginRepository(host),
            scope = drivenScope(),
            clock = { now },
            connected = connected,
            endpointGeneration = MutableStateFlow(0L),
        )
        viewModel.refreshNow()

        // The leg drops and comes back on the same endpoint, which now refuses:
        // the rows, and the banner over them, are the whole point of this path —
        // nothing crossed to a different machine.
        connected.value = false
        advanceUntilIdle()
        host.result = PluginHostResult.Refused(0, "The Gateway did not answer in time.")
        connected.value = true
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(BotsRosterPhase.Ready, state.phase)
        assertEquals(3, state.sections.flatMap { it.rows }.size)
        assertTrue(state.stale)
        assertEquals("The Gateway did not answer in time.", state.safeMessage)
    }

    @Test
    fun `an endpoint switch with the leg still up drops the roster and reads the new endpoint`() = runTest {
        val host = loadedHost()
        // The leg does *not* drop here: `leaveLocked` calls `gateway.disconnect()`
        // and then `resetForEndpointSwitch()`, but the door's `connected` is its
        // own collector over the client slot, so the generation can legally move
        // while this door still reads as up. The drop is the generation's job,
        // not the edge's — a switch that only answered to `false` would keep the
        // previous machine's rows whenever that collector ran a turn late.
        val connected = MutableStateFlow(true)
        val endpoint = MutableStateFlow(0L)
        val viewModel = BotsViewModel(
            repository = BotsPluginRepository(host),
            scope = drivenScope(),
            clock = { now },
            connected = connected,
            endpointGeneration = endpoint,
        )
        viewModel.refreshNow()
        assertEquals(3, viewModel.uiState.value.sections.flatMap { it.rows }.size)
        assertEquals(1, host.reads)

        // The new endpoint refuses `profiles.list`, which is the card's own
        // scenario: were the old rows still held, this is the exact state that
        // painted them under "showing the last good list" indefinitely.
        host.result = PluginHostResult.Refused(0, "The Gateway did not answer in time.")
        endpoint.value = 1L
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // The refusal is this endpoint's own to report: no rows inherited from
        // the machine we left, and no banner claiming to be showing them.
        assertEquals(emptyList<BotSectionBlock>(), state.sections)
        assertFalse(state.stale)
        assertEquals(BotsRosterPhase.Refused, state.phase)
        assertEquals("The Gateway did not answer in time.", state.safeMessage)
        // Exactly one read for the new endpoint: the drop is not a read storm.
        assertEquals(2, host.reads)
    }

    @Test
    fun `an answer from the endpoint the device left is not adopted`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val host = GatedHost(gate, PluginHostResult.Success(Json.parseToJsonElement(rosterJson())))
        // Not connected yet, so the only read in this test is the one the test
        // starts: a queued read would belong to whichever endpoint is current
        // when it finally runs, which would hide what is being pinned here.
        val connected = MutableStateFlow(false)
        val endpoint = MutableStateFlow(0L)
        val viewModel = BotsViewModel(
            repository = BotsPluginRepository(host),
            scope = drivenScope(),
            clock = { now },
            connected = connected,
            endpointGeneration = endpoint,
        )

        viewModel.refresh()
        runCurrent()
        assertEquals(1, host.reads)

        // The switch lands while that read is on the wire, and the endpoint we
        // left answers afterwards.
        endpoint.value = 1L
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        // Nothing has been asked of the new endpoint, so the surface is waiting
        // — holding none of the previous machine's rows.
        assertEquals(1, host.reads)
        val state = viewModel.uiState.value
        assertEquals(BotsRosterPhase.Loading, state.phase)
        assertEquals(emptyList<BotSectionBlock>(), state.sections)
        assertFalse(state.connectionUp)
    }

    @Test
    fun `a gateway without profiles dot list is its own state`() = runTest {
        val host = ScriptedHost(PluginHostResult.UnavailableOnGateway)
        val viewModel = BotsViewModel(BotsPluginRepository(host), backgroundScope, clock = { now })

        viewModel.refreshNow()

        val state = viewModel.uiState.value
        assertEquals(BotsRosterPhase.UnavailableOnGateway, state.phase)
        assertNull(state.safeMessage)
    }

    @Test
    fun `presentation changes preserve an unavailable roster method`() = runTest {
        val host = ScriptedHost(PluginHostResult.UnavailableOnGateway)
        val viewModel = BotsViewModel(BotsPluginRepository(host), backgroundScope, clock = { now })

        viewModel.refreshNow()
        viewModel.setKindFilter(RosterKindFilter.Groups)

        val state = viewModel.uiState.value
        assertEquals(BotsRosterPhase.UnavailableOnGateway, state.phase)
        assertNull(state.safeMessage)
    }

    @Test
    fun `an unavailable roster method clears a held roster permanently`() = runTest {
        val host = loadedHost()
        val viewModel = BotsViewModel(BotsPluginRepository(host), backgroundScope, clock = { now })
        viewModel.refreshNow()
        assertEquals(3, viewModel.uiState.value.sections.flatMap { it.rows }.size)

        host.result = PluginHostResult.UnavailableOnGateway
        viewModel.refreshNow()
        viewModel.setSearchQuery("researcher")

        val state = viewModel.uiState.value
        assertEquals(BotsRosterPhase.UnavailableOnGateway, state.phase)
        assertEquals(emptyList<BotSectionBlock>(), state.sections)
        assertNull(state.safeMessage)
    }

    @Test
    fun `only a classified failure badges, and a good turn clears it`() = runTest {
        val viewModel = loadedViewModel(backgroundScope)
        val key = BotRosterRow(name = "researcher").rosterKey

        viewModel.noteAttention(key, "429 Too Many Requests")
        assertTrue(viewModel.uiState.value.attentionByKey.isEmpty())

        viewModel.noteAttention(key, "401 Unauthorized")
        val badge = viewModel.uiState.value.attentionByKey[key]
        assertNotNull(badge)
        assertEquals(BotAttentionClass.ProviderAuthOrAccess, badge!!.reason)

        viewModel.clearAttention(key)
        assertTrue(viewModel.uiState.value.attentionByKey.isEmpty())
    }
}
