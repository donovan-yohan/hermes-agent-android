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
| Filters and search | mobile-adaptation | `Board controls` menu shows disabled `Filters`, then `Search`, with `WIP` | Desktop header `board.tsx:1335-1346`; Android `KanbanScreen.kt`; local filtering is not implemented |
| New task | mobile-adaptation | `Board controls` menu shows disabled `New task` with `WIP` | Desktop header `board.tsx:1359-1362`; Android `KanbanScreen.kt`; task creation is excluded |
| Lane collapse, bulk selection, drag/drop | omission | Not rendered | true non-goal for this snapshot: no mutable local board model or write API; Desktop `board.tsx:104-130, 240-301, 945-1078` |
| Task actions | mobile-adaptation | Detail `Task actions` menu shows disabled `Move task`, `Archive task`, and `Delete task` with `WIP` | Desktop `drawer.tsx:690-731`; Android `KanbanScreen.kt`; mutations are excluded |
| Drawer edit description, assignee menu, comments, attachments, diagnostics, runs | omission | Detail shows only safe inert typed fields | true non-goal for this snapshot: these require writes or expose unsafe diagnostics; Desktop `drawer.tsx:233-405`; Android `KanbanScreen.kt` |
| Live socket/poll updates | mobile-adaptation | Explicit manual Refresh, stale notice | Desktop `plugin.tsx:2-5`; Android `KanbanViewModel.kt:27-94`; bounded snapshot avoids background navigation and stale overwrites |

## Visual report

- pending: #261 (implementation issue is the required evidence issue; no separate issue is necessary).

## Executable evidence

- `KanbanPluginRepositoryTest`: GET-only paths/no body, URL encoding, required `task` wrapper, links object and child_results, unknown/missing optionals, malformed required fields, blank-id refusal, bare unavailable, envelope Gone, and non-404 refusal.
- `KanbanViewModelTest`: lifecycle connection refresh, endpoint clear/refetch, no automatic detail navigation, stale same-endpoint board/detail answer rejection, fixed unavailable/Gone/refusal detail states, and stale board behavior.
- `KanbanPluginTest`: bundled registration, route/sidebar contributions, and disposal cancelling the connection collector before later reads.
- `KanbanPluginJourneyTest`: Robolectric Settings contribution launcher to the registered route, board/detail/Back journey, fixed unavailable copy, 48dp controls, and disabled WIP labels in Desktop order.
