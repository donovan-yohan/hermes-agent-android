package com.hermesagent.mobile.ui.cron

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class CronJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun close() { owner.cancel() }

    @Test fun uncertainExistingJobRequiresExplicitReviewBeforeEditingAgain() {
        val api = FakeCronApi().apply { createResult = com.hermesagent.mobile.data.cron.CronResult.Failure(com.hermesagent.mobile.data.cron.CronFailure.Refused, true) }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = owner)
        compose.setContent {
            val state by model.state.collectAsState()
            HermesTheme(selection = AppearanceSelection()) { CronScreen(state, model, {}) }
        }
        compose.runOnIdle { model.show("default"); model.select(api.job); model.edit(api.job) }
        compose.onNodeWithTag("cron-name").performTextReplacement("Kept draft")
        compose.onNodeWithText("Save changes").performClick()
        compose.onNodeWithText("Save changes").assertIsNotEnabled()
        compose.onAllNodesWithText("Review current job").onLast().performScrollTo().performClick()
        compose.onAllNodesWithText("I reviewed the current job").onLast().performScrollTo().performClick()
        compose.onNodeWithText("Save changes").assertIsEnabled()
        compose.runOnIdle {
            org.junit.Assert.assertEquals("Kept draft", model.state.value.editor!!.draft.name)
            org.junit.Assert.assertEquals(1, api.updates)
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { model.act(api.job, com.hermesagent.mobile.data.cron.CronAction.Pause) }
        compose.onNodeWithText("Pause").assertIsEnabled()
    }

    @Test fun failedBlueprintCatalogCanBeRetriedWithoutChangingProfile() {
        val api = FakeCronApi().apply { blueprintResult = com.hermesagent.mobile.data.cron.CronResult.Failure(com.hermesagent.mobile.data.cron.CronFailure.Unreachable) }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = owner)
        compose.setContent {
            val state by model.state.collectAsState()
            HermesTheme(selection = AppearanceSelection()) { CronScreen(state, model, {}) }
        }
        compose.runOnIdle { model.show("default") }
        compose.onNodeWithText("Failed to load blueprints").performScrollTo().assertExists()
        compose.runOnIdle { api.blueprintResult = null; api.blueprintRows = listOf(com.hermesagent.mobile.data.cron.CronBlueprint("retry", "Restored recipe")) }
        compose.onNodeWithText("Retry blueprints").performScrollTo().performClick()
        compose.onNodeWithText("Restored recipe").performScrollTo().assertExists()
    }

    @Test fun editorUsesRawDraftAndSevenChoicesThenWipesOnCancel() {
        val api = FakeCronApi()
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = owner)
        compose.setContent {
            val state by model.state.collectAsState()
            HermesTheme(selection = AppearanceSelection()) { CronScreen(state, model, {}) }
        }
        compose.runOnIdle { model.show("default"); model.edit() }
        compose.onNodeWithText("New cron job").assertIsDisplayed()
        compose.onNodeWithTag("cron-name").performTextInput("Status report")
        compose.onNodeWithTag("cron-prompt").performTextInput("  Exact draft  ")
        compose.onNodeWithContentDescription("Frequency").performScrollTo().performClick()
        listOf("Daily", "Weekdays", "Weekly", "Monthly", "Hourly", "Every 15 minutes", "Custom").forEach { compose.onAllNodesWithText(it).onLast().assertExists() }
        compose.onAllNodesWithText("Custom").onLast().performClick()
        compose.onNodeWithTag("cron-schedule").performScrollTo().performTextInput("every hour")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { org.junit.Assert.assertNull(model.state.value.editor); org.junit.Assert.assertTrue(api.creates.isEmpty()) }
    }

    @Test fun duplicateBlueprintTitlesSelectExactSecondKey() {
        val api = FakeCronApi().apply {
            blueprintRows = listOf(
                com.hermesagent.mobile.data.cron.CronBlueprint("first", "Same recipe", "First recipe"),
                com.hermesagent.mobile.data.cron.CronBlueprint("second", "Same recipe", "Second recipe"),
            )
        }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = owner)
        compose.setContent {
            val state by model.state.collectAsState()
            HermesTheme(selection = AppearanceSelection()) { CronScreen(state, model, {}) }
        }
        compose.runOnIdle { model.show("default"); model.edit() }
        compose.onNodeWithContentDescription("Start from").performScrollTo().performClick()
        compose.onAllNodesWithText("Same recipe").onLast().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals("second", model.state.value.editor?.blueprintKey) }
        compose.onAllNodesWithText("Second recipe").onLast().assertExists()
    }

    @Test fun asynchronousDetailInitializesPresetButManualCustomSurvivesTypingAndDiscovery() {
        val gate = CompletableDeferred<com.hermesagent.mobile.data.cron.CronResult<com.hermesagent.mobile.data.cron.CronJob>>()
        val api = FakeCronApi().apply { onDetail = { gate.await() } }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = owner)
        compose.setContent {
            val state by model.state.collectAsState()
            HermesTheme(selection = AppearanceSelection()) { CronScreen(state, model, {}) }
        }
        compose.runOnIdle { model.show("default"); model.edit(api.job) }
        compose.runOnIdle { gate.complete(com.hermesagent.mobile.data.cron.CronResult.Success(api.job.copy(schedule = "*/15 * * * *"))) }
        compose.onNodeWithContentDescription("Frequency").performScrollTo().assertTextEquals("Every 15 minutes")
        compose.onNodeWithContentDescription("Frequency").performClick()
        compose.onAllNodesWithText("Custom").onLast().performClick()
        compose.onNodeWithTag("cron-schedule").performScrollTo().performTextReplacement("0 9 * * *")
        compose.onNodeWithTag("cron-name").performScrollTo().performTextInput(" renamed")
        compose.onNodeWithTag("cron-schedule").performScrollTo().assertExists()
    }

    @Test fun modelDelimiterCollisionSelectsExactPairWithoutRedactedRoundTrip() {
        val first = "provider / nested" to "model"
        val second = "provider" to "nested / model"
        val api = FakeCronApi()
        val catalog = com.hermesagent.mobile.data.composer.ModelCatalog(providers = listOf(
            com.hermesagent.mobile.data.composer.ModelProvider(first.first, models = listOf(com.hermesagent.mobile.data.composer.ModelOption(first.second))),
            com.hermesagent.mobile.data.composer.ModelProvider(second.first, models = listOf(com.hermesagent.mobile.data.composer.ModelOption(second.second))),
        ))
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = owner, loadModels = { catalog })
        compose.setContent {
            val state by model.state.collectAsState()
            HermesTheme(selection = AppearanceSelection()) { CronScreen(state, model, {}) }
        }
        compose.runOnIdle { model.show("default"); model.edit() }
        compose.onNodeWithContentDescription("Model").performScrollTo().performClick()
        compose.onAllNodesWithText("provider / nested / model").onLast().performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(second.first, model.state.value.editor?.draft?.provider)
            org.junit.Assert.assertEquals(second.second, model.state.value.editor?.draft?.model)
        }
    }

    @Test fun sidebarStartsCollapsedAndRetainsDisclosureAcrossSavedState() {
        val api = FakeCronApi()
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = owner)
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            val state by model.state.collectAsState()
            HermesTheme(selection = AppearanceSelection()) { CronSidebarSection(state, model, {}, {}, true) }
        }
        compose.runOnIdle { model.show("default") }
        compose.onNodeWithText("Daily digest").assertDoesNotExist()
        compose.onNodeWithText("SCHEDULED JOBS").performClick()
        compose.onNodeWithText("Daily digest").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Daily digest").assertExists()
    }

    @Test fun sidebarOrdersInstantsAndCapsAtFiftyWithThreePlusTenPaging() {
        val api = FakeCronApi().apply {
            jobsResult = com.hermesagent.mobile.data.cron.CronResult.Success(
                listOf(job.copy(id = "late", name = "Late", nextRunAt = "2026-10-19T09:00:00-04:00"),
                    job.copy(id = "early", name = "Early", nextRunAt = "2026-10-19T10:00:00Z")) +
                    (0..51).map { job.copy(id = "extra-$it", name = "Extra %02d".format(it), nextRunAt = if (it == 51) "invalid" else null) }
            )
        }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = owner)
        compose.setContent {
            val state by model.state.collectAsState()
            HermesTheme(selection = AppearanceSelection()) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    CronSidebarSection(state, model, {}, {}, true)
                }
            }
        }
        compose.runOnIdle { model.show("default") }
        compose.onNodeWithText("SCHEDULED JOBS").performClick()
        val early = compose.onNodeWithText("Early").fetchSemanticsNode().boundsInRoot
        val late = compose.onNodeWithText("Late").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(early.top < late.top)
        compose.onNodeWithText("Extra 01").assertDoesNotExist()
        repeat(4) { compose.onNodeWithText("Load 10 more").performScrollTo().performClick() }
        compose.onNodeWithText("Load 7 more").performScrollTo().performClick()
        compose.onNodeWithText("Extra 47").performScrollTo().assertExists()
        compose.onNodeWithText("Extra 48").assertDoesNotExist()
        compose.onAllNodes(hasText("more", substring = true)).assertCountEquals(0)
    }

    @Test fun completedAndUnknownStateKeepPauseVisibleDisabledWithWipAndNeverResume() {
        val api = FakeCronApi().apply {
            jobsResult = com.hermesagent.mobile.data.cron.CronResult.Success(listOf(job.copy(enabled = false, state = "completed")))
        }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = owner)
        compose.setContent {
            val state by model.state.collectAsState()
            HermesTheme(selection = AppearanceSelection()) { CronScreen(state, model, {}) }
        }
        compose.runOnIdle { model.show("default"); model.select(model.state.value.jobs.single()) }
        compose.onNodeWithText("Resume").assertDoesNotExist()
        compose.onNodeWithText("Pause").assertIsNotEnabled()
        compose.onNodeWithTag("WIP").assertExists()
        compose.onNodeWithContentDescription("Pause. Work in progress.").assertExists()
        compose.runOnIdle {
            api.jobsResult = com.hermesagent.mobile.data.cron.CronResult.Success(listOf(api.job.copy(state = "future-state")))
            model.refresh()
        }
        compose.onNodeWithText("Pause").assertIsNotEnabled()
        compose.onNodeWithText("future-state").assertExists()
    }

    @Test fun rootBackUsesShellCallbackAndDetailBackKeepsDestinationOpen() {
        val api = FakeCronApi()
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = owner)
        var exits = 0
        compose.setContent {
            val state by model.state.collectAsState()
            androidx.compose.runtime.CompositionLocalProvider(com.hermesagent.mobile.ui.LocalCronBackAction provides { exits++ }) {
                HermesTheme(selection = AppearanceSelection()) { CronScreen(state, model, {}) }
            }
        }
        compose.runOnIdle { model.show("default") }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, exits); model.select(api.job) }
        compose.onNodeWithContentDescription("Back to scheduled jobs").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, exits); org.junit.Assert.assertNull(model.state.value.selected) }
    }

    @Test fun existingEditorCanSaveDespiteUnrelatedCreationLock() {
        val api = FakeCronApi()
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = owner)
        compose.setContent {
            val state by model.state.collectAsState()
            HermesTheme(selection = AppearanceSelection()) { CronScreen(state.copy(creationLocked = true), model, {}) }
        }
        compose.runOnIdle { model.show("default"); model.edit(api.job) }
        compose.onNodeWithText("Save changes").assertIsEnabled()
    }

    @Test fun uncertainCreateHasReachableReviewAndNeverAutomaticallyResubmits() {
        val api = FakeCronApi().apply {
            createResult = com.hermesagent.mobile.data.cron.CronResult.Failure(com.hermesagent.mobile.data.cron.CronFailure.Unconfirmed, mutationMayHaveApplied = true)
        }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = owner)
        compose.setContent {
            val state by model.state.collectAsState()
            HermesTheme(selection = AppearanceSelection()) { CronScreen(state, model, {}) }
        }
        compose.runOnIdle { model.show("default"); model.edit() }
        compose.onNodeWithTag("cron-prompt").performTextInput("Unconfirmed fixture draft")
        compose.onNodeWithText("Create cron").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Review previous create").performScrollTo().performClick()
        compose.onNodeWithText("Start a different cron").performScrollTo().performClick()
        compose.runOnIdle {
            org.junit.Assert.assertFalse(model.state.value.creationLocked)
            org.junit.Assert.assertEquals("", model.state.value.editor?.draft?.prompt)
            org.junit.Assert.assertEquals(1, api.creates.size)
        }
    }

    @Test fun rawRedactedModelChoicePersistsExactIdsAndUntouchedPrompt() {
        val rawProvider = "password=fixture-provider"
        val rawModel = "password=fixture-model"
        val rawPrompt = "  password=fixture-prompt\n"
        val api = FakeCronApi()
        val catalog = com.hermesagent.mobile.data.composer.ModelCatalog(providers = listOf(
            com.hermesagent.mobile.data.composer.ModelProvider(rawProvider, models = listOf(com.hermesagent.mobile.data.composer.ModelOption(rawModel))),
        ))
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = owner, loadModels = { catalog })
        compose.setContent {
            val state by model.state.collectAsState()
            HermesTheme(selection = AppearanceSelection()) { CronScreen(state, model, {}) }
        }
        compose.runOnIdle { model.show("default"); model.edit() }
        compose.onNodeWithTag("cron-prompt").performTextInput(rawPrompt)
        compose.onNodeWithContentDescription("Model").performScrollTo().performClick()
        compose.onNodeWithText(safe("$rawProvider / $rawModel")).performClick()
        compose.onNodeWithText("Create cron").performClick()
        compose.runOnIdle {
            val saved = api.creates.single().second
            org.junit.Assert.assertEquals(rawProvider, saved.provider)
            org.junit.Assert.assertEquals(rawModel, saved.model)
            org.junit.Assert.assertEquals(rawPrompt, saved.prompt)
        }
    }

    @Test fun historyIsNewestFirstAndFixtureClockDoesNotChangeGlobalDefaults() {
        val locale = java.util.Locale.getDefault()
        val zone = java.util.TimeZone.getDefault()
        val api = FakeCronApi().apply {
            runRows = listOf(
                com.hermesagent.mobile.data.cron.CronRun("older", "default", source = "cron", title = "Older run", startedAt = 1792342800.0),
                com.hermesagent.mobile.data.cron.CronRun("newer", "default", source = "cron", title = "Newer run", startedAt = 1792396800.0),
            )
        }
        val clock = CronDisplayClock(java.util.Locale.US, java.time.ZoneId.of("UTC"))
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = owner)
        compose.setContent {
            val state by model.state.collectAsState()
            androidx.compose.runtime.CompositionLocalProvider(LocalCronDisplayClock provides clock) {
                HermesTheme(selection = AppearanceSelection()) { CronScreen(state, model, {}) }
            }
        }
        compose.runOnIdle { model.show("default"); model.select(api.job) }
        compose.onNodeWithText("Older run").performScrollTo()
        val newer = compose.onNodeWithText("Newer run").fetchSemanticsNode().boundsInRoot
        val older = compose.onNodeWithText("Older run").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(newer.top < older.top)
        org.junit.Assert.assertEquals("10/20/2026, 9:00:00 AM", clock.displayTime("2026-10-20T09:00:00Z").replace('\u202f', ' '))
        org.junit.Assert.assertEquals("tomorrow", clock.relativeTime(java.time.Instant.parse("2026-10-20T09:00:00Z").toEpochMilli(), java.time.Instant.parse("2026-10-19T09:00:00Z").toEpochMilli()))
        org.junit.Assert.assertEquals(locale, java.util.Locale.getDefault())
        org.junit.Assert.assertEquals(zone, java.util.TimeZone.getDefault())
    }

    @Test fun syntheticFixtureUsesDesktopJobsBlueprintAndInertScriptOutput() = kotlinx.coroutines.runBlocking {
        val api = com.hermesagent.mobile.SyntheticCronApi("blueprints")
        val scope = api.openScope(1, "default")
        val jobs = (scope.jobs() as com.hermesagent.mobile.data.cron.CronResult.Success).value
        org.junit.Assert.assertEquals(listOf("report-daily", "script-check"), jobs.map { it.id })
        org.junit.Assert.assertEquals("printf scheduled", jobs.last().script)
        val recipe = (scope.blueprints() as com.hermesagent.mobile.data.cron.CronResult.Success).value.single()
        org.junit.Assert.assertEquals("Synthetic Recipe", recipe.title)
        org.junit.Assert.assertEquals(listOf("time", "cadence", "weekdays", "topic", "deliver"), recipe.fields.map { it.name })
        val output = (scope.runs("script-check") as com.hermesagent.mobile.data.cron.CronResult.Success).value.single()
        org.junit.Assert.assertFalse(output.isConversation)
        scope.close()
    }

    @Test fun managerNavigatesFromListToHistoryAndBack() {
        val api = FakeCronApi()
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = owner)
        compose.setContent {
            val state by model.state.collectAsState()
            HermesTheme(selection = AppearanceSelection()) { CronScreen(state, model, {}) }
        }
        compose.runOnIdle { model.show("default") }
        compose.onNodeWithText("Scheduled jobs").assertIsDisplayed()
        compose.onNodeWithText("Daily digest").performClick()
        compose.onNodeWithContentDescription("Run history").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No runs yet").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to scheduled jobs").performClick()
        compose.onNodeWithText("Daily digest").assertIsDisplayed()
    }
}
