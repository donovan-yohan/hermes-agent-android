package com.hermesagent.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The semantic layer every component reads. Nothing in `ui/` may reference a
 * [HermesPalette] field or a preset by name — components ask for *meaning*
 * (`scaffoldText`, `strokeTertiary`, `userBubble`), which is what makes a new
 * preset a data edit.
 *
 * Every value is derived, not invented, and the derivation is Desktop's own.
 * Provenance (upstream `NousResearch/hermes-agent` @
 * `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`):
 *
 *  - `apps/desktop/src/styles.css:192-193` — `--ui-base` is the theme
 *    foreground, `--ui-accent` the theme midground. Every ladder below is a
 *    CSS `color-mix` over those two.
 *  - `apps/desktop/src/themes/context.tsx:166-177` — the per-mode *knobs*
 *    (`--theme-mix-*` and the neutral chrome), set inline on `:root` by
 *    `applyTheme` and mirrored in `styles.css:170-177` / `:root.dark:517-523`.
 *  - `apps/desktop/src/themes/context.tsx:198-230` — which palette field seeds
 *    which token.
 *
 * The surface tokens are **not** raw palette fields: Desktop mixes each seed
 * with a per-mode neutral before anything paints, and skipping that mix is what
 * made the previous port drift from Desktop on all six skins.
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
    /** The transcript backdrop: `--ui-chat-surface-background` → `--ui-bg-chrome`. */
    val chatSurface: Color,
    /** The session list backdrop (`--ui-bg-sidebar`). */
    val sidebarSurface: Color,
    /** The card/editor fill (`--ui-bg-editor`, Desktop's `--dt-card`). */
    val cardSurface: Color,
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
         * Resolve a palette into semantic tokens.
         *
         * @param dark the **rendered** mode, not the requested one — see
         *   [rendersDark]. Pure: the same palette and mode always give the same
         *   tokens, which is what the parity tests assert.
         */
        fun from(palette: HermesPalette, dark: Boolean): HermesTokens {
            val knobs = ModeKnobs.of(dark)
            val base = palette.foreground
            val accent = palette.midground ?: palette.ring

            // styles.css:324-343 — a hairline is the accent mixed into a very
            // faint wash of the foreground, so strokes carry a hint of brand
            // without becoming visible lines. Same knobs in both modes.
            fun stroke(accentMix: Float, baseAlpha: Float) =
                mixPremultiplied(accent, accentMix, base.withAlpha(baseAlpha))

            // The seed chain, context.tsx:198-210. Each surface is its seed
            // mixed toward a per-mode neutral by that mode's knob.
            val chrome = mixPremultiplied(palette.background, knobs.chromeMix, knobs.neutralChrome)
            val editor = mixPremultiplied(palette.card, knobs.cardMix, knobs.neutralCard)
            val bubble = mixPremultiplied(
                palette.userBubble ?: palette.popover,
                knobs.bubbleMix,
                knobs.neutralCard,
            )

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
                chatSurface = chrome,
                // styles.css:255-259 — the sidebar's mix knob is 100% in both
                // modes, so its seed paints unchanged and the neutral behind it
                // never shows.
                sidebarSurface = palette.sidebarBackground ?: palette.background,
                cardSurface = editor,
                // styles.css:359 / :root.dark:550 — a dark card sits *above* the
                // chrome, so a widget wearing the raw card fill reads as a lit
                // panel. Dark knocks it toward black; light uses the card as is.
                widgetSurface = if (dark) mixPremultiplied(editor, 88f, Color.Black) else editor,
                userBubble = bubble,
                userBubbleBorder = palette.userBubbleBorder ?: palette.border,
                composerRing = palette.composerRing ?: accent,
                // styles.css:366-367 / :root.dark:543-544 — inline code is a
                // *fixed* ink per mode, not the theme foreground, so a fence
                // reads the same in every skin.
                inlineCodeBackground = mixPremultiplied(knobs.codeInk, knobs.codeBackgroundMix, Color.Transparent),
                inlineCodeForeground = mixPremultiplied(knobs.codeInk, 88f, Color.Transparent),
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

/**
 * The per-mode half of the derivation: the neutral each surface seed is mixed
 * toward, and how much of the seed survives.
 *
 * Knobs are `mixesFor` (`context.tsx:171-177`) and `NEUTRAL_CHROME`
 * (`context.tsx:166`); the card neutral and the inline-code ink come from
 * `styles.css:170-177` (`:root`) and `styles.css:517-544` (`:root.dark`).
 * They are values, not defaults — a knob that drifts upstream is a one-line
 * edit here and a red parity test.
 */
private data class ModeKnobs(
    val chromeMix: Float,
    val cardMix: Float,
    val bubbleMix: Float,
    val neutralChrome: Color,
    val neutralCard: Color,
    val codeInk: Color,
    val codeBackgroundMix: Float,
) {
    companion object {
        private val Light = ModeKnobs(
            chromeMix = 92f,
            cardMix = 22f,
            bubbleMix = 0f,
            neutralChrome = Color(0xFFF3F3F3),
            neutralCard = Color(0xFFFCFCFC),
            codeInk = Color(0xFF141414),
            codeBackgroundMix = 5f,
        )

        private val Dark = ModeKnobs(
            chromeMix = 74f,
            cardMix = 38f,
            bubbleMix = 46f,
            neutralChrome = Color(0xFF0D0D0E),
            neutralCard = Color(0xFF161618),
            codeInk = Color(0xFFFFFFFF),
            codeBackgroundMix = 7f,
        )

        fun of(dark: Boolean): ModeKnobs = if (dark) Dark else Light
    }
}
