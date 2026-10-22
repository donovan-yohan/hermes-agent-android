# Session Export

## Pin

Behavior inspected at `81a55437f3f31a9f12a30c53b6c5d49d420898aa`.
Historical menu visuals in [session-actions-menu.md](session-actions-menu.md)
retain their original pin and are not recertified here.

## Contract

- `apps/desktop/src/lib/session-export.ts:13-57` @ `81a55437f3f31a9f12a30c53b6c5d49d420898aa`: JSON envelope, full transcript retrieval, native download destination and safe outcome.
- `apps/desktop/src/hermes.test.ts:577-612` @ `81a55437f3f31a9f12a30c53b6c5d49d420898aa`: bounded oldest-first pages include compacted history; memory exhaustion refuses rather than returning a partial success.
- `hermes_cli/web_routers/sessions.py:739-783` @ `81a55437f3f31a9f12a30c53b6c5d49d420898aa`: authenticated owner-profile messages route, resolved lineage ID, explicit oldest order, paging envelope.
- `apps/desktop/src/i18n/en.ts:4920-4921` @ `81a55437f3f31a9f12a30c53b6c5d49d420898aa`: Session exported and Could not export session, kept verbatim in platform notices.

The existing REST transport owns credentials, URL and response byte bounds. Export
captures one transport, identity mapper and row owner before its first suspension;
it checks the connection before and after each page. Every page must match its
requested offset/order/limit and the same resolved session ID. A complete JSON
payload is prepared before asking Android for a destination. The document destination's
phone path or URI is never injected into a transcript request or exported metadata.
Cancelling the picker writes nothing. A denied/unwritable destination reports failure
without raw exceptions; a provider failure can leave an incomplete destination document,
never a reported success. The prepared mutable output buffer is cleared after
write/cancel/disposal (not a claim of whole-process memory erasure). No persisted
URI grant, intermediate app file, broad storage permission or sharing upload is used.

Only an opaque picker operation id enters saved instance state. Activity recreation
wipes the in-memory transcript and a delayed Save reports failure, rather than silently
creating an empty document. Session changes abandon the buffer; endpoint/owner validity
is checked before opening the provider stream. An abandoned picker's callback must drain
before another export can launch, so it cannot consume a newer operation's bytes.
Write-job completion owns wiping even if cancellation occurs before the body starts.
A refused/recreated save may leave the document created by the external provider empty;
Android does not claim provider cleanup or successful export in that case.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Browser JSON download | mobile-adaptation | Android CreateDocument with application/json and a neutral session.json suggestion | Native document provider gives the user control of the destination without leaking a phone path to the Gateway |
| Bounded whole transcript retrieval | mobile-adaptation | 100-row pages, 10,000-row and 8MiB total bounds; oversize refuses, never a truncated success | Phone memory is bounded; safe-load behavior follows Desktop's regression contract |
| Toast outcome | mobile-adaptation | Platform short success/failure notice | Native document flow returns to the originating surface; raw provider/transport errors are never displayed |
| Delayed external picker lifecycle | mobile-adaptation | Recreated or stale exports refuse before write, with no saved transcript | Real Compose launcher/ComponentActivity registry recreation and stale-session/endpoint tests; cancellation-before-start cleanup test |
| Current live Export states | drift | New rendered side-by-side packet pending | #72 exact-head acceptance; Concern, not visual approval |

## Visual report

- pending: #72

Evidence owner: [#72](https://github.com/donovan-yohan/hermes-agent-android/issues/72).
Feature scope: [#73](https://github.com/donovan-yohan/hermes-agent-android/issues/73).
No device/emulator operations were performed in this implementation lane.
Parent-owned acceptance must exercise document picker success, cancel and refusal.

## Tests

`SessionExportTest` covers ordered multi-page completeness, envelope shape,
wrong offsets, changing lineage, endpoint invalidation, row/byte exhaustion.
Existing REST tests cover owner scoping and bounded authenticated transport.
`GatewaySessionRepositoryTest` drives owner-scoped compacted oldest retrieval through
the production REST client. `SessionExportDestinationTest` verifies exact bytes,
stream closure and refusal propagation. `SessionExportContractTest` verifies the
native JSON CreateDocument intent and cancel result; Compose tests verify dispatch.
`SessionExportLifecycleTest` additionally drives the actual composed control and
ActivityResult registry through recreation and delayed stale callbacks under Robolectric.
This is JVM lifecycle regression evidence, not rendered/device acceptance.
