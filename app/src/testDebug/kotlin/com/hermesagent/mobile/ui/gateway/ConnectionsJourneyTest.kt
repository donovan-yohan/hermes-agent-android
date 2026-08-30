package com.hermesagent.mobile.ui.gateway

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.hermesagent.mobile.data.connections.CONNECTION_SEARCH_THRESHOLD
import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.gateway.DEFAULT_LOCAL_GATEWAY_URL
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.gateway.LocalGatewayCopy
import com.hermesagent.mobile.data.gateway.LocalGatewayProfile
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.ui.ConnectionsActions
import com.hermesagent.mobile.ui.common.COMING_SOON
import com.hermesagent.mobile.ui.GatewayActions
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import com.hermesagent.mobile.data.connections.ConnectionRegistry
import com.hermesagent.mobile.data.connections.ConnectionRegistryStore
import com.hermesagent.mobile.data.connections.ConnectionSwitchController
import com.hermesagent.mobile.data.gateway.GatewayBrowserLauncher
import com.hermesagent.mobile.data.gateway.GatewayConnectResult
import com.hermesagent.mobile.data.gateway.GatewayConnectionController
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.ssh.SshCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    @Test
    fun `adding a Local gateway takes an address and a token, then the row summarises both`() {
        var state by mutableStateOf(ConnectionsUiState(loaded = true))
        val actions = ConnectionsActions(
            onBeginAdd = { state = state.copy(editor = ConnectionEditorState()) },
            // The prefill and the kind rule live in the ViewModel; the journey
            // mirrors them so this test drives the same form a device does.
            onEditKind = { kind ->
                state = state.copy(
                    editor = state.editor?.copy(
                        kind = kind,
                        url = if (kind == ConnectionKind.Local) DEFAULT_LOCAL_GATEWAY_URL else "",
                    ),
                )
            },
            onEditLabel = { value -> state = state.copy(editor = state.editor?.copy(label = value)) },
            onEditToken = { value -> state = state.copy(editor = state.editor?.copy(token = value)) },
            onSaveEditor = {
                val editor = requireNotNull(state.editor)
                state = ConnectionsUiState(
                    connections = listOf(
                        SavedConnection(
                            id = "local",
                            label = editor.label,
                            kind = ConnectionKind.Local,
                            local = LocalGatewayProfile(baseUrl = editor.url),
                        ),
                    ),
                    activeId = "local",
                    loaded = true,
                )
            },
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ConnectionsSection(state = state, actions = actions)
                }
            }
        }

        compose.onNodeWithContentDescription(ConnectionsCopy.ADD_CONNECTION).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(ConnectionsCopy.KIND_LOCAL_DESC).performClick()
        compose.waitForIdle()

        // The prefill rule itself is `ConnectionsViewModel`'s and is asserted
        // there; this fake only mirrors it so the form under test is the one a
        // device shows. What is worth asserting *here* is that the address
        // reaches the field as a rendered value rather than a placeholder.
        compose.onNodeWithText(DEFAULT_LOCAL_GATEWAY_URL).assertExists()
        compose.onNodeWithContentDescription(ConnectionsCopy.LABEL_TITLE).performTextInput("This phone")
        compose.onNodeWithContentDescription(ConnectionsCopy.TOKEN_TITLE)
            .performScrollTo()
            .performTextInput("demo-session-token")
        compose.waitForIdle()
        assertEquals("demo-session-token", state.editor?.token)

        // The limitation stands beside the action it qualifies.
        compose.onNodeWithText(ConnectionsCopy.LOCAL_LIMITATION).assertExists()
        compose.onNodeWithText(ConnectionsCopy.SAVE).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("This phone").assertExists()
        compose.onNodeWithText(ConnectionsCopy.CURRENT_PILL).assertExists()
        compose.onNodeWithText("${ConnectionsCopy.KIND_LOCAL} · 127.0.0.1:9119 · ${SavedConnection.SESSION_TOKEN}")
            .assertExists()
        // Whatever else the row says, it never says the token.
        compose.onNodeWithText("demo-session-token", substring = true).assertDoesNotExist()
    }

    @Test
    fun `an address that is not this device is refused where it was typed`() {
        val state = ConnectionsUiState(
            editor = ConnectionEditorState(
                kind = ConnectionKind.Local,
                label = "Not this device",
                url = "http://hermes.example.com:9119",
                error = LocalGatewayCopy.INVALID_URL,
            ),
            loaded = true,
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ConnectionsSection(state = state, actions = ConnectionsActions())
                }
            }
        }

        compose.onNodeWithText(LocalGatewayCopy.INVALID_URL).performScrollTo().assertExists()
    }

    @Test
    fun `a re-addressed Local row is told the saved token no longer applies`() {
        val state = ConnectionsUiState(
            connections = listOf(localConnection("local", "This phone", DEFAULT_LOCAL_GATEWAY_URL)),
            activeId = "local",
            editor = ConnectionEditorState(
                id = "local",
                kind = ConnectionKind.Local,
                label = "This phone",
                url = "http://127.0.0.1:9200",
                error = ConnectionsCopy.TOKEN_READDRESSED,
            ),
            loaded = true,
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ConnectionsSection(state = state, actions = ConnectionsActions())
                }
            }
        }

        compose.onNodeWithText(ConnectionsCopy.TOKEN_READDRESSED).performScrollTo().assertExists()
        // The kind is stated on an existing row, never offered.
        compose.onNodeWithContentDescription(ConnectionsCopy.KIND_REMOTE_DESC).assertDoesNotExist()
    }

    @Test
    fun `switching to another row is offered on that row and moves the Current marker`() {
        var state by mutableStateOf(
            ConnectionsUiState(
                connections = listOf(
                    remoteConnection("a", "Alpha", "https://alpha.test"),
                    remoteConnection("b", "Bravo", "https://bravo.test"),
                ),
                activeId = "a",
                loaded = true,
            ),
        )
        val selected = mutableListOf<String>()
        val actions = ConnectionsActions(
            onSelect = { id ->
                selected += id
                // What the real switch does once it settles: the marker moves.
                state = state.copy(activeId = id)
            },
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ConnectionsSection(state = state, actions = actions)
                }
            }
        }

        // The active row does not offer to switch to itself; the other one does.
        compose.onNodeWithContentDescription("${ConnectionsCopy.SWITCH_CONNECTION} Alpha")
            .assertDoesNotExist()
        compose.onNodeWithContentDescription("${ConnectionsCopy.SWITCH_CONNECTION} Bravo")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitForIdle()

        assertEquals(listOf("b"), selected)
        assertEquals("b", state.activeId)
        // Exactly one row is Current, and the offer has moved to the other row.
        compose.onAllNodesWithText(ConnectionsCopy.CURRENT_PILL).assertCountEquals(1)
        compose.onNodeWithContentDescription("${ConnectionsCopy.SWITCH_CONNECTION} Bravo")
            .assertDoesNotExist()
        compose.onNodeWithContentDescription("${ConnectionsCopy.SWITCH_CONNECTION} Alpha")
            .assertExists()
    }

    @Test
    fun `the row being switched to says Connecting for the whole dial, and no row takes a second tap`() {
        // Driven by the real ConnectionSwitchController, because the bug this
        // covers is entirely about its ordering: `select` writes the active
        // marker *before* it waits for the dial, so for the whole settle window
        // the target row is both `current` and `pending`. A hand-built state
        // with activeId still on the old row would never reach that overlap and
        // would pass against the broken UI.
        val rows = listOf(
            remoteConnection("a", "Alpha", "https://alpha.test"),
            remoteConnection("b", "Bravo", "https://bravo.test"),
            remoteConnection("c", "Charlie", "https://charlie.test"),
        )
        val store = FakeRegistryStore(rows, activeId = "a")
        val gateway = StalledGateway()
        // Long enough that the settle window never closes on its own, so the
        // assertions below see the real mid-dial state rather than racing it.
        val switch = ConnectionSwitchController(
            store,
            gateway,
            SessionCache(),
            settleTimeoutMillis = 10 * 60_000L,
        )
        compose.setContent {
            // The same mapping `ConnectionsViewModel` performs over the same two
            // real flows — nothing about the row's state is invented here.
            val registry by store.connectionRegistry.collectAsState(ConnectionRegistry(rows, "a"))
            val pendingId by switch.pendingConnectionId.collectAsState()
            val scope = rememberCoroutineScope()
            HermesTheme(AppearanceSelection()) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ConnectionsSection(
                        state = ConnectionsUiState(
                            connections = registry.connections,
                            activeId = registry.active?.id,
                            pendingId = pendingId,
                            loaded = true,
                        ),
                        actions = ConnectionsActions(
                            onSelect = { id -> scope.launch { switch.select(id) } },
                        ),
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("${ConnectionsCopy.SWITCH_CONNECTION} Bravo")
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        // The controller has moved the marker and is now waiting on the dial.
        assertEquals("b", store.registry.value.activeId)
        assertEquals("b", switch.pendingConnectionId.value)

        // Bravo is Current *and* still says what is happening to it. Before the
        // fix the `if (!current)` guard dropped this control the instant the
        // marker moved, leaving `Current` beside greyed siblings and no word.
        compose.onNodeWithText(ConnectionsCopy.CONNECTING).performScrollTo().assertExists()
        compose.onNodeWithContentDescription("${ConnectionsCopy.SWITCH_CONNECTION} Bravo")
            .performScrollTo()
            .assertIsNotEnabled()
            .performClick()
        // And no other row can start a competing one while it runs.
        compose.onNodeWithContentDescription("${ConnectionsCopy.SWITCH_CONNECTION} Charlie")
            .performScrollTo()
            .assertIsNotEnabled()
            .performClick()
        compose.waitForIdle()

        assertEquals(
            "a tap on a disarmed switch must not start a second teardown",
            1,
            gateway.disconnects,
        )
        assertEquals("b", switch.pendingConnectionId.value)
    }

    @Test
    fun `every row offers Desktop's actions, with the two Android cannot do yet marked`() {
        val state = ConnectionsUiState(
            connections = listOf(remoteConnection("a", "Alpha", "https://alpha.test")),
            activeId = "a",
            loaded = true,
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ConnectionsSection(state = state, actions = ConnectionsActions())
                }
            }
        }

        // Rendered and marked, not omitted: an absent control reads as a
        // surface that never had the feature (`connections-registry.tsx:589-603`).
        compose.onNodeWithText(ConnectionsCopy.TEST_CONNECTION).performScrollTo().assertExists()
        compose.onNodeWithText(ConnectionsCopy.MAKE_PRIMARY).performScrollTo().assertExists()
        compose.onAllNodesWithText(COMING_SOON).assertCountEquals(2)
        compose.onNodeWithContentDescription(
            "${ConnectionsCopy.TEST_CONNECTION}. ${COMING_SOON}",
        ).assertExists()
        compose.onNodeWithContentDescription(
            "${ConnectionsCopy.MAKE_PRIMARY}. ${COMING_SOON}",
        ).assertExists()
    }

    @Test
    fun `switching to a Managed SSH row lands on the SSH pane with Connect one tap away`() {
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
        val actions = ConnectionsActions(onSelect = { id -> connections = connections.copy(activeId = id) })
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                // The whole Gateways screen, because the claim under test is
                // about where the person lands: the route pane is a projection
                // of the active row's kind, so activating an SSH row is what
                // puts the Managed SSH pane's Connect above this list.
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

        compose.onNodeWithContentDescription("${ConnectionsCopy.SWITCH_CONNECTION} Bravo")
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        assertEquals("b", connections.activeId)
        // It is Current even though nothing dialled — the marker is where the
        // app is pointed, not whether a socket is up.
        compose.onNodeWithText(ConnectionsCopy.CURRENT_PILL).performScrollTo().assertExists()
        // And it says why, rather than looking like a switch that hung.
        compose.onNodeWithText(ConnectionsCopy.SSH_NEEDS_CREDENTIAL).performScrollTo().assertExists()
        // The next action the copy names is really on screen.
        compose.onNodeWithText("Connect").performScrollTo().assertExists()
    }

    @Test
    fun `the Managed SSH sentence goes when the connection it asks for is up`() {
        val connections = ConnectionsUiState(
            connections = listOf(sshConnection("b", "Bravo", "demo-host")),
            activeId = "b",
            loaded = true,
        )

        // Connected: SshScreen offers Disconnect, not Connect, so a sentence
        // naming Connect would be advice about a problem that is over.
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                GatewayScreen(
                    state = GatewaySettingsUiState(
                        mode = GatewayConnectionMode.Ssh,
                        connection = GatewayConnectionState(GatewayConnectionStatus.Connected),
                        loaded = true,
                    ),
                    gatewayActions = GatewayActions(),
                    sshState = SshUiState(
                        connection = GatewayConnectionState(GatewayConnectionStatus.Connected),
                    ),
                    sshActions = SshActions(),
                    connectionsState = connections,
                    connectionsActions = ConnectionsActions(),
                )
            }
        }

        compose.onNodeWithText(ConnectionsCopy.SSH_NEEDS_CREDENTIAL).assertDoesNotExist()
        compose.onNodeWithText("Connect").assertDoesNotExist()
        compose.onNodeWithText(ConnectionsCopy.CURRENT_PILL).performScrollTo().assertExists()
    }

    private fun localConnection(id: String, label: String, url: String): SavedConnection =
        SavedConnection(
            id = id,
            label = label,
            kind = ConnectionKind.Local,
            local = LocalGatewayProfile(baseUrl = url),
        )

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
    /** The saved rows, in memory, with the real store's active-marker rules. */
    private class FakeRegistryStore(
        rows: List<SavedConnection>,
        activeId: String?,
    ) : ConnectionRegistryStore {
        val registry = MutableStateFlow(ConnectionRegistry(rows, activeId))
        override val connectionRegistry: StateFlow<ConnectionRegistry> = registry.asStateFlow()

        override suspend fun saveConnection(connection: SavedConnection) {
            registry.update { current ->
                val index = current.connections.indexOfFirst { it.id == connection.id }
                val next = if (index >= 0) {
                    current.connections.toMutableList().also { it[index] = connection }
                } else {
                    current.connections + connection
                }
                current.copy(connections = next)
            }
        }

        override suspend fun removeConnection(id: String) {
            registry.update { current ->
                val next = current.connections.filterNot { it.id == id }
                val active = if (current.activeId == id) next.firstOrNull()?.id else current.activeId
                current.copy(connections = next, activeId = active)
            }
        }

        override suspend fun setActiveConnection(id: String) {
            registry.update { it.copy(activeId = id) }
        }
    }

    /**
     * A gateway that goes down when asked and never comes back up.
     *
     * Nothing publishes `Connected`, so the switch controller's settle wait
     * stays parked and the mid-dial state this test is about persists rather
     * than resolving out from under the assertions.
     */
    private class StalledGateway : GatewayConnectionController {
        private val _state = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Disconnected))
        override val state: StateFlow<GatewayConnectionState> = _state.asStateFlow()

        var disconnects = 0
            private set

        override suspend fun connect(profile: HostProfile, credential: SshCredential): GatewayConnectResult =
            GatewayConnectResult.Connected

        override suspend fun connectRemote(
            profile: RemoteGatewayProfile,
            browser: GatewayBrowserLauncher,
        ): GatewayConnectResult = GatewayConnectResult.Connected

        override suspend fun forgetRemoteAuthentication(profile: RemoteGatewayProfile) = Unit

        override suspend fun disconnect() {
            disconnects += 1
        }
    }
}
