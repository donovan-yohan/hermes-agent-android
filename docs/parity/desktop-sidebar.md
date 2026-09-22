# Desktop sidebar parity

## Scope

This page covers the mobile session sidebar's Desktop-derived chrome at upstream
reference `e2f8a0731bf26e95b31e35d73e71e183a1045b81` (read-only export), plus the
captured shell reference. The frozen shell shows the mode strip above the
sidebar slot and the flat core rows in this order: `New session`, `Capabilities`,
`Messaging`, `Artifacts`, `Scheduled jobs`.

## Mobile adaptation

The two supported tabs (`SESSIONS`, `BOTS`) are rendered above the rows in both
the compact drawer and wide rail. The selected tab has accent text and a thin
accent underline. `BOTS` switches to the real `BotsRosterScreen` supplied by a
typed sidebar contribution; its Back action returns to `SESSIONS`. Group Chats
remains a separate functional plugin launcher and keeps the `Group Chats` label.

Rows use the shared Codicon-backed `HermesIconGlyph` primitive and a 48dp
minimum touch floor. Core icon/order mapping is `Robot`, `SymbolMisc`, `Comment`, `Files`,
`Watch`, matching the frozen contribution data. Capabilities, Messaging,
Artifacts, and Scheduled jobs remain disabled and show the shared `WIP` chip.
`TERMINAL` remains visible as a disabled tab with a WIP chip; it has no Android
route in this slice. The tab strip remains visible while the Bots roster is open.

## Regression coverage

The existing mounted Compose bounds journey covers contribution placement and
cramped scrolling. The sidebar mode implementation is stateful at the mounted
`SessionList` surface, preserves the shell/list layout on return, and consumes
real Bot roster state rather than a placeholder row.

## Visual report

- pending: #71

A genuine Desktop shell reference has been captured from the disposable export
at the stated pin. The full shell includes the mode strip; the sidebar crop
begins at its slot. An aligned Android comparison and portable evidence packet
are still pending; this page does not claim rendered parity.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Terminal mode | drift | Disabled with WIP until a mobile route exists | `SessionSidebarNavigationBoundsTest` verifies the disabled control; visual comparison pending #71. |
| Compact navigation rows | mobile-adaptation | 48dp touch floor | `SessionSidebarNavigationBoundsTest` checks cramped layout and action reachability. |
| Bots mode | mobile-adaptation | Existing Android roster in the sidebar pane | `SessionSidebarNavigationBoundsTest` verifies mode selection and return; rendered roster comparison pending #71. |