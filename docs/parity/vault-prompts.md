# Vault prompts: source and divergence ledger

The three Passwords & Logins requests that park a turn — `vault.code.request`,
`vault.save_login.request` and `vault.unlock.request` — ported per
[`docs/workflows/port-desktop-surface.md`](../workflows/port-desktop-surface.md)
for [#223](https://github.com/donovan-yohan/hermes-agent-android/issues/223):
`data/gateway/PendingInput.kt`, `data/gateway/GatewaySessionRepository.kt`,
`data/notifications/SessionNotifier.kt` and `ui/chat/PendingInputSurface.kt`.

Before this, `PendingInputKind` was `{Clarify, Approval, Sudo, Secret}` and all
three events were received and dropped: on a current backend a session could sit
parked on a vault prompt with nothing on screen and nothing in the shade, until
the Gateway's own 120 s or 180 s timeout gave up for it.

## Pin

| Source | Pin | Read via |
|---|---|---|
| Desktop renderer, Gateway | `hermes-agent` @ `564aef2946c436500a5e80ee117b66b789b3f99a` | read-only checkout; every citation below was taken with `git show <sha>:<path>` |

Every `path:line` below is against that SHA.

## Paths that settled the port

| Question | Path |
|---|---|
| The three handlers: payload fields, the fallbacks, and that each parks the session | `apps/desktop/src/app/session/hooks/use-message-stream/gateway-event/input-requests.ts:370-446` |
| The three `.expire` handlers, and that expiry is request-correlated | `.../gateway-event/input-requests.ts:173-204` |
| That all three raise the `input` notification kind, and what Desktop puts in the body | `.../gateway-event/input-requests.ts:384-389`, `:410-415`, `:436-441` |
| The wire names the events are dispatched under | `apps/desktop/src/lib/gateway-events.ts:45-50` |
| The three cards: fields, masking, buttons, and what each button sends | `apps/desktop/src/components/prompt-overlays.tsx:256-577` |
| That a saved login is one JSON string in `login` | `apps/desktop/src/components/prompt-overlays.tsx:435` |
| That a typed code is stripped of spaces and dashes first | `apps/desktop/src/components/prompt-overlays.tsx:535` |
| Every visible string | `apps/desktop/src/i18n/en.ts:3898-3922` |
| Which parameter each `*.respond` reads, and that all three tolerate a late answer | `tui_gateway/methods_prompt.py:1096-1103` |
| `_respond` answering `{status: ok}` or `{status: expired}` | `tui_gateway/server.py:3035-3054` |
| What blocks, with which timeout, and what the agent does with each answer | `tui_gateway/agent_callbacks.py:174-193` |
| That `.expire` is emitted when the bounded wait runs out, carrying `request_id` | `tui_gateway/server.py:1249-1254,1282-1294` |
| The Passwords & Logins settings surface this port does **not** take | `apps/desktop/src/app/settings/vault-settings.tsx`, `apps/desktop/src/i18n/en.ts:443,513-570` |

## The contract, in one table

| | `vault.unlock` | `vault.save_login` | `vault.code` |
|---|---|---|---|
| Raised when | a login lives in an external manager that is locked | the agent is on a sign-in page with nothing saved for it | the site asked for a second factor and no authenticator key is saved |
| Payload | `backend`, `display_name` | `origin`, `site` | `site`, `hint` |
| Answer | `vault.unlock.respond {request_id, password}` | `vault.save_login.respond {request_id, login}` | `vault.code.respond {request_id, code}` |
| The quiet button sends | `""` — keep it locked | `""` — don't save | `""` — skip |
| Gateway wait | 120 s | 180 s | 180 s |

`""` is an answer in all three, not a cancellation: the turn resumes without the
manager, without saving, or without the code. Nothing about the dialog is
optional — the agent is blocked behind it until something replies or the
Gateway's wait runs out and emits `.expire`.

## What was built

| Piece | Android | Desktop counterpart |
|---|---|---|
| Three request types and three answer types | `data/gateway/PendingInput.kt` | `store/prompts.ts:109-140` |
| Parsing, parking, superseding, expiry | `data/gateway/GatewaySessionRepository.kt` | `gateway-event/input-requests.ts:173-204,370-446` |
| The three `*.respond` calls | `data/gateway/GatewaySessionRepository.kt` | `components/prompt-overlays.tsx:288-297`, `:388-396`, `:505-513` |
| The cards, and the secure window they render in | `ui/chat/PendingInputSurface.kt` | `components/prompt-overlays.tsx:256-577` |
| The `input` notification kind, with no vault text in it | `data/notifications/SessionNotifier.kt` | `gateway-event/input-requests.ts:384-389`, `:410-415`, `:436-441` |

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
| `vaultUnlockDesc`: 41 words, and it says the master password "goes straight to {name} on this machine" (`en.ts:3900-3901`) | mobile-adaptation | 24 words, and it says the password "unlocks it for this session and is never stored or shown to the agent" | Two reasons, both real. Viewport: this is a dialog on a phone with a keyboard up, and `scripts/check-product-copy.py` caps a primary string at 36 words. Truthfulness: "this machine" is the Electron app's own host, and here the manager runs on the Gateway's host rather than on the phone, so the sentence would be false as written |
| `vaultSaveDesc`: the login "is encrypted on this machine" (`en.ts:3907-3908`) | mobile-adaptation | "your Gateway encrypts it and fills the page" | Same locative: the vault is the Gateway host's, and on a phone "this machine" reads as the phone. Everything else in the sentence is Desktop's, verbatim |
| `vaultSaveFootnote` and `vaultCodeFootnote` both point at Settings → Passwords & Logins (`en.ts:3912`, `:3920`) | omission | Neither footnote is rendered | out-of-scope: #223 — #223 is the three prompts that park a turn; the settings surface both footnotes name is #238, and a footnote directing someone to a screen this app does not have is worse than no footnote |
| Settings → Passwords & Logins manages saved logins, cards, addresses and authenticator keys (`app/settings/vault-settings.tsx`) | omission | Absent | out-of-scope: #223 — filed as #238. A login saved from the phone can only be removed from Desktop or the CLI until it ships |
| Each card is its own dialog component, mounted per session by the chat and by every tile (`prompt-overlays.tsx:579-596`) | mobile-adaptation | One secure dialog renders whichever single kind the session on screen has parked; another session's prompt is the pinned "Waiting for your answer in …" banner plus an `input` notification | One conversation is on screen at a time. The banner and the shade are how this app has surfaced a background session's parked prompt since #99, and a vault prompt joins that rather than inventing a second route |
| Both inputs disable and the confirm becomes a spinner while `*.respond` is in flight (`prompt-overlays.tsx:342-349`, `:461-468`, `:568-575`) | drift | The card has the state and the copy for it, but `ChatScreen` passes `isSubmitting = false`, so it never renders | #237. Pre-existing for sudo and secret, inherited here; wiring it touches `ChatViewModel` and `ChatScreen`, which #223 did not own |
| All three cards are titled with a `ShieldLock` glyph (`prompt-overlays.tsx:322`, `:425`, `:541`) | omission | The title is words only | deferred: #237 — a title glyph, not a control; this app's secure card has never had one and `HermesIcons` has no shield yet |
| The notification body is the prompt's own title: the site being signed into, or the manager being unlocked (`input-requests.ts:385`, `:411`, `:437`) | mobile-adaptation | The body is the session title, redacted and bounded, like every other kind | A phone renders that on a lock screen. #99's rule for this shade already forbids a command, tool output, a sudo prompt and a secret name; which site someone is signing into is the same class of thing. Also in `docs/parity/notifications.md` |
| `hint` is parsed off `vault.code.request` and stored (`input-requests.ts:376`) | omission | Parsed and stored, rendered nowhere | non-goal: Desktop does not render it either — `VaultCodeDialog` titles itself from `site` alone — so rendering it here would be a divergence rather than parity. It is carried so the contract is complete |
| `.expire` tears down clarify, sudo and secret cards too (`server.py:1249-1254`) | drift | Only the three vault `.expire` events are handled | #239. #223 shipped the vault half; the other three have never handled expiry, and changing when a sudo or clarify card disappears is a behaviour change this slice did not own |

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
| Each request parks its session as its own kind, with the payload parsed, and a vault event is never mistaken for a secret | `VaultPromptTest` |
| Desktop's two fallbacks: `site` defaults to `origin`, `display_name` defaults to `backend` | `VaultPromptTest` |
| A request with no `request_id` is dropped rather than parked unanswerably | `VaultPromptTest` |
| Each answer reaches its own method under its own parameter name — `password`, `login`, `code` | `VaultPromptTest` |
| A saved login travels as one JSON string, and declining sends `""` rather than an empty object | `VaultPromptTest` |
| Every `CharArray` handed to the repository is zeroed after the call, including on the empty answers | `VaultPromptTest`, `VaultPromptDialogTest` |
| A `status: expired` reply clears the prompt instead of leaving it answerable | `VaultPromptTest` |
| An `.expire` event clears its own request, settles the session, and cannot erase the prompt that superseded it | `VaultPromptTest` |
| Expiring one kind leaves another kind parked on the same session, and the session stays `NeedsInput` | `VaultPromptTest` |
| A vault prompt dies with its turn, like every other parked request | `VaultPromptTest` |
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
