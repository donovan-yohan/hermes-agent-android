package com.hermesagent.mobile.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * The shared primitives. DESIGN.md's rule is "one primitive per concern"
 * (`apps/desktop/DESIGN.md:32-34` @ `f82f2dba`), and these are the concerns
 * this slice actually has. Anything that needs a padding or a colour override
 * at the call site belongs here instead, as a variant.
 */

/** The single hairline. `--ui-stroke-tertiary` is the default in-panel divider. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = HermesTheme.tokens.strokeTertiary) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** Quiet uppercase field label. Groups a list; never a chrome heading. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = HermesTheme.type.sectionLabel,
        color = HermesTheme.tokens.textTertiary,
        modifier = modifier,
    )
}

/** One labelled, single-line settings field with the shared touch target and input policy. */
@Composable
fun LabelledField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    val tokens = HermesTheme.tokens
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = HermesTheme.type.body.copy(color = tokens.textPrimary),
            cursorBrush = SolidColor(tokens.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else keyboardType,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .semantics { contentDescription = label },
            decorationBox = { editor ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, tokens.strokeSecondary, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = HermesTheme.type.body, color = tokens.textQuaternary)
                    }
                    editor()
                }
            },
        )
    }
}

/**
 * A transcript scaffold line: what the agent *did*, as opposed to what it
 * said. One colour and one size for all of them, per Desktop's `ScaffoldRow`
 * (`apps/desktop/src/components/chat/scaffold-row.tsx:5-23`).
 */
@Composable
fun ScaffoldRow(
    label: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(
            text = label,
            style = HermesTheme.type.scaffold,
            color = HermesTheme.tokens.scaffoldText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (meta != null) {
            Text(
                text = meta,
                style = HermesTheme.type.scaffoldMeta,
                color = HermesTheme.tokens.scaffoldMeta,
            )
        }
    }
}

/**
 * Session status dot. Three colours and one fill/hollow axis, none of it
 * moving — Desktop's reasoning verbatim
 * (`apps/desktop/src/app/chat/session-status-dot.tsx:22-27`): motion on a 6px
 * circle can only say "something is happening"; colour and fill say *what*.
 */
@Composable
fun StatusDot(
    color: Color,
    filled: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 7.dp,
) {
    val description = contentDescription
    val base = modifier
        .size(size)
        .semantics { description?.let { this.contentDescription = it } }
    Box(
        if (filled) {
            base.background(color, CircleShape)
        } else {
            base.border(1.dp, color, CircleShape)
        },
    )
}

/**
 * Borderless search: underline on focus, no boxed tile. The only search input
 * (`apps/desktop/DESIGN.md:158-160`).
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = HermesTheme.spacing.touchTarget)
                    .testTag("Search field shell"),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = HermesTheme.type.caption.copy(color = tokens.textPrimary),
                    cursorBrush = SolidColor(tokens.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier
                        .fillMaxWidth()
                        // The editable node owns the complete touch target.
                        .heightIn(min = HermesTheme.spacing.touchTarget)
                        .semantics { contentDescription = placeholder },
                    decorationBox = { innerTextField ->
                        CenteredTextFieldContent(
                            isEmpty = value.isEmpty(),
                            contentTag = "Search text content",
                            placeholder = {
                                Text(
                                    placeholder,
                                    style = HermesTheme.type.caption,
                                    color = tokens.textTertiary,
                                )
                            },
                            innerTextField = innerTextField,
                        )
                    },
                )
            }
            if (value.isNotEmpty()) {
                QuietIconButton(
                    icon = Icons.Filled.Clear,
                    contentDescription = "Clear search",
                    onClick = { onValueChange("") },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Hairline(color = if (value.isEmpty()) tokens.strokeQuaternary else tokens.accent)
    }
}

/** Centers natural-height text inside a full-size editor without changing its wrap width. */
@Composable
internal fun CenteredTextFieldContent(
    isEmpty: Boolean,
    contentTag: String,
    placeholder: @Composable () -> Unit,
    innerTextField: @Composable () -> Unit,
    horizontalPadding: Dp = 0.dp,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().testTag(contentTag)) {
            if (isEmpty) placeholder()
            innerTextField()
        }
    }
}

/**
 * Quiet chrome button. Boxless, 48dp touch target with a smaller visual glyph
 * — the Android floor, which Desktop does not have to care about.
 */
@Composable
fun QuietIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = HermesTheme.tokens.textSecondary,
) {
    Box(
        modifier = modifier
            .size(HermesTheme.spacing.touchTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tint else HermesTheme.tokens.textQuaternary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The choice control for a small mutually-exclusive set — colour mode, auth
 * method (`apps/desktop/DESIGN.md:161-163`). Replaces radio piles and pill
 * rows; there is no second segmented control in this app.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    describe: (T) -> String = label,
) {
    val tokens = HermesTheme.tokens
    Row(
        modifier
            .fillMaxWidth()
            .selectableGroup()
            .border(1.dp, tokens.strokeTertiary, RoundedCornerShape(10.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (option in options) {
            val active = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = HermesTheme.spacing.touchTarget)
                    .background(
                        if (active) tokens.accent.copy(alpha = 0.16f) else Color.Transparent,
                        RoundedCornerShape(8.dp),
                    )
                    .selectable(
                        selected = active,
                        onClick = { onSelect(option) },
                        role = Role.RadioButton,
                    )
                    .semantics {
                        contentDescription = describe(option)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = HermesTheme.type.caption,
                    color = if (active) tokens.textPrimary else tokens.textTertiary,
                )
            }
        }
    }
}

/**
 * Primary action. Flat fill, small radius, no shadow.
 *
 * Both floors are the Android touch target, not a visual choice: caption type
 * plus the padding this design wants lands around 42dp tall, and a short label
 * like "Send" lands around 36dp wide — both under the platform minimum, and a
 * control that clears one floor and not the other is not a touch target. Every
 * caller so far was wide enough only by accident, through `fillMaxWidth()` or a
 * long label. Padding stays as the *visual* rhythm; the floors only ever make
 * the box bigger.
 */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** The one filled action's colour. Destructive confirmations own the exception. */
    container: Color = HermesTheme.tokens.accent,
) {
    val tokens = HermesTheme.tokens
    Box(
        modifier = modifier
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .widthIn(min = HermesTheme.spacing.touchTarget)
            .background(
                if (enabled) container else container.copy(alpha = 0.3f),
                RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = HermesTheme.type.caption,
            color = if (enabled) tokens.accentForeground else tokens.accentForeground.copy(alpha = 0.6f),
        )
    }
}

/**
 * Quiet inline affordance — "Change", "Forget key".
 *
 * Reads as a text link and hits like a button: no fill, no border, no chip,
 * but the tappable area around the label meets the same floor as every other
 * control here. These sit next to destructive and security choices, which is
 * the worst place to make someone aim.
 */
@Composable
fun TextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = HermesTheme.tokens.accent,
) {
    Box(
        modifier = modifier
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = HermesTheme.type.caption,
            color = if (enabled) color else HermesTheme.tokens.textQuaternary,
        )
    }
}

/** Plain-body empty state. Centered, no icon pile, no card. */
@Composable
fun EmptyState(title: String, description: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = HermesTheme.type.bodyStrong, color = HermesTheme.tokens.textSecondary)
        Text(
            description,
            style = HermesTheme.type.caption,
            color = HermesTheme.tokens.textTertiary,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/**
 * One look for every error the user can see. Destructive ink, hairline, no
 * background chip (`apps/desktop/DESIGN.md:184-187`).
 */
@Composable
fun ErrorState(title: String, description: String, modifier: Modifier = Modifier) {
    val tokens = HermesTheme.tokens
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, tokens.destructive.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = HermesTheme.type.caption, color = tokens.destructive)
        Text(description, style = HermesTheme.type.caption, color = tokens.textSecondary)
    }
}

/**
 * Raw output: no fill, hairline border, tight padding, small mono
 * (`apps/desktop/DESIGN.md:188-189`).
 */
@Composable
fun LogView(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = HermesTheme.type.code,
        color = HermesTheme.tokens.textSecondary,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, HermesTheme.tokens.strokeTertiary, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/**
 * A reduced-motion-safe "working" indicator: three dots whose *opacity* is
 * driven by an infinite transition, which the system animator scales to zero
 * duration when animations are disabled. Nothing about state depends on it.
 *
 * @param status what a screen reader should hear when these dots appear. The
 *   dots are decoration — three moving circles have no reading — so by default
 *   they are cleared out of the semantics tree entirely. Where they are the
 *   *only* signal that the agent is working, pass a status: the row becomes a
 *   polite live region announced once, on appearance. It must be a constant
 *   string. Deriving it from the streamed text would turn a live region into a
 *   per-token announcement, which is worse than silence.
 */
@Composable
fun WorkingDots(
    modifier: Modifier = Modifier,
    color: Color = HermesTheme.tokens.accent,
    status: String? = null,
) {
    val transition = rememberInfiniteTransition(label = "working")
    Row(
        modifier = modifier.clearAndSetSemantics {
            if (status != null) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = status
            }
        },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = index * 140),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(Modifier.size(4.dp).background(color.copy(alpha = alpha), CircleShape))
        }
    }
}

@Composable
fun VerticalHairline(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).background(HermesTheme.tokens.strokeTertiary))
}
