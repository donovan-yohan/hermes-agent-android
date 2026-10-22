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
- `gateway/session_deferred_pick.py:128-136`: refusal supplies only `target_model`,
  not target provider/reasoning, request id or rejection revision. Its `provider`
  describes the committed selection. It cannot correlate a same-model refusal
  to a newer local provider/reasoning pick; the client must not invent that identity.
- `gateway/session_events.py:29-35,56-63,77-89`: epoch rollover, overflow and
  missing replay require a new snapshot. `gateway/session_deferred_pick.py:49-59,78-83`
  re-checks a guarded pick on attach and mints fresh consent rather than replaying it.
- `hermes_cli/model_selection_guards.py:120-128,159-161`: the large-context warning
  includes the uncached full-price input consequence; several guard warnings are
  concatenated. Clipping the end could hide a different guard.
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
connection, endpoint generation and runtime owner. An unambiguous refusal with no
local pending intent clears consent and projects the committed selection. A refusal
without target correlation cannot erase local pending intent, even when its model
label matches. Its token is revoked, but the incomplete refusal is not presented as
the outcome of the newer choice. Unknown raw refusal text is not displayed.
The latest local whole selection remains a connection-owned supersession tombstone
after matching `session.info`, so a delayed refusal cannot repaint an already
committed newer provider/reasoning choice either. This is not a second effective model.

Replay gaps, replay-epoch changes and authority-epoch changes revoke protocol consent
immediately, before asynchronous resume. The repository also invalidates the open
dialog. A consent version captured when the token is consumed is checked under the
same protocol lock at actual `wire.send` admission. A gap that wins that lock prevents
the confirmed frame leaving, including while resume is held or fails. Consent stays
unavailable until a non-stale authoritative snapshot and fresh ask establish it again.
Runtime/profile/endpoint ownership is also checked at the repository dispatch boundary.
The existing synchronous guarded-model exchange uses the same revocable dispatch
lease and consumes its token before sending. An uncertain timeout does not retain a
"sent" confirmation for an automatic confirmed retry. Re-choosing asks afresh;
neither exchange automatically confirms or retries an expensive model change.

The producer contract cannot establish which same-model pending pick was refused.
No request/revision correlation is fabricated. Pending intent can therefore remain
until matching authoritative selection arrives or the person chooses again; it is
not evidence of successful application. This producer limitation remains a Concern
under the existing source-drift owner #266.

`ChatViewModel` reuses `ModelConsent`, the existing confirmation dialog and its
session/composer/endpoint callback fences. It keeps pending next-turn intent
separate from effective controls. Confirmed event asks that are deferred again retain
their captured target as pending, not the old effective selection. The model pill displays that intent with its
existing deferred label. `session.info` continues to update effective controls;
it cannot be replaced by an optimistic deferred receipt. Declining sends nothing.
No model/provider execution, including Opus, is needed by these tests.

Confirmation prose is redacted and capped at 4096 characters by admission, not
truncation: normal warnings are shown in full; an oversized warning offers no native
confirmation and directs the person to Desktop. This preserves all combined guard
meaning instead of hiding a warning behind an ellipsis. The displayed target label
is redacted/bounded separately; the exact provider/reasoning payload stays untouched.

## Executable evidence

- `CanonicalGatewayRpcTest`: real correlated JSON-RPC peer through the live
  repository; canonical deferred receipt, no auto-confirm, exact payload/provider/
  reasoning/token, owner routing, one-shot consent and stale/foreign/older/refused
  ask rejection, same-model provider/reasoning supersession, held/failed resume,
  final-send revocation, synchronous one-shot consent, timeout/malformed one-shot
  outcomes and full/redacted/oversized warnings.
- `ChatViewModelTest`: next-turn intent is separate, explicit consent once,
  effective `session.info` during an open dialog, refusal revocation, decline,
  foreign session and navigation fencing, deferred confirmation target and immediate
  dialog invalidation while recovery is unresolved.
- Existing repository, protocol and ViewModel regressions remain part of the
  focused gate. Exact executed results belong to the change's test receipt, not
  inferred from the existence of these tests.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Guarded next-turn pick uses the shared model confirmation dialog | mobile-adaptation | Reuses the existing touch dialog and opaque one-shot callback; no new confirmation surface | Session/composer/endpoint fences and whole-selection canonical adapter |
| Named raw refusal reason reaches Desktop's notification stack | mobile-adaptation | Existing model-control error text, with committed selection restored | The app uses bounded, redacted product copy rather than raw backend errors |
| Refusal identifies only the target model label | drift | Do not erase a newer local provider/reasoning intent using an uncorrelated refusal | #266; upstream `gateway/session_deferred_pick.py:128-136` at the inspected pin has no target provider, reasoning, request id or rejection revision |
| Combined guard warning is displayed in full | mobile-adaptation | Full redacted warning up to 4096 characters; longer warnings disable native consent and direct review to Desktop | A phone dialog must remain bounded without truncating required cost, data-policy or context warning meaning |

## Visual report

- pending: #73

The existing migration/evidence owner is
[#73](https://github.com/donovan-yohan/hermes-agent-android/issues/73).
No renders or device operations were performed for this patch. Visual acceptance
remains Concern; this page does not approve pixels or close that issue. The
source-drift audit remains tracked by
[#266](https://github.com/donovan-yohan/hermes-agent-android/issues/266).
