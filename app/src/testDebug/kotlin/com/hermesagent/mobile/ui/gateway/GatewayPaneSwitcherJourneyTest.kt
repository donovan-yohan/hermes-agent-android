package com.hermesagent.mobile.ui.gateway

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.ui.ConnectionsActions
import com.hermesagent.mobile.ui.GatewayActions
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Gateways pane's own mount of the connection switcher (S-G4, #110).
 *
 * Desktop mounts `ConnectionSwitcher` in the statusbar and nowhere else
 * (`app/shell/hooks/use-statusbar-items.tsx:411,617-621` @ `3ca096de`); this
 * second mount is the owner-approved mobile adaptation, because a phone's
 * Gateways screen is a destination rather than a pane beside an ever-present
 * sidebar. What is under test here is that it is the *same* control — same
 * hide-below-two rule, same sheet, same select path — with the one deliberate
 * difference: no "Manage gateways…", which from this screen would lead here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewayPaneSwitcherJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Desktop's own rule (`connection-switcher.tsx:118-120`), and it costs
     * nothing here: with one saved row this pane is already showing that row's
     * route, its fields and its `Current` pill, so a chooser whose only option
     * is where you already are would be a control that cannot do anything.
     */
    @Test
    fun `one saved connection renders no switcher on the Gateways pane`() {
        val connections = ConnectionsUiState(
            connections = listOf(remoteConnection("a", "Alpha", "https://alpha.test")),
            activeId = "a",
            loaded = true,
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                GatewayScreen(
                    state = GatewaySettingsUiState(mode = GatewayConnectionMode.Remote, loaded = true),
                    gatewayActions = GatewayActions(),
                    sshState = SshUiState(),
                    sshActions = SshActions(),
                    connectionsState = connections,
                    connectionsActions = ConnectionsActions(),
                )
            }
        }

        compose.onNodeWithContentDescription("${ConnectionsCopy.SWITCHER_LABEL}: Alpha").assertDoesNotExist()
        // The pane itself is unchanged: the mode chooser is still the first
        // thing under where the trigger would have been.
        compose.onNodeWithText(GatewayModeCopy.MODE_TITLE).assertExists()
    }

    /**
     * The mount's wiring: the trigger opens the sheet, a row reports the id it
     * was asked for, and the screen is rebuilt from the state that comes back.
     *
     * This is *not* end-to-end proof of the projection — the harness supplies
     * `mode` and `onSelect` itself, standing in for the real ones. That the
     * active row actually re-projects the route, its fields and what Connect
     * would dial is `GatewayScreenTest`'s sibling
     * `GatewaySettingsViewModelTest.kt:165`, on virtual time against the store.
     * What is only true here is the *composition*: pick a gateway from the top
     * of this screen and the screen you are on is rebuilt for it — the Remote
     * pane's `Gateway URL` gives way to the SSH pane's `SSH destination`, the
     * trigger relabels, and the `Current` pill moves down in the registry list,
     * which is what the per-row `Switch` action disappearing from Bravo and
     * appearing on Alpha proves.
     */
    @Test
    fun `picking a gateway from the pane switcher re-projects the pane and moves Current`() {
        var connections by mutableStateOf(
            ConnectionsUiState(
                connections = listOf(
                    remoteConnection("a", "Alpha", "https://alpha.test"),
                    sshConnection("b", "Bravo", "demo-host"),
                ),
                activeId = "a",
                loaded = true,
            ),
        )
        // The same lambda `MainActivity` binds to `ConnectionsViewModel::select`
        // for both mounts; the switch itself is `ConnectionSwitchControllerTest`'s.
        val actions = ConnectionsActions(onSelect = { id -> connections = connections.copy(activeId = id) })
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                GatewayScreen(
                    state = GatewaySettingsUiState(
                        mode = connections.active?.kind?.mode ?: GatewayConnectionMode.Remote,
                        loaded = true,
                    ),
                    gatewayActions = GatewayActions(),
                    sshState = SshUiState(),
                    sshActions = SshActions(),
                    connectionsState = connections,
                    connectionsActions = actions,
                )
            }
        }

        // `SectionLabel` uppercases, so the field is addressed by the
        // `contentDescription` its editor carries, not by its painted label.
        compose.onNodeWithContentDescription("Gateway URL").assertExists()
        compose.onNodeWithContentDescription("${ConnectionsCopy.SWITCH_CONNECTION} Bravo")
            .performScrollTo()
            .assertExists()

        compose.onNodeWithContentDescription("${ConnectionsCopy.SWITCHER_LABEL}: Alpha")
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("Connection switcher sheet").assertExists()
        compose.onNode(descriptionStartsWith("Alpha")).assertIsSelected()
        compose.onNode(descriptionStartsWith("Bravo")).performClick()
        compose.waitForIdle()

        assertEquals("b", connections.activeId)
        // The pane is Bravo's now, not Alpha's.
        compose.onNodeWithContentDescription("SSH destination").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Gateway URL").assertDoesNotExist()
        compose.onNodeWithContentDescription("${ConnectionsCopy.SWITCHER_LABEL}: Bravo").assertExists()
        // And so is the marker in the registry below: the row that can be
        // switched to is the one this app is no longer on.
        compose.onNodeWithText(ConnectionsCopy.CURRENT_PILL).performScrollTo().assertExists()
        compose.onNodeWithContentDescription("${ConnectionsCopy.SWITCH_CONNECTION} Alpha")
            .performScrollTo()
            .assertExists()
        compose.onNodeWithContentDescription("${ConnectionsCopy.SWITCH_CONNECTION} Bravo")
            .assertDoesNotExist()
    }

    /**
     * "Manage gateways…" is how the rail's sheet reaches this screen. From
     * this screen it would lead here, so the item and its hairline are not
     * rendered — while `Add connection`, Desktop's one add path
     * (`connections-registry.tsx:819-829`), is on the same page, below.
     */
    @Test
    fun `the pane switcher offers no Manage gateways item, and Add connection is on the page`() {
        val connections = ConnectionsUiState(
            connections = listOf(
                remoteConnection("a", "Alpha", "https://alpha.test"),
                sshConnection("b", "Bravo", "demo-host"),
            ),
            activeId = "a",
            loaded = true,
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                GatewayScreen(
                    state = GatewaySettingsUiState(mode = GatewayConnectionMode.Remote, loaded = true),
                    gatewayActions = GatewayActions(),
                    sshState = SshUiState(),
                    sshActions = SshActions(),
                    connectionsState = connections,
                    connectionsActions = ConnectionsActions(),
                )
            }
        }

        compose.onNodeWithContentDescription(ConnectionsCopy.ADD_CONNECTION).performScrollTo().assertExists()

        // Desktop names its trigger with the registry's own section title
        // (`connection-switcher.tsx:154`), which is fine where the two are on
        // different surfaces. Here they are on one, so the trigger must not
        // carry it: a screen reader would meet "Registered gateways" twice,
        // once as this button and once as the heading over the list below.
        compose.onNodeWithText(ConnectionsCopy.TITLE).performScrollTo().assertExists()
        compose.onNodeWithContentDescription("${ConnectionsCopy.TITLE}: Alpha").assertDoesNotExist()

        compose.onNodeWithContentDescription("${ConnectionsCopy.SWITCHER_LABEL}: Alpha")
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("Connection switcher sheet").assertExists()
        compose.onNode(descriptionStartsWith("Bravo")).assertExists()
        compose.onNodeWithText(ConnectionsCopy.MANAGE_GATEWAYS).assertDoesNotExist()
        compose.onNodeWithContentDescription(ConnectionsCopy.MANAGE_GATEWAYS).assertDoesNotExist()
    }

    /**
     * The Gateways surface is `FLAG_SECURE` because the registry editor takes a
     * Local row's session token. Opening and dismissing the switcher's sheet
     * neither adds a second holder nor drops the one that is there.
     */
    @Test
    fun `opening the pane switcher leaves the page protected`() {
        val connections = ConnectionsUiState(
            connections = listOf(
                remoteConnection("a", "Alpha", "https://alpha.test"),
                sshConnection("b", "Bravo", "demo-host"),
            ),
            activeId = "a",
            loaded = true,
        )
        var window: Window? = null
        compose.setContent {
            val context = LocalContext.current
            SideEffect { window = context.hostActivityWindow() }
            HermesTheme(AppearanceSelection()) {
                GatewayScreen(
                    state = GatewaySettingsUiState(mode = GatewayConnectionMode.Remote, loaded = true),
                    gatewayActions = GatewayActions(),
                    sshState = SshUiState(),
                    sshActions = SshActions(),
                    connectionsState = connections,
                    connectionsActions = ConnectionsActions(),
                )
            }
        }
        val secured = requireNotNull(window) { "the test needs a real Activity window" }
        assertTrue("the Gateways page holds the window secure", secured.isWindowSecure())

        compose.onNodeWithContentDescription("${ConnectionsCopy.SWITCHER_LABEL}: Alpha")
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("Connection switcher sheet").assertExists()
        assertTrue("the sheet must not unprotect the page under it", secured.isWindowSecure())

        compose.onNode(descriptionStartsWith("Alpha")).performClick()
        compose.waitForIdle()

        assertTrue("nor must dismissing it", secured.isWindowSecure())
    }

    /** See `ConnectionSwitcherJourneyTest`: the label is a *prefix* on a sheet row. */
    private fun descriptionStartsWith(prefix: String): SemanticsMatcher =
        SemanticsMatcher("has content description starting with \"$prefix\"") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true
        }

    private fun remoteConnection(id: String, label: String, url: String): SavedConnection =
        SavedConnection(
            id = id,
            label = label,
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = url),
        )

    private fun sshConnection(
        id: String,
        label: String,
        host: String,
        username: String = "demo-user",
    ): SavedConnection =
        SavedConnection(
            id = id,
            label = label,
            kind = ConnectionKind.Ssh,
            host = HostProfile(host = host, username = username),
        )
}

private fun Window.isWindowSecure(): Boolean =
    attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

/** Null in any host that is not an Activity. */
private tailrec fun Context.hostActivityWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.hostActivityWindow()
    else -> null
}
