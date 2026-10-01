# Existing Bot Toolsets — integrated handoff

> Historical pre-capture handoff. The [published capture packet](../media/bot-toolsets-v1/REPORT.md)
> supersedes the outstanding Android capture status below: 24 Android canonical
> receipts/PNGs and 14 current Desktop observational references. Original build
> identities and test history below remain unchanged; fresh review is pending.

## Integration and failure root cause

Toolsets implementation is preserved in `27a807c`; main avatar editor commit
`4797bf539d9b754f93965b4ec57a2f00846339b8` is merged additively. Both route renderers
collect and pass model, Toolsets and avatar state/actions. Management opens/closes
all three independent editors. Shared guarded `PluginHost` is byte-identical to main.
No model/avatar feature was replaced to resolve conflicts.

The original full-check failure was fixture drift: Edit gained an authoritative
Toolsets `profiles.describe`, but `BotManagementParityFixtureTest` expected the old
four-call sequence. The isolated test reproduced the exact mismatch before fixing
the expectation. After avatar integration the sequence is model describe/options,
Toolsets describe, avatar get_asset, identity describe/list. No-write assertions,
exact avatar parameters and refused-mutation/authority assertions remain enforced.

## Contract and current Desktop boundary

Inspected upstream `587e673e2a2fae0616d8b750bb189217080f621a`:

- `tui_gateway/methods_profiles.py:345-405`: describe name/boolean pin and independent receipts.
- `:567-588`: server order, labels, descriptions, counts, default-off row disappearance.
- `:637-727`: Toolsets-only write; empty removes CLI pin; nonempty pins explicit selection.
- `hermes_cli/tools_config.py:111,722-751`: default-off allowlist, platform filtering,
  preserved nonconfigurable entries, known-toolset recording and reconciliation.
- Historical `apps/desktop/src/plugins/hermes-bots/profile-config.tsx:395-427,583-586`:
  ordered checkboxes, grouped save and all-selected/none-to-empty behavior.

This is a **bounded selection adaptation**, not full current-Desktop parity. The
historical profile-config inspection is not the current Desktop UX contract.
Parent-reported actual current capture:
`scratch/routine-inspector-587e/toolsets-current-packet/REPORT.md` — 14 genuine
reference PNGs and four passing capture tests, not canonical parity receipts.
Current `CapabilitiesView` orders Skills → Tools → Connectors → Plugins and autosaves
switches; it has no reset-confirmation/default-restored controls or pin badge.
Do not invent matching Desktop states, force a fallback surface or claim full parity.

Android intentionally retains explicit Save, preflight/readback and independent
restore confirmation for mutation safety. All-selected remains an explicit pin;
no selection cannot Save. Only confirmed Restore defaults sends `enabled_toolsets: []`.
No invented confirm RPC/field exists. Mutation keys are only `name` and
`enabled_toolsets`; Skills, MCP, model, avatar, identity and creation/duplication
writes are excluded from this section save.

## Authority and failure behavior

Describe requires exact name, required literal boolean `toolsets_pinned`, unique
nonblank/unpadded row names and literal boolean `enabled` per row. Optional presentation
fields never create write authority. Unknown server-provided names roundtrip unchanged.

Save/restore reread the baseline first; changed baselines refuse. This is a known-stale
check, **not CAS**: another client can still race after preflight. Literal `ok:true`
and `applied.toolsets:true` precede named readback. Save requires pin true, exact desired
enabled names and existing unchecked rows disabled. Only upstream default-off names
plus yuanbao may disappear unchecked. Unknown missing rows or unexplained enabled rows
are unconfirmed. Restore requires pin false and publishes actual returned defaults.

Endpoint/profile/dialog tickets reject stale callbacks. Pending operations remain
keyed to endpoint/profile across close/reopen and distinct-profile ABA. Reopening
while pending disables writes and rereads after completion; a separate read sequence
prevents delayed old reopening reads from winning. Uncertain writes are consumed,
never replayed. The originating dialog predicate reaches the immediate wire seam.

## UI and evidence boundary

Existing Edit only: server-order checkboxes, 48dp minimum row targets, checkable state,
counts/descriptions, independent Save/default confirmation. Skills and MCP remain WIP;
New/Duplicate Toolsets remains WIP. Native per-toolset configuration is not implemented.

Debug-only `BotToolsetsParityActivity` uses production repository/view-model/editor
over an allowlisted synthetic host with no real Gateway/profile. Saved/restored/
unconfirmed execute production actions; loading holds the real request and timeout.
These are focused section fixtures, not full-sheet layout or recorded gesture proof.

States: loading, defaults, pinned, changed, all-selected, empty-selection,
reset-confirmation, saved, restored, error, empty, unconfirmed. Android Toolsets pixel
captures remain outstanding in this worktree. Desktop references were reported complete
by the parent; they cannot certify Android explicit Save/restore states. No visual
parity approval is claimed. Validate native Checkbox checkable/checked/enabled semantics
and 48dp hit regions, not merely text or browser screenshots.

## Verification actually performed

Authorized sequential Gradle execution used one worker, no parallel tasks, in-process
Kotlin and `-Xmx6g`. Isolated original fixture test failed before correction; pre-merge
focused tests passed. Integrated focused run: **72 tests, zero failures/errors/skips**,
covering Toolsets, model, avatar and management fixture.

Full `check assembleDebug`: **BUILD SUCCESSFUL** (6m 42s). XML totals:

- Debug: 3328 tests, 0 failures, 0 errors, 1 skipped.
- Release: 2637 tests, 0 failures, 0 errors, 1 skipped.

Direct regressions cover delayed reopening read arriving after reconciliation,
distinct-profile A→B→A pending authority, unknown server-name save/readback, and dirty
model/identity UI drafts surviving Toolsets save in the registered production route.
Avatar/model suites remain green. Toolsets diff against integrated main passes
`git diff --check`. The merge-wide staged check reported pre-existing trailing spaces
inside main's archived avatar provenance `.patch` files; those evidence files were
preserved unchanged rather than rewritten to hide their historical contents.

APK: `app/build/outputs/apk/debug/app-debug.apk`; SHA-256:
`52b931f852cf6f88f6e9d38ebea1983d634fa48c433e8e5a3aecbd6141111f96`.
Logs: `$TMPDIR/toolsets-red.log`, `toolsets-focused-before-merge.log`,
`toolsets-integrated-focused.log`, `toolsets-integrated-full.log`.
Compiler/deprecation warnings and malformed-image decoder diagnostics remain; no failed
gate was suppressed. No push, install or Android Toolsets capture was run.

Reproduce serially from this worktree; do not share the Gradle lane:

```sh
./gradlew :app:testDebugUnitTest \
  --tests '*BotManagementParityFixtureTest' --tests '*BotsToolsets*' \
  --tests '*BotToolsets*' --tests '*BotsModel*' --tests '*BotsAvatar*' \
  --max-workers=1 --no-parallel -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx6g'
./gradlew check assembleDebug --max-workers=1 --no-parallel \
  -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx6g'
```

## Synthetic Android capture commands (not yet executed)

Use a dedicated emulator and verified APK. These raw PNG/XML commands are not canonical
parity receipts. Select each catalog state and dark/light theme; verify intended UI
readiness before capture, not merely `am start` return. Loading stays a held request.

```sh
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" shell am start -W \
  -n com.hermesagent.mobile.debug/com.hermesagent.mobile.BotToolsetsParityActivity \
  --es visual_parity_state defaults --es visual_parity_theme dark
# After verifying intended UI readiness:
adb -s "$SERIAL" exec-out screencap -p > "$OUT/defaults-dark.png"
adb -s "$SERIAL" shell uiautomator dump /sdcard/toolsets-window.xml
adb -s "$SERIAL" pull /sdcard/toolsets-window.xml "$OUT/defaults-dark.xml"
```

Record source/APK hashes and capture provenance, validate native semantics, and review
full-sheet layout separately. Paired visual acceptance remains parent-owned.
