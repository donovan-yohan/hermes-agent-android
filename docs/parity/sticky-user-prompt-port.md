# Sticky current user prompt: Desktop-to-Android parity

Desktop authority is `3ca096de5f8183cb2e0ec23673f294d5978656a3`: `apps/desktop/src/components/assistant-ui/thread/list.tsx:194-232,350-372` groups each human turn, `user-message.tsx:28-52,321-367` makes its bubble sticky, `styles.css:1538-1569` supplies the opaque four-line fade, and `timeline.tsx:113-125` jumps by message id.

Android derives the closest preceding authoritative `UserTurn` for the first visible assistant/tool row; its viewport/follow state is local. The pin uses opaque semantic user-bubble tokens, 14dp radius, four-line measured fade, a 48dp `Return to prompt` action, and re-resolves the source id at tap time while disarming tail follow. Its accessibility label includes the prompt text, and its bubble shares the transcript `LazyListState`, so a drag or fling begun on the overlay keeps scrolling instead of creating a dead strip. `@image:` references are split out and attachment-only prompts have no excerpt.

The debug-manifest-only `StickyPromptParityActivity` is a sanitized two-turn visual fixture with dark/light extra support and no orientation lock. Focused Compose coverage verifies visible-source suppression, turn-relative source identity, id return/no re-follow, delayed history, image reference stripping, readable unique semantics, shared scroll action, and touch size. Ignored visual evidence belongs in `build/visual-parity/sticky-user-prompt/`.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| `timeline.tsx:113-125` jumps by message id held from render | mobile-adaptation | The source id is re-resolved at tap time, and tail follow is disarmed | A phone transcript is re-composed under the finger far more often than a desktop one; resolving late is what keeps the jump landing on the prompt the reader can see |
| Sticky bubble is chrome the pointer scrolls past | mobile-adaptation | The bubble shares the transcript `LazyListState` | A drag or fling begun on the overlay keeps scrolling instead of creating a dead strip under the thumb |
| Return-to-prompt is a pointer-sized affordance | mobile-adaptation | A 48 dp `Return to prompt` action whose accessibility label includes the prompt text | Touch floor, and the spoken label has to name which prompt it returns to |
| The prompt excerpt renders `@image:` references as prose | mobile-adaptation | References are split out; an attachment-only prompt has no excerpt | One phone-width line of excerpt cannot spend itself on a wire-format path |

## Visual report

- pending: #72

`StickyPromptParityActivity` is a debug-manifest-only sanitized two-turn visual
fixture with dark/light support and no orientation lock; ignored capture output
belongs in `build/visual-parity/sticky-user-prompt/`. The rendered side-by-side
against Desktop is owed by the device acceptance matrix.
