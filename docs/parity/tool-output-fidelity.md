# Tool output fidelity

What an expanded tool row shows, where each field comes from on Desktop, and
every place the Android port deliberately says something different.

Scope is issue #71 slice S33: ANSI parsing, stdout/stderr sections, the exit
code, the `$ cmd` prompt line, structured web-search hits, the tool-tone icon
set and the status glyph vocabulary. Diff windowing is S34; long-press
selection of tool output is S35. Both have rows in the deferral table.

## Pin and source contract

Desktop authority is `NousResearch/hermes-agent` at
`f82f2dbabd9e66b714f2b4f8a40447fe0c13e732` (read-only checkout; read with
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
| `apps/desktop/src/i18n/en.ts:3152-3155` | The status glyph vocabulary: Running / Error / Recovered / Done |

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
| — | `toolCopyPayload(part, view)` (`fallback.tsx:599-609`) | Carried on the view as `copy: ToolCopyAction?`, so the row's control and its label come from the same projection. The `isFileEditTool` branch is dropped with `inlineDiff`, and Copy for a diff is S35's |
| — | `stripAnsi` (`ansi.ts:177-186`) | **Not ported.** Nothing here renders escape-bearing output as plain text, and an unused export with no caller is a claim the next reader would trust |

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
| `globe` | `U+EB01` | `HermesIcon.Globe` (added) |
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
| `clampForDisplay` caps at 20,000 characters | A phone has less room and less memory than a window | Same 20,000-character cap, plus a 200-line cap; whichever bites first, with Desktop's truncation sentence. The cut lands *past* the last kept newline, and a truncation smaller than the notice itself is not announced — otherwise a complete 200-line log would gain sixty characters to say it lost one |
| Copy appears on hover, absolutely positioned over the payload | A phone has no hover | Always mounted, right-aligned above the payload, in the scaffold-meta ink, 48 dp target — the precedent `ReplyActions` and `CodingStatusRow` already set |
| Long lines wrap (`whitespace-pre-wrap wrap-anywhere`) in the tool card | A 360 dp column turns a wrapped log line into a wall | Horizontal scroll per payload block, which is the grammar `ToolPayload` already used and what S35's gesture work is scoped against |
| Search hit titles are `PrettyLink`s that open externally | Opening an external browser from a transcript is a new surface with its own consent question | Title, URL and snippet render as quiet structured text; the link affordance is deferred (see below) |
| A status glyph pre-empts the tool icon; success is silent (`fallback.tsx:212-254`) | — | Same rule: error and warning take the alert glyph, success keeps the tool glyph |
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
| bright hue | that same knob applied a second time | Intensity on a terminal is prominence against the page, so bright goes darker still on a light page and lighter still on a dark one |
| `black` / `bright-black` | `textTertiary` / `textQuaternary` | `ansi.ts:145-147` refuses pure `#000`; the text ladder is already that grey ramp |
| `white` / `bright-white` | `textSecondary` / `textPrimary` | same |

Consequences, stated plainly: the six hues are *fixed per mode*, exactly as
Desktop's are and as the diff palette and inline code already are, so a build
log reads the same in all eleven presets; the four neutrals are the only ANSI
inks that follow the preset. ANSI green and ANSI red come out identical to
`diffAddedForeground` / `diffRemovedForeground`, which is asserted rather than
incidental — a terminal's green and an inline diff's green must not become two
colours.

The obvious simpler rule — bright = the undiluted seed, which is Desktop's diff
*border* rung — was tried and rejected: `--ui-purple` is `#9e94d5`, which is
2.65:1 on a light tool surface. The legibility floor in
`ThemeSemanticParityTest` caught it.

Resolved values, `#aarrggbb`:

| Hue | Light normal | Light bright | Dark normal | Dark bright |
|---|---|---|---|---|
| red | `#ff91203c` | `#ff66162a` | `#fff09bab` | `#fff6c1cb` |
| green | `#ff166147` | `#ff0f4432` | `#ff96c7b2` | `#ffbedccf` |
| yellow | `#ff865d23` | `#ff5e4119` | `#ffd8b380` | `#ffe7d0b0` |
| blue | `#ff003ab1` | `#ff00297c` | `#ff6194fe` | `#ff9dbdfe` |
| magenta | `#ff6f6895` | `#ff4e4968` | `#ffc3bde5` | `#ffdad6ef` |
| cyan | `#ff355962` | `#ff253e45` | `#ffa6c1c8` | `#ffc8d9dd` |

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
remove. The Android parser consumes the malformed run and paints nothing. It
consumes only what it scanned, so a cut-off sequence never swallows the log
behind it. A bare `ESC` inside an OSC payload aborts that payload and is handed
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
| The ANSI ladder is the documented derivation, in both modes, for every preset | `ThemeSemanticParityTest` — the value table, the diff-token identity, the neutral ladder, distinctness, and a 3.0:1 floor on `widgetSurface` |
| Every ANSI ink resolves for every preset in both modes | `ThemeParityTest` — the completeness walk now reaches nested token groups, with a case asserting it does |

Mutation-checked twice.

Reinstating the quadratic merge — rebuilding the open run's text on every flush
instead of appending to it — turns
`AnsiTest.merging a long run stays linear rather than quadratic` red.

Making `scanControlSequence` return the end of the input instead of the position
it stopped at for a malformed sequence — undoing exactly the hardening D2
describes — turns
`AnsiTest.a megabyte of hostile bytes terminates quickly and renders everything`
red, because one malformed CSI then swallows every line behind it.

Both restored; the suite is green again.

No device capture is claimed here. Everything on this page is decided offline
and asserted by the tests above; the gesture arbitration that needs a physical
device is S35's, not this slice's.

## Deferred, with owners

| Deferred | Why | Lands in |
|---|---|---|
| Windowed diff rendering, `+/-` gutters and `@@` headers stripped, the 2 px gutter accent | Its own slice | #71 S34 |
| Long-press selection of tool payloads, and the select / horizontal-scroll / collapse-tap arbitration | Needs real-device evidence | #71 S35 |
| A Copy control on an inline diff | `InlineDiffPanel` owns that surface and S35 is where its affordances land | #71 S35 |
| `dynamicTitle` / `toolSubtitle` / `titleAction` | D8 | Not scheduled |
| Inline image results (`imageUrl`) and artifact preview targets (`previewTarget`) | Explicit non-goals of #71 | Not scheduled |
| Syntax highlighting | Explicit non-goal of #71: a size and cold-start decision of its own | Not scheduled |
| Tappable search-hit links | D9 | Not scheduled |
| Technical-mode raw args/result disclosure (`fallback.tsx:114-139`) | Android has no tool view mode toggle | Not scheduled |
