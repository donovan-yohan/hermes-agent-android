# Whole-session Branch

## Pin

Behavior inspected at `81a55437f3f31a9f12a30c53b6c5d49d420898aa`.
The historical menu visuals remain at their original pin in
[session-actions-menu.md](session-actions-menu.md); this page does not recertify them.

## Source contract

- `apps/desktop/src/app/session/hooks/use-session-actions/index.ts:2650-2669,2686-2719` @ `81a55437f3f31a9f12a30c53b6c5d49d420898aa`: route to the parent's owner, copy whole history server-side with `session.branch_whole`, and fall back only for a missing method.
- `tui_gateway/methods_session.py:2184-2193` @ `81a55437f3f31a9f12a30c53b6c5d49d420898aa`: live branching methods.
- `apps/desktop/src/app/session/hooks/use-session-actions.test.tsx:3637-3660` @ `81a55437f3f31a9f12a30c53b6c5d49d420898aa`: whole-history branch transport regression.
- `apps/desktop/src/api/canonical-protocol.ts:440-445` @ `81a55437f3f31a9f12a30c53b6c5d49d420898aa`: whole-history intent becomes a retained canonical branch mutation.
- `gateway/session_controls.py:769-790` and `hermes_state_mutation_branch.py:52-104` @ `81a55437f3f31a9f12a30c53b6c5d49d420898aa`: backend idle/revision guards, independent child identity and frozen policy, transactional transcript copy, exact receipt, then child subscription for first submit.

Android's existing runtime resolver and canonical protocol own identity/profile routing,
mutation revision receipts and backend cache publication. The menu now hands the
selected durable ID to that repository path, with no visible-transcript reconstruction.
Project previews retain their displayed `remoteProfile` through an endpoint-bound
owner-aware resume and branch; they never infer ownership from the global raw-ID slot.
The unresolved-attempt key and settlement removal use the resolved parent durable
identity, including a compression root resumed onto its tip. Compact drawer Branch
exits the current route and closes the drawer like selection; Bot Chat still disables it.
The ViewModel coalesces repeated presses until settlement and opens the child only
while endpoint and foreground selection still match the initiating context. Failure
uses the existing safe notice. The operation outlives a row leaving composition.

An unresolved whole-branch attempt survives same-endpoint reconnect as an explicit
uncertain outcome, not permission for another mutation. The new socket's canonical
adapter cannot reconstruct the old prepared revision/generation, so retry is refused
with a check-the-session-list notice. This conservative refusal applies to both
canonical and legacy transports until endpoint reset; it is not automatic receipt
reconciliation. Same-socket hydration retry retains its existing request identity.
`tui_gateway/methods_session_branch.py:84-99,126-139` and
`hermes_state_mutation_branch.py:64-86` @ `81a55437f3f31a9f12a30c53b6c5d49d420898aa`
establish why minting a new request after losing the receipt is not a safe retry.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Branch in the Work group | mobile-adaptation | Same label, glyph and order; enabled only when an owner callback exists | Touch uses the existing 48dp menu target; absent owner retains WIP rather than a dead enabled action |
| Whole-session branch from stored rows | mobile-adaptation | Existing repository resolves the parent's runtime first | Shared canonical/runtime identity path prevents a second branch implementation and preserves parent profile ownership |
| Lost receipt across reconnect | mobile-adaptation | Explicit uncertain-outcome refusal; inspect the session list, no blind second write | Repository tests replace both legacy and canonical sockets after an unresolved child attempt |
| Rendered live Branch states | drift | Fresh side-by-side evidence not captured | #72 owns exact-head surface acceptance; Concern, not visual acceptance |

## Visual report

- pending: #72

Evidence owner: [#72](https://github.com/donovan-yohan/hermes-agent-android/issues/72),
the existing exact-head surface/device acceptance gate. Feature scope remains in
[#73](https://github.com/donovan-yohan/hermes-agent-android/issues/73).

The existing menu reports remain historical. No device or emulator was operated
for this slice. Independent review and parent-owned rendered/device acceptance remain owed.

## Regression evidence

`SessionActionsMenuJourneyTest` exercises live dispatch and dismissal.
It also checks compact drawer dismissal/route exit and the Bot Chat guard.
`CachedProjectPreviewJourneyTest` holds alpha revalidation over a colliding beta
raw ID, then branches the alpha preview through the real ViewModel/repository/wire.
`ChatViewModelTest` exercises whole-history dispatch, duplicate-press coalescing and
late navigation isolation. `GatewaySessionRepositoryTest` exercises runtime resolution,
whole versus counted transport and cache publication. `CanonicalBranchRegressionTest`
covers canonical saved-row boundaries and lost-response retry identity.
The compression-root reconnect regression verifies that retrying through the resolved
tip refuses a second write. These are JVM/Robolectric behavior checks, not rendered
Desktop/Android parity or physical-device evidence; the visual verdict remains Concern.
