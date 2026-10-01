# Persisted terminal-turn metadata

## Source and scope

Inspected upstream `e05b16348b1d06a3311237423b0a4fc30d9c5aa1`:

- `apps/desktop/src/lib/chat-messages/hydration.ts:180-208`: object or JSON-text
  display metadata; failed-turn cards require a valid error surface.
- `apps/desktop/src/lib/chat-messages/hydration.ts:223-224,613`: strict boolean
  interruption on assistant rows, without local Stop attribution.
- `apps/desktop/src/lib/chat-messages/hydration.ts:580-592`: a synthetic error
  precedes the failed-turn boundary and consumes zero server rows.
- `apps/desktop/src/lib/chat-messages/reconciliation.ts:333-357`: error-code
  deduplication is limited to the tail turn, not the whole conversation.
- `apps/desktop/src/lib/error-surface.ts:100-137`: a recognised layer licenses
  the descriptor; optional malformed fields fall back rather than gaining trust.
- `agent/conversation_loop.py:1748-1764,1824-1829`: the persisted assistant
  boundary carries redacted failure metadata.

Existing surface pins and rendered evidence remain unchanged. This is a data
projection change, not a new visual-parity claim.

## Android adaptation

REST and RPC history use the same metadata parser. A blank valid failure
boundary survives REST projection. The synthetic card has a deterministic
rendering key derived from its boundary but **no durable row address**; paging
continues to count the original REST rows, and regenerate still addresses the
preceding user row. Duplicate page occurrences use the existing rendering-key
merge, while distinct boundaries with the same provider error code stay distinct.

Error summaries use the existing safe product copy and details use the existing
bounded redaction path. Invalid metadata does not create an error card. Strict
`interrupted: true` creates only `InterruptedExternally`; it never fabricates a
local Stop. The live Stop attribution path is unchanged, and redirect does not
send or imply Stop.

Android continues to refresh authoritative history; no Desktop replay mechanism
was added. A retained inflight failure is omitted only when its tail user and
error code already match the hydrated tail failure.

## Verification hand-off

Tests were authored with Gradle execution explicitly deferred to the coordinating
worker. No JVM RED/GREEN result, Android build, device capture, or visual parity
is claimed by this implementation commit.

Requested focused checks:

```sh
./gradlew :app:testDebugUnitTest \
  --tests '*PersistedTurnMetadataTest*' \
  --tests '*RestTranscriptProjectionTest*' \
  --tests '*GatewaySessionRepositoryTest*' \
  --tests '*ChatViewModelTest*' \
  --tests '*TranscriptBackfillTest*' \
  --tests '*RegeneratePlanTest*'
./gradlew check
```

The regression cases cover strict interruption, object/string metadata,
blank failures, invalid descriptors, redaction, address-free synthetic cards,
page overlap and tail grafting, live error refresh/reopen, retained error dedupe,
redirect attribution, and completion-before-wire correction rejection with a
second live session. ViewModel rejection retains the unsent correction draft.
