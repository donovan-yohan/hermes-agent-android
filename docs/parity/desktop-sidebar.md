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

### Compact auxiliary disclosure (native Mac slice)

The compact ChatScreen drawer keeps New session visible and puts Capabilities,
Messaging, Artifacts, Scheduled jobs, Group Chats and Kanban behind one
saveable More/Less disclosure, initially collapsed. Tabs, the project header
and add/filter actions, and the profile/Gateway footer remain outside that
group. Expansion preserves existing contribution order, callbacks, disabled
state and WIP markers. Its bounded independent scroller leaves the project
list the weighted remainder only when the measured visible chrome leaves at
least two touch targets (96dp). Otherwise the existing whole-pane scroll
fallback gives the project/session list a bounded 180dp slot and makes the
footer reachable by scrolling. The persistent wide rail retains its prior
all-actions-open behavior.

This scope was inspected in a disposable local export at
`36922ad064d65dcf25f8f48df81e1ccf9a55de67`:
`apps/desktop/src/app/chat/sidebar/index.tsx:207-247` retains the core order and
Codicon mapping. More/Less is an explicitly requested mobile-only disclosure,
not a renamed Desktop control. Existing historical pins and evidence are not
restamped. `MobileSidebarCollapseTest` mounts the real production drawer and
checks default state, operation/order, disabled rows, plugin callbacks, project
scroll, 400dp overview/selected search at font scales 1.0 and 2.0 with the real
profile rail and ConnectionSwitcherBar, the retained 280dp callback journey,
drawer reopen and saveable restoration. Visible top/footer chrome is measured,
including search, project back, scope notes and supplied footer content; the
auxiliary scroller retains a one-touch-target floor. Native Robolectric screenshots are synthetic JVM window draws,
not emulator or physical-device acceptance; exact-head paired review remains
pending: #72 (the existing exact-head acceptance matrix, not tool-row issue #71).
This marker does not certify a new paired capture or complete that issue.
Execution details and local image paths are recorded in
`/Users/donovanyohan/.hermes/profiles/ika-frontend/reports/native-drawer-budget-fix.md`.

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

### Project-row creation correction

PR #334 restored flat sidebar/chat header actions, not inline project-row
creation. On main `bb1693abc2b6ce924b2f6ebfd8bfcb24ee3e6d36`, `ProjectRow` had
no create callback or button and the selected-project header excluded the add
control. The new public Compose project-row and entered-project regressions
both fail against that production snapshot. "Already shipped" was therefore
incorrect for this requested behavior.

Each overview project row now exposes a separately accessible, 48dp
`New session in <project>` plus; disconnected rows keep it visible but disabled.
The row selects its catalog project before calling the existing session-create
flow. That flow snapshots profile, project/workspace, endpoint and navigation
ownership before launching its coroutine; the repository retains transport and
profile ownership across its navigation-mutex wait. A later selection may leave
the create cached in its owning profile but cannot adopt its result or composer.
The entered project header also retains a scoped plus, including HOME.
This is a mobile-adaptation for a direct touch action rather than relying on
hover or the global header. `SessionCreateAffordancesTest` verifies action
ordering through public `SessionList`; native synthetic captures document the
visible HOME and example-project controls without using a real profile.


Session-create controls also follow the source at
`95f20517c25ee418da5337f4ead347008baaa2b3`, as cited in `ChatScreen.kt`
and `SessionList.kt`. The flat sessions header offers its own `New session`
control; the project overview retains `New project`. Compact and wide chat
headers expose the pane's create action without a tab strip. All use existing
callbacks and connection gates. `SessionCreateAffordancesTest` covers those
controls; `ChatViewModelTest` covers draft retention and background-turn isolation.
These newly integrated journeys await the parent build; they are not a rendered
parity claim. Exact-head acceptance remains pending #72.

The existing mounted Compose bounds journey covers contribution placement and
cramped scrolling. The sidebar mode implementation is stateful at the mounted
`SessionList` surface, preserves the shell/list layout on return, and consumes
real Bot roster state rather than a placeholder row.

## Visual report

The cache-only review retains **Concern**, not a blank parity PASS. The durable
[actual observation report](../media/sidebar-projection-pr360/REPORT.md) and
[immutable artifact index](../media/sidebar-projection-pr360/retained-index.json)
retain Android `7e7af545571c504274e88fd6b56a0882e28c08df` and Desktop
`36922ad064d65dcf25f8f48df81e1ccf9a55de67`; receipts and pixels are not restamped.
Independent source attribution found the project-level `design` search behavior
unchanged from uncached `b49627e274afb13337c84ba3746cf1ef108d2fc4`:
**PASS for no demonstrated cache-introduced search regression**, not search
parity. Search behavior is issue-owned by #367; remaining lane/pinning and
expanded profile/archive/rehome rendered journeys remain pending: #359.

Reviewer approval is limited to compact drawer/48dp touch density, two-line
previews and the accessible icon-only All projects action: the actual overview
and selected captures preserve project/session order and 12m/20m ages while
making phone actions reachable. This does not approve loss of lane information,
pinning controls or mismatched selected-chat context. Only sidebar content/order
is compared; Android's selected design chat and Desktop's fresh chat region are
excluded. JVM reuse proof is not draft pixels. The corrected single-token miss
and real Desktop composer/draft capture are separately recorded when observed;
previous multiword miss pixels remain failed-pair evidence. No CPU/frame-time
speed claim, complete five-state acceptance or issue completion is made.

A debug-only `SidebarProjectionParityActivity` mounts the actual production
`ChatViewModel.uiState` and `ChatScreen` over transient synthetic authorities.
The [missing-state completion report](../media/sidebar-projection-pr360/COMPLETION.md)
records the corrected single-token empty-result pair and actual Desktop 20-edit
composer journey, alongside installed Android 20-emission identity reuse and
focused drawer pixels. Both new Android states retain capture source
`7bfb2fac3a57d9ad559299f1a890927084117078`; earlier overview/selected/search-match
observations retain their original `7e7af545` source and original Desktop fixture.
Five states have actual paired observations, **not all-features parity PASS**.
The historical multiword-query failed pair and prior focus-rejected draft
artifacts remain distinct; new success does not diagnose their native owner.
No masking, focus bypass, timeout extension or all-states rerun was used.
Named/unified profile, archive and compression-rehome rendered journeys remain
pending: #359. Expanded tests prove only their stated adjacent output contracts.


[Project-row/push-off native regression packet](visual/project-push-off-native/README.md)
contains synthetic production-Compose window draws and the main-vs-fixed test
results. It supplements, and does not discharge, the paired/device obligation.


- pending: #72

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
| Always-visible core rows at `sidebar/index.tsx:207-247` @ `36922ad064d65dcf25f8f48df81e1ccf9a55de67` | mobile-adaptation | Compact drawer keeps New session outside More/Less; measured chrome reserves a usable navigation area or selects whole-pane scrolling, with original order, WIP and callbacks | Real-drawer `MobileSidebarCollapseTest` verifies 400dp overview/selected search at 1.0/2.0 font scale, real profile/Gateway footer, 280dp callbacks, reopen and restoration; exact-head acceptance pending #72. No new paired-review approval. |
| Project/session search results and empty copy | drift | Matching project retains both previews; `Nothing matches` differs from Desktop query-specific Results copy | Inherited before the cache; actual match/multiword-miss pixels in [report](../media/sidebar-projection-pr360/REPORT.md); dedicated behavior owner #367, Concern. |
| Selected project branch/lane label | omission | Flat session list lacks Desktop `main` lane information | deferred: #359; actual selected pair in [report](../media/sidebar-projection-pr360/REPORT.md). Not approved as touch-density adaptation. |
| PINNED section and pin affordance | omission | Absent from this project capture | pill-owed: #359; actual overview pair in [report](../media/sidebar-projection-pr360/REPORT.md); not an all-elements pass. |
| Project sidebar density and presentation | mobile-adaptation | Compact drawer, 48dp rows and two-line previews | Reviewer approved from actual overview/selected pixels in [report](../media/sidebar-projection-pr360/REPORT.md); same order and ages, readable/reachable phone targets. |
| All projects text action | mobile-adaptation | Accessible icon-only back action | Reviewer approved from actual selected pair and Activity semantics test; conserves drawer header width while retaining the All projects action/name. |
| Terminal mode | drift | Disabled with WIP until a mobile route exists | `SessionSidebarNavigationBoundsTest` verifies the disabled control; exact-head acceptance pending #72. |
| Compact navigation rows | mobile-adaptation | 48dp touch floor | `SessionSidebarNavigationBoundsTest` checks cramped layout and action reachability. |
| Pane-strip new-session action | mobile-adaptation | Chat-header plus labelled `New session`, since Android has no session tabs; flat sidebar header has its own plus | `SessionCreateAffordancesTest` covers callback, disabled state, compact/wide and project-mode exclusivity; exact-head acceptance pending #72. |
| Overview project-row new-session action | mobile-adaptation | 48dp `New session in <project>` plus; visible but disabled when disconnected | `SessionCreateAffordancesTest` verifies action ordering through public `SessionList`; [native synthetic captures](visual/project-push-off-native/README.md) document the visible HOME and example-project controls; exact-head acceptance pending #72. |
| Bots mode | mobile-adaptation | Existing Android roster in the sidebar pane | `SessionSidebarNavigationBoundsTest` verifies mode selection and return; exact-head acceptance pending #72. |
| Active plugin navigation | drift | Sidebar-origin routes retain the wide rail and compact drawer door; rows use semantic active-row fill/accent and suppress the retained chat selection. The compact drawer action shares the route's own header rather than adding a second row. | `SessionSidebarNavigationBoundsTest` covers row selection; `PluginShellRouteJourneyTest` covers header alignment, reopening the drawer and selecting the same active route. Updated device pixels, wide-route geometry and exact-head acceptance pending #72. |