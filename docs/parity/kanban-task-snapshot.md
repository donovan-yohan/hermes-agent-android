# Kanban task snapshot

## Pin

| Authority | Pin | Exact source evidence |
|---|---|---|
| Desktop contribution and route | `NousResearch/hermes-agent@564aef2946c436500a5e80ee117b66b789b3f99a` | `apps/desktop/src/plugins/kanban/plugin.tsx:81-153` (`kanban`, route/sidebar/new-task command contributions) |
| Desktop board and control order | same | `apps/desktop/src/plugins/kanban/board.tsx:1-8, 240-301, 1330-1363, 1390-1415, 1428` (header controls, lanes, cards, drawer opening) |
| Desktop drawer contract | same | `apps/desktop/src/plugins/kanban/types.ts:94-126`; `apps/desktop/src/plugins/kanban/drawer.tsx:358-405` |
| Gateway board response | same | `plugins/kanban/dashboard/plugin_api.py:265-336` (`GET /board`, `columns[{name,tasks}]`) |
| Gateway detail response | same | `plugins/kanban/dashboard/plugin_api.py:339-368` (`GET /tasks/{task_id}`, task, links object, child_results and siblings) |

Android implementation is `app/src/main/kotlin/com/hermesagent/mobile/plugins/kanban/KanbanPluginRepository.kt`, `KanbanViewModel.kt`, and `KanbanScreen.kt`. It calls only `GET board` and `GET tasks/{url-encoded task_id}`. It renders typed task id/title/status, optional assignee/priority/body/latest_summary/result, and at most twelve parent/child identifiers or child-result identifiers. Raw metadata, diagnostics, events, comments, run errors, envelopes and safeMessage are never passed to a UI model.

## Mobile adaptation

A one-pane phone keeps the board snapshot visible after a successful refresh. A card tap, not refresh, opens the read-only detail. Back returns to that snapshot. Refresh is available on both board and detail. The board is vertically grouped by status; no wider master/detail mode is claimed.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Horizontally interactive lanes | mobile-adaptation | Vertically grouped, tap-only cards | Desktop `board.tsx:1390-1415`; Android `KanbanScreen.kt:141-179`; phone one-pane adaptation |
| Board switcher | mobile-adaptation | `Board controls` menu shows disabled `Board switcher` with `WIP` | Desktop titlebar `board.tsx:1326-1328`, control `board-switcher.tsx:1-6`; Android `KanbanScreen.kt`; current-board-only snapshot, no board selection read or write |
| Filters and search | mobile-adaptation | Enabled `Filters` menu and `Filter cards…` search over title/body/id, exact profile/tenant intersection; Show archived reloads the snapshot | Phone keeps the search field visible and puts filters in Board controls; source audit below; no task mutation |
| Group Running by profile | omission | Visible disabled WIP in Filters | coming soon; vertical status groups do not yet subgroup by profile |
| Orchestration settings and New task | mobile-adaptation | `Board controls` menu keeps both after filtering controls, disabled with `WIP` | Desktop `apps/desktop/src/plugins/kanban/i18n.ts:238-239`, header `board.tsx:1347-1362`; Android `KanbanScreen.kt`; orchestration and task creation are excluded |
| Lane collapse, bulk selection, drag/drop | omission | Not rendered | out-of-scope: #261; the read-only vertical snapshot has no mutable board model or write API; Desktop `board.tsx:240-301, 945-1078, 1310-1321` |
| Status and task actions | mobile-adaptation | Detail `Task actions` combines disabled `Move to`, `Copy task id`, `Copy title`, `Archive`, and `Delete` groups with `WIP` | Phone combines Desktop's adjacent status and actions controls while preserving their order and separators; Desktop status transition `ui.tsx:190-228` (`i18n.ts:267` `Move to`, `board.tsx:1017`), `drawer.tsx:680-731`, `i18n.ts:270,372-374`; Android `KanbanScreen.kt`; all actions remain inert in this snapshot |
| Drawer edit description, assignee menu, comments, attachments, diagnostics, runs | omission | Detail shows only safe inert typed fields | out-of-scope: #261; this slice excludes mutations and unsafe diagnostic/event payloads; Desktop `drawer.tsx:184-475, 899-903`; Android `KanbanScreen.kt` |
| Live socket/poll updates | mobile-adaptation | Explicit manual Refresh, stale notice | Desktop `apps/desktop/src/plugins/kanban/api.ts:63-65,122-125` and `board.tsx:1088-1089`; Android `KanbanViewModel.kt:63-103`; bounded snapshot avoids background navigation and stale overwrites |

## Visual report

- pending: #73. Historical implementation issue #261 is closed; its [explicit evidence-debt comment](https://github.com/donovan-yohan/hermes-agent-android/issues/261#issuecomment-5656598661) is not rendered approval. The open migration epic owns the pending comparison.

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
