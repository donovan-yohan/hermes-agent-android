---
name: port-hermes-desktop-surface
description: Use when translating any current Hermes Desktop UI or capability (chat, sessions, plugins, Kanban, files, settings) into this Android app. Enforces a pinned upstream SHA, source-and-test inspection over screenshots, backend-contract classification, mobile-native adaptation, parity evidence, and a learning-capture step.
---

# Port a Desktop surface to Android

Desktop is the reference implementation, not a design mock. A port that reads
the screenshots and guesses the rules ships a lookalike that behaves wrong the
first time a turn streams, a session goes stale, or a background event lands.

The full checklist is [`docs/workflows/port-desktop-surface.md`](../../../docs/workflows/port-desktop-surface.md).
Follow it; this file is the contract it enforces.

## Non-negotiables

1. **Pin first.** Record the upstream SHA before reading anything, and cite
   `path:line` against that SHA in every comment, doc and test you write. The
   read-only checkout is `~/.hermes/hermes-agent`; never write
   to it. A citation without a SHA is a citation to nothing.
2. **Read the code and its tests.** Upstream tests state the invariants prose
   omits. When a doc and the code disagree, the code wins.
3. **Classify every piece of state before writing UI**, using Desktop's own
   authority model (`apps/desktop/AGENTS.md`, "Decide state by authority"):
   backend-authoritative, machine/runtime, connection-scoped, or UI-only. The
   Android home for each is in
   [`docs/phase-1-architecture.md`](../../../docs/phase-1-architecture.md).
   Backend-authoritative data goes through `SessionCache` and merges; it never
   clobbers.
4. **Adapt, do not transcribe.** Port the hierarchy, density, typography ratios,
   colour semantics, flatness and interaction grammar. Replace desktop
   mechanics — hover, right-click, multi-window, keyboard-first — with the
   native equivalent, and say in the PR what you replaced and why.
5. **Consume semantic tokens only.** `HermesTheme.tokens`, never a preset name,
   never a raw colour. If a surface needs a colour the tokens do not have, add
   the token with its Desktop provenance; do not reach past the layer.
6. **Land parity evidence**, not assertions: which upstream tests you read, what
   behaviour you reproduced, the Android tests that pin it, and the exact
   commands you ran with their results.
7. **Evolve this skill.** After the port lands, update the workflow doc with the
   source paths that turned out to matter, the pitfalls you hit, and any step
   that proved useless — delete stale steps rather than appending a diary.

## Explicitly out of scope

Desktop JS plugins cannot run on Android (three independent blockers, recorded
in `docs/spikes/native-kotlin-ssh-client-scope.md` §8.1). Do not port the
plugin loader. Server-side plugin tools already arrive inside turns.

## Done means

- [ ] Upstream SHA recorded in the change, and every citation resolves against it.
- [ ] State classified; nothing backend-authoritative lives in a ViewModel field.
- [ ] Components read only `HermesTheme.tokens` / `HermesTheme.type`.
- [ ] Content descriptions, 48dp targets, IME behaviour and reduced-motion
      safety checked on the new surface.
- [ ] `./gradlew check` green, including `verifyRepoInvariants`.
- [ ] Workflow doc updated with what this port taught.
