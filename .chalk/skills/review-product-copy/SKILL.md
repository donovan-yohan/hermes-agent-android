---
name: review-product-copy
description: Review Android product copy for task, state, outcome, next action, Hermes Desktop terminology, concise limitations, safe errors, and rendered accessibility semantics. Use for visible strings, error copy, screenshots, or product-copy gate exceptions.
---

# Review product copy

Primary UI is product copy, not an implementation note. Read the rendered
strings in their real Kotlin call sites and follow the complete checklist in
[`docs/workflows/review-product-copy.md`](../../../docs/workflows/review-product-copy.md).
Whether a string matches Desktop's word for word is `review-desktop-parity`'s
verbatim `en.ts` diff; this skill owns whether the words serve the user.

## Required evidence

1. Read the actual strings that render, including conditionals, concatenation,
   error mapping, content descriptions, and disabled states. Comments are not
   evidence of what a user sees.
2. Compare Hermes Desktop terminology/copy at the pinned authority SHA when it
   applies. If access is intentionally frozen, use the supplied SHA-scoped
   authority ledger and record that boundary.
3. Review phone/wide screenshots or rendered previews and the merged/unmerged
   Compose semantics. A short source literal can still wrap into an essay or
   be announced twice.
4. Run `scripts/check-product-copy.py` and its synthetic self-test. Reject a
   reasonless allow marker or an exception that hides ordinary primary copy.

## Reject when

- primary copy explains phases, architecture, implementation status, security
  design, or developer caveats instead of the user's task and next action;
- an error exposes an exception, endpoint, credential, fingerprint, or token;
- a platform limitation takes more than one concise sentence beside the action;
- Desktop terminology is changed without a truthful product reason;
- review cites comments or tests without inspecting rendered strings and
  semantics.
