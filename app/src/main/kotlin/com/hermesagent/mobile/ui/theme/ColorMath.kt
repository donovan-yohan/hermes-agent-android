package com.hermesagent.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Colour maths ported from Hermes Desktop so Android and Desktop resolve the
 * same palette from the same seed instead of two lookalike approximations.
 *
 * Provenance (upstream `NousResearch/hermes-agent` @
 * `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`):
 *   - [mix], [relativeLuminance], [readableOn] — `apps/desktop/src/themes/color.ts:29-65`
 *   - [mixPremultiplied] — CSS `color-mix(in srgb, …)` semantics, which is what
 *     `apps/desktop/src/styles.css` uses for every derived token.
 *
 * Two different mixes exist on purpose and are not interchangeable:
 *   - [mix] is Desktop's own opaque lerp. It ignores alpha and is used by the
 *     synthesised light palette.
 *   - [mixPremultiplied] is what a browser does for `color-mix(in srgb, …)`:
 *     alpha is mixed too, and the RGB channels are mixed *premultiplied*. This
 *     is why `color-mix(in srgb, X 22%, transparent)` is "X at 22% alpha".
 */

/** Desktop `mix(a, b, amount)` — opaque lerp of two colours, alpha untouched. */
fun mix(a: Color, b: Color, amount: Float): Color = Color(
    red = a.red + (b.red - a.red) * amount,
    green = a.green + (b.green - a.green) * amount,
    blue = a.blue + (b.blue - a.blue) * amount,
    alpha = a.alpha,
)

/**
 * CSS `color-mix(in srgb, [first] [firstPercent]%, [second])`.
 *
 * Percentages are normalised the way CSS does, and the RGB mix is
 * premultiplied so mixing against a transparent colour only lowers alpha.
 */
fun mixPremultiplied(first: Color, firstPercent: Float, second: Color): Color {
    val p1 = (firstPercent / 100f).coerceIn(0f, 1f)
    val p2 = 1f - p1
    val alpha = first.alpha * p1 + second.alpha * p2
    if (alpha <= 0f) return Color.Transparent

    fun channel(a: Float, b: Float): Float =
        (a * first.alpha * p1 + b * second.alpha * p2) / alpha

    return Color(
        red = channel(first.red, second.red).coerceIn(0f, 1f),
        green = channel(first.green, second.green).coerceIn(0f, 1f),
        blue = channel(first.blue, second.blue).coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

/** WCAG relative luminance (gamma-corrected), 0..1. */
fun relativeLuminance(color: Color): Float {
    fun linear(channel: Float): Float =
        if (channel <= 0.03928f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

    return 0.2126f * linear(color.red) + 0.7152f * linear(color.green) + 0.0722f * linear(color.blue)
}

/** WCAG contrast ratio (1..21) between two colours. */
fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val hi = max(la, lb)
    val lo = min(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}

/** Desktop `readableOn` — a legible foreground for a given background. */
fun readableOn(color: Color): Color =
    if (relativeLuminance(color) > 0.58f) Color(0xFF161616) else Color(0xFFFFFFFF)

/** Same colour at a different alpha. Used for the text/hairline ladders. */
fun Color.withAlpha(alpha: Float): Color = copy(alpha = alpha.coerceIn(0f, 1f))

/** `#rrggbb` for diagnostics and test failure messages. Alpha is dropped. */
fun Color.toHex(): String {
    fun channel(value: Float) = (value * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    return "#${channel(red)}${channel(green)}${channel(blue)}"
}
