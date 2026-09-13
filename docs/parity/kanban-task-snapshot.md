# Kanban task snapshot

## Pin

| Authority | Pin | Source paths |
|---|---|---|
| Hermes Desktop and Gateway contract | `NousResearch/hermes-agent@564aef2946c436500a5e80ee117b66b789b3f99a` | `apps/desktop/src/contrib/plugins.ts`, Kanban plugin REST namespace |

Android uses only `PluginRest`: `GET board`, then `GET tasks/{url-encoded task_id}`. The projection accepts the board's `columns`, `tenants`, `assignees`, `latest_event_id`, and `now` envelope and the detail's sibling task collections. It renders only required task id, title, and status; unknown fields are ignored.

## Mobile adaptation

Desktop lanes become a vertically grouped status snapshot. The phone drills from the first safely available task into a full-screen, read-only detail and Back returns to the snapshot. No board selector is invented. Writes, drag/drop, task controls, live feed, polling, sockets, links, HTML, Markdown execution, diagnostics, metadata, and persistence are deliberately omitted from this read-only slice.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Multi-lane board with live interaction | mobile-adaptation | Non-empty statuses stack vertically; manual refresh only | Phone viewport and explicitly read-only scope (#261) |
| Board/task mutations and live feed | omission | Not rendered | non-goal: this snapshot has no mutation or event contract |

## Visual report

- pending: #261 (no rendered pixels are claimed)

## Executable evidence

- `KanbanPluginRepositoryTest`: exact GET paths, decoding, malformed rows, and 404 classification.
- `KanbanViewModelTest`: empty/detail/stale/switch fencing states.
- `KanbanPluginJourneyTest`: Settings to snapshot to detail to Back, unavailable, and empty journeys.