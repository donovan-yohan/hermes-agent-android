package com.hermesagent.mobile.ui.system

import com.hermesagent.mobile.data.gateway.GatewayAction
import com.hermesagent.mobile.data.gateway.GatewayActionStatus
import com.hermesagent.mobile.data.gateway.GatewayRestResult
import com.hermesagent.mobile.data.gateway.GatewayRestartStart
import com.hermesagent.mobile.data.gateway.GatewayStatusSummary
import com.hermesagent.mobile.data.gateway.GatewayUpdateCheck
import com.hermesagent.mobile.data.gateway.GatewayUpdateCommit
import com.hermesagent.mobile.data.gateway.GatewayUpdateReceipt
import com.hermesagent.mobile.data.gateway.GatewayUpdateStart
import com.hermesagent.mobile.data.updates.CommitGroupId
import com.hermesagent.mobile.data.updates.GatewaySystemApi
import com.hermesagent.mobile.data.updates.GatewayUpdateController
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The System panel's state and its one loop, on virtual time.
 *
 * The restart poll is Desktop's, cadence included — eighteen attempts, 1200 ms
 * apart (`apps/desktop/src/app/command-center/index.tsx:274-284` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`) — so every tick is counted rather
 * than assumed: a loop that stopped ticking would otherwise pass by doing
 * nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val api = RecordingSystemApi()

    /** Every wakeup of the restart poll, so "does not tick" is observed. */
    private var waits = 0

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the panel loads the backend's version, session count and gateway state`() = systemTest { viewModel ->
        viewModel.refresh()
        settle()

        val status = requireNotNull(viewModel.uiState.value.status)
        assertEquals("0.5.1", status.version)
        assertEquals(2L, status.activeSessions)
        assertTrue(status.gatewayRunning)
        assertNull(viewModel.uiState.value.statusError)
    }

    @Test
    fun `a status the panel cannot read says so in the transport's own words`() = systemTest { viewModel ->
        api.statusResult = GatewayRestResult.Failed(0, "Reconnect to the Gateway and try again.")

        viewModel.refresh()
        settle()

        assertNull(viewModel.uiState.value.status)
        assertEquals("Reconnect to the Gateway and try again.", viewModel.uiState.value.statusError)
    }

    @Test
    fun `a restart polls eighteen times at Desktop's cadence and stops when the child exits`() =
        systemTest { viewModel ->
            api.actionStatuses += restartRunning()
            api.actionStatuses += restartFinished(exitCode = 0L)

            viewModel.restartGateway()
            settle()
            assertEquals(1, api.restartCalls)
            // Two ticks, not eighteen: the loop breaks the moment the child is
            // no longer running (`index.tsx:281-283`).
            assertEquals(2, waits)
            assertEquals(
                SystemActionState("gateway-restart", SystemActionPhase.Done),
                viewModel.uiState.value.action,
            )
            assertNull(viewModel.uiState.value.actionError)
            // `exit_code == 0` is a handoff, not a running gateway
            // (`web_server.py:4598-4604`), so the panel re-reads the status
            // rather than claiming anything itself.
            assertEquals(1, api.statusCalls)
        }

    @Test
    fun `a child that never finishes is polled exactly eighteen times and no more`() = systemTest { viewModel ->
        repeat(30) { api.actionStatuses += restartRunning() }

        viewModel.restartGateway()
        settle()

        assertEquals(SystemViewModel.RESTART_POLL_ATTEMPTS, waits)
        // Still running is the truth, so that is what the line says.
        assertEquals(
            SystemActionState("gateway-restart", SystemActionPhase.Running),
            viewModel.uiState.value.action,
        )
        assertNull(viewModel.uiState.value.actionError)
    }

    @Test
    fun `the poll ticks on the interval and not before it`() = systemTest { viewModel ->
        repeat(30) { api.actionStatuses += restartRunning() }

        viewModel.restartGateway()
        runCurrent()
        // The first tick is armed the moment the host accepted the restart, and
        // nothing has been asked of it yet.
        assertEquals(1, waits)
        assertEquals(0, api.actionStatusCalls)

        advanceTimeBy(SystemViewModel.RESTART_POLL_INTERVAL_MILLIS - 1)
        runCurrent()
        assertEquals("nothing polls before the interval is up", 0, api.actionStatusCalls)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, api.actionStatusCalls)

        for (expectedCalls in 2..SystemViewModel.RESTART_POLL_ATTEMPTS) {
            advanceTimeBy(SystemViewModel.RESTART_POLL_INTERVAL_MILLIS)
            runCurrent()
            assertEquals("tick $expectedCalls polls on cadence", expectedCalls, api.actionStatusCalls)
        }

        advanceTimeBy(SystemViewModel.RESTART_POLL_INTERVAL_MILLIS)
        runCurrent()
        assertEquals("no 19th poll fires", SystemViewModel.RESTART_POLL_ATTEMPTS, api.actionStatusCalls)
        assertEquals(SystemViewModel.RESTART_POLL_ATTEMPTS, waits)
    }

    @Test
    fun `a non-zero exit is reported in Desktop's own words`() = systemTest { viewModel ->
        api.actionStatuses += restartFinished(exitCode = 1L)

        viewModel.restartGateway()
        settle()

        assertEquals(
            SystemActionState("gateway-restart", SystemActionPhase.Failed),
            viewModel.uiState.value.action,
        )
        assertEquals(SystemCopy.GATEWAY_RESTART_FAILED, viewModel.uiState.value.actionError)
    }

    @Test
    fun `a restart the Gateway would not start never enters the polling loop`() = systemTest { viewModel ->
        api.restartResult = GatewayRestResult.Failed(500, "The Gateway could not complete that request. Try again.")

        viewModel.restartGateway()
        settle()

        assertEquals(0, waits)
        assertNull(viewModel.uiState.value.action)
        assertEquals(
            "The Gateway could not complete that request. Try again.",
            viewModel.uiState.value.actionError,
        )
    }

    @Test
    fun `a second restart is refused while one is running`() = systemTest { viewModel ->
        repeat(30) { api.actionStatuses += restartRunning() }

        viewModel.restartGateway()
        advanceTimeBy(SystemViewModel.RESTART_POLL_INTERVAL_MILLIS * 2)
        runCurrent()
        assertTrue(viewModel.uiState.value.actionRunning)

        viewModel.restartGateway()
        settle()

        assertEquals("only one gateway-restart may be in flight", 1, api.restartCalls)
    }

    @Test
    fun `opening the sheet forces a check past the host's six-hour cache`() = systemTest { viewModel ->
        api.checkResults += GatewayRestResult.Success(
            check(behind = 2, commits = listOf("feat: a new thing", "fix: an old thing")),
        )

        viewModel.openUpdates()
        settle()

        assertTrue(viewModel.uiState.value.sheetOpen)
        assertEquals(listOf(true), api.checkForced)
        val check = requireNotNull(viewModel.uiState.value.check)
        assertTrue(check.updateAvailable)
        assertEquals(2, check.behind)
        assertEquals(listOf(CommitGroupId.New, CommitGroupId.Fixed), check.changelog.map { it.id })
        // Two shown out of two behind: nothing is hidden, so no overflow line.
        assertEquals(0, check.moreChanges)
        assertFalse(check.failed)
    }

    @Test
    fun `a host further behind than its changelog shows counts the remainder`() = systemTest { viewModel ->
        api.checkResults += GatewayRestResult.Success(check(behind = 9, commits = listOf("feat: one thing")))

        viewModel.openUpdates()
        settle()

        assertEquals(8, requireNotNull(viewModel.uiState.value.check).moreChanges)
    }

    @Test
    fun `a failed check keeps what it already knew and raises the failure over it`() = systemTest { viewModel ->
        api.checkResults += GatewayRestResult.Success(check(behind = 1, commits = listOf("feat: a thing")))
        api.checkResults += GatewayRestResult.Failed(0, "unreachable")

        viewModel.openUpdates()
        settle()
        assertFalse(requireNotNull(viewModel.uiState.value.check).failed)

        viewModel.checkForUpdates()
        settle()

        val check = requireNotNull(viewModel.uiState.value.check)
        assertTrue(check.failed)
        // A check that could not run has not discovered that this host cannot
        // update itself (`store/updates.ts:380-386`).
        assertTrue(check.supported)
    }

    @Test
    fun `the sheet refuses to close while an apply is in flight`() = systemTest { viewModel ->
        api.checkResults += GatewayRestResult.Success(check(behind = 1, commits = listOf("feat: a thing")))
        api.actionStatuses += GatewayRestResult.Success(
            GatewayActionStatus("hermes-update", running = true, exitCode = null, actionId = null, lines = emptyList(), receipt = null),
        )

        viewModel.openUpdates()
        settle()
        viewModel.applyUpdate()
        runCurrent()

        assertTrue(viewModel.uiState.value.applyLocked)
        viewModel.closeUpdates()
        runCurrent()
        assertTrue("an apply owns the sheet", viewModel.uiState.value.sheetOpen)
    }

    @Test
    fun `Maybe later closes the sheet when nothing is applying`() = systemTest { viewModel ->
        api.checkResults += GatewayRestResult.Success(check(behind = 1, commits = listOf("feat: a thing")))

        viewModel.openUpdates()
        settle()
        viewModel.closeUpdates()
        runCurrent()

        assertFalse(viewModel.uiState.value.sheetOpen)
    }

    // -----------------------------------------------------------------------
    // Harness.
    // -----------------------------------------------------------------------

    private fun systemTest(body: suspend TestScope.(SystemViewModel) -> Unit) = runTest(dispatcher) {
        val updates = GatewayUpdateController(
            scope = backgroundScope,
            api = api,
            redial = {},
            nowMillis = { 0L },
            wait = { millis -> kotlinx.coroutines.delay(millis) },
        )
        val viewModel = SystemViewModel(
            api = api,
            updates = updates,
            wait = { millis ->
                waits += 1
                kotlinx.coroutines.delay(millis)
            },
        )
        // A `combine` + `WhileSubscribed` flow needs a live collector before
        // anything it projects is visible.
        backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()
        body(viewModel)
    }

    /**
     * Drive the ViewModel's own work to a standstill.
     *
     * `advanceUntilIdle()` is right here and wrong in the update controller's
     * test: this loop runs in `viewModelScope`, which `Dispatchers.setMain`
     * puts on the foreground scheduler.
     */
    private fun TestScope.settle() {
        advanceUntilIdle()
    }

    private fun restartRunning() = GatewayRestResult.Success(
        GatewayActionStatus("gateway-restart", running = true, exitCode = null, actionId = null, lines = emptyList(), receipt = null),
    )

    private fun restartFinished(exitCode: Long) = GatewayRestResult.Success(
        GatewayActionStatus("gateway-restart", running = false, exitCode = exitCode, actionId = null, lines = emptyList(), receipt = null),
    )

    private fun check(behind: Long, commits: List<String>) = GatewayUpdateCheck(
        installMethod = "git",
        currentVersion = "0.5.1",
        behind = behind,
        updateAvailable = behind > 0,
        canApply = true,
        updateCommand = "git pull",
        message = null,
        commits = commits.mapIndexed { index, summary -> GatewayUpdateCommit("sha$index", summary) },
    )

    private class RecordingSystemApi : GatewaySystemApi {
        var statusResult: GatewayRestResult<GatewayStatusSummary> = GatewayRestResult.Success(
            GatewayStatusSummary(
                version = "0.5.1",
                activeSessions = 2L,
                gatewayRunning = true,
                canUpdateHermes = true,
            ),
        )
        var restartResult: GatewayRestResult<GatewayRestartStart> =
            GatewayRestResult.Success(GatewayRestartStart("gateway-restart", 11L))
        val checkResults = ArrayDeque<GatewayRestResult<GatewayUpdateCheck>>()
        val actionStatuses = ArrayDeque<GatewayRestResult<GatewayActionStatus>>()
        val checkForced = mutableListOf<Boolean>()
        var statusCalls = 0
        var restartCalls = 0
        var actionStatusCalls = 0

        override suspend fun status(): GatewayRestResult<GatewayStatusSummary> {
            statusCalls += 1
            return statusResult
        }

        override suspend fun checkUpdate(force: Boolean): GatewayRestResult<GatewayUpdateCheck> {
            checkForced += force
            return checkResults.pollFirst() ?: GatewayRestResult.Failed(0, "no check scripted")
        }

        override suspend fun startUpdate(): GatewayRestResult<GatewayUpdateStart> =
            GatewayRestResult.Success(
                GatewayUpdateStart.Started("hermes-update", actionId = "id", alreadyRunning = false),
            )

        override suspend fun actionStatus(
            action: GatewayAction,
            lines: Int,
        ): GatewayRestResult<GatewayActionStatus> {
            actionStatusCalls += 1
            return actionStatuses.pollFirst() ?: GatewayRestResult.Failed(0, "no status scripted")
        }

        override suspend fun updateReceipt(): GatewayRestResult<GatewayUpdateReceipt> =
            GatewayRestResult.Failed(404, "no receipt")

        override suspend fun restartGateway(): GatewayRestResult<GatewayRestartStart> {
            restartCalls += 1
            return restartResult
        }
    }
}
