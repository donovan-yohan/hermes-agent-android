package com.hermesagent.mobile

import com.hermesagent.mobile.plugins.bots.BotManagementDialog
import com.hermesagent.mobile.plugins.bots.BotModelSelection
import com.hermesagent.mobile.plugins.bots.BotModelProvider
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
        assertEquals(listOf("profiles.describe", "model.options", "profiles.describe", "profiles.list"), fixture.host.calls)
        val modelState = fixture.model.model.state.value
        assertEquals(fixture.model.state.value.target, modelState.ticket?.target)
        assertEquals(BotModelSelection("synthetic-provider", "synthetic-planner-v1"), modelState.original)
        assertEquals(modelState.original, modelState.draft)
        assertEquals(listOf(BotModelProvider("synthetic-provider", "Synthetic Provider", emptyList(),
            listOf("synthetic-planner-v1", "synthetic-planner-v2"))), modelState.providers)
        assertFalse(modelState.loading)
        assertFalse(modelState.inventoryLoading)
        assertNull(modelState.message)
        assertNull(modelState.inventoryMessage)
        assertTrue(modelState.editable)
        assertFalse(modelState.canSave)
        fixture.model.submit()
        runCurrent()
        assertTrue(fixture.model.state.value.consumed)
        assertNotNull(fixture.model.state.value.message)
        assertEquals("profiles.configure", fixture.host.calls.last())
    }
    @Test fun modelMutationRefusesWithoutChangingAuthoritativeSelectionOrReplaying() = runTest {
        val fixture = BotManagementParityFixture(backgroundScope)
        runCurrent()
        fixture.open("bot-edit")
        runCurrent()
        val editor = fixture.model.model
        val original = editor.state.value.original
        assertNotNull(original)
        editor.update(editor.state.value, BotModelSelection("synthetic-provider", "synthetic-planner-v2"))
        assertTrue(editor.state.value.canSave)
        editor.save(editor.state.value)
        runCurrent()
        assertTrue(editor.state.value.consumed)
        assertFalse(editor.state.value.busy)
        assertFalse(editor.state.value.canSave)
        assertEquals(original, editor.state.value.original)
        assertEquals("The model change could not be confirmed. Close and read it again before retrying.",
            editor.state.value.message)
        assertEquals("profiles.configure", fixture.host.calls.last())
        editor.save(editor.state.value)
        runCurrent()
        assertEquals(1, fixture.host.calls.count { it == "profiles.configure" })
        fixture.model.close()
        fixture.open("bot-edit")
        runCurrent()
        assertEquals(original, editor.state.value.original)
        assertEquals(original, editor.state.value.draft)
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
