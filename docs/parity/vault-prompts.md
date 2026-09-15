# Vault prompts: source and divergence ledger

The three Passwords & Logins requests that park a turn — `vault.code`,
`vault.save_login` and `vault.unlock_prompt` — ported per
[`docs/workflows/port-desktop-surface.md`](../workflows/port-desktop-surface.md)
for [#223](https://github.com/donovan-yohan/hermes-agent-android/issues/223):
`data/gateway/PendingInput.kt`, `data/gateway/GatewaySessionRepository.kt`,
`data/notifications/SessionNotifier.kt` and `ui/chat/PendingInputSurface.kt`.

The re-pin to `437116f9` moved this family onto a new wire, which
[#279](https://github.com/donovan-yohan/hermes-agent-android/issues/279)
shipped: every blocking prompt is a server→client JSON-RPC **request** frame
(`srq-<12 hex>`) answered by exactly one response frame carrying the same id,
the per-kind `*.expire` notification is gone, and an unanswered request is
re-delivered after a reconnect inside `open_requests`. The cards, the copy and
the secret handling are unchanged; the previous `vault.*.request` events no
longer exist, so before #279 a session could sit parked on a vault prompt with
nothing on screen and nothing in the shade until the backend's own 120 s or
180 s wait gave up for it.

## Pin

| Source | Pin | Read via |
|---|---|---|
| Desktop renderer, Gateway | `hermes-agent` @ `437116f9497c80d242ce034ff7f5d81dc277a337` | read-only checkout; every citation below was taken with `git show <sha>:<path>` |

Every `path:line` below is against that SHA.

## Paths that settled the port

| Question | Path |
|---|---|
| Every blocking prompt is one request frame, with `srq-` ids, one `request.cancel`, and `open_requests` re-delivery | `tui_gateway/server_requests.py:1-18` |
| The three vault request methods, their params, and the one-string `{value}` result every one of them answers with | `tui_gateway/contracts/server_requests.py:20-28`, `:118-142` |
| The withdrawing event and its payload | `tui_gateway/contracts/server_requests.py:217-224` |
| The three handlers: payload fields, the fallbacks, and that each parks the session | `apps/desktop/src/app/session/hooks/use-message-stream/gateway-event/server-requests.ts:227-262` |
| Which method → handler, and that an unhandled method is answered `-32601` by the channel | `.../gateway-event/server-requests.ts:371-397` |
| That `request.cancel` is the one event in the family, and how it clears a card | `.../gateway-event/input-requests.ts:24-132` |
| That the answer is routed back over the socket the request arrived on | `apps/desktop/src/store/server-requests.ts:1-45` |
| The three cards: fields, masking, buttons, and what each button sends | `apps/desktop/src/components/prompt-overlays.tsx:252-560` |
| That a saved login is one JSON string in `value` | `apps/desktop/src/components/prompt-overlays.tsx:378` |
| Every visible string | `apps/desktop/src/i18n/en.ts:4069-4100` |
| What blocks, with which timeout, and what the agent does with each answer | `tui_gateway/agent_callbacks.py:174-193` |
| The Passwords & Logins settings surface this port does **not** take | `apps/desktop/src/app/settings/vault-settings.tsx`, `apps/desktop/src/i18n/en.ts:443,513-570` |

## The contract, in one table

| | `vault.unlock_prompt` | `vault.save_login` | `vault.code` |
|---|---|---|---|
| Raised when | a login lives in an external manager that is locked | the agent is on a sign-in page with nothing saved for it | the site asked for a second factor and no authenticator key is saved |
| Params | `backend`, `display_name` | `origin`, `site` | `site`, `hint` |
| Answer | response frame `{value: password}` | response frame `{value: login}` (one JSON string) | response frame `{value: code}` |
| The quiet button sends | `""` — keep it locked | `""` — don't save | `""` — skip |
| Gateway wait | 120 s, then `request.cancel {reason:"timeout"}` | 180 s, same | 180 s, same |

`""` is an answer in all three, not a cancellation: the turn resumes without the
manager, without saving, or without the code. Nothing about the dialog is
optional — the agent is blocked behind it until something replies or the
backend's wait runs out and withdraws the request.

## What was built

| Piece | Android | Desktop counterpart |
|---|---|---|
| Three request types and three answer types | `data/gateway/PendingInput.kt` | `store/prompts.ts:126-150` |
| The request channel: parse, deliver, answer, withdraw, restore | `data/gateway/GatewayRpc.kt` | `store/server-requests.ts:1-45`, `gateway-event/server-requests.ts:227-262` |
| Parsing, parking, superseding, cancel, `open_requests` restore | `data/gateway/GatewaySessionRepository.kt` | `gateway-event/input-requests.ts:24-132`, `:227-262` |
| The three answers as one response frame each | `data/gateway/GatewaySessionRepository.kt` | `components/prompt-overlays.tsx:283`, `:378`, `:490` |
| The cards, and the secure window they render in | `ui/chat/PendingInputSurface.kt` | `components/prompt-overlays.tsx:252-560` |
| The `input` notification kind, with no vault text in it | `data/notifications/SessionNotifier.kt` | `gateway-event/server-requests.ts:227-262` |

## The secret path, unchanged

All three ride the path `SudoPending` and `SecretPending` already use, and none
of them got a shortcut:

- The typed characters leave the composable as a `CharArray` in one
  `PendingInputAction`, reach `respondToPendingInput`, and are `fill(0)`-ed in a
  `finally` whether the call succeeded, failed or threw.
- The card is inside a window carrying `FLAG_SECURE` for as long as it is
  composed, so it is absent from a screenshot and from the recents thumbnail.
  The field state is wiped **before** the flag clears, in one `DisposableEffect`
  rather than two whose disposal order would have to be reasoned about.
- Every masked field declares `KeyboardType.Password`. This has no Desktop
  analogue to port and is not cosmetic: an Android IME learns what is typed into
  an ordinary text field and offers it back in the next app.
- Nothing is persisted, logged, or put in a notification. A vault prompt's
  notification body is the session title, like every other kind's.
- The one-time code is treated as secret material even though Desktop shows it
  as typed: the masking is a shoulder-surfing decision, and `FLAG_SECURE`, the
  `CharArray` and the zeroing are not.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| `vaultUnlockDesc`: 41 words, and it says the master password "goes straight to {name} on this machine" (`en.ts:4070-4071`) | mobile-adaptation | 24 words, and it says the password "unlocks it for this session and is never stored or shown to the agent" | Two reasons, both real. Viewport: this is a dialog on a phone with a keyboard up, and `scripts/check-product-copy.py` caps a primary string at 36 words. Truthfulness: "this machine" is the Electron app's own host, and here the manager runs on the Gateway's host rather than on the phone, so the sentence would be false as written |
| `vaultSaveDesc`: the login "is encrypted on this machine" (`en.ts:4077-4078`) | mobile-adaptation | "your Gateway encrypts it and fills the page" | Same locative: the vault is the Gateway host's, and on a phone "this machine" reads as the phone. Everything else in the sentence is Desktop's, verbatim |
| `vaultSaveFootnote` and `vaultCodeFootnote` both point at Settings → Passwords & Logins (`en.ts:4082`, `:4089-4090`) | omission | Neither footnote is rendered | out-of-scope: #223 — #223 is the three prompts that park a turn; the settings surface both footnotes name is #238, and a footnote directing someone to a screen this app does not have is worse than no footnote |
| Settings → Passwords & Logins manages saved logins, cards, addresses and authenticator keys (`app/settings/vault-settings.tsx`) | omission | Absent | out-of-scope: #223 — filed as #238. A login saved from the phone can only be removed from Desktop or the CLI until it ships |
| Each card is its own dialog component, mounted per session by the chat and by every tile (`prompt-overlays.tsx:563-580`) | mobile-adaptation | One secure dialog renders whichever single kind the session on screen has parked; another session's prompt is the pinned "Waiting for your answer in …" banner plus an `input` notification | One conversation is on screen at a time. The banner and the shade are how this app has surfaced a background session's parked prompt since #99, and a vault prompt joins that rather than inventing a second route |
| Both inputs disable and the confirm becomes a spinner while the answer frame is in flight (`prompt-overlays.tsx:300-307`, `:391-398`, `:505-512`) | drift | The card has the state and the copy for it, but `ChatScreen` passes `isSubmitting = false`, so it never renders | #237. Pre-existing for sudo and secret, inherited here; wiring it touches `ChatViewModel` and `ChatScreen`, which #223 did not own |
| All three cards are titled with a `ShieldLock` glyph (`prompt-overlays.tsx:310`, `:408`, `:519`) | omission | The title is words only | deferred: #237 — a title glyph, not a control; this app's secure card has never had one and `HermesIcons` has no shield yet |
| The notification body is the prompt's own title: the site being signed into, or the manager being unlocked (`gateway-event/server-requests.ts:227-262`) | mobile-adaptation | The body is the session title, redacted and bounded, like every other kind | A phone renders that on a lock screen. #99's rule for this shade already forbids a command, tool output, a sudo prompt and a secret name; which site someone is signing into is the same class of thing. Also in `docs/parity/notifications.md` |
| `hint` is parsed off `vault.code` and stored (`gateway-event/server-requests.ts:227-236`) | omission | Parsed and stored, rendered nowhere | non-goal: Desktop does not render it either — `VaultCodeDialog` titles itself from `site` alone — so rendering it here would be a divergence rather than parity. It is carried so the contract is complete |
| A bridge method this platform has no surface for (`terminal.read`, `preview.read`, `preview.act`, `window.read`, `tour`) is answered `-32601` by the channel so the tool fails fast (`gateway-event/server-requests.ts:371-397`) | omission | Not answered at all: the request is ignored and the backend's own timeout ends it | out-of-scope: #279 — these are desktop-only surfaces (an in-app terminal buffer, a browser preview pane, a native window below the app) with no Android equivalent to answer from and no card the person could act on. Answered as the residual on #279 rather than invented here; the timeout is the same behaviour this app had before the re-pin |

## Visual report

- pending: #223

No side-by-side was rendered. Desktop's three cards need a browser session that
has actually reached a locked manager, a sign-in page and a second-factor
challenge; the Android halves need the same three events driven through a live
Gateway on the emulator lane. `VaultPromptDialogTest` pins the copy, both
answers per card and the characters that leave, off-device and under
Robolectric — none of which is a picture of either.

## Executable evidence

| Claim | Test |
|---|---|
| Each request parks its session as its own kind, with the params parsed, and a request method is never mistaken for another kind | `VaultPromptTest` |
| Desktop's two fallbacks: `site` defaults to `origin`, `display_name` defaults to `backend` | `VaultPromptTest` |
| A request for a session this app has never bound is not shown, and neither is one with nothing to ask | `VaultPromptTest`, `PendingInputTest` |
| Each answer is one response frame carrying the request's own id, with the value the method reads — the password, the login JSON, the code | `VaultPromptTest` |
| A saved login travels as one JSON string, and declining sends `""` rather than an empty object | `VaultPromptTest` |
| Every `CharArray` handed to the repository is zeroed after the call, including on the empty answers | `VaultPromptTest`, `VaultPromptDialogTest` |
| A send that never left the leg keeps the prompt answerable rather than reporting it answered | `VaultPromptTest` |
| A `request.cancel` clears its own request, settles the session, and cannot erase the prompt that superseded it | `VaultPromptTest` |
| Nothing is answered after a cancel, and a cancelled request's stale tap sends no frame | `VaultPromptTest` |
| Cancelling one request leaves another parked on the same session, and the session stays `NeedsInput` | `VaultPromptTest` |
| A vault prompt dies with its turn, like every other parked request | `VaultPromptTest` |
| Every blocking prompt arrives as a request frame on its own stream, is answered by one frame carrying the same id, and the deleted `*.request` event pair stays refused | `GatewayRpcTest` |
| `open_requests` restores a live, answerable card on resume, over a snapshot that said the session was running | `PendingInputTest` |
| All three raise the `input` kind, and the shade is told a conversation is waiting and never which site or manager | `VaultPromptNotificationTest` |
| A vault prompt for the conversation on screen stays silent | `VaultPromptNotificationTest` |
| Each card's title, its two buttons and its verbatim Desktop labels | `VaultPromptDialogTest` |
| The confirm cannot fire on an empty field, and save-login needs both halves | `VaultPromptDialogTest` |
| A texted code keeps its digits and loses its spaces | `VaultPromptDialogTest` |
| The field is empty again the moment an answer leaves | `VaultPromptDialogTest` |
| None of the three draws anything in the transcript | `VaultPromptDialogTest` |

Not proved off-device, and deliberately not claimed: that `FLAG_SECURE` really
keeps these cards out of a screenshot and the recents thumbnail, and that a real
backend's vault prompt can be answered from the phone. The first needs a device;
the second needs a Gateway whose browser tooling has reached a real sign-in page.
