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
 * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`):
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

    /**
     * The wash a modal paints over everything behind it: every
     * `ModalBottomSheet`, the sessions drawer, and the updates overlay.
     *
     * Desktop's scrim is **black in every skin**, not an ink derived from the
     * theme: `apps/desktop/src/app/overlays/overlay-view.tsx:77` @
     * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd` paints
     * `bg-black/22 backdrop-blur-[0.125rem]`, and the session picker's dialog
     * overlay `bg-black/15 backdrop-blur-[1px]`
     * (`apps/desktop/src/components/session-picker.tsx:48` @ the same SHA).
     * Seeding it from the theme *foreground* — which is what every scrim here
     * used to do — inverts the effect in a dark skin, where the foreground is
     * near-white: the content behind the sheet gets lighter, not dimmer.
     *
     * Android paints no backdrop blur behind a modal, so the alpha carries the
     * whole separation alone and stays at this app's established 0.32 rather
     * than following Desktop down to 0.22. That single difference is the
     * mobile-adaptation row in `docs/parity/system-panel.md`.
     */
    val overlayScrim: Color,

    // ── Chat grammar ──────────────────────────────────────────────────────
    val userBubble: Color,
    val userBubbleBorder: Color,
    val composerRing: Color,
    val inlineCodeBackground: Color,
    val inlineCodeForeground: Color,
    /**
     * Desktop's reference ink for URLs and sessions: `--ui-accent-secondary` mixed 82% toward the
     * 94% foreground wash (`--foreground`). See `styles.css:836-841` -> `:208` ->
     * `themes/context.tsx:235`; `:542` -> `:389` -> `:331` -> `:206` @ `72a3277cd7`.
     */
    val referenceInk: Color,

    /**
     * The highlight painted behind selected transcript text
     * (`--ui-selection-background`, `styles.css:386` / `:root.dark:578` @
     * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`, the theme ledger's pin;
     * confirmed at `72a3277cd7`, `styles.css:386` / `578`). Like inline code, this is a
     * *fixed* ink per mode rather than the theme accent, so a highlight reads
     * the same in every skin and never disappears into a warm palette.
     */
    val selectionBackground: Color,

    // ── Accent ────────────────────────────────────────────────────────────
    /** `--ui-accent`: the brand stroke. Resolved midground, never null. */
    val accent: Color,

    /**
     * The ink for text on the **tinted** accent surface — a wash of the brand
     * hue, not the hue itself (`--dt-accent-foreground`, `context.tsx:253`).
     * A filled action does not use this; it uses [filledActionInk].
     */
    val accentForeground: Color,

    // ── Filled actions ────────────────────────────────────────────────────
    /**
     * The label ink for text painted on [accent]-strength fills — the one
     * filled action (`PrimaryButton`) and the controls that share its
     * full-strength fill.
     *
     * Desktop never chooses the fill and its ink independently: `button.tsx:19`
     * pairs `bg-primary` with `text-primary-foreground`, and `:21` pairs
     * `bg-destructive` with `text-white`. [accent] here is the same loud
     * surface Desktop builds `--dt-primary-solid` for, and it carries the same
     * fixed `#fcfcfc`-class ink — `PRIMARY_SOLID_FOREGROUND`
     * (`context.tsx:197,268`). [destructive] carries the palette's own
     * `destructiveForeground` (`--dt-destructive-foreground`,
     * `context.tsx:271`), which is Desktop's `readableInk(destructive)`
     * (`themes/skin.ts:102`).
     *
     * Both are resolved with one rule, and the rule is *measured*, not
     * asserted: a palette pairing is honoured only while it clears the WCAG AA
     * body-text floor of 4.5:1 on the fill it is painted on, and otherwise the
     * legible candidate wins. Eleven of the twenty-two accent fills and eight
     * of the twenty-two destructive fills in `BuiltinThemes.ALL` fail their
     * palette pairing — Desktop's own ink on `catppuccin` dark destructive is
     * 2.32:1 — so honouring the pairing unconditionally would ship the very
     * defect this token exists to close. See
     * `ThemeParityTest.filled action ink follows its fill and clears AA on every
     * preset and mode` for the
     * per-preset table, and #140 for the finding.
     */
    val filledActionInk: Color,

    /** The destructive fill's own label ink — [filledActionInk] over [destructive]. */
    val destructiveActionInk: Color,

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
    /** The eight inks the Context Usage breakdown paints its categories in. */
    val contextUsage: HermesContextUsageInk,
    /** The five inks an inline diff's change content is tokenised in. */
    val syntax: HermesSyntaxInk,
) {
    companion object {
        // Tailwind amber-500 / emerald-500, the two literals Desktop's status
        // dot uses across every skin (session-status-dot.tsx:63-65).
        private val Amber500 = Color(0xFFF59E0B)
        private val Emerald500 = Color(0xFF10B981)

        /** `--ui-selection-background`'s seed, identical in both modes. */
        private val SelectionInk = Color(0xFFFFD24A)

        /**
         * Desktop's Shiki theme pair for code and diffs, one value per mode.
         *
         * `components/chat/shiki-config.ts:9` @
         * `437116f9497c80d242ce034ff7f5d81dc277a337`:
         * `{ dark: 'github-dark-dimmed', light: 'github-light-default' }`,
         * consumed by `diff-lines.tsx:607-608` for the compact diff body this
         * app ports. `github-dark-dimmed` is GitHub's lower-contrast dark
         * palette, chosen upstream over the vivid default at this code size
         * (`shiki-config.ts:6-8`).
         *
         * The five ink roles are read out of those two themes' own TextMate
         * rules rather than invented, and fixed per mode like the diff and ANSI
         * ladders beside them — Desktop's comment replacement
         * (`shiki-config.ts:20-22`, `#6e7781` → `#57606a` for light) is the one
         * upstream remap and is already in the light value below. The scope each
         * role is read from is named in its own comment.
         */
        private val SyntaxDarkKeyword = Color(0xFFF47067)
        private val SyntaxDarkString = Color(0xFF96D0FF)
        private val SyntaxDarkComment = Color(0xFF768390)
        private val SyntaxDarkNumber = Color(0xFF6CB6FF)
        private val SyntaxDarkFunction = Color(0xFFDCBDFB)

        private val SyntaxLightKeyword = Color(0xFFCF222E)
        private val SyntaxLightString = Color(0xFF0A3069)
        private val SyntaxLightComment = Color(0xFF57606A)
        private val SyntaxLightNumber = Color(0xFF0550AE)
        private val SyntaxLightFunction = Color(0xFF8250DF)

        /**
         * [overlayScrim]. One value for every preset and both modes, because
         * `overlay-view.tsx:77` @ `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd` is
         * a literal `bg-black/22`, not a theme variable.
         */
        private val OverlayScrim = Color.Black.withAlpha(0.32f)

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

            // styles.css:210,213,561-562 @
            // 72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd — `--ui-green` /
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
            val uiOrange = Color(0xFFDB704B)

            // An ANSI hue's *normal* rung is Desktop's diff-foreground rung for
            // that seed: the same knob, doing the same job — take a saturated
            // brand colour and make it legible ink on this mode's page.
            //
            // `bright` follows Desktop's direction rather than an intuition
            // about what "bright" ought to mean. For the six hues this serves
            // — `lib/ansi.ts:149-154` against their bright rungs at `:157-162`
            // @ 72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd — Desktop steps the
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

            val referenceInk = mixPremultiplied(palette.primary, 82f, textPrimary)

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
                // 72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd: keep the
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
                overlayScrim = OverlayScrim,
                userBubble = bubble,
                userBubbleBorder = palette.userBubbleBorder ?: palette.border,
                composerRing = palette.composerRing ?: accent,
                // styles.css:366-367 / :root.dark:543-544 — inline code is a
                // *fixed* ink per mode, not the theme foreground, so a fence
                // reads the same in every skin.
                inlineCodeBackground = mixPremultiplied(knobs.codeInk, knobs.codeBackgroundMix, Color.Transparent),
                inlineCodeForeground = mixPremultiplied(knobs.codeInk, 88f, Color.Transparent),
                referenceInk = referenceInk,
                // styles.css:382 / :root.dark:564 @ the theme ledger's pin —
                // one amber highlight for every skin, weaker in dark so it
                // does not blow out.
                selectionBackground = mixPremultiplied(SelectionInk, knobs.selectionMix, Color.Transparent),
                accent = accent,
                // `--dt-accent-foreground` is a palette semantic of its own;
                // it must not inherit the distinct midground foreground.
                // context.tsx:249-256 @ 72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd.
                accentForeground = palette.accentForeground,
                // Desktop decides a filled action's ink with the fill, never
                // apart from it: `button.tsx:19` pairs `bg-primary` with
                // `text-primary-foreground`, `:21` pairs `bg-destructive` with
                // `text-white`. Its own pairing is honoured here while it stays
                // legible, and the legible candidate wins where it does not —
                // Desktop's destructiveForeground is `readableInk(destructive)`
                // (`themes/skin.ts:102`), so this degrades toward Desktop's own
                // rule rather than away from it. See the field docs for why
                // both halves are load-bearing (#140).
                filledActionInk = filledActionInk(accent, palette.midgroundForeground),
                destructiveActionInk = filledActionInk(palette.destructive, palette.destructiveForeground),
                statusNeedsInput = Amber500,
                statusWorking = accent,
                // `styles.css:1026-1055,1144-1159` @
                // 72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd: the sidebar
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
                // styles.css:217-224 @
                // 72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd — the eight
                // `--context-usage-*` variables, each one an expression over
                // the named colour set above rather than a literal. Only
                // `system` tracks the preset (it is a wash of `--ui-base`);
                // the rest are fixed per mode, exactly like the diff palette,
                // so a breakdown reads the same in every skin.
                contextUsage = HermesContextUsageInk(
                    system = base.withAlpha(0.55f),
                    tools = uiPurple,
                    rules = diffAdded,
                    skills = uiYellow,
                    mcp = mixPremultiplied(diffRemoved, 72f, uiPurple),
                    subagents = mixPremultiplied(uiBlue, 70f, uiCyan),
                    memory = mixPremultiplied(uiOrange, 80f, uiYellow),
                    conversation = uiCyan,
                ),
                // Desktop's Shiki theme pair, one value per mode and fixed for
                // every preset, exactly like the diff palette and the ANSI hues
                // above: code in a diff must read the same in all eleven skins.
                syntax = HermesSyntaxInk(
                    // `keyword`, `storage`, `storage.type` → `#f47067` dark /
                    // `#cf222e` light.
                    keyword = if (dark) SyntaxDarkKeyword else SyntaxLightKeyword,
                    // `string` → `#96d0ff` dark / `#0a3069` light.
                    string = if (dark) SyntaxDarkString else SyntaxLightString,
                    // `comment`, `punctuation.definition.comment` → `#768390`
                    // dark / `#57606a` light, the one remapped rung.
                    comment = if (dark) SyntaxDarkComment else SyntaxLightComment,
                    // `constant`, `variable.language`, `support` → `#6cb6ff` dark
                    // / `#0550ae` light.
                    number = if (dark) SyntaxDarkNumber else SyntaxLightNumber,
                    // `entity.name.function` → `#dcbdfb` dark / `#8250df` light.
                    function = if (dark) SyntaxDarkFunction else SyntaxLightFunction,
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
 * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`) — `red-700 dark:red-300` and so
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

/**
 * The eight inks the Context Usage breakdown paints a category in, resolved for
 * one mode.
 *
 * The Gateway never sends a colour value: `agent/context_breakdown.py:19-28` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3` maps each of the eight known
 * category ids to a CSS variable name (`var(--context-usage-*)`), and defaults
 * an unknown id to `var(--ui-text-tertiary)` (`:155`). Desktop resolves those
 * names against `apps/desktop/src/styles.css:217-224` at the same SHA:
 *
 * ```
 * --context-usage-system:       color-mix(in srgb, var(--ui-base) 55%, transparent)
 * --context-usage-tools:        var(--ui-purple)
 * --context-usage-rules:        var(--ui-green)
 * --context-usage-skills:       var(--ui-yellow)
 * --context-usage-mcp:          color-mix(in srgb, var(--ui-red) 72%, var(--ui-purple))
 * --context-usage-subagents:    color-mix(in srgb, var(--ui-blue) 70%, var(--ui-cyan))
 * --context-usage-memory:       color-mix(in srgb, var(--ui-orange) 80%, var(--ui-yellow))
 * --context-usage-conversation: var(--ui-cyan)
 * ```
 *
 * Android has no CSS variables, so the same expressions are resolved here, over
 * the same named colour set (`styles.css:210-216`, `:root.dark:556-558`). The
 * fields are named for the *variable*, not for the category id, so the mapping
 * back to `styles.css` stays one-to-one; `resolveCategoryColor` is the only
 * place that joins a Gateway id to one of them.
 */
data class HermesContextUsageInk(
    val system: Color,
    val tools: Color,
    val rules: Color,
    val skills: Color,
    val mcp: Color,
    val subagents: Color,
    val memory: Color,
    val conversation: Color,
)

/**
 * The five inks an inline diff's change content is tokenised in.
 *
 * Desktop highlights a diff with Shiki under
 * `{ dark: 'github-dark-dimmed', light: 'github-light-default' }`
 * (`components/chat/shiki-config.ts:9` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`) and layers the add/remove tint
 * over the result, so the *background* is the change and the *ink* is the code
 * (`components/chat/diff-lines.tsx:453-467,469-487` @ the same SHA). Each value
 * here is read out of one of those two themes' own TextMate rules, by the scope
 * named on the field, rather than invented.
 *
 * Fixed per mode and identical in every preset, for the same reason the diff
 * palette is: a diff is the file's syntax, not the app's skin, and Desktop's
 * Shiki pair does not track the theme.
 *
 * The one place this diverges from Desktop is *which* tokeniser produces these
 * runs — Shiki's grammars there, `SyntaxHighlight.kt`'s bounded lexer here. See
 * that file's header and `docs/parity/tool-output-fidelity.md`.
 */
data class HermesSyntaxInk(
    /** `keyword`, `storage`, `storage.type`. */
    val keyword: Color,
    /** `string`. */
    val string: Color,
    /** `comment`, `punctuation.definition.comment`. */
    val comment: Color,
    /** `constant`, `variable.language`, `support`. */
    val number: Color,
    /** `entity.name.function`. */
    val function: Color,
)
