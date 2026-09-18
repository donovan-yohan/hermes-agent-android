package com.hermesagent.mobile.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermesagent.mobile.data.profiles.AvatarRosterCoordinator
import com.hermesagent.mobile.data.profiles.ProfileAvatar
import com.hermesagent.mobile.data.profiles.ProfileAvatarRef

/** The app-owned avatar authority. A missing provider always renders fallback and does no I/O. */
val LocalProfileAvatarRoster = androidx.compose.runtime.staticCompositionLocalOf<AvatarRosterCoordinator?> { null }

/**
 * Draws a read-only avatar subscription over a caller-owned fallback.
 *
 * Admission happens only from the resumed lifecycle effect. The image is read through
 * [AvatarRosterCoordinator.Binding.current] during composition and again from the draw-phase
 * guard, so an owner or receipt change suppresses an old bitmap even when collectors have not
 * yet delivered a redraw. The draw guard only reads; it never acquires, refreshes, or mutates.
 */
@Composable
internal fun ProfileAvatarImage(
    ref: ProfileAvatarRef?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(3.dp),
    fallback: @Composable () -> Unit,
) {
    val roster = LocalProfileAvatarRoster.current
    var binding by remember(roster) { mutableStateOf<AvatarRosterCoordinator.Binding?>(null) }
    var boundRef by remember(roster) { mutableStateOf<ProfileAvatarRef?>(null) }
    var observed by remember(roster) { mutableStateOf<ProfileAvatar>(ProfileAvatar.Unavailable) }

    LifecycleResumeEffect(roster, ref) {
        val next = roster?.let {
            it.syncOwner()
            it.bind(ref)
        }
        binding = next
        boundRef = ref
        onPauseOrDispose {
            next?.close()
            if (binding === next) binding = null
            if (boundRef === ref) boundRef = null
        }
    }

    LaunchedEffect(binding) {
        val current = binding ?: return@LaunchedEffect
        current.updates.collect { observed = it }
    }

    // The coordinator revision is an explicit redraw signal for owner/receipt changes that
    // produce no avatar StateFlow event. Reading current() remains the final authority.
    val revision by roster?.revisions?.collectAsStateWithLifecycle(0L)
        ?: remember { mutableStateOf(0L) }
    val safeAvatar = if (revision >= 0L && boundRef === ref && observed is ProfileAvatar.Ready) {
        binding?.current() as? ProfileAvatar.Ready
    } else {
        null
    }

    Box(modifier) {
        fallback()
        if (safeAvatar != null) {
            Image(
                bitmap = safeAvatar.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .drawWithContent {
                        // This is deliberately read-only. Binding.current() rechecks the
                        // captured owner/receipt at draw time, closing the frame-sized gap
                        // between composition and rasterization without starting I/O.
                        if (binding?.current() is ProfileAvatar.Ready) drawContent()
                    },
            )
        }
    }
}
