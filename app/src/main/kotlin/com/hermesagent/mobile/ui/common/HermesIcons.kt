package com.hermesagent.mobile.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesagent.mobile.R
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * The Desktop glyph language. Values are Codicons 0.0.45 code points, matching
 * the pinned Desktop dependency rather than substituting Material symbols.
 */
enum class HermesIcon(val glyph: String) {
    Add("\uEA60"),
    Edit("\uEA73"),
    File("\uEA7B"),
    Search("\uEA6D"),
    Clock("\uEA82"),
    Terminal("\uEA85"),
    Error("\uEA87"),
    SymbolMethod("\uEA8C"),
    Check("\uEAB2"),
    Checklist("\uEAB3"),
    ArrowDown("\uEA9A"),
    ArrowUp("\uEAA1"),
    ChevronDown("\uEAB4"),
    ChevronRight("\uEAB6"),
    Diff("\uEAE1"),
    RootFolder("\uEB46"),
    ListUnordered("\uEB17"),
    ListFilter("\uEB83"),
    Thinking("\uEC59"),
    Link("\uEB15"),
    Mic("\uEC12"),
    StopCircle("\uEC1F"),
    GitBranch("\uEA68"),
    CircleSlash("\uEABD"),
    KebabVertical("\uEB10"),
    PassFilled("\uEBB3"),
    Copy("\uEBCC"),
    Close("\uEA76"),
    Home("\uEB06"),
    Layers("\uEBD2"),
    Ellipsis("\uEA7C"),
}

private val CodiconFont = FontFamily(Font(R.font.codicon))

/** A decorative Codicon. The owning control supplies its spoken label. */
@Composable
fun HermesIconGlyph(
    icon: HermesIcon,
    modifier: Modifier = Modifier,
    color: Color = HermesTheme.tokens.textTertiary,
    size: TextUnit = 14.sp,
) {
    Text(
        text = icon.glyph,
        style = TextStyle(fontFamily = CodiconFont, fontSize = size, lineHeight = size),
        color = color,
        modifier = modifier.clearAndSetSemantics {},
    )
}

/**
 * Desktop-sized Codicon inside Android's 48dp touch floor. Growing the hit box
 * must not make a quiet 12-14px sidebar glyph look like a Material toolbar icon.
 */
@Composable
fun HermesIconButton(
    icon: HermesIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    tint: Color = HermesTheme.tokens.textTertiary,
) {
    val tokens = HermesTheme.tokens
    Box(
        modifier = modifier
            .size(HermesTheme.spacing.touchTarget)
            .background(
                if (active) tokens.widgetSurface else Color.Transparent,
                RoundedCornerShape(4.dp),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        HermesIconGlyph(
            icon = icon,
            color = if (enabled) tint else tokens.textQuaternary,
        )
    }
}

/** Desktop's 8px two-tone checker mark from `.dither`. */
@Composable
fun DitherMark(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(8.dp).clearAndSetSemantics {}) {
        val cell = size.width / 4f
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                if ((row + column) % 2 == 0) {
                    drawRect(
                        color = color,
                        topLeft = androidx.compose.ui.geometry.Offset(column * cell, row * cell),
                        size = androidx.compose.ui.geometry.Size(cell, cell),
                    )
                }
            }
        }
    }
}
