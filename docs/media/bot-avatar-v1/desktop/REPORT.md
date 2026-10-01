# Desktop avatar capture completion

## Outcome

**Desktop evidence coverage complete; strict Android/Desktop presentation equivalence is not claimed.** This packet contains 16 canonical Desktop receipts and their unchanged real Electron screenshots: 14 new captures plus the two already successful pending-diagnostic captures, preserved with their original capture identity. All 16 receipts pass the existing v1 schema. A separate native-picker screenshot is supplementary OS evidence, not a ninth themed editor state.

Only additive scratch E2E harnesses and scratch evidence were written. No Android source edit/build, GitHub action, production Desktop edit, or Desktop rebuild. Verified 2,735 production source files against upstream `587e673e2a2fae0616d8b750bb189217080f621a`; 1,087 built bundle files still match the original packet. Additive fixture patch reconstruction passed.

## Precise failure and root cause

The original rerun log reports two different failing assertions:

1. Pending/error: `expect.poll(() => window.__avatarProbe.reads).toBeGreaterThan(0)` timed out after 30 seconds with **0**. The probe was installed in an init script only after bot creation and direct seed-file copy, then a renderer reload. The first renderer could already fetch the seed and persist `hermes.plugin.hermes-bots.bot-meta-v2["local::synthetic-avatar"].image`. Reload is not a cache reset. `profile-ops.ts:173-179` intentionally skips the read when local metadata contains an image. The minimal fresh failing run reproduced reads=0 while real `profiles.list` returned `has_avatar: true`, the sandbox asset was a blue 256×256 PNG, and the cached image was exactly that PNG. The inference-server `/api/tags` and `/props` 404s are unrelated discovery noise, not avatar RPC failures.
2. Journey: `getByRole('dialog', {name:'Edit profile'}).locator('img').first()` was absent after 5 seconds. The old harness waited only for a read to **start**, not for the image to arrive. `edit-profile-dialog.tsx:65-90` snapshots `appearance.image` into local state on bot/open-key change; it does not synchronize that draft when the async avatar read completes. A separate delayed-real-reply test reproduced the exact mechanism: open editor without image → release real backend read → roster image visible, editor still has zero images → close/reopen → editor image visible. That controlled reproduction establishes the mechanism; the old failed log alone does not identify the exact historical reply timing.

The harness correction clears only the disposable synthetic bot's cached `image` fields in the init script before application startup, keeps real Gateway/disk reads intact, and waits for `[data-roster-key="local::synthetic-avatar"] img` before opening the loaded editor. No DOM was fabricated or application state/render code replaced. Backend mutation and image normalization remain production behavior.

The earlier speculative shape-backfill wait was not repeated. No repeated blind full-suite run; no region reached three unsuccessful fixes in this lane.

## Focused execution receipts

| Run | Result | Purpose |
|---|---|---|
| `focused-diagnosis/run.log` | 1 failed, reads=0 | Minimal original ordering, full RPC/cache/disk diagnostic |
| `cache-cold/run.log` | 1 passed | Cache-cold initialization and image-ready opening gate |
| `native-minimal/run.log` | 1 passed | Real native picker and trusted Cancel event |
| `completed-desktop/run.log` | 2 passed, 34.9s | Remaining complete journey and read-refusal only; no retries |
| `readiness-proof/run.log` | 1 passed, 13.6s | Delayed real reply proves stale open-editor snapshot |

Raw logs and RPC dumps stay outside the curated packet because they contain private scratch sandbox paths. `cache-race-proof.json` and `readiness-proof.json` retain safe, derived assertions. Initial `xvfb-run` could not launch because xauth was absent; an owned Xvfb display with `-ac` was used instead, with process cleanup. This infrastructure preflight was not an application retry. Per-file editor LSP reported missing Node globals; Playwright itself successfully transpiled and executed the additive specs; no full TypeScript build is claimed.

## Explicit platform map and supported acceptance

Each row has separate light/dark editor PNGs under `desktop/<state>-<theme>/screenshot.png`, with `contract.json` and `observations.json`.

| State | Actual Desktop behavior | Supported acceptance / boundary |
|---|---|---|
| loaded | Real named read, blue preview before opening editor | Blue preview bytes equal seeded PNG; no fake DOM |
| loading | Actual request held pending; shape remains | Pending RPC proof + fallback pixels, **not** Android `Loading avatar…` text. Existing diagnostic receipts retained unchanged |
| picking | Actual native Open File window is open; editor remains blue | Editor crop depicts underlying application, not native chooser. Supplementary `native-picker.png` and window proof establish the real OS picker. OS chrome is not emulated light/dark |
| cancel | Escape to focused native Open File; Chromium emitted `{type:"cancel", isTrusted:true, files:0}` | Preview unchanged; zero writes; no empty-file onchange injection. No inline Android cancellation message. Upstream `pickImageFromDevice` registers only onchange, so native cancel has no promise resolver in source; no resolved-null claim |
| error | Initial named read receives injected JSON-RPC refusal; silent shape fallback | Refused-request proof + real fallback pixels, **not** Android error/retry UI |
| changed | Genuine browser file selection and production normalization | Orange 256×256 preview staged before save |
| saved | Shared Save closes the editor; reopened orange preview | Uploaded 2,375 PNG bytes matched actual sandbox asset exactly. **Not** an in-place success state |
| cleared | Remove image → Save → clear RPC → fallback shape raster upload → reopened vector shape | **Documented drift:** asset exists again. This cannot certify Android-style confirmed absence. No pixels or receipts advertise absence parity |

The unchanged upstream does not provide the Android loading/error/cancellation/success labels. Accept its documented behavioral counterparts above or keep strict UI equivalence blocked; do not manufacture those states. The clear/backfill drift remains open to product review; this task does not change upstream semantics.

Visual review of both eight-state contact sheets confirmed complete dialog crops, blue/orange/shape progression, light/dark surfaces and visible controls. Disabled/secondary labels remain low-contrast, especially in dark mode; no accessibility contrast certification or redesign is claimed.

## Provenance and files

- `index.json`: authoritative 16 state/theme entries.
- `receipt-validation.json`: all existing-v1 schema checks; these validate receipt structure, not equivalence.
- `verification.json`: image-byte/state assertions, native cancel, save and clear checks.
- `source-verification.json`: immutable production source and bundle comparison.
- `original-packet-verification.json`: original historical packet preserved byte-for-byte.
- `desktop-additive-fixtures.patch`: two additive capture specs, applied and hash-checked in an independent initialized scratch directory.
- `native-escape.py`: native XTest Escape helper; refuses any display except the owned `:187` and any focused window lacking a native picker title.
- `native-events.json`, `native-window-proof.json`, `native-picker.png`: actual OS cancellation evidence. The picker PNG is a Linux GTK file chooser; its theme is not cross-platform or themed editor evidence.
- `saved-disk-outcome.json`, `clear-outcome.json`: actual write outcomes.
- `SHA256.json`: curated packet hashes.

## Reproduction

Use the original pinned Desktop E2E bootstrap and existing genuine built bundle in a disposable export; do not run against a user profile. Initialize a separate scratch directory with `git init -q`, apply `desktop-additive-fixtures.patch`, and compare both spec hashes with this packet before execution. Put `blue.png` and `orange.png` in a fresh output directory. Start an owned Xvfb `:187` (1600×1200×24); never target a user's display.

From `apps/desktop`:

```sh
DISPLAY=:187 PARITY_OUT=<fresh-output> NATIVE_HELPER=<packet>/native-escape.py \
  npx playwright test e2e/avatar-complete-journey.spec.ts e2e/avatar-read-refusal.spec.ts \
  --workers=1 --retries=0 --reporter=list
```

The native helper requires Python Pillow, libX11, libXtst and `xwininfo`; capture specs use the real upstream fixture. Stop Xvfb afterward. Pending receipts in this packet originate from the earlier successful pending-only diagnostic, not this command.
