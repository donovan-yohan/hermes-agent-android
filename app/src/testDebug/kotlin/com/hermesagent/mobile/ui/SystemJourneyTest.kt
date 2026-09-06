package com.hermesagent.mobile.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
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
import androidx.compose.ui.test.performScrollTo
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewayStatusSummary
import com.hermesagent.mobile.plugins.Contribution
import com.hermesagent.mobile.plugins.ContributionRegistry
import com.hermesagent.mobile.plugins.PluginAreas
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.common.WIP_PILL
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.settings.SettingsRow
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.system.SYSTEM_ACTION_TAG
import com.hermesagent.mobile.ui.system.SYSTEM_ERROR_TAG
import com.hermesagent.mobile.ui.system.SYSTEM_LOADING_TAG
import com.hermesagent.mobile.ui.system.SYSTEM_NO_LOGS_TAG
import com.hermesagent.mobile.ui.system.SYSTEM_RESTART_TAG
import com.hermesagent.mobile.ui.system.SYSTEM_UPDATE_TAG
import com.hermesagent.mobile.ui.system.SYSTEM_VERSION_TAG
import com.hermesagent.mobile.ui.system.SystemActionPhase
import com.hermesagent.mobile.ui.system.SystemActionState
import com.hermesagent.mobile.ui.system.SystemActions
import com.hermesagent.mobile.ui.system.SystemCopy
import com.hermesagent.mobile.ui.system.SystemUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesSpacing
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The System panel as a Settings destination: where its row sits, what the
 * panel renders in Desktop's order, and what ships disabled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the system row sits between Gateways and Relay and opens the panel`() {
        launch()

        compose.onNodeWithContentDescription("Open settings").performClick()

        // Desktop's palette has no phone form, so the panel becomes a Settings
        // destination — placed with the Gateway rows it is about, and before
        // Plugins and Relay, which are a plugin inventory and a workspace.
        compose.onNodeWithTag(GATEWAYS).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 1f),
        )
        compose.onNodeWithTag(SYSTEM_ROW).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 2f),
        )
        compose.onNodeWithTag(PLUGINS_ROW).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 3f),
        )
        compose.onNodeWithTag(RELAY_ROW).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 4f),
        )

        compose.onNodeWithTag(SYSTEM_ROW)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(HermesSpacing().touchTarget)
        // Verbatim `commandCenter.sectionEntries.system` (`en.ts:1548` @ the pin).
        compose.onNodeWithContentDescription("System panel. Gateway status, logs, restart/update")
            .assertIsDisplayed()

        compose.onNodeWithTag(SYSTEM_ROW).performClick()
        // No status has arrived yet in this fixture, so the panel is its own
        // loading state rather than a blank overlay.
        compose.onNodeWithTag(SYSTEM_LOADING_TAG).assertIsDisplayed()
    }

    @Test
    fun `the row is disabled with no Gateway connected`() {
        launch(connected = false)

        compose.onNodeWithContentDescription("Open settings").performClick()

        compose.onNodeWithTag(SYSTEM_ROW).assertIsNotEnabled()
    }

    @Test
    fun `the panel renders Desktop's order, its two actions and its progress line`() {
        launch(
            system = SystemUiState(
                status = GatewayStatusSummary(
                    version = "0.5.1",
                    activeSessions = 2L,
                    gatewayRunning = true,
                    canUpdateHermes = true,
                ),
                action = SystemActionState("gateway-restart", SystemActionPhase.Running),
            ),
        )
        openPanel()

        compose.onNodeWithText(SystemCopy.GATEWAY_RUNNING).assertIsDisplayed()
        compose.onNodeWithTag(SYSTEM_VERSION_TAG).assertIsDisplayed()
        compose.onNodeWithText("Hermes 0.5.1 · Active sessions 2").assertIsDisplayed()
        // Restart first, Update second — Desktop's order (`index.tsx:444-451`).
        compose.onNodeWithTag(SYSTEM_RESTART_TAG)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(HermesSpacing().touchTarget)
        compose.onNodeWithTag(SYSTEM_UPDATE_TAG)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(HermesSpacing().touchTarget)
        compose.onNodeWithText(SystemCopy.RESTART_GATEWAY).assertIsDisplayed()
        compose.onNodeWithText(SystemCopy.UPDATE_HERMES).assertIsDisplayed()
        compose.onNodeWithTag(SYSTEM_ACTION_TAG).assertIsDisplayed()
        compose.onNodeWithText("gateway-restart · running").assertIsDisplayed()
    }

    @Test
    fun `a stopped messaging gateway says so rather than staying silent`() {
        launch(
            system = SystemUiState(
                status = GatewayStatusSummary(
                    version = "0.5.1",
                    activeSessions = 0L,
                    gatewayRunning = false,
                    canUpdateHermes = true,
                ),
            ),
        )
        openPanel()

        compose.onNodeWithText(SystemCopy.GATEWAY_STOPPED).assertIsDisplayed()
        assertEquals(0, compose.countWithText(SystemCopy.GATEWAY_RUNNING))
    }

    @Test
    fun `no status yet is the loading line, and a failure is an inline error`() {
        launch(system = SystemUiState(status = null))
        openPanel()
        compose.onNodeWithTag(SYSTEM_LOADING_TAG).assertIsDisplayed()
        compose.onNodeWithText(SystemCopy.LOADING_STATUS).assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag(SYSTEM_ROW).assertIsDisplayed()
    }

    @Test
    fun `a failed restart shows Desktop's own sentence inline`() {
        launch(
            system = SystemUiState(
                status = GatewayStatusSummary("0.5.1", 0L, gatewayRunning = true, canUpdateHermes = true),
                action = SystemActionState("gateway-restart", SystemActionPhase.Failed),
                actionError = SystemCopy.GATEWAY_RESTART_FAILED,
            ),
        )
        openPanel()

        compose.onNodeWithTag(SYSTEM_ERROR_TAG).assertIsDisplayed()
        compose.onNodeWithText(SystemCopy.GATEWAY_RESTART_FAILED).assertIsDisplayed()
    }

    @Test
    fun `the recent-logs section ships visible and disabled behind the marker chip`() {
        launch(
            system = SystemUiState(
                status = GatewayStatusSummary("0.5.1", 0L, gatewayRunning = true, canUpdateHermes = true),
            ),
        )
        openPanel()

        // Every control Desktop has, and none of them working: an absent
        // control would claim the surface was never meant to have one.
        compose.onNodeWithText(SystemCopy.RECENT_LOGS.uppercase()).assertIsDisplayed()
        compose.onNodeWithTag(WIP_PILL).assertIsDisplayed()
        for (file in SystemCopy.LOG_FILES) {
            compose.onNodeWithText(file).assertIsDisplayed()
        }
        for (level in SystemCopy.LOG_LEVELS) {
            compose.onNodeWithText(level).assertIsDisplayed()
        }
        compose.onNodeWithText(SystemCopy.LOG_SEARCH_PLACEHOLDER).assertIsDisplayed()
        // Below the fold on a phone, which is what the scroll container is for.
        compose.onNodeWithTag(SYSTEM_NO_LOGS_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Recent logs. Work in progress.").assertIsNotEnabled()
    }

    @Test
    fun `app back transitions form the settings parent chain`() {
        assertEquals(HermesDestination.Settings, HermesDestination.System.backDestination())
    }

    private fun openPanel() {
        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithTag(SYSTEM_ROW).performClick()
    }

    private fun launch(
        connected: Boolean = true,
        system: SystemUiState = SystemUiState(),
    ) {
        compose.setContent {
            val registry = remember {
                ContributionRegistry().apply {
                    registerMany(
                        listOf(
                            Contribution(
                                id = "hermes-plugin-relay:sidebar-nav",
                                area = PluginAreas.SIDEBAR_NAV_AREA,
                                source = "plugin:hermes-plugin-relay",
                                title = "Relay channels",
                                order = 300,
                                render = {
                                    SettingsRow(
                                        label = "Relay channels",
                                        description = "Channels, transcripts, and messaging live in their own workspace.",
                                        traversalIndex = 4f,
                                        onClick = {},
                                    )
                                },
                            ),
                        ),
                    )
                }
            }
            HermesApp(
                chatState = ChatUiState(),
                gatewayState = GatewaySettingsUiState(
                    connection = GatewayConnectionState(
                        if (connected) {
                            GatewayConnectionStatus.Connected
                        } else {
                            GatewayConnectionStatus.Disconnected
                        },
                    ),
                ),
                sshState = SshUiState(),
                appearance = AppearanceSelection(),
                chatActions = ChatActions(),
                appearanceActions = AppearanceActions(),
                gatewayActions = GatewayActions(),
                sshActions = SshActions(),
                systemState = system,
                systemActions = SystemActions(),
                pluginRegistry = registry,
                pluginStore = testPluginStore(),
            )
        }
        compose.waitForIdle()
    }

    private companion object {
        const val GATEWAYS = "settings-row-gateways"
        const val SYSTEM_ROW = "settings-row-system panel"
        const val PLUGINS_ROW = "settings-row-plugins"
        const val RELAY_ROW = "settings-row-relay channels"
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.countWithText(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
