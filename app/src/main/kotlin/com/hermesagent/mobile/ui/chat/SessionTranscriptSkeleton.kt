package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.theme.HermesTheme

/** Static placeholders keep loading legible without an infinite animation. */
@Composable
internal fun SessionTranscriptSkeleton(modifier: Modifier = Modifier) {
    val tokens = HermesTheme.tokens
    Column(
        modifier.fillMaxSize()
            .testTag("Session transcript skeleton")
            .semantics {
                contentDescription = "Loading conversation"
                liveRegion = LiveRegionMode.Polite
            }
            .padding(HermesTheme.spacing.pageInset),
        verticalArrangement = Arrangement.spacedBy(HermesTheme.spacing.blockGap),
    ) {
        listOf(.62f, .94f, .86f, .72f).forEachIndexed { index, fraction ->
            Box(
                Modifier.fillMaxWidth(fraction).height(if (index == 0) 32.dp else 14.dp)
                    .background(tokens.strokeQuaternary, RoundedCornerShape(4.dp)),
            )
        }
    }
}
