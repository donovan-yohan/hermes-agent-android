package com.hermesagent.mobile.ui.gateway

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.hermesagent.mobile.data.connections.CONNECTION_SEARCH_THRESHOLD
import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.ui.ConnectionsActions
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Journey coverage for [ConnectionsSection], the saved-connections registry
 * that lives at the foot of the Gateways settings page. Drives the composable
 * directly with a mutable [ConnectionsUiState], mutating it from the actions
 * exactly as the real [ConnectionsViewModel] would, so the assertions exercise
 * the same state machine a device does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionsJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a two row registry lists both labels, marks the active row, and redacts the summary`() {
        val state = ConnectionsUiState(
            connections = listOf(
                remoteConnection("a", "Alpha", "https://alpha.test"),
                sshConnection("b", "Bravo", "demo-host"),
            ),
            activeId = "a",
            loaded = true,
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ConnectionsSection(state = state, actions = ConnectionsActions())
            }
        }

        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithText("Bravo").assertExists()
        compose.onNodeWithText(ConnectionsCopy.CURRENT_PILL).assertExists()

        // The row description is "<kind label> · <redacted endpoint> · <auth mode>"
        // (`ConnectionsSection.kt`'s `SavedConnection.summary()`); match the whole
        // string rather than a bare "SSH" substring, which also appears in the
        // section's own intro copy.
        compose.onNodeWithText("${ConnectionsCopy.KIND_REMOTE} · https://alpha.test · Browser sign-in")
            .assertExists()
        compose.onNodeWithText("${ConnectionsCopy.KIND_SSH} · demo-user@demo-host · Tailscale SSH")
            .assertExists()
    }

    @Test
    fun `an empty registry shows the empty state copy`() {
        val state = ConnectionsUiState(connections = emptyList(), loaded = true)
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ConnectionsSection(state = state, actions = ConnectionsActions())
            }
        }

        compose.onNodeWithText(ConnectionsCopy.EMPTY).assertExists()
    }

    @Test
    fun `adding a connection opens the editor and saving fires onSaveEditor`() {
        var state by mutableStateOf(ConnectionsUiState(loaded = true))
        var saveCount = 0
        val actions = ConnectionsActions(
            onBeginAdd = { state = state.copy(editor = ConnectionEditorState()) },
            onEditLabel = { value -> state = state.copy(editor = state.editor?.copy(label = value)) },
            onSaveEditor = { saveCount++ },
        )
        // ConnectionsSection is always hosted inside the Gateways screen's own
        // scrollable Column (`GatewayScreen.kt`'s `RemoteGatewayScreen`); the
        // editor form is tall enough that its Save button needs the same
        // scrollable ancestor here to be reachable by a real click.
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ConnectionsSection(state = state, actions = actions)
                }
            }
        }

        compose.onNodeWithContentDescription(ConnectionsCopy.ADD_CONNECTION).performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription(ConnectionsCopy.LABEL_TITLE).assertExists()
        compose.onNodeWithContentDescription(ConnectionsCopy.LABEL_TITLE).performTextInput("Homelab")
        compose.waitForIdle()
        assertEquals("Homelab", state.editor?.label)

        compose.onNodeWithText(ConnectionsCopy.SAVE)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitForIdle()
        assertEquals(1, saveCount)
    }

    @Test
    fun `editing a row opens the editor scoped to that row and hides the kind picker`() {
        var state by mutableStateOf(
            ConnectionsUiState(
                connections = listOf(
                    remoteConnection("a", "Alpha", "https://alpha.test"),
                    sshConnection("b", "Bravo", "demo-host"),
                ),
                activeId = "a",
                loaded = true,
            ),
        )
        var beganEditId: String? = null
        val actions = ConnectionsActions(
            onBeginEdit = { id ->
                beganEditId = id
                val row = state.connections.first { it.id == id }
                state = state.copy(
                    editor = ConnectionEditorState(
                        id = row.id,
                        kind = row.kind,
                        label = row.label,
                        destination = row.host.destination,
                    ),
                )
            },
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ConnectionsSection(state = state, actions = actions)
            }
        }

        compose.onNodeWithContentDescription("${ConnectionsCopy.EDIT_CONNECTION} Bravo").performClick()
        compose.waitForIdle()

        assertEquals("b", beganEditId)
        compose.onNodeWithContentDescription(ConnectionsCopy.KIND_REMOTE_DESC).assertDoesNotExist()
        compose.onNodeWithContentDescription(ConnectionsCopy.KIND_SSH_DESC).assertDoesNotExist()
    }

    @Test
    fun `requesting removal shows a confirm sheet and confirming fires onConfirmRemove`() {
        var state by mutableStateOf(
            ConnectionsUiState(
                connections = listOf(
                    remoteConnection("a", "Alpha", "https://alpha.test"),
                    sshConnection("b", "Bravo", "demo-host"),
                ),
                activeId = "a",
                loaded = true,
            ),
        )
        var requestedId: String? = null
        var confirmed = 0
        val actions = ConnectionsActions(
            onRequestRemove = { id ->
                requestedId = id
                state = state.copy(removeTarget = state.connections.first { it.id == id })
            },
            onConfirmRemove = { confirmed++ },
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ConnectionsSection(state = state, actions = actions)
            }
        }

        compose.onNodeWithContentDescription("${ConnectionsCopy.REMOVE_CONNECTION} Bravo").performClick()
        compose.waitForIdle()

        assertEquals("b", requestedId)
        compose.onNodeWithText(ConnectionsCopy.REMOVE_CONFIRM_TITLE).assertExists()

        compose.onNodeWithText(ConnectionsCopy.REMOVE_CONNECTION).performClick()
        compose.waitForIdle()
        assertEquals(1, confirmed)
    }

    @Test
    fun `a duplicate error on the editor is rendered`() {
        val error = ConnectionsCopy.duplicateUrl("Existing gateway")
        val state = ConnectionsUiState(
            connections = emptyList(),
            editor = ConnectionEditorState(kind = ConnectionKind.Remote, label = "New", error = error),
            loaded = true,
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ConnectionsSection(state = state, actions = ConnectionsActions())
            }
        }

        compose.onNodeWithText(error).assertExists()
    }

    @Test
    fun `search appears once the registry reaches the threshold`() {
        val underThreshold = (1 until CONNECTION_SEARCH_THRESHOLD).map { index ->
            remoteConnection("g$index", "Gateway $index", "https://g$index.test")
        }
        var state by mutableStateOf(
            ConnectionsUiState(connections = underThreshold, activeId = "g1", loaded = true),
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ConnectionsSection(state = state, actions = ConnectionsActions())
            }
        }

        compose.onNodeWithContentDescription(ConnectionsCopy.SEARCH_PLACEHOLDER).assertDoesNotExist()

        state = state.copy(
            connections = state.connections + remoteConnection(
                "g$CONNECTION_SEARCH_THRESHOLD",
                "Gateway $CONNECTION_SEARCH_THRESHOLD",
                "https://g$CONNECTION_SEARCH_THRESHOLD.test",
            ),
        )
        compose.waitForIdle()

        compose.onNodeWithContentDescription(ConnectionsCopy.SEARCH_PLACEHOLDER).assertExists()
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
