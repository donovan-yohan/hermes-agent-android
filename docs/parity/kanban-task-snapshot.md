# Kanban task snapshot

## Pin

| Authority | Pin | Exact source evidence |
|---|---|---|
| Desktop contribution and route | `NousResearch/hermes-agent@564aef2946c436500a5e80ee117b66b789b3f99a` | `apps/desktop/src/plugins/kanban/plugin.tsx:81-153` (`kanban`, route/sidebar/new-task command contributions) |
| Desktop board and control order | same | `apps/desktop/src/plugins/kanban/board.tsx:1-8, 240-301, 1330-1363, 1390-1415, 1428` (header controls, lanes, cards, drawer opening) |
| Desktop drawer contract | same | `apps/desktop/src/plugins/kanban/types.ts:94-126`; `apps/desktop/src/plugins/kanban/drawer.tsx:358-405` |
| Gateway board response | same | `plugins/kanban/dashboard/plugin_api.py:265-336` (`GET /board`, `columns[{name,tasks}]`) |
| Gateway detail response | same | `plugins/kanban/dashboard/plugin_api.py:339-368` (`GET /tasks/{task_id}`, task, links object, child_results and siblings) |

Android implementation is `app/src/main/kotlin/com/hermesagent/mobile/plugins/kanban/KanbanPluginRepository.kt`, `KanbanViewModel.kt`, and `KanbanScreen.kt`. Snapshot reads resolve `GET boards` then call named `GET board` and `GET tasks/{url-encoded task_id}`; New task and Move to use separately scoped typed host capabilities described below. It renders typed task id/title/status, optional assignee/priority/body/latest_summary/result, and at most twelve parent/child identifiers or child-result identifiers. Raw metadata, diagnostics, events, comments, run errors, envelopes and safeMessage are never passed to a UI model.

## Mobile adaptation

A one-pane phone keeps the board snapshot visible after a successful refresh. A card tap, not refresh, opens the read-only detail. Back returns to that snapshot. Refresh is available on both board and detail. The board is vertically grouped by status; no wider master/detail mode is claimed.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Horizontally interactive lanes | mobile-adaptation | Vertically grouped, tap-only cards | Desktop `board.tsx:1390-1415`; Android `KanbanScreen.kt:141-179`; phone one-pane adaptation |
| Board switcher | mobile-adaptation | `Board controls` menu shows disabled `Board switcher` with `WIP` | Desktop titlebar `board.tsx:1326-1328`, control `board-switcher.tsx:1-6`; Android `KanbanScreen.kt`; current-board-only snapshot, no board selection read or write |
| Move to | mobile-adaptation | Enabled task action opens a named-board confirmation with legal server-enumerated destinations and fresh task readback | Phone uses a scrollable confirmation instead of a pointer submenu; retains Desktop target order and excludes Running/Review/Scheduled; source audit below |
| Remaining task actions | omission | Copy task id/title, Archive and Delete remain visible disabled WIP | coming soon; only status movement is implemented |
| Filters and search | mobile-adaptation | Enabled `Filters` menu and `Filter cards…` search over title/body/id, exact profile/tenant intersection; Show archived reloads the snapshot | Phone keeps the search field visible and puts filters in Board controls; source audit below; no task mutation |
| Group Running by profile | omission | Visible disabled WIP in Filters | coming soon; vertical status groups do not yet subgroup by profile |
| Orchestration settings | omission | Disabled WIP after filtering controls | coming soon; orchestration editing remains unavailable |
| New task | mobile-adaptation | Enabled New task opens a scrollable phone form: title, description, priority; configured default assignee and board workspace inherited | Full-screen form fits the phone keyboard. Typed host capability uses the plugin namespace and explicit-board readback; source audit below |
| Advanced New task options | omission | Workspace/Assignee overrides, skills, model, parent, goal mode and estimate stay disabled WIP | coming soon; basic creation uses server workspace defaults and the resolved orchestration assignee |
| Lane collapse, bulk selection, drag/drop | omission | Not rendered | out-of-scope: #261; basic task creation does not add lane, bulk or drag interactions; Desktop `board.tsx:240-301, 945-1078, 1310-1321` |
| Drawer edit description, assignee menu, comments, attachments, diagnostics, runs | omission | Detail shows only safe inert typed fields | out-of-scope: #261; drawer mutations and unsafe diagnostic/event payloads remain excluded; Desktop `drawer.tsx:184-475, 899-903`; Android `KanbanScreen.kt` |
| Live socket/poll updates | mobile-adaptation | Explicit manual Refresh, stale notice | Desktop `apps/desktop/src/plugins/kanban/api.ts:63-65,122-125` and `board.tsx:1088-1089`; Android `KanbanViewModel.kt:63-103`; bounded snapshot avoids background navigation and stale overwrites |

## Visual report

- pending: #73. Historical implementation issue #261 is closed; its [explicit evidence-debt comment](https://github.com/donovan-yohan/hermes-agent-android/issues/261#issuecomment-5656598661) is not rendered approval. The open migration epic owns the pending comparison.

## Move to source audit (2026-10-22)

Movement was additionally inspected at `NousResearch/hermes-agent@81a55437f3f31a9f12a30c53b6c5d49d420898aa`; historical source and visual pins above are unchanged:

- `apps/desktop/src/plugins/kanban/ui.tsx:48-55,194-228` and `apps/desktop/src/plugins/kanban/board.tsx:325-333,1201-1237`: legal targets, order, PATCH handler and reconciliation.
- `apps/desktop/src/plugins/kanban/api.ts:419-440,506`: namespace PATCH plus best-effort dispatcher nudge.
- `plugins/kanban/dashboard/plugin_api.py:533-551,594-614,638-716`: status schema, backend dependency/conflict enforcement and task receipt.
- `tests/plugins/test_kanban_dashboard_plugin.py:203-287`: backend review/reopen/dependency semantics. Android does not recreate backend transition rules.
- `hermes_cli/web_server.py:412-439,456-493`: authenticated plugin routing.
- `apps/desktop/src/plugins/kanban/i18n.ts:233-241,280`: verbatim status labels and Move to.

The repository resolves a named board before fetching the snapshot. That slug travels with board tasks, detail and selection. The typed move scope uses only this source slug for lane/task reads and mutation, never a later current pointer or a title as identity. Renaming does not change identity. Missing source identity refuses preparation. This follows `apps/desktop/src/plugins/kanban/api.ts:358-395` and `plugins/kanban/dashboard/plugin_api.py:1411-1427` at the inspected SHA. Confirmation labels use `redact()` while raw source board/task/status identities remain unchanged for dispatch.

Done exposes a completion-summary field. Fresh non-review tasks without a stored result require nonblank human evidence; Android sends that text in both `result` and `summary`. Review approvals may omit new evidence; the client invents none. The actual packaged plugin collects evidence in `plugins/kanban/dashboard/dist/index.js:939-979`; the real router forwards fields in `plugins/kanban/dashboard/plugin_api.py:594-614,638-716`, and the gate is `hermes_cli/kanban_db.py:2639-2668,2772-2807`, tested by `tests/hermes_cli/test_kanban_empty_completion.py:32-69`, all at `81a55437f3f31a9f12a30c53b6c5d49d420898aa`. A bare status-only fake response does not prove fresh Done works.

The scope captures endpoint/transport before suspension, uses the existing dispatch fence and a close/enqueue lock, disables replay/redirect, and never automatically retries a refused or uncertain mutation. A board-unavailable result removing the form irrevocably closes its scope and invalidates pending detail work. Success requires both the PATCH receipt and fresh named-task readback to match id/status.

The backend offers no version/compare-and-set precondition: a worker may change status after preparation, and a fresh read is not an atomic lock. The server remains authoritative; conflicts or rapid post-write transitions show fixed unconfirmed/recovery copy, not fabricated success or raw backend exceptions. The extra confirmation and inline evidence field are touch/error-recovery adaptations. Tests exercise production authenticated transport, encoded id, source-board identity across pointer change/rename, legal lane exclusion, Done evidence/review approval, background-failure credential admission revocation, late-response invalidation, endpoint invalidation, conflict and readback mismatch. Compose covers redacted semantics and required evidence input. Rendered/native/live-Gateway acceptance remains Concern, pending #73.

## New task source audit (2026-10-22)

Basic task creation is additionally inspected at `NousResearch/hermes-agent@81a55437f3f31a9f12a30c53b6c5d49d420898aa`; the historical Pin table and visual provenance are not restamped:

- `apps/desktop/src/plugins/kanban/board.tsx:547-675,688-766`: title/body/priority form, resolved default assignee, inherited board workspace, create/invalidation flow.
- `apps/desktop/src/plugins/kanban/api.ts:419-443,506`: namespaced create plus best-effort dispatcher nudge.
- `plugins/kanban/dashboard/plugin_api.py:414-457`: exact POST tasks schema and response; `tests/plugins/test_kanban_dashboard_plugin.py:87-114` proves create then board visibility.
- `hermes_cli/web_server.py:412-439,456-493`: plugin runtime gate behind token/native authentication.
- `apps/desktop/src/plugins/kanban/i18n.ts:287-311`: form labels, Create task and Cancel.

Android opens a typed, irrevocably closable endpoint-bound host scope, resolves the current board once, then sends all create/readback/nudge calls to `api/plugins/kanban` with that explicit board. Preparation binds that board's exact `default_workspace_kind`; submission sends it explicitly, omitting a path override so both project-linked and directory-only boards inherit their configured workspace. At the inspected SHA, this is `apps/desktop/src/plugins/kanban/board.tsx:565-583,645-657`, `plugins/kanban/dashboard/plugin_api.py:1381-1394,1411-1427` and `tests/plugins/test_kanban_dashboard_plugin.py:116-134`. Omitted kind is not equivalent for directory-only boards.

Close and OkHttp enqueue share a revocation lock; endpoint teardown uses the existing dispatch fence. A board-unavailable result that removes the form also closes the scope and rejects late continuations, including mutations still waiting for credentials. Guarded transport disables redirect/replay. A single dialog issues at most one POST, with an idempotency key. Success requires a fresh task GET matching the returned id/title; a lost, refused or malformed reply does not automatically retry and instructs the person to close and refresh. The server has no atomic create-plus-nudge transaction, so dispatcher availability is shown separately from confirmed creation. Backend board/assignee display labels pass through `redact()` without changing wire identities.

Desktop's advanced fields remain visible WIP rather than being silently absent. Android adds a safe confirmation-failure sentence instead of displaying raw backend exceptions. `KanbanCreateTest` exercises the actual authenticated production transport, selected-board workspace kinds, raw assignee identity, endpoint fencing, explicit board/idempotency payload and unconfirmed receipt. `KanbanCreationStateTest` and `KanbanCreateRevocationTest` cover a held background board failure followed by late completion or credential release. Compose covers New task -> form -> Create task, Cancel, and redacted backend-label semantics. No live-Gateway or rendered approval is claimed: Concern, pending #73.

## Filtering source audit (2026-10-22)

New filtering behavior was inspected at `NousResearch/hermes-agent@81a55437f3f31a9f12a30c53b6c5d49d420898aa`:

- `apps/desktop/src/plugins/kanban/board.tsx:880-953,1183-1197`: menu order, exact profile/tenant equality, trimmed case-insensitive title/body/id search. Filters are UI-only; unfiltered task records remain authoritative.
- `apps/desktop/src/plugins/kanban/api.ts:388-389`: archived snapshot query.
- `plugins/kanban/dashboard/plugin_api.py:291-370`: board inventory and `include_archived` read contract.
- `apps/desktop/src/plugins/kanban/i18n.ts:252-278`: verbatim labels and no-match copy.

The historical Pin table and visual provenance above are unchanged. `KanbanFiltersTest` covers intersection, inventory parsing, archived query, endpoint clearing and obsolete snapshot rejection. Backend inventory labels pass through `redact()` at display, while exact unmodified values govern equality and selection. The Compose journey exercises text search, profile choice, no-match recovery and synthetic secret-label semantics with raw identity retained by the handler. No rendered approval is claimed: Concern, pending #73; closed #261 explicitly retains evidence debt, not approval.

## Executable evidence

- `KanbanPluginRepositoryTest`: GET-only paths/no body, URL encoding, required `task` wrapper, links object and child_results, unknown/missing optionals, malformed required fields, blank-id refusal, bare unavailable, envelope Gone, and non-404 refusal.
- `KanbanViewModelTest`: lifecycle connection refresh, endpoint clear/refetch, no automatic detail navigation, stale same-endpoint board/detail answer rejection, fixed unavailable/Gone/refusal detail states, and stale board behavior.
- `KanbanPluginTest`: bundled registration, route/sidebar contributions, and disposal cancelling the connection collector before later reads.
- `KanbanPluginJourneyTest`: Robolectric Settings contribution launcher to the registered route, board/detail/Back journey, empty and unavailable board states, fixed unavailable copy, 48dp controls, and disabled WIP labels in Desktop order.
