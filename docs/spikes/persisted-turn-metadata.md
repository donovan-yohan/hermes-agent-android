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
was added. The new retained-overlay prompt/code heuristic has been removed.
Retained failure overlays remain visible even when the prompt and error code
match a persisted failure. This can replay a same-occurrence error and prompt;
that ambiguity is **not fixed** by this hydration deliverable.

## Conservative visibility and protocol limitation

At the pinned upstream revision, `tui_gateway/session_history.py:413-454`
keeps the original `user`, appends separate `corrections`, and retains failure
state until another turn starts. The source audit found no authoritative link
from retained failure to persisted boundary, no atomic history/live snapshot,
and no persistence receipt. Prompt equality (including a repeated identical
prompt), error-code equality, correction text, and missing user text are not
occurrence identity. A later failure may arrive before its user/boundary is saved.

Executable repository tests now assert conservative visibility, not successful
same-occurrence deduplication: both errors remain visible for an omitted REST-tail
user, a persisted correction, and an ambiguous matching prompt. The REST case
still exercises older-page loading and raw-row offset 120. A separate regression
first hydrates an older failure, observes a later running turn with the identical
prompt, then restores its failure while history remains unchanged; both errors
and both prompt occurrences must survive. Boundary-identity overlap/tail-merge
tests remain unchanged: those have real persisted identity, unlike overlays.

## Future protocol acceptance — not current passing tests

The two impossible deduplication expectations from `972b144b` are relocated here,
not disabled with `@Ignore` or claimed fixed by revised assertions:

1. **User outside REST tail:** with a 120-row page (assistant rows 101–219,
   failed boundary 220, original user 100 outside the page), a retained failure
   authoritatively linked to boundary 220 must not replay its prompt or error.
   Expect one error and no user in the initial tail; older-page loading still
   requests raw offset 120 and hydrates user row 100.
2. **Persisted correction:** with original user 39, correction 40, failed boundary
   41, and retained original user plus correction, an authoritative link to 41
   must yield one error and exactly the two persisted users, without replay.

Both require a protocol-supplied shared occurrence/boundary identity or an
explicit, verified atomic ordering/receipt contract before executable acceptance
can honestly demand dedupe. Any future implementation must also retain the later
unpersisted identical-prompt/code failure. Do not infer the link from text/code.

Historical RED was run in an isolated base worktree at `651d659b` with the
original new tests transplanted: 462 tests, 8 failures, no compilation failure.
The existing different-turn/same-code regression remains intact.

## Verification hand-off

Verified source head: `6a5792e0952cb84b1080bb44b05c47d066fe68ad`.
The publication follow-up changes only this evidence document; it does not change
the tested or independently reviewed source. The coordinating worker supplied
bounded independent approval and completed the Gradle runs. Publication inspected
the saved logs/count summaries and hashed the existing APK without running Gradle:

- Focused run: 530 tests, zero failures/errors/skips; `BUILD SUCCESSFUL`.
- Full run: 3,342 debug tests and 2,651 release tests, zero failures/errors;
  one `LiveGatewaySmokeTest` skipped in each variant; `BUILD SUCCESSFUL`.
- Debug APK SHA-256:
  `d51ba8f26b31f52adc2e45693288b4c8f157e2465e0c42533f048912f0ef5f71`.
- Local coordinating evidence: `metadata-6a5792e0-focused.log`,
  `metadata-6a5792e0-focused-counts.json`, `metadata-6a5792e0-full.log`, and
  `metadata-6a5792e0-full-counts.json` in the coordinating profile's scratch area.
- Publication parity-evidence check: all 41 parity pages pass. No production
  `app/src/main/kotlin/.../ui/` paths changed; the ViewModel change is test-only.
  Existing surface pins/reports remain unchanged. No new device capture or
  rendered visual-parity claim is made, and normal UI parity gates remain intact.

Approval covers persisted interruption and validated failed-turn metadata only,
not retained-overlay same-occurrence deduplication. Ambiguous duplicates remain
visible because hiding a later unpersisted failure would be worse. GitHub checks
and reviews are separate, exact-publication-head gates; approval is not a claim
that hosted CI has passed. No merge is authorized by this publication.
The existing commits are preserved without reset/rewrite, and the original WIP
worktree is untouched.

Focused-check command reference:

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
page overlap and tail grafting, live error refresh/reopen, conservative retained
failure visibility (not same-occurrence dedupe),
redirect attribution, and completion-before-wire correction rejection with a
second live session. ViewModel rejection retains the unsent correction draft.
