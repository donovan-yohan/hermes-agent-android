# Upstream target refresh — 587e673e — 2026-10-01

## Scope and provenance

Requested Desktop implementation/theme target:
`587e673e2a2fae0616d8b750bb189217080f621a` in `NousResearch/hermes-agent`.
Compared directly with `e27448b231498e79ade668d68c0b6c6206951206` in a
separate disposable upstream worktree. Android changes are isolated on
`feat/upstream-bot-refresh`, based on
`5118d43bf9bde5ec0a77670c5cc280c1e2c99c19`; the source Android worktree and
installed upstream checkout were not edited.

The approved `AGENTS.md` edit is applied. The global implementation target, theme
ledger and review workflow now agree on the requested target.

Historical per-surface pins, source citations, screenshots, visual-capture
catalog and the earlier October 1 audit are deliberately retained. Nothing here
upgrades old rendered evidence or certifies all Android surfaces at this SHA.
The parallel routine-inspector workstream is not edited or certified here.

## Built-in themes and semantic-token audit

All upstream paths in this section were inspected at
`587e673e2a2fae0616d8b750bb189217080f621a`.

| Area | Direct source comparison | Result |
|---|---|---|
| Full built-in registry and typography | `apps/desktop/src/themes/presets.ts` is byte-identical between targets | All eleven names, labels, descriptions, registry order, default `nous`, fonts and literal spans unchanged. |
| Every built-in palette | `apps/shared/src/theme-presets.ts` is byte-identical | No light/dark palette expression edits; no Android palette change warranted. |
| Required and optional colour keys | `apps/desktop/src/themes/types.ts` is byte-identical | No added, removed or renamed colour fields; ledger required/optional sets remain valid. |
| Colour arithmetic | `apps/desktop/src/themes/color.ts` is byte-identical | No `ColorMath.kt` change warranted. |
| Light synthesis, base colours and semantic CSS-variable derivation | Whole `apps/desktop/src/themes/context.tsx` diff only changes `RETIRED_SKINS` and its comment | `synthLightColors`, `getBaseColors`, semantic mappings and fallbacks are unchanged. No `HermesTokens.kt` change warranted by this interval. |
| Stylesheet behavior | `apps/desktop/src/styles.css` adds media-lightbox transparent-shell styling and hidden-pane descendant visibility inheritance | Not a palette/token-definition change. These surface behaviors are not certified on Android. |
| Backend skin registry/selection | `apps/desktop/src/themes/backend-sync.ts`, `context.tsx`, `use-skin-command.ts` change | Android selection fix below; not a twelfth built-in. |

The identity script passes for `nous`, `github`, `catppuccin`, `everforest`,
`solarized`, `nous-alt`, `midnight`, `ember`, `mono`, `slate`, `cyberpunk`.
It checks identity/order/default/dark membership, **not** rendered colour values.
Byte comparisons and the full context diff, not the script alone, justify leaving
production palettes, typography, token derivation and colour arithmetic untouched.

### Classic Hermes backend-skin drift

Desktop now registers backend `default` as **Classic Hermes**, retains its CLI
description, allows selection instead of normalizing it to `nous`, and routes
`gold`/`hermes` slash aliases to `default`. Its boot default remains `nous`.
Evidence: `apps/desktop/src/themes/backend-sync.ts:91-125`,
`apps/desktop/src/themes/context.tsx:51-55`, and
`apps/desktop/src/themes/use-skin-command.ts:4-14` at
`587e673e2a2fae0616d8b750bb189217080f621a`.

Android had two selection defects: `data/themes/GatewayTheme.kt` rejected backend
`default`, and `BackendSkinSync.kt` rewrote its apply target to `nous`. This refresh
removes both exceptions, labels the backend-derived preset **Classic Hermes**,
and preserves the supplied description and existing palette conversion. It adds
no built-in and leaves the boot default `nous`. Existing Compose resolution
selects a registered custom `default` before the built-in fallback.

Regression tests cover parse identity, palette provenance, cache/restore without
repainting, applying and acknowledging `default`, and refusing a definition-less
`default` rather than silently applying `nous`. Built-in collision protection and
endpoint/profile guards are unchanged. This is a source-backed backend selection
fix, not a rendered-parity claim. Desktop `gold`/`hermes` slash aliases remain
outside this fix: Android still lacks the general slash dispatch pipeline below.

## Bot and shared-composer deltas

All upstream paths in this section refer to
`587e673e2a2fae0616d8b750bb189217080f621a`. These are source audits, not executed
upstream UI or Android integration acceptance.

| Delta | Exact upstream behavior and evidence | Android disposition |
|---|---|---|
| Screen profile dispatch | `apps/desktop/src/plugins/hermes-bots/screen-connection.ts:117-134` explicitly overwrites caller `profile` with route-owned `targetProfile || profile` (or the string route). Dedicated-secondary RPCs no longer accidentally use the launch profile. | **omission** — no Android Screen transport/control surface. A future port must bind endpoint plus route-owned profile at dispatch, not copy a caller override. |
| RFB target and credentials | `apps/desktop/src/plugins/hermes-bots/screen-connection.ts:161-190` selects `targetProfile` ahead of route profile for the sibling `/api/display/ws` URL, strips the Gateway credential, then attaches `display_ticket`. Credential stripping already existed at the previous pin; the target-profile selection is new. | **omission** — preserve ticket-only RFB authentication when implementing Android Screen; do not report existing display contracts as a backend blocker. |
| Browser Comment Mode → group draft | `apps/desktop/src/plugins/hermes-bots/group-chat-view.tsx:622-739` accepts only the matching renderer window, group and composer key; uses Electron relay or browser BroadcastChannel fallback; appends prompt/images to New Thread draft, clears reply-thread selection and ACKs after staging. Stale routes get no ACK. It does not send the draft. | **omission** — Android hosted-room Group Chats has disabled New Thread and no browser annotation handoff/draft surface. This is missing Android functionality, not worker unavailability. |
| Group fenced-code wrapping | `apps/desktop/src/plugins/hermes-bots/group-chat-view.tsx:1320-1327` replaces horizontal pre overflow with soft-wrap/anywhere; `group-chat-view.wrap.test.tsx` covers the wrapper. | **omission** — Android currently renders a different hosted-event projection, not the rich Desktop room message body. No visual wrap parity claim. |
| Skills Hub capabilities | `apps/desktop/src/plugins/hermes-bots/skills-hub.tsx:227-245` adds clipboard-write and popup sandbox permissions; `apps/desktop/electron/hub-iframe-policy.ts:14-55` limits trusted origins, external schemes and clipboard write (not read). `apps/desktop/electron/main.ts:13680-13710` prevents trusted Hub frames escaping the picker. | **omission** — Android has no embedded Hub picker/capability policy. A future native/WebView adaptation must retain origin/scheme restrictions and frame pinning; enabling popups or clipboard globally is not parity. |
| Stacked slash skills | `tui_gateway/methods_tools.py:718-746` now consumes additional leading skill tokens and returns one expanded `type: skill` payload, loading notice, missing-skill notice when applicable, and display projection. The shared CLI/helper already supported stacking; this interval adds it to the Gateway dispatch path. | **omission** — Android completion offset support and transcript scaffold projection do not establish dispatch parity. Ordinary composer submission uses `prompt.submit` directly; there is no general `command.dispatch` → `type: skill` expansion/notice/display pipeline. Missing native dispatch is not a backend limitation. |

Android source checks used `plugins/groups/GroupsScreen.kt` (disabled New Thread),
`ui/chat/ChatViewModel.kt` (completion offsets and submit path),
`data/gateway/GatewaySessionRepository.kt` (`requestPromptSubmit` and the limited
`slash.exec` goal-status call), and `data/gateway/SkillScaffold.kt` (display-only
projection). No new mutation, automatic send, iframe permission, or Screen URL
construction is introduced by this audit.

## Verification and limits

Commands from the isolated Android worktree (`$upstream` is the disposable
checkout whose HEAD is the requested target):

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

Executed static results:

- Live identity gate: PASS, all eleven presets; checkout HEAD equals ledger pin.
- Direct source assertions: PASS for byte-identical registry, full shared palette,
  colour schema and colour arithmetic; all eleven ledger literal spans, registry
  and default location; all 19 required and 7 optional colour keys; entire theme
  context after accounting for only the documented retired-skin change.
- Citation self-test and explicit `HEAD..WORKTREE` gate: PASS; 5 citations true,
  26 retained on their own pin, 0 not provable, 109 unattributable across 4 files
  with citation activity. The gate does not certify unattributable references or
  every new audit statement; new source spans above were read directly.
- Repo invariants: PASS, including 128 Python tests and parity-evidence structure
  over 36 pages. Its nested citation check **skipped** this valid linked upstream
  worktree because the wrapper expects a `.git` directory, not a `.git` file;
  the explicit citation command above did run successfully against that checkout.
- `git diff --check`: PASS.
- Standalone Kotlin/JUnit regression run: **42 tests pass** across
  `GatewayThemeParserTest`, `BackendSkinSyncTest`, `BackendSkinCacheTest`,
  `GatewayThemeRepositoryTest` and `ThemeParityTest`. Parser regression was seen
  failing on rejected `default`; sync regressions failed on missing registration
  and the stale `nous` rewrite before their respective fixes.
  No Gradle was invoked: the scratch harness compiles current theme data sources,
  palettes/colour math, ledger and tests with cached Kotlin compiler 2.3.21, using
  existing debug classes read-only for unchanged external dependencies. This is
  targeted JVM evidence, not a clean full-app build. Harness:
  `$TMPDIR/classic-theme-check/run.py`; final output: `verification.log`.

No Gradle, full app suite, APK build, emulator, Desktop rendering, commit or push
is part of this lane. Parent-owned build/runtime acceptance remains outstanding.
No blanket theme or Bot feature-parity claim is made.
