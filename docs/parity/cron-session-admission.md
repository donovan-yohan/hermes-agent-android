# Cron execution-session admission parity

Upstream pin: `e05b16348b1d06a3311237423b0a4fc30d9c5aa1`.

Contract: `apps/desktop/src/app/cron/open-cron-run.ts:14-52,97-124` and `apps/desktop/src/app/session/hooks/use-prompt-actions/submit.ts:292-319`. Android implementation and executed test boundaries: [admission report](../spikes/cron-session-admission-boundary.md).

## Visual report

- pending: #194 — the Bot/session parity follow-up owes a paired rendered view-only run and pending/refused draft journey. The JVM ViewModel tests prove the existing notice surface receives the reason and the draft survives admission; they are not a rendered Desktop/Android comparison. No new layout, icon or theme token is introduced.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Native runtime admission | mobile-adaptation | Pre-resume gate | Android must refuse `session.resume`/`session.activate` as well as prompt submission because opening a runtime is a writable operation. Stored REST messages remain readable without that runtime. See the [repository tests and boundary report](../spikes/cron-session-admission-boundary.md). |
| Named owner and exact response identity | mobile-adaptation | Scoped detail | Unified mobile sessions can share IDs across profile databases. Detail requires an exact explicit profile, captures endpoint transport, and rejects mismatched or unstamped detail responses. No fallback to another profile. |
| Invalid activity timestamps | mobile-adaptation | Finite-only numeric fallback | Nonfinite and quoted timestamps cannot establish activity; literal JSON booleans retain Desktop's ownership/activity precedence. Fixed-clock policy tests cover the fallback and boundary. |
| Read-only explanation | mobile-adaptation | Existing native chat notice | Uses the existing Android chat notice; unknown lookup is distinguished from a known view-only execution. Desktop admission behavior is ported, but this native explanation is not claimed as verbatim Desktop copy. Rendered review remains pending: #194. |
| Client-side ownership race | mobile-adaptation | Observed-state fencing only | There is no upstream expected-ownership token for RPC. The client fences observed admission, selection, profile and endpoint state, but cannot retract an admitted frame or detect an unseen scheduler transition. This is an API availability limit, not a guessed liveness capability. |
