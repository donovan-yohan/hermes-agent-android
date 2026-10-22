# Open recent session

## Pin

Action audited at `81a55437f3f31a9f12a30c53b6c5d49d420898aa`.
Historical canonical Bot Chat evidence retains its original pin.

## Contract and execution path

Desktop `apps/desktop/src/plugins/hermes-bots/bot-row.tsx:450-455` disables the
menu action without a listed session. `recent-session.ts:24-78` selects
`last_session.id`, never canonical Chat, the worker heartbeat or `resolved_id`,
and resumes with the explicit bot profile and connection route. Its
`recent-session.test.ts:45-79` proves this distinction.

Android enables the existing menu slot only for an actual listed ID. Both
standalone and embedded roster mounts wire `BotsViewModel.openRecent` through
PluginNavigation, ChatActions and MainActivity into
`ChatViewModel.openBotRecentSession`, then `openSessionAtEndpoint`. The existing
repository preserves owner/endpoint provenance at dispatch and hydrates before
foreground adoption. Opening sends nothing, creates nothing and does not grant
the canonical Bot Chat mutation capability. Failure keeps the roster and safe
retry copy. Duplicate taps, stale roster snapshots, late endpoint callbacks and
newer profile navigation are covered by unit tests; Compose drives the menu and
its no-listed-session disabled state.

## Navigation and private queue ownership

An asynchronous completion captures the shell navigation epoch, including
leave-and-return ABA; it cannot drag a newer Settings/plugin destination back
to Chat. The ordinary session's queue binds to its explicit bot owner while
the browsing rail remains unchanged. Opening suppresses automatic queue drain
through hydration, scope binding and reconnect until an explicit send/resume.
Recent compressed IDs use the existing draft/history/queue migration path after
binding the owner queue namespace. Shell liveness is checked before foreground
adoption and after migration awaits; every ordinary session entry restores the
queue owner from the backend row, without changing the browsing rail.
These boundaries have focused VM/controller and real-shell Compose regressions;
the test command and exact-candidate outcome remain separate execution evidence.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Workspace tab beside canonical Bot Chat | mobile-adaptation | Single chat destination after successful hydration | A phone has one foreground transcript pane. The canonical row tap and identity remain unchanged. |
| Pointer context menu | mobile-adaptation | Existing 48dp overflow menu | Touch has no right-click; action wording and relative order are unchanged. |

## Visual report

- pending: #216

No new rendered side-by-side or physical-device acceptance is claimed.
Visual verdict remains Concern until the existing owner supplies evidence.
