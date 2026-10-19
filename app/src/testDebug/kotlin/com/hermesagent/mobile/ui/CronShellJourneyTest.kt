package com.hermesagent.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CronShellJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun cronDisclosureSurvivesCompactDrawerCloseWithoutHiddenComposition() {
        compose.setContent {
            HermesApp(
                chatState = ChatUiState(), gatewayState = GatewaySettingsUiState(), sshState = SshUiState(),
                appearance = AppearanceSelection(), chatActions = ChatActions(),
                appearanceActions = AppearanceActions(), gatewayActions = GatewayActions(), sshActions = SshActions(),
                pluginStore = testPluginStore(),
                cronSection = { close ->
                    var expanded by androidx.compose.runtime.saveable.rememberSaveable {
                        androidx.compose.runtime.mutableStateOf(false)
                    }
                    androidx.compose.material3.TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Fixture history expanded" else "Fixture history collapsed")
                    }
                    androidx.compose.material3.TextButton(onClick = close) { Text("Close fixture drawer") }
                },
            )
        }
        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithText("Fixture history collapsed").performScrollTo().performClick()
        compose.onNodeWithText("Close fixture drawer").performScrollTo().performClick()
        compose.onNodeWithText("Fixture history expanded").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithText("Fixture history expanded").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sidebarShowsRunOpenFailureOnItsOriginatingSurface() {
        val owner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        val model = com.hermesagent.mobile.ui.cron.CronViewModel(
            com.hermesagent.mobile.ui.cron.FakeCronApi(), kotlinx.coroutines.flow.MutableStateFlow(1L),
            kotlinx.coroutines.flow.MutableStateFlow(true), ownerScope = owner,
        )
        try {
            compose.setContent {
                val state by model.state.collectAsState()
                com.hermesagent.mobile.ui.theme.HermesTheme(selection = AppearanceSelection()) {
                    com.hermesagent.mobile.ui.cron.CronSidebarSection(state, model, {}, {}, visible = true)
                }
            }
            compose.runOnIdle { model.show("default"); model.reportRunOpenFailure() }
            compose.onNodeWithContentDescription("Cron jobs").performClick()
            compose.onNodeWithText("Failed to open cron run. Try again.").assertIsDisplayed()
        } finally { owner.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test
    fun `scheduled jobs is a core destination and restores without the Bots plugin`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            HermesApp(
                chatState = ChatUiState(), gatewayState = GatewaySettingsUiState(), sshState = SshUiState(),
                appearance = AppearanceSelection(), chatActions = ChatActions(),
                appearanceActions = AppearanceActions(), gatewayActions = GatewayActions(), sshActions = SshActions(),
                pluginStore = testPluginStore(),
                cronContent = {
                    val back = LocalCronBackAction.current
                    com.hermesagent.mobile.ui.common.HermesIconButton(
                        com.hermesagent.mobile.ui.common.HermesIcon.ArrowLeft,
                        "Back", onClick = { back?.invoke() },
                    )
                    Text("Core cron manager", Modifier.testTag("cron-manager"))
                },
            )
        }
        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithTag("sidebar-action-scheduled-jobs").performScrollTo().performClick()
        compose.onNodeWithTag("cron-manager").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("cron-manager").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Message Hermes").assertIsDisplayed()
        assertEquals(HermesDestination.Chat, HermesDestination.Cron.backDestination())
    }
}
