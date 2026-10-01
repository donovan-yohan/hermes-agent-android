# Avatar fallback and registered-navigation verification

## Snapshot and scope

- Base: `60cc7deb8035e8ca3061021ebe11118af9dc1be1`, working-tree build; no commit or push.
- APK SHA-256: `792b3cee351d1239bed2c2a90c19f414dd2bc5f47c8eef438364bd9bacc1aee5`.
  Local, transferred and pulled installed bytes matched, including the final installed-byte check after capture.
- [Build provenance](provenance/build-provenance.json), [exact source patch](provenance/android-working-tree.patch),
  [604-entry app source manifest](provenance/android-source-hashes.json), [verification](verification.json).
- All 127 files in the previous `bot-avatar-v1` packet remain byte-identical. Neither old pixels nor their receipts were restamped.
- This is a second evidence packet, **not** a new v2 platform-equivalence contract. Captures use the existing `bot-avatar-editor-synthetic-v1` fixture and v1 receipt schema.

## Behavior

`BotAvatarEditor` now shows the shared Android `ProfileGlyph` at 96dp when no decoded image is available.
The canonical immutable ticket target determines identity; the active theme determines fallback ink.
A cleared draft has no roster avatar reference and cannot reuse the photo still cached by the roster.
This covers cleared/absent, loading and refused editor reads without changing dispatch ownership, consent, retries or save/readback behavior.

Desktop `profile-ops.ts:94-118,194-201` at `587e673e2a2fae0616d8b750bb189217080f621a`
intentionally uploads a 160px shape raster for inter-agent notices and excludes it from the live roster face.
Android clear is a scoped adaptation: confirmed absence plus its existing profile glyph. It does not synthesize Desktop's raster.
Desktop `ProfileGlyph` is independently used by profile navigation/roster and session ownership tags; it is not the Bots shape/photo `BotFace`.
Shape/color authoring, Pet and Generate remain disabled WIP under #194. Strict cross-platform visual equivalence is not claimed.

## Executed checks

- RED: existing `explicitRemovalWritesOnlyAfterSaveAndShowsReadbackResult` failed on the new visible-fallback assertion before the editor change.
- Focused avatar, fixture, `ProfileGlyphTest` and `ProfileAvatarDrawGuardTest` selection passed.
- Full `check assembleDebug --rerun-tasks --no-parallel --max-workers=1`, in-process Kotlin,
  Gradle `-Xmx6g -XX:MaxMetaspaceSize=2g`: **exit 0**, 115 tasks executed, 8m35s.
- [JUnit totals](provenance/test-totals.json): debug **3,304**, release **2,618**; each has zero failures/errors and one skipped.
- The first foreground full run was interrupted by the tool's 420-second timeout; no success was inferred. A fresh sequential background full rerun completed successfully. Existing compiler opt-in/deprecation warnings remain.

`BotsAvatarRegisteredJourneyTest.registeredPhotoSaveRosterReopenClearRosterReopenHasNoStalePhoto`
mounts the **production registered route** with `GatewayPluginHost`, its dispatch fence, `AvatarRosterCoordinator`, native decoder,
and an in-memory synthetic RPC endpoint. Through real Compose actions it opens Actions → Edit, expands the real sheet via accessibility,
uses the registered ActivityResult content picker contract, saves a normalized photo, closes to a visibly blue roster glyph,
reopens the photo, removes it without a write until Save, saves clear, closes to a photo-free roster, then reopens the shared fallback.
It verifies exactly two asset mutations, exact upload/clear fields, named readback and disabled Save on reopened absence.
The display label deliberately differs from canonical `worker`; fallback semantics remain canonical.
Roster pixels are synchronous native Robolectric decor renders, **not emulator screenshots**. Existing endpoint/no-replay tests passed unchanged.

## Fresh Android screenshots

Captured on the API 37 emulator using the repository ADB/UIAutomator worker. Each folder includes the original PNG,
app-only accessibility XML and validated receipt. These are standalone production-editor fixture states, not screenshots of the registered journey.
Clear is fixture-staged through production clear/save/readback. No new Desktop captures or physical-device verification occurred.
There is no runtime RPC/ticket exporter in this v1 fixture; receipt validation is not a claim of live-Gateway acceptance.

| State | Light | Dark | Pixel review |
|---|---|---|---|
| Loaded photo | [PNG](android/loaded-light/reference.png) | [PNG](android/loaded-dark/reference.png) | Blue synthetic image; no fallback replacing the loaded photo |
| Loading fallback | [PNG](android/loading-light/reference.png) | [PNG](android/loading-dark/reference.png) | Shared green S; loading label; held read captured before the unchanged deadline |
| Refused-read fallback | [PNG](android/error-light/reference.png) | [PNG](android/error-dark/reference.png) | Shared green S and explicit refusal; no photo |
| Cleared fallback | [PNG](android/cleared-light/reference.png) | [PNG](android/cleared-dark/reference.png) | Shared green S and “Avatar removed.”; no stale photo |

All eight original images were reviewed via an unmodified-source contact sheet, with the cleared-dark original also inspected directly.
Light and dark surfaces are painted, header remains below status icons, and labels are readable; disabled WIP actions remain muted.
The source patch was independently applied to an initialized export of the base; all 604 app source hashes matched.
All eight receipts and PNG hashes passed validation; previous packet hashes passed unchanged.
