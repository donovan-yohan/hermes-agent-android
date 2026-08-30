# Transcript selection and per-reply copy: Desktop-to-Android parity

Desktop authority is `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`: `apps/desktop/src/styles.css:1170-1180` makes the message slots and `[data-selectable-text='true']` subtrees `user-select: text`, `1182-1186` puts every `button`/`[role='button']` back to `none`, and `1188-1194` undoes that again for the user bubble's own text because the bubble is itself a button. `apps/desktop/src/components/assistant-ui/thread/user-message-selection.test.ts` tests selection on the user bubble. `apps/desktop/src/components/assistant-ui/thread/assistant-message.tsx:245-293` mounts a per-message `AssistantActionBar` containing a `CopyButton`, always mounted and revealed on `group-hover`, deliberately not `hideWhenRunning` so a settling turn does not shift the conversation. That button copies markdown **source**, not rendered text: `:286` passes `text={getMessageText}`, `:135` defines `getMessageText` as `messageContentText(messageRuntime.getState().content)`, and `thread/content.ts:17-23` joins the message's raw text parts. `apps/desktop/src/styles.css:368` and `:root.dark:545` pin `--ui-selection-background: color-mix(in srgb, #ffd24a 55%|38%, transparent)`; `745-746` applies it through a global `*::selection`. That token is byte-identical at the theme ledger's pin `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8` (`styles.css:382` / `564` / `766-767`), which is where `ThemeSemanticParityTest` reads it.

Android inverts the mechanism because Compose inverts the default: nothing is selectable unless a `SelectionContainer` says so, so the boundary Desktop draws by opting chrome *out* is drawn here by where the container goes. `AssistantProse` wraps one container around a turn's markdown blocks, so a selection spans that reply's paragraphs, headings, lists, tables, inline code and fences and stops at the turn edge. `UserTurnBubble` takes an opt-in `selectable` flag, passed only by the transcript bubble. The per-reply copy control writes `List<MarkdownBlock>.replyPlainText()` — rendered text, inline markers dropped, fences verbatim, list markers kept, standalone `@image:` lines stripped — to the platform clipboard, and carries a `Copy reply` custom accessibility action inside a 48dp target.

## Deviations

| Desktop | Android | Why |
|---|---|---|
| Native browser selection is the whole copy story | selection **plus** an explicit per-reply copy control | two handles and a scroll gesture are a poor way to grab several screens of prose on a phone; the system Copy in the selection toolbar still covers "copy this sentence" |
| Action bar is `opacity-0`, reveals on `group-hover` | always mounted, always quiet: scaffold-meta ink inside a 48dp target | there is no hover on touch. Same deviation shape as the worked example in `docs/workflows/port-desktop-surface.md` |
| Bar is mounted from the first frame of a turn | control appears with the reply's first visible text | a control that copies nothing is worse than one that arrives a token late; the shift is one row at the start of a turn rather than at the settle Desktop's comment protects |
| Clipboard writes are silent | Copy → Check state on the control, no toast | Android 13+ raises a system clipboard notice; a second confirmation would be the app talking over the platform |
| `<li>` markers are not text nodes, so a drag skips them | `DisableSelection` on bullet/number markers and the fence language tag | drag-selection matches the browser; whole-reply copy keeps markers, because it is copying structure rather than a drag |
| `CopyButton` hands over the markdown **source** (`assistant-message.tsx:286` → `:135` → `content.ts:17-23`, which joins the raw text parts) | `replyPlainText()` hands over **rendered** plain text: inline markers dropped, fences without their fence line or language tag, tables tab-separated | a real product difference, not a port artefact — Android could read `turn.markdown` just as easily. A phone pastes into chat, notes and message composers, where `**bold**` and a bare fence line are syntax the reader has to decode; Desktop's paste target is usually an editor that will re-render it. Issue #48 asks for rendered text by name. `MarkdownPlainTextTest` pins the projection |
| No selection handles exist | handle colour is `tokens.accent` | an Android affordance with no Desktop equivalent, so it wears the brand stroke |
| `*::selection` is global | `LocalTextSelectionColors` is provided app-wide inside `MaterialTheme` | matches the global rule, and stops Material seeding selection from `colorScheme.primary` per skin |

## Omissions

Recorded rather than implemented, per `docs/workflows/port-desktop-surface.md`:

- **Tool output, inline diffs and reasoning text are not selectable.** Desktop marks `components/chat/terminal-output.tsx` and `components/ui/log-view.tsx` with `data-selectable-text`, so a Desktop user can drag-select terminal output and log lines. Issue #48 requires only that a *reply's* selection not bleed into tool cards, which one container per turn already guarantees; giving those payloads their own container is a separate change, and it interacts with their `horizontalScroll` and with the disclosure row's collapse tap in ways that want device verification. Follow-up needed.
- **A fence's or table's `horizontalScroll` inside the `SelectionContainer` is unverified on device.** Both blocks scroll sideways inside the container that now claims the long press, so a horizontal drag that starts on code is a gesture two nodes want. Nothing here asserts which one wins: the Robolectric drag test measures the *vertical* transcript scroll, and Compose's selection/scroll arbitration is the kind of thing a host JVM models badly. Same device verification the tool-output omission above wants.
- **`ReadAloudButton`, `Reload` and `branchInNewChat`** from Desktop's action bar have no Android equivalent. Out of scope for #48; the bar here has one control.
- **A reply whose only content is a standalone `@image:` line has nothing to copy.** Assistant prose is rendered without the ref strip the user bubble applies, so that line is drawn as ordinary text; the control is gated on what is *drawn*, so it is mounted, but #48 asks for refs to be stripped and the strip empties the projection. The control is therefore disabled and offers no TalkBack action rather than confirming a clipboard write that carried no text; the words stay long-pressable. The defect underneath is the transcript drawing a wire-format line as prose at all — changing what assistant turns render is a separate slice. Pinned by `TranscriptSelectionTest`.
- **A reference folded into a paragraph by a soft line break survives into the clipboard.** The `@image:` strip runs per projected block so it can never gut a fence that is *quoting* the format, which costs the folded case. Only user turns are written in that shape, and they are split before parsing.

## Evidence

`app/src/test/.../data/markdown/MarkdownPlainTextTest.kt` (11) covers the projection on fixed inputs. `app/src/testDebug/.../ui/chat/TranscriptSelectionTest.kt` (13) covers long-press selection on prose and on the user bubble, the platform menu offering Copy, scaffold rows and the pinned prompt selecting nothing, both halves of the streaming behaviour below, a real pointer drag scrolling the same with and without a selection, the clipboard contents, the in-place confirmation, the 48dp target, the custom action, and the control appearing but disabled for a reply that renders only an `@image:` line. `ThemeSemanticParityTest` pins the selection token across every built-in in both modes.

## Streaming

Measured, not inferred. A selection anchored in a turn's **settled prefix** survives every subsequent delta: the `SelectionContainer` is never remounted, and Compose keeps the anchors on child layouts the delta does not touch. A selection anchored inside the **tail block the delta rewrites is cleared outright** — the handles go from two to zero on the next token, and the reader is left with nothing selected. Only the settled prefix survives.

Both halves are pinned by `TranscriptSelectionTest`, the second deliberately: it asserts today's behaviour so that a Compose upgrade which starts re-anchoring a rewritten block fails the test and gets noticed rather than landing unremarked.

So selecting inside a live turn's last paragraph is not a usable gesture. **The copy control is the path for a live turn**: it is mounted throughout, matching Desktop's always-mounted bar, and copies the reply as far as it has arrived.

## Not covered

What a *drag* selection actually copies, which would pin the `DisableSelection` half of the marker contract. No device or emulator capture; the floating toolbar chrome is Android's ActionMode.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Native browser selection is the whole copy story | mobile-adaptation | Selection **plus** an explicit per-reply copy control | Two handles and a scroll gesture are a poor way to grab several screens of prose on a phone; the system Copy still covers "copy this sentence" |
| Action bar is `opacity-0`, revealed on `group-hover` | mobile-adaptation | Always mounted, always quiet: scaffold-meta ink inside a 48 dp target | There is no hover on touch |
| Bar is mounted from the first frame of a turn | mobile-adaptation | The control appears with the reply's first visible text | A control that copies nothing is worse than one that arrives a token late; the shift is one row at the start of a turn |
| Clipboard writes are silent | mobile-adaptation | Copy → Check on the control, no toast | Android 13+ raises a system clipboard notice, and a second confirmation would be the app talking over the platform |
| `<li>` markers are not text nodes, so a drag skips them | mobile-adaptation | `DisableSelection` on bullet/number markers and the fence language tag | Drag-selection matches the browser; whole-reply copy keeps markers, because it is copying structure rather than a drag |
| `CopyButton` hands over the markdown **source** (`assistant-message.tsx:286` → `:135` → `content.ts:17-23`) | mobile-adaptation | `replyPlainText()` hands over **rendered** plain text | A phone pastes into chat, notes and message composers, where `**bold**` and a bare fence line are syntax the reader has to decode; Desktop's paste target is usually an editor that re-renders it |
| No selection handles exist | mobile-adaptation | Handle colour is `tokens.accent` | An Android affordance with no Desktop equivalent, so it wears the brand stroke |
| `*::selection` is global | mobile-adaptation | `LocalTextSelectionColors` provided app-wide inside `MaterialTheme` | Matches the global rule, and stops Material seeding selection from `colorScheme.primary` per skin |
| `data-selectable-text` on `terminal-output.tsx` and `log-view.tsx` | drift | Tool output, inline diffs and reasoning text are not selectable | Their own container interacts with `horizontalScroll` and the disclosure row's collapse tap; #56 |
| A fence's or table's `horizontalScroll` inside a selectable subtree | drift | Unverified on device | Compose's selection/scroll arbitration is modelled badly on a host JVM; #56 |
| `ReadAloudButton`, `Reload` and `branchInNewChat` in the action bar | omission | Absent; the bar here has one control | pill-owed: #101 — Desktop renders all three, so each owes a disabled "coming soon" control (rewind is #69) |
| A reply whose only content is a standalone `@image:` line | omission | The control is mounted but disabled, with no TalkBack action | coming soon is not the case here: the strip empties the projection, so the control is honestly dead rather than absent; the defect underneath is the transcript drawing a wire-format line as prose (#56) |

## Visual report

- pending: #72

No device or emulator capture: the floating toolbar is Android's own ActionMode
chrome, and what a *drag* selection actually copies — the `DisableSelection`
half of the marker contract — is the part only a device can show.
