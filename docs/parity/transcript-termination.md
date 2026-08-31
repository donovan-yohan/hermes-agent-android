# Transcript termination attribution: Desktop-to-Android parity

Desktop authority for this page is the exact UI pin
`936b970e281d5d28e930c5698f36bc4ebb54c7ba`. The evidence below was read from
that Git object in the read-only upstream reference checkout; this page does
not claim that the local worktree HEAD is at the pin, and the upstream checkout
was not modified or fetched.

## Desktop contract

Desktop has no transcript row or copy for a turn ended externally. The pinned
assistant message renders message parts, loading state, attachments, errors,
the timestamp, footer and changed-files card, with no termination scaffold
(`apps/desktop/src/components/assistant-ui/thread/assistant-message.tsx:187-241`
@ `936b970e281d5d28e930c5698f36bc4ebb54c7ba`). Its gateway event handler drops
reclaimed runtime state and refreshes the session list, then handles
`session.info`; neither branch adds transcript termination copy
(`apps/desktop/src/app/session/hooks/use-message-stream/gateway-event.ts:478-504`
@ `936b970e281d5d28e930c5698f36bc4ebb54c7ba`). The same handler settles a
running=false turn without a completion event as lifecycle state, not as a
user-attributed transcript row
(`apps/desktop/src/app/session/hooks/use-message-stream/gateway-event.ts:695-719`
@ `936b970e281d5d28e930c5698f36bc4ebb54c7ba`).

The only nearby Desktop wording is `backendStopped: 'Backend stopped'` in the
boot error translations (`apps/desktop/src/i18n/en.ts:75-82`
@ `936b970e281d5d28e930c5698f36bc4ebb54c7ba`); it is boot/status copy, not a
per-transcript external-ended label. Desktop's interrupted-turn test instead
keeps the partial reply and correction visible and asserts that the generated
interrupt scaffolding is not painted into the transcript
(`apps/desktop/src/lib/chat-messages.test.ts:287-323`
@ `936b970e281d5d28e930c5698f36bc4ebb54c7ba`). Therefore there is no Desktop
external-ended string for Android to copy.

Android preserves the existing user-attributed wording exactly: the only
user-attributed termination is `Stopped by you` (`Transcript.kt:504-507`).
Every external ending uses one product-facing row separated from Gateway reason
strings: `The Gateway ended this turn. You can try again.` It states the
outcome and a safe next action without claiming where the app was when the turn
ended (`Transcript.kt:504-513`).

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| No external-ended transcript row or copy; reclaimed and `session.info` events update lifecycle/session state only | mobile-adaptation | Adds a non-selectable scaffold row for externally ended turns; only a confirmed interrupt sent by this client says `Stopped by you` | Android must explain a turn that can end while the process is suspended and its socket is lost: the Gateway may emit `ws_orphan_reap`. The row is chrome outside the reply selection boundary (`Transcript.kt:485-488`), and user copy remains exact (`Transcript.kt:504-507`). |

## Visual report

- pending: #72

No render was produced. This page records the source contract and the
mobile-adaptation rationale; the side-by-side visual report remains pending.
