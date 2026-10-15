# Gateway compatibility target: 046094df with owner repair

Backend base: **046094df28b712f62a5e3c728a9e59da05dcbf1e** (tree
`b6b6156eccada464cb2838591e30805c306b5c82`) in `NousResearch/hermes-agent`.
Required bounded repair: **3fd44a954fb70a83ce3819aff96603babc625eaa** (tree
`8c86299b63a008177a5733fa205a8f4cba56e76f`), direct child of that base,
[upstream draft PR #139382](https://github.com/NousResearch/hermes-agent/pull/139382).
The contribution's live main base has advanced independently; testing this frozen
parent plus repair does not accept that newer main revision.

Vanilla046 remains broken for named-profile native settlement and named-profile
runtime.describe. Patched proof requires the exact repair above; it is not proof
that upstream main shipped the fix. Upstream maintainer review/merge and independent
Android review, current required CI and merged-main APK verification remain release
dependencies. No installed backend was updated; physical deployment remains separate.

## Scope and retained contracts

The existing consumer audit covered 284 Android files and 448 current upstream
files, 99 literals and 26 HTTP literal paths plus dynamic endpoint builders.
Literal matches are inventory, not execution evidence. The retained
[379 compatibility matrix](upstream-compatibility-379cf122.md) keeps its original
source pins and historical results. Current source comparison preserves these
consumer families:

- Remote public native authentication/tickets, Local static-token ownership and
  Managed SSH TOFU/private-process lifecycle; protocol selected by authenticated
  readiness, independent of connection route.
- Owner-qualified endpoint/profile/session cache identities across REST, canonical
  RPC and bundled plugins. Session attachments remain isolated to immutable owner
  channels; client channel provenance alone never grants server authorization.
- Canonical gui create, stored list, display resume/history, identified submissions,
  queued/unknown/terminal admissions, revision/generation-fenced mutations, branch
  child hydration and authoritative saved-row cuts.
- Replay/epoch/generation fences, full snapshot gap recovery, concurrent sessions,
  foreground/offscreen ownership and backend-authoritative cache merging.
- Staged clarification and distinct canonical/legacy/native-batch replies;
  image/file staging, bounded downloads/regeneration, scoped voice/wake,
  profile/project/model controls, existing Bots/groups/skills/MCP/PluginHost and
  host update/action receipts. No live update, provider or model was invoked.

## Demonstrated deltas

At base046, `gateway/session_automation.py:94-106` emits role-less live
message.start payloads for typed background completion. Android now reuses existing
TimelineEvent classification for process_complete and async_delegation_complete,
including display_text/report and authoritative row identity. Machinery envelopes
remain timeline events; ordinary missing-role assistant and genuine user text
retain their semantics. Production parsing/repository regression covers subsequent
assistant streaming, snapshot dedupe and stale/foreign-owner drops.

At base046, `tui_gateway/contracts/liveness.py:14-46` declares transport-wide
client.capabilities without profile. Android sends that closed shape unchanged on
named owner channels. Empty canonical root profile aliases normalize to the
implicit launch route; named profile identity remains distinct.

At repair3fd44a95, native sudo/secret/native CLI batch and local Desktop bridge
requests retain their creating authority, live session, epoch and execution
lifetime. Resolution projects only an existing verified host grant onto that exact
serving authority, validates session:respond and current generation, and refuses
restricted foreign owners, missing/retired/re-served/replaced/stale/cancelled/expired
requests. Atomic result publication precedes single-winner registry removal.
Direct legacy callers retain their contract. runtime.describe strips profile only
after trusted routing; unknown parameters remain rejected.

## Proof boundary

Upstream regressions use real public HTTP ticket middleware, gated WebSockets,
authority dispatch, native waiters and durable admission workers, with a synthetic
registered auth provider and inert admitted handler. Vanilla native RED remains
separate from descriptor RED. Repair GREEN proves terminal first and subsequent
admissions on two independent public sockets, exactly one next identified input,
and no pending/running work. A separate known-request-ID restricted-owner negative
is resolver authorization proof; it does not demonstrate a public transport leak.

The external Android gate uses actual ChatScreen/ChatViewModel and notification
handlers, live repository, profile/correlated RPC and OkHttp against isolated public
network backends. It observes real waiter results, terminal admissions, pending
release and next sends; it does not infer settlement from one-way frame emission.
Failed harness/setup attempts remain distinct from product RED. Routine Android CI
keeps source-shaped synthetic JVM/Compose tests and requires no Python installation.
Final frozen evidence receipts name both source trees, harness bytes, local gates
and APK hashes; they do not substitute for independent main shipment verification.

## Unsupported optional additions and visual scope

New Desktop preview/window/terminal actions, theme picker/tour/setup bridge events,
automation cards, live voice leases/STT/TTS streams and hosted-room media are not
Android ports. Unknown bridge requests receive bounded unsupported-method errors
on the original owner; error settlement grants no feature capability. Existing
legacy vault string prompts remain supported without claiming new authority vault
callbacks. Plugin sockets and third-party remote-code loading remain deferred.

Desktop UI/theme target stays `36922ad064d65dcf25f8f48df81e1ccf9a55de67`;
existing surface pins/captures remain historical. No UI source changed in this
continuation. Selected badge/row paint drift and decorative Enter omission remain
[issue #378](https://github.com/donovan-yohan/hermes-agent-android/issues/378);
settled tool-result rendering remains
[issue #380](https://github.com/donovan-yohan/hermes-agent-android/issues/380).
Current backend work does not close those visual or physical acceptance gaps.
