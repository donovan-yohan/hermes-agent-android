# Bot Routines parity

## Pin

| Authority | Revision | Read method |
|---|---|---|
| Hermes Desktop and Gateway | `d177b119e9c56c9ddc0b7379ffce52341ec06584` | read-only `git -C <snapshot> show <sha>:<path>` |

Every source location below is against that exact revision. The Desktop
snapshot used for this slice is a read-only disposable export of that SHA.

## Scope of this page

This covers the read-only #310/#313 foundation, #316's existing-row actions,
and the bounded #191 creation and held-list inspector slices. The list still includes disabled jobs.
Existing-row writes send only `cron.manage {action:"pause"|"resume"|"remove", name:<job id>, profile:<raw bot>}`; creation sends one `cron.manage {action:"add"}` from the selected bot's production form.
The form preserves Desktop's eight frequency choices in order, starts on Daily,
and supports all eight through the pure wire model. The current-target correction
uses `in 30m` for Once, the explicit one-shot contract at
`cron/jobs.py:823-832` @ `e27448b231498e79ade668d68c0b6c6206951206`. This is an
intentional safety adaptation from Desktop's recurring bare-duration bug, not a
claim that Desktop sends that prefix. Delay amount and units are enabled.
Delivery target, continuity and repeat are retained in the wire model; Edit,
Run-now and legacy auto-pause remain out of scope and visibly deferred.

## Sources and action evidence

| Question | Desktop/Gateway source | Android evidence |
|---|---|---|
| The read and its scope | `apps/desktop/src/plugins/hermes-bots/cron.tsx:103-127` (`loadRoutines`), and its own test `cron-load.test.ts:58-78` pinning `{action:'list', include_disabled:true, profile:'research'}` | `BotsPluginRepository.loadRoutines` sends exactly that object through `PluginHost.requestAtEndpoint`; `BotsRoutinesRepositoryTest` asserts the request shape, that `include_disabled` is `true` on the wire, and that the profile is sent verbatim |
| The handler | `tui_gateway/methods_tools.py:1075-1085` (`@_scoped_rpc("cron.manage", 5023)`, `_ok(rid, result)` around the `cronjob()` payload, `scoped` echo), `:42-60` (profile resolution and the `4064 profile '<p>' not found` refusal) | `BotsRoutinesLoad` distinguishes `Loaded` / `MismatchedScope` / `Rejected` / `UnavailableOnGateway` / `Refused`; `BotsRoutinesRepositoryTest` covers each |
| The row | `tools/cronjob_job_args.py:351-399` (`_format_job`), narrowed by Desktop's own `apps/desktop/src/plugins/hermes-bots/types.ts:257-283` (`RoutineJob`) | `parseRoutineJobs` / `parseRoutineJob`; `BotsRoutinesParseTest` reads the full pinned row shape and asserts no unread member survives |
| Titles | `cron.tsx:73` (`BOT_TAG_RE`), `:87-95` (`routineBot`, `routineTitle`) | `routineBot` / `routineTitle`; `BotsRoutinesParseTest` covers the tag strip, the tag-only name and mid-name brackets |
| State derivation | `cron.tsx:489` (`serverActive = !legacyUnsafe && job.enabled !== false && job.state !== 'paused'`), pinned by `cron-detail.test.tsx:74-78`; Gateway's own `cron/jobs.py:525-538` (`effective_job_state`) and `cron/jobs.py:1537-1539` (disabled completion) | `RoutineRunState` preserves completed, failed and unknown state independently of enabled. Disabled known scheduled jobs become paused; completed/unknown never become resumable. Disabled failed records remain inactive. Parser, repository, VM and real-route UI regressions include production-shaped disabled records |
| Which jobs show | `cron.tsx:215-230` (`selectRoutineJobs`), pinned by `cron-jobs-view.test.ts` | `selectRoutineJobs` / `routineScopeAgrees`; `BotsRoutinesRepositoryTest` covers scoped, unmarked and mismatched answers |
| The filter hint | `cron.tsx:232-248` (`routineFilterHint`), pinned by `cron-jobs-view.test.ts:98-117` | `routineFilterHint`; the empty card renders it in the description slot, `BotsRoutinesJourneyTest` |
| Schedule labels | `cron.tsx:288-324` (`scheduleLabel`) | `routineScheduleLabel`; `BotsRoutinesParseTest` covers every recognised form and the pass-through |
| Relative next run | `cron.tsx:328-332` with `apps/desktop/src/lib/time.ts:38-56` (`Intl.RelativeTimeFormat`, style `short`) | `routineRelativeLabel`; `BotsRoutinesParseTest` pins `in 5 min` / `in 2 hr` / `in 1 day` and the half-up rounding boundary |
| The pane's states | `cron.tsx:1277-1318`: stale banner over a held list, loading, failure + Retry, empty, populated | `BotsRoutinesPhase` and `BotsRoutinesScreen`; `BotsRoutinesViewModelTest` and `BotsRoutinesJourneyTest` cover each |
| The pane's header | `cron.tsx:1252-1275`: the bot's face, its display name and `@handle`, the uppercase pane noun, the New-cron control | `RoutinesOwnerHeader`; the display name travels with the selection (`BotsPlugin`'s `onOpenRoutines` passes `displayName(row.name, row.displayName)`), and the New-cron control opens the creation sheet |
| The row's controls | `cron.tsx:539-576`: a title button, a pause/resume Switch and a delete control, as siblings | `RoutineRowItem` renders a token switch followed by Codicon Trash, independently actionable; the title opens the held-list inspector |
| Mutation and confirmation | `cron.tsx:496-523,559-574`, `cron-owner.test.tsx:53-69`; `tui_gateway/methods_tools.py:1097-1098` | Row delete is immediate, with **no confirmation dialog**, matching Desktop. `BotsRoutineActionsTest` checks exact payloads and literal acknowledgements; `BotsRoutinesJourneyTest` exercises the production route, pending state, rollback, both toggles and direct delete |
| Reads remain inert | `cron.tsx:131-166` is deliberately not ported | `BotsRoutinesRepositoryTest` still asserts that a read sends only `list`; no legacy auto-pause sweep |
| Ordering and identity | `cron-owner.test.tsx:53-69` captures the rendered owner | Immutable owner + endpoint + selection + job identity; actual `requestAtEndpoint` endpoint/client wire fence. A mutation revision invalidates pre-operation and overlapping reads; pending writes block only their row; targeted rollback never restores another row. A completion still belonging to the current selection requests an authoritative list, never a blind mutation retry. Tests gate real coroutine interleavings and the production host dispatch |

### Accepted intent after navigation (PR #317 C2)

A stale callback, including one queued before `act`, is rejected before entering
the repository. Once a valid captured intent enters the repository, navigation
on the same endpoint is **not cancellation**: that intent may finish only on its
original profile and job. It must never retarget or repaint the replacement
owner, including on refusal/rollback. Endpoint/client changes still fence actual
dispatch. A departed owner's late completion causes no refresh of the replacement
owner; reopening the original owner performs its normal authoritative read.

`BotsRoutineActionsTest` pauses the production host's client immediately before
the actual `dispatch` handoff, switches to a different owner on the same endpoint,
and verifies the exact old-owner payload followed by both successful and refused
late replies. Replacement state is unchanged throughout; an old callback invoked
after navigation adds no second write. This contract needs no PluginHost
selection seam and does not change the shared transport.

Accepted operations retain a separate owner + endpoint + job lock across navigation
(PR #317 comment 4046807311). Selection epochs fence UI publication, not the
operation's lifetime. Returning A after A → B → A can load A's new list, but the
still-pending row remains protected against a second mutation. B's pending state,
reads and actions are independent even when its job has the same id. Completion
or cancellation releases only its own operation token in `finally`; an older
selection never rolls its optimistic snapshot back onto a newly selected list.
A completion for the visible owner clears pending and reconciles by reading;
a completion for another owner does not invalidate the visible owner's read.
`BotsRoutinePendingIdentityTest` gates actual before-wire and reply handoffs,
returned-owner reconciliation, cancellation and queued selection rejection.

## Local-delivery reconciliation

The three unmerged commits `2ce11b61e49ef7ac206aa501aee2efc1fda32dbf`,
`5ea72c947b892a398c5f84acae463afe92cf2f83`, and
`9773503b3d7e9043faa19f3b4153ff2fbed6fff3` were ported as a reviewed delta, without
cherry-picking or replacing current-main files. Current-target confirmation uses
`cron.tsx:960-1100` and plugin `apps/desktop/src/plugins/hermes-bots/i18n.ts:887-918` at
`e27448b231498e79ade668d68c0b6c6206951206`.

Reconciliation fixes in addition to that delivery:
- A confirmed result can be closed before creating a distinct routine; the old
  implementation retained Created forever and never returned to a usable form.
- Rejected fields remain editable and are preserved for an explicit corrected retry.
- An unconfirmed result can be closed to inspect the scheduled list; reopening
  retains the no-retry outcome rather than trapping the person in a modal.
- Form entry/edit/submit synchronously fence endpoint changes, including the gap
  before the endpoint collector runs. Late result publication checks the live endpoint.
- New/Create require a fresh matching scoped list receipt and live connection; Ready
  and confirmed scoped Empty are admitted. Unscoped tag-filtered display is never
  creation permission. Failed refresh, loading, mismatched/rejected/unsupported lists
  and disconnect revoke admission, including a recheck after the pre-dispatch yield.
- Actual registered roster and sidebar callbacks carry the originating roster
  snapshot and refuse a departed endpoint before selecting an owner or navigating.
- Null/non-string/blank/control-bearing creation ids never confirm a created job.
- The production host's actual before-wire fence has an added creation regression.
- Frequency, delivery and continuity copy now follows the current plugin bundle.

Unknown creation receipts and saved-but-unregistered receipts still prevent a second
add for that owner/endpoint for this model's lifetime. Durable cross-process receipt
reconciliation is not implemented. Once is now enabled through the explicit
one-shot contract; legacy management and rendered parity remain open. Imported test provenance is not a
new-head pass: this lane runs no Gradle; the parent must execute the reconciled suite.

## Held-list inspector follow-up

This follow-up reads the following sources at
`e27448b231498e79ade668d68c0b6c6206951206`:
- `apps/desktop/src/plugins/hermes-bots/cron.tsx:328-475` and its `cron-detail.test.tsx` test;
- `apps/desktop/src/plugins/hermes-bots/i18n.ts:865-880`;
- `apps/desktop/src/app/cron/job-state.ts:34-57`;
- `tools/cronjob_job_args.py:425-475`.

Read-only Git comparison against `587e673e2a2fae0616d8b750bb189217080f621a`
found no changes to the inspector component/test or overdue helper.
Earlier citations and capture provenance on this page are not repinned.

The title opens a selected job from the held list, with **zero additional RPCs**.
Selection captures raw owner, endpoint generation, owner-selection epoch and job
id. Old open/close callbacks cannot act across owner ABA or endpoint switches;
missing/mismatched rows close the sheet. Failed refresh retains inspectable facts
with the stale notice; a successful refresh supplies current facts, not a copied
second record. Pause/delete remain sibling controls, not sheet actions.

Order: status, schedule, raw schedule only when different, repeat, next/overdue,
last run, last result, delivery, model, workdir; instruction preview follows.
Missing or malformed optional values are omitted. Failure precedence is fire
error, delivery error, paused reason. Overdue means **strictly more than 15 minutes**;
exact boundary, future/invalid timestamps, paused/completed/unknown and disabled
records cannot claim overdue. The list and inspector use the same derivation.

Evidence is bounded: an isolated cached Kotlin compiler/JUnit probe recompiles
changed non-Compose production sources and tests against existing app collaborators;
**83 JVM tests passed** (inspector/parser/VM plus existing mutation and pending-identity
regressions). Log: active-profile scratch `routine-inspector-jvm.log`; the existing
VM tests emit coroutine opt-in warnings. This is not a clean build.
No Gradle, APK, device, screenshot or new visual parity
claim. Compose journey and debug `inspector`/`overdue` states are authored for the
coordinated parent lane. `bot-routine-inspector` has a separate current-pin capture
catalog entry; historical `bot-routines` provenance remains intact.

## Inspector acceptance follow-up (new-capture boundary)

The genuine scratch packet at Desktop `587e673e2a2fae0616d8b750bb189217080f621a`
exposed Android's shortened delivery-result label. Android now preserves the
execution outcome with **Ran, but delivery failed**. The debug fixture also
opens sparse, paused, completed and overdue inspectors directly and includes the
same synthetic completed job as Desktop. Completed remains **Completed**, cannot
resume, and has no next run: the documented terminal-state safety adaptation is
unchanged even though Desktop's disabled completed inspector says **Paused**.

The v2 inspector catalog/workflow choices target **new captures only**. The old
`bot-routines` pin and all historical image/receipt identities remain unchanged.
Inspector fixtures now supply en-US/UTC timestamp formatting locally, without
changing device or process defaults; mono remains the capture skin. See the
[new capture handoff](../workflows/routine-inspector-capture.md) for exact inputs,
Desktop normalization, dispatch commands and receipt boundaries. The subsequent
[v2 rendered report](../media/routine-inspector-v2/REPORT.md) now publishes actual
Android/Desktop pixels with their original dirty-source/APK provenance, not a
claim that the publication or integration commit produced that APK.

Local verification for this follow-up: the copy regression failed before the
fix; completed state failed before fixture support; non-US/non-UTC fixture
formatting failed before local injection. Focused inspector/capture/journey
checks passed, then an unfiltered rerun of `testDebugUnitTest check assembleDebug`
passed: **3,241 tests, zero failures/errors, one skipped** across 280 debug JVM
suites. `check` also ran 2,576 release JVM tests with zero failures/errors and one
skip; no release APK assembly was requested. The workflow/receipt Python checks
passed (16 tests). These results are
working-tree verification, not a committed-source or installed-device receipt.

## Copy and navigation

The roster row's tap opens the bot's chat, exactly as before; Routines is a
separate control on the same row. Entering it selects that bot and navigates to
its own route, so nothing about the roster's existing action changed
(`BotsRoutinesJourneyTest`: `the roster row's Routines control opens that bot's
jobs, and a row tap still opens chat`).

On the destination, everything rendered is either this app's own sentence or
Desktop's own words read at the pin: the state messages, the empty card, the
filter hint, the stale banner and the failure card all come from
`plugins/hermes-bots/i18n.ts:513-551` (the bundle's cron block) or core
`i18n/en.ts:2545-2645` (the pane's own chrome, which Desktop also takes from
core), and the retry control reuses the roster's `Retry now`
(`plugins/hermes-bots/i18n.ts:338`) rather than spelling a second word for one
control. Mutation failure uses core `apps/desktop/src/i18n/en.ts:2646` verbatim: "Failed to update cron job".
Two sentences are this app's own because Desktop has no counterpart:
the Gateway-predates sentence and the mismatched-scope pair.

Inspector display text is redacted before bounding (512 characters per ordinary
field, 1024 for issue and instruction preview), using the shared credential,
endpoint, address and fingerprint sanitizer. Full prompt text is never retained;
only `prompt_preview` can reach Instruction. Raw identity/tag values stay outside
rendered text. Run state remains a closed enum; unknown state has no invented label.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| A `scoped` echo naming another profile falls back to the `[bot:]` tag filter and shows the tagged rows (`cron.tsx:221-229`, `cron-jobs-view.test.ts:52-61`) | drift | The answer is refused: the surface shows "Not this bot's scheduled jobs" with Retry, and no part of that store — tagged or not — is rendered | #310. Deliberate, not an adaptation: the safety rationale is a claim about the data, not about a phone, so it holds on every platform. On Desktop both profiles are machines Desktop is in and a tag filter is a conservative fallback; here the reply describes a bot the person did not ask for, and a filter that *appears* to work teaches the reader that a mismatched answer is normal. Failing closed states the truth and cannot leak |
| A legacy delegated routine is paused on load, and its row says "Paused for security: delete and recreate this legacy job before running it again." (`cron.tsx:131-166`, `:591-595`) | drift | The routine is listed, labelled as one this app cannot manage yet, and its state is whatever the Gateway said — never a pause this app did not perform; its delete also stays WIP | #310/#316. No legacy mutation is issued; the sweep and legacy management stay in #191 |
| Routines is reached by focusing a bot: Desktop's Bots pane and the Routines tile are on screen at once, and the pane follows `$focusedBotOwner` / `$selectedBot` (`cron.tsx:42`, `:1198-1207`) | mobile-adaptation | A visible, labelled control on each roster row opens that bot's own Routines destination; the pane header names the bot it is scoped to | A phone shows one surface at a time, so the destination needs an entry on the row; the alternative — a hidden gesture — is not discoverable, and the row's own tap must stay Bot Chat. The owner is plugin-scoped instance state because this app's plugin contract has no module globals (`BotsPluginTest` asserts the plugin declares no mutable statics) |
| The row's face: an avatar or mood-driven `BotFace` beside the display name and `@handle` (`cron.tsx:1253`, `avatar.tsx`) | omission | The header shows the bot's display name and the pane noun, with no face | deferred: #189 — the roster already ledgers the absent avatar surface, and this slice adds no second one |
| The header's New-cron control (`cron.tsx:1270-1274`) | mobile-adaptation | Creation opens the phone's form; the form keeps the same action visible while an operation is unresolved | The phone needs a single reachable form surface rather than Desktop's pane-local dialog. The create dispatch and unresolved-state lock are production-wired; rendered side-by-side remains owed by the parent capture lane |
| Desktop's Once choice composes a bare duration (`cron.tsx:672-675` at the current target) | mobile-adaptation | Once is enabled but sends `in <duration>`; Daily remains initial | #191; explicit `cron/jobs.py:823-832` contract @ e27448b; model regression fails with bare duration and passes with one-shot prefix |
| Desktop renders the seven supported schedule choices plus delivery, continuity and repeat controls in its creation form (`cron.tsx:603-1035`) | mobile-adaptation | The phone form uses the production pure model and preserves all eight enabled frequencies; advanced controls remain model-backed and are continued in the form slice | Android uses a scrollable bottom sheet to fit the picker and keyboard; exact visual comparison is still owed by the parent capture lane |
| Desktop creation error and partial-save outcomes remain on the creation surface | mobile-adaptation | Safe local outcomes retain exact job identity for saved-registration failure and never show backend error prose or automatically retry | A phone needs a concise next action; unresolved results stay protected and must be reconciled from the list rather than guessed by title |
| Desktop row controls are enabled without a scope receipt and without terminal-state checks (`cron.tsx:489,559-569`) | drift | Unknown/stale owner scope cannot authorize a write; completed and disabled failed rows cannot toggle but can be deleted. Unknown records remain WIP even when disabled, with no invented paused label; completed records render completed. Legacy remains WIP | #316 / PR #317 F1. A tag fallback licenses display, not a write to a profile's store. Disabled is not evidence of resumability; production-shaped disabled completed/unknown rows are covered through repository, VM and UI |
| Delete is hover-revealed (`cron.tsx:567`) | mobile-adaptation | Trash remains visible with the Android touch-target floor; switch comes first, no menu or separators, no confirmation | #316. A touch screen has no persistent hover; `BotsRoutinesJourneyTest` checks direct delete |
| The pane polls every 20s and refetches on its socket opening (`cron.tsx:183-189`) | mobile-adaptation | The destination re-reads when it is entered and when the connection comes back; there is no RPC polling timer (a resumed-only 30s display clock updates overdue labels) | A phone does not leave this destination mounted while the person works elsewhere, so entering it is the trigger, and a background poll would spend the device's battery on a surface nobody is looking at |
| Desktop's title opens a held-list detail dialog (`cron.tsx:370-475` @ e27448b) | mobile-adaptation | Scrollable read-only bottom sheet, same field order, title-only opener and sibling mutation controls | #191; phone viewport/touch adaptation. [Rendered v2 comparison](../media/routine-inspector-v2/REPORT.md): stacked sheet rows, unboxed text and text Close differ from Desktop's two-column bordered dialog |
| Desktop inspector treats any enabled non-paused state as Active | drift | Completed stays Completed; unknown omits status and next run; disabled failed jobs do not promise another run | #191; existing terminal/unknown safeguards retained in inspector regression tests |
| Desktop renders backend detail text directly | mobile-adaptation | Shared redaction and finite display bounds apply to every backend display field; full prompt never shown | #191; bounded phone display and secret-safe rendering; model tests cover all parsed display fields |
| Desktop disabled completed inspector says Paused / Succeeded | drift | Completed / Succeeded; no next run/overdue or Resume | [v2 completed captures](../media/routine-inspector-v2/REPORT.md); intentional terminal-state safeguard, not a mobile-only rationale; preserve it |
| Desktop uses year/seconds and Intl relative wording | drift | Short localized year/no seconds, in 17 hr, 1 day ago and 7 hr ago | [v2 inspector captures](../media/routine-inspector-v2/REPORT.md); UTC hours now agree, but formatter drift remains a Concern under #191 |

## Visual report

- [Routine inspector v2 rendered side-by-side report](../media/routine-inspector-v2/REPORT.md): 28 original images and 28 validated receipts, seven states in both modes/platforms. Dirty Android base/diff/APK identity is retained; this is not exact integrated-head APK evidence or whole-Bot parity.
- pending: #316
- pending: #191

The Android half of a real capture is available and catalogued —
`docs/parity/visual-capture-surfaces.json` registers `bot-routines` with a
`populated`, `read-failure`, `pause-pending`, `action-rollback`, `resumed` and
`deleted` state, rendered by the debug-only
`BotsRoutinesParityActivity` from the production view model and screen on
synthetic data and an immutable clock. The pinned Desktop E2E fixture is
`apps/desktop/e2e/bot-routines-pane-narrow.spec.ts:69-101,127-148,181-257`,
which seeds a real cron job and selects the Routines subtree. The checked-in
[Desktop rendering packet](visual/desktop-bot-render-evidence/README.md#routines)
provides dark-mode Desktop evidence;
this lane does not claim they are a side-by-side or exact-head Android match.
The parent owns catalog validation, exact-head CI capture dispatch and
inspection; this author lane does not run an emulator. The clock constant is
unchanged: 2026-09-17T16:00:00Z, seventeen hours before the first next run.
Independent exact-head review and CI remain acceptance gates, separate from
local JVM/Compose/lint evidence.
