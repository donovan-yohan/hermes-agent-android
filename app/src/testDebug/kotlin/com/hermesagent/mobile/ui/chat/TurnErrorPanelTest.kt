package com.hermesagent.mobile.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.TurnErrorDetails
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("DEPRECATION")
class TurnErrorPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var retries = 0
    private var copiedText: AnnotatedString? = null
    private val clipboard = object : ClipboardManager {
        override fun getText() = copiedText
        override fun setText(annotatedString: AnnotatedString) { copiedText = annotatedString }
    }

    private fun render(mode: HermesThemeMode = HermesThemeMode.Dark, retryable: Boolean = true, enabled: Boolean = true) {
        compose.setContent {
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, mode)) {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    Column(Modifier.fillMaxSize().background(HermesTheme.tokens.chatSurface).padding(16.dp)) {
                        TurnErrorPanel(
                            turn = AssistantTurn(
                                id = "failed-reply", markdown = "", atMillis = 1,
                                error = SUMMARY,
                                errorDetails = TurnErrorDetails(
                                    details = "Internal start failed. password=synthetic-secret",
                                    layer = "gateway", code = "internal_start", retryable = retryable,
                                ),
                            ),
                            retryEnabled = enabled,
                            onRetry = { retries++ },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun `dark collapsed and expanded capture with working recovery and safe clipboard`() {
        render()
        compose.onNodeWithText("Hermes hit a problem").assertIsDisplayed()
        compose.onNodeWithTag("Error details").assertDoesNotExist()
        compose.onNodeWithText("View Gateway logs").assertIsNotEnabled()
        compose.onNodeWithText("Send diagnostics").assertIsNotEnabled()
        capture("dark-collapsed")
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
        compose.onNodeWithTag("Error details toggle").performClick()
        compose.onNodeWithTag("Error details").assertIsDisplayed()
        capture("dark-expanded")
        compose.onNodeWithText("Copy error details").performClick()
        assertTrue(copiedText!!.text.contains("code: internal_start"))
        assertFalse(copiedText!!.text.contains("synthetic-secret"))
        compose.onNodeWithText("Copied").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss error").performClick()
        compose.onNodeWithText("Hermes hit a problem").assertDoesNotExist()
        assertEquals(1, retries)
    }

    @Test fun `light capture keeps unsupported controls marked`() {
        render(HermesThemeMode.Light)
        capture("light-collapsed")
        compose.onNodeWithTag("Error details toggle").performClick()
        capture("light-expanded")
    }

    @Test fun `busy session disables retry without disabling copy or dismissal`() {
        render(enabled = false)
        compose.onNodeWithText("Retry").assertIsNotEnabled()
        compose.onNodeWithText("Copy error details").assertIsEnabled()
        compose.onNodeWithContentDescription("Dismiss error").assertIsEnabled()
    }

    @Test fun `deterministic failure does not offer a misleading retry`() {
        render(retryable = false)
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Copy error details").assertIsEnabled()
    }

    @Test fun `empty failed assistant in real transcript exposes retry with its own entry id`() {
        var retried: String? = null
        compose.setContent {
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                ChatScreen(
                    state = ChatUiState(
                        activeSession = com.hermesagent.mobile.data.session.SessionSummary(
                            id = "failed-session", title = "Recovery", preview = "", lastActiveAtMillis = 1,
                        ),
                        transcript = listOf(
                            com.hermesagent.mobile.data.session.UserTurn("source", "Try this", 1),
                            AssistantTurn("failed-reply", "", 2, error = SUMMARY),
                        ),
                        isStreaming = false,
                        connection = com.hermesagent.mobile.data.gateway.GatewayConnectionState(
                            com.hermesagent.mobile.data.gateway.GatewayConnectionStatus.Connected,
                        ),
                    ),
                    actions = com.hermesagent.mobile.ui.ChatActions(onRegenerateReply = { retried = it }),
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals("failed-reply", retried)
        capture("dark-transcript")
    }

    /** Native Robolectric rendering, not device/IME or live Gateway evidence. */
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            val directory = File("build/visual-parity/turn-error/android").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    private companion object {
        const val SUMMARY = "Hermes hit an internal problem starting this reply. Send your message again. If it keeps happening, use Desktop to send diagnostics."
    }
}
