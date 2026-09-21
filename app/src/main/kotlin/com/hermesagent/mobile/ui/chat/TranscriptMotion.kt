package com.hermesagent.mobile.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermesagent.mobile.ui.theme.HermesTheme

/** Motion has no semantic state: assistive technology keeps the stable row label. */
@Composable
internal fun transcriptMotionPhase(active: Boolean, durationMillis: Int): Float {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    if (!active || !lifecycle.isAtLeast(Lifecycle.State.RESUMED) || scale <= 0f) return 0f
    val transition = rememberInfiniteTransition(label = "transcript activity")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "activity phase",
    )
    return phase
}

private val breatheFrames = listOf("⠀", "⠂", "⠌", "⡑", "⢕", "⢝", "⣫", "⣟", "⣿", "⣟", "⣫", "⢝", "⢕", "⡑", "⠌", "⠂", "⠀")

@Composable
internal fun RunningToolGlyph(color: Color) {
    val phase = transcriptMotionPhase(active = true, durationMillis = 1700)
    // Keep a visible resting mark when reduced motion or backgrounding pauses it.
    val frame = if (phase == 0f) "⣿" else breatheFrames[(phase * breatheFrames.size).toInt().coerceIn(breatheFrames.indices)]
    Text(frame, color = color, fontSize = 13.sp, modifier = Modifier.clearAndSetSemantics {})
}

@Composable
internal fun ActivityLabel(
    text: String,
    active: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val phase = transcriptMotionPhase(active, 1800)
    val ink = if (phase == 0f) style.copy(color = color) else style.copy(
        brush = Brush.linearGradient(
            colors = listOf(color, HermesTheme.tokens.textPrimary, color),
            start = Offset(-160f + phase * 480f, 0f),
            end = Offset(phase * 480f, 0f),
        ),
    )
    Text(text, modifier = modifier, style = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

internal fun progressPulseAlpha(phase: Float): Float =
    if (phase < 0.08f) 0.55f + 0.45f * kotlin.math.abs(phase / 0.04f - 1f) else 1f
