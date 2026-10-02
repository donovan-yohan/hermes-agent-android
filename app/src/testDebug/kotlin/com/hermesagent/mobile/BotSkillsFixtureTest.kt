package com.hermesagent.mobile

import kotlinx.coroutines.test.*
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BotSkillsFixtureTest {
    @Test fun `loading and pending keep the twenty second production deadline`() = runTest {
        for (name in listOf("skills-loading", "skills-pending")) {
            val fixture = BotSkillsFixture(name, backgroundScope)
            backgroundScope.launchStage(fixture); runCurrent()
            assertTrue(fixture.vm.state.value.loading || fixture.vm.state.value.busy)
            advanceTimeBy(19_999); runCurrent()
            assertTrue(fixture.vm.state.value.loading || fixture.vm.state.value.busy)
            advanceTimeBy(1); runCurrent()
            assertFalse(fixture.vm.state.value.loading)
            assertFalse(fixture.vm.state.value.busy)
            assertNotNull(fixture.vm.state.value.message)
            assertFalse(fixture.vm.state.value.message!!.contains("saved", ignoreCase = true))
            fixture.vm.close()
        }
    }
    @Test fun `capture states stage real reads toggles and named readback`() = runTest {
        for (name in BotSkillsFixture.STATES) {
            val fixture = BotSkillsFixture(name, backgroundScope)
            backgroundScope.launchStage(fixture)
            runCurrent()
            val state = fixture.vm.state.value
            assertNotNull(state.ticket)
            when (name) {
                "skills-loading" -> assertTrue(state.loading)
                "skills-pending" -> { assertTrue(state.busy); assertEquals(1, fixture.calls.count { it == "PUT" }) }
                "skills-saved", "skills-reopened" -> {
                    assertFalse(state.busy); assertTrue(state.rows!!.first().enabled)
                    assertEquals(1, fixture.calls.count { it == "PUT" })
                }
                "skills-essential", "skills-refused", "skills-unconfirmed" -> {
                    assertFalse(state.busy); assertTrue(state.message!!.startsWith("Change not confirmed"))
                    assertEquals(1, fixture.calls.count { it == "PUT" })
                }
                "skills-error", "skills-unavailable" -> { assertNull(state.rows); assertNotNull(state.message) }
                "skills-empty" -> assertEquals(emptyList<Any>(), state.rows)
                else -> { assertNotNull(state.rows); assertFalse(state.busy) }
            }
            fixture.vm.close()
        }
    }
}
private fun kotlinx.coroutines.CoroutineScope.launchStage(fixture: BotSkillsFixture) =
    launch { fixture.stage() }
