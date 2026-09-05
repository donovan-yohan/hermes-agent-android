package com.hermesagent.mobile.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hermesagent.mobile.R

/**
 * Desktop's conversation type scale, adapted for a phone.
 *
 * Desktop values (`apps/desktop/src/styles.css:440-447` @ `3ca096de`, 1rem = 16px):
 *
 * | Desktop token                     | Desktop | Android |
 * |-----------------------------------|---------|---------|
 * | `--conversation-text-font-size`   | 13px    | 15sp    |
 * | `--conversation-caption-font-size`| 12px    | 13sp    |
 * | `--conversation-tool-font-size`   | 11px    | 12sp    |
 * | `--conversation-line-height`      | 18px    | 21sp    |
 * | `--conversation-caption-line-height` | 16px | 18sp    |
 * | `--conversation-turn-gap`         | 6px     | 8dp     |
 * | `--turn-block-gap`                | 12px    | 14dp    |
 * | `--message-text-indent`           | 12px    | 12dp    |
 *
 * The ~1.15x bump is the whole adaptation: a phone is read at ~35cm against a
 * desktop's ~60cm, and 13px body text is unreadable in the hand. Ratios between
 * the three sizes are preserved, so the hierarchy — prose loudest, captions a
 * notch down, scaffolding quietest — reads identically.
 */
data class HermesTypeScale(
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val caption: TextStyle,
    /** Transcript scaffolding: tool rows, thinking headers, activity ticker. */
    val scaffold: TextStyle,
    /** Durations and counts trailing a scaffold label. Tabular. */
    val scaffoldMeta: TextStyle,
    val code: TextStyle,
    val sessionTitle: TextStyle,
    val sessionPreview: TextStyle,
    /** Quiet uppercase field label above a list group or a payload block. */
    val sectionLabel: TextStyle,
    /** Accent sidebar panel heading: uppercase, wide tracking, semibold. */
    val panelLabel: TextStyle,
    val screenTitle: TextStyle,
    /**
     * The empty chat's display lettering. Desktop's `.wordmark`
     * (`apps/desktop/src/styles.css:1629-1635` @ `3ca096de`): Collapse Bold,
     * weight 700, line-height 0.9, uppercase, tracking 0.08em.
     *
     * The family is [CollapseBold] whatever the preset asks for, because
     * `.wordmark` names `'Collapse', var(--font-sans)` and so overrides the
     * theme sans for every skin upstream — including `cyberpunk`, which sets
     * the rest of the UI in a monospace.
     *
     * The size here is only Desktop's `--fit-min` floor. `.fit-text` sizes the
     * lettering to its column and so does
     * [com.hermesagent.mobile.ui.chat.Wordmark], which overrides both the size
     * and the line height it derives from it.
     */
    val wordmark: TextStyle,
)

/** Layout rhythm. Same provenance as the type scale. */
data class HermesSpacing(
    /** Page side padding. Desktop's `PAGE_INSET_X`. */
    val pageInset: Dp = 16.dp,
    /** Between the parts of one turn. `--conversation-turn-gap`. */
    val turnGap: Dp = 8.dp,
    /** Between top-level turn blocks (prose ↔ tools ↔ thinking). `--turn-block-gap`. */
    val blockGap: Dp = 14.dp,
    /** Left inset that aligns assistant prose with scaffolding. `--message-text-indent`. */
    val textIndent: Dp = 12.dp,
    /** Minimum touch target. Android platform floor, not a Desktop value. */
    val touchTarget: Dp = 48.dp,
)

/**
 * Build the scale for a preset's font choice. `cyberpunk` sets both families
 * to monospace upstream, which turns the whole UI monospace — [sans] carries
 * that through rather than special-casing the theme name.
 */
fun hermesTypeScale(fonts: HermesFontChoice): HermesTypeScale {
    val sans = fonts.sans.toFontFamily()
    val mono = fonts.mono.toFontFamily()

    val body = TextStyle(fontFamily = sans, fontSize = 15.sp, lineHeight = 21.sp)
    val caption = TextStyle(fontFamily = sans, fontSize = 13.sp, lineHeight = 18.sp)
    val scaffold = TextStyle(fontFamily = sans, fontSize = 12.sp, lineHeight = 17.sp)

    return HermesTypeScale(
        body = body,
        bodyStrong = body.copy(fontWeight = FontWeight.SemiBold),
        caption = caption,
        scaffold = scaffold,
        scaffoldMeta = scaffold.copy(fontSize = 11.sp, textAlign = TextAlign.End),
        code = TextStyle(fontFamily = mono, fontSize = 13.sp, lineHeight = 19.sp),
        sessionTitle = TextStyle(fontFamily = sans, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        sessionPreview = caption.copy(fontSize = 12.5f.sp),
        sectionLabel = TextStyle(
            fontFamily = sans,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.08.em,
        ),
        // SidebarPanelLabel at 3ca096de: 0.64rem, semibold, uppercase,
        // tracking 0.16em. The one-sp bump is the phone readability adaptation.
        panelLabel = TextStyle(
            fontFamily = sans,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.16.em,
        ),
        screenTitle = TextStyle(fontFamily = sans, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        // Desktop's own face at Desktop's weight, tracking and 0.9 line
        // height. Not `sans`: `.wordmark` overrides the theme sans upstream.
        wordmark = TextStyle(
            fontFamily = CollapseBold,
            fontSize = 44.sp,
            lineHeight = 39.6f.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.08.em,
            textAlign = TextAlign.Center,
        ),
    )
}

/**
 * Desktop's wordmark face, bundled.
 *
 * `styles.css:62-68` @ `3ca096de` loads `Collapse-Bold.woff2` from
 * `@nous-research/ui`; `res/font/collapse_bold.otf` is that same file with the
 * woff2 container removed, because Android's `res/font` cannot read woff2.
 * Only the Bold is shipped, because only the Bold is what `.wordmark` asks
 * for — nothing here may synthesise another weight from it. Provenance,
 * hashes and the licence line are in `docs/fonts.md`.
 */
private val CollapseBold = FontFamily(Font(R.font.collapse_bold, FontWeight.Bold))

/**
 * The wordmark aside, Android bundles no *theme* webfont and does not fetch one
 * at runtime, so every family a preset names collapses to a platform family.
 * See `docs/workflows/sync-desktop-themes.md` for the per-preset substitution
 * table.
 */
private fun HermesFontFamily.toFontFamily(): FontFamily = when (this) {
    HermesFontFamily.Sans -> FontFamily.SansSerif
    HermesFontFamily.Mono -> FontFamily.Monospace
}
