# Sticky current user prompt: Desktop-to-Android parity

## Current push-off correction

The historical collapse implementation described below was **not** user-visible
acceptance. Main `bb1693abc2b6ce924b2f6ebfd8bfcb24ee3e6d36` fails the public
Compose regressions for full-height retention and incoming-user push-off. Its
owner lookup changed only when the incoming row crossed the viewport top;
there was no next-user collision geometry, so incoming bubbles ran behind the
opaque pin. A separate measured four-line copy produced the internal cut.

The current overlay keeps one full-height, same-width bubble. Its top is bounded
by the incoming user row's top minus the outgoing height and turn gap. Both
bubbles therefore move together until the outgoing bubble leaves the viewport;
only then does the incoming user acquire the pin. Source body paint and
semantics are hidden in the same composition as overlay ownership, not through
a delayed measurement callback. Attachments retain their inline slot.

`PromptCollapseTest` now asserts full-height retention and measured paired
movement through the public `ChatScreen`; successor, pagination, return,
selection, touch-size and mask tests remain. Native synchronous window captures
are explicitly Robolectric evidence, not emulator or physical-device acceptance.
The paired Desktop/device report remains pending under #72. The older collapse
narrative below is historical and is superseded by this section.


Desktop authority is `3ca096de5f8183cb2e0ec23673f294d5978656a3`: `apps/desktop/src/components/assistant-ui/thread/list.tsx:194-232,350-372` groups each human turn, `user-message.tsx:28-52,321-367` makes its bubble sticky, `styles.css:1538-1569` supplies the opaque four-line fade, and `timeline.tsx:113-125` jumps by message id.

Android derives the closest preceding authoritative `UserTurn` for the first visible assistant, tool, reasoning or timeline row; its viewport/follow state is local. As the source reaches the viewport top, Android preserves the inline slot and moves visible/accessibility ownership to an overlay whose height follows the source scroll distance in both directions. The full and four-line forms are measured at the same actual width, so short prompts and explicit wide lines keep the same right edge without rewrapping; prompts already within four lines use the turn-gap distance for a continuous inset transition. The `Show earlier messages` row stays in LazyColumn coordinates only and is removed before prompt-owner and source-position lookup. `PromptCollapseTest` covers width, intermediate/reverse geometry, one-pixel successor handoff, pagination-row indexing and single ownership. `PromptBubbleDecorationRenderTest` uses Robolectric native graphics for one-line/four-line continuity and compact border/corner pixels.

The settled pin keeps the opaque semantic user-bubble tokens, 14dp radius, four-line measured fade, a 48dp `Return to prompt` action, and re-resolves the source id at tap time while disarming tail follow. Its accessibility label includes the prompt text, and its bubble shares the transcript `LazyListState`, so a drag or fling begun on the overlay keeps scrolling instead of creating a dead strip. `@image:` references are split out and attachment-only prompts have no excerpt.

The debug-manifest-only `StickyPromptParityActivity` is a sanitized two-turn visual fixture with dark/light extra support and no orientation lock. Focused Compose coverage verifies visible-source suppression, turn-relative source identity, id return/no re-follow, delayed history, image reference stripping, readable unique semantics, shared scroll action, and touch size. Ignored visual evidence belongs in `build/visual-parity/sticky-user-prompt/`.

When an older page is prepended by `Show earlier messages`
(`docs/parity/transcript-backfill.md`), the pin does not move and does not
change owner. The merge only adds a strictly older, chronological prefix: it
never reorders, rewrites or drops a row already on screen, so a turn stays
whole across the window boundary and the closest preceding `UserTurn` for the
first visible row is the same turn it was. What does change is that turn's
index, which the pane re-anchors on the row itself rather than on a position.

## The mask behind the pin, pinned separately

Upstream masked the thread behind its sticky bubble after the authority above
was written, so this half is pinned at
`564aef2946c436500a5e80ee117b66b789b3f99a` — the repo pin — rather than at
`3ca096de`. It landed as `e7c819a7e1`, and it is one of only two `styles.css`
hunks in that whole range.

Desktop's bubble is a `position: sticky` child of the scroll container, so it
floats `--sticky-human-top` — `0.23rem`, `apps/desktop/src/styles.css:497` —
below the viewport's top edge. That sliver belonged to the thread, and the
thread kept scrolling through it. The fix paints a
`[data-slot='aui_user-message-root']::before` with
`--ui-chat-surface-background` over the sliver (`styles.css:1568-1577`) and
offsets the bubble by a further pixel,
`--sticky-human-offset: calc(var(--sticky-human-top) + 1px)` (`:1562-1565`), so
the cover overlaps the bubble's own fill rather than abutting it. The container
also declares `data-glass-opaque` at its call site
(`apps/desktop/src/components/assistant-ui/thread/user-message.tsx:45`), which
under a glass window forces `--ui-chat-surface-background` back to the solid
`--ui-bg-chrome` (`styles.css:676-680`): a see-through mask reads as text
through text, not as glass.

This port has no sliver to cover. The pin is a sibling overlay aligned to the
top of the very box the transcript fills (`ChatScreen.kt:700-702,773-788`), so it begins
where the viewport begins, and its own opaque `chatSurface` box *is* the cover —
full-bleed width, with a scroll-derived `spacing.turnGap` inset above and below
the settled bubble (`ChatScreen.kt:874-875,925-929,985-990`). The Android counterpart of `--sticky-human-top`
is 0 dp, which is why neither the `::before` nor the extra pixel has anything
to do here. There is no glass field to fall through either: `chatSurface` is the
chrome seed on every theme and mode (`HermesTokens.kt:252`), which is the value
Desktop's glass rule forces.

That leaves the Android equivalent of a whole CSS element as an invariant about
a colour — the kind of claim no text assertion can see and a silent refactor can
drop. `StickyPromptMaskInkTest` reads it back in pixels instead: the turn behind
the pin is one tall fenced block, because a fence paints `widgetSurface` edge to
edge (`Transcript.kt:2295-2301`) where prose would leave exactly the
`chatSurface` a missing mask would show, and prove nothing. Both bands read
`chatSurface`; the row below the pin reads `widgetSurface` (`Transcript.kt:2295-2301`).

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| `timeline.tsx:113-125` jumps by message id held from render | mobile-adaptation | The source id is re-resolved at tap time, and tail follow is disarmed | A phone transcript is re-composed under the finger far more often than a desktop one; resolving late is what keeps the jump landing on the prompt the reader can see |
| Desktop's sticky source keeps its settled message height | mobile-adaptation | Android collapses the same-width source into the four-line pin from `LazyListState` scroll distance, with reversible intermediate geometry (`ChatScreen.kt:662-698,874-990`) | A long prompt otherwise replaces a full-height inline bubble with compact chrome at one item boundary on a phone; `PromptCollapseTest` and `PromptBubbleDecorationRenderTest` cover actual width, the pagination row, forward/reverse one-pixel movement, corners and border |
| Sticky bubble is chrome the pointer scrolls past | mobile-adaptation | The bubble shares the transcript `LazyListState` | A drag or fling begun on the overlay keeps scrolling instead of creating a dead strip under the thumb |
| Return-to-prompt is a pointer-sized affordance | mobile-adaptation | A 48 dp `Return to prompt` action whose accessibility label includes the prompt text | Touch floor, and the spoken label has to name which prompt it returns to |
| The prompt excerpt renders `@image:` references as prose | mobile-adaptation | References are split out; an attachment-only prompt has no excerpt | One phone-width line of excerpt cannot spend itself on a wire-format path |
| `styles.css:1568-1577` covers the sticky sliver with a `::before`, and `:1562-1565` offsets the bubble a pixel so the cover overlaps it | mobile-adaptation | The overlay's own `chatSurface` box is the cover, and it starts at the viewport's top edge | A Compose overlay is placed rather than offset by CSS sticky, so the sliver a `top:` creates never exists and there is no seam for the extra pixel to close; `StickyPromptMaskInkTest` reads both bands back in pixels |
| `user-message.tsx:45` declares `data-glass-opaque` so a glass window cannot thin the mask (`styles.css:676-680`) | mobile-adaptation | No glass field exists, and `chatSurface` is the chrome seed on every theme and mode (`HermesTokens.kt:252`) | An Android window has no translucent desktop field behind it to fall through, and the token already resolves to the value that rule forces |

## Visual report

- pending: #72

`StickyPromptParityActivity` is a debug-manifest-only sanitized two-turn visual
fixture with dark/light support and no orientation lock; ignored capture output
belongs in `build/visual-parity/sticky-user-prompt/`. The rendered side-by-side
against Desktop is owed by the device acceptance matrix.

The masking half above is not waiting on that report to be checkable.
`StickyPromptMaskInkTest` renders the pane under Robolectric's native canvas and
asserts the ink of the bands the pin adds, so the one thing the rendered
comparison would be looking for there already fails the build offline when it
stops being true.
