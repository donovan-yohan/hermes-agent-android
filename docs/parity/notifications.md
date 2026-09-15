# OS notifications: source and deviation ledger

Connected-only OS notifications for approvals, questions and idle turns —
slices S-N1, S-N3 and S-N4 of
[#99](https://github.com/donovan-yohan/hermes-agent-android/issues/99), ported
per [`docs/workflows/port-desktop-surface.md`](../workflows/port-desktop-surface.md).

The passive half of the same surface — one silent ongoing activity group for the
active connection, with one child per live chat, beside the actionable alerts —
is [#274](https://github.com/donovan-yohan/hermes-agent-android/issues/274). Its
sources, rules and honesty limits are in
[the activity group](#the-passive-activity-group-274) below.

## Pin

| Source | Pin | Read via |
|---|---|---|
| Desktop renderer, Gateway | `hermes-agent` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3` | read-only checkout; every citation below was taken with `git show <sha>:<path>` |
| The Gateway's live-session registry, and Desktop's own live-status poll | `hermes-agent` @ `437116f9497c80d242ce034ff7f5d81dc277a337` | read-only checkout; the citations marked *(registry)* below were taken at that SHA |

Every `path:line` below is against the SHA its row names.

## Paths that settled the port

| Question | Path |
|---|---|
| The kinds, and their registry order | `apps/desktop/src/store/native-notifications.ts:15-26` |
| Which kinds break through a focused window | `:29` |
| Preference shape and defaults (all on) | `:31-49` |
| The four dispatch guards, and their order | `:190-223` |
| Self-evicting 1 s throttle | `:97-114` |
| "Backgrounded" | `:116-129` |
| Foreground / active-session gating | `:131-148` |
| Approve / Reject from a notification button | `:348-367` |
| 4 s post-connect quiet window, and why | `apps/desktop/src/store/notify-baseline.ts:1-26` |
| Native titles, bodies and action labels | `apps/desktop/src/i18n/en.ts:175-187` |
| Per-kind labels and descriptions | `apps/desktop/src/i18n/en.ts:431-474` |
| `turnDone` dispatch site | `apps/desktop/src/app/session/hooks/use-message-stream/index.ts:772` |
| `input` dispatch sites (clarify, batch clarify, sudo, secret) | `.../use-message-stream/gateway-event/input-requests.ts:101-106`, `:149-154`, `:282-287`, `:313-318` |
| `input` dispatch sites for the three vault prompts, read at `564aef2946c436500a5e80ee117b66b789b3f99a` | `.../gateway-event/input-requests.ts:384-389`, `:410-415`, `:436-441` |
| `approval` dispatch site, with its two buttons | `.../gateway-event/input-requests.ts:256-265` |
| `turnError` dispatch site | `.../gateway-event/status.ts:140-145` |
| The choices the Gateway actually offers | `gateway/platforms/api_server.py:74-77` |
| `approval.respond` answering `{resolved: N}` | `tui_gateway/methods_prompt.py:1513-1534` |
| The `approval.received` ack that precedes it | `tui_gateway/methods_prompt.py:1494-1510` |
| The Gateway's live-session registry the group projects *(registry)* | `tui_gateway/methods_session.py:926-937` — "Live TUI sessions in this process (not a DB browser)" |
| The three live states a registry row can carry *(registry)* | `tui_gateway/server.py:2653-2660` — `waiting`, `starting`, `working`, and `idle` for everything else |
| The row shape: runtime id, durable `session_key`, title, preview, status *(registry)* | `tui_gateway/server.py:2670-2691` |
| The typed contract, and the method's own registry entry *(registry)* | `tui_gateway/contracts/sessions.py:226-249` |
| When the Gateway says the session rows moved *(registry)* | `tui_gateway/change_watcher.py:180` (0.5 s probe) and `:189` (2 s broadcast floor) |
| Desktop's own poll of the same snapshot, and its two cadences *(registry)* | `apps/desktop/src/app/contrib/hooks/use-background-sync.ts:702`, `:303`, `:307`, `:723` |
| Desktop treating the snapshot as authoritative about absence *(registry)* | `apps/desktop/src/app/contrib/hooks/use-background-sync.ts:371-380` |

## What was built

| Piece | Android | Desktop counterpart |
|---|---|---|
| Kinds and attention set | `data/notifications/NotificationKind.kt` | `native-notifications.ts:15-29` |
| Preferences (master + per kind) | `data/notifications/NotificationPreferences.kt` | `native-notifications.ts:31-93` |
| Gating, throttle, quiet window | `data/notifications/SessionNotifier.kt` | `native-notifications.ts:97-223`, `notify-baseline.ts` |
| Copy | `data/notifications/NotificationCopy.kt` | `i18n/en.ts:174-186,430-473` |
| Channels, builders, intents | `data/notifications/AndroidNotificationSurface.kt` | Electron `Notification` bridge |
| Shade answers: approval choices and a clarify | `data/notifications/NotificationActionReceiver.kt`, `data/gateway/ApprovalChoices.kt`, `data/gateway/ClarifyShade.kt` | `native-notifications.ts:348-367`, and the renderer's own approval/clarify cards |
| Settings screen | `ui/settings/NotificationsScreen.kt`, `ui/settings/NotificationsCopy.kt` | `app/settings/notifications-settings.tsx`, `i18n/en.ts:588-628` |
| Status-bar mark | `scripts/build-notification-icon.py`, `res/drawable-*/ic_stat_hermes.png` | none — Electron files the app's own colour icon |
| Where the user is | `data/notifications/NotificationPresence.kt` | `document.hidden`/`hasFocus`, `$activeSessionId` |
| Runtime permission | `data/notifications/NotificationPermissionGate.kt`, `ui/common/NotificationPermissionPrompt.kt` | none — Electron needs no grant |
| The passive activity group: one child per live chat | `data/notifications/GatewayActivity.kt`, `data/notifications/SessionNotifier.kt` (`applyActivity`), `data/notifications/AndroidNotificationSurface.kt` (`postActivity`) | the sidebar's live rows, and nothing in the OS — Electron files per-event notifications only |
| The live set itself | `data/gateway/LiveSessions.kt`, `GatewaySessionRepository.liveSessions()` over `session.active_list` | `session.active_list` through `use-background-sync.ts:702` |
| The group's summary and the socket that keeps it true | `data/gateway/TurnForegroundService.kt`, `data/gateway/TurnProtectionController.kt` | none — Electron is a window, not a foreground service |

The notifier follows the session repository, not a transport, so Remote,
Managed SSH and Local behave identically: all three deliver the same events
over the same socket, and `SessionNotifier` has never heard of any of them.

## Gating, verbatim

`SessionNotifier.shouldFire` is adapted from `native-notifications.ts:131-148` with
two substitutions:

- `isBackgrounded()` becomes "no resumed Activity", read from
  `ProcessLifecycleOwner` — process lifecycle, so a rotation is not read as
  leaving the app.
- `$activeSessionId` becomes the conversation the chat surface has open,
  published by `MainActivity` from `ChatUiState.activeSession`.

The consequence: an approval or a question for an **off-screen** session fires
even while the app is in the foreground, and a finished turn fires whenever the
app is **away** for any session. When foregrounded, completion notifications
remain suppressed (in-app unread dot isolates the foreground).

The four guards run in Desktop's order — preferences, quiet window,
foreground/active-session, throttle — because the order is observable: a
throttle entry recorded before the gating check would suppress the *next*,
legitimate notification.

## The passive activity group (#274)

One ongoing, silent group per active connection. Its summary is the
foreground-service notification (`TurnForegroundService`), and its children are
posted and reconciled by `AndroidNotificationSurface.postActivity` — one child
per live chat, each opening that conversation when tapped. The actionable kinds
above are untouched by it: they keep their own channels, their own per-session
group and their own gating, and nothing about the group can suppress or absorb
them.

**Where the live set comes from.** `session.active_list` — the Gateway's own
in-memory registry, not a database browser *(registry)*:
`tui_gateway/methods_session.py:926-937`. A row is live work when its status is
`working`, `starting` or `waiting` (`tui_gateway/server.py:2653-2660`); `idle`
rows are dropped, and a row without a durable `session_key` is dropped rather
than guessed at. The projection unions three authoritative sources and nothing
else:

1. the registry rows, which include a chat Desktop, the TUI or another client
   started on the same Gateway process;
2. this app's own parked prompts — a question waiting for an answer is live work
   even when the registry has moved on;
3. this app's own turns on the wire, which is why a turn submitted a moment ago
   is not briefly invisible before the registry catches up.

One child per durable id, `waiting` over `working` for the same id, ordered by
last activity then id.

**Absence is authoritative.** When the registry stops listing a session, its
child goes. The app never infers liveness from a cached row alone — a cache row
carries whichever status the Gateway last said, and the registry is the contract
that says what is live *now*. Desktop reaps on the same reading
(`use-background-sync.ts:371-380`) *(registry)*.

**What the group cannot show, and says so.** `session.active_list` enumerates
the live sessions of the *one* gateway process this app is connected to. Two
kinds of work therefore never appear as passive children, and neither is
inferred:

- a run owned by another process — a cron job, an inbound messaging-gateway
  turn — is not in that registry at all;
- work on a *different* Gateway (another profile leg served by another process,
  or another saved connection) is out of scope while the app has one active
  connection.

This is #274's own non-goal, not an omission that could be fixed by polling
harder, and the honest consequence is stated in `status/ROADMAP.md` rather than
papered over.

**How it stays true.** The snapshot is refetched on every connection edge, on
every `sessions.changed` broadcast (probed at 0.5 s and floored to one every 2 s
server-side: `change_watcher.py:180`, `:189`) *(registry)*, and on a 30 s
backstop for the degraded-socket edge a broadcast cannot cover. One refresh is
in flight at a time, and a burst of hints re-runs once on the trailing edge.
Desktop polls the same snapshot at 1.5 s only while its window is visible
(`use-background-sync.ts:303`, `:723`) *(registry)* — a cadence that exists for a
window someone is looking at, and which a phone with a foreground service does
not need.

**Privacy.** A child carries the redacted session title, the project label only
when the authoritative catalog knows one, and the same display-safe preview text
the sidebar uses (through `redact()`, bounded to 400 characters, and gated by the
existing preview preference). Never a command, tool output, approval text, sudo
or secret name, hostname or path. Every child is `VISIBILITY_PRIVATE` with a
public version that says only `Hermes activity`, so a locked screen learns that
chats are live and nothing about which.

**Cancellation.** A connection edge — a dropped socket, an endpoint switch, a
sign-out — publishes "the Gateway did not answer", which withdraws every child
and stops the summary with the service. Opening a conversation does **not**
withdraw its child: the chat is still live, and the group is a view of the
connection rather than an inbox.

## Divergences

Classified for `scripts/check-parity-evidence.py`. Two entries that were in this
table are not divergences at all and have moved below it, rather than being
given a class they do not deserve.

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| One preference per kind, and the OS layer has no notion of a channel | mobile-adaptation | Two channels, `Approvals` and `Responses` | Android importance is a property of a channel and can never be lowered after the OS creates it, so per-kind channels would freeze seven importances on first launch. The names are the issue's own event matrix; the descriptions are Desktop's per-kind sentences (`en.ts:437`, `:441`, `:445`) |
| No grouping layer: Electron files each notification on its own | mobile-adaptation | A conversation's group summary rides the channel of the *first* notification filed under it | Android needs a summary before it will bundle a group, and a summary has to sit on some channel. `GROUP_ALERT_CHILDREN` keeps the summary silent whichever channel it lands on, so the channel decides nothing the user can hear. Pinning it to `Approvals`, as the first version did, was not harmless: it gave a finished turn an approval's importance |
| An approval's body is `command \|\| description` (`gateway-event/input-requests.ts:261`) | mobile-adaptation | The body is the **session title**, never the command — and an approval carries no preview line at any setting | A phone renders that on a lock screen. #99's security section forbids commands, tool output, sudo prompts and secret names in a notification; the only Gateway text that reaches the shade is a session title, through `redact()` and bounded |
| A vault prompt's body is its own title: `Verification code for <site>`, `Save your <site> login?`, `Unlock <manager>` (`input-requests.ts:385`, `:411`, `:437` @ `564aef2946c436500a5e80ee117b66b789b3f99a`) | mobile-adaptation | The body is the **session title**, never the site or the password manager | The row above, for the same surface and the same reason: which site someone is signing into, and which manager holds their passwords, is the same class of thing as a command. `VaultPromptNotificationTest` names `1Password` explicitly so an edit that "improves" the body fails there rather than on a lock screen. Ledgered in full in `docs/parity/vault-prompts.md` |
| No lock screen exists | mobile-adaptation | `VISIBILITY_PRIVATE` with a `publicVersion` carrying only the kind | A locked phone is told "Approval needed" and nothing about which conversation |
| Clicking the notification body focuses the window; there is no button vocabulary for it | mobile-adaptation | No explicit "Open" action button | Tapping the notification body *is* Open on Android, so a button duplicating the tap target is noise. The exception is the row below, where the buttons are gone and the body says so |
| The renderer is always there to answer, so an action button always works | mobile-adaptation | "Open to respond." when the connection has moved on, raised on `PendingInputResponse.Retryable` | This app's socket may not be there. A button that silently does nothing is worse than a sentence |
| Electron needs no notification grant | mobile-adaptation | A `POST_NOTIFICATIONS` runtime prompt with its own rationale, asked at the first live Gateway, once | No Desktop equivalent to port. The rationale reuses the settings panel's vocabulary (`en.ts:431`) rather than inventing a second description |
| A clarify is answered in the renderer | mobile-adaptation | One question with up to three choices becomes one action each; one with none becomes a `RemoteInput` reply; a batch, a multi-select, or more choices than three is not answerable from the shade at all | Owner decision 3 on #99 refused this outright because "a clarify can be a batch of questions with constrained choices and a single free-text box cannot answer that honestly". That argument is about batches, and `shadeQuestion` now keeps exactly it: answering the first of a batch leaves the turn parked, one action cannot accumulate a multi-select, and Android drops actions past three — a truncated list of constrained choices is a lie about what the options were |
| The 1 s throttle drops a superseding approval, at the cost of a stale body (`:97-114`) | mobile-adaptation | Same identity with a changed target is exempt from the throttle | Here the notification carries *buttons* bound to a request id, so a throttled supersession would leave the shade able to answer a request the Gateway has already replaced. The exemption is the narrowest that fixes it |
| Dispatch is per event, so a prompt dropped in the quiet window is simply never offered again (`notify-baseline.ts:1-26`) | mobile-adaptation | Prompts replayed into the 4 s quiet window are deferred until expiry rather than swallowed | On mobile, reconnects wipe repository state and redeliver pending prompts. Deferring unannounced prompts until the quiet window closes prevents permanent swallow while deduplication (including across incremental single-event replays of multiple outstanding prompts) prevents reconnect storms |
| A finished turn only alerts if its session is `$activeSessionId` (`:146-147`) | drift | A finished turn notifies for any session when backgrounded; foreground remains isolated | On Android, leaving the app from the session list or non-chat surface leaves `visibleSessionId` null, so completion notifications alert for any background session. Because the throttle key is `kind:session` and grouping is per conversation, N background conversations finishing within the window produce N alerting summaries; #99 |
| A parked approval keeps its notification across a reconnect | drift | Notifications for an already-notified prompt vanish on disconnect and deduplication prevents re-posting on reconnect | The repository clears its pending map on every client change and the notifier follows it, clearing shade notifications. For prompts already announced pre-disconnect, deduplication refuses re-posting on reconnect replay, so the shade stays clear until in-app interaction or new activity occurs; #99 |
| Shade buttons are `Approve` and `Reject` (`native-notifications.ts:349`) | mobile-adaptation | Supported choices from the Gateway offer, capped at Android's three: run once, the strongest grant on offer, then the refusal — with `setAuthenticationRequired` on a persistent grant | The earlier note said `session` and `always` stay in the app because "a persistent grant should not be one mis-tap from a lock screen". The objection is real and has an Android answer: the OS refuses to fire the action until the device is unlocked (API 31+; below it the grant is simply not offered). Unknown choices stay in the app: the shade receiver deliberately whitelists the known wire vocabulary, so rendering an action it cannot send would lie. Desktop's own approval words are reused rather than its notification pair, because beside `Always allow` the word `Approve` no longer says which of the two it is (`en.ts:3749,3752,3759,3754`) |
| No settings panel divergence — Desktop lists every kind in one undivided list (`notifications-settings.tsx`) | mobile-adaptation | Two sections, a permission row that appears only when the OS grant is gone, and a preview toggle | A preference screen that let somebody turn six things on while Android drops all of them would be lying by omission, and Desktop has no grant to lose. The permission row offers Android's own settings page rather than re-requesting, because Android stops showing the dialog after two denials |
| The completion body is empty; the title carries the news (`en.ts:181`) | mobile-adaptation | A preview line, on by default: the question, or the line a turn ended on, in `BigTextStyle` | A phone notification saying only `Input needed` makes somebody open the app to learn whether it was worth opening the app for; Desktop's is beside the window that already answers that. The preference is the first gate and not the only one — an approval, a sudo or secret prompt and the two state kinds carry no preview at any setting, and `publicVersion` is unchanged, so a locked phone is still told only the kind |
| — | mobile-adaptation | `connectionLost`: the Gateway went away with a turn running or a prompt parked | Desktop's renderer is either running or quit. This app's socket can drop on its own mid-turn, which silently ends the turn and stops the shade's own approval buttons from being answerable, and nothing else is in a position to say so while the app is backgrounded. Only a drop *from* connected, and only for conversations that had something in flight |
| — | mobile-adaptation | `stillWaiting`: one reminder, five minutes after an announced prompt is still unanswered | A notification can be swiped into a shade and forgotten while an agent stays blocked behind it; a renderer on a screen someone is sitting at cannot be. Android has one reminder identity per session, so simultaneous prompts deterministically bind it to one live request; resolving that request clears or repoints the reminder to another due prompt. It uses the `Approvals` channel because a calmer channel would make the reminder quieter than the prompt it recalls. |
| `backgroundDone`, `credits` and `plugin` kinds | omission | In the preference store, never dispatched | non-goal: none has a mobile source at all — no backgrounded terminal, no credit ledger, no desktop plugins. They are carried so S-N2's settings screen is a pure UI slice and the disabled rows have something to bind to |
| Completion-sound picker (`notifications-settings.tsx:65-108`) | omission | Absent | out-of-scope: #99 named it a non-goal of that issue, being Electron-only |
| Live state lives per runtime in the renderer; the OS only ever gets per-event notifications | mobile-adaptation | One ongoing silent group for the connection: a summary that *is* the foreground-service notification, plus a silent child per live chat | A phone has no window to keep live state in, and the shade is the only surface that exists while the app is away; Android also requires the service holding the socket open to be foreground and to carry a notification, so the group's summary is that notification rather than a second one beside it |
| The same snapshot polled at 1.5 s while the window is visible, 30 s as a backstop (`use-background-sync.ts:303`, `:307`, `:723`) | mobile-adaptation | Same RPC, pulled on every connection edge, on every `sessions.changed` broadcast and on a 30 s backstop — never on a visible-window cadence | Battery is the phone's constraint and a visible window is the desktop's: the broadcast already fires on every `state.db` write, and the foreground service is what keeps the socket able to receive it |
| The snapshot is authoritative about absence, and a runtime that vanishes is reaped (`:371-380`) | mobile-adaptation | The same reading, with one carve-out: a turn this client has on the wire keeps its child until the turn ends | The app never sees the `running: false` edge for a session it is not streaming, so absence is the only honest signal for everything else; the carve-out covers the gap between a submit and the registry entry, which Desktop covers with its own `awaitingResponse` rule |
| Electron files one notification per event and has no group summary | mobile-adaptation | A third channel, `hermes.activity` (`IMPORTANCE_LOW`), carries the summary and every child; the retired `Active turn` channel is never posted to again | Android importance belongs to a channel and can never be lowered once the OS has created it, so a silent group needs a channel of its own to stay silent and separately silenceable. An upgraded install keeps the retired channel as an empty OS row — deleting it while a notification may still be posted on it is the one thing that is not safe |
| The sidebar paints the project and the last line beside a row, with a window's width | mobile-adaptation | A child spends its two lines on the project label, then the preview or a truthful status line | A shade has two lines and no scroll, so the facts are ordered by how much they say; a title alone would make every live chat look alike |
| One preference per kind, and no notion of an ongoing group | mobile-adaptation | The group answers to the existing master switch and preview preference; it has no row of its own | A group is not an event kind. A per-kind row would promise a control over "every live chat" that Android does not offer, and the master switch is the honest switch for "may this app use the shade at all" |

### Verbatim from Desktop, and deliberately so

Neither of these is a divergence; both were worth writing down, so they are
here rather than in the table under a class they would not earn.

- **An interrupted turn still raises "Hermes finished".** Desktop dispatches
  `turnDone` from the completion handler regardless of the interrupt flag
  (`index.ts:772`; the error path is a separate `failAssistantMessage`).
  Stopping a turn requires the app in the foreground, so the gate almost always
  suppresses it anyway. Kept verbatim rather than "improved" into a silent
  divergence.

## `resolved == 0` is success — but a map miss is not

`approval.respond` answers `{"resolved": N}` and carries no `status` field
(`methods_prompt.py:1513-1534`), and `respondToPendingInput` reads an absent
status as resolved. So an approval the Gateway reports as `resolved: 0` —
answered somewhere else — is withdrawn from the shade without a word. That is
the intended reading of #99's "success-and-cancel-the-notification".

What that reading must **not** be extended to is a request missing from the
repository's pending map, and the first version of this port made exactly that
mistake. `respondToPendingInput` began with
`mutablePendingInputs.value[key] ?: return Resolved`, which reported success
for a request it had never sent. The generation fence on the next line could
never catch it, because `PendingInputKey` carries its own generation and the
map is emptied on every connection change: a key from a dead connection is
guaranteed to miss the map and return on the line above. On the primary T1
path — process dies, notification survives, user presses Approve — the fresh
repository reported success, the notification vanished, and the agent stayed
blocked behind an approval nobody had answered.

The map miss is now classified against a per-connection ledger of keys this
repository actually **retired**: answered, expired, superseded, or died with
their turn. Membership means finished business (`Resolved`); absence means the
request may still be parked (`Unanswerable`, routed to "Open to respond",
never to a silent withdrawal). The ledger is cleared with the pending map on
every connection change, and it is bounded — evicting oldest-first degrades an
ancient key to "cannot answer", which is the safe direction.

A generation comparison alone would not have been enough, which is the part
worth remembering. `connectionGeneration` is a per-process counter that
restarts at zero, so the number baked into a notification by a process that has
since died is a number a fresh process reaches again within milliseconds — and
on that fresh process the map is empty because no session has been opened yet,
not because anything was answered. `ShadeApprovalTest` pins both the mismatched
and the colliding case.

The shade answers through the **same** `respondToPendingInput` as the in-app
bar, so there is one writer, one session token, one `connectionGeneration`
fence and one `respondingKeys` in-flight guard. Every field the action intent
carries is fixed when the notification is built, and the `PendingIntent` is
`FLAG_IMMUTABLE`, so nothing that can reach it can redirect which request is
answered or with what choice.

## One repository seam was added

`GatewaySessionRepository.turnOutcomes` — a dropping `SharedFlow` of
`GatewayTurnOutcome(durableSessionId, failed)`, emitted from the two terminal
frames. It exists because `message.complete` and a terminal `error` both settle
the session to `SessionStatus.Idle`, so the cache alone cannot tell an
app-scoped follower which happened, and Desktop raises a different kind for
each (`index.ts:772` against `gateway-event/status.ts:140-145`). It is a signal, not
state: nothing renders from it and nothing persists it. It also makes S-N5's
`turnError` row a wiring change rather than a repository change.

## Evidence

| Claim | Where it is proved |
|---|---|
| Gating, throttle, quiet window, grouping, resolve-clears, supersession (including of a replayed prompt), re-raising a prompt the user viewed and left | `app/src/test/kotlin/.../notifications/SessionNotifierTest.kt` (34 tests, virtual time) |
| The shade-response outcomes, including a notification outliving its process and a colliding generation, against the live repository | `app/src/test/kotlin/.../notifications/ShadeApprovalTest.kt` (7 tests) |
| "Retired" against "never seen" at the repository | `app/src/test/kotlin/.../gateway/PendingInputTest.kt` (11 tests) |
| Desktop's kinds, order and defaults; redaction of a session title | `app/src/test/kotlin/.../notifications/NotificationSettingsTest.kt` |
| The three vault prompts raise `input` rather than an eighth kind, and carry no vault text into the shade | `app/src/test/kotlin/.../notifications/VaultPromptNotificationTest.kt` (3 tests) |
| When the permission is asked for | `app/src/test/kotlin/.../notifications/NotificationPermissionGateTest.kt` |
| Channels, extras, public version, action intents, API-specific persistent-grant handling, group summary channel and alert behaviour, denied path | `app/src/testDebug/kotlin/.../notifications/AndroidNotificationSurfaceTest.kt` (Robolectric) |
| The live-session parse: the three live states, `idle` and unknown statuses dropped, a row without a durable key dropped, both timestamp units, a malformed list refused | `app/src/test/kotlin/.../gateway/LiveSessionsTest.kt` |
| The projection: the registry ∪ parked prompts ∪ this client's own turns, one child per id, waiting over working, deterministic order, project labels only from the catalog | `app/src/test/kotlin/.../notifications/GatewayActivityTest.kt` |
| The follower: a fetch per connection edge, no fetch while disconnected, hint coalescing, the backstop interval, and a disconnect clearing the snapshot | `app/src/test/kotlin/.../notifications/GatewayActivityTest.kt` (virtual time) |
| The group's notification rules: children posted and reconciled, the preview toggle, the master switch, an unchanged projection not reposting, and the alert kinds untouched by any of it | `app/src/test/kotlin/.../notifications/GatewayActivityNotificationTest.kt` |
| The children's records: one tag per chat on the activity channel, silent and low, private with a chat-free public version, no actions, a child that left withdrawn, `clearSession` not touching them | `app/src/testDebug/kotlin/.../notifications/AndroidNotificationSurfaceTest.kt` (Robolectric) |
| The summary: the activity channel, the group-summary and ongoing flags, the counts it renders, and a live update | `app/src/testDebug/kotlin/.../gateway/TurnForegroundServiceTest.kt` (Robolectric) |
| Protection held for a chat the Gateway reports and this client never submitted | `app/src/test/kotlin/.../gateway/TurnProtectionControllerTest.kt` |

Not proved off-device, and deliberately not claimed: that a real approval can
be answered from a real shade. That is #99's acceptance gate and it needs the
server-mac emulator lane driving real events through the Termux Local route.

## Visual report

- pending: #99 — the settings screen, and the shade at each kind: an approval
  with three choices, a question with its own choices, a question with a reply
  box, a preview on and off, and the status-bar mark at real density
- pending: #274 — the activity group's own shade: the collapsed bundle, the
  group expanded with one child per live chat, a child whose chat is parked on a
  question, and the actionable alerts beside it. Owed because this change was
  built and verified without an attached device or emulator; the renderer that
  can produce it is `scripts/capture-android-visual-parity.sh` on
  `.github/workflows/visual-parity-capture.yml`, which needs a
  `notification-shade` surface and a debug-only way to post the fixtures

The shade is not a Desktop surface, so half of this comparison is a phone
screenshot beside Desktop's settings panel and nothing else. The owner has
recorded that notifications are a mobile-native surface which does not
translate one-to-one, and the rows above are classified on that basis rather
than chased toward a Desktop that has no lock screen, no permission and no
channels.

- pending: #99

A notification is drawn by the OS shade, not by this app, so the side-by-side
that matters is an Android shade against an Electron notification — and it needs
the real events #99's acceptance gate already calls for: the server-mac emulator
lane driving approvals through the Termux Local route. `AndroidNotificationSurfaceTest`
pins the channels, extras, public version, action intents and group alert
behaviour off-device; none of that is a picture of the shade.
