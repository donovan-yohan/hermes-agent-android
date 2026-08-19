package com.hermesagent.mobile.ui.ssh

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.hermesagent.mobile.data.ssh.FakeSshProbe
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.HostProfileStore
import com.hermesagent.mobile.data.ssh.SshProbe
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
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

    private fun launch(probe: SshProbe = FakeSshProbe(delayMillis = 0)) {
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
    fun `Tailscale SSH is the starting choice and shows no secret field`() {
        launch()

        compose.onNodeWithContentDescription("Authenticate with Tailscale SSH").assertIsDisplayed()
        compose.onNodeWithContentDescription("Authenticate with Password").assertIsDisplayed()
        compose.onNodeWithContentDescription("Authenticate with Private key").assertIsDisplayed()

        assertEquals("nothing to type for a keyless method", 0, compose.countWithContentDescription("Password"))
        assertEquals(
            "and the screen says what it does need",
            1,
            compose.countWithText("target runs Tailscale SSH", substring = true),
        )

        compose.onNodeWithContentDescription("Authenticate with Password").performClick()
        compose.waitForIdle()

        assertEquals("switching reveals the field", 1, compose.countWithContentDescription("Password"))
        assertEquals(0, compose.countWithText("target runs Tailscale SSH", substring = true))
    }

    @Test
    fun `a destination alone reaches the host-key review and then a probe`() {
        launch()

        compose.onNodeWithContentDescription("SSH destination").performTextInput("donovanyohan@dev")
        compose.waitForIdle()
        compose.onNodeWithText("Run probe").performClick()

        // Still stops before authenticating, keyless method or not.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.countWithText("First connection to this host") == 1
        }
        compose.onNodeWithText("Accept this key").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Run probe").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.countWithText("Probe succeeded") == 1 }
    }

    @Test
    fun `a box that is only on the tailnet is told so, not asked for a password`() {
        store.saved.value = HostProfile(
            host = "dev",
            username = "donovanyohan",
            acceptedFingerprint = FakeSshProbe.DEFAULT_FINGERPRINT,
        )
        launch(FakeSshProbe(tailscaleSshEnabled = false, delayMillis = 0))
        compose.waitForIdle()

        compose.onNodeWithText("Run probe").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.countWithText("Reachable, but not over Tailscale SSH") == 1
        }
        assertEquals(
            "the copy has to separate the tailnet from Tailscale SSH",
            1,
            compose.countWithText("sharing a tailnet only provides the route", substring = true),
        )
    }

    /** The in-memory half of [HostProfileStore]; the SSH tests' usual double. */
    private class InMemoryHostProfileStore : HostProfileStore {
        val saved = MutableStateFlow(HostProfile())
        override val hostProfile: Flow<HostProfile> = saved
        override suspend fun saveHostProfile(profile: HostProfile) {
            saved.value = profile
        }
    }
}

private fun ComposeContentTestRule.countWithText(text: String, substring: Boolean = false): Int =
    onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size

private fun ComposeContentTestRule.countWithContentDescription(description: String): Int =
    onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size
