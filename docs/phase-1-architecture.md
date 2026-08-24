# Phase 1 — historical baseline

This file records the boundary at base commit
`d66b7eb1cec6faabb402c46d604e12cf76ee7d45`: chat used deterministic demo
sessions and SSH ended after a bounded probe. That is no longer the current
architecture.

The live Gateway transport, remote lifecycle, backend-authoritative sessions,
and residual limitations are documented in
[Phase 2 architecture](phase-2-architecture.md). Start there for current code.

The Phase 1 safety decisions remain in force: mandatory TOFU review, changed
host keys fail closed, one auth method per attempt, credentials remain
memory-only, UI-visible failures are redacted, and session cache updates merge
rather than clobber.

## Desktop theme font substitutions

Desktop may load the listed web fonts; Android bundles no webfont and makes no
runtime font request. The preset still records the requested role, then uses
the matching platform family:

| Desktop preset / font | Android result |
|---|---|
| `nous`, `github`, `nous-alt` / Courier Prime mono | Platform monospace |
| `midnight`, `slate` / JetBrains Mono | Platform monospace |
| `ember` / IBM Plex Mono | Platform monospace |
| `cyberpunk` / Courier for sans and mono | Platform monospace for both roles |
| `catppuccin`, `everforest`, `solarized`, `mono` / no typography override | Platform sans and monospace defaults |

`cyberpunk` is load-bearing: because Desktop assigns Courier to both roles,
Android also renders its body text with the monospace family.
