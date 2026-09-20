package com.hermesagent.mobile

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.hermesagent.mobile.data.attachments.MediaStoreRecentImages
import com.hermesagent.mobile.data.attachments.RecentImageAccess
import com.hermesagent.mobile.data.attachments.readRecentImages
import com.hermesagent.mobile.ui.chat.composer.ComposerAddSheetContent
import com.hermesagent.mobile.ui.chat.composer.RecentImagesUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only real MediaStore probe. Grant media access through Android before launching.
 * Only explicitly seeded QA image names are rendered; other device photos stay private.
 * Uses the production query, thumbnails and URI resolution, not a synthetic source.
 */
class RecentImagesProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val source = MediaStoreRecentImages(contentResolver)
        var state by mutableStateOf(RecentImagesUiState(access = RecentImageAccess.Granted, loading = true))
        var result by mutableStateOf("Reading device MediaStore")
        lifecycleScope.launch {
            val read = withContext(Dispatchers.IO) { source.readRecentImages() }
            val seeded = read.images.filter { it.displayName.startsWith("hermes-qa-recent-") }
            state = RecentImagesUiState(
                access = RecentImageAccess.Granted, images = seeded,
                thumbnails = read.thumbnails.filterKeys { id -> seeded.any { it.id == id } },
                failed = read.failed,
            )
            result = "MediaStore failed=${read.failed}; QA images=${seeded.size}; thumbnails=${state.thumbnails.size}"
        }
        setContent {
            HermesTheme(AppearanceSelection()) {
                Column(Modifier.fillMaxSize().background(HermesTheme.tokens.chatSurface).systemBarsPadding()) {
                    Text(result, color = HermesTheme.tokens.textPrimary)
                    ComposerAddSheetContent(
                        recentImages = state,
                        onAddRecentImage = { id ->
                            lifecycleScope.launch {
                                val image = state.images.first { it.id == id }
                                val readable = withContext(Dispatchers.IO) {
                                    contentResolver.openInputStream(Uri.parse(source.sourceFor(image)))?.use { it.read() >= 0 } == true
                                }
                                state = state.copy(addedIds = state.addedIds + id)
                                result = "Selected QA image; source readable=$readable"
                            }
                        },
                        onRequestRecentImageAccess = {}, onPickPhotos = {}, onChooseFiles = {},
                        onChooseUrl = {}, onChooseSnippets = {},
                    )
                }
            }
        }
    }
}
