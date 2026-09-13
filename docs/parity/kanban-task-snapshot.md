# Kanban task snapshot

## Pin

| Authority | Pin | Exact source evidence |
|---|---|---|
| Desktop contribution and route | `NousResearch/hermes-agent@564aef2946c436500a5e80ee117b66b789b3f99a` | `apps/desktop/src/plugins/kanban/plugin.tsx:81-153` (`kanban`, route/sidebar/new-task command contributions) |
| Desktop board and control order | same | `apps/desktop/src/plugins/kanban/board.tsx:1-8, 104-130, 240-301` (header controls, lanes, cards, drawer opening) |
| Desktop drawer contract | same | `apps/desktop/src/plugins/kanban/types.ts:94-126`; `apps/desktop/src/plugins/kanban/drawer.tsx:358-405` |
| Gateway detail response | same | `plugins/kanban/dashboard/plugin_api.py:339-368` (`GET /tasks/{task_id}`, task, links object, child_results and siblings) |

Android implementation is `app/src/main/kotlin/com/hermesagent/mobile/plugins/kanban/KanbanPluginRepository.kt`, `KanbanViewModel.kt`, and `KanbanScreen.kt`. It calls only `GET board` and `GET tasks/{url-encoded task_id}`. It renders typed task id/title/status, optional assignee/priority/body/latest_summary/result, and at most twelve parent/child identifiers or child-result identifiers. Raw metadata, diagnostics, events, comments, run errors, envelopes and safeMessage are never passed to a UI model.

## Mobile adaptation

A one-pane phone keeps the board snapshot visible after a successful refresh. A card tap, not refresh, opens the read-only detail. Back returns to that snapshot. Refresh is available on both board and detail. The board is vertically grouped by status; no wider master/detail mode is claimed.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Horizontally interactive lanes | mobile-adaptation | Vertically grouped, tap-only cards | Desktop `board.tsx:104-130`; Android `KanbanScreen.kt:52-58`; phone one-pane adaptation |
| Board switcher | mobile-adaptation | `Board controls` menu shows disabled `Board switcher` with `WIP` | Desktop titlebar `board.tsx:80, 1326-1328`; Android `KanbanScreen.kt`; current-board-only snapshot, no board selection read or write |
| Filters and search | mobile-adaptation | `Board controls` menu shows disabled `Filters`, then `Filter cards…`, with `WIP` | Phone slice prioritizes a readable current-board snapshot; Desktop `i18n.ts:240,261`, header `board.tsx:1335-1346`; Android `KanbanScreen.kt` |
| Orchestration settings and New task | mobile-adaptation | `Board controls` menu keeps both after filtering controls, disabled with `WIP` | Desktop `i18n.ts:238-239`, header `board.tsx:1347-1362`; Android `KanbanScreen.kt`; orchestration and task creation are excluded |
| Lane collapse, bulk selection, drag/drop | omission | Not rendered | out-of-scope: #261; the read-only vertical snapshot has no mutable board model or write API; Desktop `board.tsx:104-130, 240-301, 945-1078` |
| Status and task actions | mobile-adaptation | Detail `Task actions` combines disabled `Move task`, `Copy task id`, `Copy title`, `Archive`, and `Delete` groups with `WIP` | Phone combines Desktop's adjacent status and actions controls while preserving their order and separators; Desktop `drawer.tsx:680-731`, `i18n.ts:270,372-374`; Android `KanbanScreen.kt`; all actions remain inert in this snapshot |
| Drawer edit description, assignee menu, comments, attachments, diagnostics, runs | omission | Detail shows only safe inert typed fields | out-of-scope: #261; this slice excludes mutations and unsafe diagnostic/event payloads; Desktop `drawer.tsx:233-405`; Android `KanbanScreen.kt` |
| Live socket/poll updates | mobile-adaptation | Explicit manual Refresh, stale notice | Desktop `plugin.tsx:2-5`; Android `KanbanViewModel.kt:27-94`; bounded snapshot avoids background navigation and stale overwrites |

## Visual report

- pending: #261 (implementation issue is the required evidence issue; no separate issue is necessary).

## Executable evidence

- `KanbanPluginRepositoryTest`: GET-only paths/no body, URL encoding, required `task` wrapper, links object and child_results, unknown/missing optionals, malformed required fields, blank-id refusal, bare unavailable, envelope Gone, and non-404 refusal.
- `KanbanViewModelTest`: lifecycle connection refresh, endpoint clear/refetch, no automatic detail navigation, stale same-endpoint board/detail answer rejection, fixed unavailable/Gone/refusal detail states, and stale board behavior.
- `KanbanPluginTest`: bundled registration, route/sidebar contributions, and disposal cancelling the connection collector before later reads.
- `KanbanPluginJourneyTest`: Robolectric Settings contribution launcher to the registered route, board/detail/Back journey, fixed unavailable copy, 48dp controls, and disabled WIP labels in Desktop order.
