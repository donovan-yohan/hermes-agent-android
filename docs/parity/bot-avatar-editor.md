# Existing-Bot avatar import and clear

## Pin

Hermes Desktop/Gateway `587e673e2a2fae0616d8b750bb189217080f621a`.
This delta does not restamp the historical read-only captures in [bot identity](bot-identity.md).

## Contract

- `tui_gateway/methods_profiles.py:408-443`: `profiles.set_asset` takes the exact
  profile `name`, fixed `asset: "avatar"`, and either inline `data` or explicit
  `clear: true`. The **2,000,000-byte inclusive cap counts base64-decoded file
  bytes**, not base64 characters and not decoded bitmap memory. The server
  sniffs PNG/JPEG/WebP signatures; Android sends normalized PNG only.
- `tui_gateway/methods_profiles.py:446-460`: named `profiles.get_asset` returns
  `found:false` or `found:true,mime,size,data`. Upload success is not enough:
  Android checks the receipt, then exact byte readback (or confirmed absence).
- `apps/desktop/src/plugins/hermes-bots/data.ts:375-405`: unchanged image must not
  trigger a write, especially no automatic clear that could erase another client’s
  new avatar. These writes have no CAS. Android does not pretend otherwise.
- `apps/desktop/src/plugins/hermes-bots/avatar-image.ts:17-70`: centre crop to
  256×256 PNG, device chooser, 15 MB pre-normalization source guard.
- `apps/desktop/src/plugins/hermes-bots/avatar-picker.tsx:123-139`: order is Bot,
  Generate, Upload, Pet; removal is explicit. `i18n.ts:675-680` spells the
  tabs that way and labels removal “Remove image — use shape”. Android preserves
  tab labels/order; its shorter “Remove image” does not claim a shape editor.
- `apps/desktop/src/plugins/hermes-bots/profile-config.tsx:1-49`: advanced profile
  configuration is a separate describe/configure capability. Image bytes never
  go into `profiles.configure`, SOUL.md, plugin storage or attachment staging.

## Android implementation

Existing Bot → Edit profile now includes the avatar editor. Upload invokes
Android `GetContent` with `image/*`; only a returned `content://` selection is
opened. No filename/path/URI reaches RPC, logs or persistent storage. No durable
URI grant is taken. Cancellation preserves the draft and writes nothing.

Normalization runs off-main behind a single-worker semaphore. The input stream
is closed and limited to 15,000,000 bytes. Bounds are checked before allocating
pixels: edge ≤8192 and ≤16,777,216 source pixels. The software first frame is
sampled to ≤256 on either edge and ≤262,144 allocated pixel bytes, centre-cropped
and scaled to 256×256, then encoded PNG with the independent wire cap. JPEG EXIF
orientation is applied; GIF is static first-frame input. Unsupported/corrupt inputs
fail closed; unlike Desktop’s catch fallback, original input is never uploaded.
Only the normalized draft and bounded authoritative read stay in memory.

The initial avatar is a direct named editor read, separate from roster `has_avatar`
gating. Save avatar is a separate explicit action from identity/model Save.
Remove image stages removal; only Save avatar sends `clear:true`, and only when
an original existed. Removing a newly selected image over an absent original
remains a no-op. Snapshot/ticket/revision/endpoint fences reject stale callbacks;
an admitted save remains pending across close/reopen and cannot be duplicated.
Uncertain writes consume the editor until close/reopen. Named readback precedes
success; cache invalidation plus roster refresh follows attempted writes on the
same endpoint, including a save completed after its dialog closed.

## Capture fixtures and verification boundary

`bot-avatar-editor-synthetic-v1` registers loaded/loading/picking/cancel/error/
changed/saved/cleared in the catalog, workflow choices and debug Activity.
`BotAvatarFixtureRpc` implements only the real named asset RPCs over memory,
through `GatewayPluginHost` and its dispatch fence. Its saved and cleared states
run production actions and named readback; changed uses the actual normalizer.
Picking is **app waiting state**, not system-picker pixels. Mutation states are
fixture-staged, not recorded user gestures. No Desktop renderer or physical phone
has been captured for this delta. The production 60-second read deadline remains
unchanged; a late loading capture must fail rather than be relabelled.

The parent build lane reports approved code review and green full-suite gates:
3,301 debug tests and 2,618 release tests. Earlier scratch-only Kotlin/JUnit and
Robolectric checks covered repository, ViewModel, native decode/crop, Compose
controls, ActivityResult cancellation and fixture paths; those earlier checks
alone did not establish clean-build acceptance. No additional Gradle execution
was performed for this publication. Installed-APK visual acceptance, paired real
Desktop captures and live-Gateway acceptance remain separate, unfulfilled gates
until their actual evidence is retained.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Browser file input and shared dialog Save | mobile-adaptation | Android content picker; explicit Save avatar independent of identity Save; inline result | Touch/ActivityResult ownership and partial-write clarity; `BotAvatarEditor`, `BotsAvatarViewModel` |
| Remove image — use shape | mobile-adaptation | Remove image | Android returns to its shared profile glyph, not Desktop’s configurable shape avatar; avoid a false capability claim |
| Browser normalization can return undecoded original on failure | mobile-adaptation | Fail closed; tighter dimension/pixel limits and sampled decode | Bounded native memory on phones; `AndroidAvatarImporterTest` |
| Bot shape/color authoring, Generate, Pet | omission | Visible disabled WIP; no pet asset kind, gallery URL fetch or image generation call invented | coming soon — #194; reachable sprite sheets and inline generated images remain unproved |
| New/duplicate avatar authoring | omission | Avatar remains WIP outside existing-Bot Edit | coming soon — #194; no asset write before a profile exists |
| Shared dialog preview and close/reopen saved result | mobile-adaptation | Scrollable inline phone editor; standalone production-editor debug capture | Small viewport; catalog names staged app states rather than claiming Desktop-equivalent outcomes |
| Paired rendered acceptance | omission | No new pixels claimed | deferred: #194 — parent build/capture lane and real Desktop editor comparison remain pending |

## Visual report

- pending: #194
- Verdict: Concern until exact-source Android/Desktop renders and clean build gates pass.
