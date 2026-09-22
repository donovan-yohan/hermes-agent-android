# Upstream main sweep: 2026-09-22

## Frozen target

- Mobile baseline: `ca9d255b04053c22c1e1046f27372e9496956e91`.
- Intake source: `NousResearch/hermes-agent` at `e2f8a0731bf26e95b31e35d73e71e183a1045b81`; citations below retain that inspected source.
- Refreshed implementation target: `95f20517c25ee418da5337f4ead347008baaa2b3`, resolved from upstream main and verified in a fresh clone on 2026-09-22. Existing capture provenance is not restamped.
- Previous theme/reference pin: `437116f9497c80d242ce034ff7f5d81dc277a337`.
- This is an in-progress audit, not a complete parity certification. Historical per-surface citations and captured reports retain their original SHA.

## Priority delivery slices

| Slice | Observed gap | Acceptance |
|---|---|---|
| Backend custom skins | Mobile's `data/themes/GatewayTheme.kt` parses Dashboard palette definitions. Desktop also registers resolved backend skins and caches them for startup in `apps/desktop/src/themes/backend-sync.ts:28-44,69-110` at the target. | Valid skin renders, survives restart, respects manual selection and cannot leak across endpoint/profile changes; existing Dashboard support remains intact. |
| Sidebar navigation | Mobile sidebar contributions use `SettingsRow`, including descriptions, instead of the Desktop navigation row treatment. | Sidebar-top SESSIONS/BOTS/TERMINAL tabs; flat icon-and-label rows; correct active treatment, order, Back behavior and compact/wide layout. |
| Missing navigation doors | Desktop core entries are New session, Capabilities, Messaging, Artifacts and Scheduled jobs in `apps/desktop/src/app/chat/sidebar/index.tsx:201-237` at the target; plugin entries follow. | Preserve existing functional destinations; expose unported controls honestly as disabled/WIP; do not conflate Messaging with hosted Group Chats. |
| Built-in theme repin | Registry and shared palette sources have no changes between the previous theme pin and the target. | Update offline ledger provenance and pass live identity parity, theme unit tests and citation checks. |

## Additional upstream fixes to evaluate

These are audit candidates, not claims that all are reproduced Android defects:

- Stale search results: upstream `aca1843823` clears old server hits while a new query is pending. Reproduced on mobile: changing a nonempty query retained `old-hit` during the next debounce. The regression failed before the fix and passes after invalidating remote results on every distinct complete search key. Local matches remain immediate; request cancellation and debounce are unchanged.
- Hidden canonical Bot Chats: upstream `e46b5650fd` closes a sidebar visibility regression caused by resolved/live rows re-entering the cache after list filtering. Mobile asks `session.list` for `include_hidden=false`, but `SessionSummary` has no hidden flag and `buildSessionRows` filters archive state, not hidden state. The admission path is applicable: `ChatViewModel.openBotChat` calls `openSessionAtEndpoint`; the repository's `canonicalSummary` uses `parseSession`, which discards hidden metadata. `SessionCache.upsertSessions` deliberately retains server-omitted rows. Therefore list exclusion does not repair a resolved row already in cache. Disposition: reproduce with a canonical-chat resolve/live-cache regression and preserve authoritative visibility metadata through partial merges before filtering sidebar projections (including search/project views). Do not remove the canonical chat from the authoritative cache simply to hide its sidebar row. This source trace is not yet a passing regression or a completed port.
- Background session rotation: upstream `ced5f36b2a`, `32bb86ff72` and `6d7a8005d0` guard foreground ownership. Exercise mobile rotation/compaction against a different foreground session.
- Completion and resume reconciliation: upstream `01d934f1d6`, `c3f5c37ecb`, `bf4f92b688`, `e4b8c30f87` and `a835405065` address transcript occurrences, ownership and sealed-reply settlement. Compare behavior, not React implementation details.
- Interrupted tool rows and manual compression progress: upstream `ee9203889a` and `a59d15a0c8` improve visible status fidelity.
- Connectors and plugin settings: upstream `dc50403a81`, `70f5dc5f46` and `5c0e73eff1` expand Desktop capabilities. Inventory native applicability and backend availability before scheduling a port.

## Intake-to-refresh delta

### Bounded applicability audit dispositions

Source/test inspection at the frozen target produced these decisions; this is
not live acceptance or proof that every possible upstream change was inventoried.

| Candidate | Disposition | Evidence / required follow-up |
|---|---|---|
| `ced5f36b2a`, `32bb86ff72`, `6d7a8005d0` | Native ownership guard already present; Desktop routing implementation not applicable. | `ChatViewModel.adoptCanonicalSession` rechecks foreground identity after suspended draft migration. Add delayed A-to-continuation while B is foreground coverage; the existing active rehome test is narrower. |
| `01d934f1d6` | Defer implementation pending occurrence regression. | Native resume matches normalized user text within an open run. Repeated equal corrections and persisted tool-round plus retained-tail cases remain unproved; durable row IDs alone do not establish equivalence. |
| `c3f5c37ecb` | Defer pending idempotence regression. | Native resume rebuilds authoritative history plus inflight projection rather than retaining Desktop's journal. Test repeated activation of a retained failure and partial-to-longer snapshots. |
| `bf4f92b688` | Queue ownership and basic partial-error retention covered; mixed tool-round case deferred. | Queued prompts remain composer state until `message.start`; completion settles the runtime-owned assistant. Existing tests cover these cases, not all pre-tool/post-tool arrival orders. |
| `e4b8c30f87`, `a835405065` | Desktop sealed-row patch algorithm not directly applicable; behavioral regressions deferred. | Native correction does not seal/remove its runtime assistant. Test equal no-delta completion, new output after correction, and repeated hidden-prompt turns. |
| `a59d15a0c8` | Apply adapted manual-compression status handling. | Parent verified the wire mismatch: mobile accepts `compacting`/`compacted`, while the target sends `compressing`, then `{kind:"status",text:"ready"}`. Copying a Desktop `kind == "ready"` branch would not handle the actual helper output. Require start/end, success/no-op/failure and session ownership tests. |
| `ee9203889a` | Explicit interrupted completion covered; other missing-result fidelity deferred. | Native interruption marks unfinished tools Stopped and accepts later results. Ordinary completion still seals unfinished tools Done. Test non-user missing-result and mid-turn settlement separately; update stale ToolView commentary when porting that distinction. |
| `dc50403a81`, `70f5dc5f46` | Defer connector capability port. | No native connector consumer established. Future implementation needs the owner union, scoped account-owned updates, policy expected_revision and deployed-Gateway capability checks. Backend Python changes are not Kotlin ports. |
| `5c0e73eff1` | Defer Gateway plugin settings capability, not a platform non-goal. | Android's bundled Plugins surface does not implement backend plugin settings. Future port needs typed settings_schema and separate secret handling; the bundled-only app SDK ADR does not forbid Gateway plugin administration. |

Manual-compression wire provenance at
`95f20517c25ee418da5337f4ead347008baaa2b3`:
`tui_gateway/methods_session.py:1883-1908` emits start and terminal status;
`tui_gateway/server.py:790-801` converts a no-text status call into kind `status`.
The terminal notification is in `finally`, so failure must clear it too.

The GitHub comparison reports six commits after intake. The changed-file inventory
does not touch the shared theme registry, Desktop theme converter, or sidebar.
The delta comprises Desktop peer-window profile routing and its tests, connector
dialog layout/copy, and Python Codex authentication/catalog-cache handling.
Native Android has no Electron peer windows or local Codex token catalog; those
changes are not direct Kotlin ports. Connector UI remains an unported capability
behind the visible WIP entry; its newer header actions belong in that future port.
This delta classification does not settle the earlier audit candidates above.

## Skin ownership boundary

At `e2f8a0731bf26e95b31e35d73e71e183a1045b81`,
`tui_gateway/contracts/events.py:35-49` declares a skin payload without a profile
identifier. `tui_gateway/change_watcher.py:73-82` publishes the resolved skin via
`_broadcast_global_event`; `tui_gateway/server.py:687-695` fans that event out to
all connected transports. These announcements do not identify the sidebar's
selected Hermes profile. Mobile must not invent that provenance from whichever
profile happens to be selected when the event arrives.

The current bridge fences the receiving saved connection scope and endpoint
generation. `ComposerControlsScope` here is the persisted route/provider or SSH
profile scope, not the profile rail's selected session-RPC scope. Cache isolation
and the atomic preference-write guard cover that saved connection scope only.
Per-sidebar-profile skin attribution is not established by this protocol and is
not an acceptance claim of this implementation.

## Evidence so far

- Reviewed working-tree APK `18ee6a3b027d7786c9213c2dd2a6d6c00993d4def7beeed42e7c42ad3285596b`
  was transferred with matching hashes and installed on the trusted remote
  emulator. Package readback succeeded; cold launch returned 919 ms, a live PID
  and empty crash buffer. Private settled screenshots verify sidebar-top
  SESSIONS/BOTS/TERMINAL tabs, core action order and honest WIP doors, embedded
  Bots roster, and successful bot-row navigation closing the drawer and loading
  history. The loaded history contains a prior turn-ended notice; no new prompt
  or successful new turn is claimed. Group Chats and Kanban each open, retain
  drawer access and exclusively highlight their icon/label row when selected.
  These phone-emulator interactions are not a clean aligned Desktop comparison,
  wide-layout proof or a committed-head artifact. No prompt was sent.
- Bounded review found a remaining hidden-row leak in project previews, which
  bypass the ordinary session-row builder. A ViewModel regression reproduced
  `[session-a, bot-chat]` instead of `[session-a]`. The projection now filters
  resolved cached hidden rows without deleting their owners; the same regression
  verifies explicit unhide restores the preview. Full `check assembleDebug` then
  passed: 3,030 debug and 2,416 release tests, zero failures/errors, one skip per
  lane, and a clean diff whitespace check. Hidden-row follow-up review passed
  with no further blockers; broader lineage/explicit-unhide test suggestions
  remain non-blocking. These are not device-rendering or committed-head CI results.
- The same review identified compression-ready erasing newer interleaved
  thinking progress. A `compressing → thinking → ready` regression failed with
  expected `Newer work`, actual null. Ready now clears the compression flag but
  retains non-compression progress and its runtime tracking. Full
  `check assembleDebug` passed after the correction. The final narrow read-only
  compression review passed with no further logic or security findings; it
  inspected the handler and interleaved regression, without independently
  rerunning tests. No live compression acceptance is implied.
- Manual-compression status now accepts the actual `compressing` start and
  `{kind:"status",text:"ready"}` terminal frames. The regression failed at
  the missing start indicator before implementation, then passed. It verifies
  clearing without a turn completion, refusal to clear another runtime's
  indicator, and preservation of subsequent unrelated thinking progress when
  a redundant ready arrives. Full `check assembleDebug` passed after the final
  test expansion. Success/no-op/failure share the backend finally frame; this
  wire-level test does not invoke all three backend operations or establish
  post-compression history/identity acceptance. The new status and hidden-row
  changes are in bounded correctness review before delivery.
- Hidden-session parser/cache/sidebar regression reproduced the unwanted row
  before implementation. Mobile now preserves nullable backend visibility through
  ordinary partial upserts and project-cache merges, filters hidden rows from
  session projections, and prevents flagless search results from resurrecting a
  known hidden identity. Explicit `hidden=false` can restore visibility; the chat
  stays in the cache. `HiddenSessionVisibilityTest` covers the parser-to-cache-to-
  sidebar path and explicit unhide. Full `check assembleDebug` passed afterward:
  3,027 debug and 2,413 release tests, zero failures/errors, one skip per lane.
  A second regression reproduced loss of visibility during compression rehome;
  the canonical cache row and project previews now inherit known visibility
  when the continuation omits it. The full gate passed after that correction.
  Live canonical-Bot opening and project-overview rendering still need targeted
  acceptance; this does not claim those paths are complete.
- Live theme parity at the target: all 11 built-in presets match in order and identity.
- Worktree citation validation after the initial target/ledger update: passed.
- Focused gate: 187 tests passed with no skips, failures or errors (166 ChatViewModel, 13 theme parity, 8 color math), verified from fresh XML reports. Includes the stale-search regression.
- Custom-skin and sidebar implementations are integrated locally. The latest
  working-tree `check assembleDebug` passed with 3,025 debug and 2,412 release
  tests, zero failures/errors and one skip in each suite, plus lint/repo gates.
  This is not committed-head CI evidence.
- Review follow-ups cover startup replay, built-in/default application,
  saved-scope admission, synchronized repository publication/reset, cache-write
  recovery and capacity bounds, and acknowledgment only after preference-write
  success. Expected preference I/O failures remain retryable; cancellation is
  propagated. Focused regressions cover these paths.
- `BackendSkinRestartJourneyTest` exercises real cache/preferences objects and
  Compose colors after object recreation with no HTTP service. This does not
  establish process-death or live-Gateway acceptance.
- Private emulator inspection of working-tree APK SHA-256
  `ce6253e5b60e08477ee9fbc4246a46808b06d432d8e336ccdb2360ff74789577`
  confirmed the single Kanban header, reopening its drawer, selected feature
  highlighting without stale chat selection, tabs and profile/Gateway footer.
  This predates the final Bots callback and theme durability fixes; it is not
  evidence for those changes or a portable clean-profile parity packet.
- Embedded Bots now shares its Routines action with the standalone roster;
  successful bot opening closes the drawer, while failure retains it and the
  completion callback is preserved. Mounted regression tests pass.
- Live custom-skin QA on the remote emulator passed using working-tree APK
  SHA-256 `b67e13debd9513510e31be6806dddcccd0d5bc23e11eb8075984e90089402f35`:
  matching transfer hash, installation/package readback, cold launch and empty
  crash buffer; a real Gateway skin appeared in Appearance, selection changed
  the rendered palette, and the selection/colors survived force-stop plus cold
  process restart. No prompt was sent. Network remained connected, so this is
  not offline-start or first-frame proof. Private captures are not a public
  parity packet. It exposed a remaining platform-chrome defect: status icons
  stayed dark against the skin's dark surface in System mode.
- The bounded follow-up review passed for the preceding theme/sidebar fixes,
  with no additional actionable blockers. This is not comprehensive parity acceptance.
- Platform status/navigation icon contrast now follows the rendered palette,
  not the OS mode. The mounted regression failed without the synchronization
  and passed with it, including switching back to a light theme. A forced full
  `check assembleDebug --rerun-tasks` passed: debug 3,026 tests and release
  2,412 tests, zero failures/errors and one skip in each lane.
- Working-tree APK SHA-256
  `de8bb99e7c4d8653c51e92a8a672925e8be51d97303ae1431960e0f3979af1ba`
  was hash-matched, installed, package-read back and cold-launched on the remote
  emulator, with a live PID and empty crash buffer. Inspected pixels show light
  platform icons on the real dark Gateway skin; restoring the original Nous
  selection and cold-restarting retained Nous with dark platform icons. The
  original System mode was preserved. No prompt was sent. A subsequent bounded
  review of the contrast patch passed with no actionable correctness/security
  findings. It noted the deliberate activity-window scope: a future theme root
  inside a Dialog would need explicit dialog-window handling. This is not a
  claim of independent-model-family review or comprehensive platform acceptance.
- Offline emulator cold-start follow-up used the installed `de8bb99e` APK
  (full hash above, re-read from the installed base APK). Wi-Fi and mobile data
  both read `0` before launch and after screenshot capture. Force-stop/start
  returned COLD, 803 ms, a live PID and an empty crash buffer. Inspected pixels
  show the cached custom palette and readable system icons while the app is
  connecting, without a Gateway response. Both network settings were restored
  to their original `1`, and Nous/System selection was visually verified after
  restoration. No prompt was sent. This proves offline settled launch rendering,
  not the first frame; the installed APK predates the hidden-session/compression
  changes and does not validate those changes.
- Aligned rendered comparison, first-frame skin QA, remaining upstream
  audit, committed-head CI, merge and final device QA remain pending.
