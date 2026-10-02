# Installed Bot Skills — bounded autosave editor

## Pin and scope

Desktop/Gateway authority: `587e673e2a2fae0616d8b750bb189217080f621a`.
Implementation starts from the approved `b4c84ebb` host foundation, documented
in [ADR 0005](../adr/0005-scoped-core-skills-http.md). This adds only existing-Bot
installed global selection. It does not install/uninstall, replace a disabled
set, call `skills.manage`, or broaden plugin REST permissions.

Current Desktop is Advanced → Skills → Installed in `CapabilitiesView`, not
the historical profile-config checklist. Source references at this pin:
`apps/desktop/src/api/skills.ts:14-43`,
`apps/desktop/src/app/capabilities/skills/skills-tab.tsx:77-147`,
`hermes_cli/web_routers/skills.py:343-394`, and
`hermes_cli/skills_config.py:43-54`.

## Behavioral contract

- Each switch immediately submits one named delta through `PluginHost.skills`.
  Outer Save is unrelated; Cancel never undoes accepted Skills changes.
- Target endpoint/profile and dialog revision are captured. Closing irrevocably
  closes the scope to future enqueue, **not** the plugin-owned coroutine. A PUT
  already admitted may still commit. Reopening blocks duplicate mutation until
  the original operation completes, then reads the new named scope without replay.
- Pending ownership survives profile A→B→A. Read sequence and dialog tickets reject
  late results. Endpoint admission fencing remains the unchanged core-host door.
- Controls are disabled while reading/saving. No optimistic success: `Skill saved.`
  requires the host's exact receipt plus matching fresh named inventory. Essential
  normalization, refusal, cancellation, or missing readback cannot claim success.
  A failed reconciliation drops the inventory and offers read-only Refresh skills.
- No absent names are reconstructed or deleted. All server-returned valid names
  are shown exactly; there is no client skill allowlist. No inheritance/pin state
  is inferred, and global selection is not a cross-platform availability claim.
- Model, avatar, identity and Toolsets remain independent editors. Skills does not
  invoke roster refresh or write their drafts; both registered Bots routes wire
  the new editor additively.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Broader catalog with Installed filter, search, bulk actions, detail/edit/archive and installation | omission | Installed-only subset; remaining catalog/install and detail/edit controls stay disabled/WIP | coming soon — bounded task scope; no catalog or installation claim |
| Optimistic autosave switches | mobile-adaptation | Autosave, but retain observed selection while busy and publish success only after named readback | ADR 0005; essential echo regression |
| Advanced tab/card layout | mobile-adaptation | Scrollable inline Skills section in the existing phone editor; accessible 48dp Switch rows | `BotSkillsEditor`; registered Compose journey; [native packet](captures/installed-skills-4df25613/REPORT.md) |
| Outer Cancel leaves autosaved changes intact | mobile-adaptation | Same persistence behavior, with explicit phone copy explaining Cancel | Registered journey cancels/reopens after autosave |
| Desktop loading/error also shows No matches | mobile-adaptation | Distinct loading, empty, unavailable and refusal text rather than contradictory empty results | Synthetic production-state fixture; no matching pixel claim |

## Visual report

- pending: #194
- Recovered local [comparison packet](captures/installed-skills-4df25613/REPORT.md):
  24 Android PNG/receipt combinations and 48 original XML brackets, plus 14
  unchanged Desktop references and 14 semantic pairings. APK source remains
  `4df256139b8d2bf03f903516152e299cd9949aa5`; Desktop remains at the pin above.
  Android receipts pass the canonical validator. Desktop observations remain
  noncanonical with their original rejection and reused-build limits preserved.
- Captured on a new task-owned disposable emulator without changing the existing
  owner installation. The owned emulator was shut down and process absence verified.
  `BotSkillsParityActivity` exercises production state over synthetic transport;
  native pixels are not real-server persistence or human gesture evidence.
- Loading and pending retain the production 20-second request timeout. Worker and
  validator require fresh-launch monotonic screenshot brackets below that deadline.
  Fixture-staged actions are not human gesture evidence; no runtime RPC exporter,
  native accessibility speech, physical-device or live-server acceptance is claimed.

## Verification boundary

No Gradle invocation: the parent/theme lane owns builds. Scratch-only Kotlin
compilation and JUnit/Robolectric exercise changed sources against cached approved
foundation collaborators and debug resources. These are focused behavioral checks,
not a clean build, full suite, lint or an APK certification. Parent must run full
serial `check assembleDebug`, inspect native light/dark captures, and review parity
before publication. No push or PR is performed by this task.
