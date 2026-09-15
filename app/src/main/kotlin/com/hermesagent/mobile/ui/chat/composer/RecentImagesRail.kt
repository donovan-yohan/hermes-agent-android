package com.hermesagent.mobile.ui.chat.composer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.attachments.RecentImage
import com.hermesagent.mobile.data.attachments.RecentImageAccess
import com.hermesagent.mobile.data.ssh.redact
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Everything the sheet's Recent images section renders, already fenced to one
 * composer scope (connection generation plus durable session).
 */
data class RecentImagesUiState(
    val access: RecentImageAccess = RecentImageAccess.Unknown,
    val loading: Boolean = false,
    val images: List<RecentImage> = emptyList(),
    val thumbnails: Map<Long, ImageBitmap> = emptyMap(),
    /** Image ids already added to this message, so a second tap is not a second copy. */
    val addedIds: Set<Long> = emptySet(),
    /** True once this message holds as many attachments as it can carry. */
    val full: Boolean = false,
) {
    val showsRail: Boolean get() = !loading && images.isNotEmpty() && access.readsLibrary
}

/** The Recent images section of the composer add sheet. */
@Composable
internal fun RecentImagesSection(
    state: RecentImagesUiState,
    onAddImage: (Long) -> Unit,
    onRequestAccess: () -> Unit,
    onChoosePhotos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Recent images",
            style = HermesTheme.type.bodyStrong,
            color = tokens.textPrimary,
            modifier = Modifier.testTag("Recent images label"),
        )
        when {
            state.loading -> RecentImagesLoading()
            state.showsRail -> RecentImagesRail(state, onAddImage)
            state.access.readsLibrary -> {
                Text(
                    "No recent images on this device.",
                    style = HermesTheme.type.scaffoldMeta,
                    color = tokens.scaffoldMeta,
                    modifier = Modifier.testTag("Recent images empty"),
                )
            }
        }
        if (state.access == RecentImageAccess.Unknown || state.access == RecentImageAccess.Denied) {
            AddRow(
                label = "Turn on photo access",
                description = "Show recent images in this sheet",
                icon = HermesIcon.Eye,
                onClick = onRequestAccess,
                testTag = "Recent images access action",
            )
        }
        if (state.access == RecentImageAccess.Partial) {
            Text(
                "Showing the photos you allowed.",
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.scaffoldMeta,
            )
        }
        if (state.full) {
            Text(
                "This message is full. Remove an attachment to add another.",
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.textSecondary,
            )
        }
        AddRow(
            label = "Choose photos",
            description = "Open Android's photo picker",
            icon = HermesIcon.FileMedia,
            onClick = onChoosePhotos,
            testTag = "Choose photos action",
        )
    }
}

@Composable
private fun RecentImagesLoading() {
    val tokens = HermesTheme.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Loading recent images" }
            .testTag("Recent images loading"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(4) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(tokens.widgetSurface, RoundedCornerShape(8.dp)),
            )
        }
    }
}

@Composable
private fun RecentImagesRail(state: RecentImagesUiState, onAddImage: (Long) -> Unit) {
    val tokens = HermesTheme.tokens
    LazyRow(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxWidth().testTag("Recent images rail"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(state.images, key = { it.id }) { image ->
            val added = image.id in state.addedIds
            val enabled = !added && !state.full
            val safeName = redact(image.displayName)
            Box(
                Modifier
                    .sizeIn(
                        minWidth = HermesTheme.spacing.touchTarget,
                        minHeight = HermesTheme.spacing.touchTarget,
                    )
                    .clickable(enabled = enabled, role = Role.Button) { onAddImage(image.id) }
                    .semantics {
                        // The state is the name, not a colour: a screen reader hears
                        // "added" and a second tap has nothing left to do.
                        contentDescription = if (added) {
                            "$safeName added to the message"
                        } else {
                            "Add $safeName to the message"
                        }
                        if (!enabled) disabled()
                    }
                    .testTag("Recent image ${image.id}"),
                contentAlignment = Alignment.Center,
            ) {
                val thumbnail = state.thumbnails[image.id]
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Box(
                        Modifier.size(56.dp).background(tokens.widgetSurface, RoundedCornerShape(8.dp)),
                    )
                }
                if (added) {
                    Text(
                        "Added",
                        style = HermesTheme.type.scaffoldMeta,
                        color = tokens.accent,
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
        }
    }
}
