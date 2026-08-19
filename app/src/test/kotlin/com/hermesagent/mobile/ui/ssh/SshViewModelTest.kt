package com.hermesagent.mobile.ui.ssh

import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.ssh.FakeSshProbe
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.HostProfileStore
import com.hermesagent.mobile.data.ssh.KeyImportProblem
import com.hermesagent.mobile.data.ssh.ProbeFailure
import com.hermesagent.mobile.data.ssh.ProbeResult
import com.hermesagent.mobile.data.ssh.SshCredential
import com.hermesagent.mobile.data.ssh.SshProbe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The onboarding journey, end to end, against the deterministic probe:
 * first use → review → accept → retry → success, plus the changed-key hard
 * stop — and what happens when the form moves while work is in flight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SshViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = InMemoryHostProfileStore()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(probe: SshProbe) = SshViewModel(store, probe)

    /** A destination plus a password: the pre-Tailscale-SSH baseline. */
    private fun SshViewModel.fillValidProfile() {
        setDestination("hermes@hermes-box.local")
        setAuthMethod(AuthMethod.Password)
        setPassword("s3cret")
    }

    private fun privateKey(): CharArray =
        ("-----BEGIN OPENSSH PRIVATE KEY-----\nsecretkeymaterial\n-----END OPENSSH PRIVATE KEY-----\n")
            .toCharArray()

    @Test
    fun `a first probe stops at the fingerprint review instead of connecting`() = runTest(dispatcher) {
        val probe = FakeSshProbe()
        val vm = viewModel(probe)
        vm.fillValidProfile()

        vm.runProbe()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(FakeSshProbe.DEFAULT_FINGERPRINT, state.pendingHostKey?.fingerprint)
        assertEquals("ssh-ed25519", state.pendingHostKey?.keyType)
        assertEquals(ProbeStatus.Idle, state.status)
        assertNull("nothing is trusted until the user says so", state.profile.acceptedFingerprint)
    }

    @Test
    fun `accepting the fingerprint persists it and the retry succeeds`() = runTest(dispatcher) {
        val probe = FakeSshProbe()
        val vm = viewModel(probe)
        vm.fillValidProfile()
        vm.runProbe()
        advanceUntilIdle()

        vm.acceptPendingHostKey()
        advanceUntilIdle()

        assertEquals(FakeSshProbe.DEFAULT_FINGERPRINT, vm.uiState.value.profile.acceptedFingerprint)
        assertEquals(
            "the accepted fingerprint must reach the store",
            FakeSshProbe.DEFAULT_FINGERPRINT,
            store.saved.value.acceptedFingerprint,
        )
        assertNull(vm.uiState.value.pendingHostKey)

        vm.runProbe()
        advanceUntilIdle()

        val status = vm.uiState.value.status
        assertTrue("expected success, got $status", status is ProbeStatus.Succeeded)
        assertEquals(SshProbe.EXPECTED_OUTPUT, (status as ProbeStatus.Succeeded).output)
    }

    @Test
    fun `a changed host key is terminal and offers no accept path`() = runTest(dispatcher) {
        store.saved.value = HostProfile(
            host = "hermes-box.local",
            username = "hermes",
            acceptedFingerprint = "SHA256:previouslyTrustedFingerprintNOTtheOneOffered",
        )
        val vm = viewModel(FakeSshProbe())
        advanceUntilIdle()

        vm.runProbe()
        advanceUntilIdle()

        val status = vm.uiState.value.status
        assertTrue("expected a mismatch, got $status", status is ProbeStatus.KeyMismatch)
        assertEquals(FakeSshProbe.DEFAULT_FINGERPRINT, (status as ProbeStatus.KeyMismatch).presented)
        assertNull("a mismatch must never become a pending prompt", vm.uiState.value.pendingHostKey)

        // The only escape is deliberately forgetting the old key.
        vm.acceptPendingHostKey()
        assertEquals(
            "previously trusted key must still be in place",
            "SHA256:previouslyTrustedFingerprintNOTtheOneOffered",
            vm.uiState.value.profile.acceptedFingerprint,
        )
    }

    @Test
    fun `forgetting the stored key returns the host to first use`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        vm.fillValidProfile()
        vm.runProbe()
        advanceUntilIdle()
        vm.acceptPendingHostKey()
        advanceUntilIdle()

        vm.forgetAcceptedHostKey()
        advanceUntilIdle()
        vm.runProbe()
        advanceUntilIdle()

        assertEquals(FakeSshProbe.DEFAULT_FINGERPRINT, vm.uiState.value.pendingHostKey?.fingerprint)
    }

    @Test
    fun `an auth failure is reported without echoing the credential`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe(authSucceeds = false))
        vm.fillValidProfile()
        vm.setPassword("hunter2correcthorse")
        vm.runProbe()
        advanceUntilIdle()
        vm.acceptPendingHostKey()
        advanceUntilIdle()

        vm.runProbe()
        advanceUntilIdle()

        val status = vm.uiState.value.status as ProbeStatus.Failed
        assertEquals(ProbeFailure.AuthFailed, status.kind)
        assertFalse(status.message.contains("hunter2correcthorse"))
    }

    @Test
    fun `cancelling a running probe reports cancellation`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe(delayMillis = 10_000))
        vm.fillValidProfile()
        vm.runProbe()
        advanceTimeBy(100)
        assertEquals(ProbeStatus.Running, vm.uiState.value.status)

        vm.cancelProbe()
        advanceUntilIdle()

        assertEquals(ProbeFailure.Cancelled, (vm.uiState.value.status as ProbeStatus.Failed).kind)
    }

    @Test
    fun `probing is refused until the destination parses and a credential is present`() = runTest(dispatcher) {
        val probe = FakeSshProbe()
        val vm = viewModel(probe)

        vm.runProbe()
        advanceUntilIdle()
        assertTrue("an empty destination must not dial", probe.calls.isEmpty())

        vm.setDestination("hermes@")
        vm.setAuthMethod(AuthMethod.Password)
        vm.runProbe()
        advanceUntilIdle()
        assertTrue("a half-typed destination must not dial", probe.calls.isEmpty())

        vm.setDestination("hermes@hermes-box.local")
        vm.runProbe()
        advanceUntilIdle()
        assertTrue("no credential means no dial", probe.calls.isEmpty())

        vm.setPassword("s3cret")
        vm.runProbe()
        advanceUntilIdle()
        assertEquals(1, probe.calls.size)
    }

    @Test
    fun `the destination field is the only host, port and username input`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())

        vm.setDestination("  test-user@test-host  ")
        advanceUntilIdle()

        val implicit = vm.uiState.value.profile
        assertEquals("test-user", implicit.username)
        assertEquals("test-host", implicit.host)
        assertEquals("nobody should have to type port 22", 22, implicit.port)
        assertNull(vm.uiState.value.destinationError)

        vm.setDestination("test-user@test-host:2222")
        advanceUntilIdle()
        assertEquals(2222, vm.uiState.value.profile.port)
    }

    @Test
    fun `an unparseable destination is shown, not saved`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        vm.setDestination("hermes@hermes-box.local")
        advanceUntilIdle()

        vm.setDestination("hermes@hermes box")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("the field keeps what was typed", "hermes@hermes box", state.destination)
        assertNotNull("and says why it cannot be used", state.destinationError)
        assertFalse(state.canProbe)
        assertEquals(
            "a broken edit must not overwrite a working profile",
            "hermes-box.local",
            store.saved.value.host,
        )
    }

    @Test
    fun `an emptied destination cannot quietly dial the last host`() = runTest(dispatcher) {
        val probe = FakeSshProbe()
        val vm = viewModel(probe)
        vm.setDestination("hermes@hermes-box.local")
        advanceUntilIdle()

        vm.setDestination("   ")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNull("a blank field is not worth an error message", state.destinationError)
        assertFalse("but it is not something to dial either", state.canProbe)

        vm.runProbe()
        advanceUntilIdle()
        assertTrue(probe.calls.isEmpty())
    }

    @Test
    fun `editing only the username keeps the accepted fingerprint`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        vm.setDestination("hermes@hermes-box")
        vm.runProbe()
        advanceUntilIdle()
        vm.acceptPendingHostKey()
        advanceUntilIdle()

        vm.setDestination("test-user@hermes-box")
        advanceUntilIdle()
        assertEquals(FakeSshProbe.DEFAULT_FINGERPRINT, store.saved.value.acceptedFingerprint)

        vm.setDestination("test-user@other-box")
        advanceUntilIdle()
        assertNull("a new host has to be reviewed again", store.saved.value.acceptedFingerprint)
    }

    // ── Work is bound to the form that started it ─────────────────────────────

    @Test
    fun `retargeting the destination takes the review with it`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        vm.setDestination("hermes@host-a")
        vm.runProbe()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.pendingHostKey)

        vm.setDestination("hermes@host-b")
        advanceUntilIdle()

        assertNull(
            "a fingerprint offered by host-a is not a decision about host-b",
            vm.uiState.value.pendingHostKey,
        )
    }

    @Test
    fun `accepting after a retarget cannot file one host's key under another`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        vm.setDestination("hermes@host-a")
        vm.runProbe()
        advanceUntilIdle()

        vm.setDestination("hermes@host-b")
        advanceUntilIdle()
        vm.acceptPendingHostKey()
        advanceUntilIdle()

        assertEquals("host-b", store.saved.value.host)
        assertNull("host-a's key must not be stored for host-b", store.saved.value.acceptedFingerprint)
        assertNull(vm.uiState.value.profile.acceptedFingerprint)
    }

    @Test
    fun `an invalid or blank destination cannot accept an old review`() = runTest(dispatcher) {
        for (destination in listOf("hermes@host a", "")) {
            val vm = viewModel(FakeSshProbe())
            vm.setDestination("hermes@host-a")
            vm.runProbe()
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.pendingHostKey)

            vm.setDestination(destination)
            vm.acceptPendingHostKey()
            advanceUntilIdle()

            assertNull("$destination must not retain host-a's review", vm.uiState.value.pendingHostKey)
            assertNull("$destination must not trust a host it cannot name", vm.uiState.value.profile.acceptedFingerprint)
        }
    }

    @Test
    fun `changing only the port also invalidates the review`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        vm.setDestination("hermes@host-a")
        vm.runProbe()
        advanceUntilIdle()

        vm.setDestination("hermes@host-a:2222")
        advanceUntilIdle()

        assertNull("a different port can be a different sshd", vm.uiState.value.pendingHostKey)
    }

    @Test
    fun `renaming the user keeps the review, because the box has not moved`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        vm.setDestination("hermes@host-a")
        vm.runProbe()
        advanceUntilIdle()

        vm.setDestination("test-user@host-a")
        advanceUntilIdle()
        vm.acceptPendingHostKey()
        advanceUntilIdle()

        assertEquals(FakeSshProbe.DEFAULT_FINGERPRINT, store.saved.value.acceptedFingerprint)
        assertEquals("test-user", store.saved.value.username)
    }

    @Test
    fun `an answer that arrives after the destination moved does not paint the screen`() =
        runTest(dispatcher) {
            val probe = ManualProbe()
            val vm = viewModel(probe)
            vm.setDestination("hermes@host-a")
            vm.runProbe()
            advanceUntilIdle()
            assertEquals(ProbeStatus.Running, vm.uiState.value.status)

            vm.setDestination("hermes@host-b")
            advanceUntilIdle()

            // The probe ignored the cancellation and answered anyway, which is
            // exactly what a blocking transport does.
            probe.finish(ProbeResult.Ok(SshProbe.EXPECTED_OUTPUT, "SSH-2.0-OpenSSH_9.6", 12))
            advanceUntilIdle()

            assertEquals(
                "a result about host-a must not report success for host-b",
                ProbeStatus.Idle,
                vm.uiState.value.status,
            )
            assertNull(vm.uiState.value.pendingHostKey)
        }

    @Test
    fun `an answer that arrives after cancelling does not undo the cancellation`() = runTest(dispatcher) {
        val probe = ManualProbe()
        val vm = viewModel(probe)
        vm.setDestination("hermes@host-a")
        vm.runProbe()
        advanceUntilIdle()

        vm.cancelProbe()
        advanceUntilIdle()
        probe.finish(ProbeResult.Ok(SshProbe.EXPECTED_OUTPUT, "SSH-2.0-OpenSSH_9.6", 12))
        advanceUntilIdle()

        assertEquals(
            ProbeFailure.Cancelled,
            (vm.uiState.value.status as ProbeStatus.Failed).kind,
        )
    }

    @Test
    fun `a review that arrives after the destination moved is never shown`() = runTest(dispatcher) {
        val probe = ManualProbe()
        val vm = viewModel(probe)
        vm.setDestination("hermes@host-a")
        vm.runProbe()
        advanceUntilIdle()

        vm.setDestination("hermes@host-b")
        advanceUntilIdle()
        probe.finish(ProbeResult.HostKeyPending("SHA256:aKeyHostBneverOffered", "ssh-ed25519"))
        advanceUntilIdle()

        assertNull(vm.uiState.value.pendingHostKey)
        assertNull(vm.uiState.value.hostKeyReview)
    }

    @Test
    fun `changing the auth method stops a probe that is already running`() = runTest(dispatcher) {
        val probe = FakeSshProbe(delayMillis = 10_000)
        val vm = viewModel(probe)
        vm.fillValidProfile()
        vm.runProbe()
        advanceTimeBy(100)
        assertEquals(ProbeStatus.Running, vm.uiState.value.status)

        vm.setAuthMethod(AuthMethod.TailscaleSsh)
        advanceUntilIdle()

        assertEquals(
            "the credential in flight is not the one the form now describes",
            ProbeStatus.Idle,
            vm.uiState.value.status,
        )
    }

    @Test
    fun `editing a credential stops and invalidates the old probe`() = runTest(dispatcher) {
        val probe = ManualProbe()
        val vm = viewModel(probe)
        vm.fillValidProfile()
        vm.runProbe()
        advanceUntilIdle()

        vm.setPassword("replacement")
        probe.finish(ProbeResult.Ok(SshProbe.EXPECTED_OUTPUT, "SSH-2.0-test", 1))
        advanceUntilIdle()

        assertEquals(ProbeStatus.Idle, vm.uiState.value.status)
        assertEquals("replacement", vm.uiState.value.password)
    }

    // ── The screen stops holding a secret once a probe has used it ────────────

    @Test
    fun `the state never prints the password or the passphrase`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        vm.setPassword("hunter2correcthorse")
        vm.setKeyPassphrase("passphrase-value")
        advanceUntilIdle()

        val printed = vm.uiState.value.toString()

        assertFalse("a state snapshot is a log line waiting to happen", printed.contains("hunter2correcthorse"))
        assertFalse(printed.contains("passphrase-value"))
        assertTrue(printed.contains("<redacted>"))
    }

    @Test
    fun `a fingerprint review keeps the credential, because nothing was sent`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        vm.fillValidProfile()

        vm.runProbe()
        advanceUntilIdle()

        assertEquals("accept-then-retry must not ask for the password again", "s3cret", vm.uiState.value.password)
    }

    @Test
    fun `a completed probe stops the screen holding the password`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        vm.fillValidProfile()
        vm.runProbe()
        advanceUntilIdle()
        vm.acceptPendingHostKey()
        advanceUntilIdle()

        vm.runProbe()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.status is ProbeStatus.Succeeded)
        assertEquals("", vm.uiState.value.password)
        assertFalse("and cannot silently dial again with it", vm.uiState.value.canProbe)
    }

    @Test
    fun `a refused authentication also drops the credential`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe(authSucceeds = false))
        vm.fillValidProfile()
        vm.runProbe()
        advanceUntilIdle()
        vm.acceptPendingHostKey()
        advanceUntilIdle()

        vm.runProbe()
        advanceUntilIdle()

        assertEquals(ProbeFailure.AuthFailed, (vm.uiState.value.status as ProbeStatus.Failed).kind)
        assertEquals("", vm.uiState.value.password)
    }

    @Test
    fun `cancelling drops the credential the probe was given`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe(delayMillis = 10_000))
        vm.fillValidProfile()
        vm.setKeyPassphrase("passphrase-value")
        vm.runProbe()
        advanceTimeBy(100)

        vm.cancelProbe()
        advanceUntilIdle()

        assertEquals("", vm.uiState.value.password)
        assertEquals("", vm.uiState.value.keyPassphrase)
    }

    @Test
    fun `the imported key is zeroed, not merely dropped`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        val pem = privateKey()
        vm.setDestination("hermes@hermes-box")
        vm.importPrivateKey(pem, "id_ed25519")
        advanceUntilIdle()

        vm.runProbe()
        advanceUntilIdle()
        vm.acceptPendingHostKey()
        advanceUntilIdle()
        vm.runProbe()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.status is ProbeStatus.Succeeded)
        assertTrue("the key material must be wiped, not garbage", pem.all { it == '\u0000' })
        assertFalse(vm.uiState.value.privateKeyLoaded)
        assertNull("and the label for a key that is gone is a lie", store.saved.value.importedKeyName)
    }

    @Test
    fun `replacing a key wipes the previous valid key immediately`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        val old = privateKey()
        val replacement = privateKey()

        vm.importPrivateKey(old, "old")
        vm.importPrivateKey(replacement, "replacement")
        advanceUntilIdle()

        assertTrue("a valid replacement must wipe the previous key", old.all { it == '\u0000' })
        assertTrue(vm.uiState.value.privateKeyLoaded)
    }

    @Test
    fun `an idle auth-method change wipes hidden key material`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        val key = privateKey()
        vm.importPrivateKey(key, "id_key")
        vm.setKeyPassphrase("passphrase")
        advanceUntilIdle()

        vm.setAuthMethod(AuthMethod.TailscaleSsh)
        advanceUntilIdle()

        assertTrue(key.all { it == '\u0000' })
        assertFalse(vm.uiState.value.privateKeyLoaded)
        assertEquals("", vm.uiState.value.keyPassphrase)
        assertNull(vm.uiState.value.profile.importedKeyName)
    }

    @Test
    fun `cancelling after probe start clears the credential copy`() = runTest(dispatcher) {
        val probe = CapturingCancellableProbe()
        val vm = viewModel(probe)
        val key = privateKey()
        vm.setDestination("hermes@host-a")
        vm.importPrivateKey(key, "id_key")

        vm.runProbe()
        val credential = requireNotNull(probe.credential)
        assertTrue(requireNotNull(credential.privateKey).any { it != '\u0000' })

        vm.cancelProbe()

        assertTrue("the probe-owned copy must be wiped on immediate cancellation",
            requireNotNull(credential.privateKey).all { it == '\u0000' })
    }

    @Test
    fun `editing a passphrase during a key probe clears the old key and cancels`() = runTest(dispatcher) {
        val probe = ManualProbe()
        val vm = viewModel(probe)
        val key = privateKey()
        vm.setDestination("hermes@host-a")
        vm.importPrivateKey(key, "id_key")
        vm.setKeyPassphrase("old-passphrase")
        vm.runProbe()
        advanceUntilIdle()

        vm.setKeyPassphrase("replacement-passphrase")
        probe.finish(ProbeResult.Ok(SshProbe.EXPECTED_OUTPUT, "SSH-2.0-test", 1))
        advanceUntilIdle()

        assertEquals(ProbeStatus.Idle, vm.uiState.value.status)
        assertTrue(key.all { it == '\u0000' })
        assertFalse(vm.uiState.value.privateKeyLoaded)
        assertEquals("", vm.uiState.value.keyPassphrase)
    }

    @Test
    fun `a suspended old save cannot overwrite newer profile intent`() = runTest(dispatcher) {
        val delayedStore = DelayedFirstSaveStore()
        val vm = SshViewModel(delayedStore, FakeSshProbe())

        vm.setDestination("hermes@host-a")
        runCurrent()
        assertTrue("the first save should be held in the store", delayedStore.firstSaveStarted.isCompleted)

        vm.setDestination("hermes@host-b:2222")
        runCurrent()
        delayedStore.releaseFirstSave.complete(Unit)
        advanceUntilIdle()

        assertEquals("host-b", delayedStore.saved.value.host)
        assertEquals(2222, delayedStore.saved.value.port)
    }

    @Test
    fun `forgetting the key wipes it on the spot`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        val pem = privateKey()
        vm.importPrivateKey(pem, "id_ed25519")
        advanceUntilIdle()

        vm.forgetPrivateKey()
        advanceUntilIdle()

        assertTrue(pem.all { it == '\u0000' })
        assertFalse(vm.uiState.value.privateKeyLoaded)
    }

    @Test
    fun `importing a key switches method and never puts the pem in ui state`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())

        vm.importPrivateKey(privateKey(), "id_ed25519")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(AuthMethod.PrivateKey, state.profile.authMethod)
        assertTrue(state.privateKeyLoaded)
        assertEquals("id_ed25519", state.profile.importedKeyName)
        assertFalse(
            "the pem must never be reachable from a state snapshot",
            state.toString().contains("secretkeymaterial"),
        )
    }

    @Test
    fun `a document that is not a key is refused and wiped, with a reason`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        val junk = "read me for advice about your PRIVATE KEY".toCharArray()

        vm.importPrivateKey(junk, "notes.txt")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(KeyImportProblem.NotAPrivateKey, state.keyImportProblem)
        assertFalse("junk must not read as a loaded key", state.privateKeyLoaded)
        assertTrue(junk.all { it == '\u0000' })
    }

    @Test
    fun `a failed import leaves a key that already works alone`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        vm.importPrivateKey(privateKey(), "id_ed25519")
        advanceUntilIdle()

        vm.reportKeyImportProblem(KeyImportProblem.Unreadable)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(KeyImportProblem.Unreadable, state.keyImportProblem)
        assertTrue("a failed replacement is not a reason to forget what works", state.privateKeyLoaded)
    }

    @Test
    fun `the imported key name is sanitised before it is ever shown`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())

        vm.importPrivateKey(privateKey(), "id\u202egnp.exe")
        advanceUntilIdle()

        // The bidi override would render `id_ed25519.txt` as `idtxt.912de_di`
        // in the "Key loaded" row. It is screen state either way — that it is
        // not written to disk is `HermesPreferencesTest`'s claim, not this
        // double's, which stores whatever HostProfile it is handed.
        assertEquals("idgnp.exe", vm.uiState.value.profile.importedKeyName)
    }

    // ── Leaving the screen ────────────────────────────────────────────────

    @Test
    fun `leaving the screen wipes the key, the password and the passphrase`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        val pem = privateKey()
        vm.setDestination("hermes@hermes-box")
        vm.importPrivateKey(pem, "id_ed25519")
        vm.setKeyPassphrase("passphrase-value")
        advanceUntilIdle()

        vm.releaseScreen()

        // Synchronous: no `advanceUntilIdle` before these, on purpose.
        assertTrue("the key must be zeroed, not merely dropped", pem.all { it == '\u0000' })
        assertFalse(vm.uiState.value.privateKeyLoaded)
        assertEquals("", vm.uiState.value.keyPassphrase)
        assertNull(vm.uiState.value.profile.importedKeyName)
    }

    @Test
    fun `leaving the screen cancels a probe and its credential copy`() = runTest(dispatcher) {
        val probe = CapturingCancellableProbe()
        val vm = viewModel(probe)
        vm.setDestination("hermes@hermes-box")
        vm.setAuthMethod(AuthMethod.Password)
        vm.setPassword("s3cret")
        vm.runProbe()

        val credential = requireNotNull(probe.credential)
        vm.releaseScreen()

        assertTrue(
            "the probe-owned copy must be wiped as the screen goes",
            requireNotNull(credential.password).all { it == '\u0000' },
        )
        assertEquals("", vm.uiState.value.password)
        assertEquals(ProbeStatus.Idle, vm.uiState.value.status)
    }

    @Test
    fun `an answer that arrives after leaving the screen never paints it`() = runTest(dispatcher) {
        val probe = ManualProbe()
        val vm = viewModel(probe)
        vm.setDestination("hermes@hermes-box")
        vm.runProbe()
        advanceUntilIdle()

        vm.releaseScreen()
        probe.finish(ProbeResult.Ok(SshProbe.EXPECTED_OUTPUT, "SSH-2.0-test", 12))
        advanceUntilIdle()

        assertEquals(ProbeStatus.Idle, vm.uiState.value.status)
        assertNull(vm.uiState.value.pendingHostKey)
    }

    @Test
    fun `leaving the screen keeps the profile a returning user still needs`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        vm.setDestination("hermes@hermes-box:2222")
        vm.setAuthMethod(AuthMethod.Password)
        vm.setPassword("s3cret")
        vm.runProbe()
        advanceUntilIdle()
        vm.acceptPendingHostKey()
        advanceUntilIdle()

        vm.releaseScreen()
        advanceUntilIdle()

        val profile = vm.uiState.value.profile
        assertEquals("hermes-box", profile.host)
        assertEquals(2222, profile.port)
        assertEquals("hermes", profile.username)
        assertEquals(AuthMethod.Password, profile.authMethod)
        assertEquals(
            "a fingerprint reviewed out of band is not a secret to forget",
            FakeSshProbe.DEFAULT_FINGERPRINT,
            profile.acceptedFingerprint,
        )
        assertEquals("hermes@hermes-box:2222", vm.uiState.value.destination)
    }

    @Test
    fun `leaving twice, or leaving a screen that held nothing, changes nothing`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        vm.setDestination("hermes@hermes-box")
        advanceUntilIdle()

        vm.releaseScreen()
        val afterFirst = vm.uiState.value
        vm.releaseScreen()
        advanceUntilIdle()

        assertEquals(afterFirst.profile, vm.uiState.value.profile)
        assertEquals(ProbeStatus.Idle, vm.uiState.value.status)
    }

    @Test
    fun `nothing secret is ever handed to the store`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        vm.fillValidProfile()
        vm.importPrivateKey(privateKey(), "id_ed25519")
        vm.setKeyPassphrase("passphrase-value")
        advanceUntilIdle()

        val persisted = store.saved.value.toString()
        assertFalse(persisted.contains("s3cret"))
        assertFalse(persisted.contains("secretkeymaterial"))
        assertFalse(persisted.contains("passphrase-value"))
    }

    @Test
    fun `a fresh screen probes over Tailscale SSH without collecting anything`() = runTest(dispatcher) {
        val probe = FakeSshProbe()
        val vm = viewModel(probe)
        advanceUntilIdle()

        assertEquals(AuthMethod.TailscaleSsh, vm.uiState.value.profile.authMethod)

        vm.setDestination("test-user@test-host")
        advanceUntilIdle()
        assertTrue("a destination is the whole form for Tailscale SSH", vm.uiState.value.canProbe)

        vm.runProbe()
        advanceUntilIdle()
        assertEquals(1, probe.calls.size)
        assertFalse("nothing secret may reach the transport", probe.calls.single().carriedSecret)
        assertEquals(
            "the host key is still reviewed first",
            FakeSshProbe.DEFAULT_FINGERPRINT,
            vm.uiState.value.pendingHostKey?.fingerprint,
        )
    }

    @Test
    fun `a host that only rides the tailnet is told apart from a wrong password`() = runTest(dispatcher) {
        store.saved.value = HostProfile(
            host = "test-host",
            username = "test-user",
            acceptedFingerprint = FakeSshProbe.DEFAULT_FINGERPRINT,
        )
        val vm = viewModel(FakeSshProbe(tailscaleSshEnabled = false))
        advanceUntilIdle()

        vm.runProbe()
        advanceUntilIdle()

        val status = vm.uiState.value.status as ProbeStatus.Failed
        assertEquals(ProbeFailure.TailscaleSshRefused, status.kind)
        assertEquals(SshProbe.TAILSCALE_SSH_REFUSED, status.message)
        assertTrue("the copy must name the policy", status.message.contains("policy"))
        assertTrue("and the way out", status.message.contains("Password"))
        assertNull("a refusal is not a key problem", vm.uiState.value.pendingHostKey)
    }

    @Test
    fun `switching to Tailscale SSH does not send a password typed before the switch`() = runTest(dispatcher) {
        val probe = FakeSshProbe()
        val vm = viewModel(probe)
        vm.fillValidProfile()
        advanceUntilIdle()

        vm.setAuthMethod(AuthMethod.TailscaleSsh)
        vm.runProbe()
        advanceUntilIdle()

        assertFalse(probe.calls.single().carriedSecret)
    }

    /**
     * A probe that answers only when told to, and does not notice cancellation.
     *
     * That last part is the point: a blocking transport can already have its
     * answer in hand when the user taps cancel or retargets the field, so the
     * ViewModel cannot rely on the coroutine dying to keep a stale result off
     * the screen. This drives that exact window.
     */
    private class ManualProbe : SshProbe {
        private var waiting: Continuation<ProbeResult>? = null

        override suspend fun probe(profile: HostProfile, credential: SshCredential): ProbeResult =
            suspendCoroutine { waiting = it }

        fun finish(result: ProbeResult) {
            val continuation = requireNotNull(waiting) { "no probe is in flight" }
            waiting = null
            continuation.resume(result)
        }
    }

    /** Captures the probe-owned copy and cooperates with cancellation. */
    private class CapturingCancellableProbe : SshProbe {
        var credential: SshCredential? = null
            private set

        override suspend fun probe(profile: HostProfile, credential: SshCredential): ProbeResult {
            this.credential = credential
            return suspendCancellableCoroutine<ProbeResult> { }
        }
    }

    /** Holds the first write after it has captured its stale argument. */
    private class DelayedFirstSaveStore : HostProfileStore {
        val saved = MutableStateFlow(HostProfile())
        override val hostProfile: Flow<HostProfile> = saved
        val firstSaveStarted = CompletableDeferred<HostProfile>()
        val releaseFirstSave = CompletableDeferred<Unit>()
        private var saveCount = 0

        override suspend fun saveHostProfile(profile: HostProfile) {
            if (saveCount++ == 0) {
                firstSaveStarted.complete(profile)
                releaseFirstSave.await()
            }
            saved.value = profile
        }
    }

    /** The in-memory half of [HostProfileStore] — the reason the interface exists. */
    private class InMemoryHostProfileStore : HostProfileStore {
        val saved = MutableStateFlow(HostProfile())
        override val hostProfile: Flow<HostProfile> = saved
        override suspend fun saveHostProfile(profile: HostProfile) {
            saved.value = profile
        }
    }
}
