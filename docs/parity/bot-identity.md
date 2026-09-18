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
| Profile identity fallback | `apps/desktop/src/components/ui/profile-glyph.tsx:10-43` at the pin | `ProfileGlyph` keeps the default home mark and named initial as its fallback, with existing semantic labels, active ring, fixed size and `HermesTheme.tokens`. The pinned source emits utility-class spans, not a `.profile-glyph` hook. |
| BotFace avatar read | `apps/desktop/src/plugins/hermes-bots/avatar.tsx:999-1012` and `profile-ops.test.ts` at the pin | Parent's isolated real Desktop/Gateway proof served a synthetic data URL; the executed locator `page.getByRole("button", {name:/^alpha\b/i}).filter({visible:true}).first().locator("img")` found a real `img` with `naturalWidth:32`. This proves the BotFace image seam, not profile-rail `ProfileGlyph` avatars. |
| Avatar read | `tui_gateway/methods_profiles.py:662-676` at the pin | `GatewayAvatarAssetSource` sends only `profiles.get_asset` with the accepted raw wire `name` and `asset:"avatar"`; `AvatarPayload`/`AndroidAvatarDecoder` validate and bound the response. |
| Accepted row provenance | `tui_gateway/methods_profiles.py:205-249` at the pin | `AvatarRosterCoordinator` attaches opaque process-local refs only to accepted profile/Bots rows. Null, synthetic, stale, providerless and reconstructed rows stay fallback and make zero requests. |
| Shared user-visible surfaces | `apps/desktop/src/components/ui/profile-glyph.tsx:10-43` and the Bots roster source at the pin | `ProfileGlyph` is shared by rail, picker, profile roster, session tags and the Bots row. `BotRowItem` uses a 36dp shared glyph with 22dp rounded treatment; chat row tap and separate Routines control remain unchanged. |

## States

The debug-only `ProfileAvatarsParityActivity` mounts production `ProfileGlyph`,
`BotsRosterScreen`, `AvatarRosterCoordinator`, `ProfileAvatarRepository`,
`AndroidAvatarDecoder`, and captured fake transport over generated synthetic data.
The catalog registers `profile-ready`, `profile-fallback`, `profile-loading`,
`profile-unavailable`, `bots-ready`, and `bots-fallback`. The named profile
states exercise distinct production transport outcomes: `profile-ready` returns
an image, `profile-fallback` advertises an avatar then returns `found:false`,
`profile-loading` advertises an avatar and holds `profiles.get_asset` pending,
and `profile-unavailable` advertises an avatar then refuses that asset read.
`bots-fallback` uses `has_avatar:false` and therefore makes no asset request.
The fixture never contacts a Gateway or includes a host, credential, profile
path, or private session text.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Desktop `ProfileGlyph` is the fallback identity mark; its pinned source emits utility-class spans and no `.profile-glyph` hook | mobile-adaptation | Android keeps the same home/initial fallback and extends the accepted-row path with a read-only static PNG/JPEG/WebP avatar; fallback remains visible for missing, loading, refused, corrupt, stale, synthetic or unavailable rows | `profile-glyph.tsx:10-43` at `d177b119`; no profile-rail avatar equivalence is claimed |
| Desktop BotFace renders an uploaded avatar in the Bots surface | mobile-adaptation | Android uses the shared profile glyph and fixed 36dp cover-cropped static image to keep one identity treatment across rail, picker, profile roster and Bots on a phone | Real pinned BotFace proof: `/tmp/hm-desktop-avatar-selector-proof/test.log`; executed CSS locator matched exactly one Alpha row, while `.profile-glyph` matched zero; 36dp touch/space budget and shared component contract |
| Desktop BotFace uses 22% corner rounding | drift | Android's 22dp rounding on a 36dp glyph produces a circular image instead of the Desktop rounded square; this is not exact shape parity | Actual ready captures and source comparison in the [capture review](https://github.com/donovan-yohan/hermes-agent-android/pull/323#issuecomment-5734724739); follow-up: #194 |
| Desktop has no phone lifecycle binding equivalent | mobile-adaptation | Avatar subscriptions are admitted only while the real Compose lifecycle is resumed, disposed on pause/row removal/plugin disposal, and invalidated once on foreground/explicit refresh | `ProfileAvatars.kt`, coordinator revision/current draw guard, lifecycle test |
| Aligned Desktop/Android scene comparison remains pending | omission | Android's six dark states are rendered and inspected; the executed Desktop proof is Bots-only, with different scene inputs, and does not establish profile-rail avatar parity | deferred: #194 — [Android capture review](https://github.com/donovan-yohan/hermes-agent-android/pull/323#issuecomment-5734724739) |

## Visual report

- report: https://github.com/donovan-yohan/hermes-agent-android/pull/323#issuecomment-5734724739
- commit: 493fefc118a5f7344187ff7be85f1ee5a258d6a8
- [Pinned Desktop evidence packet](visual/desktop-bot-render-evidence/README.md), delivered separately in PR #324.
- Verdict: Android dark-state rendering verified; Concern for shape drift and unmatched Desktop scene inputs. No full side-by-side approval.

The Android capture receipts were downloaded and validated against their source,
catalog, and matching built/installed APK hashes. Ready images visibly render
the same synthetic blue PNG used by the real Desktop BotFace fixture. Missing,
pending and refused states render the initial fallback; distinct transport
semantics are established by regression tests, not inferred from equal pixels.
No physical-device, live external-Gateway, light-theme or aligned side-by-side
acceptance is claimed. A later documentation-only commit does not change the
capture's recorded source SHA or turn it into a new-head receipt.

## Verification boundary

- Production Kotlin compile passed after provider, glyph, Bots row and fixture changes were introduced in incremental local milestones.
- Focused coordinator/decoder/transport/ProfileGlyph tests passed.
- `ProfileAvatarsParityFixtureTest` passed 4 tests / 0 failures, proving `found:false`, pending asset loading, refused asset, and absent/no-request outcomes reach production repository state mapping; the fixture records `profiles.list` followed by `profiles.get_asset` only for avatar-authorized profile states.
- `ProfileAvatarDrawGuardTest` passed with 1 test / 0 failures using native Robolectric graphics, production `ProfileGlyph`, production coordinator/repository, captured transport and generated PNG; owner revocation happened after composition and before the synchronous draw without waiting for collectors.
- Parent still owns mutation-red/restored-green draw-guard evidence, full lint/assemble/invariant/parity/copy gates, exact-head CI and visual capture inspection.
