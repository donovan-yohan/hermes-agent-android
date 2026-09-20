package com.hermesagent.mobile.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.hermesagent.mobile.data.gateway.GatewayLogsResult
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class GatewayLogsDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `transcript logs action requires confirmation and dismiss isolates late HTTP completion`() {
        var calls = 0
        val answer = CompletableDeferred<GatewayLogsResult>()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember {
                GatewayLogsController(scope, { GatewayLogsIdentity(7, 1, "session", "profile") }, { true }) { current ->
                    assertTrue(current()); calls++; answer.await()
                }
            }
            val logs by controller.state.collectAsState()
            val actions = remember {
                ChatActions(
                    onViewGatewayLogs = { id, endpoint ->
                        assertEquals("failed", id); assertEquals(7L, endpoint); controller.open()
                    },
                    onConfirmGatewayLogs = controller::confirm,
                    onDismissGatewayLogs = controller::dismiss,
                )
            }
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                ChatScreen(
                    state = ChatUiState(
                        gatewayLogs = logs, diagnosticsEndpointGeneration = 7L,
                        transcript = listOf(AssistantTurn("failed", "", 1L, error = "Failed reply")),
                        connection = GatewayConnectionState(GatewayConnectionStatus.Connected),
                    ), actions = actions, onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithText("View Gateway logs").performScrollTo().performClick()
        compose.onNodeWithTag("Gateway logs dialog").assertIsDisplayed()
        compose.onNodeWithText("Logs may include other sessions", substring = true).assertExists()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.onNodeWithTag("Gateway logs dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithText("View Gateway logs").performScrollTo().performClick()
        compose.onNodeWithText("Read Gateway logs").performScrollTo().performClick()
        compose.onNodeWithText("Reading Gateway logs…").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("View Gateway logs").performScrollTo().performClick()
        compose.runOnIdle { answer.complete(GatewayLogsResult.Content("old private excerpt", false)) }
        compose.onNodeWithText("Read Gateway logs").assertExists()
        compose.onNodeWithTag("Gateway log excerpt").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun `finished excerpt offers close only without export upload or refresh`() {
        compose.setContent {
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Light)) {
                GatewayLogsDialog(GatewayLogsState(1, GatewayLogsState.Phase.Finished,
                    GatewayLogsResult.Content("safe excerpt", true)), onConfirm = { error("unexpected") }, onDismiss = {})
            }
        }
        compose.onNodeWithText("safe excerpt").assertIsDisplayed()
        compose.onNodeWithText("Display limit reached. This excerpt may be incomplete.").assertExists()
        compose.onNodeWithText("Copy").assertDoesNotExist()
        compose.onNodeWithText("Export").assertDoesNotExist()
        compose.onNodeWithText("Upload").assertDoesNotExist()
        compose.onNodeWithText("Read Gateway logs").assertDoesNotExist()
        compose.onNodeWithText("Close").assertIsEnabled()
    }
}
