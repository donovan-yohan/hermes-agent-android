# Turn error recovery and support actions

## Pin

Contract pin: `437116f9497c80d242ce034ff7f5d81dc277a337` (the AGENTS.md pin).
Source references recorded in the implementation:
- `apps/desktop/src/components/assistant-ui/thread/assistant-message.tsx:243-264,590-627`: recovery action order and tint.
- `apps/desktop/src/lib/error-surface.ts:42-71`: advisory failure descriptor.
- `tui_gateway/methods_config.py:342-375`: `diagnostics.share_nous`.
- `hermes_cli/web_routers/status.py:720-754` and `hermes_cli/logs.py:17-26,167-209`: backend logs.

The user-supplied newer Desktop screenshot has unknown revision. Its heading,
Details disclosure and dismissal are the requested presentation, not evidence
of a pinned renderer. A fresh verbatim source/copy/glyph comparison is owed
in #325; the reference citations above are not a claim that comparison was completed.

## Current behavior and privacy

The error panel preserves failure metadata across live terminal events and
retained-failure activation. Details and clipboard text are redacted before
bounding. Retry uses durable history when available; a missing row is only
resubmitted after authoritative `agent_init_failed` validation. Native image
references are re-staged, and endpoint generation fences protect retry dispatch.
Dismissal is presentation-only and never deletes backend history.

View Gateway logs asks for confirmation before fetching a bounded errors-log
excerpt. This is operator/backend-wide data, not session- or profile-scoped.
Navigation, profile, connection and endpoint identity changes discard retained
results. The current transport is resolved at confirmation, not VM creation.
Send diagnostics requires fresh per-upload consent checked again at wire
handoff; it never probes support by uploading. Unsupported/refused/failed states
are explicit, and only validated Nous portal links may be opened. Both dialogs
are ephemeral. No real private logs were read and no diagnostics uploaded in QA.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Compact single action row | mobile-adaptation | Outlined actions wrap inside touch-sized targets | Phone viewport and touch access require larger hit areas; relative Retry, logs, diagnostics, copy order is retained |
| Open Desktop logs | mobile-adaptation | View Gateway logs with backend-wide warning and explicit consent | Android cannot open the Desktop filesystem; the remote backend is the relevant log source and needs a separate privacy boundary |
| Desktop diagnostics action | mobile-adaptation | Explicit confirmation sheet and ephemeral upload receipt | Remote host diagnostics can contain data beyond the visible phone session; fresh consent precedes the mutating RPC |
| Newer heading, Details and dismissal | mobile-adaptation | Screenshot-directed newer presentation alongside pinned recovery order | Explicit user-requested mobile priority; supplied Desktop screenshot has unknown revision, not pinned parity evidence |
| Switch provider | omission | Conditional disabled WIP action | coming soon; shown for auth, billing, endpoint and provider failures |
| Conditional OAuth and ownership recovery controls | omission | Dedicated controls not implemented in this panel | pill-owed: #325; existing sign-in and ownership flows elsewhere do not prove error-panel parity |
| Summary recommends Desktop diagnostics | drift | Some safe summaries still recommend Desktop although native consent-gated diagnostics now works | #325; safe fallback guidance is retained but copy needs reconciliation |
| Current support dialogs and exact glyph/copy parity | drift | Functional dialogs have synthetic tests but no current side-by-side capture | #325; historical packet cannot establish final-head visual equivalence |

## Visual report

- pending: #325

Historical packet: [turn-error-recovery/report.html](visual/turn-error-recovery/report.html).
Android images are from an uncommitted build based on
`d628e28e32aca91b009338b4e3645e58b5cbf7e8`; exact capture-build digest unknown.
They predate the enabled support actions and are labelled accordingly. No image
has been retouched to look current. The synthetic MediaStore probe in that
packet proves a readable seeded image, not full composer rail parity (#277).

Verdict: **Concern**, not full visual approval. JVM/Compose tests exercise
redaction, retry dispatch, consent, stale-result fencing and dialog states with
fake transports. Instrumented CI is the existing emulator lane, not a live
Gateway support workflow or physical-device acceptance. System panel logs and
background/update-contract checks remain with #127.
