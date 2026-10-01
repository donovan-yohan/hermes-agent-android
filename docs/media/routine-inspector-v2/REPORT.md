# Routine inspector v2 — bounded visual acceptance

Open [side-by-side.html](side-by-side.html) locally for the original Android and
Desktop images. This is inspector acceptance, **not whole-Bot or pixel parity**.

## Evidence and identity

- 28 original PNGs: seven states × dark/light × Android/Desktop. States are
  inspector, sparse, paused, completed, overdue-inspector, overdue, read-failure.
- 28 catalog receipts validated; [validation.json](validation.json) retains the
  capture results. Publication revalidated every receipt and image hash.
- Desktop immutable source: `587e673e2a2fae0616d8b750bb189217080f621a`.
- Android **dirty working-tree base**, not build commit:
  `b4cd28b0e51d05148ddcbf12a669f99492ae0aac`.
- Original tracked diff SHA-256:
  `a2479e4e7a9266bbf59df98b0157b229438c7135a720e403da629ba4a5f8e2a8`.
- Supplied/installed debug APK SHA-256:
  `da9335071c9c3bac003bbe1d6579c18ed044cd5c32705a74d2a709ca64b9355d`.
- [android-source.json](android-source.json),
  [android-working-tree.patch](android-working-tree.patch), original untracked
  [recipe](routine-inspector-capture.md) and [provenance.json](provenance.json)
  preserve the capture identity. None is restamped to the publication/merge head.

The capture task installed the supplied APK and compared local, transferred and
package-manager-installed bytes. Every Android frame resolved/focused the fixture
Activity; cold launches succeeded, the final process lived and crash buffer was
empty. Build-to-source equivalence was **not independently rebuilt** in capture.
The source diff hash also matched the publisher's initial dirty worktree.

Actual Electron Playwright execution passed **2 tests (35.6s)** against production
Bots navigation/dialog/pane with its real mock backend. The immutable export's
17,003 committed regular files were unchanged; matching target-built dist/build
and dependencies were reused. Only the two additive fixture specs were added.

## Normalization and limits

Both use synthetic ops / syn-1–syn-5, mono, en-US, UTC, explicit light/dark,
clock 2026-09-17T16:00:00Z (overdue: 2026-09-18T16:00:00Z).
Desktop runtime assertions exercised renderer-local CDP locale/timezone, real
production theme-provider keys, resolved mode and clock before every image.
Android uses composition-local US/UTC formatter and fixed-clock fixture inputs;
rendered timestamp/relative text and neutral mode pixels were asserted. There is
no in-process Android locale getter; paused/refusal pixels cannot independently
prove timezone. Device-global settings were unchanged. SystemUI clock is real.

Android is the requested **ARM64 emulator**, not the catalog's Pixel 6 x86/KVM CI
lane. Receipts validate provenance shape, not device equivalence or pixel equality.
Android images are full-device; Desktop inspector images are locator crops and
list/refusal images full-window. No fabricated dialog crops or restyled Desktop
components were used. Contact-sheet inspection found no unexpected overlays;
the parent inspected the legible original Android inspector-dark delivery copy.

## Classified observations

| Observation | Class | Disposition |
|---|---|---|
| Both show Ran, but delivery failed and Synthetic delivery failure | match | Execution outcome copy fixed; populated failure detail is distinct from list refusal |
| Full-width bottom sheet, drag handle, stacked rows, unboxed text and text Close versus centered two-column dialog, bordered panels and two close affordances | mobile-adaptation | Phone/touch layout retained; not pixel parity |
| Same UTC hour, but Android 9/18/26, 9:00 AM versus Desktop 9/18/2026, 9:00:00 AM; in 17 hr versus in 17 hr.; 1 day ago versus yesterday; 7 hr ago versus 7 hr. ago | drift | Concern remains under #191; timezone mismatch fixed, formatter wording/precision not matched. Android receipts preserve U+202F before AM |
| Identical completed payload displays Completed / Succeeded on Android versus Paused / Succeeded on Desktop | drift | Intentional terminal-state safeguard, not a mobile-only rationale. Both omit next run/overdue; Android sheet has no Resume. Never weaken terminal protection to match the misleading label |
| Sparse omits absent fields; paused shows reason without next run; initial refusal shows explanatory copy and Retry | match | Both fixtures include all five synthetic jobs; Desktop refusal asserts real refused-request count |
| Android destination versus Desktop multi-pane shell / optional Screen installation card | mobile-adaptation | Full-window context differs; broader Bot acceptance remains pending |

## Publication sanitation

Only allowlisted synthetic PNGs, receipts, normalization assertions, source/fixture
snapshots and reports are committed. No APK, raw execution logs, serials, private
host paths, capture-host script or real user data is published. Original PNG bytes
are unchanged and checked against provenance. Receipts include only the debug
app's signing-certificate digest, not an SSH host fingerprint or secret.
The original scratch packet and older packet remain untouched; this is a curated
publication, not a retroactive certification of the older failed catalog result.

## Reconstructing the fixtures

Export the exact Desktop revision above into an empty directory, initialize it
with `git init`, then `git apply -p1 capture-only.patch`. Compare the two spec
SHA-256 hashes against provenance. Build/install matching Desktop dependencies
and E2E artifacts according to that revision's own build instructions (capture
reused existing matching artifacts). From apps/desktop under an owned Xvfb:

```sh
DISPLAY=:193 PARITY_OUT=<new-output> npx playwright test \
  e2e/routine-inspector-reference.spec.ts e2e/routine-inspector-failure.spec.ts \
  --workers=1 --reporter=list
```

Android source can be reconstructed by applying android-working-tree.patch to
its stated base and restoring the original recipe at
`docs/workflows/routine-inspector-capture.md`. The published source fixture is
also included separately. Build a **new** debug APK for new captures; the omitted
historical APK cannot be recovered from its hash. Never label new bytes as the
old artifact. Use the repository's capture workflow/contract and record new
source/APK receipts. Historical recipe caveats describe that earlier point in time.

## Verification boundary

Before publication/integration the acceptance worktree passed 3,241 debug JVM
and 2,576 release JVM tests, zero failures/errors, one skip in each, plus check
and assembleDebug; independent review approved that pre-integration work.
Those are not post-integration build results. Main integration requires fresh
exact-head build/tests/CI; this publishing task deliberately runs no Gradle.
