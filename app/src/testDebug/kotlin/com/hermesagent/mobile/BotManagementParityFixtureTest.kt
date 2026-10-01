package com.hermesagent.mobile

import com.hermesagent.mobile.plugins.bots.BotManagementDialog
import com.hermesagent.mobile.plugins.bots.BotModelSelection
import com.hermesagent.mobile.plugins.bots.BotModelProvider
import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.serialization.json.*
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

    @Test fun loadingAndErrorComeFromRealInventoryAndNeverWrite() = runTest {
        for (state in listOf("bot-model-loaded", "bot-model-manual", "bot-model-inventory-loading", "bot-model-inventory-error")) {
            val fixture = BotManagementParityFixture(backgroundScope)
            runCurrent(); fixture.open(state); runCurrent()
            val editor = fixture.model.model
            assertFalse(editor.state.value.loading)
            assertEquals(state == "bot-model-inventory-loading", editor.state.value.inventoryLoading)
            assertEquals(state == "bot-model-inventory-error", editor.state.value.inventoryMessage != null)
            assertEquals(buildJsonObject {
                put("profile", "synthetic-planner"); put("include_unconfigured", true); put("explicit_only", false)
            }, fixture.host.requests.single { it.method == "model.options" }.params)
            editor.update(editor.state.value, BotModelSelection("synthetic-provider", "synthetic-planner-v2"))
            assertTrue(editor.state.value.canSave)
            assertFalse(fixture.host.calls.contains("profiles.configure"))
            if (state == "bot-model-inventory-loading") {
                advanceTimeBy(20_001); runCurrent()
                assertFalse(editor.state.value.inventoryLoading)
                assertNotNull(editor.state.value.inventoryMessage)
                assertEquals("synthetic-planner-v2", editor.state.value.draft.model)
            }
        }
    }

    @Test fun warningConsentAndReadbackAreProductionActionsWithExactPayloads() = runTest {
        val fixture = BotManagementParityFixture(backgroundScope)
        runCurrent(); fixture.open("bot-model-saved"); runCurrent()
        val editor = fixture.model.model
        val original = editor.state.value.original
        editor.update(editor.state.value, BotModelSelection("synthetic-provider", "synthetic-planner-v2"))
        assertFalse(fixture.host.calls.contains("profiles.configure"))
        editor.save(editor.state.value); runCurrent()
        assertNotNull(editor.state.value.warning)
        assertEquals(original, fixture.host.authoritative)
        assertEquals(original, editor.state.value.original)
        val pair = buildJsonObject {
            put("name", "synthetic-planner"); put("provider", "synthetic-provider"); put("model", "synthetic-planner-v2")
        }
        assertEquals(pair, fixture.host.requests.last().params)
        val gate = CompletableDeferred<Unit>()
        fixture.host.readbackGate = gate
        editor.confirm(editor.state.value); runCurrent()
        assertEquals(listOf("profiles.configure", "profiles.describe"), fixture.host.calls.takeLast(2))
        assertEquals(JsonObject(pair + ("confirm_expensive_model" to JsonPrimitive(true))),
            fixture.host.requests.filter { it.method == "profiles.configure" }.last().params)
        assertEquals(buildJsonObject { put("name", "synthetic-planner") }, fixture.host.requests.last().params)
        assertTrue(editor.state.value.busy)
        assertNull(editor.state.value.message)
        assertEquals(original, editor.state.value.original)
        gate.complete(Unit); runCurrent()
        assertEquals("Model saved.", editor.state.value.message)
        assertEquals(fixture.host.authoritative, editor.state.value.original)
    }

    @Test fun stagedStatesUseGuardedActionsAndAllOtherWritesRefuse() = runTest {
        for (state in listOf("bot-model-confirmation", "bot-model-saved", "bot-model-save-refused")) {
            val fixture = BotManagementParityFixture(backgroundScope)
            runCurrent(); fixture.open(state); runCurrent()
            assertFalse(fixture.host.calls.contains("profiles.configure"))
            backgroundScope.launch { fixture.stage(state) }; runCurrent()
            val result = fixture.model.model.state.value
            when (state) {
                "bot-model-confirmation" -> { assertNotNull(result.warning); assertEquals("synthetic-planner-v1", fixture.host.authoritative.model) }
                "bot-model-saved" -> assertEquals("Model saved.", result.message)
                else -> { assertTrue(result.consumed); assertEquals("synthetic-planner-v1", fixture.host.authoritative.model) }
            }
            for (method in listOf("profiles.create", "profiles.delete", "model.save_key", "model.set"))
                assertEquals(PluginHostResult.UnavailableOnGateway, fixture.host.request(method, buildJsonObject {}))
            assertEquals(PluginHostResult.UnavailableOnGateway, fixture.host.request("profiles.configure", buildJsonObject {
                put("name", "synthetic-planner"); put("description", "not a model-only write")
            }))
        }
    }

    @Test fun hostRejectsConsentWithoutWarningAndNonAllowlistedPayloads() = runTest {
        val host = BotManagementFixtureHost()
        host.scenario = "bot-model-saved"
        val pair = buildJsonObject { put("name", "synthetic-planner"); put("provider", "synthetic-provider"); put("model", "synthetic-planner-v2") }
        val consent = JsonObject(pair + ("confirm_expensive_model" to JsonPrimitive(true)))
        assertEquals(PluginHostResult.UnavailableOnGateway, host.request("profiles.configure", consent))
        for (extra in listOf("description", "soul", "api_key"))
            assertEquals(PluginHostResult.UnavailableOnGateway, host.request("profiles.configure", JsonObject(pair + (extra to JsonPrimitive("synthetic")))))
        assertEquals(PluginHostResult.UnavailableOnGateway, host.request("profiles.configure", JsonObject(pair + ("name" to JsonPrimitive("foreign")))))
        assertEquals("synthetic-planner-v1", host.authoritative.model)
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
