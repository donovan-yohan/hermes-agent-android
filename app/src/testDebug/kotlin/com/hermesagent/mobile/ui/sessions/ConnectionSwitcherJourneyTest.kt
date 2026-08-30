package com.hermesagent.mobile.ui.sessions

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.hermesagent.mobile.data.connections.CONNECTION_SEARCH_THRESHOLD
import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.ui.ConnectionsActions
import com.hermesagent.mobile.ui.gateway.ConnectionsCopy
import com.hermesagent.mobile.ui.gateway.ConnectionsUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Journey coverage for [ConnectionSwitcherBar], the session rail's connection
 * switcher. Desktop shows no source chrome at all for a single connection, so
 * the trigger and its sheet only exist once a registry is actually
 * switchable; every scenario here drives that composable directly with a
 * [ConnectionsUiState] built for the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionSwitcherJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a single saved connection renders no switcher trigger`() {
        val state = ConnectionsUiState(
            connections = listOf(remoteConnection("a", "Alpha", "https://alpha.test")),
            activeId = "a",
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ConnectionSwitcherBar(state = state, actions = ConnectionsActions(), onManage = {})
            }
        }

        compose.onNodeWithContentDescription("${ConnectionsCopy.TITLE}: Alpha").assertDoesNotExist()
    }

    @Test
    fun `two connections show the active label, open a sheet listing both, and mark the active row selected`() {
        var selectedId: String? = null
        val state = ConnectionsUiState(
            connections = listOf(
                remoteConnection("a", "Alpha", "https://alpha.test"),
                sshConnection("b", "Bravo", "demo-host"),
            ),
            activeId = "a",
        )
        val actions = ConnectionsActions(onSelect = { id -> selectedId = id })
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ConnectionSwitcherBar(state = state, actions = actions, onManage = {})
            }
        }

        compose.onNodeWithContentDescription("${ConnectionsCopy.TITLE}: Alpha")
            .assertExists()
            .performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("Connection switcher sheet").assertExists()
        compose.onNode(descriptionStartsWith("Alpha")).assertExists().assertIsSelected()
        compose.onNode(descriptionStartsWith("Bravo")).assertExists()

        compose.onNode(descriptionStartsWith("Bravo")).performClick()
        compose.waitForIdle()

        assertEquals("b", selectedId)
    }

    @Test
    fun `a pending switch shows Connecting on the trigger`() {
        val state = ConnectionsUiState(
            connections = listOf(
                remoteConnection("a", "Alpha", "https://alpha.test"),
                sshConnection("b", "Bravo", "demo-host"),
            ),
            activeId = "a",
            pendingId = "b",
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ConnectionSwitcherBar(state = state, actions = ConnectionsActions(), onManage = {})
            }
        }

        compose.onNodeWithText("Connecting…").assertExists()
    }

    @Test
    fun `eight or more connections show a search field that filters and reports no matches`() {
        val connections = (1..CONNECTION_SEARCH_THRESHOLD).map { index ->
            remoteConnection("g$index", "Gateway $index", "https://g$index.test")
        }
        val state = ConnectionsUiState(connections = connections, activeId = "g1")
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ConnectionSwitcherBar(state = state, actions = ConnectionsActions(), onManage = {})
            }
        }

        compose.onNodeWithContentDescription("${ConnectionsCopy.TITLE}: Gateway 1").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription(ConnectionsCopy.SEARCH_PLACEHOLDER).assertExists()

        compose.onNodeWithContentDescription(ConnectionsCopy.SEARCH_PLACEHOLDER).performTextInput("Gateway 5")
        compose.waitForIdle()

        compose.onNode(descriptionStartsWith("Gateway 5")).assertExists()
        compose.onNode(descriptionStartsWith("Gateway 1")).assertDoesNotExist()

        compose.onNodeWithContentDescription(ConnectionsCopy.SEARCH_PLACEHOLDER).performTextClearance()
        compose.onNodeWithContentDescription(ConnectionsCopy.SEARCH_PLACEHOLDER).performTextInput("no-such-gateway")
        compose.waitForIdle()

        compose.onNodeWithText(ConnectionsCopy.NO_SEARCH_RESULTS).assertExists()
    }

    @Test
    fun `the Manage gateways row fires onManage`() {
        var manageCount = 0
        val state = ConnectionsUiState(
            connections = listOf(
                remoteConnection("a", "Alpha", "https://alpha.test"),
                sshConnection("b", "Bravo", "demo-host"),
            ),
            activeId = "a",
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ConnectionSwitcherBar(
                    state = state,
                    actions = ConnectionsActions(),
                    onManage = { manageCount++ },
                )
            }
        }

        compose.onNodeWithContentDescription("${ConnectionsCopy.TITLE}: Alpha").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription(ConnectionsCopy.MANAGE_GATEWAYS).performClick()
        compose.waitForIdle()

        assertEquals(1, manageCount)
    }

    /**
     * The Gateways screen mounts this same composable, and "Manage gateways…"
     * navigates to the Gateways screen — so on that mount the item, and the
     * hairline that separates it, are not rendered at all. The rail's mount,
     * which has somewhere to go, is the test above.
     */
    @Test
    fun `a mount with nowhere to manage renders no Manage gateways row`() {
        val state = ConnectionsUiState(
            connections = listOf(
                remoteConnection("a", "Alpha", "https://alpha.test"),
                sshConnection("b", "Bravo", "demo-host"),
            ),
            activeId = "a",
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ConnectionSwitcherBar(state = state, actions = ConnectionsActions(), onManage = null)
            }
        }

        compose.onNodeWithContentDescription("${ConnectionsCopy.TITLE}: Alpha").performClick()
        compose.waitForIdle()

        // The sheet is open and listing connections; only the footer is gone.
        compose.onNodeWithTag("Connection switcher sheet").assertExists()
        compose.onNode(descriptionStartsWith("Bravo")).assertExists()
        compose.onNodeWithContentDescription(ConnectionsCopy.MANAGE_GATEWAYS).assertDoesNotExist()
        compose.onNodeWithText(ConnectionsCopy.MANAGE_GATEWAYS).assertDoesNotExist()
    }

    /**
     * The sheet's own row carries its label as a *prefix* of its content
     * description ("Alpha. https://alpha.test…"), which is what distinguishes
     * it from the trigger's "Registered gateways: Alpha" — a plain substring
     * match on the label would hit both.
     */
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
