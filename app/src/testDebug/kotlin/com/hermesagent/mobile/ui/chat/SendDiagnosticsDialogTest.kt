package com.hermesagent.mobile.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.hermesagent.mobile.data.gateway.DiagnosticsResult
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
class SendDiagnosticsDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `real transcript opens consent cancel sends nothing and explicit upload shows progress then receipt`() {
        var calls = 0
        val answer = CompletableDeferred<DiagnosticsResult>()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember {
                SendDiagnosticsController(scope, { 7L }, { 1L }, { true }) { _, endpoint, dispatch ->
                    assertEquals(7L, endpoint)
                    assertTrue(dispatch { calls++; true })
                    answer.await()
                }
            }
            val diagnostics by controller.state.collectAsState()
            val actions = remember {
                ChatActions(
                    onSendDiagnostics = { id, endpoint ->
                        assertEquals("failed", id)
                        assertEquals(7L, endpoint)
                        controller.open("Failed reply")
                    },
                    onConfirmDiagnostics = controller::confirm,
                    onDismissDiagnostics = controller::dismiss,
                )
            }
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                ChatScreen(
                    state = ChatUiState(
                        diagnostics = diagnostics, diagnosticsEndpointGeneration = 7L,
                        transcript = listOf(AssistantTurn("failed", "", 1L, error = "Failed reply")),
                        connection = GatewayConnectionState(GatewayConnectionStatus.Connected),
                    ),
                    actions = actions, onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithText("Send diagnostics").performScrollTo().performClick()
        compose.onNodeWithTag("Send diagnostics dialog").assertIsDisplayed()
        compose.onNodeWithText("Consent applies to this upload only.").assertExists()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.onNodeWithTag("Send diagnostics dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithText("Send diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Upload diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Uploading diagnostics…").assertIsDisplayed()
        compose.onNodeWithText("Upload diagnostics").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, calls)
            answer.complete(DiagnosticsResult.Uploaded(null, "report-1", null))
        }
        compose.onNodeWithText("Diagnostics uploaded").assertIsDisplayed()
        compose.onNodeWithText("Upload ID: report-1").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("Send diagnostics dialog").assertDoesNotExist()
    }

    @Test fun `unsupported outcome is honest without an automatic retry action`() {
        compose.setContent {
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Light)) {
                SendDiagnosticsDialog(
                    SendDiagnosticsState(1L, SendDiagnosticsState.Phase.Finished, DiagnosticsResult.Unsupported),
                    onConfirm = { error("No consent given") }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("This Gateway does not support diagnostic uploads. Ask its operator to update it.").assertIsDisplayed()
        compose.onNodeWithText("Upload diagnostics").assertDoesNotExist()
        compose.onNodeWithText("Open private report").assertDoesNotExist()
    }
}
