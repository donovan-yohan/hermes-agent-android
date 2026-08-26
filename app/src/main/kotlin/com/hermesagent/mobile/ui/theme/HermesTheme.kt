package com.hermesagent.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/** What the user picked in Appearance. Mirrors Desktop's `ThemeMode`. */
enum class HermesThemeMode { Light, Dark, System }

/** Persisted appearance choice: which preset, and which mode. */
data class AppearanceSelection(
    val themeName: String = BuiltinThemes.DEFAULT_NAME,
    val mode: HermesThemeMode = HermesThemeMode.System,
)

private val LocalHermesTokens = staticCompositionLocalOf<HermesTokens> {
    error("HermesTheme not applied")
}
private val LocalHermesTypeScale = staticCompositionLocalOf<HermesTypeScale> {
    error("HermesTheme not applied")
}
private val LocalHermesSpacing = staticCompositionLocalOf { HermesSpacing() }
private val LocalHermesIsDark = staticCompositionLocalOf { true }

/**
 * Accessor object for the Hermes design tokens, mirroring how [MaterialTheme]
 * is read. Components read `HermesTheme.tokens.scaffoldText`, never a preset
 * field or a raw colour — that indirection is the whole reason a new theme is
 * a data edit.
 */
object HermesTheme {
    val tokens: HermesTokens
        @Composable @ReadOnlyComposable get() = LocalHermesTokens.current

    val type: HermesTypeScale
        @Composable @ReadOnlyComposable get() = LocalHermesTypeScale.current

    val spacing: HermesSpacing
        @Composable @ReadOnlyComposable get() = LocalHermesSpacing.current

    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalHermesIsDark.current
}

/**
 * Apply a Hermes preset.
 *
 * Material 3 gets a mapped [androidx.compose.material3.ColorScheme] so stock
 * components (text fields, sheets, ripples) land inside the palette instead of
 * fighting it; Hermes-specific meaning lives in [HermesTokens] alongside it.
 * There is deliberately no dynamic-colour path: a Hermes skin is an identity,
 * and Material You would overwrite it with the wallpaper.
 */
@Composable
fun HermesTheme(
    selection: AppearanceSelection = AppearanceSelection(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val preset = remember(selection.themeName) { BuiltinThemes.resolve(selection.themeName) }
    val requestedDark = remember(selection.mode, systemDark) { selection.mode.resolvesToDark(systemDark) }
    // The palette is picked by what the user asked for; everything painted from
    // it is picked by what that palette actually *renders* as (`rendersDark`),
    // which is Desktop's split between `getBaseColors` and `renderedModeFor`.
    val palette = remember(preset, requestedDark) { preset.paletteFor(requestedDark) }
    val dark = remember(palette, requestedDark) { rendersDark(palette.background, requestedDark) }
    val tokens = remember(palette, dark) { HermesTokens.from(palette, dark) }
    val typeScale = remember(preset.fonts) { hermesTypeScale(preset.fonts) }
    // Material 3 seeds selection from `colorScheme.primary`, which is a theme
    // seed and therefore a different highlight on every skin. Desktop pins one
    // (`--ui-selection-background`), so the tokens own it here too; the drag
    // handles are an Android affordance Desktop has no equivalent for and wear
    // the brand stroke.
    //
    // App-wide on purpose, not transcript-only: Desktop's `*::selection` rule
    // is global (`styles.css:767` @ `45fcaaa5`), so the composer and every
    // other text field get the same highlight rather than Material's primary.
    val selectionColors = remember(tokens) {
        TextSelectionColors(
            handleColor = tokens.accent,
            backgroundColor = tokens.selectionBackground,
        )
    }

    CompositionLocalProvider(
        LocalHermesTokens provides tokens,
        LocalHermesTypeScale provides typeScale,
        LocalHermesSpacing provides HermesSpacing(),
        LocalHermesIsDark provides dark,
        LocalContentColor provides tokens.textPrimary,
    ) {
        MaterialTheme(
            colorScheme = palette.toMaterialColorScheme(dark, tokens),
            typography = typeScale.toMaterialTypography(),
        ) {
            // Inside MaterialTheme on purpose: it provides its own
            // LocalTextSelectionColors, and the Hermes one has to win.
            CompositionLocalProvider(
                LocalTextSelectionColors provides selectionColors,
                content = content,
            )
        }
    }
}

/** Desktop's `resolveMode` (`themes/context.tsx:44-45`). */
fun HermesThemeMode.resolvesToDark(systemDark: Boolean): Boolean = when (this) {
    HermesThemeMode.Light -> false
    HermesThemeMode.Dark -> true
    HermesThemeMode.System -> systemDark
}

/**
 * Map the Hermes palette onto Material's slots. The mapping is intentionally
 * flat — `surface` is the card, `surfaceVariant` is muted, `outline` is the
 * border — because Desktop has no tonal-elevation concept and faking one would
 * reintroduce exactly the boxed look DESIGN.md forbids.
 *
 * The two surfaces Desktop derives rather than seeds (`--dt-card` is
 * `--ui-bg-editor`, `--dt-background` is `--ui-bg-chrome`; `styles.css:370-372`)
 * come from [tokens], so a stock Material component lands on the same fill a
 * Hermes component would.
 */
private fun HermesPalette.toMaterialColorScheme(dark: Boolean, tokens: HermesTokens) = run {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    base.copy(
        primary = primary,
        onPrimary = primaryForeground,
        primaryContainer = accent,
        onPrimaryContainer = accentForeground,
        secondary = secondary,
        onSecondary = secondaryForeground,
        secondaryContainer = secondary,
        onSecondaryContainer = secondaryForeground,
        tertiary = midground ?: ring,
        onTertiary = midgroundForeground ?: readableOn(midground ?: ring),
        background = tokens.chatSurface,
        onBackground = foreground,
        surface = tokens.cardSurface,
        onSurface = cardForeground,
        surfaceVariant = muted,
        onSurfaceVariant = mutedForeground,
        surfaceContainer = popover,
        surfaceContainerHigh = popover,
        surfaceContainerHighest = popover,
        surfaceContainerLow = tokens.cardSurface,
        surfaceContainerLowest = tokens.chatSurface,
        outline = border,
        outlineVariant = mixPremultiplied(midground ?: ring, 10f, foreground.withAlpha(0.05f)),
        error = destructive,
        onError = destructiveForeground,
        errorContainer = destructive,
        onErrorContainer = destructiveForeground,
        scrim = foreground.withAlpha(0.32f),
    )
}

/** Keep stock Material text (menu items, snackbars) on the Hermes scale. */
private fun HermesTypeScale.toMaterialTypography() = Typography(
    bodyLarge = body,
    bodyMedium = body,
    bodySmall = caption,
    labelLarge = caption,
    labelMedium = scaffold,
    labelSmall = sectionLabel,
    titleLarge = screenTitle,
    titleMedium = sessionTitle,
    titleSmall = sessionTitle,
)
