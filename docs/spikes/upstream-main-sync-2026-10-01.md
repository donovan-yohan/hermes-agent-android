# Upstream target and built-in theme audit — 2026-10-01

## Scope and provenance

The new implementation target is `NousResearch/hermes-agent` commit
`e27448b231498e79ade668d68c0b6c6206951206`. Source was read from a clean,
disposable checkout at that exact HEAD; the installed Hermes checkout was not
modified. The previous global/theme target was
`95f20517c25ee418da5337f4ead347008baaa2b3`.

This is a target refresh and a built-in registry/palette audit, **not** a claim
that all Android surfaces match this upstream revision. `AGENTS.md`,
`DesktopThemeLedger.PINNED_SHA`, and the review workflow now agree. The review
workflow had still named `564aef2946c436500a5e80ee117b66b789b3f99a`; its runnable
capture example and shell-gate citations were re-derived for the new target.
Historical per-surface pins, screenshots, production token provenance, and
composer evidence retain their inspected revisions. Bots and notifications are
separate workstreams and are not certified by this audit.

## Built-in theme result

All source paths in this section are at
`e27448b231498e79ade668d68c0b6c6206951206`, compared with
`95f20517c25ee418da5337f4ead347008baaa2b3`.

| Area | Source evidence | Result / Android action |
|---|---|---|
| Identity and registry | `apps/desktop/src/themes/presets.ts:66-415` | Same eleven names, labels, descriptions, declaration order, default `nous`, and hand-tuned-dark membership. Live identity gate passes. No `BuiltinThemes.kt` data change needed. |
| Palette values | `apps/shared/src/theme-presets.ts` | Whole file is byte-identical between pins. No palette expression change needed. |
| Colour maths and light synthesis | `apps/desktop/src/themes/color.ts`; functions `synthLightColors` and `getBaseColors` in `apps/desktop/src/themes/context.tsx` | Colour file and both function bodies are byte-identical between pins. No `ColorMath.kt` or synthesis change needed. |
| Typography | `apps/desktop/src/themes/presets.ts:35-43,379-382` | Desktop puts bundled JetBrains Mono and expanded Linux fallbacks into the mono stack; Cyberpunk appends that stack after Courier. Android keeps its documented platform-family substitution, including Cyberpunk's monospace body. This does not claim identical glyph coverage or font metrics. |
| Offline provenance | `DesktopThemeLedger.kt` | Pin/date and every preset literal span were refreshed. All eleven spans, registry span, and default line were checked directly against source. Existing production citations were not restamped. |

The live theme script checks identity/order/default/dark membership, **not**
palette values, font metrics, CSS, or Android rendering. The separate byte
comparisons above are the evidence for leaving the palette and colour code
untouched, not a stronger interpretation of the identity gate.

## Changed upstream behaviour outside the built-in palette sync

These are follow-up audit boundaries, not features implemented by this repin:

- `apps/desktop/src/themes/types.ts` adds optional `customCSS` to `DesktopTheme`.
  `backend-sync.ts` preserves CSS for built-in-named backend skins and seeds a
  local Electron skin at boot; `context.tsx` injects/clears CSS and scopes the boot
  fallback to its profile. No Android custom-CSS or backend-skin implementation
  is introduced here.
- `apps/desktop/src/styles.css` adds reduced-transparency handling, chat-only
  text scaling, CJK composer font selection, and explicit text-direction rules.
  It also changes arc-border geometry, markdown-image margins, the rolling
  counter's clipping, and HUD resize hit-testing. These are not palette edits;
  affected surface behaviour/rendering requires its own port and evidence audit.
- Desktop theme selection now records feature-use telemetry. No Android
  telemetry behaviour is inferred or added.

The above comes from source diffs of the theme context/backend-sync/types and
stylesheet; it is not an exhaustive audit of all upstream commits. In
particular, unchanged built-in palettes do not establish current semantic or
visual parity for `HermesTokens` and every consumer.

## Verification

Commands run from the Android worktree, using the disposable checkout as
`$upstream`:

```bash
python3 .chalk/skills/sync-hermes-desktop-themes/scripts/check-theme-parity.py \
  --upstream "$upstream"
python3 scripts/verify-pin-citations.py --self-test
python3 scripts/verify-pin-citations.py --check-range HEAD..WORKTREE \
  --upstream "$upstream" --repo .
HERMES_AGENT_UPSTREAM="$upstream" PIN_CITATION_RANGE=HEAD..WORKTREE \
  ./scripts/check-repo-invariants.sh
git diff --check
```

- Live theme gate: PASS, eleven presets in the same order; checkout HEAD and
  ledger pin both equal the new target.
- Direct source assertions: PASS for all eleven ledger literal ranges,
  registry/default citations, unchanged palette and colour-math files, and
  unchanged light-synthesis/base-colour function bodies.
- Citation fixture: PASS. The first full invariant run caught two stale shell
  gate citations in the review workflow after its runnable pin was updated;
  both were re-derived from the new source rather than suppressed.
- Final repo-invariant run and `git diff --check`: PASS (exit 0), including
  128 Python tests and the parity-evidence structure check over 35 pages.
- Final working-tree citation run: PASS (exit 0); the shared-worktree snapshot
  reported 6 citations true, 325 left on their own pin, 0 not provable, and
  339 unattributable across 8 files with citation activity. Other workers were
  editing their own surfaces concurrently. Unattributable references are not
  proof of correctness; the gate does not certify every reference in the repo.
  The theme ledger's refreshed ranges were additionally checked directly as
  described above.

No Gradle, Kotlin tests, APK build, or rendered preview was run in this
workstream. The parent owns serialized Gradle verification, including
`ThemeParityTest` and `ColorMathTest`. No visual parity upgrade is claimed.
