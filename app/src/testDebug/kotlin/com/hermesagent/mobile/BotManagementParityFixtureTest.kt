package com.hermesagent.mobile

import com.hermesagent.mobile.plugins.bots.BotManagementDialog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BotManagementParityFixtureTest {
    @Test fun editUsesProductionDescribeAndMutationRefusesSafely() = runTest {
        val fixture = BotManagementParityFixture(backgroundScope)
        runCurrent()
        fixture.open("bot-edit")
        runCurrent()
        assertEquals(BotManagementDialog.Edit, fixture.model.state.value.dialog)
        assertEquals("Review synthetic release plans.", fixture.model.state.value.draft.soul)
        assertEquals(listOf("profiles.describe", "profiles.list"), fixture.host.calls)
        fixture.model.submit()
        runCurrent()
        assertTrue(fixture.model.state.value.consumed)
        assertNotNull(fixture.model.state.value.message)
        assertEquals("profiles.configure", fixture.host.calls.last())
    }
    @Test fun statesOpenRealManagementDialogs() = runTest {
        val fixture = BotManagementParityFixture(backgroundScope)
        runCurrent()
        for ((wire, dialog) in mapOf("bot-new" to BotManagementDialog.New,
            "bot-duplicate" to BotManagementDialog.Duplicate, "bot-move" to BotManagementDialog.Move,
            "bot-section" to BotManagementDialog.Section)) {
            fixture.open(wire)
            assertEquals(dialog, fixture.model.state.value.dialog)
            assertFalse(fixture.model.state.value.consumed)
        }
    }
}
