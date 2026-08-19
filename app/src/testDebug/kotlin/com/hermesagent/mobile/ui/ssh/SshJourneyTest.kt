package com.hermesagent.mobile.ui.ssh

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.hermesagent.mobile.data.ssh.FakeSshProbe
import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.ProbeFailure
import com.hermesagent.mobile.data.ssh.SshProbe
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesSpacing
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The onboarding journey as a person meets it: one field, pick how to
 * authenticate, review the host key, probe.
 *
 * It runs the real screen against the deterministic probe, so what it asserts
 * is the copy and the affordances that actually ship — including the one this
 * slice exists for, where a box is on the tailnet but is not running Tailscale
 * SSH.
 */
@RunWith(RobolectricTestRunner::class)
// A tall viewport on purpose: this screen is one long scrolling column, and a
// tap injected at a node below the fold lands outside the window and is
// dropped, which reads as a silent no-op rather than a failure.
@Config(sdk = [34], qualifiers = "w412dp-h2000dp")
class SshJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    private val store = InMemoryHostProfileStore()

    private fun launch(probe: SshProbe = FakeSshProbe(delayMillis = 0)): SshViewModel {
        val viewModel = SshViewModel(store, probe)
        compose.setContent {
            val state by viewModel.uiState.collectAsState()
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                SshScreen(
                    state = state,
                    actions = SshActions(
                        onDestinationChange = viewModel::setDestination,
                        onAuthMethodChange = viewModel::setAuthMethod,
                        onPasswordChange = viewModel::setPassword,
                        onPassphraseChange = viewModel::setKeyPassphrase,
                        onForgetKey = viewModel::forgetPrivateKey,
                        onProbe = viewModel::runProbe,
                        onCancelProbe = viewModel::cancelProbe,
                        onAcceptHostKey = viewModel::acceptPendingHostKey,
                        onDismissHostKey = viewModel::dismissPendingHostKey,
                        onForgetHostKey = viewModel::forgetAcceptedHostKey,
                    ),
                )
            }
        }
        return viewModel
    }

    @Test
    fun `the screen asks for one destination, not a host, a port and a username`() {
        launch()

        compose.onNodeWithContentDescription("SSH destination").assertIsDisplayed()
        for (gone in listOf("Host", "Port", "Username")) {
            assertEquals("$gone must no longer be its own field", 0, compose.countWithContentDescription(gone))
        }
    }

    @Test
    fun `connected Gateway shows one clear disconnect action`() {
        compose.setContent {
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                SshScreen(
                    state = SshUiState(
                        profile = HostProfile(host = "test-host", username = "test-user"),
                        destination = "test-user@test-host",
                        connection = GatewayConnectionState(GatewayConnectionStatus.Connected),
                    ),
                    actions = SshActions(),
                )
            }
        }

        assertEquals(1, compose.countWithText("Connected"))
        assertEquals(1, compose.countWithText("Disconnect"))
        assertEquals(0, compose.countWithText("Connect"))
    }

    @Test
    fun `a trusted Gateway host says how to restore a cleared secret`() {
        fun state(method: AuthMethod) = SshUiState(
            profile = HostProfile(
                host = "test-host",
                username = "test-user",
                authMethod = method,
                acceptedFingerprint = FakeSshProbe.DEFAULT_FINGERPRINT,
            ),
            destination = "test-user@test-host",
        )
        val current = mutableStateOf(state(AuthMethod.Password))
        compose.setContent {
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                SshScreen(state = current.value, actions = SshActions())
            }
        }

        assertEquals(1, compose.countWithText("Re-enter your password, then Connect."))

        current.value = state(AuthMethod.PrivateKey)
        compose.waitForIdle()

        assertEquals(0, compose.countWithText("Re-enter your password, then Connect."))
        assertEquals(1, compose.countWithText("Re-import your private key, then Connect."))
    }

    @Test
    fun `Tailscale SSH is the starting choice and shows no secret field`() {
        launch()

        compose.onNodeWithContentDescription("Authenticate with Tailscale SSH").assertIsDisplayed()
        compose.onNodeWithContentDescription("Authenticate with Password").assertIsDisplayed()
        compose.onNodeWithContentDescription("Authenticate with Private key").assertIsDisplayed()

        assertEquals("nothing to type for a keyless method", 0, compose.countWithContentDescription("Password"))
        assertEquals(
            "and the screen says what it does need",
            1,
            compose.countWithText("host and tailnet policy must allow", substring = true),
        )

        compose.onNodeWithContentDescription("Authenticate with Password").performClick()
        compose.waitForIdle()

        assertEquals("switching reveals the field", 1, compose.countWithContentDescription("Password"))
        assertEquals(0, compose.countWithText("host and tailnet policy must allow", substring = true))
    }

    @Test
    fun `a destination alone reaches the host-key review and then a probe`() {
        launch()

        compose.onNodeWithContentDescription("SSH destination").performTextInput("test-user@test-host")
        compose.waitForIdle()
        compose.onNodeWithText("Test SSH only").performClick()

        // Still stops before authenticating, keyless method or not.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.countWithText("First connection to this host") == 1
        }
        compose.onNodeWithText("Accept this key").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Test SSH only").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.countWithText("SSH test passed") == 1 }
    }

    @Test
    fun `a box that is only on the tailnet is told so, not asked for a password`() {
        store.saved.value = HostProfile(
            host = "test-host",
            username = "test-user",
            acceptedFingerprint = FakeSshProbe.DEFAULT_FINGERPRINT,
        )
        launch(FakeSshProbe(tailscaleSshEnabled = false, delayMillis = 0))
        compose.waitForIdle()

        compose.onNodeWithText("Test SSH only").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.countWithText("Reachable, but not over Tailscale SSH") == 1
        }
        assertEquals(
            "the copy has to separate the tailnet from Tailscale SSH",
            1,
            compose.countWithText("Enable and allow Tailscale SSH", substring = true),
        )
    }

    @Test
    fun `retargeting the destination takes the fingerprint review off the screen`() {
        launch()

        compose.onNodeWithContentDescription("SSH destination").performTextInput("test-user@host-a")
        compose.waitForIdle()
        compose.onNodeWithText("Test SSH only").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.countWithText("First connection to this host") == 1
        }

        // The field stays editable while a review is up, so the review has to
        // stop being about this form the moment the form moves.
        compose.onNodeWithContentDescription("SSH destination").performTextInput("2")
        compose.waitForIdle()

        assertEquals(
            "a review for host-a is not a decision about host-a2",
            0,
            compose.countWithText("First connection to this host"),
        )
        assertEquals(0, compose.countWithText("Accept this key"))
    }

    @Test
    fun `a document that is not a key says so instead of going quiet`() {
        val viewModel = launch()

        viewModel.importPrivateKey("notes about my PRIVATE KEY".toCharArray(), "notes.txt")
        compose.waitForIdle()

        assertEquals(1, compose.countWithText("That key was not imported"))
        assertEquals(
            "and says what would have been accepted",
            1,
            compose.countWithText("OpenSSH or PKCS#8 private key", substring = true),
        )
        assertEquals("junk must not read as a loaded key", 0, compose.countWithText("Key loaded", substring = true))
    }

    @Test
    fun `the fingerprint review stays concise and actionable`() {
        launch()

        compose.onNodeWithContentDescription("SSH destination").performTextInput("test-user@test-host")
        compose.waitForIdle()
        compose.onNodeWithText("Test SSH only").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.countWithText("First connection to this host") == 1
        }

        assertEquals(
            "the primary surface gives the next action",
            1,
            compose.countWithText("Compare this fingerprint with the host", substring = true),
        )
        assertEquals(0, compose.countWithText("ssh-keygen", substring = true))
    }

    // ── Field geometry and semantics ──────────────────────────────────────

    @Test
    fun `the editable node is the touch target, not a box drawn around it`() {
        launch()
        val floor = HermesSpacing().touchTarget

        // The node a finger and TalkBack both address is the one carrying the
        // label and the edit action. Asserting the height on a decorative
        // wrapper would pass while the real target stayed a 22 dp text line.
        compose.onNodeWithContentDescription("SSH destination")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
            .assertHeightIsAtLeast(floor)

        compose.onNodeWithContentDescription("Authenticate with Password").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Password")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
            .assertHeightIsAtLeast(floor)
    }

    @Test
    fun `field text is centred in the target rather than pinned to its top`() {
        launch()

        val field = compose.onNodeWithContentDescription("SSH destination").fetchSemanticsNode()
        val placeholder = compose.onNodeWithText("you@hermes-box", useUnmergedTree = true).fetchSemanticsNode()

        // Placeholder and typed text share the style and the alignment, so one
        // measurement covers both. Half a device pixel of rounding is allowed;
        // top-aligned text in a 48 dp box is off by ten times that.
        val drift = abs(placeholder.boundsInRoot.center.y - field.boundsInRoot.center.y)
        assertTrue("the placeholder sits ${drift}px off the field's centre line", drift <= 1f)
    }

    // ── Copy the screen is answerable for ─────────────────────────────────

    @Test
    fun `the primary screen speaks to connection outcome without phase caveats`() {
        launch()

        assertEquals(1, compose.countWithText("Connect this app to a remote Hermes Gateway", substring = true))
        assertEquals(1, compose.countWithText("Disconnected"))
        assertEquals(1, compose.countWithText("Connect"))
        assertEquals(0, compose.countWithText("not implemented", substring = true))
        assertEquals(0, compose.countWithText("Android sandboxes", substring = true))
    }

    @Test
    fun `the host-key contract is on screen before any key is trusted`() {
        launch()

        assertEquals(1, compose.countWithText("Review the host key on first connect", substring = true))
        assertEquals(1, compose.countWithText("a changed key is blocked", substring = true))
        assertEquals(0, compose.countWithText("known_hosts", substring = true))
    }

    @Test
    fun `profile selection and diagnostics stay secondary to connect`() {
        launch()

        compose.onNodeWithContentDescription("Hermes profile (optional)").assertIsDisplayed()
        assertEquals(1, compose.countWithText("Connect"))
        assertEquals(1, compose.countWithText("Test SSH only"))
        assertEquals(0, compose.countWithText("credential", substring = true))
    }

    @Test
    fun `unavailable SSH diagnostics are disabled instead of silently ignoring taps`() {
        val state = mutableStateOf(SshUiState())
        compose.setContent {
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                SshScreen(state = state.value, actions = SshActions(onProbe = {}))
            }
        }

        compose.onNodeWithText("Test SSH only").assertIsNotEnabled()

        state.value = state.value.copy(
            status = ProbeStatus.Failed(
                ProbeFailure.Unreachable,
                "fixture",
            ),
        )
        compose.waitForIdle()

        compose.onNodeWithText("Test again").assertIsNotEnabled()
    }

    @Test
    fun `the sections read in the order the task is done in`() {
        launch()

        val destination = compose.onNodeWithContentDescription("SSH destination").fetchSemanticsNode()
        val authentication = compose.onNodeWithTag(SECTION_AUTHENTICATION).fetchSemanticsNode()
        val hostKey = compose.onNodeWithTag(SECTION_HOST_KEY).fetchSemanticsNode()
        val probe = compose.onNodeWithTag(SECTION_PROBE).fetchSemanticsNode()

        val order = listOf(destination, authentication, hostKey, probe).map { it.boundsInRoot.top }
        assertEquals("where, then who, then whose key, then run", order.sorted(), order)
    }

}
