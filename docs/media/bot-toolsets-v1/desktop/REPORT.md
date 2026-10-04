# Actual Desktop Tools reference — upstream 587e673e

## Result

Real Electron `setupMockBackend` capture: **4 tests passed, 0 failed**, exit 0, final attempt 3. **14 unchanged PNGs** cover seven observed states × mono light/dark. Four Playwright traces remain in sibling `toolsets-current-attempt3/`. Per-image JSON retains the rendered accessibility snapshot, full editor text, scoped IPC requests/results, synthetic profile RPC requests and resolved theme/locale/timezone/clock. These are observational evidence, **not catalog-certified parity receipts**.

`provenance.json` identifies upstream `587e673e2a2fae0616d8b750bb189217080f621a`, the additive fixture, its reconstructible patch, PNGs, traces and reused build assets. An immutable `git archive` comparison verified all **17,003 regular upstream files unchanged**, none missing. No production source was patched; only `e2e/toolsets-current-reference.spec.ts` was added to the supplied disposable `source-v2`. The supplied prebuilt dist was reused; this task did not rebuild it, so build identity is recorded separately rather than claiming a fresh source-to-build proof.

## Reachable surface, not the obsolete checklist

Real route: Bots → Synthetic Toolsets context menu → Edit profile → Advanced → Tools. This pin mounts the live `CapabilitiesView`, not the older fallback checkbox editor. Actual rendered navigation is **Skills → Tools → Connectors → Plugins**; the Connectors tab is the MCP-related surface. “MCP” appears in the capabilities label, not the tab label.

Native copy reads **“Capabilities (applies immediately — skills, tools, MCP)”**. Tools is a master/detail list with native switches and a real Terminal configuration inspector. The containing profile dialog still has Cancel/Save, but these do not stage Tools toggles. The screenshot scroll is positioned at Advanced, so the surrounding avatar/header and footer Save are outside the viewport. Both Tools rows and the capability panel are visible; the Terminal inspector itself scrolls, and lower backend options are clipped naturally. No screenshot retouching or invented controls.

Source anchors:
- `src/plugins/hermes-bots/profile-config.tsx:247-299`: live capability branch; fallback starts at 341 and toolset checklist at 395.
- `src/app/capabilities/index.tsx:114-130,189-198`: genuine loading/error gate and navigation.
- `src/app/capabilities/toolsets/toolsets-tab.tsx:52-101`: optimistic, immediate per-toolset write; silent success, revert/notify on error.
- Same file `:130-140`: bulk All switch, **not restore defaults**.
- `src/api/toolsets.ts:17-34`: scoped GET and per-toolset PUT.
- `hermes_cli/web_routers/tools.py:280-308`: actual production toggle persists via `_save_platform_tools` for its target platform. This source reading is not a claim that our synthetic store exercised disk persistence.

## Actual counterparts and gaps

| Android/requested concept | Actual Desktop evidence | Boundary |
|---|---|---|
| Loaded/default selection | `loaded-defaults-{light,dark}.png` | Web on, Terminal off. Named profile description fixture has `toolsets_pinned=false`. Tools list API does not expose pin state; no “Using defaults” badge. |
| Loaded/custom pin | `loaded-pinned-{light,dark}.png` | Same Web/Terminal selection, description fixture `toolsets_pinned=true`. No native “Custom selection” badge. Labels/counts/descriptions match Android fixture. |
| Loading | `loading-{light,dark}.png` | Actual Tools GET held pending; native spinner and count skeleton, no invented “Reading toolsets…” wording. Android holds `profiles.describe`; Desktop initial profile description succeeds so current Tools is reachable. |
| Read error | `error-{light,dark}.png` | Tools GET really refuses four attempts. Current native gate says **“Skills failed to load”**, action **“Refresh skills”**, even on Tools. Not relabelled to Android error copy. |
| Changed | `changed-autosave-pending-{light,dark}.png` | Clicking Terminal dispatches real scoped PUT immediately. Both switches optimistic-on while synthetic write is held. **Not an unsaved draft awaiting Save**. |
| Saved | `saved-autosave-{light,dark}.png` | Held PUT released, synthetic store committed, both switches on. Native success is silent; no “Toolsets saved.” toast invented. |
| Saved readback | `saved-reopened-{light,dark}.png` | Cancel closes containing editor; reopen triggers a second scoped GET returning Web+Terminal enabled. Actual readback log verified, not merely cached on-state. |
| Reset confirmation | **No current Tools counterpart captured** | No restore-defaults control in native current Tools; All toggles enablement, not inheritance. We did not force the fallback or fabricate a confirmation. |
| Default restored | **No current Tools counterpart captured** | No native restore action means no justified transition screenshot. Initial unpinned fixture cannot masquerade as a restored state. |

The Android fixture also enumerates all-selected, empty-selection, empty and unconfirmed. They were read but were not requested as additional Desktop journeys here. Saved/autosave happens to show both selected; it is not relabelled as another acceptance state. No write-refusal journey was captured.

## Synthetic scope and proof limits

Android was read-only: `BotToolsetsParityActivity.kt` and `visual-capture-surfaces.json` (hashes in provenance). Its fixture is `synthetic-toolsets`, `web`/Web/Search and read web pages/count 2 and `terminal`/Terminal/Run shell commands/count 1. Initial selection is Web only. Android saves `profiles.configure(name, enabled_toolsets)` and resets using an empty selection, with independent confirmation; current Desktop dispatches `PUT /api/tools/toolsets/terminal {enabled:true}`.

Our additive fixture uses existing upstream E2E seams:
- Real `setupMockBackend`, Electron, isolated sandbox and real component tree.
- `ipcMain._invokeHandlers` wrapper, like `e2e/completed-reply-refresh.spec.ts`, substitutes only the named synthetic profile's toolset list/write responses. Other requests pass through to the isolated backend.
- WebSocket `profiles.describe` response for the named synthetic profile, like adjacent model-reference capture. This seeds descriptor/default-pin input, not the actual current Tools list. Its descriptor is static across reopen; current Tools authoritative saved readback is the separate IPC GET. Do not infer profile-describe persistence from it.
- Every observed intercepted request was verified to carry **profile `synthetic-toolsets`, connectionId `local`**. The toggle originates from the actual rendered switch. No direct fixture API write was used to create saved state.
- Saved means committed and reread in this deterministic synthetic store, **not production Python disk-write coverage**. Pin/default UI parity remains absent despite fixture input matching.
- Real Terminal inspector capability probes were left intact. “Ready”/“Needs setup” reflects this disposable execution host; those host-dependent details are not Android fixture-aligned.

Each PNG resolves mono, its stated light/dark mode, en-US, UTC, and `2026-09-17T16:00:00.000Z`. Main-process log timestamps remain real execution times; they are not renderer clock claims.

## Validation and retained attempts

`check-receipt --platform desktop --receipt .../loaded-pinned-light.json` was actually run: exit 1, **“receipt misses required common provenance”** (`receipt-validation.txt`). The JSON intentionally uses native state names and observation schema. No invented schema fields or receipts were added to turn this reference into certified parity. The Android catalog currently describes an existing Toolsets editor with a separate save/reset contract; these observations demonstrate why mapping current Desktop straight onto that contract would be false.

Attempts 1 and 2 remain untouched in sibling directories, with failure screenshots and error-context source snapshots. They failed on capture selectors (navigation pills are buttons, switches are “Turn Terminal toolset on/off”, error action is “Refresh skills”; loading has a spinner without visible Loading text). Attempt 3 uses actual accessible controls and passed. Contact sheet is a derived review convenience only, in `toolsets-current-attempt3/contact-sheet.jpg`; the PNG originals are unchanged.

Raw traces and failure logs are scratch-only and not reviewed for publication. They can include private execution paths and environment-related metadata; do not copy them into a public packet. No Android files, Gradle tasks, GitHub state, installed Desktop sources, or user Desktop profiles were modified.

## Reproduction

From an immutable export of the pinned SHA, initialize a local scratch Git repository before applying `toolsets-current-harness.patch` using `git apply`. The patch adds only the fixture. Reconstruction was exercised into a separate scratch directory and the resulting fixture SHA-256 matched `fa4d9ace16ade3988ca4b610047997f1ee932bd55ddc27a06ef5cba17924eac2`.

With the real upstream E2E prerequisites/build available in that disposable export and a verified dedicated Xvfb display:

```sh
DISPLAY=:137 PARITY_OUT="$SCRATCH/toolsets-current-attempt3" \
  npx playwright test e2e/toolsets-current-reference.spec.ts \
  --workers=1 --reporter=list --output="$SCRATCH/toolsets-current-results3"
```

Run from `apps/desktop`; `SCRATCH` names your disposable scratch root. The display was owned by this task and stopped after capture. Preserve the real exit status and do not overwrite historical attempts. Reload this report's seam/selector findings for future captures rather than changing upstream components to suit stale locators.
