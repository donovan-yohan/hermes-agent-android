# Upstream main sweep: 2026-09-22

## Frozen target

- Mobile baseline: `ca9d255b04053c22c1e1046f27372e9496956e91`.
- Upstream target: `NousResearch/hermes-agent` at `e2f8a0731bf26e95b31e35d73e71e183a1045b81`, resolved from upstream main at intake.
- Previous theme/reference pin: `437116f9497c80d242ce034ff7f5d81dc277a337`.
- This is an in-progress audit, not a complete parity certification. Historical per-surface citations and captured reports retain their original SHA.

## Priority delivery slices

| Slice | Observed gap | Acceptance |
|---|---|---|
| Backend custom skins | Mobile's `data/themes/GatewayTheme.kt` parses Dashboard palette definitions. Desktop also registers resolved backend skins and caches them for startup in `apps/desktop/src/themes/backend-sync.ts:28-44,69-110` at the target. | Valid skin renders, survives restart, respects manual selection and cannot leak across endpoint/profile changes; existing Dashboard support remains intact. |
| Sidebar navigation | Mobile sidebar contributions use `SettingsRow`, including descriptions, instead of the Desktop navigation row treatment. | Sidebar-top SESSIONS/BOTS/TERMINAL tabs; flat icon-and-label rows; correct active treatment, order, Back behavior and compact/wide layout. |
| Missing navigation doors | Desktop core entries are New session, Capabilities, Messaging, Artifacts and Scheduled jobs in `apps/desktop/src/app/chat/sidebar/index.tsx:201-237` at the target; plugin entries follow. | Preserve existing functional destinations; expose unported controls honestly as disabled/WIP; do not conflate Messaging with hosted Group Chats. |
| Built-in theme repin | Registry and shared palette sources have no changes between the previous theme pin and the target. | Update offline ledger provenance and pass live identity parity, theme unit tests and citation checks. |

## Additional upstream fixes to evaluate

These are audit candidates, not claims that all are reproduced Android defects:

- Stale search results: upstream `aca1843823` clears old server hits while a new query is pending. Reproduced on mobile: changing a nonempty query retained `old-hit` during the next debounce. The regression failed before the fix and passes after invalidating remote results on every distinct complete search key. Local matches remain immediate; request cancellation and debounce are unchanged.
- Hidden canonical Bot Chats: upstream `e46b5650fd` closes a sidebar visibility regression caused by resolved/live rows re-entering the cache after list filtering. Mobile asks `session.list` for `include_hidden=false`, but `SessionSummary` has no hidden flag and `buildSessionRows` filters archive state, not hidden state. List exclusion alone is therefore insufficient evidence; follow up with a canonical-chat resolve/live-cache regression before choosing a fix. Do not remove the canonical chat from the authoritative cache simply to hide its sidebar row.
- Background session rotation: upstream `ced5f36b2a`, `32bb86ff72` and `6d7a8005d0` guard foreground ownership. Exercise mobile rotation/compaction against a different foreground session.
- Completion and resume reconciliation: upstream `01d934f1d6`, `c3f5c37ecb`, `bf4f92b688`, `e4b8c30f87` and `a835405065` address transcript occurrences, ownership and sealed-reply settlement. Compare behavior, not React implementation details.
- Interrupted tool rows and manual compression progress: upstream `ee9203889a` and `a59d15a0c8` improve visible status fidelity.
- Connectors and plugin settings: upstream `dc50403a81`, `70f5dc5f46` and `5c0e73eff1` expand Desktop capabilities. Inventory native applicability and backend availability before scheduling a port.

## Evidence so far

- Live theme parity at the target: all 11 built-in presets match in order and identity.
- Worktree citation validation after the initial target/ledger update: passed.
- Focused gate: 187 tests passed with no skips, failures or errors (166 ChatViewModel, 13 theme parity, 8 color math), verified from fresh XML reports. Includes the stale-search regression.
- Custom-skin and sidebar implementations: separate isolated worktrees, not integrated or accepted yet.
- Rendered comparison, exact-head review, full gates, merge and device QA: pending.
