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
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`):
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
    /** The selected session's fill (`--ui-row-active-background`). */
    val sessionRowActiveSurface: Color,
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

    /**
     * The highlight painted behind selected transcript text
     * (`--ui-selection-background`, `styles.css:382` / `:root.dark:564` @
     * `3ca096de5f8183cb2e0ec23673f294d5978656a3`, the theme ledger's pin;
     * confirmed at `3ca096de`, `styles.css:386` / `564`). Like inline code, this is a
     * *fixed* ink per mode rather than the theme accent, so a highlight reads
     * the same in every skin and never disappears into a warm palette.
     */
    val selectionBackground: Color,

    // ── Accent ────────────────────────────────────────────────────────────
    /** `--ui-accent`: the brand stroke. Resolved midground, never null. */
    val accent: Color,
    val accentForeground: Color,

    // ── Session status dots (`session-status-dot.tsx:29-77`) ──────────────
    /** Amber: a clarify/approval blocks the turn. The one "act now" colour. */
    val statusNeedsInput: Color,
    /** Accent: the turn is running. */
    val statusWorking: Color,
    /** Sidebar running outline: foreground in dark mode, accent in light mode. */
    val sessionRunningOutline: Color,
    /** Emerald: the turn finished while the user was looking elsewhere. */
    val statusUnread: Color,
    /** Faintest ink the app has: nothing has ever run here. */
    val statusIdle: Color,
    /** Desktop's `--ui-green` / `--ui-red` counters, which are also the diff add/remove borders. */
    val diffAdded: Color,
    val diffRemoved: Color,
    /** The tint an added or removed diff line paints behind itself, and the ink it paints on. */
    val diffAddedBackground: Color,
    val diffAddedForeground: Color,
    val diffRemovedBackground: Color,
    val diffRemovedForeground: Color,
    /** Desktop's amber untracked-only working-tree count. */
    val gitUntracked: Color,
    /**
     * Desktop's amber "this needs a second look, but nothing broke" ink: the
     * recovered-tool glyph (`fallback.tsx:200`) and a non-zero process exit
     * (`fallback.tsx:736`). Distinct from [destructive], which claims failure,
     * and from [statusNeedsInput], which claims the turn is blocked on you.
     */
    val statusWarning: Color,
    /** Desktop's fixed `--ui-purple` for merged pull requests. */
    val pullRequestMerged: Color,
    /** Desktop task completion glyph, separate from the unread-session dot. */
    val taskCompleted: Color,
    val destructive: Color,
    /** The sixteen ANSI foregrounds terminal-shaped tool output paints with. */
    val ansi: HermesAnsiInk,
) {
    companion object {
        // Tailwind amber-500 / emerald-500, the two literals Desktop's status
        // dot uses across every skin (session-status-dot.tsx:63-65).
        private val Amber500 = Color(0xFFF59E0B)
        private val Emerald500 = Color(0xFF10B981)

        /** `--ui-selection-background`'s seed, identical in both modes. */
        private val SelectionInk = Color(0xFFFFD24A)

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

            // styles.css:196-199,528-529 @
            // 3ca096de5f8183cb2e0ec23673f294d5978656a3 — `--ui-green` /
            // `--ui-red` are fixed per mode, so a diff reads the same in every
            // skin. They are also the diff *border* seeds (`:222,225`).
            val diffAdded = if (dark) Color(0xFF55A583) else Color(0xFF1F8A65)
            val diffRemoved = if (dark) Color(0xFFE75E78) else Color(0xFFCF2D56)

            // styles.css:223-224,226-227 and `:root.dark:531-532` @ the same
            // SHA. The background is the seed at 12%; the foreground mixes the
            // seed toward the page — 70% toward #000 in light, 62% toward #fff
            // in dark — which is why only the foregrounds need a dark override.
            fun diffTint(seed: Color) = mixPremultiplied(seed, 12f, Color.Transparent)
            fun diffInk(seed: Color) =
                if (dark) mixPremultiplied(seed, 62f, Color.White) else mixPremultiplied(seed, 70f, Color.Black)

            // styles.css:196-202 and `:root.dark:528-530` @ the same SHA — the
            // rest of Desktop's named colour set. Only red, green and cyan get
            // a dark override; yellow, blue and purple are one value per app.
            val uiYellow = Color(0xFFC08532)
            val uiBlue = Color(0xFF0053FD)
            val uiCyan = if (dark) Color(0xFF6F9BA6) else Color(0xFF4C7F8C)
            val uiPurple = Color(0xFF9E94D5)

            // An ANSI hue's *normal* rung is Desktop's diff-foreground rung for
            // that seed: the same knob, doing the same job — take a saturated
            // brand colour and make it legible ink on this mode's page.
            //
            // `bright` follows Desktop's direction rather than an intuition
            // about what "bright" ought to mean. For the six hues this serves
            // — `lib/ansi.ts:149-154` against their bright rungs at `:157-162`
            // @ 3ca096de5f8183cb2e0ec23673f294d5978656a3 — Desktop steps the
            // bright rung one Tailwind step *lighter* in both modes, never a
            // step darker: `red-700 → rose-600` (`:149` → `:157`) in light,
            // `emerald-300 → emerald-200` (`:150` → `:158`) in dark. Android
            // has no Tailwind ladder, so "one step" is a mix toward white, the
            // same direction in both modes.
            //
            // Only the hues. The four neutral rungs are not derived here and do
            // not follow that rule — `:156` steps bright-black *darker* in dark
            // mode (`zinc-300 → zinc-400`) — so they are read straight off
            // Desktop's fixed greys below (`:148,155,156,163`).
            //
            // 18 % is the largest uniform step that keeps every rung above the
            // 3.0:1 as-painted floor `ThemeSemanticParityTest` asserts: the
            // floor arbitrates the size, and solarized light's bright magenta
            // is the binding pair at 3.17:1. Going further reads as Desktop's
            // washed-out bright rung and then falls through the floor; the
            // undiluted seed — the diff *border* rung — is 2.65:1 on a light
            // page and was rejected for the same reason.
            fun ansiBright(seed: Color) = mixPremultiplied(diffInk(seed), 82f, Color.White)

            val textPrimary = base.withAlpha(0.94f)
            val textSecondary = base.withAlpha(0.74f)
            val textTertiary = base.withAlpha(0.54f)
            val textQuaternary = base.withAlpha(0.36f)

            return HermesTokens(
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                textTertiary = textTertiary,
                textQuaternary = textQuaternary,
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
                // `--ui-row-active-background` at styles.css:308-312 @
                // 3ca096de5f8183cb2e0ec23673f294d5978656a3: keep the
                // Desktop nested color-mix expression rather than resolving a
                // Nous-only literal at the session-row call site.
                sessionRowActiveSurface = mixPremultiplied(
                    accent,
                    8f,
                    base.withAlpha(0.05f),
                ),
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
                // styles.css:382 / :root.dark:564 @ the theme ledger's pin —
                // one amber highlight for every skin, weaker in dark so it
                // does not blow out.
                selectionBackground = mixPremultiplied(SelectionInk, knobs.selectionMix, Color.Transparent),
                accent = accent,
                // `--dt-accent-foreground` is a palette semantic of its own;
                // it must not inherit the distinct midground foreground.
                // context.tsx:238-245 @ 3ca096de5f8183cb2e0ec23673f294d5978656a3.
                accentForeground = palette.accentForeground,
                statusNeedsInput = Amber500,
                statusWorking = accent,
                // `styles.css:1011-1040,1129-1144` @
                // 3ca096de5f8183cb2e0ec23673f294d5978656a3: the sidebar
                // outline's bright stop is --dt-foreground in dark mode and
                // --dt-midground in light mode.
                sessionRunningOutline = if (dark) palette.foreground else accent,
                statusUnread = Emerald500,
                statusIdle = base.withAlpha(0.36f),
                diffAdded = diffAdded,
                diffRemoved = diffRemoved,
                diffAddedBackground = diffTint(diffAdded),
                diffAddedForeground = diffInk(diffAdded),
                diffRemovedBackground = diffTint(diffRemoved),
                diffRemovedForeground = diffInk(diffRemoved),
                // coding-row.tsx:319-324 @ the same pinned SHA.
                gitUntracked = Amber500,
                // fallback.tsx:200,736 @ the same pinned SHA — amber-600/400.
                statusWarning = Amber500,
                // styles.css:202 + pr-tag.tsx:10-14 @ the pinned SHA.
                pullRequestMerged = uiPurple,
                // status-row.tsx:20-23 @ the pinned SHA — emerald-500/80.
                taskCompleted = Emerald500.copy(alpha = 0.8f),
                destructive = palette.destructive,
                ansi = HermesAnsiInk(
                    // Desktop paints the four ANSI neutrals as greys, never as
                    // `#000`/`#fff`, because the pure ends "disappear into the
                    // surface" (`lib/ansi.ts:145-147`). The greys are zinc:
                    // 700 / 600 / 500 / 500 in light and 100 / 200 / 300 / 400
                    // in dark (`lib/ansi.ts:148,155,156,163`), fixed for
                    // every theme — Desktop's neutrals do not track the page.
                    //
                    // Android has no Tailwind zinc, so the same four rungs are
                    // plain greys at zinc's lightness. Dark takes zinc's four
                    // stops directly. Light is an even ramp anchored on zinc-700
                    // and zinc-600 whose last two stops fall either side of the
                    // single zinc-500 Desktop ties bright-black and bright-white
                    // at; the sixteen inks have to stay distinct here, and the
                    // tie is broken so the bold rung is never the fainter one.
                    //
                    // The text ladder is *not* usable for this: its lower rungs
                    // are alpha washes, and as painted on `widgetSurface` the
                    // quaternary rung is 1.65:1 in the weakest preset. See the
                    // legibility floor in `ThemeSemanticParityTest`.
                    black = if (dark) Color(0xFFD5D5D5) else Color(0xFF424242),
                    white = if (dark) Color(0xFFE5E5E5) else Color(0xFF555555),
                    brightWhite = if (dark) Color(0xFFF4F4F4) else Color(0xFF686868),
                    brightBlack = if (dark) Color(0xFFA2A2A2) else Color(0xFF7B7B7B),
                    red = diffInk(diffRemoved),
                    brightRed = ansiBright(diffRemoved),
                    green = diffInk(diffAdded),
                    brightGreen = ansiBright(diffAdded),
                    yellow = diffInk(uiYellow),
                    brightYellow = ansiBright(uiYellow),
                    blue = diffInk(uiBlue),
                    brightBlue = ansiBright(uiBlue),
                    magenta = diffInk(uiPurple),
                    brightMagenta = ansiBright(uiPurple),
                    cyan = diffInk(uiCyan),
                    brightCyan = ansiBright(uiCyan),
                ),
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
    val selectionMix: Float,
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
            selectionMix = 55f,
        )

        private val Dark = ModeKnobs(
            chromeMix = 74f,
            cardMix = 38f,
            bubbleMix = 46f,
            neutralChrome = Color(0xFF0D0D0E),
            neutralCard = Color(0xFF161618),
            codeInk = Color(0xFFFFFFFF),
            codeBackgroundMix = 7f,
            selectionMix = 38f,
        )

        fun of(dark: Boolean): ModeKnobs = if (dark) Dark else Light
    }
}

/**
 * The sixteen ANSI foregrounds, resolved for one mode.
 *
 * Desktop maps the ANSI palette to fixed Tailwind classes
 * (`apps/desktop/src/lib/ansi.ts:144-164` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`) — `red-700 dark:red-300` and so
 * on, with a note that they are "tuned for legibility against the muted
 * bg-(--ui-bg-tertiary) surface" and that pure `#000`/`#fff` are avoided
 * because they disappear into it.
 *
 * Android cannot take that ladder literally. Those are thirty-two colours from
 * a CSS framework's palette, tuned against Desktop's single surface; this app
 * paints tool output on `widgetSurface`, which is derived per preset, and
 * `AGENTS.md` is explicit that a component reads meaning rather than a colour.
 * So the ladder is *derived* instead, and derived from Desktop's own named
 * colour set — `--ui-red`, `--ui-yellow`, `--ui-green`, `--ui-cyan`,
 * `--ui-blue`, `--ui-purple` (`styles.css:196-202`, `:root.dark:528-530`) —
 * which happens to cover exactly the six hues ANSI names. The rule, and what it
 * costs, is in `docs/parity/tool-output-fidelity.md`.
 *
 * Like the diff palette and inline code, the six hues are fixed per mode, so
 * terminal output reads the same in every skin; only the four neutral rungs
 * follow the preset's foreground.
 */
data class HermesAnsiInk(
    val black: Color,
    val red: Color,
    val green: Color,
    val yellow: Color,
    val blue: Color,
    val magenta: Color,
    val cyan: Color,
    val white: Color,
    val brightBlack: Color,
    val brightRed: Color,
    val brightGreen: Color,
    val brightYellow: Color,
    val brightBlue: Color,
    val brightMagenta: Color,
    val brightCyan: Color,
    val brightWhite: Color,
)
