package com.hermesagent.mobile.device

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CurrentAnrCandidateTest {
    private val app = CurrentAnrCandidate.Identity(42, 10001, "synthetic.app")
    private fun row(name: String? = app.name, pid: Int = app.pid, uid: Int = app.uid,
                    msg: String? = "ANR Input dispatching timed out", condition: Int = 2) =
        CurrentAnrCandidate.Row(condition, name, pid, uid, msg)
    private fun reduce(vararg rows: CurrentAnrCandidate.Row?) = CurrentAnrCandidate.reduce(rows.toList(), app)

    @Test fun soleAndRoles() {
        val result = reduce(row())
        assertEquals(CurrentAnrCandidate.Status.CURRENT_ANR_CANDIDATE, result.status)
        assertEquals(CurrentAnrCandidate.Role.APP_PROCESS, result.role)
        assertEquals(CurrentAnrCandidate.Reason.INPUT_DISPATCH_TIMEOUT, result.reason)
        assertEquals(CurrentAnrCandidate.Role.UNKNOWN, reduce(row(pid = 43)).role)
        assertEquals(CurrentAnrCandidate.Role.UNKNOWN, reduce(row(uid = 10002)).role)
        assertEquals(CurrentAnrCandidate.Role.UNKNOWN, reduce(row(name = "synthetic.app:other")).role)
        assertEquals(CurrentAnrCandidate.Role.SYSTEM_UI, reduce(row(name = "com.android.systemui")).role)
    }
    @Test fun nullEmptyMultipleAndInvalid() {
        assertEquals(CurrentAnrCandidate.Cause.NO_RECORD, CurrentAnrCandidate.reduce(null, app).cause)
        assertEquals(CurrentAnrCandidate.Cause.NO_RECORD, reduce().cause)
        assertEquals(CurrentAnrCandidate.Cause.NO_RECORD, reduce(row(condition = 1)).cause)
        assertEquals(CurrentAnrCandidate.Cause.MULTIPLE, reduce(row(), row()).cause)
        assertEquals(CurrentAnrCandidate.Cause.MULTIPLE, reduce(row(), row(name = "other")).cause)
        for (bad in listOf(null, row(pid = -1), row(uid = -1), row(name = null), row(name = ""),
            row(name = "bad\nname"), row(name = "x".repeat(257)), row(msg = "x".repeat(4097)),
            row(msg = "ANR\nInput dispatching timed out"), row(condition = 3))) {
            assertEquals(CurrentAnrCandidate.Cause.INVALID_RECORD, reduce(bad).cause)
        }
        assertEquals(CurrentAnrCandidate.Cause.OVERFLOW,
            CurrentAnrCandidate.reduce(List(33) { row() }, app).cause)
        assertEquals(CurrentAnrCandidate.Role.UNKNOWN, reduce(row(pid = 0)).role)
        assertEquals(CurrentAnrCandidate.Status.CURRENT_ANR_CANDIDATE,
            reduce(row(), row(condition = 1)).status)
    }
    @Test fun exactReasonOnly() {
        for (msg in listOf(null, "", "ANR", "ANR input dispatching timed out", "prefix ANR Input dispatching timed out",
            "ANR Input dispatching timed out trailing", "ANR Input dispatching timed out (reason", "ANR other")) {
            assertEquals(CurrentAnrCandidate.Reason.UNKNOWN, reduce(row(msg = msg)).reason)
        }
        assertEquals(CurrentAnrCandidate.Reason.INPUT_DISPATCH_TIMEOUT,
            reduce(row(msg = "ANR Input dispatching timed out (synthetic)")).reason)
        assertEquals(CurrentAnrCandidate.Status.CURRENT_ANR_CANDIDATE,
            reduce(row(name = "x".repeat(256), msg = "x".repeat(4096))).status)
        for (control in listOf('\u0000', '\u007f', '\u0085', '\u2028', '\u2029'))
            assertEquals(CurrentAnrCandidate.Cause.INVALID_RECORD, reduce(row(msg = "ANR$control")).cause)
    }

    private class Harness {
        val calls = mutableListOf<String>()
        var pending: (() -> Unit)? = null
        var wait: (() -> Unit)? = null
        var failure: String? = null
        val api = object : CurrentAnrCandidate.Api {
            override fun adopt() { calls += "adopt"; if (failure == "adopt") error("private") }
            override fun query(): CurrentAnrCandidate.Result {
                calls += "query"; if (failure == "query") error("private")
                return CurrentAnrCandidate.Result(CurrentAnrCandidate.Status.CURRENT_ANR_CANDIDATE)
            }
            override fun drop() { calls += "drop"; if (failure == "drop") error("private") }
        }
        val collector = CurrentAnrCandidate.Collector(
            launch = { pending = it },
            await = { latch, millis ->
                assertEquals(2500L, millis)
                wait?.invoke()
                latch.count == 0L
            },
        )
        fun finish() { pending!!.invoke(); pending = null }
        fun collect(enabled: Boolean = true) = collector.collect(enabled, api)
    }
    @Test fun optOutDoesNotConsumeAttemptOrCallApi() {
        val h = Harness()
        assertEquals(CurrentAnrCandidate.Cause.NOT_ATTESTED, h.collect(false).cause)
        assertTrue(h.calls.isEmpty()); assertNull(h.pending)
        h.wait = { h.finish() }
        assertEquals(CurrentAnrCandidate.Status.CURRENT_ANR_CANDIDATE, h.collect().status)
        assertEquals(listOf("adopt", "query", "drop"), h.calls)
        assertEquals(CurrentAnrCandidate.Cause.ALREADY_ATTEMPTED, h.collect().cause)
        assertEquals(3, h.calls.size)
    }
    @Test fun failuresCleanupAndOriginalThrowable() {
        for (stage in listOf("adopt", "query", "drop")) {
            val h = Harness(); h.failure = stage; h.wait = { h.finish() }
            var result: CurrentAnrCandidate.Result? = null
            val original = AssertionError("original")
            try {
                observeReadinessFailure({ result = h.collect(); error("sink") }) { throw original }
                fail("missing original")
            } catch (caught: Throwable) { assertSame(original, caught) }
            assertEquals(if (stage == "drop") CurrentAnrCandidate.Cause.CLEANUP_FAILED
                else CurrentAnrCandidate.Cause.ACQUISITION_FAILED, result!!.cause)
            assertEquals(CurrentAnrCandidate.Status.UNKNOWN, result!!.status)
            assertEquals("drop", h.calls.last())
            assertEquals(CurrentAnrCandidate.Cause.ALREADY_ATTEMPTED, h.collect().cause)
        }
    }
    @Test fun timeoutDiscardsLateCompletionAndNeverRetries() {
        val h = Harness()
        assertEquals(CurrentAnrCandidate.Cause.TIMEOUT, h.collect().cause)
        assertTrue(h.calls.isEmpty())
        assertEquals(CurrentAnrCandidate.Cause.ALREADY_ATTEMPTED, h.collect().cause)
        h.finish()
        assertEquals(listOf("adopt", "query", "drop"), h.calls)
        assertEquals(CurrentAnrCandidate.Cause.ALREADY_ATTEMPTED, h.collect().cause)
        assertEquals(3, h.calls.size)
    }
    @Test fun cleanupMustCompleteBeforeAcceptance() {
        val h = Harness()
        h.wait = { assertTrue(h.calls.isEmpty()); h.finish() }
        assertEquals(CurrentAnrCandidate.Status.CURRENT_ANR_CANDIDATE, h.collect().status)
        assertEquals("drop", h.calls.last())
    }

    @Test fun virtualDeadlineWhileEachApiStageIsBlocked() {
        // Latches schedule stage boundaries, not delays. Observer time is injected;
        // five-second awaits are deadlock guards only, never a timeout under test.
        for (stage in listOf("adopt", "query", "drop")) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val calls = mutableListOf<String>()
            fun call(name: String) {
                calls += name
                if (stage == name) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            val api = object : CurrentAnrCandidate.Api {
                override fun adopt() = call("adopt")
                override fun query(): CurrentAnrCandidate.Result {
                    call("query")
                    return CurrentAnrCandidate.Result(CurrentAnrCandidate.Status.CURRENT_ANR_CANDIDATE)
                }
                override fun drop() = call("drop")
            }
            var worker: Thread? = null
            val collector = CurrentAnrCandidate.Collector(
                launch = { work ->
                    worker = Thread { try { work() } finally { finished.countDown() } }
                        .apply { isDaemon = true; start() }
                },
                await = { _, millis ->
                    assertEquals(2500L, millis)
                    check(entered.await(5, TimeUnit.SECONDS))
                    false // Advance the virtual observer deadline while this API is in flight.
                },
            )
            val original = AssertionError("readiness")
            var observed: CurrentAnrCandidate.Result? = null
            try {
                observeReadinessFailure({ observed = collector.collect(true, api) }) { throw original }
                fail("missing original")
            } catch (caught: Throwable) { assertSame(original, caught) }
            finally { release.countDown() }
            assertEquals(CurrentAnrCandidate.Cause.TIMEOUT, observed?.cause)
            assertEquals(CurrentAnrCandidate.Cause.ALREADY_ATTEMPTED, collector.collect(true, api).cause)
            check(finished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("adopt", "query", "drop"), calls)
            assertFalse(worker!!.isInterrupted)
            assertEquals(CurrentAnrCandidate.Cause.ALREADY_ATTEMPTED, collector.collect(true, api).cause)
        }
    }

    @Test fun deadlineWinsEvenIfWorkerCompletesAtBoundary() {
        val h = Harness()
        val collector = CurrentAnrCandidate.Collector(
            launch = { h.pending = it },
            await = { _, _ -> h.finish(); false },
        )
        assertEquals(CurrentAnrCandidate.Cause.TIMEOUT, collector.collect(true, h.api).cause)
        assertEquals(CurrentAnrCandidate.Cause.ALREADY_ATTEMPTED, collector.collect(true, h.api).cause)
    }

    @Test fun concurrentCallersLaunchOnlyOneWorker() {
        val h = Harness()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = java.util.Collections.synchronizedList(mutableListOf<CurrentAnrCandidate.Cause>())
        repeat(2) {
            Thread {
                try {
                    check(start.await(5, TimeUnit.SECONDS))
                    results += h.collect().cause
                } finally { done.countDown() }
            }.apply { isDaemon = true; start() }
        }
        start.countDown()
        check(done.await(5, TimeUnit.SECONDS))
        assertEquals(setOf(CurrentAnrCandidate.Cause.TIMEOUT, CurrentAnrCandidate.Cause.ALREADY_ATTEMPTED), results.toSet())
        h.finish()
        assertEquals(listOf("adopt", "query", "drop"), h.calls)
    }

    @Test fun launchFailureConsumesAttemptWithoutPermissionCalls() {
        val h = Harness()
        val collector = CurrentAnrCandidate.Collector(launch = { error("private") })
        assertEquals(CurrentAnrCandidate.Cause.ACQUISITION_FAILED, collector.collect(true, h.api).cause)
        assertEquals(CurrentAnrCandidate.Cause.ALREADY_ATTEMPTED, collector.collect(true, h.api).cause)
        assertTrue(h.calls.isEmpty())
    }

    @Test fun interruptedObserverDiscardsLateResultAndPreservesInterrupt() {
        val h = Harness()
        val collector = CurrentAnrCandidate.Collector(
            launch = { h.pending = it }, await = { _, _ -> throw InterruptedException("private") },
        )
        try {
            assertEquals(CurrentAnrCandidate.Cause.TIMEOUT, collector.collect(true, h.api).cause)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        h.finish()
        assertEquals(listOf("adopt", "query", "drop"), h.calls)
        assertEquals(CurrentAnrCandidate.Cause.ALREADY_ATTEMPTED, collector.collect(true, h.api).cause)
    }
}
