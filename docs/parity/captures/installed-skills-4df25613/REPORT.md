# Installed Skills — disposable native capture recovery

## Result and pins

Captured **24/24 Android catalog state/theme combinations**, with **24 canonical Android receipts and 48 original XML brackets**. Preserved **14/14 unchanged Desktop reference PNGs** and their observational JSON; `pairs.json` links seven semantic states in both themes. Five Android-only states in both themes have no supplied Desktop counterpart. This is a comparison packet, not full cross-platform canonical acceptance.

- Android APK source: `4df256139b8d2bf03f903516152e299cd9949aa5`.
- Capture worktree started at `cd267491`; its only difference from the APK source was the historical blocker documentation. No source or fixture modification, rebuild, or Gradle invocation occurred.
- Approved, transferred and independently pulled installed base APK SHA-256: `1b080b6b6325b451bb8fdeaff3b98b19a804c8e87c722f9b9b855ac590b4705b`.
- Desktop source remains `587e673e2a2fae0616d8b750bb189217080f621a`. Its original reused-build limitation and rejected canonical validation are retained in [Desktop report](desktop/REPORT.md). No Desktop recapture or restamping occurred.

## Isolation and lifecycle

Read-only host discovery found an ARM64 host with 128 GiB RAM, 88% reported free memory and 818 GiB free disk. Its only installed system image was API 37.1 Google Play 16K ARM64. Created a new named AVD from that SDK image with a private scratch AVD registry and data directory, 4 GiB memory, four cores, unused explicit console/ADB ports, headless SwiftShader, no audio and no snapshots. No existing AVD was copied, wiped, stopped or reconfigured. No existing Android user or shared installed package was changed. Owner user zero was used only inside this freshly created task-owned emulator.

Installed only the approved APK on the explicit owned serial. The worker independently pulled and compared installed package bytes before every fixture launch. Focus and resumed Activity were checked against the Skills fixture before XML/screenshot capture. Captured actual device viewport was **1080×1920 at 420 dpi** (the emulator constrained the requested taller override); receipts record device-reported values, not the request. Live app PID was observed after capture, crash buffer was empty. Owned emulator console shutdown succeeded; process absence and disappearance of its serial were independently verified, while the pre-existing emulator and physical device remained connected. Private task scratch AVD/logs remain for diagnosis, excluded from publication.

## State proof and limits

The debug Activity renders the production `BotSkillsEditor`, `BotsSkillsViewModel`, typed host parsing and dispatch fence against allowlisted synthetic HTTP outcomes. Its `stage()` invokes the production ViewModel toggle and close/open paths; these are **fixture-staged actions, not ADB taps or human interaction**. Receipts correctly contain empty interaction arrays. No network mutation, real server persistence, runtime HTTP/RPC telemetry export, TalkBack speech or physical-device acceptance is claimed.

Loading actually holds GET pending and saving holds PUT pending through the unchanged production 20-second timeout. All four light/dark loading/pending screenshot brackets passed the monotonic-before-launch deadline gate: postchecks were between 7.17 and 7.32 seconds. `validation.json` retains exact measured values. Expected catalog labels were verified in both original XML brackets for every combination, in addition to canonical receipt checks. Other state names establish rendered output over source-backed fixture staging, not independently exported request traces.

Native contact-sheet inspection found painted, readable light/dark surfaces, visible switches and distinct loading/error/refusal/empty/unavailable/unconfirmed messages, without notification overlays. Disabled WIP controls remain low-contrast by design. Native layout is a compact vertical installed-only editor, not Desktop cards/catalog. Android waits for authoritative readback; Desktop references show optimistic autosave. Pending, saved and reopened are compared semantically, not pixel equivalence. Essential/refused/unconfirmed/empty/unavailable have no Desktop counterpart in the supplied packet.

Desktop PNG hashes, fixture, patch and source-manifest hashes were checked against original provenance. The harness patch was reapplied in a fresh initialized scratch repository and its reconstructed fixture hash matched. Desktop's prior four-test result is historical, not rerun here. Original canonical rejection is preserved; no invented provenance fields were added.

## Publication and reproducibility

Only synthetic PNGs, XML, receipts, existing sanitized Desktop source/fixture provenance, pairing data and this report are included. No APK, device identity, host path, emulator log or private orchestration script is published. `manifest.json` binds all packet bytes except itself. Original screenshots are unchanged. Contact sheets were inspection-only scratch derivatives, not substitute evidence.

Reproduce Android with the pinned APK/source and repository `capture-android-reference.py`, catalog surface `bot-installed-skills`, each of twelve catalog states in light/dark, Activity `com.hermesagent.mobile.BotSkillsParityActivity`, `--launch-fixture` and exact catalog accessibility label. Bind every ADB operation to a new credential-free task-owned emulator; do not reuse an owner install. The remote adapter must use full `dumpsys window` when the `windows` subcommand omits focus fields. Two initial transport/adapter attempts failed before completing their next state; no fabricated receipt or altered pixels were substituted. Fresh retries completed the entire catalog.

See [feature ledger](../../bot-installed-skills.md), [historical blocker](../../installed-skills-native-capture-blocker.md), and [media capture index](../../../media/CAPTURE.md#installed-skills-supplement). No push, PR or new build/test certification is part of this recovery.
