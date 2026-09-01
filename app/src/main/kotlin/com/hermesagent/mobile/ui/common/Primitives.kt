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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * The shared primitives. DESIGN.md's rule is "one primitive per concern"
 * (`apps/desktop/DESIGN.md:32-34` @ `3ca096de`), and these are the concerns
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

/**
 * Desktop's shared card emphasis, `selectableCardClass({ active, prominent })`
 * (`apps/desktop/src/lib/selectable-card.ts:22-31` @ `3ca096de`).
 *
 * Three tiers there, two of them used here: `active` is the strongest —
 * `border-primary bg-primary/[0.06] ring-2 ring-primary/20`; `prominent` is
 * the resting card — `border-(--ui-stroke-tertiary) bg-(--ui-bg-quinary)`. The
 * muted tier is a hover treatment for pickers, and touch has no hover.
 *
 * One colour role is **not** matched, and it is a divergence rather than a
 * translation. Desktop's `--ui-bg-quinary` is a translucent accent-tinted wash
 * (`styles.css:288-292` @ `3ca096de`: an accent mix over 3% of the base); the
 * nearest thing this app's token layer has is `widgetSurface`, which is opaque
 * and derived from the card fill. The port skill's rule is to add the missing
 * token with its Desktop provenance rather than reach past the layer — but the
 * token layer is pinned at a *different* SHA and gated by `ThemeParityTest`,
 * so adding one is a theme-sync change, not this slice's. Recorded in
 * `docs/parity/gateway-connections.md` as drift with an owner instead of
 * quietly painting the wrong thing under a comment that claims otherwise.
 *
 * Desktop's ring is a box-shadow, so it costs no layout. Here the 2dp is
 * always reserved and only *painted* when active, which keeps a card exactly
 * the same size selected and unselected — Desktop's cards do not move either.
 */
@Composable
fun selectableCardModifier(active: Boolean, shape: RoundedCornerShape): Modifier {
    val tokens = HermesTheme.tokens
    return Modifier
        .border(2.dp, if (active) tokens.accent.copy(alpha = 0.20f) else Color.Transparent, shape)
        .padding(2.dp)
        .border(1.dp, if (active) tokens.accent else tokens.strokeTertiary, shape)
        .background(if (active) tokens.accent.copy(alpha = 0.06f) else tokens.widgetSurface, shape)
}

/** The card radius Desktop writes as `rounded-lg`, and this app's container radius. */
private val ModeCardShape = RoundedCornerShape(10.dp)

/**
 * Desktop's `ModeCard` (`apps/desktop/src/app/settings/gateway-settings.tsx:88-135`
 * @ `3ca096de`): a selectable card carrying an icon, a medium-weight title, an
 * optional hint, a check when it is the active one, and a description.
 *
 * Two mechanics change and nothing else does.
 *
 * Desktop's hint is a hover `Tip` on a `HelpCircle`. Touch has no hover, so the
 * glyph is a button that reveals the *same sentence* under the description.
 * Revealing beats hiding: the text is on screen rather than one gesture away
 * from being undiscoverable. It sits inside a 48dp touch area it does not paint
 * (`minimumInteractiveComponentSize`), so the hit target clears the platform
 * floor without the 14sp glyph growing into a Material toolbar icon, and it
 * consumes its own tap the way Desktop's `stopPropagation` does.
 *
 * `disabled:opacity-50` becomes a disabled card whose text drops a tier, since
 * a flat 50% alpha over a themed surface is not a token this app has.
 */
@Composable
fun ModeCard(
    title: String,
    description: String,
    icon: HermesIcon,
    active: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hint: String? = null,
    /**
     * A status this card carries, spoken last. `selectable` below merges this
     * card's descendants, so a marker that spoke for itself would replace the
     * card's name rather than follow it; the card says the whole phrase or
     * nothing, and the marker beside it stays visual.
     */
    status: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val tokens = HermesTheme.tokens
    var hintShown by rememberSaveable(title) { mutableStateOf(false) }
    val bodyColor = if (enabled) tokens.textTertiary else tokens.textQuaternary

    Column(
        modifier
            .fillMaxWidth()
            .then(selectableCardModifier(active, ModeCardShape))
            // Before `selectable`: an unclipped ripple paints square corners
            // over a rounded card.
            .clip(ModeCardShape)
            .selectable(
                selected = active,
                enabled = enabled,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .then(
                if (status == null) {
                    Modifier
                } else {
                    Modifier.semantics { contentDescription = "$title. $description. $status" }
                },
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // Every card reserves the same header height, hint or not.
                // The revealer below needs the platform's 48dp, and a card
                // that grew only where it had a hint would leave the four
                // sitting at two different heights in the one-column layout,
                // where no row exists to equalise them.
                .heightIn(min = HermesTheme.spacing.touchTarget),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HermesIconGlyph(
                // Desktop writes this `text-muted-foreground` and the
                // description `--ui-text-tertiary`, which sound like two roles
                // and render as one: the captured contract has both at the
                // same 54% ink (`build/visual-parity/gateway-connection-mode`).
                // Tertiary, then — and it is `HermesIconGlyph`'s own default.
                icon = icon,
                color = if (enabled) tokens.textTertiary else tokens.textQuaternary,
            )
            // Desktop groups icon + title + hint and pushes the check away with
            // `ml-auto`. This inner row is that group: it takes the free width,
            // so the trailing slot lands flush right, and the title shrinks
            // inside it the way `min-w-0` lets it.
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = HermesTheme.type.body.copy(fontWeight = FontWeight.Medium),
                    color = if (enabled) tokens.textPrimary else tokens.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (hint != null) {
                    Box(
                        Modifier
                            .minimumInteractiveComponentSize()
                            .clickable(role = Role.Button) { hintShown = !hintShown }
                            .semantics { contentDescription = "About $title" },
                        contentAlignment = Alignment.Center,
                    ) {
                        HermesIconGlyph(HermesIcon.Question, color = tokens.textTertiary)
                    }
                }
            }
            when {
                trailing != null -> trailing()
                active -> HermesIconGlyph(HermesIcon.Check, color = tokens.accent)
            }
        }
        Text(
            text = description,
            style = HermesTheme.type.caption,
            color = bodyColor,
        )
        if (hint != null && hintShown) {
            Text(
                text = hint,
                style = HermesTheme.type.caption,
                color = bodyColor,
            )
        }
    }
}

/**
 * Desktop's mode grid steps at `sm` (40rem / 640px) and `min-[72rem]`
 * (1152px), and both are **viewport** media queries — not the container query
 * its registry kind chooser uses (`gateway-settings.tsx:1048` versus
 * `connections-registry.tsx:648` @ `3ca096de`). So this reads the window too.
 *
 * `640px` lands on 600dp because that is Android's own compact/medium boundary
 * — the platform's "this is no longer a phone" line, and the nearest standard
 * one to Desktop's. `1152px` lands on 720dp because that is already this app's
 * wide breakpoint (`ui/chat/ChatScreen.kt:95-97`), and Desktop's 1152px window
 * is also carrying a sidebar and a chat pane that this settings column is not;
 * inventing a third breakpoint to be arithmetically closer would buy nothing.
 *
 * Separate from the composable so the mapping is a fact a JVM test can assert
 * without a device.
 */
internal fun modeCardColumnsFor(widthDp: Int): Int = when {
    widthDp >= MODE_CARD_WIDE_DP -> 4
    widthDp >= MODE_CARD_TWO_COLUMN_DP -> 2
    else -> 1
}

/** Desktop's `sm:` step, on Android's compact/medium boundary. */
internal const val MODE_CARD_TWO_COLUMN_DP = 600

/** Desktop's `min-[72rem]:` step, on this app's existing wide breakpoint. */
internal const val MODE_CARD_WIDE_DP = 720

/**
 * `grid auto-rows-fr grid-cols-1 gap-2 sm:grid-cols-2 min-[72rem]:grid-cols-4`
 * (`gateway-settings.tsx:1048` @ `3ca096de`).
 *
 * `auto-rows-fr` is what makes every card in a row the same height regardless
 * of how long its description is; `IntrinsicSize.Min` plus `fillMaxHeight` is
 * the same statement here. Laid out by hand rather than with a lazy grid
 * because this lives inside a page that already scrolls, and there are four
 * items — a nested scroller would be the bug, not the feature.
 */
@Composable
fun <T> ModeCardGrid(
    items: List<T>,
    modifier: Modifier = Modifier,
    columns: Int = modeCardColumnsFor(LocalConfiguration.current.screenWidthDp),
    itemContent: @Composable (T) -> Unit,
) {
    Column(
        modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.chunked(columns).forEach { row ->
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { item ->
                    Box(Modifier.weight(1f).fillMaxHeight()) { itemContent(item) }
                }
                // A short last row keeps the column rhythm instead of
                // stretching two cards across four columns' worth of width.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Desktop's `Button` in the two variants its registry kind chooser uses:
 * `variant="default"` for the chosen kind and `variant="outline"` for the rest
 * (`connections-registry.tsx:653-664` @ `3ca096de`).
 *
 * Not a [ModeCard]: Desktop deliberately renders the *registry* chooser as a
 * plain button row, and the mode cards only on the Gateways page above it.
 * Keeping the two treatments apart is the parity contract.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** A status this choice carries, spoken last — see [ModeCard]'s own. */
    status: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val tokens = HermesTheme.tokens
    val shape = RoundedCornerShape(8.dp)
    // Flowing rather than truncating, so a cell narrower than its contents
    // keeps Desktop's column count *and* the whole label. It earned its keep
    // when the marker beside an unbuilt choice was two words wide; the short
    // `WIP` marker now fits beside every label this app passes here, measured
    // at 411dp, so this is the floor under a long label or a large font scale
    // rather than the ordinary case.
    FlowRow(
        modifier
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .background(
                // Desktop's `variant="default"` is a solid `bg-primary`, not a
                // wash (`components/ui/button.tsx` via
                // `connections-registry.tsx:661` @ `3ca096de`). This app's
                // segmented control uses a 16% accent tint for *its* selected
                // segment, but that is a different control; matching Desktop
                // here costs nothing and removes a divergence.
                if (selected && enabled) tokens.accent else Color.Transparent,
                shape,
            )
            .border(1.dp, if (selected && enabled) tokens.accent else tokens.strokeTertiary, shape)
            .clip(shape)
            .selectable(selected = selected, enabled = enabled, onClick = onClick, role = Role.RadioButton)
            .then(
                if (status == null) {
                    Modifier
                } else {
                    Modifier.semantics { contentDescription = "$label. $status" }
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = HermesTheme.type.caption,
            color = when {
                !enabled -> tokens.textQuaternary
                // On a solid accent fill the label is Desktop's
                // `text-primary-foreground`, not the ordinary ink.
                selected -> tokens.accentForeground
                else -> tokens.textSecondary
            },
        )
        trailing?.invoke()
    }
}
