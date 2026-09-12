package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostEvent
import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.coroutines.CoroutineScope
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
        override suspend fun request(method: String, params: JsonObject): PluginHostResult = result
        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }

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
        val viewModel = BotsViewModel(BotsPluginRepository(host), backgroundScope, clock = { now })

        viewModel.refreshNow()

        val state = viewModel.uiState.value
        assertEquals(BotsRosterPhase.Refused, state.phase)
        assertEquals("The Gateway refused that request.", state.safeMessage)
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
    fun `a gateway without profiles dot list is its own state`() = runTest {
        val host = ScriptedHost(PluginHostResult.UnavailableOnGateway)
        val viewModel = BotsViewModel(BotsPluginRepository(host), backgroundScope, clock = { now })

        viewModel.refreshNow()

        val state = viewModel.uiState.value
        assertEquals(BotsRosterPhase.UnavailableOnGateway, state.phase)
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
