# Live-owner refusal escape: Desktop-to-Android parity

Desktop authority for this page is the exact UI pin
`564aef2946c436500a5e80ee117b66b789b3f99a`. The evidence below was read from
that Git object in the read-only upstream reference checkout; this page does
not claim that the local worktree HEAD is at the pin, and the upstream checkout
was not modified or fetched.

The surface is one state, not a screen: what the app says and offers when
`prompt.submit` is refused because another surface — a TUI, a messaging
gateway, a second client — holds this session's live-owner lease.

## Desktop contract

The refusal is machine data. `prompt.submit` answers JSON-RPC 4090 with
`error.data.reason = SESSION_NOT_OWNED`, and Desktop classifies off that code,
prose only for pre-contract backends
(`apps/desktop/src/app/session/hooks/use-prompt-actions/utils.ts:285-294` @
`564aef2946c436500a5e80ee117b66b789b3f99a`). Desktop had a sentence matcher
first and deleted it, because it "would silently miss a reworded or localized
message" (`c80003ff57`).

The classification is then *stamped on the failed turn* rather than re-derived
by the view: submit writes `errorSurface {layer: 'gateway', code:
'SESSION_NOT_OWNED', retryable: false}` onto the assistant message
(`apps/desktop/src/app/session/hooks/use-prompt-actions/submit.ts:856-875` @
the pin), and the error card reads `surface.code`
(`apps/desktop/src/components/assistant-ui/thread/assistant-message.tsx:548-551`
@ the pin).

The card's recovery row then renders, in this order: `Start new session` (only
when the code matches), the OAuth `Sign in again` action, `Retry` (only when
`retryable`, which this refusal is not), `Switch provider`, `Open logs`, and
`Send diagnostics`
(`apps/desktop/src/components/assistant-ui/thread/assistant-message.tsx:559-590`
@ the pin). The escape is therefore *first*, and `Retry` is *absent* — retrying
reproduces the same 4090 for as long as the lease is held. The button's action
is `requestFreshSession()` (`apps/desktop/src/store/profile.ts:353-355` @ the
pin).

Copy, verbatim: `errorStartNewSession: 'Start new session'`
(`apps/desktop/src/i18n/en.ts:3714` @ the pin).

## Android surface

Classification matches the contract and the order: `isSessionNotOwned()` reads
`error.data.reason` first and falls back to prose only for a bare 4090
(`GatewaySessionRepository.kt:5786-5792`), with `SessionNotOwnedTest` holding
that order.

What this page adds is the escape. A chat notice is no longer a string: it is
`ChatNotice(text, action)` with an optional `ChatNoticeAction`
(`ChatViewModel.kt`), so the one notice that has a way out carries it as data
instead of leaving the view to recognise the state by comparing user-visible
prose — the same sniffer Desktop deleted. The refusal is the only notice with
an action; every other notice is written through a line-only setter, so the
next notice, the next rehome, the next endpoint switch and the next accepted
send all retire the escape simply by writing.

The screen renders it on the notice's own line, which is already a door when it
names a problem another surface fixes: `StatusAction(spokenDestination,
onClick)` gives that line a 48dp pointer band and a spoken name
(`StatusAction.kt`). For this state the destination is Desktop's own label,
`Start new session`, and the click is `ChatActions.onCreateSession` — the same
`session.create` the sidebar's `+` uses.

That line has two homes, and #242 is the second one. The composer's `Full`
layout shows it as the status line it has always been; at 560dp of composer
width and below — every width that is not `Full` — the bottom row belongs to the
model control, so the same sentence is drawn on a line of its own above the
editor (`Composer.kt`), with the same wording, the same band and the same
spoken destination — the two houses are handed one `statusAction` and differ
only in where the words sit. What deliberately does *not* travel down with it
is the rest of the status line: a connection that needs a door reports itself
in the header at every width, so the composer owes a phone no permanent second
row.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| The escape is a button on the failed turn's inline error card, inside the transcript | mobile-adaptation | The escape is the refusal's own notice line, which the composer draws at every width — the status line in `Full`, a line above the editor below it | A refused `prompt.submit` never becomes a transcript turn here — there is no error card to hang a button on — and the notice line is this app's established tappable-status seam, which is what gives the escape a 48dp pointer band and a spoken destination instead of a caption-sized link (`StatusAction.kt:27-30`, `:103-112`). |
| `Retry` is rendered for retryable failures and hidden for this one | mobile-adaptation | No per-turn Retry control exists; the refused draft is restored into the composer, where Send is the retry | Touch viewport: a per-turn action row costs a phone more than it returns, so the failed prompt comes back to the composer instead (`ChatViewModel.kt` `restoreSubmittedDraft`). Desktop's composer stays sendable in this state too — what it hides is the card's own Retry, and there is none here to hide. |
| The card shows the gateway's error text and prints the escape's label on a button | mobile-adaptation | One app-written sentence carries both: "Another Hermes has this session open. Start a new session to send here." | Viewport space: the line *is* the control, so there is no button face to print a label on. Desktop's label is kept verbatim as the spoken destination, so a screen reader announces the sentence and then "Start new session". |
| The escape is visible at every window width | mobile-adaptation | Visible at every width from two homes: the composer's status line in `Full`, and a line above the editor at 560dp of composer width and below | Viewport space: a narrow composer's bottom row is already the model control's, so the sentence takes the line above the editor — the one-line seam the coding header uses — instead of evicting a control. Wording, 48dp band and spoken destination are the same at both widths because both homes are handed one `statusAction` (`Composer.kt`, `StatusAction.kt`). Asserted at both widths by `SessionNotOwnedEscapeTest`, which used to pin the gap and now pins the contract. |

## Visual report

- pending: #243

No render was produced. This page records the source contract at the pin, the
classification order, and the three adaptations above; the side-by-side and the
device pass are owed by #243.
