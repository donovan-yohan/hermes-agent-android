package com.hermesagent.mobile.data.updates

import com.hermesagent.mobile.data.gateway.GatewayAction
import com.hermesagent.mobile.data.gateway.GatewayActionReceipt
import com.hermesagent.mobile.data.gateway.GatewayActionStatus
import com.hermesagent.mobile.data.gateway.GatewayRestResult
import com.hermesagent.mobile.data.gateway.GatewayRestartStart
import com.hermesagent.mobile.data.gateway.GatewayStatusSummary
import com.hermesagent.mobile.data.gateway.GatewayUpdateCheck
import com.hermesagent.mobile.data.gateway.GatewayUpdateCommit
import com.hermesagent.mobile.data.gateway.GatewayUpdateReceipt
import com.hermesagent.mobile.data.gateway.GatewayUpdateStart
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six-minute update state machine, on virtual time.
 *
 * Nothing here waits: the poll interval and the wall clock are both injected,
 * so the budget-exhaustion case costs microseconds rather than ten minutes. The
 * subject is a port of Desktop's `runBackendUpdate`
 * (`apps/desktop/src/store/updates.ts:638-766` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`), and each test names the branch
 * of it that it pins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayUpdateControllerTest {

    @Test
    fun `a refusal that arrives as a 200 is terminal, with the host's own remediation`() = updateTest {
        // `web_server.py:5088-5124` @ the pin; `updates.ts:658-664`.
        api.startResult = GatewayRestResult.Success(
            GatewayUpdateStart.Refused(
                error = "apt_update_required",
                message = "Hermes is managed by Termux APT.",
                updateCommand = "pkg upgrade hermes-agent",
            ),
        )

        controller.apply()
        settle()

        val state = controller.state.value
        assertFalse(state.applying)
        assertEquals(GatewayUpdateStage.Manual, state.stage)
        assertEquals(GatewayUpdateStatusKey.NotAvailable, state.status)
        assertEquals("Hermes is managed by Termux APT.", state.message)
        assertEquals("pkg upgrade hermes-agent", state.command)
        // Nothing was started, so nothing is polled and nothing is redialled.
        assertEquals(0, api.actionStatusCalls)
        assertEquals(0, redials)
    }

    @Test
    fun `a refusal with no remediation falls back to Desktop's own command`() = updateTest {
        api.startResult = GatewayRestResult.Success(
            GatewayUpdateStart.Refused(error = "update_not_in_place", message = null, updateCommand = null),
        )

        controller.apply()
        settle()

        assertEquals(DEFAULT_UPDATE_COMMAND, controller.state.value.command)
        assertNull(controller.state.value.message)
    }

    @Test
    fun `a zero exit code finishes the apply and redials exactly once`() = updateTest {
        api.actionStatuses += running()
        api.actionStatuses += finished(exitCode = 0L)

        controller.apply()
        settle()

        assertEquals(GatewayUpdateStage.Done, controller.state.value.stage)
        assertFalse(controller.state.value.applying)
        assertEquals(1, redials)
        // The cadence is Desktop's: one poll per 1500 ms, no sooner.
        assertEquals(listOf(POLL_INTERVAL_MILLIS, POLL_INTERVAL_MILLIS), waits)
    }

    @Test
    fun `a transport blackout becomes the restart stage and comes back to pull`() = updateTest {
        api.actionStatuses += running()
        api.actionStatuses += GatewayRestResult.Failed(0, "unreachable")
        api.actionStatuses += GatewayRestResult.Failed(0, "unreachable")
        api.actionStatuses += running()
        api.actionStatuses += finished(exitCode = 0L)

        val job = controller.apply()
        // Two polls in: the action is running.
        runCurrent()
        advanceThroughPolls(1)
        assertEquals(GatewayUpdateStage.Pull, controller.state.value.stage)

        // The host stops answering. Desktop switches to `restart` on the first
        // failed poll and extends the deadline; it does not fail.
        advanceThroughPolls(1)
        assertEquals(GatewayUpdateStage.Restart, controller.state.value.stage)
        assertEquals(GatewayUpdateStatusKey.Restarting, controller.state.value.status)

        // It answers again, still running: back to `pull` (`updates.ts:701-714`).
        advanceThroughPolls(2)
        assertEquals(GatewayUpdateStage.Pull, controller.state.value.stage)

        settle()
        job.join()
        assertEquals(GatewayUpdateStage.Done, controller.state.value.stage)
        assertEquals(1, redials)
    }

    @Test
    fun `the log marker proves success when the restart took the exit code with it`() = updateTest {
        // The host replays `=== hermes-update completed <id> ===` from
        // `update.log` after the update restarted the process that spawned the
        // child (`web_server.py:4814-4839,5831-5833`).
        api.startResult = GatewayRestResult.Success(
            GatewayUpdateStart.Started("hermes-update", actionId = ACTION_ID, alreadyRunning = false),
        )
        api.actionStatuses += GatewayRestResult.Failed(0, "unreachable")
        api.actionStatuses += GatewayRestResult.Success(
            GatewayActionStatus(
                name = "hermes-update",
                running = false,
                exitCode = null,
                actionId = ACTION_ID,
                lines = listOf("pulling", "=== hermes-update completed $ACTION_ID ==="),
                receipt = null,
            ),
        )

        controller.apply()
        settle()

        assertEquals(GatewayUpdateStage.Done, controller.state.value.stage)
        assertEquals(1, redials)
    }

    @Test
    fun `a marker for somebody else's run proves nothing`() = updateTest {
        api.startResult = GatewayRestResult.Success(
            GatewayUpdateStart.Started("hermes-update", actionId = ACTION_ID, alreadyRunning = false),
        )
        api.actionStatuses += GatewayRestResult.Success(
            GatewayActionStatus(
                name = "hermes-update",
                running = false,
                exitCode = null,
                actionId = ACTION_ID,
                lines = listOf("=== hermes-update completed some-other-run ==="),
                receipt = null,
            ),
        )

        controller.apply()
        settle()

        // Not done: the loop keeps polling until the budget runs out.
        assertEquals(GatewayUpdateStage.Error, controller.state.value.stage)
        assertEquals(0, redials)
    }

    @Test
    fun `a receipt that started after this apply proves the outcome`() = updateTest {
        api.startResult = GatewayRestResult.Success(
            GatewayUpdateStart.Started("hermes-update", actionId = ACTION_ID, alreadyRunning = false),
        )
        api.actionStatuses += receiptStatus(outcome = "success", startedAt = iso(CLOCK_START))

        controller.apply()
        settle()

        assertEquals(GatewayUpdateStage.Done, controller.state.value.stage)
        assertEquals(1, redials)
    }

    @Test
    fun `a receipt inside the sixty-second clock slack still counts`() = updateTest {
        api.startResult = GatewayRestResult.Success(
            GatewayUpdateStart.Started("hermes-update", actionId = ACTION_ID, alreadyRunning = false),
        )
        // The host's clock is 59 seconds behind this phone's. Desktop absorbs
        // exactly one minute of skew (`updates.ts:604-617`).
        api.actionStatuses += receiptStatus(outcome = "success", startedAt = iso(CLOCK_START - 59_000L))

        controller.apply()
        settle()

        assertEquals(GatewayUpdateStage.Done, controller.state.value.stage)
    }

    @Test
    fun `a receipt from a previous update is refused, and so is an unfinished one`() = updateTest {
        api.startResult = GatewayRestResult.Success(
            GatewayUpdateStart.Started("hermes-update", actionId = ACTION_ID, alreadyRunning = false),
        )
        // Two minutes before this apply started: a different run.
        api.actionStatuses += receiptStatus(outcome = "success", startedAt = iso(CLOCK_START - 120_000L))
        api.actionStatuses += receiptStatus(outcome = "success", startedAt = iso(CLOCK_START), finishedAt = null)
        api.actionStatuses += receiptStatus(outcome = "running", startedAt = iso(CLOCK_START))

        controller.apply()
        settle()

        assertEquals(GatewayUpdateStage.Error, controller.state.value.stage)
        assertEquals(0, redials)
    }

    @Test
    fun `a receipt that proves failure ends without a redial`() = updateTest {
        api.startResult = GatewayRestResult.Success(
            GatewayUpdateStart.Started("hermes-update", actionId = ACTION_ID, alreadyRunning = false),
        )
        api.actionStatuses += receiptStatus(outcome = "failed", startedAt = iso(CLOCK_START))

        controller.apply()
        settle()

        assertEquals(GatewayUpdateStage.Error, controller.state.value.stage)
        assertEquals(GatewayUpdateStatusKey.NoReturn, controller.state.value.status)
        assertEquals(0, redials)
    }

    @Test
    fun `a Gateway with no action id falls back to re-checking whether the host moved`() = updateTest {
        // `updates.ts:620-634,728-738`. The check-time version was 0.5.1; the
        // host now reports 0.5.2, so it moved.
        controller.rememberCheck(check(currentVersion = "0.5.1", behind = 3, commits = listOf("abc1234")))
        api.startResult = GatewayRestResult.Success(
            GatewayUpdateStart.Started("hermes-update", actionId = null, alreadyRunning = false),
        )
        api.actionStatuses += finished(exitCode = null)
        api.checkResults += GatewayRestResult.Success(check(currentVersion = "0.5.2", behind = 3))

        controller.apply()
        settle()

        assertEquals(GatewayUpdateStage.Done, controller.state.value.stage)
        assertEquals(1, redials)
    }

    @Test
    fun `the legacy fallback accepts a host that is no longer behind`() = updateTest {
        controller.rememberCheck(check(currentVersion = "0.5.1", behind = 3, commits = listOf("abc1234")))
        api.startResult = GatewayRestResult.Success(
            GatewayUpdateStart.Started("hermes-update", actionId = null, alreadyRunning = false),
        )
        api.actionStatuses += finished(exitCode = null)
        api.checkResults += GatewayRestResult.Success(check(currentVersion = "0.5.1", behind = 0))

        controller.apply()
        settle()

        assertEquals(GatewayUpdateStage.Done, controller.state.value.stage)
    }

    @Test
    fun `a budget exhausted in the restart blackout says the outcome is unknown`() = updateTest {
        // Nothing ever answers again. Ten minutes of virtual time later the
        // apply gives up — and says the one thing that is true, which is that
        // the backend never came back, rather than that the update failed.
        api.actionStatuses += running()
        api.alwaysFail = true

        controller.apply()
        settle()

        val state = controller.state.value
        assertEquals(GatewayUpdateStage.Error, state.stage)
        assertEquals(GatewayUpdateStatusKey.NoReturn, state.status)
        assertFalse(state.applying)
        assertEquals(0, redials)
        // The return budget *replaces* the action deadline the moment the first
        // poll fails (`updates.ts:687-696`), so the apply is bounded by four
        // minutes from the blackout rather than by the six-minute action cap:
        // one running poll, the failing poll that arms it, then four minutes of
        // 1500 ms ticks.
        val blackoutTicks = (RETURN_BUDGET_MILLIS / POLL_INTERVAL_MILLIS).toInt()
        assertEquals(2 + blackoutTicks, waits.size)
    }

    @Test
    fun `host returns after a blackout with less than one poll interval of action budget left`() = updateTest {
        // Run normally for 3 minutes (120 polls * 1500ms = 180s).
        repeat(120) { api.actionStatuses += running() }
        // Blackout starts at 180s. Return budget is 180s + 240s = 420s.
        // 119 failed polls * 1500ms = 178.5s -> total elapsed = 358.5s.
        // Less than one 1500ms poll interval remains of the 360s action budget.
        repeat(119) { api.actionStatuses += GatewayRestResult.Failed(0, "unreachable") }
        // Host returns running at 360s (action budget of 360s exhausted).
        api.actionStatuses += running()

        controller.apply()
        settle()

        val state = controller.state.value
        assertEquals(GatewayUpdateStage.Error, state.stage)
        // Reconnecting was cleared on the resumed poll, so the exhausted action
        // budget reports Failed rather than NoReturn (`updates.ts:702-711`).
        assertEquals(GatewayUpdateStatusKey.Failed, state.status)
        assertFalse(state.applying)
        assertEquals(0, redials)
    }

    @Test
    fun `a non-zero exit code is a failed update, not an absent one`() = updateTest {
        api.actionStatuses += finished(exitCode = 1L)

        controller.apply()
        settle()

        assertEquals(GatewayUpdateStage.Error, controller.state.value.stage)
        assertEquals(GatewayUpdateStatusKey.Failed, controller.state.value.status)
        assertEquals(0, redials)
    }

    @Test
    fun `an update the host was already running is adopted rather than started twice`() = updateTest {
        api.startResult = GatewayRestResult.Success(
            GatewayUpdateStart.Started("hermes-update", actionId = ACTION_ID, alreadyRunning = true),
        )
        api.actionStatuses += finished(exitCode = 0L)

        controller.apply()
        settle()

        assertEquals(1, api.startCalls)
        assertEquals(GatewayUpdateStage.Done, controller.state.value.stage)
    }

    @Test
    fun `a second tap joins the apply in flight instead of starting another`() = updateTest {
        api.actionStatuses += running()
        api.actionStatuses += finished(exitCode = 0L)

        val first = controller.apply()
        runCurrent()
        val second = controller.apply()

        assertTrue(first === second)
        settle()
        assertEquals(1, api.startCalls)
    }

    @Test
    fun `log lines are redacted, capped and kept newest-last`() = updateTest {
        api.actionStatuses += GatewayRestResult.Success(
            GatewayActionStatus(
                name = "hermes-update",
                running = true,
                exitCode = null,
                actionId = null,
                lines = listOf(
                    "",
                    "   ",
                    "Authorization: Bearer sk-live-should-not-survive",
                    "x".repeat(MAX_BACKEND_LINE_CHARS + 500),
                ),
                receipt = null,
            ),
        )
        api.actionStatuses += finished(exitCode = 0L)

        controller.apply()
        settle()

        // The state at the end is the Done state; capture the log from the
        // publish that happened while it was applying.
        val log = loggedLines
        assertEquals(2, log.size)
        assertFalse(log.any { it.contains("sk-live-should-not-survive") })
        assertTrue(log.first().contains("<redacted>"))
        assertEquals(MAX_BACKEND_LINE_CHARS, log.last().length)
    }

    @Test
    fun `reset forgets the endpoint and cancels the apply that belonged to it`() = updateTest {
        api.actionStatuses += running()
        api.actionStatuses += running()
        api.actionStatuses += finished(exitCode = 0L)

        val job = controller.apply()
        runCurrent()
        advanceThroughPolls(1)
        assertEquals(GatewayUpdateStage.Pull, controller.state.value.stage)

        controller.reset()
        settle()

        assertTrue(job.isCancelled)
        assertEquals(GatewayUpdateState(), controller.state.value)
        // The apply died with its endpoint; nothing was redialled on the
        // connection that has since been replaced.
        assertEquals(0, redials)
    }

    @Test
    fun `the durable receipt is read once the apply is terminal`() = updateTest {
        api.actionStatuses += finished(exitCode = 0L)
        api.receipt = GatewayRestResult.Success(
            GatewayUpdateReceipt(
                outcome = "success",
                preVersion = "0.5.1",
                postVersion = "0.5.2",
                serveUnitsVerified = listOf("unit-a"),
                serveUnitsFailed = emptyList(),
                staleRuntimes = 0,
            ),
        )

        controller.apply()
        settle()

        assertEquals("0.5.1", controller.state.value.receipt?.preVersion)
        assertEquals("0.5.2", controller.state.value.receipt?.postVersion)
    }

    // -----------------------------------------------------------------------
    // Harness.
    // -----------------------------------------------------------------------

    private lateinit var controller: GatewayUpdateController
    private lateinit var api: RecordingSystemApi
    private var redials = 0
    private var clock = CLOCK_START
    private val waits = mutableListOf<Long>()
    private val loggedLines = mutableListOf<String>()

    /**
     * Every test runs on one scheduler with an injected clock, so "ten minutes
     * later" is a scheduler advance and never a real wait. The clock moves with
     * the scheduler, because the controller's deadlines are wall clock and its
     * cadence is scheduler time — a fake clock that stood still would make the
     * budget infinite.
     */
    private fun updateTest(body: suspend TestScope.() -> Unit) = runTest {
        api = RecordingSystemApi()
        controller = GatewayUpdateController(
            scope = backgroundScope,
            api = api,
            redial = { redials += 1 },
            nowMillis = { clock },
            wait = { millis ->
                waits += millis
                delay(millis)
                clock += millis
            },
        )
        backgroundScope.launch {
            controller.state.collect { state ->
                if (state.log.isNotEmpty()) {
                    loggedLines.clear()
                    loggedLines += state.log
                }
            }
        }
        body()
    }

    /**
     * Run the apply to whatever end it reaches.
     *
     * `advanceUntilIdle()` cannot be used here: it advances only while a
     * *foreground* task remains, and this controller is app-scoped by design —
     * its coroutine lives in `backgroundScope`, exactly as it lives in the
     * application scope in production. Advancing the clock past every budget
     * this engine has drives it instead, which is also a truer statement of
     * what the test means.
     */
    private fun TestScope.settle() {
        advanceTimeBy(SETTLE_MILLIS)
        runCurrent()
    }

    /** Move the scheduler forward by whole poll intervals. */
    private fun TestScope.advanceThroughPolls(count: Int) {
        repeat(count) {
            advanceTimeBy(POLL_INTERVAL_MILLIS)
            runCurrent()
        }
    }

    private fun running() = GatewayRestResult.Success(
        GatewayActionStatus("hermes-update", running = true, exitCode = null, actionId = null, lines = emptyList(), receipt = null),
    )

    private fun finished(exitCode: Long?) = GatewayRestResult.Success(
        GatewayActionStatus("hermes-update", running = false, exitCode = exitCode, actionId = null, lines = emptyList(), receipt = null),
    )

    private fun receiptStatus(
        outcome: String,
        startedAt: String,
        finishedAt: String? = iso(CLOCK_START + 240_000L),
    ) = GatewayRestResult.Success(
        GatewayActionStatus(
            name = "hermes-update",
            running = false,
            exitCode = null,
            actionId = ACTION_ID,
            lines = emptyList(),
            receipt = GatewayActionReceipt(
                outcome = outcome,
                startedAt = startedAt,
                finishedAt = finishedAt,
                postVersion = "0.5.2",
            ),
        ),
    )

    private fun check(currentVersion: String, behind: Long, commits: List<String> = emptyList()) =
        GatewayUpdateCheck(
            installMethod = "git",
            currentVersion = currentVersion,
            behind = behind,
            updateAvailable = behind > 0,
            canApply = true,
            updateCommand = "git pull",
            message = null,
            commits = commits.map { GatewayUpdateCommit(it, "feat: something") },
        )

    /** The host writes `datetime.now(timezone.utc).isoformat()` (`update_receipt.py:53`). */
    private fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    private class RecordingSystemApi : GatewaySystemApi {
        var startResult: GatewayRestResult<GatewayUpdateStart> = GatewayRestResult.Success(
            GatewayUpdateStart.Started("hermes-update", actionId = null, alreadyRunning = false),
        )
        val actionStatuses = ArrayDeque<GatewayRestResult<GatewayActionStatus>>()
        val checkResults = ArrayDeque<GatewayRestResult<GatewayUpdateCheck>>()
        var receipt: GatewayRestResult<GatewayUpdateReceipt> =
            GatewayRestResult.Failed(404, "no receipt")
        var alwaysFail = false
        var startCalls = 0
        var actionStatusCalls = 0

        override suspend fun status(): GatewayRestResult<GatewayStatusSummary> =
            GatewayRestResult.Failed(0, "not used here")

        override suspend fun checkUpdate(force: Boolean): GatewayRestResult<GatewayUpdateCheck> =
            checkResults.pollFirst() ?: GatewayRestResult.Failed(0, "no check scripted")

        override suspend fun startUpdate(): GatewayRestResult<GatewayUpdateStart> {
            startCalls += 1
            return startResult
        }

        override suspend fun actionStatus(
            action: GatewayAction,
            lines: Int,
        ): GatewayRestResult<GatewayActionStatus> {
            actionStatusCalls += 1
            val scripted = actionStatuses.pollFirst()
            return when {
                scripted != null -> scripted
                alwaysFail -> GatewayRestResult.Failed(0, "unreachable")
                else -> GatewayRestResult.Failed(0, "no status scripted")
            }
        }

        override suspend fun updateReceipt(): GatewayRestResult<GatewayUpdateReceipt> = receipt

        override suspend fun restartGateway(): GatewayRestResult<GatewayRestartStart> =
            GatewayRestResult.Failed(0, "not used here")
    }

    private companion object {
        const val ACTION_ID = "0123456789abcdef0123456789abcdef"

        /** A fixed instant, so a receipt timestamp is arithmetic rather than a race. */
        const val CLOCK_START = 1_780_000_000_000L

        /** Longer than the six-minute action budget plus the four-minute return one. */
        const val SETTLE_MILLIS = 15 * 60 * 1_000L
    }
}
