# Android e05 theme refresh — actual APK observation

## Result and identity

Four native, unretouched phone PNG/XML pairs: manual Mono and offline-persisted retired `default`, each in Light and Dark mode. All four were visually inspected. Manual Mono shows its checkmark beside Mono; persisted `default` paints Nous and shows its checkmark beside Nous. The raw stored retired name remains `default` after both captures; this is resolution evidence, **not an explicit backend reset**.

- Approved implementation: `9bbdca7b3700c1109a83b2934fc8a6f819d10efb`.
- Documentation HEAD inspected: `9c32b0257c096b0ef4fe2ee5fa258c7dd2952f5e`. Git comparison shows only the appearance ledger and refresh audit changed after implementation.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Local, transferred and installed-base APK SHA-256 all match `5e8584f1d2398931e7c5bd7b5aa4f9629cd70415b75169959967aaa61bad8758`.
- Install returned Success; exact package path was read back; cold launch returned Status ok. Final process was alive and crash buffer empty.
- API 37 arm64 emulator; 1280×2856 physical pixels, density 480; phone portrait only. No Espresso, instrumentation, Gradle or source changes.
- Production `MainActivity` → Settings → Appearance; not a catalog fixture Activity. No screenshot painting, HTML replica or pixel retouching.

## Isolation and state actions

The existing emulator owner profile was not synthetic: first launch restored a live session. That initial capture was rejected and excluded from this packet. No interaction with that session was performed. Existing owner data was not cleared. Capture continued in a newly created, credential-free ephemeral Android user with separately installed app data, no endpoint or credentials, and a blank initial registry. This packet includes only the subsequent synthetic Appearance surface.

1. Open settings through its exact accessibility label, then Appearance.
2. Tap Light mode and the real Mono row; capture `manual-mono-light` and read back the app DataStore containing `theme: mono`.
3. Tap Dark mode; capture `manual-mono-dark`.
4. Force-stop only the synthetic user's app. Install the offline preference input in `retired-default-offline-input.json` into that user's `hermes.preferences_pb`. Read back identical bytes before launch. It contains one synthetic Remote row, no URL/host/credentials, selected raw name `default`, and Light mode.
5. Cold-launch the same installed APK; navigate through real settings; capture `retired-default-offline-light`. Read back persisted `default`.
6. Tap Dark mode; capture `retired-default-offline-dark`; read back persisted `default` again.

The offline input is DataStore Preferences protobuf: outer field 1 repeated map entries; entry field 1 is the key; entry field 2 wraps Value field 5, a UTF-8 string. This precisely seeds existing production persistence, not a fabricated backend event. Source paths/hashes are in `source-manifest.json`; `ConnectionRegistryCodec` reads the row's `theme`, preferences resolve the active row, and `AppearanceScreen` resolves the selected preset before painting its check icon. Private execution scripts and raw readbacks remain in scratch, not this publication allowlist.

Every tap resolved a fresh exact label to an enabled app-owned clickable ancestor; `actions.json` retains those ancestors. Final screenshot captures were bracketed by both resumed-Activity and current-window-focus checks. Early lock-screen attempts were rejected; API 37 can report a resumed Activity behind SystemUI.

## Accessibility and acceptance limits

- All eleven built-in skin rows are visible. No Classic row is present. Their native UIAutomator attributes report `selected=false`, `checked=false` despite the visible checkmark and Compose source declaring selected semantics. These raw values are retained; no TalkBack selected-state or spoken-role pass is claimed.
- This APK has no Appearance capture Activity, no Appearance catalog dispatch, and no debug entry point that injects `gateway.ready`/`skin.changed` through the theme bridge while exporting owner/apply/cache telemetry. Consequently **register-only default preserving Mono, explicit backend default applying Nous, and repeated default preserving a later manual choice were not exercised on Android**.
- Exact missing fixture: a credential-free connected Android Gateway fixture delivering real ready/skin-change frames to production `GatewayGlobalEvents`/`BackendSkinSync`, with selected-name/cache/action readback; or an authorized debug fixture/build adding that seam. Preserve seed versus apply and repeated-event identities. Disk preloading cannot stand in for any of those transitions.
- No wide Android capture, matched Desktop phone viewport, stale custom-default definition, retired gold/nous-light, Gateway custom-theme list/loading/empty/failure+retry, all-theme palette comparison, or locale/timezone/clock normalization is certified.
- Four canonical validator calls fail `receipt misses required common provenance`; preserved in `validation-history.json`. These are observational receipts, deliberately not restamped as catalog-certified captures. The Appearance slice is not registered in the current catalog.
- Issue **#292 remains pending; verdict Concern**, not full rendered parity approval.

## Pairing and Desktop integrity

Desktop reference source: `e05b16348b1d06a3311237423b0a4fc30d9c5aa1`, at 1220×800. Its worker produced eight observations from two Playwright tests against the real Python backend: manual Mono, connect-seed default preserving Mono, explicit default applying Nous, and repeated default preserving later Mono, each light/dark. Its report and rejection history retain their original scope.

`desktop-pair-integrity.json` independently verifies all eight Desktop PNG/receipt hashes. A fresh initialized scratch repository applied the packet's `fixture.patch`; reconstructed `apps/desktop/e2e/theme-contract.spec.ts` exactly matches both its actual final source file and packet copy, SHA-256 `412ee6155f990a616aa4f9709293dcff3770afc209ce1ddda1daf17f2f5dd414`. The earlier `theme-reference.spec.ts` is still a discovery-only probe, not the final fixture or a member of that patch; the final command explicitly targets `theme-contract.spec.ts`. No assumption was made that the earlier failed overwrite succeeded.

Manual Mono light/dark can be paired as genuine user-selected states. Android's offline retired-name Nous images may be shown next to Desktop's explicit-default Nous only as a **same resolved appearance, different action origin** comparison. Do not rename Android files to Desktop backend-state identities.

Visible adaptation: Android uses a fixed-order one-column skin list, small previews and trailing checkmarks. Desktop uses large two-column cards with an outlined selected card promoted to the top. Labels/descriptions visible in both are consistent; this is not a pixel-equivalence assertion. Only the Desktop light Mono and dark explicit Nous references were independently visually re-inspected in this Android lane; the Desktop worker documents its own complete visual inspection.

No push, commit or issue mutation is authorized by this packet. Exact publication review remains required.
