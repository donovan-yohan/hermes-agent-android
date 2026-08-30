# OS notifications: source and deviation ledger

Connected-only OS notifications for approvals, questions and idle turns —
slices S-N1, S-N3 and S-N4 of
[#99](https://github.com/donovan-yohan/hermes-agent-android/issues/99), ported
per [`docs/workflows/port-desktop-surface.md`](../workflows/port-desktop-surface.md).

## Pin

| Source | Pin | Read via |
|---|---|---|
| Desktop renderer, Gateway | `hermes-agent` @ `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732` | read-only checkout; every citation below was taken with `git show <sha>:<path>` |

Every `path:line` below is against that SHA.

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
| Native titles, bodies and action labels | `apps/desktop/src/i18n/en.ts:174-186` |
| Per-kind labels and descriptions | `apps/desktop/src/i18n/en.ts:430-473` |
| `turnDone` dispatch site | `apps/desktop/src/app/session/hooks/use-message-stream/index.ts:772` |
| `input` dispatch sites (clarify, batch clarify, sudo, secret) | `.../use-message-stream/gateway-event.ts:1228`, `:1279`, `:1366`, `:1393` |
| `approval` dispatch site, with its two buttons | `.../gateway-event.ts:1344-1354` |
| `turnError` dispatch site | `.../gateway-event.ts:1661` |
| The choices the Gateway actually offers | `gateway/platforms/api_server.py:74-77` |
| `approval.respond` answering `{resolved: N}` | `tui_gateway/methods_prompt.py:1496-1517` |
| The `approval.received` ack that precedes it | `tui_gateway/methods_prompt.py:1477-1493` |

## What was built

| Piece | Android | Desktop counterpart |
|---|---|---|
| Kinds and attention set | `data/notifications/NotificationKind.kt` | `native-notifications.ts:15-29` |
| Preferences (master + per kind) | `data/notifications/NotificationPreferences.kt` | `native-notifications.ts:31-93` |
| Gating, throttle, quiet window | `data/notifications/SessionNotifier.kt` | `native-notifications.ts:97-223`, `notify-baseline.ts` |
| Copy | `data/notifications/NotificationCopy.kt` | `i18n/en.ts:174-186,430-473` |
| Channels, builders, intents | `data/notifications/AndroidNotificationSurface.kt` | Electron `Notification` bridge |
| Shade Approve / Reject | `data/notifications/NotificationActionReceiver.kt` | `native-notifications.ts:348-367` |
| Where the user is | `data/notifications/NotificationPresence.kt` | `document.hidden`/`hasFocus`, `$activeSessionId` |
| Runtime permission | `data/notifications/NotificationPermissionGate.kt`, `ui/common/NotificationPermissionPrompt.kt` | none — Electron needs no grant |

The notifier follows the session repository, not a transport, so Remote,
Managed SSH and Local behave identically: all three deliver the same events
over the same socket, and `SessionNotifier` has never heard of any of them.

## Gating, verbatim

`SessionNotifier.shouldFire` is `native-notifications.ts:131-148` with two
substitutions:

- `isBackgrounded()` becomes "no resumed Activity", read from
  `ProcessLifecycleOwner` — process lifecycle, so a rotation is not read as
  leaving the app.
- `$activeSessionId` becomes the conversation the chat surface has open,
  published by `MainActivity` from `ChatUiState.activeSession`.

The consequence is Desktop's, unchanged: an approval or a question for an
**off-screen** session fires even while the app is in the foreground, and a
finished turn fires only when the app is **away** *and* the session is the
selected one. The second half is the rule that stops a busy Gateway raising one
alert per background conversation (`:146-147`).

The four guards run in Desktop's order — preferences, quiet window,
foreground/active-session, throttle — because the order is observable: a
throttle entry recorded before the gating check would suppress the *next*,
legitimate notification.

## Divergences

| # | What | Class | Why |
|---|---|---|---|
| 1a | A conversation's group summary rides the channel of the *first* notification filed under it | adaptation | Android needs a summary before it will bundle a group, and a summary has to sit on some channel. Harmless because `GROUP_ALERT_CHILDREN` keeps the summary silent whichever channel it lands on — only the children ever alert — so the channel decides nothing the user can hear. Pinning it to `Approvals` instead, as the first version did, was not harmless: it gave a finished turn an approval's importance. |
| 1 | Two channels (`Approvals`, `Responses`) rather than one preference per kind at the OS layer | adaptation | Android importance is a property of a channel, and a channel's importance can never be lowered after the OS creates it. Per-kind channels would freeze seven importances on first launch. The names are the issue's own event matrix; the descriptions are Desktop's per-kind sentences (`en.ts:437`, `:441`, `:445`). |
| 2 | An approval's body is the **session title**, never the command | adaptation | Desktop puts `command \|\| description` in the body (`gateway-event.ts:1346`). A phone renders that on a lock screen. Issue #99's security section forbids commands, tool output, sudo prompts and secret names in a notification, and this honours that: the only Gateway text that reaches the shade is a session title, through `redact()` and bounded. |
| 3 | `VISIBILITY_PRIVATE` with a `publicVersion` carrying only the kind | adaptation | Desktop has no lock screen. A locked phone is told "Approval needed" and nothing about which conversation. |
| 4 | Shade buttons are **Approve** and **Reject**, sending `once` and `deny` | none | Desktop's own labels (`en.ts:176-177`) and Desktop's own mapping (`native-notifications.ts:349`). `session` and `always` are offered by the Gateway (`api_server.py:77`) and stay in the app: a persistent grant should not be one mis-tap from a lock screen. |
| 5 | No explicit "Open" action button | adaptation | Tapping the notification body *is* Open on Android. A button that duplicates the tap target is noise. The exception is divergence 6, where the buttons are gone and the body says so. |
| 6 | "Open to respond." when the connection has moved on | adaptation | Desktop's renderer is always there to answer; this app's socket may not be. A button that silently does nothing is worse than a sentence. Raised on `PendingInputResponse.Retryable`. |
| 7 | A runtime permission prompt with its own rationale | adaptation | `POST_NOTIFICATIONS` has no Desktop equivalent. The rationale reuses the settings panel's vocabulary (`en.ts:431`) rather than inventing a second description. Asked at the first live Gateway, once. |
| 8 | The status-bar glyph is `android.R.drawable.stat_notify_chat` | omission → coming soon | A drawn Hermes notification mark is design work. The launcher icon is a colour bitmap and would render as a white block. Matches the precedent already in `WakeWordForegroundService`. |
| 9 | `turnError`, `backgroundDone`, `credits` and `plugin` are in the preference store but never dispatched | omission → coming soon | `turnError` is S-N5 with the connection-lost row. The other three have no mobile source at all: no backgrounded terminal, no credit ledger, no desktop plugins. They are carried so that S-N2's settings screen is a pure UI slice, and so the parity mandate's "visible but disabled" rows have something to bind to. |
| 10 | No `RemoteInput` reply on a question | omission → coming soon | Owner decision 3 on #99. A clarify can be a batch of questions with constrained choices (`PendingInput.kt:24-30`); a single free-text box cannot answer that honestly. |
| 11 | No completion-sound picker | omission | Explicitly a non-goal on #99: Electron-only (`notifications-settings.tsx:65-108`). |
| 12 | An interrupted turn still raises "Hermes finished" | none, deliberate | Desktop dispatches `turnDone` from the completion handler regardless of the interrupt flag (`index.ts:772`; the error path is a separate `failAssistantMessage`). Stopping a turn requires the app in the foreground, so the gate almost always suppresses it anyway. Kept verbatim rather than "improved" into a silent divergence. |
| 13 | A superseding approval bypasses the 1 s throttle | adaptation | Desktop's throttle would drop it, and on Desktop that costs a stale body. Here the notification carries *buttons* bound to a request id, so a throttled supersession leaves the shade able to answer a request the Gateway has already replaced. The exemption is the narrowest that fixes it: same identity, changed target only. |
| 14 | A prompt replayed into the quiet window is remembered as old news | adaptation | Desktop dispatches per event, so a dropped replay is simply never offered again. This follows a state map, where the same prompt is re-offered on every subsequent change, so the verdict is recorded explicitly and cleared on the next socket open. |
| 15 | Notifications for a session vanish when the socket does | drift, stated | The repository clears its pending map on every client change, and the notifier follows it. On a reconnect the 4 s quiet window then suppresses the replay, so a still-parked approval has no notification until something new happens. Desktop keeps its in-app prompt through the same window; this app does too — only the OS notification goes. This is the honest shape of T1 and is stated in `status/ROADMAP.md`. |

## `resolved == 0` is success — but a map miss is not

`approval.respond` answers `{"resolved": N}` and carries no `status` field
(`methods_prompt.py:1496-1517`), and `respondToPendingInput` reads an absent
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
each (`index.ts:772` against `gateway-event.ts:1661`). It is a signal, not
state: nothing renders from it and nothing persists it. It also makes S-N5's
`turnError` row a wiring change rather than a repository change.

## Evidence

| Claim | Where it is proved |
|---|---|
| Gating, throttle, quiet window, grouping, resolve-clears, supersession (including of a replayed prompt), re-raising a prompt the user viewed and left | `app/src/test/kotlin/.../notifications/SessionNotifierTest.kt` (23 tests, virtual time) |
| The shade-response outcomes, including a notification outliving its process and a colliding generation, against the live repository | `app/src/test/kotlin/.../notifications/ShadeApprovalTest.kt` (7 tests) |
| "Retired" against "never seen" at the repository | `app/src/test/kotlin/.../gateway/PendingInputTest.kt` (11 tests) |
| Desktop's kinds, order and defaults; redaction of a session title | `app/src/test/kotlin/.../notifications/NotificationSettingsTest.kt` |
| When the permission is asked for | `app/src/test/kotlin/.../notifications/NotificationPermissionGateTest.kt` |
| Channels, extras, public version, action intents, group summary channel and alert behaviour, denied path | `app/src/testDebug/kotlin/.../notifications/AndroidNotificationSurfaceTest.kt` (15 tests, Robolectric) |

Not proved off-device, and deliberately not claimed: that a real approval can
be answered from a real shade. That is #99's acceptance gate and it needs the
server-mac emulator lane driving real events through the Termux Local route.
