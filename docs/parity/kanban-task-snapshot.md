# Kanban task snapshot

## Pin

| Authority | Pin | Exact source evidence |
|---|---|---|
| Desktop contribution and route | `NousResearch/hermes-agent@564aef2946c436500a5e80ee117b66b789b3f99a` | `apps/desktop/src/plugins/kanban/plugin.tsx:81-153` (`kanban`, route/sidebar/new-task command contributions) |
| Desktop board and control order | same | `apps/desktop/src/plugins/kanban/board.tsx:1-8, 240-301, 1330-1363, 1390-1415, 1428` (header controls, lanes, cards, drawer opening) |
| Desktop drawer contract | same | `apps/desktop/src/plugins/kanban/types.ts:94-126`; `apps/desktop/src/plugins/kanban/drawer.tsx:358-405` |
| Gateway board response | same | `plugins/kanban/dashboard/plugin_api.py:265-336` (`GET /board`, `columns[{name,tasks}]`) |
| Gateway detail response | same | `plugins/kanban/dashboard/plugin_api.py:339-368` (`GET /tasks/{task_id}`, task, links object, child_results and siblings) |

Android implementation is `app/src/main/kotlin/com/hermesagent/mobile/plugins/kanban/KanbanPluginRepository.kt`, `KanbanViewModel.kt`, and `KanbanScreen.kt`. Snapshot reads call `GET board` and `GET tasks/{url-encoded task_id}`; New task uses the separately scoped typed host capability described below. It renders typed task id/title/status, optional assignee/priority/body/latest_summary/result, and at most twelve parent/child identifiers or child-result identifiers. Raw metadata, diagnostics, events, comments, run errors, envelopes and safeMessage are never passed to a UI model.

## Mobile adaptation

A one-pane phone keeps the board snapshot visible after a successful refresh. A card tap, not refresh, opens the read-only detail. Back returns to that snapshot. Refresh is available on both board and detail. The board is vertically grouped by status; no wider master/detail mode is claimed.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Horizontally interactive lanes | mobile-adaptation | Vertically grouped, tap-only cards | Desktop `board.tsx:1390-1415`; Android `KanbanScreen.kt:141-179`; phone one-pane adaptation |
| Board switcher | mobile-adaptation | `Board controls` menu shows disabled `Board switcher` with `WIP` | Desktop titlebar `board.tsx:1326-1328`, control `board-switcher.tsx:1-6`; Android `KanbanScreen.kt`; current-board-only snapshot, no board selection read or write |
| Filters and search | mobile-adaptation | `Board controls` menu shows disabled `Filters`, then `Filter cards…`, with `WIP` | Phone slice prioritizes a readable current-board snapshot; Desktop `apps/desktop/src/plugins/kanban/i18n.ts:240,261`, header `board.tsx:1335-1346`; Android `KanbanScreen.kt` |
| Orchestration settings | omission | Disabled WIP after filtering controls | coming soon; orchestration editing remains unavailable |
| New task | mobile-adaptation | Enabled New task opens a scrollable phone form: title, description, priority; configured default assignee and board workspace inherited | Full-screen form fits the phone keyboard. Typed host capability uses the plugin namespace and explicit-board readback; source audit below |
| Advanced New task options | omission | Workspace/Assignee overrides, skills, model, parent, goal mode and estimate stay disabled WIP | coming soon; basic creation uses server workspace defaults and the resolved orchestration assignee |
| Lane collapse, bulk selection, drag/drop | omission | Not rendered | out-of-scope: #261; basic task creation does not add lane, bulk or drag interactions; Desktop `board.tsx:240-301, 945-1078, 1310-1321` |
| Status and task actions | mobile-adaptation | Detail `Task actions` combines disabled `Move to`, `Copy task id`, `Copy title`, `Archive`, and `Delete` groups with `WIP` | Phone combines Desktop's adjacent status and actions controls while preserving their order and separators; Desktop status transition `ui.tsx:190-228` (`i18n.ts:267` `Move to`, `board.tsx:1017`), `drawer.tsx:680-731`, `i18n.ts:270,372-374`; Android `KanbanScreen.kt`; all actions remain inert in this snapshot |
| Drawer edit description, assignee menu, comments, attachments, diagnostics, runs | omission | Detail shows only safe inert typed fields | out-of-scope: #261; drawer mutations and unsafe diagnostic/event payloads remain excluded; Desktop `drawer.tsx:184-475, 899-903`; Android `KanbanScreen.kt` |
| Live socket/poll updates | mobile-adaptation | Explicit manual Refresh, stale notice | Desktop `apps/desktop/src/plugins/kanban/api.ts:63-65,122-125` and `board.tsx:1088-1089`; Android `KanbanViewModel.kt:63-103`; bounded snapshot avoids background navigation and stale overwrites |

## Visual report

- pending: #261 (implementation issue is the required evidence issue; no separate issue is necessary).

## New task source audit (2026-10-22)

Basic task creation is additionally inspected at `NousResearch/hermes-agent@81a55437f3f31a9f12a30c53b6c5d49d420898aa`; the historical Pin table and visual provenance are not restamped:

- `apps/desktop/src/plugins/kanban/board.tsx:547-675,688-766`: title/body/priority form, resolved default assignee, inherited board workspace, create/invalidation flow.
- `apps/desktop/src/plugins/kanban/api.ts:419-443,506`: namespaced create plus best-effort dispatcher nudge.
- `plugins/kanban/dashboard/plugin_api.py:414-457`: exact POST tasks schema and response; `tests/plugins/test_kanban_dashboard_plugin.py:87-114` proves create then board visibility.
- `hermes_cli/web_server.py:412-439,456-493`: plugin runtime gate behind token/native authentication.
- `apps/desktop/src/plugins/kanban/i18n.ts:287-311`: form labels, Create task and Cancel.

Android opens a typed, irrevocably closable endpoint-bound host scope, resolves the current board once, then sends all create/readback/nudge calls to `api/plugins/kanban` with that explicit board. Close and OkHttp enqueue share a revocation lock; endpoint teardown uses the existing dispatch fence. Guarded transport disables redirect/replay. A single dialog issues at most one POST, with an idempotency key. Success requires a fresh task GET matching the returned id/title; a lost, refused or malformed reply does not automatically retry and instructs the person to close and refresh. The server has no atomic create-plus-nudge transaction, so dispatcher availability is shown separately from confirmed creation.

Desktop's advanced fields remain visible WIP rather than being silently absent. Android adds a safe confirmation-failure sentence instead of displaying raw backend exceptions. `KanbanCreateTest` exercises the actual authenticated production transport and held-credential revocation, endpoint fencing, explicit board/idempotency payload and unconfirmed receipt. Compose covers New task -> form -> Create task and Cancel. No live-Gateway or rendered approval is claimed: Concern, pending #261.

## Executable evidence

- `KanbanPluginRepositoryTest`: GET-only paths/no body, URL encoding, required `task` wrapper, links object and child_results, unknown/missing optionals, malformed required fields, blank-id refusal, bare unavailable, envelope Gone, and non-404 refusal.
- `KanbanViewModelTest`: lifecycle connection refresh, endpoint clear/refetch, no automatic detail navigation, stale same-endpoint board/detail answer rejection, fixed unavailable/Gone/refusal detail states, and stale board behavior.
- `KanbanPluginTest`: bundled registration, route/sidebar contributions, and disposal cancelling the connection collector before later reads.
- `KanbanPluginJourneyTest`: Robolectric Settings contribution launcher to the registered route, board/detail/Back journey, empty and unavailable board states, fixed unavailable copy, 48dp controls, and disabled WIP labels in Desktop order.
