# e05 theme refresh — narrow paired observation

**Verdict: Concern; #292 remains pending. Exact publication review required.**

- Android approved code: `9bbdca7b3700c1109a83b2934fc8a6f819d10efb`; documentation-only capture HEAD: `9c32b0257c096b0ef4fe2ee5fa258c7dd2952f5e`.
- Android local/transfer/installed APK SHA-256: `5e8584f1d2398931e7c5bd7b5aa4f9629cd70415b75169959967aaa61bad8758`.
- Desktop source: `e05b16348b1d06a3311237423b0a4fc30d9c5aa1`.
- Four native Android phone images, eight genuine Desktop wide references. This is not a matched phone/wide matrix or twelve equivalent acceptance states.

## Images and action identities

| Mode | Android native observation | Desktop reference | Pairing scope |
|---|---|---|---|
| Light | [Manual Mono](android/manual-mono-light.png) | [Manual Mono](desktop/light-manual-mono.png) | Both real manual selections |
| Dark | [Manual Mono](android/manual-mono-dark.png) | [Manual Mono](desktop/dark-manual-mono.png) | Both real manual selections |
| Light | [Offline persisted default resolves Nous](android/retired-default-offline-light.png) | [Explicit backend default applies Nous](desktop/light-explicit-default-applies-nous.png) | Same resolved appearance, **different action origin** |
| Dark | [Offline persisted default resolves Nous](android/retired-default-offline-dark.png) | [Explicit backend default applies Nous](desktop/dark-explicit-default-applies-nous.png) | Same resolved appearance, **different action origin** |

Android visibly checks Mono after manual selection and Nous for a persisted retired `default`; the raw retired value remains stored. Android fixed-order rows and trailing checks differ from Desktop's selected-first two-column cards and selected outline. No pixel equivalence, remote CSS support, or matched typography/spacing claim is made.

## What remains unverified

Android backend connect-seed, explicit apply and repeated-event/manual-override transitions were **not exercised**. The exact APK has no Appearance debug fixture/event-dispatch seam exposing those transitions and their runtime state. Offline DataStore seeding must not be called a backend reset. A credential-free connected Gateway fixture with real ready/skin-change frames, or an authorized debug fixture/build, is needed. Wide Android and Desktop phone captures also remain missing.

Native API 37 XML reports all skin rows `selected=false`/`checked=false` although checkmarks are visible; raw XML is preserved. No TalkBack selected-state pass. No custom-Gateway loading/empty/failure/retry states, stale custom-default definition, or locale/timezone/clock normalization were captured. Historical #292 scope and pins remain unchanged.

All twelve observational receipts fail the canonical validator, with rejection histories preserved by platform. Appearance is not registered in the capture catalog. These packets are evidence, not canonical acceptance.

## Evidence and reproducibility

- [Android report and source/action limits](android/REPORT.md), [provenance](android/provenance.json), [source manifest](android/source-manifest.json), [native actions](android/actions.json), [offline input](android/retired-default-offline-input.json), [validator history](android/validation-history.json).
- [Desktop report](desktop/REPORT.md), [provenance](desktop/provenance.json), [fixture patch](desktop/fixture.patch), [validator history](desktop/validation-history.json).
- [Independent Desktop fixture reconstruction and hash check](android/desktop-pair-integrity.json): final `theme-contract.spec.ts` matches its actual source, packet copy and applied patch. Earlier `theme-reference.spec.ts` remains only the discovery probe; its failed overwrite was not assumed successful.
- [Appearance ledger](../../parity/appearance-themes.md), [capture index](../CAPTURE.md#e05-theme-refresh-supplement).

No APK, device serial, capture-host script or initial live-profile capture is in this publication. Application source, Gradle, existing owner profile data, historical evidence, remote issues and branches were not modified by the capture lane. The platform reports preserve their pre-publication handoff status.

## Independent publication review

The publication reviewer inspected all twelve original-image thumbnails: Android checkmarks match Mono/manual and Nous/offline-default; Desktop selected cards match the four reported states in both modes. Images are nonblank and expose no account/session content. All twelve PNGs match the original worker packets and provenance hashes; Android XML hashes and Desktop receipt hashes also match. Native XML selected/checked false remains disclosed, not repaired.

The seven Android source-manifest entries match approved implementation `9bbdca7b`; all 17,035 Desktop source-manifest entries match the original disposable source. A fresh initialized scratch repository reconstructs the final Desktop fixture patch to its recorded hash. The publication allowlist passes private-path, device-serial and credential-pattern scanning. All twelve canonical receipt calls reproduce the recorded provenance rejection, not acceptance.

Repository static invariants pass, including 177 Python tests and citation-range checks. Existing full-suite XML independently totals 3,335 debug and 2,643 release tests, zero failures/errors and one skip each; the focused snapshot totals 96 passing tests. The retained build log reports `BUILD SUCCESSFUL`, and the local APK matches the recorded SHA-256. These are retained results for underlying implementation `9bbdca7b`, not a new Gradle run or rendered acceptance at the documentation head. No local Gradle or device operation was performed during publication review. #292 remains pending; publication is a draft, bounded theme-contract PR.
