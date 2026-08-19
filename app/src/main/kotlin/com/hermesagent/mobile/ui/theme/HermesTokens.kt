package com.hermesagent.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The semantic layer every component reads. Nothing in `ui/` may reference a
 * [HermesPalette] field or a preset by name — components ask for *meaning*
 * (`scaffoldText`, `strokeTertiary`, `userBubble`), which is what makes a new
 * preset a data edit.
 *
 * Every value is derived, not invented. Provenance is Desktop's
 * `apps/desktop/src/styles.css` @ `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`,
 * where the same ladders are CSS `color-mix` expressions over `--ui-base`
 * (the theme foreground, styles.css:192) and `--ui-accent` (the theme
 * midground, styles.css:193).
 */
data class HermesTokens(
    // ── Text ladder (styles.css:313-316) ──────────────────────────────────
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textQuaternary: Color,

    // ── Transcript scaffolding (styles.css:322-323) ───────────────────────
    /** Thinking headers, settled tool runs, the live activity ticker. */
    val scaffoldText: Color,
    /** Durations, counts and diff stats trailing a scaffold label. */
    val scaffoldMeta: Color,

    // ── Hairlines (styles.css:324-343), strongest first ───────────────────
    val strokePrimary: Color,
    val strokeSecondary: Color,
    val strokeTertiary: Color,
    val strokeQuaternary: Color,

    // ── Surfaces ──────────────────────────────────────────────────────────
    /** The transcript backdrop (`--ui-chat-surface-background`). */
    val chatSurface: Color,
    /** The session list backdrop (`--ui-bg-sidebar`). */
    val sidebarSurface: Color,
    /** Inline tool/artifact widget fill (`--ui-widget-surface-background`). */
    val widgetSurface: Color,

    // ── Chat grammar ──────────────────────────────────────────────────────
    val userBubble: Color,
    val userBubbleBorder: Color,
    val composerRing: Color,
    val inlineCodeBackground: Color,
    val inlineCodeForeground: Color,

    // ── Accent ────────────────────────────────────────────────────────────
    /** `--ui-accent`: the brand stroke. Resolved midground, never null. */
    val accent: Color,
    val accentForeground: Color,

    // ── Session status dots (`session-status-dot.tsx:29-77`) ──────────────
    /** Amber: a clarify/approval blocks the turn. The one "act now" colour. */
    val statusNeedsInput: Color,
    /** Accent: the turn is running. */
    val statusWorking: Color,
    /** Emerald: the turn finished while the user was looking elsewhere. */
    val statusUnread: Color,
    /** Faintest ink the app has: nothing has ever run here. */
    val statusIdle: Color,
    val destructive: Color,
) {
    companion object {
        // Tailwind amber-500 / emerald-500, the two literals Desktop's status
        // dot uses across every skin (session-status-dot.tsx:33,64).
        private val Amber500 = Color(0xFFF59E0B)
        private val Emerald500 = Color(0xFF10B981)

        /**
         * Resolve a palette into semantic tokens. Pure: the same palette and
         * mode always give the same tokens, which is what the parity tests
         * assert.
         */
        fun from(palette: HermesPalette, dark: Boolean): HermesTokens {
            val base = palette.foreground
            val accent = palette.midground ?: palette.ring

            // styles.css:324-343 — a hairline is the accent mixed into a very
            // faint wash of the foreground, so strokes carry a hint of brand
            // without becoming visible lines.
            fun stroke(accentMix: Float, baseAlpha: Float) =
                mixPremultiplied(accent, accentMix, base.withAlpha(baseAlpha))

            val card = palette.card
            // styles.css:550 — a widget settles *into* the transcript in dark
            // mode instead of glowing above it.
            val widget = if (dark) mixPremultiplied(card, 88f, base.withAlpha(0.06f)) else card

            return HermesTokens(
                textPrimary = base.withAlpha(0.94f),
                textSecondary = base.withAlpha(0.74f),
                textTertiary = base.withAlpha(0.54f),
                textQuaternary = base.withAlpha(0.36f),
                scaffoldText = base.withAlpha(0.64f),
                scaffoldMeta = base.withAlpha(0.44f),
                strokePrimary = stroke(24f, 0.10f),
                strokeSecondary = stroke(16f, 0.07f),
                strokeTertiary = stroke(10f, 0.05f),
                strokeQuaternary = stroke(6f, 0.03f),
                chatSurface = palette.background,
                sidebarSurface = palette.sidebarBackground ?: palette.background,
                widgetSurface = widget,
                userBubble = palette.userBubble ?: palette.card,
                userBubbleBorder = palette.userBubbleBorder ?: stroke(10f, 0.05f),
                composerRing = palette.composerRing ?: accent,
                // styles.css:366-367 — the inline-code wash is a fixed ink over
                // whatever surface it lands on, so it reads the same everywhere.
                inlineCodeBackground = base.withAlpha(0.06f),
                inlineCodeForeground = base.withAlpha(0.88f),
                accent = accent,
                accentForeground = palette.midgroundForeground ?: readableOn(accent),
                statusNeedsInput = Amber500,
                statusWorking = accent,
                statusUnread = Emerald500,
                statusIdle = base.withAlpha(0.36f),
                destructive = palette.destructive,
            )
        }
    }
}
