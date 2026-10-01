package com.hermesagent.mobile

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BotToolsetsFixtureTest {
    @Test fun `all declared settled states use production actions and exact toolsets only transport`() = runTest {
        for (scenario in BotToolsetsFixture.STATES - "loading") {
            val fixture = BotToolsetsFixture(scenario, backgroundScope)
            val stage = launch { fixture.stage() }; runCurrent(); stage.join()
            val state = fixture.vm.state.value
            assertFalse(scenario, state.loading); assertFalse(scenario, state.busy)
            val writes = fixture.calls.filter { it.first == "profiles.configure" }
            assertEquals(scenario, if (scenario in setOf("saved", "restored", "unconfirmed")) 1 else 0, writes.size)
            assertTrue(writes.all { it.second.keys == setOf("name", "enabled_toolsets") })
            when (scenario) {
                "saved" -> { assertEquals("Toolsets saved.", state.message); assertTrue(state.original!!.pinned) }
                "restored" -> { assertEquals("Defaults restored.", state.message); assertFalse(state.original!!.pinned); assertEquals(setOf("terminal"), state.draft) }
                "unconfirmed" -> { assertTrue(state.consumed); assertFalse(state.canSave); assertEquals(setOf("web", "terminal"), fixture.selected) }
                "empty-selection" -> { assertTrue(state.draft.isEmpty()); assertFalse(state.canSave) }
                "reset-confirmation" -> assertTrue(state.confirmDefaults)
                "error" -> assertNull(state.original)
                "empty" -> assertTrue(state.original!!.rows.isEmpty())
            }
        }
    }
    @Test fun `loading is a held real describe with a real deadline not painted flags`() = runTest {
        val fixture = BotToolsetsFixture("loading", backgroundScope)
        fixture.stage(); runCurrent()
        assertTrue(fixture.vm.state.value.loading)
        assertEquals(listOf("profiles.describe"), fixture.calls.map { it.first })
        advanceTimeBy(20_001); runCurrent()
        assertFalse(fixture.vm.state.value.loading)
        assertNull(fixture.vm.state.value.original)
    }
}
