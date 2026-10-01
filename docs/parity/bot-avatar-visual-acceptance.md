# Bot avatar actual visual acceptance

**Verdict: blocked.** Captures exist; neither filename coverage nor passing Desktop tests establishes visual parity.

## Captured provenance

- Android publication source: `8cd7454cf4d10dec87279b4b83f61b49a6d0df8c`, based on `107d7cd15c0d6c3d85913bcf33a92780b5e58b87`.
- Existing parent-built debug APK SHA-256, verified equal after emulator install: `704ad8f83a1c95510d55fcd63ca89d2c2c56f92672888d4ce457ee12c9377f6b`.
- API 37 emulator; 16 original PNGs and accessibility XMLs; explicit Activity focus before/after screenshots. ADB launch and UIAutomator dump, **not Espresso**. No global settings changes, device wipe, or physical-device interaction.
- No Gradle run in this lane. The existing APK was not independently rebuilt to certify commit equivalence. Source file hashes identify the publication source, not a retroactive build receipt.
- Real Desktop upstream `587e673e2a2fae0616d8b750bb189217080f621a`, disposable `routine-inspector-587e/source-v2`; all 2,735 production source files under `apps/desktop/src` matched the upstream object bytes.
- Additive `e2e/avatar-acceptance.spec.ts` uses actual `setupMockBackend`, creates only a synthetic profile, operates the real Edit profile dialog and file input, and preserves production normalization and Gateway writes. No handcrafted UI or renderer replacement.
- Final Playwright execution: **3 passed, exit 0**; 16 Desktop PNGs. Sandbox window resized to 1440×1100 to retain complete dialogs; earlier cropped captures and failed-run logs remain scratch evidence.
- Upload normalization was checked as 256×256 orange pixels; the 2,375 uploaded PNG bytes exactly matched the sandbox asset on disk before removal.

## Explicit platform map

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

## Acceptance blockers and findings

1. **Android fixture presentation fails visual acceptance.** Light captures show black background with nearly invisible dark text. Dark captures show the header overlapping status icons. Source mounts `BotAvatarEditor` in an unpainted `Column` without safe status insets. This is evidence against the standalone fixture, not proof the production sheet shares the defect. Parent decision needed: authorize the minimal fixture surface/inset correction and a build/recapture lane. Do not retouch images or relabel them as passing.
2. Android loading captures completed with postchecks at 4.614s and 4.646s, below the unchanged 60-second production deadline. Accessibility labels verify requested states, but no runtime RPC/ticket exporter exists in this fixture. Source wiring plus actual pixels/XML are retained, not invented runtime telemetry.
3. Desktop clear produced upload → clear → shape-raster upload; the asset existed afterward. `profile-ops.ts:64-119` explains that backfill. Preserve this adaptation rather than advertising cross-platform asset-absence equivalence.
4. Desktop pending/error/chooser cancellation do not match Android's presentation or action boundary. A future explicit contract needs parent review; this lane did not churn the catalog or validators to manufacture equivalence.
5. Raw evidence manifests are not canonical receipts. `check-receipt` rejected the Android aggregate (`receipt must be an object`) and Desktop raw record (`receipt misses required common provenance`); failures are retained. No validator pass is claimed.

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
