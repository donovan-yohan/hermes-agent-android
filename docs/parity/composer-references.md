# Composer reference chips: source and divergence ledger

Hermes Desktop's inline reference chip in the composer — the `@url:`/`@file:`/`@folder:`
directive painted as a glyph plus a label — ported per
[`docs/workflows/port-desktop-surface.md`](../workflows/port-desktop-surface.md):
`data/composer/ComposerReferenceSyntax.kt`, `ui/chat/composer/ComposerReferenceChips.kt`,
`ui/chat/Composer.kt`, `ui/chat/ChatViewModel.kt`, `ui/theme/HermesTokens.kt` and
`ui/common/HermesIcons.kt`. The wire text the composer sends is unchanged; the chip is a
same-length paint layer over it with an identity offset mapping.

## Pin

| Source | Pin | Read via |
|---|---|---|
| Desktop renderer (composer, directive chips, styles, i18n) | `hermes-agent` @ `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd` | read-only checkout; every citation taken with `git show <sha>:<path>` |

Every `path:line` below is against that SHA.

## Paths that settled the port

| Question | Path |
|---|---|
| What a chip is: text with colour and an optional icon, no fill, padding or border | `apps/desktop/src/styles.css:808-810`, `:855-868` |
| Chip ink for url/session: `--ui-accent-secondary` mixed 82% toward `--foreground` | `apps/desktop/src/styles.css:836-841`; chain `:208`, `apps/desktop/src/themes/context.tsx:235`, `styles.css:542`, `:389`, `:331`, `:206` |
| Chip ink for file/folder: `--ui-text-secondary` | `apps/desktop/src/styles.css:829-834` |
| Icon size, gap and opacity | `apps/desktop/src/styles.css:887-897` |
| Which SVG each kind draws on the chip | `apps/desktop/src/components/assistant-ui/reference-kinds.ts:60-76`, `apps/desktop/src/components/assistant-ui/directive-text.tsx:78-80` |
| The url label rule (host + path, scheme/port/fragment dropped, `www.` and one trailing slash stripped) | `apps/desktop/src/components/assistant-ui/directive-text.tsx:299-322`; `apps/desktop/src/app/chat/composer/directive-label.test.ts:135-146` |
| No truncation: labels wrap with the prose | `apps/desktop/src/app/chat/composer/index.tsx:1046-1047` |
| Wire syntax and fencing | `apps/desktop/src/app/chat/composer/rich-editor.ts:104-118`; `apps/desktop/src/components/assistant-ui/reference-kinds.ts:147` |
| Atomic caret, backspace and selection | `apps/desktop/src/app/chat/composer/rich-editor.ts:457-509`, `:513-526`, `:652-660`; token boundary `:253-271`, `:277-287` |
| Chipping on paste and on the space after a typed URL | `apps/desktop/src/app/chat/composer/url-refs.ts:35-60`, `:65-103`; `apps/desktop/src/app/chat/composer/index.tsx:553-562`; `apps/desktop/src/app/chat/composer/text-utils.ts:181-185` |
| Padding around an inserted reference | `apps/desktop/src/app/chat/composer/inline-refs.ts:169-171`; `apps/desktop/src/components/assistant-ui/thread/user-edit-composer.tsx:383-385` |

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| The chip draws Tabler's outline `link`/`file`/`folder` SVG (`reference-kinds.ts:60-76`, via `directive-text.tsx:78-80`) | mobile-adaptation | Codicon `link` U+EB15, `file` U+EA7B and `folder` U+EA83 from the bundled face (`HermesIcons.kt:107`, `:39`, `:133`) | Only Codicons ship (`docs/fonts.md:8`) and `HermesIcons.kt:70-76` already records the Tabler-to-Codicon substitution; `link` matches the chip's SVG shape and is the glyph the Add sheet's URL row already uses (`ComposerAddSheet.kt:141`). Desktop's own Codicon for the `url` kind is `globe` (`reference-kinds.ts:69`, the completion popover row's glyph per `:36-37`, rendered by `trigger-popover.tsx:229`), which is a different surface from the chip. Whether `link` reads correctly at phone size remains pending in #203's visual side-by-side |
| Icon `margin-inline-end: 0.25em`, `opacity: 0.8`, `vertical-align: -0.1em` (`styles.css:887-897`) | mobile-adaptation | A U+2005 four-per-em space after the glyph, the glyph's alpha multiplied by 0.8, `BaselineShift(-0.1f)` | A text span has no margin; the four-per-em space is 0.25em by definition and keeps the painted string the same length as the wire text |
| The label goes through `new URL()` normalisation: lowercased host, percent-encoded path (`directive-text.tsx:307-316`) | mobile-adaptation | Same-length masking hides the scheme, userinfo, port, fragment, a leading `www.` and one trailing slash, but keeps the typed case and encoding | The identity offset mapping keeps every editor offset in wire space; recasing or re-encoding would change the painted length and force a non-identity mapping |
| A caret that lands inside a chip is placed before it; the chip is `contenteditable=false` (`rich-editor.ts:652-660`) | mobile-adaptation | A direction-aware snap to the edge the caret travelled toward, or the nearer edge on a tap; a deletion that touches a chip removes it whole even when the keyboard has opened a composition on the token, at the cost of one input restart; an insertion or replacement made while a composition is open passes through untouched | Compose has no non-editable inline run, and rewriting an open preedit restarts the IME, so the restart is spent only where the chip would otherwise degrade to raw syntax |
| Bare `\S+` values are hydrated as chips on a whole-text render (`reference-kinds.ts:147`) | mobile-adaptation | Only fenced values paint as chips; a Gateway path pick is fenced at pick time; a half-typed value stays plain text | A per-keystroke re-parse would chip a value while it is still being typed |
| Composer copy yields the chip's DOM text, i.e. the label | mobile-adaptation | Copy and cut put the wire `@url:`…`` on the clipboard | The legacy text field copies the original value, and the wire form is what round-trips through a paste |
| Assistive tech meets an inline SVG, which is silent | mobile-adaptation | The editable text exposed to TalkBack replaces the glyph with a space; the U+200B and U+2005 characters stay | Keeping the same length keeps the selection range aligned with the wire text |
| Hovering a url or session chip floats an `Open` pill, `link-external` + `Open` (`directive-actions.tsx:1-12`, `directive-text.tsx:482-493`, `en.ts:2578`) | omission | None | deferred: #205 — touch has no hover; the follow-up decides the touch affordance |
| The chip carries a `title` tooltip with the full URL (`rich-editor.ts:135`) | omission | None | non-goal: touch has no hover tooltip; the wire URL is what copy and send carry |
| The `@session:` chip label is Desktop's session fallback label (`directive-text.tsx:303-305`) | omission | Session spans are atomic and coloured; their syntax stays visible | deferred: #205 |
| `+ → URL…` creates an attachment card (`use-composer-url-dialog.ts:31-47`, `session-tile.tsx:259-262`) | drift | An inline chip at the caret (`ComposerAddSheet.kt:234`) | #205 |
| The sent message renders the same chip, clickable (`user-message-text.tsx:121-175`, `directive-text.tsx:546-575`) | drift | The bubble and the sticky pinned prompt show the raw wire text (`Transcript.kt:327-411`, `ChatScreen.kt:615-637`) | #204 |

## Visual report

- pending: #203

## Executable evidence

| Claim | Test |
|---|---|
| Span recognition, masking, the label rule, completion fencing | `ComposerReferenceSyntaxTest` |
| Same-length paint, identity offset mapping, caret snap, atomic deletion, paste canonicalisation, caret-keeping on-space rule, insert padding | `ComposerReferenceChipsTest` |
| Reference ink derives from Desktop's primary over the text-primary wash in every skin | `ThemeSemanticParityTest` |
| A caret right after a chip opens no completion; a fresh `@` after a chip opens the At completion with an empty query | `ChatViewModelTest` |
| Displayed text differs from the submitted wire text; backspace removes a chip whole; a caret placed inside a chip snaps to an edge | `ComposerReferenceChipJourneyTest`, `ChatJourneyTest` |
