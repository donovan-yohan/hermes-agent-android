package com.hermesagent.mobile.ui.ssh

import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.ssh.FakeSshProbe
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.HostProfileStore
import com.hermesagent.mobile.data.ssh.ProbeFailure
import com.hermesagent.mobile.data.ssh.SshProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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

/**
 * The onboarding journey, end to end, against the deterministic probe:
 * first use → review → accept → retry → success, plus the changed-key stop.
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

        vm.setDestination("  donovanyohan@dev  ")
        advanceUntilIdle()

        val implicit = vm.uiState.value.profile
        assertEquals("donovanyohan", implicit.username)
        assertEquals("dev", implicit.host)
        assertEquals("nobody should have to type port 22", 22, implicit.port)
        assertNull(vm.uiState.value.destinationError)

        vm.setDestination("donovanyohan@dev:2222")
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

        vm.setDestination("donovanyohan@hermes-box")
        advanceUntilIdle()
        assertEquals(FakeSshProbe.DEFAULT_FINGERPRINT, store.saved.value.acceptedFingerprint)

        vm.setDestination("donovanyohan@other-box")
        advanceUntilIdle()
        assertNull("a new host has to be reviewed again", store.saved.value.acceptedFingerprint)
    }

    @Test
    fun `importing a key switches method and never puts the pem in ui state`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nsecretkeymaterial\n-----END OPENSSH PRIVATE KEY-----"

        vm.importPrivateKey(pem, "id_ed25519")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(AuthMethod.PrivateKey, state.profile.authMethod)
        assertTrue(state.privateKeyLoaded)
        assertEquals("id_ed25519", state.profile.importedKeyName)
        assertFalse("the pem must never be reachable from a state snapshot", state.toString().contains("secretkeymaterial"))
    }

    @Test
    fun `nothing secret is ever handed to the store`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        vm.fillValidProfile()
        vm.importPrivateKey("-----BEGIN OPENSSH PRIVATE KEY-----\nsecretkeymaterial\n-----END", "id_ed25519")
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

        vm.setDestination("donovanyohan@dev")
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
            host = "dev",
            username = "donovanyohan",
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

    /** The in-memory half of [HostProfileStore] — the reason the interface exists. */
    private class InMemoryHostProfileStore : HostProfileStore {
        val saved = MutableStateFlow(HostProfile())
        override val hostProfile: Flow<HostProfile> = saved
        override suspend fun saveHostProfile(profile: HostProfile) {
            saved.value = profile
        }
    }
}
