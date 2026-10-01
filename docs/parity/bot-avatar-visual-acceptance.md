# Bot avatar actual visual acceptance

**Current verdict: bounded paired evidence complete — 16 Android and 16 Desktop canonical receipts, with 32 unchanged editor PNGs.** See the [published paired report](../media/bot-avatar-v1/REPORT.md). Strict UI equivalence and clear/backfill asset semantics remain unresolved under #194. Original failed-packet findings and the intermediate blocked rerun remain historical below; the final completion is recorded at the end.

## Original captured provenance (historical)

- Android publication source: `8cd7454cf4d10dec87279b4b83f61b49a6d0df8c`, based on `107d7cd15c0d6c3d85913bcf33a92780b5e58b87`.
- Existing parent-built debug APK SHA-256, verified equal after emulator install: `704ad8f83a1c95510d55fcd63ca89d2c2c56f92672888d4ce457ee12c9377f6b`.
- API 37 emulator; 16 original PNGs and accessibility XMLs; explicit Activity focus before/after screenshots. ADB launch and UIAutomator dump, **not Espresso**. No global settings changes, device wipe, or physical-device interaction.
- No Gradle run in this lane. The existing APK was not independently rebuilt to certify commit equivalence. Source file hashes identify the publication source, not a retroactive build receipt.
- Real Desktop upstream `587e673e2a2fae0616d8b750bb189217080f621a`, disposable `routine-inspector-587e/source-v2`; all 2,735 production source files under `apps/desktop/src` matched the upstream object bytes.
- Additive `e2e/avatar-acceptance.spec.ts` uses actual `setupMockBackend`, creates only a synthetic profile, operates the real Edit profile dialog and file input, and preserves production normalization and Gateway writes. No handcrafted UI or renderer replacement.
- Final Playwright execution: **3 passed, exit 0**; 16 Desktop PNGs. Sandbox window resized to 1440×1100 to retain complete dialogs; earlier cropped captures and failed-run logs remain scratch evidence.
- Upload normalization was checked as 256×256 orange pixels; the 2,375 uploaded PNG bytes exactly matched the sandbox asset on disk before removal.

## Original explicit platform map (historical)

Every row has separate light/dark images. These are descriptive mappings, **not additions to the capture contract or validator-equivalence assertions**.

| Catalog state | Android actual fixture | Desktop actual capture | Boundary |
|---|---|---|---|
| `bot-avatar-loaded` | Blue preview after named asset read | `loaded`: blue preview in real Edit profile | Android standalone component vs Desktop whole dialog |
| `bot-avatar-loading` | Request held pending; `Loading avatar…` | `read-pending-fallback`: named asset request held; shape fallback | Desktop has no equivalent inline loading label; pending probe retained |
| `bot-avatar-picking` | `beginPick` staged; `Choosing image…`; app Activity stays focused | `picking-app-unchanged`: real filechooser request intercepted by Playwright; editor unchanged | Neither PNG is OS picker evidence; Desktop has no busy indication |
| `bot-avatar-cancel` | Production cancellation branch; explicit cancelled message | `cancel-no-selection`: actual file input receives empty selection; preview unchanged, no writes | Empty selection exercises onchange/no-file, **not native chooser Cancel-event equivalence** |
| `bot-avatar-error` | Initial read refusal; explicit close/retry message | `read-error-fallback`: actual request refused; shape fallback | Desktop silently falls back; no equivalent inline error presentation |
| `bot-avatar-changed` | Actual Android normalizer stages orange image | `changed-staged`: real file input and browser normalizer stage orange image | No save at capture |
| `bot-avatar-saved` | Production fixture save/readback; inline `Avatar saved.` | `saved-reopened`: actual shared Save, disk-byte verification, reopened orange preview | Not an in-place Desktop success state |
| `bot-avatar-cleared` | Production fixture clear/readback; no preview and `Avatar removed.` | `cleared-reopened-shape`: real remove and Save, reopened shape | Desktop emits clear, then upstream shape rasterization can recreate asset; **not confirmed absence** |

## Original packet blockers and findings

1. **Android fixture presentation fails visual acceptance.** Light captures show black background with nearly invisible dark text. Dark captures show the header overlapping status icons. Source mounts `BotAvatarEditor` in an unpainted `Column` without safe status insets. This is evidence against the standalone fixture, not proof the production sheet shares the defect. Parent decision needed: authorize the minimal fixture surface/inset correction and a build/recapture lane. Do not retouch images or relabel them as passing.
2. Android loading captures completed with postchecks at 4.614s and 4.646s, below the unchanged 60-second production deadline. Accessibility labels verify requested states, but no runtime RPC/ticket exporter exists in this fixture. Source wiring plus actual pixels/XML are retained, not invented runtime telemetry.
3. Desktop clear produced upload → clear → shape-raster upload; the asset existed afterward. `profile-ops.ts:64-119` explains that backfill. Preserve this adaptation rather than advertising cross-platform asset-absence equivalence.
4. Desktop pending/error/chooser cancellation do not match Android's presentation or action boundary. A future explicit contract needs parent review; this lane did not churn the catalog or validators to manufacture equivalence.
5. Raw evidence manifests are not canonical receipts. `check-receipt` rejected the Android aggregate (`receipt must be an object`) and Desktop raw record (`receipt misses required common provenance`); failures are retained. No validator pass is claimed.

## Visual report

- report: ../media/bot-avatar-v1/REPORT.md
- commit: 0cc6ea3564fc268b8572e4f27c59e02c3f23546b
- This is the dirty Android capture base plus the report's retained exact patch, not a clean publication-head build. Original failed evidence is not relabeled; actual limited comparisons and preserved identities are in the report.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Pending/error silently render shape | mobile-adaptation | Explicit loading/error labels | Original actual platform map above |
| Trusted native picker cancel, unchanged preview and no inline notice | mobile-adaptation | Fixture-staged production cancellation and inline notice | Phone ActivityResult ownership; Desktop now proves real native Cancel, but Android OS-picker gestures are not captured |
| Shared Save closes; reopen for saved pixels | mobile-adaptation | Inline save/readback result | Separate presentation boundaries |
| Clear then shape-raster upload recreates asset | drift | Explicit clear followed by confirmed absence | deferred: #194 — `profile-ops.ts:64-119` and [actual disk outcome](../media/bot-avatar-v1/desktop/clear-outcome.json); not absence parity |

## Retained packet

The parent handoff includes scratch packet `avatar-acceptance/packet` with original images, Android XML/labels/deadline brackets, Desktop state/probe JSON, source/installed-APK provenance, per-file hashes, and the additive Desktop fixture plus reconstructible patch. APKs, private device identifiers, capture-host commands and raw logs are excluded from the curated packet.

Reconstruct the additive fixture in a disposable export of the pinned upstream:

```sh
git init -q
git apply /path/to/desktop-additive-fixture.patch
# Compare e2e/avatar-acceptance.spec.ts SHA-256 against provenance.json.
# Place the retained synthetic blue.png and orange.png in PARITY_OUT.
cd apps/desktop
DISPLAY=<owned-display> PARITY_OUT=<synthetic-output> npx playwright test e2e/avatar-acceptance.spec.ts --workers=1
```

The patch was actually reapplied into an independent initialized scratch repository and its resulting fixture hash matched. Dependency/bootstrap instructions remain those of the pinned real E2E harness. The original Android failure images must stay preserved when replacement captures become available.

## Executed fixture correction and recapture

- Debug fixture only: paint `HermesTheme.tokens.chatSurface` before applying system-bar insets, outside scrolling, matching neighboring debug fixtures. Production `BotManagementSheet` and `BotAvatarEditor` were not changed.
- `BotAvatarFixtureSurfaceTest` covers real painted pixels and header bounds for light/dark with nonzero injected system insets. Both cases failed on the original header bounds and passed after correction. A host regression also verifies the real default is `WindowInsets.systemBars` and that surface/insets precede scrolling.
- Read and applied the six-file readiness change from main `fbdda921252df85b1f73f8a1342f21ce59a61128` without committing; all six integrated files match that commit byte-for-byte. Base remains `0cc6ea3564fc268b8572e4f27c59e02c3f23546b`; this is a working-tree build, not a newly committed artifact.
- Full executed gate: `check assembleDebug --rerun-tasks --no-parallel --max-workers=1`, in-process Kotlin and Gradle `-Xmx6g`, exit 0. Debug: 3,303 tests, 0 failures/errors, 1 skipped. Release: 2,618 tests, 0 failures/errors, 1 skipped. Earlier runs stopped at parity-document invariants; the exact invariant gate passed in isolation before the successful full rerun. Original failed logs remain scratch evidence.
- Rebuilt and installed APK SHA-256: `d3f30a1aef2cb15ab2c58b97d90d0606a32a850dc233f06fa2a4553528c62a27`. Local, transferred and pulled installed bytes match; installed identity was checked again after all captures.
- Fresh API 37 ADB captures: all eight states in both themes, **16 PNGs and 16 canonical Android v1 receipts**. Ordered accessibility actions now dispatch through the real worker; loading is bracketed against its unchanged 60-second deadline. Loading screenshot postchecks completed at 12.593s light / 12.598s dark. No Espresso, global settings changes, physical-device interaction, or RPC/ticket telemetry claim.
- Visual review of all 16 originals through separate review contact sheets confirms painted near-white/light and near-black/dark surfaces, readable header/body copy and header separation from status icons. Actual header top is 228 pixels in every retained app XML; SystemUI bounds are not published in these XMLs, so status separation is a pixel review, not an invented XML assertion. Disabled controls remain visually muted; no broader production-sheet redesign is claimed.
- New packet: scratch `avatar-recapture/packet`, with source patch/manifest, installed-byte provenance, receipts, original PNG/XML, validation outcomes and hashes. All **83 files** in the original `avatar-acceptance/packet` remain byte-identical. No old PNG was assigned a new capture identity or fixture version.

### Intermediate Desktop follow-up boundary (historical)

- Existing additive fixture and built Desktop bundle still match the original packet hashes; all 2,735 production source files match upstream `587e673e2a2fae0616d8b750bb189217080f621a`.
- The new unchanged-fixture rerun failed all three scenarios: journey lacked the image in the real Edit profile dialog; pending/error observed no named read. This is retained as a harness/reproducibility blocker, not overwritten by the historical 3-pass result.
- A fresh pending-only diagnostic run passed and produced **two canonical Desktop v1 receipts**, accurately named `read-pending-fallback` in descriptive boundary metadata. The raw diagnostic includes private sandbox paths and is excluded from the curated packet. A focused journey experiment waiting for upstream shape backfill still timed out at 240 seconds; no further retry or success claim.
- The original upload → clear → shape-raster-upload observation remains **documented drift**, tracked under #194, not confirmed absence parity. The successful historical journey is preserved as historical evidence only, not restamped into new canonical receipts.
- All 18 new receipts pass `check-receipt` under the existing v1 catalog. Descriptive per-platform boundaries are supplemental observations, **not a new v2 equivalence contract**. Android has no runtime RPC exporter; Desktop has only fresh pending-state coverage. Canonical receipts do not certify full paired acceptance.

## Completed Desktop follow-up and durable publication

- The focused diagnosis found a warm metadata cache suppressing the named read and an editor snapshot opened before the real reply arrived. Cache-cold synthetic initialization plus waiting for the roster image fixed the additive harness; no production Desktop modification or rebuild. A delayed-real-reply reproduction confirmed the stale open-editor snapshot. [Diagnosis and safe assertions](../media/bot-avatar-v1/desktop/REPORT.md).
- Two focused Playwright journey/refusal tests passed without retries, yielding 14 new state/theme images; the original two pending-diagnostic images remain unchanged. All 16 Desktop receipts validate. Native Open File → Escape produced a trusted cancel event; zero writes and unchanged preview were observed. Save bytes matched disk. Clear still triggered shape-raster reupload, not absence.
- [Durable paired report](../media/bot-avatar-v1/REPORT.md): 32 receipts and 32 original editor images, plus one supplemental native-picker image and two synthetic seed PNGs. Source/APK identities, Android dirty patch, 603-entry source manifest, all three reconstructible Desktop capture specs and safe per-state observations are retained. Publication revalidated all receipts and image hashes and independently reconstructed the patches.
- This replaces the missing paired report, not unresolved #194 scope. Android remains fixture-staged, Desktop saved/cleared are reopened outcomes, and clear/backfill remains drift. No Gradle or PR merge occurred in the publication lane; historical build results do not claim a later integrated-head build.
