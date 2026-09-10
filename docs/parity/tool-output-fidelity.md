# Tool output fidelity

What an expanded tool row shows, where each field comes from on Desktop, and
every place the Android port deliberately says something different.

Scope is issue #71 slice S33: ANSI parsing, stdout/stderr sections, the exit
code, the `$ cmd` prompt line, structured web-search hits, the tool-tone icon
set and the status glyph vocabulary — and, since S34, the inline diff panel,
which has a section of its own below. Long-press selection of tool output is
S35 and still has a row in the deferral table.

## Pin and source contract

Desktop authority is `NousResearch/hermes-agent` at
`72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd` (read-only checkout; read with
`git -C ~/.hermes/hermes-agent show <sha>:<path>`). Files read for this port:

| Desktop source | What it decides |
|---|---|
| `apps/desktop/src/components/assistant-ui/tool/fallback-model/types.ts:32-64` | The `ToolView` contract — the field list this port mirrors |
| `apps/desktop/src/components/assistant-ui/tool/fallback-model/index.ts:1409-1499` | `buildToolView`: how every field is derived |
| `apps/desktop/src/components/assistant-ui/tool/fallback-model/index.ts:142-214,233-236` | `TOOL_META` / `PREFIX_META`: tone and icon per tool |
| `apps/desktop/src/components/assistant-ui/tool/fallback-model/index.ts:274-551` | Count metric: field keys, noun tables, pluralisation |
| `apps/desktop/src/components/assistant-ui/tool/fallback-model/index.ts:666-737` | Error text, status, duration label |
| `apps/desktop/src/components/assistant-ui/tool/fallback-model/index.ts:1188-1281` | `toolCopyPayload`: what Copy hands over, per tool |
| `apps/desktop/src/components/assistant-ui/tool/fallback-model/format.ts:45-59` | `clampForDisplay` and `MAX_TOOL_RENDER_CHARS` |
| `apps/desktop/src/components/assistant-ui/tool/fallback.tsx:92,183-254,597-744` | Section-label grammar, status glyphs, the expanded row, `TerminalTranscript` |
| `apps/desktop/src/components/ui/tool-icon.tsx` | The tool-tone glyph set |
| `apps/desktop/src/components/assistant-ui/ansi-text.tsx` | How parsed runs become styled spans |
| `apps/desktop/src/lib/ansi.ts` + `ansi.test.ts` | The ANSI rule set and its fixtures |
| `apps/desktop/src/components/chat/terminal-output.tsx:14,23,45-58` | Tail only when already near the bottom |
| `apps/desktop/src/styles.css:196-202,222-227`, `:root.dark:528-532` | The named colour set the ANSI ladder derives from |
| `apps/desktop/src/i18n/en.ts:3182-3185` | The status glyph vocabulary: Running / Error / Recovered / Done |

## ToolView field map

`ToolView` on Android lives in `app/src/main/kotlin/com/hermesagent/mobile/ui/chat/ToolView.kt`
and is built by `ToolActivity.toolView()`. Desktop builds its view from a decoded
`args`/`result`; Android's `ToolActivity` carries them as raw JSON *text*
(`SessionModel.kt:188-200`), so every read here is tolerant — a payload that is
not an object, not JSON, or missing the field yields nothing rather than
throwing. Tool output is untrusted input.

| `ToolView` field (`types.ts`) | Desktop derivation | Android |
|---|---|---|
| `tone` | `TOOL_META[name].tone` | **Not carried.** No renderer reads `view.tone` at the pin — not `fallback.tsx`, not anywhere in `apps/desktop/src` — so it is a contract field with no consumer on either side. The table it comes from is ported for its icons |
| `icon` | `TOOL_META[name].icon` (a Codicon name drawn as a filled Phosphor path), then the `browser_`/`web_` prefix rule | `ToolIconName` → `HermesIcon` Codicon 0.0.45 code point, same table and same prefix rule, then the substring heuristic Android already shipped. See **Icons** below |
| `status` | `toolStatus`: running / success / warning / error | `ToolStatus`, derived from `ToolState` plus Desktop's exit-code rule; adds a `Stopped` rung |
| `countLabel` | `toolResultCount` + `formatCountLabel` | Ported: field keys, array keys, exclusions, noun tables, singularise/pluralise, the `memory` `entry_count` special case, and the free-text fallback |
| `durationLabel` | `formatDurationSeconds(result.duration_s)` | `ToolActivity.elapsedSeconds`, which the gateway already fills from `duration_s` (`GatewaySessionRepository.kt:2319-2322`) |
| `detail` | `toolDetailText`, error text prefixed and de-duplicated | Same, plus one arm Desktop cannot need (see **Deviations**, D5) |
| `detailLabel` | `'Error details'`, `'Details'` (web_search), `'Snapshot summary'`, else empty | Identical |
| `inlineDiff` | `stripInlineDiffChrome(sideDiff) \|\| inlineDiffFromResult(result)` | **Not carried.** `ToolRow` hands an inline-diff row to `InlineDiffPanel` and returns before this view paints, so a field here would be write-only. The panel keeps `ToolActivity.inlineDiff` unchanged from S32 |
| `rendersAnsi` | `toolName === 'terminal' \|\| 'execute_code'` | Same, matched loosely so gateway variants still qualify |
| `searchQuery` | `search_term` / `query` / context value, web_search only | Identical |
| `searchHits` | `extractSearchResults(result, limit = 6)` | Identical: same container keys, same field aliases, same six-hit cap |
| `stdout` / `stderr` | `firstStringField(result, ['stdout'])` / `['stderr']`, attached only when the backend split them | Identical, including "only when actually split" |
| `terminalCommand` | `shellCommand(args)`, `terminal` only | Identical |
| `terminalExitCode` | `numericField(result, 'exit_code')`, `terminal` only | Identical |
| `title` / `subtitle` / `titleAction` | `dynamicTitle` + `toolSubtitle` (~250 lines) | **Not ported.** Android keeps its own `displayTitle()`. See D8 |
| `imageUrl` | `toolImageUrl` | **Not ported** — inline image results are an explicit non-goal of #71 |
| `previewTarget` | `toolPreviewTarget` | **Not ported** — artifact detection is an explicit non-goal of #71 |
| — | `toolCopyPayload(part, view)` (`fallback.tsx:599-609`) | Carried on the view as `copy: ToolCopyAction?`, so the row's control and its label come from the same projection. The `isFileEditTool` branch is ported in `InlineDiffPanel`, and its Copy hands over the same payload Desktop does — `view.inlineDiff`, chrome-stripped by `fallback.tsx:375` before `copy.file` is built, file headers still present (`fallback-model/index.ts:1254-1257 @ 72a3277cd7`) — through the same `ToolCopyControl` |
| — | `stripAnsi` (`ansi.ts:177-186`) | Ported in S34 as `data/markdown/Ansi.kt`'s `stripAnsi`, on the same single-pass scanner `parseAnsi` already uses. Every surface *this* slice owns paints escapes as colour rather than as text, so the strip has exactly one caller: the inline diff panel. See **Inline diff (S34)** below |

### Icons

Desktop's `ToolIcon` draws a filled Phosphor path keyed by a Codicon name, and
falls back to the outline Codicon font for any name it has no path for. Android
has the Codicon font only, at the same pinned version the Desktop `package.json`
declares (`@vscode/codicons` 0.0.45), so the outline is the whole set.

| Desktop icon name | Codicon 0.0.45 | Android |
|---|---|---|
| `edit` | `U+EA73` | `HermesIcon.Edit` (already present) |
| `eye` | `U+EA70` | `HermesIcon.Eye` (added) |
| `file` | `U+EA7B` | `HermesIcon.File` (already present) |
| `file-media` | `U+EAEA` | `HermesIcon.FileMedia` (added) |
| `files` | `U+EAF0` | `HermesIcon.Files` (added) |
| `globe` | `U+EB01` | `HermesIcon.Globe` (already present) |
| `question` | `U+EB32` | `HermesIcon.Question` (added) |
| `search` | `U+EA6D` | `HermesIcon.Search` (already present) |
| `terminal` | `U+EA85` | `HermesIcon.Terminal` (already present) |
| `tools` | `U+EB6D` | `HermesIcon.Tools` (added) |
| `watch` | `U+EB7C` | `HermesIcon.Watch` (added) |
| `brain` | **not in Codicon 0.0.45** | `HermesIcon.Database` (`U+EACE`) — see D3 |

## Mobile adaptation table

| Desktop behaviour | Why it cannot come across unchanged | Android |
|---|---|---|
| Each payload sits in a `max-h-16`/`max-h-20` box that scrolls internally and tails when already near the bottom (`terminal-output.tsx:14,45-58`) | A nested vertical scroller inside a `LazyColumn` competes with the transcript's own drag on touch, and it is the exact gesture ambiguity #56 already deferred once | The block renders inline, clamped by lines and characters; the near-the-bottom tail rule is the transcript's existing follow discipline (`ChatScreen.kt:250-303`), which arms at the bottom and disarms on a backward scroll. No second scroll effect was added |
| `clampForDisplay` caps at 20,000 characters | A phone has less room and less memory than a window | Same 20,000-character cap, plus a 200-line cap; whichever bites first, with Desktop's truncation sentence. The cut lands *past* the last kept newline, and a truncation smaller than the notice itself is not announced — otherwise a complete 200-line log would gain sixty characters to say it lost one. "Use Copy for the full output" means the full output *this app was given*: `GatewaySessionRepository` already caps every tool payload at `MAX_TOOL_PAYLOAD` = 32,768 characters on the way in, so Copy hands over at most 32 KB and the clipboard-size question never arises |
| Copy appears on hover, absolutely positioned over the payload | A phone has no hover | Always mounted, right-aligned above the payload, in the scaffold-meta ink, 48 dp target — the precedent `ReplyActions` and `CodingStatusRow` already set |
| Long lines wrap (`whitespace-pre-wrap wrap-anywhere`) in the tool card | A 360 dp column turns a wrapped log line into a wall | Horizontal scroll per payload block, which is the grammar `ToolPayload` already used and what S35's gesture work is scoped against |
| Search hit titles are `PrettyLink`s that open externally | Opening an external browser from a transcript is a new surface with its own consent question | Title, URL and snippet render as quiet structured text; the link affordance is deferred (see below) |
| A status glyph pre-empts the tool icon; success is silent (`fallback.tsx:212-254`) | — | Same rule: error and warning pre-empt, success keeps the tool glyph. Which glyph and which ink differ — see D10 |
| Running shows a `GlyphSpinner` in place of the tool icon (`fallback.tsx:184-192`) | The Android disclosure row has no spinner slot, and issue #71 says to extend that row, not replace it | The tool glyph tinted with the accent, beside the live elapsed timer, which already says "running". `stopped`, which Desktop has no concept of, likewise keeps the tool glyph in the quietest ink |
| Status glyphs are aria-labelled Running / Error / Recovered / Done | Android's row is one merged semantics node with a sentence | The same four words, lower-cased into `"Tool <title>, <state>"`, plus `stopped` |
| Section labels are `uppercase` CSS (`fallback.tsx:92`) | — | `HermesTheme.type.sectionLabel` already matches the size, weight and 0.08 em tracking; the label text is upper-cased at the call site |
| The `$` is `aria-hidden` (`fallback.tsx:726`) | — | The prompt line carries `contentDescription = "Command <cmd>"`, so the `$` is painted but not spoken |

## Deviation ledger

**D1 — The ANSI colour ladder is derived, not transcribed.**
Desktop maps the sixteen ANSI foregrounds to fixed Tailwind classes
(`ansi.ts:144-164`), e.g. `red-700 dark:red-300`, with a comment that they are
"tuned for legibility against the muted `bg-(--ui-bg-tertiary)` surface" and
that pure `#000`/`#fff` are avoided because they vanish into it. Those thirty-two
colours belong to a CSS framework (`tailwindcss` 4.3.3, OKLCH), are tuned
against Desktop's single surface, and are not values this repo can hold without
importing a palette it otherwise has nothing to do with — while `AGENTS.md`
requires a component to read *meaning*, and this app paints tool output on
`widgetSurface`, which is derived per preset.

So the ladder is derived from Desktop's *own* named colour set
(`styles.css:196-202`, `:root.dark:528-530`), plus the text ladder for the
neutrals. Six of the seven names Desktop declares — `--ui-red`, `--ui-yellow`,
`--ui-green`, `--ui-cyan`, `--ui-blue`, `--ui-purple` — are exactly the six hues
ANSI names, magenta taking the purple; `--ui-orange` has no ANSI counterpart and
is unused here:

| Rung | Rule | Provenance of the rule |
|---|---|---|
| normal hue | the `--ui-<hue>` seed mixed toward the mode's contrast pole: 70 % seed + `#000` in light, 62 % seed + `#fff` in dark | Desktop's own diff-foreground knob, `styles.css:224,227` / `:root.dark:531-532` |
| bright hue | that normal rung mixed 18 % toward `#fff`, in **both** modes | Desktop's direction across these six hues: `lib/ansi.ts:149-154` against their bright rungs at `:157-162` steps bright one Tailwind step *lighter* in both modes (`red-700 → rose-600`, `:149` → `:157`; `emerald-300 → emerald-200`, `:150` → `:158`) and never a step darker. The neutrals sit outside that rule — `:156` steps bright-black darker in dark — and take the row below. The size is what the floor allows — see below |
| the four neutrals | plain greys at Tailwind zinc's lightness, fixed per mode | `lib/ansi.ts:145-147,148,155,156,163`: Desktop refuses `#000`/`#fff` and paints zinc 700 / 600 / 500 / 500 in light, 100 / 200 / 300 / 400 in dark — greys that do not track the theme |

**The bright rung follows Desktop, not an intuition.** An earlier draft read
`bright` as "more prominent" and applied the diff-foreground knob a second time,
which goes *darker* on a light page. Desktop does the opposite: every one of its
six bright hues is the same lightness or one Tailwind step lighter than its
normal rung, in both modes. Android now does the same thing with one knob and
one direction.

The step size is not free — Android has no Tailwind ladder to step along — so
the legibility floor arbitrates it. 18 % toward white is the largest uniform
step that keeps all sixteen rungs at 3.0:1 as painted; solarized light's
`bright-magenta` is the binding pair at 3.17:1. That also disposes of the
obvious simpler rule, bright = the undiluted seed (Desktop's diff *border*
rung): `--ui-purple` is `#9e94d5`, 2.65:1 on a light tool surface.

**The neutrals are greys, not the text ladder.** The first draft read the four
neutrals off the app's text ladder, which is the one part of the ladder that
tracked the preset. That was wrong twice. Desktop's neutrals are zinc, fixed for
every theme — they do not track its page either. And the lower text rungs are
alpha washes: composited onto `widgetSurface`, `textQuaternary` is 1.65:1 in the
weakest preset, so `bright-black` — the ink `git` hints and `npm` progress land
in — was below the floor in all 22 preset/mode pairs and `black` in 3. The
floor did not catch it because it was measuring the un-painted colour; that is
fixed too, and every ANSI ink is now opaque, which is asserted.

Dark takes zinc's four stops. Light is an even ramp anchored on zinc-700 and
zinc-600 whose last two stops fall either side of the single zinc-500 Desktop
ties `bright-black` and `bright-white` at; Android has to keep the sixteen
distinct, and the tie is broken so the bold rung is never the fainter of the two.
`bright-black` stays the quietest neutral in both modes, as Desktop has it.

Consequences, stated plainly: all sixteen inks are now *fixed per mode*, exactly
as Desktop's are and as the diff palette and inline code already are, so a build
log reads the same in all eleven presets. Nothing in the ANSI ladder follows the
preset any more. ANSI green and ANSI red come out identical to
`diffAddedForeground` / `diffRemovedForeground`, which is asserted rather than
incidental — a terminal's green and an inline diff's green must not become two
colours.

Resolved values, `#aarrggbb`:

| Hue | Light normal | Light bright | Dark normal | Dark bright |
|---|---|---|---|---|
| red | `#ff91203c` | `#ffa5485f` | `#fff09bab` | `#fff3adba` |
| green | `#ff166147` | `#ff407d68` | `#ff96c7b2` | `#ffa9d1c0` |
| yellow | `#ff865d23` | `#ff9c7a4b` | `#ffd8b380` | `#ffdfc197` |
| blue | `#ff003ab1` | `#ff2e5dbf` | `#ff6194fe` | `#ff7da7fe` |
| magenta | `#ff6f6895` | `#ff8983a8` | `#ffc3bde5` | `#ffcec9ea` |
| cyan | `#ff355962` | `#ff59777e` | `#ffa6c1c8` | `#ffb6ccd2` |

| Neutral | Desktop rung | Light | Dark |
|---|---|---|---|
| `black` | zinc-700 / zinc-300 | `#ff424242` | `#ffd5d5d5` |
| `white` | zinc-600 / zinc-200 | `#ff555555` | `#ffe5e5e5` |
| `bright-white` | zinc-500 / zinc-100 | `#ff686868` | `#fff4f4f4` |
| `bright-black` | zinc-500 / zinc-400 | `#ff7b7b7b` | `#ffa2a2a2` |

As painted on `widgetSurface`, across all eleven presets in both modes: the
weakest rung is `bright-magenta` at 3.17:1 (solarized light), `black` bottoms out
at 8.91:1 and `bright-black` at 3.75:1 (both solarized light), and the closest
two inks a reader has to tell apart are dark `cyan` and `bright-cyan` at
dE76 4.66 — the smallest bright step there is.

No preset was touched: `BuiltinThemes.ALL` is unchanged, registry order
included. The sixteen inks are a mode-level derivation in `HermesTokens.from`,
grouped as `HermesTokens.ansi` (`HermesAnsiInk`).

**D0 — Two hardenings where upstream's behaviour is a visible bug on a phone.**

`applySgr` consumes the arguments of a `48` (background) selector as well as a
`38`. Upstream has no `48` arm, so `ESC[48;5;1m` leaves a stray `1` to be read
as bold-on and `ESC[48;2;0;0;255m` leaves a `0` to be read as a full reset —
mid-line, in exactly the `bat` / `delta` / `fzf` / CI output the terminal path
exists for. Nothing is painted for a background either way; the difference is
whether the rest of the line survives with the style it was given.

`parseAnsi` accumulates each run in a `StringBuilder` rather than rebuilding an
immutable segment on every merge. Upstream's array-of-objects shape is fine in
a browser at Desktop's payload sizes; here it made the parser quadratic — a
1 MB colourised log spent hundreds of milliseconds purely re-copying, and the
`MAX_SEGMENTS` cap made it worse rather than better, because past the cap every
flush merges. The file claims to be linear, so it has to be.

**D2 — A truncated or malformed escape is dropped, not printed.**
Desktop matches escapes with two global regexes, so `ESC [ 3 1` with no final
byte matches nothing and `[31` survives into the rendered text (the ESC itself
is invisible). On a streaming transcript the tail of a delta looks like that
routinely, and printing it is precisely the literal garbage this slice exists to
remove. The Android parser consumes the malformed run and paints none of it as
text. "Consumes" is literal: it consumes only what it scanned, so a cut-off
sequence never swallows the log behind it, and the bytes past the scan are
ordinary text. `ESC[3<U+D800>1mx` is the case that shows the difference: the
lone surrogate is not a parameter, intermediate or final byte, so the scan stops
on it and everything from it onward — the surrogate, `1`, `m`, `x` — is ordinary
text. Nothing of the *escape* is painted; the tail of the line survives. A bare `ESC` inside an OSC payload aborts that payload and is handed
back, the way a terminal behaves.

**D3 — `memory` rows take a database glyph, not a brain.**
Desktop's `brain` is a Phosphor path with no Codicon equivalent at 0.0.45, and
Desktop's own fallback (`<Codicon name="brain"/>`) would render nothing here.
`database` is the nearest "this was stored" glyph in the same family.

**D4 — `ToolStatus` has a fifth rung, `Stopped`.**
Desktop's thread cannot show a stopped tool: a user stop ends the turn and the
row is left as it was. Android models `ToolState.Stopped` and already paints it,
so dropping it to match Desktop's four would lose state the transcript holds.

**D5 — A non-JSON result is shown rather than discarded.**
Desktop's `part.result` is already decoded, so `firstStringField` on a bare
string is simply empty and `toolDetailText` returns `''` for a terminal row.
Android's `resultText` really can be a bare string, and a backend that hands
back plain text still produced output. When the result text is not JSON it is
used as the merged output.

**D5b — A failed command still shows its output.**
Upstream's `view.status === 'error'` ternary (`fallback.tsx:636-654`)
short-circuits the stream branch, so an errored row shows only the error
sentence. Android paints the error sentence *and* the streams below it. A failed
command is the one row where its output matters most, and leaving it out meant
Copy handed over text the screen had refused to paint.

**D6 — Errors render as one destructive block, not a summary plus a body.**
Desktop splits the error detail into a bold summary line and a monospace body
(`fallback.tsx:636-653`). Android paints `view.detail` as one destructive
monospace block under the `ERROR DETAILS` label. The split is cosmetic and the
splitting rule lives in a helper this slice did not need.

**D7 — The non-code detail form is monospace, not markdown.**
Desktop chooses between a `<pre>` and `CompactMarkdown` per tool
(`fallback.tsx:693-702`). Android renders every detail block as code. Markdown
inside a tool payload is rare, and the `renderDetailAsCode` predicate is part of
the title/subtitle machinery that is not ported here (D8).

**D8 — Title, subtitle and `titleAction` keep Android's grammar.**
`dynamicTitle` and `toolSubtitle` are roughly 250 lines of per-tool phrasing.
Issue #71's design-language note names tone, icon, status glyph, count and
duration labels, and the stdout/stderr sections as the load-bearing contract;
the title grammar Android already ships (`displayTitle()` in `Transcript.kt`)
is left alone, and the shimmer `titleAction` has no mobile equivalent yet.

**D9 — Search hits are not links.**
Rendered as title, URL and snippet in quiet structured text. Desktop's
`PrettyLink` opens an external browser; adding that from a transcript row is its
own surface and is not in this slice's acceptance.

**D10 — Error and warning differ by glyph, not only by tint.**
Desktop paints both with the same `AlertCircle`, separated only by colour —
`text-destructive` for error, amber-600/400 for warning (`fallback.tsx:194-202`).
Android gives them two Codicons, `error` (`U+EA87`) and `warning` (`U+EA6C`),
each in the matching ink. Colour alone is not a safe carrier on a phone: the
glyph is 14 sp, it is often the only difference between "this failed" and "this
recovered", and an eleven-preset theme registry cannot promise the two tints
stay far apart on every page. The spoken labels are Desktop's either way.

**D11 — A finished tool's glyph is one rung louder than Desktop's.**
Desktop's success is silent — `leadingStatus` returns nothing (`fallback.tsx:247-253`),
so the tool's own icon shows in `--ui-text-tertiary` (`:234-235`). Android
keeps the tool glyph too, but tints it `scaffoldText` rather than `textTertiary`,
because every other glyph in this transcript row already sits at that weight and
matching Desktop here would make the finished row's icon quieter than the text
beside it. The section labels above payload blocks *are* `textTertiary`, which is
where `fallback.tsx:92` actually applies.

**D12 — The display line cap counts newlines, not terminal lines.**
`clampForDisplay`'s 200-line cap splits on `\n`. Carriage-return progress output
— `npm`, `pip`, a `curl` progress bar — rewrites one screen line over and over
without ever emitting a newline, so the whole run is one "line" to the cap and
only the 20,000-character cap bites. That is the same shape upstream has, which
counts characters only; nothing regresses, but the cap is not a promise about
what a terminal would have shown. Collapsing `\r` runs to their final state is
terminal emulation, and this slice does not emulate a terminal.

## Executable evidence

| Claim | Test |
|---|---|
| The ANSI rule set matches Desktop's, case for case | `AnsiTest` — every case in `lib/ansi.test.ts` ported, plus the background-selector, device-control-string and OSC-abort cases upstream has no fixtures for |
| The parser is linear, not merely terminating | `AnsiTest.merging a long run stays linear rather than quadratic` — doubling a 1 MB same-style payload must not more than triple the time |
| The parser is total and bounded against hostile bytes | `AnsiTest`: truncated escape, unterminated CSI, unterminated OSC, a 15-digit repeat count, a 200,000-character parameter run, lone surrogates, 1 MB of adversarial bytes under 5 s, 500,000 bare escapes, and a segment cap that drops no characters |
| `ToolView` matches `buildToolView` field for field | `ToolViewTest` — streams, prompt line, exit code, the `TOOL_META` table entry for entry, the prefix rule, status mapping, count nouns and pluralisation, search-hit extraction and the six-hit cap, and every `toolCopyPayload` branch |
| The display clamp truncates and Copy does not | `ToolViewTest` (character cap, line cap, message) and `ToolRowFidelityTest` (clipboard carries the tail the screen dropped) |
| ANSI reaches the screen as colour, not as `[31m` | `ToolRowFidelityTest` |
| stdout and stderr are two labelled sections, and a lone stdout has no label | `ToolRowFidelityTest` |
| `$ cmd` and the exit code render, and the `$` is not spoken | `ToolRowFidelityTest` |
| Web-search results render as hits, never raw JSON | `ToolRowFidelityTest` |
| The status glyph vocabulary is spoken on all five rungs | `ToolRowFidelityTest` |
| A failed command still shows its output | `ToolRowFidelityTest.a failed command still shows the output it produced` |
| The copy confirmation survives a streamed delta | `ToolRowFidelityTest.the copy confirmation survives a streamed delta` |
| The Copy control meets the 48 dp floor and names what it copies | `ToolRowFidelityTest` |
| Tail-follow still only follows a reader who is already at the bottom | `TranscriptFollowTest` — two cases with a *growing tool payload* as the tail |
| The ANSI ladder is the documented derivation, in both modes, for every preset | `ThemeSemanticParityTest` — the hue value table, the diff-token identity, and the four fixed neutral rungs |
| Every ANSI ink is legible **as painted**, and no two are one colour to a reader | `ThemeSemanticParityTest.every ansi ink is distinct and readable as painted on the tool surface` — a 3.0:1 floor on `ink.over(widgetSurface)`, a dE76 3.0 floor between all 120 pairs, and an opacity check so no future rung can measure one colour and paint another. Iterates all eleven presets × both modes and names every failing (preset, mode, rung, ratio) |
| A malformed CSI gives back the bytes it did not scan | `AnsiTest.a malformed csi gives the bytes it did not scan back as text` — D2's `ESC[3<U+D800>1mx` |
| Every ANSI ink resolves for every preset in both modes | `ThemeParityTest` — the completeness walk now reaches nested token groups, with a case asserting it does |

Mutation-checked four times.

Reinstating the quadratic merge — rebuilding the open run's text on every flush
instead of appending to it — turns
`AnsiTest.merging a long run stays linear rather than quadratic` red.

Making `scanControlSequence` return the end of the input instead of the position
it stopped at for a malformed sequence — undoing exactly the hardening D2
describes — turns
`AnsiTest.a megabyte of hostile bytes terminates quickly and renders everything`
red, because one malformed CSI then swallows every line behind it.

Reverting the legibility floor to `contrastRatio(ink, widgetSurface)` — the
un-composited form — while putting `brightBlack` back to `textQuaternary` turns
`ThemeSemanticParityTest.every ansi ink is distinct and readable as painted on
the tool surface` **green** on the legibility assertion. That is the bug: the
floor was measuring a colour the screen never shows.

Restoring the composite with the same `brightBlack` reds it with the numbers
this rewrite exists to remove — `nous/light 2.17:1`, `everforest/light 1.65:1`,
ten light presets named — and the opacity check names the translucent rung.

All four restored; the suite is green again.

No device capture is claimed here. Everything on this page is decided offline
and asserted by the tests above; the gesture arbitration that needs a physical
device is S35's, not this slice's.

## Inline diff (S34)

A file-edit tool row shows the diff the Gateway rendered, not a diff this app
computed. `tool.complete` carries `inline_diff` as the joined output of Hermes'
*terminal* renderer (`tui_gateway/tool_progress.py:233-237`), so it arrives with
chrome written for a TTY. The ESC byte is invisible in Compose, so painting that
payload raw put `[38;2;125;187;255m` and a `┊ review diff` header on the screen,
with `+0  −0` in the row's header and no tint on any line.

### Source contract

| Contract | Source at the pin | Android |
|---|---|---|
| The payload | `tui_gateway/tool_progress.py:233-237` — `inline_diff` is `"\n".join(rendered)` from `render_edit_diff_with_delta` | `ToolActivity.inlineDiff`, unchanged on the wire |
| The banner | `agent/display.py:656-664` — the first line is exactly `  ┊ review diff`; the TUI may wrap it in `ESC[33m…ESC[0m` (`ui-tui/src/__tests__/createGatewayEventHandler.test.ts:758`) | Removed by `stripInlineDiffChrome` |
| The colours | `agent/display.py:63-81,84-105` — truecolour `ESC[38;2;r;g;bm`, plus a tinted `ESC[48;2;r;g;bm` background on `+`/`-` rows | Removed by `stripAnsi`; the tint is re-derived from the theme's own diff tokens |
| The header pair | `agent/display.py:672-690` — `--- `/`+++ ` collapse into one `a/x → b/x` arrow line | Skipped by `stripDiffFileHeaders`; `filePath()` reads the `b/` side when the tool args carry no `path` |
| The cap trailer | `agent/display.py:703-731` — `… omitted N diff line(s)[ across M additional file(s)/section(s)]` in the hunk colour | Kept, as a context line: it is information about the payload, not chrome |
| Chrome strip | `assistant-ui/tool/fallback-model/index.ts:771-781` — `stripAnsi`, then `/^\s*┊\s*review diff\s*\n/i`, then `trim` | `stripInlineDiffChrome`, the same three steps in the same order; step one is wider than the call site's SGR-only regex (see Divergences) |
| Line stats | `fallback-model/index.ts:46-59` — `+` that is not `+++`, `-` that is not `---`, counted on the cleaned diff | `countDiffLineStats`, identical |
| Header slots | `fallback.tsx:481-486,585-594` — `+N` and `−N` (U+2212) in their own slots, each only when its count is positive, and `:596` — a file edit shows no duration | Identical, with the ink from `tokens.diffAdded` / `tokens.diffRemoved` (see Divergences) |
| Header zone | `chat/diff-lines.tsx:96-134` — `diff --git`, `index `, `--- `, `+++ `, similarity/rename/new-file/deleted-file, blank lines and the arrow line, up to the first `@@` | `stripDiffFileHeaders`, prefix for prefix |
| Body parse | `chat/diff-lines.tsx:69-89,135-215` — kind per line, gutter marker stripped, `@@` headers dropped, `\` lines skipped, one blank context line between hunks, and a whole-payload fallback when nothing parses | `parseDiff` → `List<DiffLine>` |
| Line paint | `chat/diff-lines.tsx:42-52,279-291` — `border-l-2` in `--ui-diff-*-border`, background, ink; a context row transparent on both and inheriting `--ui-text-secondary`; an empty line still paints a row | 2 dp gutter in `tokens.diffAdded` / `diffRemoved`, `diffAddedBackground` / `diffRemovedBackground`, `diffAddedForeground` / `diffRemovedForeground`; `Color.Transparent` behind `tokens.textSecondary`; empty text paints a space |
| Copy | `fallback-model/index.ts:1254-1257` — `copy.file` is `view.inlineDiff`, which `fallback.tsx:375` already chrome-stripped | `ToolCopyControl` hands over the cleaned diff, file headers still present, no escapes |
| Path | `fallback-model/index.ts:61-67` — args `path`/`file`/`filepath`, then result `path`/`file`/`filepath`/`resolved_path`, then `htmlPathFromInlineDiff` (`:783-795`), which mines the diff for an `.htm`/`.html` name **only** | The same two field arms, in the same key order. The diff arm is **not** the same and is ledgered as drift below: it reads any `+++ b/` or `a/x → b/x` path, because the Gateway's rendered diff carries no `+++` line and a diff-emitting tool whose args carry no `path` would otherwise leave the row reading the literal fallback. `/dev/null` still yields nothing |

### What landed

`data/markdown/Ansi.kt` gained `stripAnsi`, a port of `ansi.ts:177-186` on the
scanner `parseAnsi` already uses, so the two agree byte for byte on what an
escape is — including this port's two deliberate additions over upstream's
regexes: a truncated escape is dropped rather than leaked, and OSC/DCS payloads
are consumed rather than printed.

`data/markdown/InlineDiff.kt` is new and Compose-free: `stripInlineDiffChrome`,
`stripDiffFileHeaders`, `parseDiff` and `countDiffLineStats`, each citing the
Desktop lines it mirrors.

`InlineDiffPanel` now reads the cleaned diff for everything — header stats,
body, path and Copy — and paints each line by kind rather than by marker, since
the marker is stripped before the line reaches the screen.

### Executable evidence

| Claim | Test |
|---|---|
| The strip removes every escape shape, agrees with the parser on what survives, and a truncated escape leaks nothing | `AnsiTest` — the eight `stripAnsi` cases, incl. the 1 MB budget case |
| Desktop's own two fixtures still hold | `InlineDiffTest.a clean unified diff passes through the chrome strip unchanged`, `.counts added and removed lines` |
| The header zone is skipped, including the arrow line and an absolute path, and stops at the first content line | `InlineDiffTest.a git preamble is skipped up to the first hunk`, `.the arrow line the gateway emits instead of a header pair is skipped`, `.a diff line that happens to contain an arrow is not a header`, `.the header zone stops at the first line that is not a header` |
| A gateway-shaped payload keeps no escape, no SGR payload, no banner, no `@@` and no arrow line; it counts +1/−1 and parses to exactly four lines, the cap trailer among them as context | `InlineDiffTest.no escape byte and no sgr payload survives the gateway diff`, `.the gateway diff counts one addition and one removal`, `.the gateway diff parses to its body with the markers and the noise gone` |
| A yellow-wrapped banner is still recognised | `InlineDiffTest.a banner the tui wrapped in yellow is still recognised` |
| Hunk rules: blank separator, `\` skipped, no-hunk fallback, malformed header | `InlineDiffTest.two hunks are separated by one blank context line`, `.git's no-newline marker is dropped`, `.a payload with no hunk header falls back to classifying every line`, `.an unparseable hunk header closes the hunk rather than opening one` |
| 1 MB of rendered diff cleans and parses inside a frame budget | `InlineDiffTest.a megabyte of rendered diff is cleaned and parsed well inside a frame budget` |
| A 32 KB line of arrows is refused as a header on length, before the quadratic regex sees it, and a real header at the cap still passes | `InlineDiffTest.a payload-sized line of arrows costs nothing to reject as a header`, `.a real arrow header is still recognised at the length cap` |
| Only a *first-line* banner is chrome, and `[38;2;…m` with no escape byte in front of it is content | `InlineDiffTest.a review diff banner below the first line is content, not chrome`, `.sgr parameter bytes with no escape in front of them are content`, `ToolRowFidelityTest.sgr parameter bytes with no escape byte in front of them are painted` |
| The wider strip's one visible consequence is pinned: an unterminated OSC drops the remainder, and the header count still agrees with the body because both read `cleaned` | `InlineDiffTest.an unterminated osc swallows the rest of the diff, and both halves agree` |
| A 10,000-line diff composes a bounded number of rows, the notice says so, and Copy still carries the tail | `ToolRowFidelityTest.a ten thousand line diff composes a bounded number of rows` (counts the `inline-diff-line-*` nodes actually in the tree), `ToolViewTest.a ten thousand line diff paints a bounded number of rows` |
| A cut landing inside a `@@` header does not silently swallow the rest of the body and the notice with it | `ToolViewTest.a diff cut mid hunk header still paints its truncation notice`, `.an unclamped diff paints exactly what it parses, with no notice row` |
| The panel paints no chrome, strips the gutter markers, shows `+1`/`−1` and no duration, drops a zero count, and Copy hands over the cleaned diff | `ToolRowFidelityTest.a gateway rendered diff paints its lines without the tty chrome`, `.the diff header counts the change and shows no duration`, `.a diff with only additions shows no removal count`, `.an inline diff carries a live Copy control that hands over the whole diff` |
| Each kind paints its own gutter and tint, a context row paints neither, and neither is the old lookalike semantic | `InlineDiffPanelInkTest` (4 cases, pixels read off a native-canvas draw) |
| The panel is the thing reading those tokens, the right way round | `scripts/check-repo-invariants.sh` check 11, mutation-checked by transposing the add/remove tints |

No device capture is claimed here. The rendered side-by-side is owed by #201.

## Deferred, with owners

| Deferred | Why | Lands in |
|---|---|---|
| Long-press selection of tool payloads, and the select / horizontal-scroll / collapse-tap arbitration | Needs real-device evidence | #71 S35 |
| `dynamicTitle` / `toolSubtitle` / `titleAction` | D8 | Not scheduled |
| Inline image results (`imageUrl`) and artifact preview targets (`previewTarget`) | Explicit non-goals of #71 | Not scheduled |
| Syntax highlighting | Explicit non-goal of #71: a size and cold-start decision of its own | Not scheduled |
| Tappable search-hit links | D9 | Not scheduled |
| Technical-mode raw args/result disclosure (`fallback.tsx:114-139`) | Android has no tool view mode toggle | Not scheduled |

## Divergences

Classified for `scripts/check-parity-evidence.py`; D0-D12 above carry the
argument and the citations.

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Each payload sits in a `max-h-16`/`max-h-20` box that scrolls internally (`terminal-output.tsx:14,45-58`) | mobile-adaptation | Rendered inline, clamped by lines and characters; the transcript's own follow discipline supplies the tail rule | A nested vertical scroller inside a `LazyColumn` competes with the transcript's drag on touch — the gesture ambiguity #56 already deferred once |
| `clampForDisplay` caps at 20,000 characters | mobile-adaptation | The same 20,000-character cap plus a 200-line cap, whichever bites first | A phone has less room and less memory than a window; the cut lands past the last kept newline and a truncation smaller than its own notice is not announced |
| Copy appears on hover, absolutely positioned over the payload | mobile-adaptation | Always mounted, right-aligned above the payload, scaffold-meta ink, 48 dp target | Touch has no hover; the precedent `ReplyActions` and `CodingStatusRow` already set |
| Long lines wrap (`whitespace-pre-wrap wrap-anywhere`) | mobile-adaptation | Horizontal scroll per payload block | A 360 dp column turns a wrapped log line into a wall |
| `GlyphSpinner` replaces the tool icon while running (`fallback.tsx:184-192`) | mobile-adaptation | The tool glyph tinted with the accent, beside the live elapsed timer | The disclosure row has no spinner slot, and #71 says to extend that row rather than replace it; the timer already says "running" |
| Status glyphs are aria-labelled Running / Error / Recovered / Done | mobile-adaptation | The same four words lower-cased into `"Tool ⟨title⟩, ⟨state⟩"`, plus `stopped` | The Android row is one merged semantics node, so the state has to arrive inside the sentence |
| Search hit titles are `PrettyLink`s that open externally (D9) | drift | Title, URL and snippet as quiet structured text | Leaving a transcript for a browser is a new surface with its own consent question; not scheduled under #71 |
| The tool card's diff body paints **every** line inside a `max-h-[12rem]` box that scrolls internally (`diff-lines.tsx:66-67,583-641`) | mobile-adaptation | Rendered inline, clamped before parsing — the same 20,000-character / 200-line cut every other tool payload takes — so the row count is bounded before Compose measures anything | A nested vertical scroller inside a `LazyColumn` competes with the transcript's own drag on touch, the gesture ambiguity #56 already deferred once; the clamp bounds what is *painted*, and Copy still hands over the whole cleaned diff. There is no Desktop windowing here to port: the card passes neither `showLineNumbers` nor `virtualized` (`fallback.tsx:637`), so `windowed` is false (`diff-lines.tsx:609`) and the compact body is a plain `DiffBody`. The virtualised chunk list (`PREVIEW_*` at `:56-58`, `useFixedRowWindow` at `:598-603`) belongs to the preview pane |
| Header stats use one-off Tailwind rungs — `text-emerald-600 dark:text-emerald-400` and `text-rose-600 dark:text-rose-400` (`fallback.tsx:586-593`) | drift | `tokens.diffAdded` / `tokens.diffRemoved`, the same seeds the body's gutter uses | Those two rungs are outside Desktop's own token system: they are the only diff colours on the page that are *not* `--ui-diff-*`, and this app has no raw colours by rule (`AGENTS.md`). The seeds are the tokenised nearest colour, so the stat and the gutter beneath it now agree — which Desktop's do not. The visible delta is small and deliberate; recorded as drift rather than adaptation because nothing about a phone forced it. Owner #201 renders it side by side |
| The call site's `stripAnsi` is SGR-only — one `ESC[…m` regex (`fallback-model/index.ts:771-773`) | mobile-adaptation | `Ansi.kt`'s `stripAnsi`, shared with `parseAnsi`: every CSI, the two-byte escapes, and OSC/DCS/SOS/PM/APC string payloads | One scanner means the strip and the parser can never disagree about what an escape *is*, which is the disagreement that made the leak possible. The one visible consequence is bounded and pinned by a test: an **unterminated** OSC has no terminator to stop at, so it drops the remainder of the diff, where Desktop would leave `ESC]junk` and the lines behind it on the page. The header count cannot drift from the body under it either way, because both read the same `cleaned` string |
| `isArrowHeaderLine` runs `/^\S.*→\s*\S+$/` on a line of any length (`diff-lines.tsx:110`) | mobile-adaptation | The same regex, behind a 1,024-character refusal | That pattern backtracks quadratically: on a 32 KB line of arrows — the ingest cap, `GatewaySessionRepository`'s `MAX_TOOL_PAYLOAD` — it costs seconds, and here it runs inside a `remember {}` on the composition thread rather than in a browser worker. `AGENTS.md` treats tool output as untrusted, so the input's size has to bound the work. The cap refuses nothing a real header could carry: the line is two paths and an arrow, and `PATH_MAX` is 4096 |
| The diff is mined for a path only when it names an `.htm`/`.html` file, and only after both the args and the result have been tried (`fallback-model/index.ts:61-67,783-795`) | drift | The args and the result are read in Desktop's key order; the diff arm then accepts **any** `+++ b/` or `a/x → b/x` path | Desktop's arm exists to find an artifact to preview; this one exists to title the row. The Gateway's rendered `inline_diff` has no `+++` line, so for a diff-emitting tool whose args carry no `path` — `skill_manage` is one, `agent/display.py:651` lists it — Desktop's arm would leave the row on its literal fallback where the diff plainly names the file. Recorded as drift, not adaptation: nothing about a phone forced it. Owner #201 renders both headers side by side |
| Syntax-highlighted diff bodies (`SyntaxDiff`, `diff-lines.tsx:469-487`) | omission | Absent | non-goal: #71 named syntax highlighting a non-goal of that issue, being a size and cold-start decision of its own; nothing about the platform refuses it |
| Long-press selection of tool payloads, and the select / horizontal-scroll / collapse-tap arbitration | drift | Not selectable | Needs real-device evidence; #71 S35, tracked as #56 |
| A Copy control on an inline diff, labelled `copy.file` — `toolCopyPayload` resolves a file-edit tool holding an inline diff to `Copy file` (`fallback-model/index.ts:1254-1257`, `en.ts:3644`) rather than the generic `common.copy` | mobile-adaptation | `Copy file`, right-aligned above the diff body, now live in the slot `ToolCopyControl` occupies for an ordinary payload; the cleaned diff is copied via `ToolCopyAction(COPY_DIFF, "File copied", cleaned)` | A phone has no hover, so the control is always mounted in the slot Desktop reveals on hover (the precedent every `ToolCopyControl` follows). The payload is Desktop's own: `view.inlineDiff`, which `fallback.tsx:375` chrome-stripped before `copy.file` was built — escapes and banner gone, file headers still present. What is *painted* is stripped further, since `parseDiff` drops the header zone, the `@@` headers and the gutter markers, so the clipboard deliberately carries more than the screen does. `ToolRowFidelityTest` asserts the label, the 48 dp floor, the placement above the body, and that the clipboard receives exactly that chrome-stripped text with no escape byte in it |
| `titleAction` (D8) | omission | Absent | deferred: #71 — #71 owns this surface and its title grammar, but its acceptance never names `titleAction` and no slice is scheduled for it, so the marker records an owner rather than a plan and `pill-owed:` would overstate it. The control also has no host here: it hangs off Desktop's tool *title* row, and this app's disclosure row has no action slot, so a chip would have to invent the surface it marks. That is what separates it from the inline-diff Copy above, which ships live — there the slot is Desktop's own, a `CopyButton` over the payload, and `ToolCopyControl` already occupies it for an ordinary payload. The retarget off #101 is an amendment to that issue's acceptance and is recorded in the PR body |
| `dynamicTitle` / `toolSubtitle` (D8) | omission | Absent | deferred: #71 — title text, not a control; Android's title grammar is the disclosure row's |
| Technical-mode raw args/result disclosure (`fallback.tsx:114-139`) | omission | Absent | non-goal: Android has no tool view mode toggle to hang it from |
| Inline image results (`imageUrl`) and artifact preview targets (`previewTarget`) | omission | Absent | out-of-scope: #71 excluded both from its own scope; neither is refused by the platform |

## Visual report

- pending: #201

Every colour on this page is derived and asserted offline by the tests above.
The rendered side-by-side — ANSI ladder, status glyphs, truncation notice and
the clamped payload at phone width — is owed by #71's device pass. #201 owes the
inline diff panel specifically: a file-edit row with a gateway-rendered diff,
light and dark, Desktop at the pin beside Android.
