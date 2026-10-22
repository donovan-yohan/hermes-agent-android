# Deferred model picks: compatibility correction

## Pin

This bounded behavior audit uses Hermes upstream
`81a55437f3f31a9f12a30c53b6c5d49d420898aa`, inspected 2026-10-22.
It does not move any existing UI, theme or visual-capture pin, or assert whole
backend compatibility. This is compatibility finding F2, not a new feature.

## Contract and source

All citations in this section refer to the SHA above.

- `gateway/session_mutations.py:122-142`: a busy model mutation records next-turn
  intent, rather than changing the running turn's route.
- `gateway/session_deferred_pick.py:16-46`: the canonical receipt says
  `status: deferred`, with revision and generation. It is not an applied pick.
- `gateway/session_deferred_pick.py:92-136`: the boundary either commits, emits
  `confirmation_required` with the whole original payload and token, or emits
  `refused` with the committed selection.
- `apps/desktop/src/api/canonical-protocol.ts:601-607,684-703`: preserve the event
  token and translate the deferred receipt into the composer's legacy handshake.
- `apps/desktop/src/api/canonical-live-pick.test.ts:28-35,85-103`: deferred
  translation and exact confirmation payload, including reasoning.
- `apps/desktop/src/app/session/hooks/use-message-stream/gateway-event/deferred-pick.ts:36-89`:
  use the existing guarded-model dialog, restore committed selection on refusal,
  and send `session.model` only after explicit consent.
- `apps/desktop/src/app/session/hooks/use-message-stream/gateway-event/deferred-pick.test.ts:43-61,70-128`:
  refusal, decline, foreign profile, newer choice and combined-selection cases.

## Android authority and implementation

`CanonicalSessionProtocol` translates `status: deferred` to `deferred: true`.
Ordered canonical owner/session/replay/generation fences apply before accepting
an event-delivered model ask. A newer local target invalidates the old ask.
Confirmation consumes the captured whole payload and token once, through the
existing revision/generation-fenced `session.mutate` adapter. No value-pick
retry loop or non-idempotent write retry is introduced.

`LiveGatewaySessionRepository` publishes next-turn outcomes separately from
`session.info` effective controls. Its confirmation callback captures the exact
connection, endpoint generation and runtime owner. A refused pick clears consent
and projects the committed selection. Unknown raw refusal text is not displayed.

`ChatViewModel` reuses `ModelConsent`, the existing confirmation dialog and its
session/composer/endpoint callback fences. It keeps pending next-turn intent
separate from effective controls. The model pill displays that intent with its
existing deferred label. `session.info` continues to update effective controls;
it cannot be replaced by an optimistic deferred receipt. Declining sends nothing.
No model/provider execution, including Opus, is needed by these tests.

## Executable evidence

- `CanonicalGatewayRpcTest`: real correlated JSON-RPC peer through the live
  repository; canonical deferred receipt, no auto-confirm, exact payload/provider/
  reasoning/token, owner routing, one-shot consent and stale/foreign/older/refused
  ask rejection.
- `ChatViewModelTest`: next-turn intent is separate, explicit consent once,
  effective `session.info` during an open dialog, refusal revocation, decline,
  foreign session and navigation fencing.
- Existing repository, protocol and ViewModel regressions remain part of the
  focused gate. Exact executed results belong to the change's test receipt, not
  inferred from the existence of these tests.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Guarded next-turn pick uses the shared model confirmation dialog | mobile-adaptation | Reuses the existing touch dialog and opaque one-shot callback; no new confirmation surface | Session/composer/endpoint fences and whole-selection canonical adapter |
| Named raw refusal reason reaches Desktop's notification stack | mobile-adaptation | Existing model-control error text, with committed selection restored | The app uses bounded, redacted product copy rather than raw backend errors |

## Visual report

- pending: #73

The existing migration/evidence owner is
[#73](https://github.com/donovan-yohan/hermes-agent-android/issues/73).
No renders or device operations were performed for this patch. Visual acceptance
remains Concern; this page does not approve pixels or close that issue. The
source-drift audit remains tracked by
[#266](https://github.com/donovan-yohan/hermes-agent-android/issues/266).
