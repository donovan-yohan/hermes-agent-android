# Gateway compatibility target: unmodified 046094df

Backend base: **046094df28b712f62a5e3c728a9e59da05dcbf1e** (tree
`b6b6156eccada464cb2838591e30805c306b5c82`) in `NousResearch/hermes-agent`.
This is the unmodified backend target. No repair, backend deployment or maintainer
merge is required. The prior upstream contribution
[PR #139382](https://github.com/NousResearch/hermes-agent/pull/139382) was closed;
its `3fd44a954fb70a83ce3819aff96603babc625eaa` paired results remain historical only.
Exact-head Android review, required CI and merged-main APK verification remain
separate delivery gates. Physical deployment remains separate.

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

Stock root runtime.describe accepts empty parameters. Named profile routes use
public scoped profiles.list: only its current serving authority row supplies
provisional identity; discovered entries grant no owner access. Canonical snapshot
info.profile_id must match before state adoption. Non-local snapshots without it
require actual same-profile/session canonical session.info confirmation first. Unknown/refused routes do not
fall back to the launch owner, and Android never fabricates a server descriptor.

Stock native id-only replies authorize against the launch authority. For another
authority Android retains the input, refuses answer callbacks and shows **Reply
unavailable** with **Stop**. The action uses the captured owner and execution
generation through session.interrupt. An accepted stop marks the card as stopping;
backend cancellation removes it. Actual terminal backend state and client idle
state must precede the next send. Root-owner native sudo/secret/batch retain their
reply path; aliases are compared by authoritative owner identity. Named canonical
approval/clarification remain supported. Existing endpoint/owner/stale/duplicate
fences and secret wiping still apply.

## Proof boundary

The external stock gate uses actual ChatScreen/ChatViewModel and notification
handlers, live repository, profile/correlated RPC and OkHttp against isolated
unmodified public-network backends. A synthetic registered auth provider and inert
admitted runner exercise real HTTP tickets, WebSockets, authority dispatch, native
waiters and durable admissions. Supported replies and unsupported cancellation
are distinct outcomes. Frame emission or local removal alone is not settlement.
Failed setup/harness attempts remain separate from product RED. Earlier repaired-
backend positives are historical, not stock acceptance. Routine Android CI needs
no Python installation. Frozen receipts bind source trees, harness bytes, local
gates and APK hashes; they do not substitute for independent main shipment proof.

### Executed stock continuation

The retained `kani-stock046/stock-green-4` source manifest and execution receipts
bind this continuation to unmodified046. Android and both isolated public backends
exit0: 24 terminal admissions total, no native/canonical pending inputs or running
executions at completion. Actual app Stop cancels unsupported named native and
bridge waiters, preserves an equal-ID root prompt and allows the next send. Root
native sudo and full multiselect/null CLI batch, named choice/text clarification,
notification helper answers, held ACK, two-frame same-prompt/generation retry with
one admission, reconnect and two-endpoint callback fences pass. Canonical approval
retains its production-path regression coverage; this network journey does not
add an approval-waiter claim. Affected Debug116/Release67 tests pass separately.
Failed stock/harness attempts remain recorded separately. Required current CI and
independent review/merged-main artifact verification are still delivery gates.

## Unsupported optional additions and visual scope

New Desktop preview/window/terminal actions, theme picker/tour/setup bridge events,
automation cards, live voice leases/STT/TTS streams and hosted-room media are not
Android ports. Unknown bridge requests on the launch authority receive bounded unsupported-method
errors; other-owner requests use the explicit Stop recovery above. Neither path
grants feature capability. Existing
legacy vault string prompts remain supported without claiming new authority vault
callbacks. Plugin sockets and third-party remote-code loading remain deferred.

Desktop UI/theme target stays `36922ad064d65dcf25f8f48df81e1ccf9a55de67`;
existing surface pins/captures remain historical. The minimal stock limitation/recovery card reuses existing tokens and Stop
control; its divergence is a mobile adaptation. Selected badge/row paint drift and decorative Enter omission remain
[issue #378](https://github.com/donovan-yohan/hermes-agent-android/issues/378);
settled tool-result rendering remains
[issue #380](https://github.com/donovan-yohan/hermes-agent-android/issues/380).
Current backend work does not close those visual or physical acceptance gaps.
