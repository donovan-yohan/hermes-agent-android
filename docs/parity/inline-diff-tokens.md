# Inline diff token parity

How an added or removed line in an inline tool diff is coloured, and why the
colour is derived rather than chosen.

## Pin and source contract

Desktop authority is `NousResearch/hermes-agent` at
`72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd` (read-only checkout; read with
`git -C ~/.hermes/hermes-agent show <sha>:<path>`). The `--ui-diff-*` block and
its `--ui-green`/`--ui-red` seeds are byte-identical at upstream `HEAD`
(`1fe0f2f3ac9748ce799272eb93bee2937b5ab802`, checked 2026-08-26), so the
checkout's drift from the pin does not reach any value on this page.

| Contract | Desktop source | Android port |
|---|---|---|
| Seeds | `apps/desktop/src/styles.css:196,199` (light), `:528-529` (`:root.dark`) | `HermesTokens.diffAdded` / `diffRemoved` — fixed per mode, not per preset |
| Border | `apps/desktop/src/styles.css:222,225` — the border **is** the seed | `HermesTokens.diffAdded` / `diffRemoved`, unchanged |
| Background | `apps/desktop/src/styles.css:223,226` — `color-mix(in srgb, seed 12%, transparent)` | `HermesTokens.diffAddedBackground` / `diffRemovedBackground` via `mixPremultiplied(seed, 12f, Color.Transparent)` |
| Foreground | `apps/desktop/src/styles.css:224,227` (light, 70% toward `#000`), `:531-532` (dark, 62% toward `#fff`) | `HermesTokens.diffAddedForeground` / `diffRemovedForeground` via `mixPremultiplied` on the same knobs |
| Application | `apps/desktop/src/components/chat/diff-lines.tsx:42-52` — `DIFF_KIND_TINT` paints `border-l-2` + background, `DIFF_KIND_TEXT` paints the ink on the colour-only renderer | `InlineDiffPanel` in `ui/chat/Transcript.kt` — colour-only, so both apply. Since #71 S34 the border applies too: a 2 dp left gutter in the seed, transparent on a context row |
| Context line | `diff-lines.tsx:43,49` — transparent border, no tint, inherits `DIFF_BOX_CLASS`'s `--ui-text-secondary` (`:66`) | `Color.Transparent` behind `tokens.textSecondary` |

## Resolved values

Both seeds are fixed per mode, so — like inline code and the selection
highlight — a diff reads identically in all eleven presets. Quantised to
Compose's 8-bit sRGB channels, `#aarrggbb`:

| Token | Light | Dark |
|---|---|---|
| `--ui-diff-add-border` | `#ff1f8a65` | `#ff55a583` |
| `--ui-diff-add-background` | `#1f1f8a65` | `#1f55a583` |
| `--ui-diff-add-foreground` | `#ff166147` | `#ff96c7b2` |
| `--ui-diff-remove-border` | `#ffcf2d56` | `#ffe75e78` |
| `--ui-diff-remove-background` | `#1fcf2d56` | `#1fe75e78` |
| `--ui-diff-remove-foreground` | `#ff91203c` | `#fff09bab` |

## The bug this closes

`InlineDiffPanel` tinted added lines with `statusUnread` and removed lines with
`destructive`. Those are a different semantic that merely happened to be green
and red: `statusUnread` is the fixed emerald-500 unread-session dot
(`session-status-dot.tsx:63-65`), and `destructive` is the palette's
destructive-action colour, which moves per preset. So an inline diff ignored the
theme's green in every skin, and its "removed" red tracked whatever red the
palette used for destructive buttons.

Under `AGENTS.md`'s rule — adding a theme must be a data edit, and if a chat
component has to change, that is the bug — the component *was* the bug. Desktop
names no diff colour of its own either; it derives all six values from the two
seeds. The port mirrors that derivation rather than introducing raw colours, so
the fix added four derived tokens to `HermesTokens.from` (a mode-level
derivation, alongside the two seeds already there) and touched no preset:
`BuiltinThemes.ALL` is unchanged, registry order included.

Deliberate visual delta: an added line's ink moves from emerald-500 to the
theme's green mixed toward the page, and its tint from 10% to Desktop's 12%.
This is the correction, not a regression.

## Executable evidence

- `ThemeSemanticParityTest.the diff palette derives from desktop's green and red in each mode`
  asserts all six values in both modes for every preset, and separately asserts
  the diff seeds are **not** equal to `statusUnread` / `destructive`, so a
  silent revert is a red test rather than a subtle drift.
- `ThemeParityTest.every preset resolves a complete token set in both modes`
  reflects over every `Color` property and rejects a fully transparent one, so
  the four new tokens are covered for all eleven presets in both modes without
  the test having to name them.
- `scripts/check-repo-invariants.sh` check 11 scans the `InlineDiffPanel` body.
  It fails if `statusUnread` or `destructive` reappears there, and it fails
  unless each `DiffKind` is paired with its *own* gutter seed, tint and ink — a
  presence-only check would let a transposed add/remove pair through. It keys on
  the kind rather than on a `+`/`-` marker since #71 S34, because the marker is
  stripped before a line is painted. Mutation-checked: red when the add and
  remove tints are swapped, green again when they are restored.
- `InlineDiffPanelInkTest` reads the painted pixels back off a native-canvas
  draw: each kind's gutter is its seed, each kind's tint is its token
  composited over what the row sits on, a context row paints neither, and
  neither gutter is `statusUnread` or `destructive`.
- `scripts/check-theme-parity.py --upstream ~/.hermes/hermes-agent` is clean
  (11 presets, same order); it diffs the preset registry, which this change does
  not touch.

## Deferred, with owners

| Deferred | Why | Lands in |
|---|---|---|
| Syntax-highlighted diffs (`SyntaxDiff`, `diff-lines.tsx:468-479`) | Explicit non-goal of #71: a size/cold-start decision of its own | Not scheduled |
| Long-press selection of diff text | Needs real-device gesture arbitration evidence | #71 S35 |

No device capture is claimed here: every value on this page is decided offline
by the derivation and asserted by the tests above.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Large diffs render windowed inside a `max-h-[12rem]` scroll box (`diff-lines.tsx:66-67`) | mobile-adaptation | Rendered inline and clamped before parsing, so there is nothing left to virtualise | A nested vertical scroller inside a `LazyColumn` competes with the transcript's drag on touch (#56); see `docs/parity/tool-output-fidelity.md` for the full reasoning |
| Long-press selection of diff text | drift | Not selectable | Needs real-device gesture arbitration evidence; #71 S35 |
| Syntax-highlighted diffs (`SyntaxDiff`, `diff-lines.tsx:468-479`) | omission | Absent | out-of-scope: #71 named it a non-goal of that issue, being a size and cold-start decision of its own; nothing about the platform refuses it |

## Visual report

- pending: #201

No device capture is claimed here: every value on this page is decided offline
by the derivation and asserted by the tests above. The panel geometry landed in
#71 S34, so the rendered side-by-side is now #201's: a file-edit row with a
gateway-rendered diff, light and dark, Desktop at the pin beside Android.
