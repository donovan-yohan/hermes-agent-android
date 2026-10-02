# Desktop installed Skills reference — unpublished scratch packet

## Result

Real Electron `setupMockBackend` E2E: **4 passed, 0 failed**, attempt 2. Four independent synthetic sandboxes; no user's running Desktop/profile used. Captured **14 unchanged PNGs**: loaded-default, loaded-disabled, changed-autosave-pending, saved-autosave, saved-reopened, loading, error; each light/dark. JSON siblings contain actual scoped renderer API requests, mock replies/readbacks, accessibility snapshot, rendered text and resolved theme/locale/timezone/clock.

Pin: `587e673e2a2fae0616d8b750bb189217080f621a`. A fresh git archive was used, initialized as its own scratch repository. **17,003 upstream regular files matched the immutable pin; none changed.** Only the additional `apps/desktop/e2e/installedskills-reference.spec.ts` is capture code. `harness.patch` reconstructs it with `git apply` from an independently initialized directory; fixture hash equality verified. `source-manifest.json` hashes every upstream regular file; `source-snapshot/` preserves relevant UI, API and gateway implementations. `provenance.json` includes PNG, fixture, patch and reused build hashes.

## Semantics relevant to Android

- Actual reachable path: Bots roster → synthetic bot context menu → Edit → Advanced → Skills → **Installed source filter**. The Skills tab initially browses the wider catalog. Installed toggles are not installation controls. Adjacent catalog installation switches have different behavior.
- Current real Skills UI uses `CapabilitiesView` / `SkillsTab`, not the historical staged profile checklist. It autosaves immediately with optimistic enabled state and disables mutation controls while the request is pending. Canceling the outer profile editor does not undo a committed skill toggle. Verified reopening and a fresh synthetic GET returning enabled=true.
- Observed renderer request scope: `connectionId: "local"`, `profile: "synthetic-installedskills"`, `priority: "foreground"`. Read: `/api/skills`. Write: `PUT /api/skills/toggle` with `{name:"synthetic-research",enabled:true}`. Real UI dispatch and synthetic readback are recorded; this is NOT a captured production HTTP/RPC exchange.
- REST backend toggle adds/removes a name in the selected profile's `skills.disabled`; no installation/uninstallation. `save_disabled_skills` removes essential skills from the disabled set. Backend can acknowledge a requested disabled flag even for essential skills, so authoritative readback matters.
- JSON-RPC **`skills.manage` does not support enable/disable** at this pin. Actions are list/search/install/browse/inspect; unknown action returns 4017. Its list is category→names, not enabled-row inventory. List/install use scoped `profile`; shared-hub search/browse/inspect are distinct.
- JSON-RPC **`profiles.describe` `{name: target}`** returns installed skills `{name,enabled}` from that profile's skills directory. **`profiles.configure` `{name: target,disabled_skills:[...]}`** is the supported staged bulk update route; it replaces the disabled-name set and returns `applied.skills`. `disabled_skills: []` means none disabled, **not toolset-style inheritance reset**. There is no `skills_pinned` flag in describe. Missing/non-list disabled_skills leaves the section unchanged. This is source-backed, not exercised in this capture.
- `get_disabled_skills(config, platform)` unions global and platform disabled sets, then removes essential skills. The captured REST toggle has no platform argument. Do not conflate this platform rule with toolset pin inheritance or claim profile inheritance was runtime tested.
- Default here means fixture inventory enabled=true, not a separate visible "Use defaults" UI. No inheritance/reset control was invented or captured.

## Visible details and limits

- Skills are cards with named switches, search field, source filter and bulk/menu controls. Pending uses disabled switches; no explicit pending Save button or invented overlay.
- Loading actually holds `/api/skills` pending. Genuine screen shows a spinner AND "No matches" under the Installed filter. Error shows "Skills failed to load", synthetic IPC error text, "Refresh skills", AND "No matches". These awkward combinations are production component output, not corrected pixels.
- PNGs are the real editor viewport after scrolling Advanced to the top. Top identity fields and footer Save/Cancel are outside the viewport; Skills cards/status are visible. Not full-scroll screenshots.
- `mono`, en-US, UTC, fixed 2026-09-17T16:00:00Z runtime values asserted for each image. Screenshot themes are real provider/system-mode resolution.
- Existing disposable source-v2 built `dist` was copied; dependencies/venv referenced from the earlier disposable harness. No new Desktop build was performed, and build-to-pin reproducibility is not newly attested. All upstream source bytes were independently verified; built artifact hashes are preserved.
- Synthetic API seam proves UI dispatch, pending gating, optimistic update and reopened synthetic readback. It does not prove disk persistence, remote connection routing, JSON-RPC writes, write-refusal rollback, default-profile selection, essential-skill normalization or cross-profile isolation under concurrent requests.
- Attempt 1 had two passes/two failures because the installed switches were not visible until the real Installed filter was selected. Failed artifacts and raw traces remain in sibling scratch attempt directories, excluded from this sanitized packet.
- **Not a canonical parity receipt:** repository `check-receipt --platform desktop` rejects observational JSON: "receipt misses required common provenance" (exit 1), preserved in `receipt-validation.txt`. No fields were fabricated to pass. No Android comparison or parity acceptance claimed.

## Reproduction

1. Export the pin to disposable source, `git init -q`, then `git apply /path/to/harness.patch`.
2. Supply compatible installed dependencies, Python environment, real built Desktop dist and an owned headless X display using upstream E2E conventions.
3. From `apps/desktop`: `DISPLAY=:187 PARITY_OUT=/scratch/output npx playwright test e2e/installedskills-reference.spec.ts --workers=1 --reporter=line`.
4. Expect four passing tests and fourteen PNG/JSON pairs. Preserve failed attempts separately.

Only scratch files were written. No Android edits/builds/devices, upstream checkout edits, publication, or worker-owned Gradle activity. Owned Xvfb was stopped.
