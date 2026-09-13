package com.hermesagent.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import com.hermesagent.mobile.ui.chat.Composer
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode

/** Debug-only fixture that mounts the real composer with safe wire text. */
class ComposerReferenceParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = ComposerReferenceFixtureState.parse(intent.getStringExtra(EXTRA_STATE))
        val theme = if (intent.getStringExtra(EXTRA_THEME) == "light") HermesThemeMode.Light else HermesThemeMode.Dark
        setContent {
            HermesTheme(AppearanceSelection("mono", theme)) {
                Column(Modifier.fillMaxSize().background(HermesTheme.tokens.chatSurface).systemBarsPadding()) {
                    Spacer(Modifier.weight(1f))
                    Composer(
                        draft = state.wireText, onDraftChange = {}, onSend = {}, onStop = {}, isStreaming = false,
                        canSend = true, connected = true, statusLine = "Synthetic capture connection",
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_STATE = "visual_parity_state"
        const val EXTRA_THEME = "visual_parity_theme"
    }
}

/** Every composer-reference state in the catalog; status-stack values are rejected. */
private enum class ComposerReferenceFixtureState(val wireValue: String, val wireText: String) {
    UrlChip("url-chip", "@url:`https://example.invalid/reference` "),
    FileChip("file-chip", "@file:`fixtures/example.txt` "),
    FolderChip("folder-chip", "@folder:`fixtures` "),
    ;

    companion object {
        fun parse(value: String?): ComposerReferenceFixtureState = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("unsupported ComposerReference parity state: $value")
    }
}
