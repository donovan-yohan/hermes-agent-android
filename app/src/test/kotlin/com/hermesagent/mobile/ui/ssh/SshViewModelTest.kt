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

    private fun SshViewModel.fillValidProfile() {
        setHost("hermes-box.local")
        setPort("22")
        setUsername("hermes")
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
        vm.setPassword("s3cret")

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
    fun `probing is refused until the profile and a credential are present`() = runTest(dispatcher) {
        val probe = FakeSshProbe()
        val vm = viewModel(probe)

        vm.runProbe()
        advanceUntilIdle()
        assertTrue("an empty profile must not dial", probe.calls.isEmpty())

        vm.setHost("hermes-box.local")
        vm.setUsername("hermes")
        vm.runProbe()
        advanceUntilIdle()
        assertTrue("no credential means no dial", probe.calls.isEmpty())

        vm.setPassword("s3cret")
        vm.runProbe()
        advanceUntilIdle()
        assertEquals(1, probe.calls.size)
    }

    @Test
    fun `validation reports every problem at once`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        vm.setPort("0")
        advanceUntilIdle()

        val errors = vm.uiState.value.validationErrors
        assertEquals(setOf("Host is required.", "Port must be between 1 and 65535.", "Username is required."), errors.toSet())
    }

    @Test
    fun `the port field ignores anything that is not a digit`() = runTest(dispatcher) {
        val vm = viewModel(FakeSshProbe())
        vm.setPort("2s2")
        advanceUntilIdle()
        assertEquals(22, vm.uiState.value.profile.port)
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

    /** The in-memory half of [HostProfileStore] — the reason the interface exists. */
    private class InMemoryHostProfileStore : HostProfileStore {
        val saved = MutableStateFlow(HostProfile())
        override val hostProfile: Flow<HostProfile> = saved
        override suspend fun saveHostProfile(profile: HostProfile) {
            saved.value = profile
        }
    }
}
