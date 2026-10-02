# e05 theme-default Desktop reference — observational scratch packet

## Outcome and narrow claim

Genuine Desktop renderer and Python backend built from immutable upstream `e05b16348b1d06a3311237423b0a4fc30d9c5aa1`. Two Playwright cases passed, producing eight unique state/mode captures (four states × light/dark) at 1220×800:

1. Actual Appearance → Theme picker, manually selected Mono.
2. Renderer reload / real `gateway.ready` announcing backend `default`: Mono remains selected and rendered; backend theme cache remains absent.
3. Real JSON-RPC `config.set {key:"skin",value:"default"}`: actual backend `skin.changed` announces default; production renderer applies Nous, preserving chosen light/dark mode.
4. Actual picker selects Mono again; repeated real default config.set/event preserves manual Mono. Real `config.get skin` independently reads back backend default.

No fabricated skin event/reply, CSS appearance override, hand-written HTML, copied 587 screenshots, or production profile was used. WebSocket instrumentation observes genuine incoming events and sends explicit real requests; it does not replace backend answers. Inference alone uses the upstream synthetic mock. PNGs are unretouched; a contact sheet outside this packet is inspection-only.

## Provenance / reconstruction

`provenance.json` binds all eight PNGs/receipts, fixture hash, command exit codes, built distribution hashes, and source identity. `upstream-source-manifest.json` records all 17,035 upstream regular-file hashes. Every archive regular file remained byte-identical after install/build/capture, including upstream package manifests and lockfile. Only new E2E spec files were added to the disposable export. The upstream 587 built export was inspected only for discovery and never served for these captures.

Reconstruct source with `git archive <target>`, initialize its own `git init -q`, then `git apply fixture.patch`. Install/build with the recorded compatible npm command; `uv sync --frozen --no-dev`; add disposable root-local `venv -> .venv`; launch a dedicated credential-free Xvfb; run recorded Playwright command with `THEME_PACKET` pointing to an empty output directory. The original fixtures create and clean isolated HOME/HERMES_HOME/Electron user-data per test. No Android build or emulator action was taken.

## Honest acceptance boundary

This is **not completion of issue 292**. `issue292.json` preserves its actual scope: historical 437116f9 Desktop comparison, Android built-ins offline, Gateway-present/loading/empty/failure+retry/selected-custom states. This packet instead references the e05 backend-default contract slice. No custom Gateway definition, retry state, offline journey, phone viewport, Android pairing, or all-theme pixel comparison is certified. The chooser is naturally scrollable; lower cards are below the viewport. Selected theme is promoted to the first card by the actual UI, so the visible card order changes on selection.

`validation-history.json` preserves eight canonical validator rejections: `receipt misses required common provenance`. These are source/request/state observational receipts, not catalog-dispatched canonical visual-parity receipts. There is no appearance surface in the current capture catalog; neither the catalog nor Android source was edited to invent acceptance. Source proof and runtime assertions are separate from this schema rejection.

Final full-window images were visually inspected together; explicit Nous dark and seed-preserving Mono light were additionally inspected at full resolution. Theme/mode/persistence, backend events, successful set/get responses and absent converted-default registry are recorded. No locale/timezone/clock normalization or phone/wide matched Android acceptance is claimed; visible text is English and dates are not part of this picker. The body snapshot may include hidden shell CONNECTING text during reload, not a claim about connection-overlay stability. The selected chooser is visibly settled in final PNGs.

## Preserved attempts and limitations

Logs and rejected candidates live one level above this packet. Initial probe used the wrong waitForAppReady argument, fixed before capture. First contract attempt incorrectly waited for a chat composer after settings-route reload (the real app correctly restored Settings). Second attempt passed DOM assertions but one compositor capture was blank. Its original candidate PNGs and receipts are preserved in `attempt2/`, not relabeled. Final attempt adds visible-chooser assertions and a 1500ms compositor/animation settling interval before each screenshot; both tests passed and all eight images are nonblank. Mock provider capability probes return expected unimplemented 404s; those logs are not backend-theme failures.

The requested Android candidate was `9bbdca7b`; a read-only check during this task observed the concurrently advanced worktree HEAD `9c32b0257c096b0ef4fe2ee5fa258c7dd2952f5e`. This packet makes no APK/source pairing assertion. Parent must bind the actual approved APK SHA/source and capture fixtures when its Gradle lane is ready. Android comparison and the issue 292 ledger remain pending.

No publish, commit, push, issue mutation, or upstream manifest modification occurred.
