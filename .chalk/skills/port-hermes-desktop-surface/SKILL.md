---
name: port-hermes-desktop-surface
description: Use when translating any current Hermes Desktop UI or capability into this Android app. Enforces a pinned source contract, a rendered visual-contract packet, mobile-native adaptation without design-language drift, parity evidence, and learning capture.
---

# Port a Desktop surface to Android

Desktop is the reference implementation, not a loose mood board. Source and
tests define behavior. A screenshot plus computed styles defines the rendered
visual contract. A port needs both.

The full checklist is [`docs/workflows/port-desktop-surface.md`](../../../docs/workflows/port-desktop-surface.md).
Follow it; this file is the contract it enforces. A finished port is reviewed by
`review-desktop-parity`, which is where the rendered comparison and the
divergence classes become a pass or fail.

## Non-negotiables

1. **Pin first.** Record the upstream SHA before reading anything, and cite
   `path:line` against that SHA in every comment, doc and test you write. The
   read-only checkout is `~/.hermes/hermes-agent`; never write
   to it. A citation without a SHA is a citation to nothing.
2. **Read the code and its tests.** Upstream tests state the invariants prose
   omits. When a doc and the code disagree, the code wins.
3. **Capture the exact Desktop state before styling Android.** Use
   `scripts/capture-desktop-reference.mjs` to save a screenshot and computed
   typography/geometry packet from the running dev renderer. Capture the same
   state on Android with `scripts/capture-android-reference.py`, then build the
   side-by-side page with `scripts/build-visual-report.py`. Use synthetic
   data: screenshots must contain no host, fingerprint, credential, or private
   session content.
4. **Classify every piece of state before writing UI**, using Desktop's own
   authority model (`apps/desktop/AGENTS.md`, "Decide state by authority"):
   backend-authoritative, machine/runtime, connection-scoped, or UI-only. The
   Android home for each is in
   [`docs/phase-2-architecture.md`](../../../docs/phase-2-architecture.md).
   Backend-authoritative data goes through `SessionCache` and merges; it never
   clobbers.
5. **One-to-one is the default.** Preserve capitalization, typeface category,
   weight, tracking, icon family, visual icon size, control order, alignment,
   spacing rhythm, radii, color semantics, flatness, and hierarchy. Material
   defaults are not a neutral fallback. Deviate only for mobile interaction,
   available space, accessibility, or explicit priority; record each deviation
   beside the visual packet. Never call a missing control a mobile adaptation.
6. **Adapt mechanics, not the design language.** Replace hover, right-click,
   multi-window, and keyboard-first behavior with the native equivalent. Keep
   48dp touch targets around Desktop-scaled glyphs; the hit box may grow without
   making the visual icon louder.
7. **Consume semantic tokens only.** `HermesTheme.tokens`, never a preset name,
   never a raw colour. If a surface needs a colour the tokens do not have, add
   the token with its Desktop provenance; do not reach past the layer.
8. **Land parity evidence**, not assertions: source/test citations, Desktop and
   Android captures, the deviation ledger, Android tests, and exact commands.
9. **Evolve this skill.** After the port lands, update the workflow doc with the
   source paths that turned out to matter, the pitfalls you hit, and any step
   that proved useless — delete stale steps rather than appending a diary.

## Explicitly out of scope

Desktop JS plugins cannot run on Android (three independent blockers, recorded
in `docs/spikes/native-kotlin-ssh-client-scope.md` §8.1). Do not port the
plugin loader. Server-side plugin tools already arrive inside turns.

## Done means

- [ ] Upstream SHA recorded in the change, and every citation resolves against it.
- [ ] Desktop screenshot + computed contract, matching Android screenshot, and side-by-side report captured.
- [ ] Header labels, icon family/order, type treatment, spacing, and surface grammar compared explicitly.
- [ ] Every visual/interaction deviation has a mobile reason; omissions are named as omissions.
- [ ] State classified; nothing backend-authoritative lives in a ViewModel field.
- [ ] Components read only `HermesTheme.tokens` / `HermesTheme.type`.
- [ ] Content descriptions, 48dp targets, IME behaviour and reduced-motion
      safety checked on the new surface.
- [ ] `./gradlew check` green, including `verifyRepoInvariants`.
- [ ] Workflow doc updated with what this port taught.
