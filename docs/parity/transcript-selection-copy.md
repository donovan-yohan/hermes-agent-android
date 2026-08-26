# Transcript selection and per-reply copy: Desktop-to-Android parity

Desktop authority is `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`: `apps/desktop/src/styles.css:1170-1180` makes the message slots and `[data-selectable-text='true']` subtrees `user-select: text`, `1182-1186` puts every `button`/`[role='button']` back to `none`, and `1188-1194` undoes that again for the user bubble's own text because the bubble is itself a button. `apps/desktop/src/components/assistant-ui/thread/user-message-selection.test.ts` tests selection on the user bubble. `apps/desktop/src/components/assistant-ui/thread/assistant-message.tsx:245-293` mounts a per-message `AssistantActionBar` containing a `CopyButton`, always mounted and revealed on `group-hover`, deliberately not `hideWhenRunning` so a settling turn does not shift the conversation. `apps/desktop/src/styles.css:368` and `:root.dark:545` pin `--ui-selection-background: color-mix(in srgb, #ffd24a 55%|38%, transparent)`; `767` applies it through a global `*::selection`. That token is byte-identical at the theme ledger's pin `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8` (`styles.css:382` / `564` / `767`), which is where `ThemeSemanticParityTest` reads it.

Android inverts the mechanism because Compose inverts the default: nothing is selectable unless a `SelectionContainer` says so, so the boundary Desktop draws by opting chrome *out* is drawn here by where the container goes. `AssistantProse` wraps one container around a turn's markdown blocks, so a selection spans that reply's paragraphs, headings, lists, tables, inline code and fences and stops at the turn edge. `UserTurnBubble` takes an opt-in `selectable` flag, passed only by the transcript bubble. The per-reply copy control writes `List<MarkdownBlock>.replyPlainText()` — rendered text, inline markers dropped, fences verbatim, list markers kept, standalone `@image:` lines stripped — to the platform clipboard, and carries a `Copy reply` custom accessibility action inside a 48dp target.

## Deviations

| Desktop | Android | Why |
|---|---|---|
| Native browser selection is the whole copy story | selection **plus** an explicit per-reply copy control | two handles and a scroll gesture are a poor way to grab several screens of prose on a phone; the system Copy in the selection toolbar still covers "copy this sentence" |
| Action bar is `opacity-0`, reveals on `group-hover` | always mounted, always quiet: scaffold-meta ink inside a 48dp target | there is no hover on touch. Same deviation shape as the worked example in `docs/workflows/port-desktop-surface.md` |
| Bar is mounted from the first frame of a turn | control appears with the reply's first visible text | a control that copies nothing is worse than one that arrives a token late; the shift is one row at the start of a turn rather than at the settle Desktop's comment protects |
| Clipboard writes are silent | Copy → Check state on the control, no toast | Android 13+ raises a system clipboard notice; a second confirmation would be the app talking over the platform |
| `<li>` markers are not text nodes, so a drag skips them | `DisableSelection` on bullet/number markers and the fence language tag | drag-selection matches the browser; whole-reply copy keeps markers, because it is copying structure rather than a drag |
| `getMessageText()` reads the rendered DOM | `replyPlainText()` projects the parsed blocks | there is no DOM to read; the projection is the equivalent and is unit-tested |
| No selection handles exist | handle colour is `tokens.accent` | an Android affordance with no Desktop equivalent, so it wears the brand stroke |
| `*::selection` is global | `LocalTextSelectionColors` is provided app-wide inside `MaterialTheme` | matches the global rule, and stops Material seeding selection from `colorScheme.primary` per skin |

## Omissions

Recorded rather than implemented, per `docs/workflows/port-desktop-surface.md`:

- **Tool output, inline diffs and reasoning text are not selectable.** Desktop marks `components/chat/terminal-output.tsx` and `components/ui/log-view.tsx` with `data-selectable-text`, so a Desktop user can drag-select terminal output and log lines. Issue #48 requires only that a *reply's* selection not bleed into tool cards, which one container per turn already guarantees; giving those payloads their own container is a separate change, and it interacts with their `horizontalScroll` and with the disclosure row's collapse tap in ways that want device verification. Follow-up needed.
- **`ReadAloudButton`, `Reload` and `branchInNewChat`** from Desktop's action bar have no Android equivalent. Out of scope for #48; the bar here has one control.
- **A reference folded into a paragraph by a soft line break survives into the clipboard.** The `@image:` strip runs per projected block so it can never gut a fence that is *quoting* the format, which costs the folded case. Only user turns are written in that shape, and they are split before parsing.

## Evidence

`app/src/test/.../data/markdown/MarkdownPlainTextTest.kt` (11) covers the projection on fixed inputs. `app/src/testDebug/.../ui/chat/TranscriptSelectionTest.kt` (11) covers long-press selection on prose and on the user bubble, the platform menu offering Copy, scaffold rows and the pinned prompt selecting nothing, a streaming delta not dropping a selection, a real pointer drag scrolling the same with and without a selection, the clipboard contents, the in-place confirmation, the 48dp target and the custom action. `ThemeSemanticParityTest` pins the selection token across every built-in in both modes.

Not covered: what a *drag* selection actually copies, which would pin the `DisableSelection` half of the marker contract. No device or emulator capture; the floating toolbar chrome is Android's ActionMode.
