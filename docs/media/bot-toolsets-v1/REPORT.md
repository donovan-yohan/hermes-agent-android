# Existing-Bot Toolsets: bounded Android adaptation

[Rendered side-by-side report](report.html) · [Exact platform map](platform-map.json) ·
[Per-image hashes and capture-source manifest](provenance.json) ·
[Divergence ledger](../../parity/bot-toolsets.md)

## Outcome and immutable identity

**24 actual Android PNGs: 12 catalog states × light/dark**, with 24 validated
canonical v1 Android receipts. **14 unchanged genuine Electron reference PNGs:
seven native states × light/dark**, with their original observational evidence.
These are 38 distinct platform/state/theme artifacts, not 38 equivalence approvals.
Android and current Desktop have different mutation contracts; strict parity and
fresh independent exact-head review remain pending.

- Android capture source: `0d08856c6dcd720b62fa2aa97e6bf3ac82503902` (clean).
- Integrated main: `4797bf539d9b754f93965b4ec57a2f00846339b8`.
- Installed debug APK SHA-256:
  `52b931f852cf6f88f6e9d38ebea1983d634fa48c433e8e5a3aecbd6141111f96`.
- Desktop upstream: `587e673e2a2fae0616d8b750bb189217080f621a`.
- Android: dedicated ARM64 API 37 emulator, 1280×2856. Not the attached physical
  phone. Local, transferred and pulled installed base APK hashes matched; signing
  certificate and package version are retained in each receipt. Install succeeded;
  final cold launch returned `Status: ok`, live process, empty post-clear crash buffer.
- Publication changes documentation/evidence only. It does not restamp the APK as
  built from the later publication commit. No Gradle or Espresso run was added.

## Complete Android catalog

`loading`, `defaults`, `pinned`, `changed`, `all-selected`, `empty-selection`,
`reset-confirmation`, `saved`, `restored`, `error`, `empty`, `unconfirmed`.
Every state has `android/<state>-<theme>/reference.png` and `contract.json`.
The report links both themes individually, including states without Desktop peers.

Each capture uses the committed debug-only production repository/view-model/editor
fixture over an allowlisted synthetic host. No actual Gateway, user profile or
persisted user toolset was changed. The fixture stages production actions; receipts
correctly record **no injected gestures**. Saved/restored are in-place results,
not a reopened-editor claim. `changed` and `all-selected` intentionally stage the
same draft but remain separately catalogued captures, not distinct behaviors.

Fresh launches are bracketed by exact Activity focus and actual accessibility
membership checks. Receipts retain visible node bounds, native class,
checkable/checked/enabled state and screenshot hashes. The two checkable row targets
are 193 physical pixels tall at 480 dpi (over 48dp); their nested Web/Terminal labels
and checked values are checked in the packet verifier. Android exports these as
**checkable `android.view.View` rows, not `android.widget.CheckBox` class nodes**.
Do not infer native Checkbox role announcement: TalkBack speech/role acceptance is
unverified. The verifier initially assumed a CheckBox class, failed, and was corrected
to assert the observed View class without altering receipts or application behavior.

### Loading and refusal evidence

- Android loading holds the actual `profiles.describe` coroutine; the production
  20-second deadline is unchanged. Installed identity was collected before launch.
  Screenshot/state/focus postchecks completed at **8.200s light / 8.032s dark**,
  with “Reading toolsets…” present before and after. The local capture adapter
  explicitly enabled the existing worker's deadline bracket for this generic
  `loading` state; the stock CLI only recognizes model/avatar loading names.
- Android `error` takes the synthetic host's real read-refusal branch and captures
  “Toolsets could not be read. Close and try again.” `unconfirmed` actually runs
  production Save, mutates the synthetic store and then refuses its receipt. It
  is **not** evidence of a rejected-before-write mutation, nor a separate retry.
- The Android fixture has **no runtime RPC export**. Receipt acceptance proves the
  visible state, focus, installed bytes and timing; RPC sequence/outcome claims are
  backed by committed fixture wiring and existing fixture/repository tests, not a
  fabricated per-capture transport log. No broader transport telemetry is claimed.
- Desktop loading retains a pending actual Tools GET; read-error observations retain
  four real refused attempts. Pending autosave retains its held PUT; saved and
  reopened observations retain committed store state and a subsequent scoped GET.
  Native error copy remains “Skills failed to load” / “Refresh skills” even on Tools.

## Current Desktop is not the historical checklist

Real route: Bots → synthetic profile → Edit profile → Advanced → Tools. Current
`CapabilitiesView` orders **Skills → Tools → Connectors → Plugins**, uses switches
and a Terminal inspector, and applies toggles immediately. The outer Save/Cancel
controls do not stage Tools mutations. See the preserved [Desktop report](desktop/REPORT.md)
and [reconstructible additive fixture](desktop/toolsets-current-harness.patch).

| Android state | Actual Desktop reference | Deliberate boundary |
|---|---|---|
| defaults / pinned | loaded-defaults / loaded-pinned | Matching initial selections, but Desktop has no pin/default badge |
| loading / error | loading / error | Different real read seams and native error wording |
| changed | changed-autosave-pending | Android unsaved draft versus an already-dispatched optimistic Desktop write |
| saved | saved-autosave | Android explicit Save + readback notice versus silent immediate Desktop commit |
| separate readback | saved-reopened | Desktop reopened GET evidence; not relabelled as Android in-place saved |
| all-selected | none as a distinct journey | Saved Desktop happens to show both on; not reused as new state coverage |
| empty-selection / empty / unconfirmed | not captured | No invented matching reference |
| reset-confirmation / restored | no current Tools counterpart | Android confirmation and inheritance restoration are explicit safety adaptations; Desktop All is not Restore defaults |

Android all-selected stays an explicit pin; an empty draft cannot Save. Only
confirmed Restore defaults sends `enabled_toolsets: []`. Preflight/readback are
known-stale checks, **not CAS**. Model, avatar, identity, Skills and MCP writes are
not bundled into this section save. Per-toolset configuration, New/Duplicate
Toolsets and full-sheet visual acceptance remain outside this delivery.

## Validation and reproducibility limits

Fresh publication checks: packet verifier passed all 38 image hashes/dimensions,
24 receipt CLI validations, native row states/48dp bounds and loading/refusal/readback
assertions. The Python visual-parity contract/workflow suite passed **17 tests**;
repository invariants and the **41-page** parity evidence gate passed. The Desktop
patch was reapplied in an initialized disposable directory and reconstructed the
fixture byte-for-byte. Publication text/JSON scans found no private host/device paths.
Browser DOM verification loaded all **38/38** report images; a separate report
screenshot attempt timed out, so no browser-report screenshot is claimed. Original
Android/Desktop pixels were reviewed through derived contact sheets, not modified.

- Existing integrated `check assembleDebug`: debug **3328 tests, 0 failures/errors,
  1 skipped**; release **2637 tests, 0 failures/errors, 1 skipped**. Totals were
  reread from the existing XML here; this publication did not rerun Gradle.
- Existing focused integrated handoff: **72 tests, 0 failures/errors/skips**.
- Desktop supplied run: **4 passed, 0 failed**, attempt 3. Not rerun for publication.
  Its supplied prebuilt dist was reused; upstream source comparison is not a fresh
  source-to-build proof. Projected Desktop provenance keeps the original provenance
  digest and build-map digest; raw traces and build-file listing stay scratch-only.
- All Android receipts are schema-validated separately from state/checkbox/timing
  assertions. Desktop observational JSON still fails canonical receipt validation
  with “receipt misses required common provenance”. The rejection is preserved;
  it is not converted into invented receipts to pass the legacy checklist contract.
- Android mono light/dark is wired in the fixture and visually checked. Unlike
  Desktop's resolved mono/en-US/UTC/fixed renderer clock, Android does not export
  locale/timezone/clock getters or normalize SystemUI time. These images contain no
  date-bearing app content. Status-bar time is live and not pixel-equivalence evidence.
- The generic two-platform workflow is not claimed to support this packet: Toolsets
  is absent from its dispatch choices and its Desktop seam describes the older
  checklist. Use the committed Android fixture and genuine Desktop additive E2E
  fixture; do not dispatch the historical fallback to fabricate a match.
- APKs, raw logs, device identities, host paths and private capture-host scripts are
  excluded. Published PNGs are byte-for-byte originals; the HTML is only a comparison
  report that embeds those images. No retouching or synthetic replacement UI.

To verify this packet offline, run `python3 docs/media/bot-toolsets-v1/verify.py`.
To reproduce Desktop, apply the additive patch in an initialized immutable export
at the pin and run its Playwright test with the upstream E2E prerequisites. Android
reproduction uses the exact committed fixture, explicit state/theme extras, installed
APK verification and the existing Python worker's bracket/accessibility helpers;
for `loading`, pass a measured launch origin and the 20-second deadline explicitly.

The original integrated [handoff](../../spikes/bot-toolsets-handoff.md) remains a
historical pre-capture record. This packet supersedes its outstanding-capture status,
not its source/build identities. Fresh independent review is pending; no merge or
full current-Desktop parity approval is authorized or claimed.
