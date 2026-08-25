# Sticky current user prompt: Desktop-to-Android parity

Desktop authority is `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8`: `apps/desktop/src/components/assistant-ui/thread/list.tsx:178-215,333-355` groups each human turn, `user-message.tsx:28-52,321-367` makes its bubble sticky, `styles.css:1538-1569` supplies the opaque four-line fade, and `timeline.tsx:113-125` jumps by message id.

Android derives the closest preceding authoritative `UserTurn` for the first visible assistant/tool row; its viewport/follow state is local. The pin uses opaque semantic user-bubble tokens, 14dp radius, four-line measured fade, a 48dp `Return to current prompt` action, and re-resolves the source id at tap time while disarming tail follow. `@image:` references are split out and attachment-only prompts have no excerpt.

The debug-manifest-only `StickyPromptParityActivity` is a sanitized two-turn visual fixture with dark/light extra support and no orientation lock. Focused Compose coverage verifies visible-source suppression, turn-relative source identity, id return/no re-follow, delayed history, image reference stripping, unique semantics, and touch size. Ignored visual evidence belongs in `build/visual-parity/sticky-user-prompt/`.
