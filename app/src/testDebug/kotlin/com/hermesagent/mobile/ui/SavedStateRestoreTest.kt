package com.hermesagent.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.relay.RelayUiState
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the user was survives the composition being thrown away.
 *
 * The composition is rebuilt from saved state alone: nothing retained is
 * available to cover for a value that was never saved. That is a property of
 * `rememberSaveable` and of the saver the navigation state uses, so it is
 * provable without a device — the instrumented lane keeps only the other half,
 * a real Activity destroy and rebuild with real `Bundle` parceling
 * (`ActivityRecreateTest`).
 *
 * Neither is a system-initiated process kill. The OS reclaiming the app under
 * memory pressure, and everything a cold restart then has to re-acquire, stays
 * on the physical device matrix (issue #72, S39).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedStateRestoreTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the open destination survives saved-state restore alone`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            HermesApp(
                chatState = ChatUiState(),
                gatewayState = GatewaySettingsUiState(),
                sshState = SshUiState(),
                appearance = AppearanceSelection(),
                chatActions = ChatActions(),
                appearanceActions = AppearanceActions(),
                gatewayActions = GatewayActions(),
                sshActions = SshActions(),
                relayState = RelayUiState(),
                relayActions = RelayActions(),
            )
        }

        compose.onNodeWithContentDescription(OPEN_SETTINGS).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(GATEWAYS_ROW).assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.onNodeWithTag(GATEWAYS_ROW).assertIsDisplayed()
        // Falling back to chat would also "work" without restoring anything.
        compose.onNodeWithContentDescription(COMPOSER_FIELD).assertDoesNotExist()
    }

    private companion object {
        const val OPEN_SETTINGS = "Open settings"
        const val COMPOSER_FIELD = "Message Hermes"
        const val GATEWAYS_ROW = "settings-row-gateways"
    }
}
