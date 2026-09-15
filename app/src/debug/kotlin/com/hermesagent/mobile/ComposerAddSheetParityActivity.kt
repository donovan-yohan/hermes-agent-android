package com.hermesagent.mobile

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.attachments.RecentImage
import com.hermesagent.mobile.data.attachments.RecentImageAccess
import com.hermesagent.mobile.ui.chat.composer.ComposerAddSheetContent
import com.hermesagent.mobile.ui.chat.composer.RecentImagesUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode

/**
 * Debug-only, synthetic add-sheet fixture. The images are drawn here rather
 * than read from any device library or grant, and the intent extras are capture
 * metadata, never user data.
 */
class ComposerAddSheetParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = ComposerAddSheetFixtureState.parse(intent.getStringExtra(EXTRA_STATE))
        val theme = if (intent.getStringExtra(EXTRA_THEME) == "light") HermesThemeMode.Light else HermesThemeMode.Dark
        setContent { HermesTheme(AppearanceSelection("mono", theme)) { ComposerAddSheetParityFixture(state) } }
    }

    companion object {
        const val EXTRA_STATE = "visual_parity_state"
        const val EXTRA_THEME = "visual_parity_theme"
    }
}

/** Every add-sheet state in the capture catalog. Unknown values fail before rendering. */
internal enum class ComposerAddSheetFixtureState(val wireValue: String) {
    Populated("recent-images-populated"),
    Added("recent-images-added"),
    Permission("recent-images-permission"),
    ;

    companion object {
        fun parse(value: String?): ComposerAddSheetFixtureState = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("unsupported composer add sheet parity state: $value")
    }
}

@Composable
internal fun ComposerAddSheetParityFixture(state: ComposerAddSheetFixtureState) {
    val images = (1..ComposerAddSheetFixture.IMAGE_COUNT).map { index ->
        RecentImage(id = index.toLong(), displayName = "Synthetic shot $index", mimeType = "image/png")
    }
    val thumbnails = images.associate { image -> image.id to ComposerAddSheetFixture.thumbnail(image.id) }
    val rail = when (state) {
        ComposerAddSheetFixtureState.Permission -> RecentImagesUiState(access = RecentImageAccess.Denied)
        ComposerAddSheetFixtureState.Populated ->
            RecentImagesUiState(access = RecentImageAccess.Granted, images = images, thumbnails = thumbnails)
        ComposerAddSheetFixtureState.Added -> RecentImagesUiState(
            access = RecentImageAccess.Granted,
            images = images,
            thumbnails = thumbnails,
            addedIds = setOf(2L),
        )
    }
    val tokens = HermesTheme.tokens
    Box(Modifier.fillMaxSize().background(tokens.chatSurface).systemBarsPadding()) {
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(tokens.cardSurface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
        ) {
            ComposerAddSheetContent(
                recentImages = rail,
                onAddRecentImage = {},
                onRequestRecentImageAccess = {},
                onPickPhotos = {},
                onChooseFiles = {},
                onChooseUrl = {},
                onChooseSnippets = {},
            )
        }
    }
}

/** Flat synthetic tiles, so a capture reads the rail's layout rather than stock photography. */
private object ComposerAddSheetFixture {
    const val IMAGE_COUNT = 6
    private const val TILE_PX = 56

    fun thumbnail(id: Long): ImageBitmap {
        val bitmap = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
        val channel = (id.toInt() * 0x24).coerceAtMost(0xE0)
        val shade = 0xFF000000.toInt() or (channel shl 16) or ((0xE0 - channel) shl 8) or 0x60
        for (x in 0 until TILE_PX) {
            for (y in 0 until TILE_PX) {
                bitmap.setPixel(x, y, shade)
            }
        }
        return bitmap.asImageBitmap()
    }
}
