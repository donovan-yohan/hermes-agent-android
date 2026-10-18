# Chat links and reasoning readability

## Pin

Desktop source inspected in a disposable export at
`36922ad064d65dcf25f8f48df81e1ccf9a55de67`. This page supplements existing
transcript and sticky-prompt ledgers; it does not move their historical pins.

- `apps/desktop/src/components/assistant-ui/markdown-text.tsx:256-282,284-378`
  preserves authored link labels, including formatted labels, and hands web
  destinations to `PrettyLink`.
- `apps/desktop/src/components/assistant-ui/markdown-text.bare-url.test.tsx:8-39`
  requires bare URLs to retain their full visible text, while labelled links
  keep their authored labels.
- `apps/desktop/src/components/assistant-ui/markdown-text.linklabels.test.tsx:17-63`
  covers inline-code labels and exact identifier casing.
- `apps/desktop/src/styles.css:1820-1833` clamps sticky user text to four
  line-heights with an overflow mask. The requested phone adaptation uses
  three rendered lines only on the sticky copy, with the original message
  kept complete in the transcript.
- `apps/desktop/src/components/assistant-ui/thread/message-parts.tsx:187-238,288-325,439-445`
  composes the running Thinking disclosure, with its body below the header.
  `apps/desktop/src/components/chat/disclosure-row.tsx:35-69` orders label,
  caret, then trailing metadata; `apps/desktop/src/i18n/en.ts:5078-5081`
  supplies the unchanged Thinking/Thought labels.

## Contract

Web links remain text, not whole-message click targets. Explicit Markdown
links keep their destination and label through nested formatting. Bare
HTTP/HTTPS URLs remain fully visible. Inline and fenced code retain their
literal text, with web URLs independently tappable. Copy and selection keep
exactly the visible characters; link annotation inserts no text.

Only absolute HTTP/HTTPS destinations are offered to Android's URI handler.
An explicit link's label is never auto-detected as a second destination,
including when the authored destination is refused. Ordinary web links do
not inherit the Gateway endpoint normalizer's authentication restrictions.

Expanded reasoning reserves the header's measured height before placing its
body. The prior renderer emitted sibling nodes inside a transcript Box, so
both started at the same origin. The correction stacks them without changing
the animated text, typography, metadata or action order. Native phase/bounds
coverage checks light/dark, narrow widths and enlarged fonts.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Web links use `PrettyLink`, with hover and desktop-open behavior | mobile-adaptation | Inline Compose links open through Android's URI handler, retaining authored labels and bare addresses | Touch and native-browser navigation, with no hover dependency |
| Code displays literal URL text | mobile-adaptation | Literal HTTP/HTTPS URLs in inline and fenced agent code are tappable without changing copied text | Requested consistent link access on a phone; source remains literal, not parsed as Markdown |
| Desktop supports pointer text selection across link labels | mobile-adaptation | Native Compose inline-link targets own direct long presses; surrounding prose selection and Copy reply remain available | Native link semantics and per-character hit testing; direct-link long-press selection is not claimed |
| Four-line sticky mask | mobile-adaptation | Three rendered lines independent of keyboard height, overflow-only fade on line three, full inline original | Requested smaller reading obstruction; see [sticky prompt](sticky-user-prompt-port.md) |
| Thinking header orders label, caret, then trailing metadata | drift | Existing Android disclosure orders label, metadata, caret; this repair only places the body below it | Existing control-order debt remains under #72, not certified as matching by the component reference |

## Visual report

- report: docs/parity/visual/chat-readability/report.html
- commit: dc1b996

[Rendered Desktop/native Android comparison](visual/chat-readability/report.html)
and [provenance, states and review limits](visual/chat-readability/README.md).

Exact-head native draws and the pinned Desktop component comparison are
recorded with this change's visual packet. They do not establish physical
keyboard, TalkBack, or browser-installation acceptance.
Physical acceptance remains pending: #72. Existing control-order drift keeps
the overall parity verdict at Concern.

Compose's native inline-link target owns a long press directly on a link.
Surrounding prose remains selectable, and Copy reply preserves the full visible
text. Direct-link long-press selection is not claimed by this patch.
