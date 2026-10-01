# Bounded existing-Bot model editor — paired visual acceptance

Completed for the seven registered model-editor states in dark and light: **14 Android receipts, 14 Desktop receipts, 30 original PNGs** (28 primary images plus two Desktop refusal reopens). This is bounded synthetic model-editor acceptance, not whole-Bot parity, live Gateway acceptance, physical-device acceptance or proof of a publication-commit APK. Fresh GitHub review and exact-head CI remain separate gates. Refs #194.

## Provenance and reproduction

- Android captures were built from dirty base `fba060888b21a3ce0c456809f1f464fd0e5938ba`, not an unchanged committed artifact. Local and installed APK SHA-256 both equal `e15abfce6b8873289aff499b37a14fde90144a60dcf2e869c53dadbba7ed3966`. The APK is deliberately not published.
- [Provenance](provenance.json) records original runtime-patch and publication-time source-snapshot hashes separately, plus every PNG's dimensions and hash. The original [runtime patch](android/android-runtime.patch) is unchanged. The complete [publication source snapshot](android/publication-source-snapshot.patch) adds the contemporaneous validator/tests/docs changes; it must not be misrepresented as an original capture-time full-diff receipt. Apply either patch independently to an export of the Android base using `git apply`; do not apply both sequentially. Each Android capture retains exact reconstructible fixture source JSON, matched byte-for-byte to the current sources before publication.
- Desktop uses actual Electron components and the real E2E mock backend at `587e673e2a2fae0616d8b750bb189217080f621a`. [Original provenance](desktop/provenance.json) records immutable export comparison and reused matching build hashes. Apply [capture-only.patch](desktop/capture-only.patch) with `git apply` to an immutable export; its reconstructed `apps/desktop/e2e/model-contract-v2.spec.ts` must match [the retained spec](desktop/model-contract-v2.spec.ts). From `apps/desktop`, set `PARITY_CONTRACT` to the mobile checkout and `PARITY_OUT` to a new scratch directory, provide a dedicated Xvfb display, and run `npx playwright test e2e/model-contract-v2.spec.ts --workers=1`.
- Skin mono; explicit dark/light; en-US; UTC; fixture wall clock `2026-09-17T16:00:00Z`; live request timers. Android SystemUI clock is not normalized by these app-local inputs. Captures show synthetic model data only; incidental Desktop capability descriptions are public catalog content, not exercised capability acceptance.
- Original PNGs, receipts and safe observed RPC/action evidence are unchanged. No APK, raw execution log, private capture-host script, serial, private path, archive or AppleDouble file is included. Retained patch whitespace is hash-significant and excluded only from whitespace lint, not source review.

Initialize each disposable export with `git init -q` before applying patches. This prevents Git from discovering an unrelated parent repository and silently skipping paths; verify the reconstructed file hashes, not only `git apply` exit status.

## Paired report

Images below are the original captures, not mockups or retouched comparisons. Android is a narrow scrollable phone editor; Desktop is a wide Advanced editor or a separately located native dialog/notice. Different crop dimensions are intentional. Receipt files sit beside each image: Android `contract.json`; Desktop `<state>-<theme>.receipt.json`.

| State | What is accepted | Platform adaptation |
|---|---|---|
| loaded | Named v1 read, scoped inventory, no writes | Android stacked touch controls vs Desktop provider/model row |
| inventory-loading | Actual pending inventory; screenshot bracket below unchanged 20-second deadline | Android retains original fields with loading text; Desktop spinner hides fields (`fields:null`) |
| inventory-error | Actual options refusal, original pair, no writes | Android explanatory banner; Desktop native manual fallback |
| manual | Real Enter manually action, unchanged original pair | Android scroll to named Model ID input; Desktop custom form |
| confirmation | One unconfirmed model-only warning; authoritative v1 | Android fixture-staged production action and inline warning; Desktop actual Save and shared confirmation dialog |
| saved | Warning, confirmed apply, exact named v2 readback | Android staged production consent/readback, inline Model saved.; Desktop actual consent, close, reopen and v2 read |
| save-refused | One initial unconfirmed refusal; authoritative v1, no retry | Android staged inline uncertain-write notice retains draft v2, tells user to reopen; Desktop native failure notice closes editor, separate reopen proves original v1 |

Android staged states are **not recorded Save/consent gestures**. Manual and scroll gestures are recorded. Different original/draft/authoritative values on refusal are intentional, not interchangeable claims.


## Original paired images


### Dark

| State | Android | Desktop |
|---|---|---|
| loaded | ![Android loaded dark](android/dark/bot-model-loaded/reference.png) | ![Desktop loaded dark](desktop/accepted/loaded-dark.png) |
| inventory-loading | ![Android inventory-loading dark](android/dark/bot-model-inventory-loading/reference.png) | ![Desktop inventory-loading dark](desktop/rejected/inventory-loading-dark.png) |
| inventory-error | ![Android inventory-error dark](android/dark/bot-model-inventory-error/reference.png) | ![Desktop inventory-error dark](desktop/accepted/inventory-error-dark.png) |
| manual | ![Android manual dark](android/dark/bot-model-manual/reference.png) | ![Desktop manual dark](desktop/accepted/manual-dark.png) |
| confirmation | ![Android confirmation dark](android/dark/bot-model-confirmation/reference.png) | ![Desktop confirmation dark](desktop/accepted/confirmation-dark.png) |
| saved | ![Android saved dark](android/dark/bot-model-saved/reference.png) | ![Desktop saved dark](desktop/accepted/saved-dark.png) |
| save-refused | ![Android save-refused dark](android/dark/bot-model-save-refused/reference.png) | ![Desktop save-refused dark](desktop/accepted/save-refused-dark.png) |

Desktop refusal reopened, original v1: ![Reopened dark](desktop/accepted/save-refused-dark.refused-reopened.png)


### Light

| State | Android | Desktop |
|---|---|---|
| loaded | ![Android loaded light](android/light/bot-model-loaded/reference.png) | ![Desktop loaded light](desktop/accepted/loaded-light.png) |
| inventory-loading | ![Android inventory-loading light](android/light/bot-model-inventory-loading/reference.png) | ![Desktop inventory-loading light](desktop/rejected/inventory-loading-light.png) |
| inventory-error | ![Android inventory-error light](android/light/bot-model-inventory-error/reference.png) | ![Desktop inventory-error light](desktop/accepted/inventory-error-light.png) |
| manual | ![Android manual light](android/light/bot-model-manual/reference.png) | ![Desktop manual light](desktop/accepted/manual-light.png) |
| confirmation | ![Android confirmation light](android/light/bot-model-confirmation/reference.png) | ![Desktop confirmation light](desktop/accepted/confirmation-light.png) |
| saved | ![Android saved light](android/light/bot-model-saved/reference.png) | ![Desktop saved light](desktop/accepted/saved-light.png) |
| save-refused | ![Android save-refused light](android/light/bot-model-save-refused/reference.png) | ![Desktop save-refused light](desktop/accepted/save-refused-light.png) |

Desktop refusal reopened, original v1: ![Reopened light](desktop/accepted/save-refused-light.refused-reopened.png)

## Historical rejection and revalidation

Desktop's original [report](desktop/REPORT.md), [validation](desktop/validation.json), manifest and `accepted/` / `rejected/` directories remain unchanged: **12 accepted, 2 rejected**, run exit 1. The loading fields requirement contradicted the actual pinned spinner branch. The corrected validator accepts the same **14 unchanged receipts**; [separate revalidation](desktop/revalidation-loading-fix.json) preserves original hashes and results. This is not recapture or retrospective rewriting of the first run.

Android's first attempt had two manual boundary failures because caption and explicitly named input were counted twice. It was rejected, semantic resolution was corrected, the fixture privacy guard tightened, the APK rebuilt, and **all 14 states were recaptured**. The failed raw archive remains local and is not published or promoted. The final receipt set alone is accepted here.

## Verification

[Android verification](android/verification.json) records 3,271 debug and 2,594 release JVM tests, zero failures/errors and one skipped per variant; prior full incremental `check assembleDebug` succeeded. Python suite: 166 passed. These are original build receipts, not a claim that Gradle was rerun during publication. Independent runtime/export/validator review approved the dirty source snapshot before publication.

Loading: Android dark 9061.042630→10126.437713 ms, light 8921.275588→9965.913505 ms; postchecks 14.184557750 / 13.999371292 seconds after launch. Desktop dark 1789→2350 ms, light 1957→2499 ms. All remain pending with null response/error before 20000 ms. Saved has two model-only writes and exact named v2 readback; confirmation/refusal retain authoritative v1; read/manual states have zero writes.

Publication reruns receipt validation, PNG/source hashes, fixture reconstruction, static repository gates, Python tests and citation/diff checks without Gradle. Reset, creation/duplication model setup, credentials, skills/toolsets/MCP, avatars and whole Bot management remain outside this acceptance.
