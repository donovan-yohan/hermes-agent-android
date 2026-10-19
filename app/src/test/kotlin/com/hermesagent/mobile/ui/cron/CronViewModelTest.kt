package com.hermesagent.mobile.ui.cron

import com.hermesagent.mobile.data.cron.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CronViewModelTest {
    @Test fun existingOutcomeReviewPreservesDraftRotatesEditorAndNeverReplays() = runTest {
        val api = FakeCronApi().apply { createResult = CronResult.Failure(CronFailure.Refused, true) }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("all"); runCurrent(); model.edit(api.job); runCurrent()
        val old = model.state.value.editor!!.ref
        val draft = model.state.value.editor!!.draft.copy(schedule = "invalid schedule", name = "Kept")
        model.updateDraft(old, draft); model.save(old, draft); runCurrent()
        val ref = model.reference(api.job)!!
        model.refresh(); runCurrent()
        assertTrue(ref.key in model.state.value.locked)
        model.reviewJob(ref); runCurrent()
        val review = model.state.value.review!!
        assertEquals(api.job, review.job)
        assertTrue(ref.key in model.state.value.locked)
        model.acknowledgeJobOutcome(ref)
        assertTrue(ref.key in model.state.value.locked)
        model.acknowledgeJobOutcome(review.ref)
        assertTrue(model.state.value.locked.isEmpty())
        assertEquals(draft, model.state.value.editor!!.draft)
        assertNotEquals(old, model.state.value.editor!!.ref)
        model.save(old, draft); model.acknowledgeJobOutcome(review.ref); runCurrent()
        assertEquals(1, api.updates)
    }

    @Test fun reviewCannotUnlockWrongOwnerFailedInventoryOrReplacedConnection() = runTest {
        val api = FakeCronApi().apply { createResult = CronResult.Failure(CronFailure.Unconfirmed, true) }
        val identities = MutableStateFlow<Any?>(Any())
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope,
            connectionIdentity = { identities.value }, connectionIdentities = identities)
        model.show("default"); runCurrent(); model.edit(api.job); runCurrent()
        model.save(model.state.value.editor!!.draft); runCurrent()
        val ref = model.reference(api.job)!!
        api.jobsResult = CronResult.Failure(CronFailure.Unreachable)
        model.reviewJob(ref); runCurrent()
        assertNull(model.state.value.review)
        api.jobsResult = CronResult.Success(listOf(api.job))
        api.detailResult = CronResult.Success(api.job.copy(profile = "research"))
        model.reviewJob(ref); runCurrent()
        assertNull(model.state.value.review)
        api.detailResult = CronResult.Success(api.job)
        model.reviewJob(ref); runCurrent()
        val review = model.state.value.review!!
        identities.value = Any()
        model.acknowledgeJobOutcome(review.ref)
        assertTrue(ref.key in model.state.value.locked)
        runCurrent()
        model.acknowledgeJobOutcome(review.ref)
        assertTrue(ref.key in model.state.value.locked)
        assertEquals(1, api.updates)
    }

    @Test fun oldReviewCannotAcknowledgeAfterProfileABA() = runTest {
        val api = FakeCronApi().apply { createResult = CronResult.Failure(CronFailure.Unconfirmed, true) }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent(); model.edit(api.job); runCurrent()
        model.save(model.state.value.editor!!.draft); runCurrent()
        model.reviewJob(model.reference(api.job)!!); runCurrent()
        val review = model.state.value.review!!
        model.show("research"); model.show("default"); runCurrent()
        model.acknowledgeJobOutcome(review.ref)
        assertTrue(review.ref.key in model.state.value.locked)
        assertEquals(1, api.updates)
    }

    @Test fun blueprintRetryCoalescesAndPollingDoesNotReloadCatalog() = runTest {
        val api = FakeCronApi().apply { blueprintResult = CronResult.Failure(CronFailure.Unreachable) }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("all"); runCurrent()
        assertNotNull(model.state.value.blueprintsFailure)
        val held = CompletableDeferred<CronResult<List<CronBlueprint>>>()
        api.onBlueprints = { held.await() }
        model.reloadBlueprints(); model.reloadBlueprints(); runCurrent()
        assertEquals(2, api.blueprintReads)
        held.complete(CronResult.Success(listOf(CronBlueprint("restored", "Restored")))); runCurrent()
        assertNull(model.state.value.blueprintsFailure)
        assertEquals("restored", model.state.value.blueprints.single().key)
        model.setVisible(true); runCurrent(); testScheduler.advanceTimeBy(24_001); runCurrent()
        assertEquals(2, api.blueprintReads)
    }

    @Test fun changedInventoryInvalidatesPriorOutcomeAcknowledgement() = runTest {
        val api = FakeCronApi().apply { createResult = CronResult.Failure(CronFailure.Unconfirmed, true) }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent(); model.edit(api.job); runCurrent()
        model.save(model.state.value.editor!!.draft); runCurrent()
        model.reviewJob(model.reference(api.job)!!); runCurrent()
        val old = model.state.value.review!!.ref
        api.jobsResult = CronResult.Success(listOf(api.job.copy(name = "Changed elsewhere")))
        model.refresh(); runCurrent()
        model.acknowledgeJobOutcome(old)
        assertTrue(old.key in model.state.value.locked)
    }

    @Test fun dismissedEditorCannotAcknowledgeCapturedJobReview() = runTest {
        val api = FakeCronApi().apply { createResult = CronResult.Failure(CronFailure.Unconfirmed, true) }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent(); model.edit(api.job); runCurrent()
        model.save(model.state.value.editor!!.draft); runCurrent()
        model.reviewJob(model.reference(api.job)!!); runCurrent()
        val review = model.state.value.review!!.ref
        model.closeEditor()
        model.acknowledgeJobOutcome(review)
        assertTrue(review.key in model.state.value.locked)
    }

    @Test fun oldProfileReadCannotPublishAcrossABA() = runTest {
        val api = FakeCronApi()
        val held = CompletableDeferred<CronResult<List<CronJob>>>()
        var first = true
        api.onJobs = { if (first) { first = false; held.await() } else CronResult.Success(listOf(api.job.copy(name = "Current"))) }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent()
        model.show("research"); model.show("default"); runCurrent()
        held.complete(CronResult.Success(listOf(api.job.copy(name = "Old")))); runCurrent()
        assertEquals("Current", model.state.value.jobs.single().name)
    }

    @Test fun capturedRowCallbackIsRejectedAfterProfileABA() = runTest {
        val api = FakeCronApi()
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent()
        val ref = model.reference(model.state.value.jobs.single())!!
        model.show("research"); model.show("default"); runCurrent()
        model.act(ref, CronAction.Trigger); runCurrent()
        assertTrue(api.actions.isEmpty())
    }

    @Test fun inFlightOwnerWriteSurvivesProfileABAUntilCompletion() = runTest {
        val api = FakeCronApi().apply { actionGate = CompletableDeferred() }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent()
        model.act(api.job, CronAction.Pause); runCurrent()
        model.show("research"); model.show("default"); runCurrent()
        assertTrue(CronJobKey("default", "job-1") in model.state.value.busy)
        model.act(api.job, CronAction.Resume); runCurrent()
        assertEquals(1, api.actions.size)
        api.actionGate!!.complete(CronResult.Success(Unit)); runCurrent()
        assertTrue(model.state.value.busy.isEmpty())
        model.act(api.job, CronAction.Resume); runCurrent()
        assertEquals(2, api.actions.size)
    }

    @Test fun replacementConnectionRejectsCapturedActionsBeforeObserverRuns() = runTest {
        val api = FakeCronApi()
        val identities = MutableStateFlow<Any?>(Any())
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope,
            connectionIdentity = { identities.value }, connectionIdentities = identities)
        model.show("default"); runCurrent()
        val ref = model.reference(api.job)!!
        model.edit(api.job); runCurrent()
        val editor = model.state.value.editor!!
        identities.value = Any()
        model.act(ref, CronAction.Trigger)
        model.save(editor.ref, editor.draft)
        model.edit()
        assertTrue(api.actions.isEmpty())
        assertEquals(0, api.updates)
        runCurrent()
        assertNull(model.state.value.editor)
        model.act(ref, CronAction.Trigger); runCurrent()
        assertTrue(api.actions.isEmpty())
        model.act(api.job, CronAction.Trigger); runCurrent()
        assertEquals(1, api.actions.size)
    }

    @Test fun triggerQueuesSynchronouslyAndCoalescesDoubleClick() = runTest {
        val api = FakeCronApi().apply { actionGate = CompletableDeferred() }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("all"); runCurrent()
        val ref = model.reference(model.state.value.jobs.single())!!
        model.act(ref, CronAction.Trigger)
        model.act(ref, CronAction.Trigger)
        assertEquals(1, model.state.value.pending.size)
        runCurrent()
        assertEquals(listOf(Triple("default", "job-1", CronAction.Trigger)), api.actions)
    }

    @Test fun allProfileCreateUsesDefaultAndPreservesRawDraft() = runTest {
        val api = FakeCronApi()
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("all"); runCurrent()
        model.edit()
        val editor = model.state.value.editor!!
        val draft = CronDraft(name = "  A name  ", prompt = "  Exact draft\n", schedule = "every hour")
        model.save(editor.ref, draft); runCurrent()
        assertEquals("default" to draft, api.creates.single())
        assertNull(model.state.value.editor)
    }

    @Test fun unconfirmedUpdateCannotBeRetriedOrReopened() = runTest {
        val api = FakeCronApi().apply { createResult = CronResult.Failure(CronFailure.Unconfirmed, true) }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent(); model.edit(api.job); runCurrent()
        val editor = model.state.value.editor!!
        model.save(editor.ref, editor.draft); runCurrent()
        assertTrue(CronJobKey("default", "job-1") in model.state.value.locked)
        model.updateDraft(editor.ref, editor.draft.copy(name = "Retry draft"))
        assertEquals(editor.draft, model.state.value.editor!!.draft)
        model.save(editor.ref, editor.draft); runCurrent()
        assertEquals(1, api.updates)
        model.closeEditor(); model.edit(api.job); runCurrent()
        assertNull(model.state.value.editor)
    }

    @Test fun unconfirmedCreateCannotBeRetriedAfterEditorReopen() = runTest {
        val api = FakeCronApi().apply { createResult = CronResult.Failure(CronFailure.Unconfirmed, mutationMayHaveApplied = true) }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("all"); runCurrent()
        model.edit(); model.save(model.state.value.editor!!.ref, CronDraft(prompt = "Digest")); runCurrent()
        model.closeEditor(); model.edit()
        model.save(model.state.value.editor!!.ref, CronDraft(prompt = "Digest")); runCurrent()
        assertEquals(1, api.creates.size)
        assertTrue(model.state.value.creationLocked)
    }

    @Test fun detailSelectionDropsPreviousRunsAndOutputIsNotAdmitted() = runTest {
        val api = FakeCronApi().apply { runRows = listOf(CronRun("cron_output:x", "default", source = "cron_output", title = "Output"), CronRun("cron_run", "default", source = "cron", title = "Run")) }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("all"); runCurrent()
        model.select(model.state.value.jobs.single()); runCurrent()
        val selected = model.state.value.selected!!
        assertEquals(2, model.state.value.histories[selected.key]!!.rows.size)
        var opened: CronRun? = null
        model.openRun(selected, api.runRows.first()) { opened = it }
        assertNull(opened)
        model.openRun(selected, api.runRows.last()) { opened = it }
        assertEquals(api.runRows.last(), opened)
        model.show("research"); runCurrent()
        opened = null
        model.openRun(selected, api.runRows.last()) { opened = it }
        assertNull(opened)
    }

    @Test fun queuedFeedbackExpiryKeepsRequestBusyUntilLateConfirmedSuccess() = runTest {
        val api = FakeCronApi().apply { actionGate = CompletableDeferred() }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope, nowMillis = { testScheduler.currentTime })
        model.show("default"); runCurrent()
        val ref = model.reference(api.job)!!
        model.act(ref, CronAction.Trigger); runCurrent()
        testScheduler.advanceTimeBy(90_000); runCurrent()
        assertTrue(model.state.value.pending.isEmpty())
        assertTrue(ref.key in model.state.value.busy)
        model.act(ref, CronAction.Pause); model.act(ref, CronAction.Trigger); runCurrent()
        assertEquals(1, api.actions.size)
        api.actionGate!!.complete(CronResult.Success(Unit)); runCurrent()
        assertTrue(model.state.value.busy.isEmpty())
        assertTrue(model.state.value.locked.isEmpty())
        assertNull(model.state.value.failure)
        assertEquals("Cron triggered", model.state.value.notice)
        model.act(ref, CronAction.Trigger); runCurrent()
        assertEquals(2, api.actions.size)
    }

    @Test fun queuedDeadlineLocksUnknownTriggerWithoutBlindRetry() = runTest {
        val api = FakeCronApi().apply { actionGate = CompletableDeferred() }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope, nowMillis = { testScheduler.currentTime })
        model.show("default"); runCurrent()
        val ref = model.reference(api.job)!!
        model.act(ref, CronAction.Trigger); runCurrent()
        testScheduler.advanceTimeBy(90_000); runCurrent()
        assertTrue(model.state.value.pending.isEmpty())
        assertTrue(model.state.value.locked.contains(ref.key))
        model.act(ref, CronAction.Trigger); runCurrent()
        assertEquals(1, api.actions.size)
    }

    @Test fun lateUnknownDeleteLocksDepartedOwnerBeforeViewGuard() = runTest {
        val api = FakeCronApi().apply { actionGate = CompletableDeferred() }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent()
        model.requestDelete(api.job); model.confirmDelete(); runCurrent()
        model.show("research"); model.show("default"); runCurrent()
        assertTrue(CronJobKey("default", "job-1") in model.state.value.busy)
        api.actionGate!!.complete(CronResult.Failure(CronFailure.Unconfirmed, true)); runCurrent()
        assertTrue(CronJobKey("default", "job-1") in model.state.value.locked)
        assertTrue(model.state.value.busy.isEmpty())
        model.requestDelete(api.job); model.confirmDelete(); runCurrent()
        assertEquals(1, api.actions.size)
    }

    @Test fun deleteRequiresConfirmationAndFailureKeepsDialog() = runTest {
        val api = FakeCronApi().apply { actionGate = CompletableDeferred<CronResult<Unit>>().also { it.complete(CronResult.Failure(CronFailure.Refused)) } }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent()
        model.requestDelete(api.job); runCurrent()
        assertTrue(api.actions.isEmpty())
        val ref = model.state.value.deleteTarget!!
        model.confirmDelete(ref); runCurrent()
        assertNotNull(model.state.value.deleteTarget)
        assertEquals(CronFailure.Refused, model.state.value.failure?.kind)
        assertEquals(1, model.state.value.jobs.size)
    }

    @Test fun disconnectImmediatelyClearsRawEditorAndEndpointNeverReusesRows() = runTest {
        val api = FakeCronApi()
        val endpoint = MutableStateFlow(1L)
        val connected = MutableStateFlow(true)
        val model = CronViewModel(api, endpoint, connected, ownerScope = backgroundScope)
        model.show("default"); runCurrent()
        model.edit()
        connected.value = false; runCurrent()
        assertNull(model.state.value.editor)
        assertTrue(model.state.value.stale)
        endpoint.value = 2L; runCurrent()
        assertTrue(model.state.value.jobs.isEmpty())
    }

    @Test fun editLoadsAuthoritativeRawJobAndRetainsPinWhenCatalogUnavailable() = runTest {
        val api = FakeCronApi().apply { detailResult = CronResult.Success(job.copy(prompt = "  Exact draft\n", model = "retired", provider = "old-provider", noAgent = true, script = "digest.sh")) }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent()
        model.edit(api.job); runCurrent()
        val editor = model.state.value.editor!!
        assertEquals("  Exact draft\n", editor.draft.prompt)
        assertEquals("retired", editor.draft.model)
        assertTrue(editor.job!!.scriptOnly)
        model.save(editor.ref, editor.draft.copy(prompt = "", name = "Renamed")); runCurrent()
        assertEquals("", api.creates.single().second.prompt)
    }

    @Test fun successfulUpdateUsesUpdateAndRefreshDoesNotEraseSelection() = runTest {
        val api = FakeCronApi()
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent()
        model.select(api.job); model.edit(api.job); runCurrent()
        val editor = model.state.value.editor!!
        model.save(editor.ref, editor.draft); model.save(editor.ref, editor.draft); runCurrent()
        assertEquals(1, api.updates)
        assertEquals(1, api.creates.size)
        model.refresh(); runCurrent()
        assertNotNull(model.state.value.selected)
    }

    @Test fun slowHistoryIsCoalescedAcrossPollsAndPublishesWhenStillSelected() = runTest {
        val held = CompletableDeferred<CronResult<List<CronRun>>>()
        val api = FakeCronApi().apply { onRuns = { held.await() } }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent(); model.select(api.job); model.setVisible(true); runCurrent()
        testScheduler.advanceTimeBy(24_001); runCurrent()
        assertEquals(1, api.runReads)
        val run = CronRun("slow-run", "default", source = "cron")
        held.complete(CronResult.Success(listOf(run))); runCurrent()
        assertEquals(listOf(run), model.state.value.histories[CronJobKey("default", "job-1")]!!.rows)
    }

    @Test fun historyPollsOnlyVisibleAndChangeHintRefreshesSnapshot() = runTest {
        val api = FakeCronApi()
        val hints = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), hints, backgroundScope, nowMillis = { testScheduler.currentTime })
        model.show("default"); runCurrent()
        model.setVisible(true); model.select(api.job); runCurrent()
        val before = api.runReads
        testScheduler.advanceTimeBy(8_001); runCurrent()
        assertTrue(api.runReads > before)
        model.setVisible(false); runCurrent()
        val hidden = api.runReads
        testScheduler.advanceTimeBy(20_000); runCurrent()
        assertEquals(hidden, api.runReads)
        model.setVisible(true); runCurrent()
        api.jobsResult = CronResult.Success(emptyList())
        hints.tryEmit(Unit); runCurrent()
        assertTrue(model.state.value.jobs.isEmpty())
    }

    @Test fun blueprintCreateUsesConcreteProfileAndExactFieldVocabulary() = runTest {
        val api = FakeCronApi().apply { blueprintRows = listOf(CronBlueprint("digest", "Daily digest", fields = listOf(CronBlueprintField("deliver", "enum", "Deliver to", defaultValue = "origin"), CronBlueprintField("at", "time", "Time", defaultValue = "09:00")))) }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("all"); runCurrent()
        assertEquals("digest", model.state.value.blueprints.single().key)
        model.edit(); runCurrent()
        model.chooseBlueprint(model.state.value.editor!!.ref, "digest")
        val editor = model.state.value.editor!!
        assertEquals(mapOf("deliver" to "local", "at" to "09:00"), editor.values)
        model.instantiate(editor.ref, "digest", editor.values); runCurrent()
        assertEquals(Triple("default", "digest", editor.values), api.instantiations.single())
        assertNull(model.state.value.editor)
    }

    @Test fun triggerMaterializationSettlesPendingAndPauseIsVerified() = runTest {
        val api = FakeCronApi()
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope, nowMillis = { testScheduler.currentTime })
        model.show("default"); runCurrent()
        model.select(api.job); runCurrent()
        model.act(model.reference(api.job)!!, CronAction.Trigger); runCurrent()
        api.runRows = listOf(CronRun("new-run", "default", source = "cron", startedAt = 1.0))
        model.onResume(); model.setVisible(true); runCurrent()
        assertTrue(model.state.value.pending.isEmpty())
        api.detailResult = CronResult.Success(api.job.copy(enabled = false, state = "paused"))
        model.act(model.reference(model.state.value.jobs.single())!!, CronAction.Pause); runCurrent()
        assertFalse(model.state.value.jobs.single().enabled)
    }

    @Test fun lockSurvivesProfileReentryAndEditorCallbacksAreFenced() = runTest {
        val api = FakeCronApi().apply { createResult = CronResult.Failure(CronFailure.Unconfirmed, true) }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent(); model.edit()
        val old = model.state.value.editor!!.ref
        model.save(old, CronDraft(prompt = "Digest")); runCurrent()
        model.show("research"); model.show("default"); runCurrent(); model.edit()
        model.save(old, CronDraft(prompt = "Digest")); runCurrent()
        model.save(model.state.value.editor!!.ref, CronDraft(prompt = "Digest")); runCurrent()
        assertEquals(1, api.creates.size)
    }

    @Test fun lateUnknownTriggerLocksOwnerEvenAfterProfileSwitch() = runTest {
        val api = FakeCronApi().apply { actionGate = CompletableDeferred() }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent(); model.act(api.job, CronAction.Trigger); runCurrent()
        model.show("research"); runCurrent()
        api.actionGate!!.complete(CronResult.Failure(CronFailure.Unconfirmed, true)); runCurrent()
        model.show("default"); runCurrent()
        assertTrue(CronJobKey("default", "job-1") in model.state.value.locked)
    }

    @Test fun failedRefreshRetainsLastGoodJobs() = runTest {
        val api = FakeCronApi()
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default")
        runCurrent()
        assertEquals(listOf(api.job), model.state.value.jobs)
        api.jobsResult = CronResult.Failure(CronFailure.Unreachable)
        model.refresh()
        runCurrent()
        assertEquals(listOf(api.job), model.state.value.jobs)
        assertTrue(model.state.value.stale)
        assertEquals(CronFailure.Unreachable, model.state.value.failure?.kind)
    }

    @Test fun hiddenEmptyInventoryRefreshesOnHintsWithoutPollingHistory() = runTest {
        val api = FakeCronApi().apply { jobsResult = CronResult.Success(emptyList()) }
        val hints = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), hints, backgroundScope)
        model.show("default"); runCurrent()
        api.jobsResult = CronResult.Success(listOf(api.job))
        hints.tryEmit(Unit); runCurrent()
        assertEquals(listOf(api.job), model.state.value.jobs)
        val reads = api.jobReads
        testScheduler.advanceTimeBy(24_001); runCurrent()
        assertEquals(reads, api.jobReads)
        assertEquals(0, api.runReads)
    }

    @Test fun slowInventoryCoalescesAcrossPendingPollsWithoutStarvation() = runTest {
        val api = FakeCronApi().apply { actionGate = CompletableDeferred() }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent(); model.act(api.job, CronAction.Trigger); runCurrent()
        val held = CompletableDeferred<CronResult<List<CronJob>>>()
        api.onJobs = { held.await() }
        model.setVisible(true); runCurrent()
        val reads = api.jobReads
        testScheduler.advanceTimeBy(3_001); runCurrent()
        assertEquals(reads, api.jobReads)
        val fresh = api.job.copy(name = "Refreshed")
        held.complete(CronResult.Success(listOf(fresh))); runCurrent()
        assertEquals(listOf(fresh), model.state.value.jobs)
    }

    @Test fun preMutationInventoryCannotOverwriteConfirmedUpdate() = runTest {
        val api = FakeCronApi()
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent()
        val held = CompletableDeferred<CronResult<List<CronJob>>>()
        api.onJobs = { held.await() }
        model.refresh(); model.edit(api.job); runCurrent()
        val updated = api.job.copy(name = "Confirmed update")
        api.createResult = CronResult.Success(updated)
        model.save(model.state.value.editor!!.ref, CronDraft(prompt = updated.prompt, name = updated.name)); runCurrent()
        held.complete(CronResult.Success(listOf(api.job))); runCurrent()
        assertEquals(listOf(updated), model.state.value.jobs)
    }

    @Test fun uncertainCreateDoesNotBlockExistingJobUpdate() = runTest {
        val api = FakeCronApi().apply { createResult = CronResult.Failure(CronFailure.Unconfirmed, true) }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent(); model.edit(); runCurrent()
        model.save(CronDraft(prompt = "New digest")); runCurrent()
        model.closeEditor(); model.edit(api.job); runCurrent()
        assertFalse(model.state.value.creationLocked)
        api.createResult = CronResult.Success(api.job)
        val editor = model.state.value.editor!!
        model.save(editor.ref, editor.draft); runCurrent()
        assertEquals(1, api.updates)
    }

    @Test fun creationOutcomeReviewRequiresFreshInventoryAndRotatesEditorCapability() = runTest {
        val api = FakeCronApi().apply { createResult = CronResult.Failure(CronFailure.Unconfirmed, true) }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent(); model.edit(); runCurrent()
        model.save(CronDraft(prompt = "Uncertain digest")); runCurrent()
        val old = model.state.value.editor!!.ref
        model.acknowledgeCreationOutcome(old)
        assertTrue(model.state.value.creationLocked)
        model.refresh(); runCurrent()
        assertTrue(model.state.value.creationLocked)
        model.acknowledgeCreationOutcome(old)
        assertFalse(model.state.value.creationLocked)
        assertNotEquals(old, model.state.value.editor!!.ref)
        assertEquals("", model.state.value.editor!!.draft.prompt)
        model.save(old, CronDraft(prompt = "Uncertain digest")); runCurrent()
        assertEquals(1, api.creates.size)
    }

    @Test fun runOpenFailureIsVisibleOnCronSurface() = runTest {
        val model = CronViewModel(FakeCronApi(), MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent()
        model.reportRunOpenFailure()
        assertEquals("Failed to open cron run. Try again.", model.state.value.notice)
    }

    @Test fun cancelledAdmittedWritesReleaseBusyAndRetainUncertainty() = runTest {
        val owner = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.SupervisorJob())
        val api = FakeCronApi().apply { actionGate = CompletableDeferred() }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = owner)
        model.show("default"); runCurrent(); model.act(api.job, CronAction.Pause); runCurrent()
        owner.coroutineContext[kotlinx.coroutines.Job]!!.cancel(); runCurrent()
        assertTrue(model.state.value.busy.isEmpty())
        assertTrue(CronJobKey("default", "job-1") in model.state.value.locked)
    }

    @Test fun slowHistoryNeverPublishesAfterSelectionABA() = runTest {
        val api = FakeCronApi()
        val old = CompletableDeferred<CronResult<List<CronRun>>>()
        var first = true
        val fresh = CronRun("current-run", "default", source = "cron")
        api.onRuns = { if (first) { first = false; old.await() } else CronResult.Success(listOf(fresh)) }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent(); model.select(api.job); runCurrent()
        model.clearSelection(model.state.value.selected!!)
        model.select(api.job); runCurrent()
        old.complete(CronResult.Success(listOf(CronRun("stale-run", "default", source = "cron")))); runCurrent()
        assertEquals(listOf(fresh), model.state.value.histories[CronJobKey("default", "job-1")]!!.rows)
    }

    @Test fun confirmedTriggerDoesNotBecomeUnknownWhenFeedbackExpiresWithoutHistory() = runTest {
        val api = FakeCronApi()
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent(); model.act(api.job, CronAction.Trigger); runCurrent()
        testScheduler.advanceTimeBy(90_000); runCurrent()
        assertTrue(model.state.value.pending.isEmpty())
        assertTrue(model.state.value.busy.isEmpty())
        assertTrue(model.state.value.locked.isEmpty())
        assertNull(model.state.value.failure)
    }

    @Test fun heldUpdateProtectsOnlyItsJobAcrossProfileABA() = runTest {
        val held = CompletableDeferred<CronResult<CronJob>>()
        val api = FakeCronApi().apply { onUpdate = { held.await() } }
        val other = api.job.copy(id = "job-2")
        api.jobsResult = CronResult.Success(listOf(api.job, other))
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent(); model.edit(api.job); runCurrent()
        model.save(model.state.value.editor!!.draft); runCurrent()
        model.show("research"); model.show("default"); runCurrent()
        model.act(api.job, CronAction.Pause); model.edit(api.job); runCurrent()
        assertTrue(api.actions.isEmpty())
        assertNull(model.state.value.editor)
        model.act(other, CronAction.Pause); runCurrent()
        assertEquals("job-2", api.actions.single().second)
        held.complete(CronResult.Failure(CronFailure.Unconfirmed, true)); runCurrent()
        assertTrue(CronJobKey("default", "job-1") in model.state.value.locked)
        assertTrue(model.state.value.busy.isEmpty())
    }

    @Test fun pauseReadbackRetainsJobOperationUntilDetailCompletes() = runTest {
        val detail = CompletableDeferred<CronResult<CronJob>>()
        val api = FakeCronApi().apply { onDetail = { detail.await() } }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent(); model.act(api.job, CronAction.Pause); runCurrent()
        model.act(api.job, CronAction.Resume); runCurrent()
        assertEquals(1, api.actions.size)
        detail.complete(CronResult.Success(api.job.copy(enabled = false))); runCurrent()
        assertTrue(model.state.value.busy.isEmpty())
        assertFalse(model.state.value.jobs.single().enabled)
    }

    @Test fun lateConfirmedTriggerRetiresProvisionalLockAcrossProfileABA() = runTest {
        val api = FakeCronApi().apply { actionGate = CompletableDeferred() }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent(); model.act(api.job, CronAction.Trigger); runCurrent()
        testScheduler.advanceTimeBy(90_000); runCurrent()
        model.show("research"); model.show("default"); runCurrent()
        assertTrue(CronJobKey("default", "job-1") in model.state.value.locked)
        api.actionGate!!.complete(CronResult.Success(Unit)); runCurrent()
        assertTrue(model.state.value.busy.isEmpty())
        assertTrue(model.state.value.locked.isEmpty())
        model.act(api.job, CronAction.Trigger); runCurrent()
        assertEquals(2, api.actions.size)
    }

    @Test fun cancellationOfAdmittedCreateReleasesInFlightAndKeepsCreationLock() = runTest {
        val owner = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.SupervisorJob())
        val held = CompletableDeferred<CronResult<CronJob>>()
        val api = FakeCronApi().apply { onCreate = { held.await() } }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = owner)
        model.show("default"); runCurrent(); model.edit(); model.save(CronDraft(prompt = "New digest")); runCurrent()
        owner.coroutineContext[kotlinx.coroutines.Job]!!.cancel(); runCurrent()
        assertTrue(model.state.value.creationLocked)
        model.closeEditor(); model.edit(); model.save(CronDraft(prompt = "New digest")); runCurrent()
        assertEquals(1, api.creates.size)
    }

    @Test fun lateUnknownCreateProjectsLockIntoReturnedOwner() = runTest {
        val held = CompletableDeferred<CronResult<CronJob>>()
        val api = FakeCronApi().apply { onCreate = { held.await() } }
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope)
        model.show("default"); runCurrent(); model.edit(); model.save(CronDraft(prompt = "New digest")); runCurrent()
        model.show("research"); model.show("default"); runCurrent()
        held.complete(CronResult.Failure(CronFailure.Unconfirmed, true)); runCurrent()
        assertTrue(model.state.value.creationLocked)
        model.edit(); model.save(CronDraft(prompt = "New digest")); runCurrent()
        assertEquals(1, api.creates.size)
    }

    @Test fun sameClockTriggerFeedbackUsesOperationTokenNotTimestamp() = runTest {
        val api = FakeCronApi()
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope, nowMillis = { testScheduler.currentTime })
        model.show("default"); runCurrent(); model.select(api.job); runCurrent()
        model.act(api.job, CronAction.Trigger); runCurrent()
        api.runRows = listOf(CronRun("materialized", "default", source = "cron", startedAt = 0.0))
        model.setVisible(true); runCurrent()
        assertTrue(model.state.value.pending.isEmpty())
        api.actionGate = CompletableDeferred()
        model.act(api.job, CronAction.Trigger); runCurrent()
        testScheduler.advanceTimeBy(90_000); runCurrent()
        assertTrue(CronJobKey("default", "job-1") in model.state.value.locked)
        assertTrue(CronJobKey("default", "job-1") in model.state.value.busy)
    }

    @Test fun currentLegRejectsOldRunAdmissionBeforeConnectionObserverRuns() = runTest {
        val api = FakeCronApi().apply { runRows = listOf(CronRun("run", "default", source = "cron")) }
        val identities = MutableStateFlow<Any?>(Any())
        val model = CronViewModel(api, MutableStateFlow(1L), MutableStateFlow(true), ownerScope = backgroundScope,
            connectionIdentity = { identities.value }, connectionIdentities = identities)
        model.show("default"); runCurrent(); model.select(api.job); runCurrent()
        val ref = model.state.value.selected!!
        identities.value = Any()
        var opened = false
        model.openRun(ref, api.runRows.single()) { opened = true }
        assertFalse(opened)
    }
}

internal class FakeCronApi : CronApi {
    val job = CronJob("job-1", "default", name = "Daily digest", prompt = "Prepare the digest", schedule = "0 9 * * *")
    var jobsResult: CronResult<List<CronJob>> = CronResult.Success(listOf(job))
    var onJobs: (suspend (String) -> CronResult<List<CronJob>>)? = null
    var jobReads = 0
    var actionGate: CompletableDeferred<CronResult<Unit>>? = null
    var createResult: CronResult<CronJob> = CronResult.Success(job)
    val actions = mutableListOf<Triple<String, String, CronAction>>()
    val creates = mutableListOf<Pair<String, CronDraft>>()
    val opened = mutableListOf<String>()
    var runRows = emptyList<CronRun>()
    var blueprintRows = emptyList<CronBlueprint>()
    var blueprintResult: CronResult<List<CronBlueprint>>? = null
    var blueprintReads = 0
    var onBlueprints: (suspend () -> CronResult<List<CronBlueprint>>)? = null
    val instantiations = mutableListOf<Triple<String, String, Map<String,String>>>()
    var updates = 0
    var runReads = 0
    var onRuns: (suspend (String) -> CronResult<List<CronRun>>)? = null
    var onUpdate: (suspend (CronDraft) -> CronResult<CronJob>)? = null
    var onCreate: (suspend (CronDraft) -> CronResult<CronJob>)? = null
    var detailResult: CronResult<CronJob>? = null
    var onDetail: (suspend (String) -> CronResult<CronJob>)? = null
    override fun openScope(expectedEndpoint: Long, profile: String): CronScope {
        opened += profile
        return object : CronScope {
            override suspend fun jobs(): CronResult<List<CronJob>> { jobReads++; return onJobs?.invoke(profile) ?: jobsResult }
            override suspend fun detail(jobId: String) = onDetail?.invoke(jobId) ?: detailResult ?: CronResult.Success(job.copy(profile = profile))
            override suspend fun runs(jobId: String, limit: Int): CronResult<List<CronRun>> { runReads++; return onRuns?.invoke(jobId) ?: CronResult.Success(runRows.take(limit)) }
            override suspend fun deliveryTargets() = CronResult.Success(listOf(CronDeliveryTarget("local", "This desktop")))
            override suspend fun blueprints(): CronResult<List<CronBlueprint>> { blueprintReads++; return onBlueprints?.invoke() ?: blueprintResult ?: CronResult.Success(blueprintRows) }
            override suspend fun create(draft: CronDraft): CronResult<CronJob> { creates += profile to draft; return onCreate?.invoke(draft) ?: createResult }
            override suspend fun update(job: CronJob, draft: CronDraft): CronResult<CronJob> { updates++; creates += profile to draft; return onUpdate?.invoke(draft) ?: createResult }
            override suspend fun act(job: CronJob, action: CronAction): CronResult<Unit> {
                actions += Triple(profile, job.id, action)
                return actionGate?.await() ?: CronResult.Success(Unit)
            }
            override suspend fun instantiate(key: String, values: Map<String, String>): CronResult<CronJob> { instantiations += Triple(profile, key, values); return createResult }
            override fun close() = Unit
        }
    }
}
