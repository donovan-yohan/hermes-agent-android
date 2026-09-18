# Bot Routines parity

## Pin

| Authority | Revision | Read method |
|---|---|---|
| Hermes Desktop and Gateway | `d177b119e9c56c9ddc0b7379ffce52341ec06584` | read-only `git -C <snapshot> show <sha>:<path>` |

Every source location below is against that exact revision. The Desktop
snapshot used for this slice is the read-only export at
`/tmp/hermes-mobile-upstream-20260918`, whose `HEAD` is that SHA.

## Scope of this page

This covers the read-only #310/#313 foundation and #316's existing-row actions:
pause, resume and delete. The list still includes disabled jobs. Writes send
only `cron.manage {action:"pause"|"resume"|"remove", name:<job id>, profile:<raw bot>}`.
Creation, the schedule picker, delivery targets, continuity, repeat editing,
the inspector and the legacy auto-pause sweep remain #191 scope. Creation and
unsupported legacy/unknown row actions remain visible, disabled and marked `WIP`.
This does not close #191 or claim rendered parity acceptance.

## Sources and action evidence

| Question | Desktop/Gateway source | Android evidence |
|---|---|---|
| The read and its scope | `apps/desktop/src/plugins/hermes-bots/cron.tsx:103-127` (`loadRoutines`), and its own test `cron-load.test.ts:58-78` pinning `{action:'list', include_disabled:true, profile:'research'}` | `BotsPluginRepository.loadRoutines` sends exactly that object through `PluginHost.requestAtEndpoint`; `BotsRoutinesRepositoryTest` asserts the request shape, that `include_disabled` is `true` on the wire, and that the profile is sent verbatim |
| The handler | `tui_gateway/methods_tools.py:1075-1085` (`@_scoped_rpc("cron.manage", 5023)`, `_ok(rid, result)` around the `cronjob()` payload, `scoped` echo), `:42-60` (profile resolution and the `4064 profile '<p>' not found` refusal) | `BotsRoutinesLoad` distinguishes `Loaded` / `MismatchedScope` / `Rejected` / `UnavailableOnGateway` / `Refused`; `BotsRoutinesRepositoryTest` covers each |
| The row | `tools/cronjob_job_args.py:351-399` (`_format_job`), narrowed by Desktop's own `apps/desktop/src/plugins/hermes-bots/types.ts:257-283` (`RoutineJob`) | `parseRoutineJobs` / `parseRoutineJob`; `BotsRoutinesParseTest` reads the full pinned row shape and asserts no unread member survives |
| Titles | `cron.tsx:73` (`BOT_TAG_RE`), `:87-95` (`routineBot`, `routineTitle`) | `routineBot` / `routineTitle`; `BotsRoutinesParseTest` covers the tag strip, the tag-only name and mid-name brackets |
| State derivation | `cron.tsx:489` (`serverActive = !legacyUnsafe && job.enabled !== false && job.state !== 'paused'`), pinned by `cron-detail.test.tsx:74-78`; Gateway's own `cron/jobs.py:525-538` (`effective_job_state`) | `RoutineRunState`; paused is claimed on `enabled == false` **or** `state == "paused"`, and an unrecognised state word renders no state claim and no running dot |
| Which jobs show | `cron.tsx:215-230` (`selectRoutineJobs`), pinned by `cron-jobs-view.test.ts` | `selectRoutineJobs` / `routineScopeAgrees`; `BotsRoutinesRepositoryTest` covers scoped, unmarked and mismatched answers |
| The filter hint | `cron.tsx:232-248` (`routineFilterHint`), pinned by `cron-jobs-view.test.ts:98-117` | `routineFilterHint`; the empty card renders it in the description slot, `BotsRoutinesJourneyTest` |
| Schedule labels | `cron.tsx:288-324` (`scheduleLabel`) | `routineScheduleLabel`; `BotsRoutinesParseTest` covers every recognised form and the pass-through |
| Relative next run | `cron.tsx:328-332` with `apps/desktop/src/lib/time.ts:38-56` (`Intl.RelativeTimeFormat`, style `short`) | `routineRelativeLabel`; `BotsRoutinesParseTest` pins `in 5 min` / `in 2 hr` / `in 1 day` and the half-up rounding boundary |
| The pane's states | `cron.tsx:1277-1318`: stale banner over a held list, loading, failure + Retry, empty, populated | `BotsRoutinesPhase` and `BotsRoutinesScreen`; `BotsRoutinesViewModelTest` and `BotsRoutinesJourneyTest` cover each |
| The pane's header | `cron.tsx:1252-1275`: the bot's face, its display name and `@handle`, the uppercase pane noun, the New-cron control | `RoutinesOwnerHeader`; the display name travels with the selection (`BotsPlugin`'s `onOpenRoutines` passes `displayName(row.name, row.displayName)`), and the New-cron control is the marked disabled one |
| The row's controls | `cron.tsx:539-576`: a title button, a pause/resume Switch and a delete control, as siblings | `RoutineRowItem` renders a token switch followed by Codicon Trash, independently actionable; the inspector remains deferred |
| Mutation and confirmation | `cron.tsx:496-523,559-574`, `cron-owner.test.tsx:53-69`; `tui_gateway/methods_tools.py:1097-1098` | Row delete is immediate, with **no confirmation dialog**, matching Desktop. `BotsRoutineActionsTest` checks exact payloads and literal acknowledgements; `BotsRoutinesJourneyTest` exercises the production route, pending state, rollback, both toggles and direct delete |
| Reads remain inert | `cron.tsx:131-166` is deliberately not ported | `BotsRoutinesRepositoryTest` still asserts that a read sends only `list`; no legacy auto-pause sweep |
| Ordering and identity | `cron-owner.test.tsx:53-69` captures the rendered owner | Immutable owner + endpoint + selection + job identity; actual `requestAtEndpoint` wire fence. A mutation revision invalidates pre-operation and overlapping reads; pending writes block only their row; targeted rollback never restores another row. Every completion requests an authoritative list, never a blind mutation retry. Tests gate real coroutine interleavings and the production host dispatch |

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

No backend prose is rendered anywhere. The failure, delivery-failure,
pause-reason and prompt members are read for exactly one purpose — recognising
Desktop's legacy delegation wrapper — and are not kept on the parsed row at all,
so no surface downstream can render them by accident; a run's status is a closed
enum mapped to local copy, and an unrecognised state word renders nothing.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| A `scoped` echo naming another profile falls back to the `[bot:]` tag filter and shows the tagged rows (`cron.tsx:221-229`, `cron-jobs-view.test.ts:52-61`) | drift | The answer is refused: the surface shows "Not this bot's scheduled jobs" with Retry, and no part of that store — tagged or not — is rendered | #310. Deliberate, not an adaptation: the safety rationale is a claim about the data, not about a phone, so it holds on every platform. On Desktop both profiles are machines Desktop is in and a tag filter is a conservative fallback; here the reply describes a bot the person did not ask for, and a filter that *appears* to work teaches the reader that a mismatched answer is normal. Failing closed states the truth and cannot leak |
| A legacy delegated routine is paused on load, and its row says "Paused for security: delete and recreate this legacy job before running it again." (`cron.tsx:131-166`, `:591-595`) | drift | The routine is listed, labelled as one this app cannot manage yet, and its state is whatever the Gateway said — never a pause this app did not perform; its delete also stays WIP | #310/#316. No legacy mutation is issued; the sweep and legacy management stay in #191 |
| Routines is reached by focusing a bot: Desktop's Bots pane and the Routines tile are on screen at once, and the pane follows `$focusedBotOwner` / `$selectedBot` (`cron.tsx:42`, `:1198-1207`) | mobile-adaptation | A visible, labelled control on each roster row opens that bot's own Routines destination; the pane header names the bot it is scoped to | A phone shows one surface at a time, so the destination needs an entry on the row; the alternative — a hidden gesture — is not discoverable, and the row's own tap must stay Bot Chat. The owner is plugin-scoped instance state because this app's plugin contract has no module globals (`BotsPluginTest` asserts the plugin declares no mutable statics) |
| The row's face: an avatar or mood-driven `BotFace` beside the display name and `@handle` (`cron.tsx:1253`, `avatar.tsx`) | omission | The header shows the bot's display name and the pane noun, with no face | deferred: #189 — the roster already ledgers the absent avatar surface, and this slice adds no second one |
| The header's New-cron control (`cron.tsx:1270-1274`) | omission | Creation remains visible and disabled behind WIP | coming soon — #191 |
| Desktop row controls are enabled without a scope receipt and without terminal-state checks (`cron.tsx:489,559-569`) | drift | Unknown/stale owner scope cannot authorize a write; terminal toggles are disabled, while known completed rows may be deleted. Legacy/unknown records remain WIP | #316. A tag fallback licenses display, not a write to a profile's store. Unknown and terminal state cannot license resume |
| Delete is hover-revealed (`cron.tsx:567`) | mobile-adaptation | Trash remains visible with the Android touch-target floor; switch comes first, no menu or separators, no confirmation | #316. A touch screen has no persistent hover; `BotsRoutinesJourneyTest` checks direct delete |
| The pane polls every 20s and refetches on its socket opening (`cron.tsx:183-189`) | mobile-adaptation | The destination re-reads when it is entered and when the connection comes back; there is no timer | A phone does not leave this destination mounted while the person works elsewhere, so entering it is the trigger, and a background poll would spend the device's battery on a surface nobody is looking at |
| Desktop's inspector dialog opens from the row's title for one job's full detail (`cron.tsx:418-470`, `routineDetailRows`) | omission | The row shows the title, schedule, repeat and next run inline; there is no per-job inspector | deferred: #191 — the read-only slice renders what the list already carries, and the inspector is part of the parent's remaining scope |

## Visual report

- pending: #316
- pending: #191

No rendered Desktop/Android side-by-side was captured for this surface. The
Android half of a real capture is available and catalogued —
`docs/parity/visual-capture-surfaces.json` registers `bot-routines` with a
`populated`, `read-failure`, `pause-pending`, `action-rollback`, `resumed` and
`deleted` state, rendered by the debug-only
`BotsRoutinesParityActivity` from the production view model and screen on
synthetic data and an immutable clock — and can be dispatched through the
`Visual parity capture` workflow. What does not exist is the **Desktop** half
at this pin: the pinned export carries no Routines E2E fixture, so there are no
same-pin Desktop pixels to compare against, and the parity claim below the pin
is unearned. This is explicitly pending evidence, not a pixel-parity claim.
The parent owns exact-head CI capture dispatch and inspection; this author lane
does not run an emulator. The clock constant is unchanged: 2026-09-17T16:00:00Z,
seventeen hours before the first next run. Independent exact-head review and CI
remain acceptance gates, separate from local JVM/Compose/lint evidence.
