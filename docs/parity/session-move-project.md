# Move session to project

## Pin

Behavior inspected at `81a55437f3f31a9f12a30c53b6c5d49d420898aa`.
Historical menu captures retain their own pin in
[session-actions-menu.md](session-actions-menu.md).

## Source contract

- `apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:213-256` @ `81a55437f3f31a9f12a30c53b6c5d49d420898aa`: refresh project targets on open, exclude Home and current owner, dispatch selected identity.
- `apps/desktop/src/store/projects.ts:120-125,791-822` @ `81a55437f3f31a9f12a30c53b6c5d49d420898aa`: primary path then first repo root; session.workspace.move uses durable session_key, cwd and owner profile; metadata replaces old workspace and project tree refreshes.
- `tui_gateway/methods_session.py:1101-1140` @ `81a55437f3f31a9f12a30c53b6c5d49d420898aa`: stored workspace move and live-agent propagation.
- `tests/tui_gateway/test_session_agent_cwd.py:127-165` @ `81a55437f3f31a9f12a30c53b6c5d49d420898aa`: workspace mutation reaches subsequent agent threads.
- `gateway/session_mutation_policy.py:37-55` and `hermes_state_mutation_prepared.py:67-79` @ `81a55437f3f31a9f12a30c53b6c5d49d420898aa`: canonical workspace preparation, frozen terminal cwd, transactional metadata replacement and receipt.
- `apps/desktop/src/store/projects.ts:201-220` and `apps/desktop/src/lib/path-compare.ts:12-26` @ `81a55437f3f31a9f12a30c53b6c5d49d420898aa`: longest project/repo/worktree ownership match; separator and Windows-only case normalization are comparison keys, never mutation payload rewriting.
- `apps/desktop/src/i18n/en_projects.ts:32-37` @ `81a55437f3f31a9f12a30c53b6c5d49d420898aa`: Move to project, No other projects and Could not move session. Android adds a reopen/check recovery sentence because an interrupted document/dialog flow must not imply a server rollback.

Android refreshes the actual row owner's roster, not an unrelated active-profile
catalog. The picker renders redacted project labels, never asks for an Android
folder, and keeps the selected target's exact backend identity/path. Selection
revalidates that target before mutation. The shared runtime resolver and canonical
mutation adapter preserve durable identity and enforce backend revision/busy policy.
The confirmed response replaces workspace/branch metadata; backend project refresh
owns membership. Unknown/folderless/stale targets never produce a speculative write.
After mutation, owner-scoped tree and project-details reads replace every previously
hydrated membership plus the target membership, even when profile navigation changes
while the move is held. The cache publishes to that owner's snapshot without switching
or refreshing the active rail. Source removal is asserted from backend details, never
inferred from the returned cwd. Catalog summaries stay in their owner-scoped project
snapshot and cannot replace a foreign foreground raw-ID row or its transcript. A
confirmed move releases the navigation lock before best-effort catalog repair; a
failed repair never reports the accepted write as failed. The ViewModel retains the
operation if its preview row disappears. The entire picker scrolls in short windows,
and Bot Chat blocks only its own row rather than unrelated ordinary sessions.
Android does not yet model git_repo_root; cwd/branch
receipt assertions do not certify git-root readback or actual-router acceptance.
Errors stay generic and actionable; an uncertain move asks the reader to reopen the
session rather than claiming rollback.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Pointer submenu | mobile-adaptation | Flat modal project picker with loading, empty, retry, failure and cancel states | Nested pointer menus are brittle on touch; established menu order/glyph/label remain unchanged |
| Current-project exclusion | mobile-adaptation | Same longest matching project/repo/worktree root, including Windows spelling comparisons | Backend paths are compared only for picker filtering; membership still comes from the refreshed backend tree |
| Current live move/picker states | drift | Rendered side-by-side packet pending | #72 exact-head surface acceptance; Concern, not approval |

## Visual report

- pending: #72

Evidence owner: [#72](https://github.com/donovan-yohan/hermes-agent-android/issues/72).
Feature scope: [#73](https://github.com/donovan-yohan/hermes-agent-android/issues/73).
No emulator or physical-device operations were performed in this lane. Parent QA
owes the new picker states in phone light/dark and post-move session continuation.

## Regression evidence

Repository tests exercise owner-scoped roster, durable mutation, confirmed metadata
and stale-target refusal. Compose menu regression exercises fresh picker load,
selected target dispatch and dismissal. No live backend mutation is claimed by mocks.
`CanonicalGatewayRpcTest` drives repository through the correlated canonical adapter
and asserts exact raw owner, workspace operation, cwd payload and revision fence.
`SessionProjectTargetsTest` covers longest root, linked worktree, prefix boundaries,
Windows separators/case and POSIX case sensitivity.
The held-mutation regression seeds distinct owner catalogs and hydrated source membership,
changes profile navigation, then asserts owner-only reads, source removal and target
membership without unrelated catalog publication.
`CachedProjectPreviewJourneyTest` opens the real project-preview row menu over a
colliding foreign-profile raw-id slot while overview revalidation is held. It verifies
owner-scoped target loading without rewriting shared rows/transcripts, explicit-owner
resume and mutation, and rejection of the picker-returned target after an endpoint
switch. The picker retains its source owner and endpoint, not whichever profile the
rail or shared cache names at selection time. This wiring-only repair changes no copy,
menu order or presentation; the existing `pending: #72` visual gate remains unchanged.
