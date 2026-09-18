# Bot identity and read-only avatars

## Pin

| Authority | Revision | Read method |
|---|---|---|
| Hermes Desktop and Gateway | `d177b119e9c56c9ddc0b7379ffce52341ec06584` | read-only `git -C <snapshot> show <sha>:<path>` |

This page records the Android read-only avatar slice. It does not enable upload,
clear, pets, generation, or profile mutation.

## Sources and implementation evidence

| Contract | Pinned source | Android implementation |
|---|---|---|
| Profile identity fallback | `apps/desktop/src/components/ui/profile-glyph.tsx:10-43` at the pin | `ProfileGlyph` keeps the default home mark and named initial as its fallback, with existing semantic labels, active ring, fixed size and `HermesTheme.tokens`. |
| Avatar read | `tui_gateway/methods_profiles.py:662-676` at the pin | `GatewayAvatarAssetSource` sends only `profiles.get_asset` with the accepted raw wire `name` and `asset:"avatar"`; `AvatarPayload`/`AndroidAvatarDecoder` validate and bound the response. |
| Accepted row provenance | `tui_gateway/methods_profiles.py:205-249` at the pin | `AvatarRosterCoordinator` attaches opaque process-local refs only to accepted profile/Bots rows. Null, synthetic, stale, providerless and reconstructed rows stay fallback and make zero requests. |
| Shared user-visible surfaces | `apps/desktop/src/components/ui/profile-glyph.tsx:10-43` and the Bots roster source at the pin | `ProfileGlyph` is shared by rail, picker, profile roster, session tags and the Bots row. `BotRowItem` uses a 36dp shared glyph with 22dp rounded treatment; chat row tap and separate Routines control remain unchanged. |

## States

The debug-only `ProfileAvatarsParityActivity` mounts production `ProfileGlyph`,
`BotsRosterScreen`, `AvatarRosterCoordinator`, `ProfileAvatarRepository`,
`AndroidAvatarDecoder`, and captured fake transport over generated synthetic data.
The catalog registers `profile-ready`, `profile-fallback`, `profile-loading`,
`profile-unavailable`, `bots-ready`, and `bots-fallback`. It never contacts a
Gateway or includes a host, credential, profile path, or private session text.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Desktop's pinned profile glyph is an identity fallback mark and has no same-pin avatar-read E2E fixture at this revision | omission | Android extends that same glyph with a read-only static PNG/JPEG/WebP avatar when the accepted Gateway row authorizes it; fallback remains visible for missing, loading, refused, corrupt, stale, synthetic or unavailable rows | deferred: #318 — no Desktop avatar fixture exists at the pin, so no Desktop screenshot is fabricated |
| Desktop BotFace can include mood/roster-specific presentation | mobile-adaptation | Android uses the shared profile glyph and fixed 36dp cover-cropped static image to keep one identity treatment across rail, picker, profile roster and Bots on a phone | 36dp touch/space budget and shared component contract |
| Desktop has no phone lifecycle binding equivalent | mobile-adaptation | Avatar subscriptions are admitted only while the real Compose lifecycle is resumed, disposed on pause/row removal/plugin disposal, and invalidated once on foreground/explicit refresh | `ProfileAvatars.kt`, coordinator revision/current draw guard, lifecycle test |
| Desktop's own rendered avatar fixture is absent at the pin | omission | The visual capture catalog records the Android production fixture and an honest source-only Desktop boundary; no side-by-side pixel claim is made | deferred: #318 |

## Visual report

- pending: #318

No rendered Desktop/Android side-by-side is claimed here. The Android half is
catalogued through `docs/parity/visual-capture-surfaces.json` and is dispatched
by the parent-owned visual-parity workflow. The pinned Desktop export has no
avatar-read fixture; a copied image or handwritten HTML would not be evidence.

## Verification boundary

- Production Kotlin compile passed after provider, glyph, Bots row and fixture changes were introduced in incremental local milestones.
- Focused coordinator/decoder/transport/ProfileGlyph tests passed.
- `ProfileAvatarDrawGuardTest` passed with 1 test / 0 failures using native Robolectric graphics, production `ProfileGlyph`, production coordinator/repository, captured transport and generated PNG; owner revocation happened after composition and before the synchronous draw without waiting for collectors.
- Parent still owns mutation-red/restored-green draw-guard evidence, full lint/assemble/invariant/parity/copy gates, exact-head CI and visual capture inspection.
