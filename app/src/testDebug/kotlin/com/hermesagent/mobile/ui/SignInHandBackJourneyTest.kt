package com.hermesagent.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.gateway.SignInOrigin
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.gateway.ConnectionsCopy
import com.hermesagent.mobile.ui.gateway.ConnectionsUiState
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S-U6 (#116): a sign-in finishes where the person started it.
 *
 * Scenario A, from the owner report: they are in the sessions drawer, switch to
 * a gateway this app is not signed into, and are sent to Gateways to sign in.
 * The browser hands back to whatever surface was last on screen, which left
 * them in Settings — one destination away from the sessions they were trying to
 * reach, with nothing saying so.
 *
 * The shell owns both halves of that: which surface a sign-in started from, and
 * what a hand-back that names one does. The Intent that carries the value
 * between them is `GatewaySignInBrowserTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignInHandBackJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    private val reported = mutableListOf<SignInOrigin>()
    private var ask by mutableStateOf<HermesNavigationAsk?>(null)

    @Test
    fun `reaching Gateways from the sessions drawer makes the sign-in a sessions journey`() {
        launch()

        openGatewaysFromTheDrawer()

        compose.onNodeWithText("Gateways").assertIsDisplayed()
        assertEquals(
            "the person came from the drawer, so this is where finishing belongs",
            SignInOrigin.Sessions,
            reported.last(),
        )
    }

    /**
     * The other sessions route, and the one Scenario A actually takes now: the
     * chat chrome's connection line became the door out of a broken connection
     * (#116 S-U3). It leaves from the same surface as the drawer, so it marks
     * the same journey — the person tapping "sign in" from a session they
     * cannot reach is trying to reach that session.
     */
    @Test
    fun `the chat chrome's failure door is a sessions journey too`() {
        launch(chat = ChatUiState(connection = GatewayConnectionState(GatewayConnectionStatus.NeedsAttention)))

        compose.onNodeWithContentDescription(
            "${GatewayConnectionStatus.NeedsAttention.label}. ${ConnectionsCopy.MANAGE_GATEWAYS}",
        ).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Gateways").assertIsDisplayed()
        assertEquals(SignInOrigin.Sessions, reported.last())
    }

    @Test
    fun `reaching Gateways through Settings keeps the behaviour it has`() {
        launch()

        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithTag(GATEWAYS_ROW).performClick()

        compose.onNodeWithText("Gateways").assertIsDisplayed()
        assertEquals(SignInOrigin.Gateways, reported.last())
        assertEquals(
            "the shell starts at the destination whose own pane shows the result",
            SignInOrigin.Gateways,
            reported.first(),
        )
    }

    @Test
    fun `a hand-back from a sessions sign-in lands back on sessions`() {
        launch()
        openGatewaysFromTheDrawer()
        compose.onNodeWithText("Gateways").assertIsDisplayed()

        ask = HermesNavigationAsk(HermesDestination.Chat, token = 1)
        compose.waitForIdle()

        compose.onNodeWithText("Hermes").assertIsDisplayed()

        // Two sign-ins in a row are two hand-backs. Without the token the second
        // would be the same value as the first and move nothing, which is the
        // failure this fixes rather than a smaller version of it.
        openGatewaysFromTheDrawer()
        compose.onNodeWithText("Gateways").assertIsDisplayed()
        ask = HermesNavigationAsk(HermesDestination.Chat, token = 2)
        compose.waitForIdle()

        compose.onNodeWithText("Hermes").assertIsDisplayed()
    }

    /**
     * The whole navigation rule, which `MainActivity` applies to whatever the
     * hand-back Intent turned out to say.
     */
    @Test
    fun `only a sessions sign-in asks the shell for anything`() {
        assertEquals(HermesDestination.Chat, handBackDestination(SignInOrigin.Sessions))
        assertNull("its own pane already shows the result", handBackDestination(SignInOrigin.Gateways))
        assertNull("and every build before this one stamped nothing", handBackDestination(null))
    }

    private fun openGatewaysFromTheDrawer() {
        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithContentDescription("${ConnectionsCopy.TITLE}: Alpha").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("Connection switcher sheet").assertExists()
        compose.onNodeWithContentDescription(ConnectionsCopy.MANAGE_GATEWAYS).performClick()
        compose.waitForIdle()
    }

    private fun launch(chat: ChatUiState = ChatUiState()) {
        compose.setContent {
            HermesApp(
                chatState = chat,
                gatewayState = GatewaySettingsUiState(),
                sshState = SshUiState(),
                appearance = AppearanceSelection(),
                chatActions = ChatActions(),
                appearanceActions = AppearanceActions(),
                gatewayActions = GatewayActions(),
                sshActions = SshActions(),
                connectionsState = twoConnections,
                connectionsActions = ConnectionsActions(),
                navigationAsk = ask,
                onSignInOriginChange = { reported += it },
            )
        }
        compose.waitForIdle()
    }

    private val twoConnections = ConnectionsUiState(
        connections = listOf(
            remoteConnection("a", "Alpha", "https://alpha.test"),
            remoteConnection("b", "Bravo", "https://bravo.test"),
        ),
        activeId = "a",
        loaded = true,
    )

    private companion object {
        const val GATEWAYS_ROW = "settings-row-gateways"

        fun remoteConnection(id: String, label: String, url: String): SavedConnection =
            SavedConnection(
                id = id,
                label = label,
                kind = ConnectionKind.Remote,
                remote = RemoteGatewayProfile(baseUrl = url),
            )
    }
}
