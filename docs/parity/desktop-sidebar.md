# Desktop sidebar parity

## Scope

This page covers the mobile session sidebar's Desktop-derived chrome at upstream
reference `e2f8a0731bf26e95b31e35d73e71e183a1045b81` (read-only export), plus the
captured shell reference. The frozen shell shows the mode strip above the
sidebar slot and the flat core rows in this order: `New session`, `Capabilities`,
`Messaging`, `Artifacts`, `Scheduled jobs`.

Source at `e2f8a0731bf26e95b31e35d73e71e183a1045b81`:
`apps/desktop/src/app/chat/sidebar/index.tsx:201-237` defines the core row
order and glyphs; `apps/desktop/src/components/pane-shell/tree/renderer/tree-group.tsx:560-609`
mounts the enclosing pane-tab strip and derives its active tab.

## Mobile adaptation

The two supported tabs (`SESSIONS`, `BOTS`) are rendered above the rows in both
the compact drawer and wide rail. The selected tab has accent text and a thin
accent underline. `BOTS` switches to the real `BotsRosterScreen` supplied by a
typed sidebar contribution without a nested page header; the `SESSIONS` tab returns to the session list. Group Chats
remains a separate functional plugin launcher and keeps the `Group Chats` label.

Rows use the shared Codicon-backed `HermesIconGlyph` primitive and a 48dp
minimum touch floor. Core icon/order mapping is `Robot`, `SymbolMisc`, `Comment`, `Files`,
`Watch`, matching the frozen contribution data. Capabilities, Messaging,
Artifacts, and Scheduled jobs remain disabled and show the shared `WIP` chip.
`TERMINAL` remains visible as a disabled tab with a WIP chip; it has no Android
route in this slice. The tab strip and profile/Gateway footer remain visible while the Bots roster is open.

## Regression coverage

The existing mounted Compose bounds journey covers contribution placement and
cramped scrolling. The sidebar mode implementation is stateful at the mounted
`SessionList` surface, preserves the shell/list layout on return, and consumes
real Bot roster state rather than a placeholder row.

## Visual report

- pending: #71

A genuine mono Desktop shell reference has been captured from the disposable
export at the stated pin in dark and light mode, with explicit renderer theme
assertions. The full shell includes the mode strip; the sidebar crop begins at
its slot. Android dark navigation and Group Chats selected-state captures at
`f39f0f95dbb12c4a2c8a532ac0c7dba645883c6f` passed receipt validation and were
visually compared against the Desktop core rows:
[navigation capture](https://github.com/donovan-yohan/hermes-agent-android/actions/runs/35765069376),
[selected capture](https://github.com/donovan-yohan/hermes-agent-android/actions/runs/35765533593).
The comparison verifies core order/glyph family, flat rows, top tabs, and
exclusive Android selection paint. It does not establish matching configured
Desktop plugin states, Bots roster parity, footer behavior, or wide geometry.
The clean Desktop sandbox lacks optional plugin/Terminal states. Full rendered
parity remains pending; later changes need new exact-head evidence.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Terminal mode | drift | Disabled with WIP until a mobile route exists | `SessionSidebarNavigationBoundsTest` verifies the disabled control; visual comparison pending #71. |
| Compact navigation rows | mobile-adaptation | 48dp touch floor | `SessionSidebarNavigationBoundsTest` checks cramped layout and action reachability. |
| Bots mode | mobile-adaptation | Existing Android roster in the sidebar pane | `SessionSidebarNavigationBoundsTest` verifies mode selection and return; rendered roster comparison pending #71. |
| Active plugin navigation | drift | Sidebar-origin routes retain the wide rail and compact drawer door; rows use semantic active-row fill/accent and suppress the retained chat selection. The compact drawer action shares the route's own header rather than adding a second row. | `SessionSidebarNavigationBoundsTest` covers row selection; `PluginShellRouteJourneyTest` covers header alignment, reopening the drawer and selecting the same active route. Updated device pixels, wide-route geometry and rendered comparison pending #71. |