# Classic Hermes — narrow paired reference

Refs #292. **Eight new observational captures; zero canonical acceptances.**
Manual Classic selection is demonstrated in light/dark on both platforms, after
selecting Nous Alt. This is a bounded appearance reference, not whole-issue
completion or a pixel-equivalence verdict. Overall parity ceiling remains Concern.

## Paired originals

| State | Android phone | Desktop wide | Pair boundary |
| --- | --- | --- | --- |
| Light picker | [PNG](android/light-classic-picker.png) | [PNG](desktop/light-classic-picker.png) | Same manual theme/mode; different native layout |
| Dark picker | [PNG](android/dark-classic-picker.png) | [PNG](desktop/dark-classic-picker.png) | Same manual theme/mode; different native layout |
| Light screen | [PNG](android/light-classic-screen.png) | [PNG](desktop/light-classic-screen.png) | Android disconnected; Desktop synthetic Gateway ready |
| Dark screen | [PNG](android/dark-classic-screen.png) | [PNG](desktop/dark-classic-screen.png) | Android disconnected; Desktop synthetic Gateway ready |

Four Android PNG/XML/receipt sets and four Desktop PNG/receipt sets: eight unique
platform/state/mode observations, four comparison pairs. All eight original PNGs
were visually inspected; no retouching or HTML replicas. [Artifact index](artifact-index.json)
records exact dimensions and hashes. Android: 1280×2856 pixels, density 480,
API 37.1 arm64 emulator. Desktop: 1220×800 Electron renderer on Linux.

The picker shows the exact **Classic Hermes** label and **Gold on navy, the CLI's
original look** description. Android retains registry order, immediately after
Nous Alt, with a trailing selected checkmark. Desktop promotes the selected
Classic card to the first position and outlines it; its large two-column previews
are not Android's compact one-column rows. Android's last Cyberpunk description
is clipped at the viewport bottom; this is retained, not cropped away. Both
screens show the real empty-state artwork; light uses a dark-gold wordmark and
dark a cream wordmark over navy. Copy/placeholder rotation differs and is not
asserted equal. No private conversations or credentials were used.

## Immutable identity and isolation

- Approved Android source: `e318ecd019c8ac1cd27cfb54780388aedc5dbf7f`.
- Local APK, transferred APK, and installed `base.apk` SHA-256 all matched
  `23ae78171f160078f4728c23aea1e72b2ade2d08f1e20f1133411f5f886889f5`.
- Android [source manifest](android/source-manifest.json): all 1,595 tracked
  files matched the approved commit before publication. No Gradle, rebuild,
  preference injection, application-code edits, or existing-device install.
- Desktop pin: `36922ad064d65dcf25f8f48df81e1ccf9a55de67`.
  A fresh `git archive` export was initialized as its own scratch repository,
  then installed and built there. No installed Desktop checkout or user profile
  was used. The real adjacent `setupMockBackend` starts its sandboxed Python
  Gateway and synthetic model service; it is not `perf:serve`.
- Desktop [source manifest](desktop/source-manifest.json): all 17,051 tracked
  archive entries matched the immutable export after build/capture. Thirty
  PowerShell files differ from raw Git blob bytes solely because pinned
  `.gitattributes` exports CRLF; each also matches the actual immutable archive.
  No production source was edited. Only the new capture spec was added.
- A **new task-owned AVD**, private AVD home/data path and unused explicit port
  were used. It was neither cloned from nor substituted for the existing AVD.
  Every install/input/capture addressed only its serial. Existing emulator
  package code and the physical Pixel were untouched.
- Install returned Success, `pm path` resolved the exact package, cold launch
  returned Status ok, final app process was alive and crash buffer empty.
  The final DataStore readback contained `theme: classic` and `appearance.mode:
  Dark`; per-frame Android resolved-theme getters were unavailable.
- Owned-serial `emu kill` succeeded; subsequent ADB list lacked the owned serial
  and its recorded emulator PID was absent. The pre-existing emulator remained
  listed. The owned Xvfb was also terminated. Private device identities, APKs,
  raw logs and profile data are excluded from publication.

## Reproduction

### Android: approved prebuilt APK, real native UI

Create a new isolated AVD using the installed
`system-images;android-37.1;google_apis_playstore_ps16k;arm64-v8a` image and
`pixel_10_pro` hardware definition, an unused explicit port, `-no-snapshot`,
`-no-window`, and `-no-audio`. Never reuse an existing emulator or install over
shared package code. Verify the approved APK digest before transfer/install and
verify installed `base.apk` again. Launch
`com.hermesagent.mobile.debug/com.hermesagent.mobile.MainActivity` with fresh,
credential-free application data.

1. Open settings → Appearance.
2. Select Light mode → Nous Alt → Classic Hermes; capture picker.
3. Use the real Back controls through Settings to the disconnected screen;
   capture screen.
4. Reopen Appearance; select Dark mode → Nous Alt → Classic Hermes; capture
   picker, then return through the real Back controls and capture screen.
5. Read persisted selection, process/crash state; shut down only the owned AVD
   and verify process and transport disappearance.

[Ordered actions](android/actions.json) preserve fresh exact-label membership,
enabled app-owned clickable ancestors and their bounds. Every batch and capture
was gated on both resumed MainActivity and current window focus. Screencap was
bracketed by those checks. The four XML files retain raw accessibility values:
visible checkmarks do **not** establish TalkBack selected-state/speech, and the
rows' exported `selected`/`checked` fields are not rewritten to match pixels.
This is MainActivity observation, not a registered debug-fixture dispatch.

### Desktop: genuine build and E2E fixture

Prerequisites: Node 24.16.0, compatible npm 11.17.0, uv, Linux Electron libraries,
and an owned headless X display verified with `xdpyinfo`. In a fresh disposable
export at the pin:

```sh
git init -q
git apply /path/to/this-packet/desktop/fixture.patch
npx --yes npm@11.17.0 ci --workspace apps/desktop --workspace tests-js --include-workspace-root
npx --yes npm@11.17.0 run build --workspace apps/desktop
uv sync --frozen --no-dev
ln -s .venv venv
cd apps/desktop
DISPLAY="$OWNED_DISPLAY" CLASSIC_PACKET="$OUTPUT_DIRECTORY" \
  npx playwright test e2e/classic-reference.spec.ts --workers=1 --reporter=list
```

The [spec](desktop/classic-reference.spec.ts) selects actual mode buttons and
Nous Alt then Classic rows, asserts real HTML theme/mode attributes, captures
the picker, dismisses actual Settings with Escape, asserts dismissal, and
captures the screen. It reads actual stored theme/mode and DOM, never inventing
backend skin frames or command-alias controls. Its patch was applied in a second
initialized scratch repository and reconstructed to the exact fixture hash.

Install, Desktop build and frozen Python sync exited 0. The final E2E run exited
0: **two tests passed**. Build warnings include unavailable optional native HUD
modifier, bundle-size warnings and an archive-without-HEAD placeholder install
stamp. The stamp is not source provenance; the complete immutable-archive hash
comparison above is. Mock service discovery 404s are retained in private logs,
not misreported as real provider validation. A discovery run passed two tests;
a subsequent launch stopped before test discovery due to an accidental duplicate
workspace copy in scratch. That copy was moved out, and the final two-test run
passed. Earlier discovery images/logs remain private scratch history, not extra
acceptance states.

## Canonical versus observational; remaining work

All eight calls to `visual_parity_contract.py check-receipt` rejected these
observational receipts with `receipt misses required common provenance`;
[unchanged rejection history](validation-history.json) is included. No fake
catalog identity or runtime telemetry was added to turn those into passes.
Appearance/MainActivity and this manual Desktop fixture are not the catalog's
canonical dispatch. Schema acceptance would not by itself establish parity.

This packet verifies manual Classic selection/rendering only. It does **not**
exercise Android backend seed/apply/repeat, retired-name events, custom-theme
loading/empty/error/retry, cached-shadow migration, slash aliases, physical-device
acceptance, all-theme comparison, or live Gateway behavior. Desktop's sandbox
Gateway-ready screen and Android's disconnected screen have different action
contexts; they are appearance references, not matched connected-session states.
No normalized clock/timezone/locale assertion, Android wide capture, Desktop
phone viewport or matched-viewport pixel comparison is claimed. Rotating copy
and Android system time are observational inputs, not deterministic fixture data.
The same theme/actions are reproducible; byte-identical whole-screen output is
not promised. #292 remains pending. Historical e05/older evidence is unchanged.

## Independent publication review

Reviewed capture commit `ae0ef705a54c72418f15128ef46eced5c94b94e1` without
production edits or local Gradle execution. All eight published PNGs match the
retained originals byte-for-byte; image dimensions, PNG and receipt hashes match
the artifact index. Direct visual inspection confirms the stated selection,
clipping, palette and differing connection contexts, with no private content.
Both complete source manifests were independently compared against immutable
Git archives: 1,595 Android and 17,051 Desktop entries matched. The Desktop
fixture patch reconstructed its published SHA-256 in a fresh initialized export.
The existing local APK independently hashes to the exact digest above. Installed
APK identity and device teardown remain historical capture evidence, not a new
installation or device inspection during review.

All eight canonical receipt checks again rejected the observations with the
recorded provenance error. The repository static gate passed, including 191
Python tests, product-copy/evidence checks and pin-citation range
`5477daf0..ae0ef705` (25 citations proved, 145 retained on their own pin;
266 unattributable spans are not citation proofs). Initial pin checks could not
read an uncached upstream blob; after that blob was available, the complete gate
passed. The theme registry checker matched all 12 presets against the verified
Desktop export. Whitespace and added-content privacy scans passed.

Existing unit-test XML reports for the approved production snapshot contain
3,405 debug and 2,708 release tests, zero failures/errors and one skipped test
per variant. These are historical results, not a fresh exact-publication-head
Gradle run. Fresh remote CI is a separate publication gate. Review approves
publication of this bounded observational evidence, not full parity; #292
remains pending and the overall ceiling remains Concern.

See the [appearance ledger](../../parity/appearance-themes.md) and
[capture index](../CAPTURE.md). The original capture was committed locally
without a push or issue mutation; this review authorizes PR publication only,
not merge or issue completion.
