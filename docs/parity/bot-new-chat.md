# New chat with this bot

## Pin

New action audit: `81a55437f3f31a9f12a30c53b6c5d49d420898aa`.
Existing canonical Bot Chat source/captures in `bot-chat.md` retain their pins.

## Contract

Desktop `apps/desktop/src/plugins/hermes-bots/bot-row.tsx:440-449` dispatches
`newBotChat`; `data.ts:1297-1325` creates an ordinary side conversation in the
selected bot's connection/profile workspace, not its canonical Bot Chat.
Android keeps the verb in the same menu slot and routes both standalone and
embedded roster mounts through `PluginNavigation`, `ChatActions`, MainActivity
and `ChatViewModel.newBotSession` to `createSessionAtEndpoint`. The existing
repository captures profile/endpoint before waiting and owns actual Gateway
creation. No prompt is sent and no canonical Bot Chat registry is changed.

A roster callback captures its exact accepted snapshot, profile and endpoint.
Repeated taps stay disabled until completion; stale roster or endpoint callbacks
do not retarget navigation. An ordinary new conversation inherits the chosen
profile default rather than another profile's composer model or project cwd.
Unit tests exercise explicit owner creation, held completion, profile navigation,
endpoint revocation and unknown owners. Compose drives the actual menu action.

## Navigation and private queue ownership

An asynchronous completion captures the shell navigation epoch, including
leave-and-return ABA; it cannot drag a newer Settings/plugin destination back
to Chat. The ordinary session's queue binds to its explicit bot owner while
the browsing rail remains unchanged. Opening suppresses automatic queue drain
through hydration, scope binding and reconnect until an explicit send/resume.
These boundaries have focused VM/controller and real-shell Compose regressions;
the test command and exact-candidate outcome remain separate execution evidence.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Side conversation as a workspace tab | mobile-adaptation | Opens the new conversation in the chat destination | A phone has one transcript pane; the canonical row tap still opens Bot Chat and this action never replaces its identity. |
| Pointer context menu | mobile-adaptation | Existing 48dp overflow action | Touch has no right-click; action order and label remain unchanged. |

## Visual report

- pending: #216

Source and Compose behavior are covered. No fresh rendered side-by-side is
claimed; visual review remains Concern and physical acceptance is outstanding.
