package com.hermesagent.mobile.ui.ssh

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.hermesagent.mobile.data.gateway.GatewayConnectResult
import com.hermesagent.mobile.data.gateway.GatewayConnectionController
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.ssh.FakeSshProbe
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.ProbeResult
import com.hermesagent.mobile.data.ssh.SshCredential
import com.hermesagent.mobile.data.ssh.SshProbe
import com.hermesagent.mobile.ui.AppearanceActions
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.HermesApp
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume

/**
 * What leaving Gateways has to end, driven through the real navigation.
 *
 * The bug this gates is invisible from either half on its own. `SshScreen`
 * disposes and `SshViewModel` does not: the ViewModel is Activity-scoped, so
 * changing `HermesDestination` takes the surface off screen and clears
 * `FLAG_SECURE` while the password, the passphrase, the imported key and a
 * running probe all carry on living behind it — and are still there when the
 * screen is reopened, on a screen whose own copy says credentials are held "for
 * this screen only". So this test wires the ViewModel to `HermesApp` exactly as
 * `MainActivity` does and leaves the way a person leaves.
 *
 * The ordering claim is checked directly rather than assumed: the cleanup
 * action records whether the window was still secure at the moment it ran, so
 * "credentials go before the surface stops being protected" is an assertion and
 * not a comment.
 */
@RunWith(RobolectricTestRunner::class)
// A tall viewport, as in `SshJourneyTest`: this screen is one long scrolling
// column and a tap below the fold lands outside the window.
@Config(sdk = [34], qualifiers = "w412dp-h2000dp")
class SshLifecycleJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    private val store = InMemoryHostProfileStore()
    private lateinit var viewModel: SshViewModel
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private var window: Window? = null

    /** Was the window still secure when the screen's cleanup ran? */
    private var secureWhenCleaned: Boolean? = null
    private var cleanups = 0

    @Test
    fun `toolbar back wipes the password while the window is still secure`() {
        launch()
        openGateways()
        typePassword("s3cret")

        assertEquals("s3cret", viewModel.uiState.value.password)
        assertTrue("a screen holding a password has to be a secure one", isSecure())

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        assertEquals("", viewModel.uiState.value.password)
        assertEquals("the wipe must run inside the secure window", true, secureWhenCleaned)
        assertFalse("and the flag goes once nothing is held", isSecure())
        assertEquals("one screen, one cleanup", 1, cleanups)
    }

    @Test
    fun `system back ends it the same way`() {
        launch()
        openGateways()
        typePassword("s3cret")

        backDispatcher.onBackPressed()
        compose.waitForIdle()

        compose.onNodeWithText("Settings").assertExists()
        assertEquals("", viewModel.uiState.value.password)
        assertEquals(true, secureWhenCleaned)
        assertEquals(1, cleanups)
    }

    @Test
    fun `Connect and Disconnect drive the Gateway manager and clear the connection credential`() {
        val gateway = FakeGatewayConnectionController()
        launch(gatewayConnection = gateway)
        openGateways()
        typeDestination("fixture-user@gateway.invalid")
        compose.onNodeWithContentDescription("Hermes profile (optional)").performTextInput("fixture-profile")
        compose.waitForIdle()
        typePassword("fixture-password")

        assertEquals("fixture-password", viewModel.uiState.value.password)
        assertTrue("the password is entered only on a protected surface", isSecure())

        compose.onNodeWithText("Connect").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.countWithText("Connected") == 1
        }

        assertEquals(1, gateway.connectCalls)
        assertEquals("gateway.invalid", gateway.connectedProfile?.host)
        assertEquals("fixture-user", gateway.connectedProfile?.username)
        assertEquals("fixture-profile", gateway.connectedProfile?.remoteHermesProfile)
        assertTrue("the manager must receive the selected password", gateway.receivedPassword)
        assertTrue(
            "the attempt-owned credential copy must be zeroed after connect returns",
            requireNotNull(gateway.credential).password?.all { it == '\u0000' } == true,
        )
        assertEquals("the form must stop retaining the password", "", viewModel.uiState.value.password)
        assertEquals(GatewayConnectionStatus.Connected, viewModel.uiState.value.connection.status)
        assertEquals(1, compose.countWithText("Disconnect"))
        assertTrue("connection state does not end the protected screen lifetime", isSecure())

        compose.onNodeWithText("Disconnect").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            gateway.disconnectCalls == 1 && compose.countWithText("Disconnected") == 1
        }

        assertEquals(GatewayConnectionStatus.Disconnected, viewModel.uiState.value.connection.status)
        assertEquals(1, compose.countWithText("Connect"))
        assertTrue("disconnecting does not navigate away or clear the window flag", isSecure())

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        assertEquals("screen cleanup runs before FLAG_SECURE is cleared", true, secureWhenCleaned)
        assertFalse(isSecure())
        assertEquals(1, cleanups)
    }

    @Test
    fun `a probe in flight is cancelled by leaving, not left running`() {
        val probe = HeldProbe()
        launch(probe)
        openGateways()
        typeDestination("test-user@test-host")

        compose.onNodeWithText("Test SSH only").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { probe.started }
        assertEquals(ProbeStatus.Running, viewModel.uiState.value.status)

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        assertTrue("no probe may authenticate for a screen that is gone", probe.cancelled)
        assertEquals(ProbeStatus.Idle, viewModel.uiState.value.status)
    }

    @Test
    fun `an answer that lands after leaving never paints the reopened screen`() {
        val probe = ManualProbe()
        launch(probe)
        openGateways()
        typeDestination("test-user@test-host")

        compose.onNodeWithText("Test SSH only").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { probe.inFlight }

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        // The blocking half can already have its answer in hand when the screen
        // goes; cancelling the coroutine cannot unqueue that.
        probe.finish(ProbeResult.Ok(SshProbe.EXPECTED_OUTPUT, "SSH-2.0-test", 12))
        compose.waitForIdle()

        gatewaysFromSettings()

        assertEquals(0, compose.countWithText("SSH test passed"))
        assertEquals("a reopened screen offers a fresh diagnostic", 1, compose.countWithText("Test SSH only"))
    }

    @Test
    fun `reopening shows no credential from the departed screen, and the saved profile intact`() {
        store.saved.value = HostProfile(
            host = "test-host",
            username = "test-user",
            acceptedFingerprint = FakeSshProbe.DEFAULT_FINGERPRINT,
        )
        launch()
        openGateways()

        compose.onNodeWithContentDescription("Authenticate with Private key").performClick()
        compose.waitForIdle()
        viewModel.importPrivateKey(PEM.toCharArray(), "id_ed25519")
        compose.waitForIdle()
        typePassphrase("passphrase-value")
        assertEquals(1, compose.countWithText("Key loaded", substring = true))

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        gatewaysFromSettings()

        assertEquals("an imported key must not survive the screen", 0, compose.countWithText("Key loaded", substring = true))
        assertEquals(1, compose.countWithText("Import an OpenSSH private key…"))
        assertEquals("", viewModel.uiState.value.keyPassphrase)

        // Non-secret, reviewed-out-of-band state is exactly what should survive.
        assertEquals("test-user@test-host", viewModel.uiState.value.destination)
        assertEquals(1, compose.countWithText("Host key trusted"))
        assertEquals(0, compose.countWithText(FakeSshProbe.DEFAULT_FINGERPRINT))
    }

    // ── Harness ───────────────────────────────────────────────────────────

    private fun launch(
        probe: SshProbe = FakeSshProbe(delayMillis = 0),
        gatewayConnection: GatewayConnectionController? = null,
    ) {
        viewModel = SshViewModel(store, probe, gatewayConnection)
        compose.setContent {
            val state by viewModel.uiState.collectAsState()
            val context = LocalContext.current
            val dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            SideEffect {
                window = context.activityWindow()
                backDispatcher = dispatcher
            }

            HermesApp(
                chatState = ChatUiState(),
                sshState = state,
                appearance = AppearanceSelection(),
                chatActions = ChatActions(),
                appearanceActions = AppearanceActions(),
                sshActions = SshActions(
                    onDestinationChange = viewModel::setDestination,
                    onRemoteProfileChange = viewModel::setRemoteHermesProfile,
                    onAuthMethodChange = viewModel::setAuthMethod,
                    onPasswordChange = viewModel::setPassword,
                    onPassphraseChange = viewModel::setKeyPassphrase,
                    onForgetKey = viewModel::forgetPrivateKey,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    onProbe = viewModel::runProbe,
                    onCancelProbe = viewModel::cancelProbe,
                    onAcceptHostKey = viewModel::acceptPendingHostKey,
                    onDismissHostKey = viewModel::dismissPendingHostKey,
                    onForgetHostKey = viewModel::forgetAcceptedHostKey,
                    onLeaveScreen = {
                        cleanups++
                        secureWhenCleaned = isSecure()
                        viewModel.releaseScreen()
                    },
                ),
            )
        }
        compose.waitForIdle()
    }

    /** Chat → Settings → Gateways, the way the app is actually entered. */
    private fun openGateways() {
        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.waitForIdle()
        gatewaysFromSettings()
    }

    /** Back from Gateways lands on Settings, so returning is one tap. */
    private fun gatewaysFromSettings() {
        compose.onNodeWithTag("settings-row-gateways").performClick()
        compose.waitForIdle()
        assertNotNull("the test needs a real Activity window to check FLAG_SECURE", window)
    }

    private fun typeDestination(value: String) {
        compose.onNodeWithContentDescription("SSH destination").performTextInput(value)
        compose.waitForIdle()
    }

    private fun typePassword(value: String) {
        compose.onNodeWithContentDescription("Authenticate with Password").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Password").performTextInput(value)
        compose.waitForIdle()
    }

    private fun typePassphrase(value: String) {
        compose.onNodeWithContentDescription("Passphrase (if the key has one)").performTextInput(value)
        compose.waitForIdle()
    }

    private fun isSecure(): Boolean {
        val flags = window?.attributes?.flags ?: return false
        return flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    /** A probe that never answers but does notice it was cancelled. */
    private class HeldProbe : SshProbe {
        @Volatile var started = false
            private set

        @Volatile var cancelled = false
            private set

        override suspend fun probe(profile: HostProfile, credential: SshCredential): ProbeResult =
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { cancelled = true }
                started = true
            }
    }

    /** A probe whose answer can be made to arrive after the screen has gone. */
    private class ManualProbe : SshProbe {
        private var waiting: Continuation<ProbeResult>? = null

        val inFlight: Boolean get() = waiting != null

        override suspend fun probe(profile: HostProfile, credential: SshCredential): ProbeResult =
            suspendCoroutine { waiting = it }

        fun finish(result: ProbeResult) {
            val continuation = requireNotNull(waiting) { "no probe is in flight" }
            waiting = null
            continuation.resume(result)
        }
    }

    /** Process-manager double: no SSH, remote command, socket, token, or real credential. */
    private class FakeGatewayConnectionController : GatewayConnectionController {
        private val mutableState = MutableStateFlow(GatewayConnectionState())
        override val state: StateFlow<GatewayConnectionState> = mutableState

        var connectCalls = 0
            private set
        var disconnectCalls = 0
            private set
        var connectedProfile: HostProfile? = null
            private set
        var credential: SshCredential? = null
            private set
        var receivedPassword = false
            private set

        override suspend fun connect(
            profile: HostProfile,
            credential: SshCredential,
        ): GatewayConnectResult {
            connectCalls++
            connectedProfile = profile
            this.credential = credential
            receivedPassword = credential.password?.contentEquals("fixture-password".toCharArray()) == true
            mutableState.value = GatewayConnectionState(GatewayConnectionStatus.Connected)
            return GatewayConnectResult.Connected
        }

        override suspend fun disconnect() {
            disconnectCalls++
            mutableState.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        }
    }

    private companion object {
        val PEM = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            not-a-real-key
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()
    }
}

private tailrec fun Context.activityWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.activityWindow()
    else -> null
}
