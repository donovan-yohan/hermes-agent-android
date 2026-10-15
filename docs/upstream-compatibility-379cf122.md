# Gateway compatibility target: 379cf122

Backend source target: **379cf1220fe289b5ecfe8bcf61cd4d5126e140cd** in
`NousResearch/hermes-agent`. Every upstream path:line below refers to that exact
SHA. The target was inspected in a dedicated checkout; the personal Hermes
reference was not modified. Android fixtures are public synthetic data derived
from actual serializers/contracts, never presented as live server output.

Desktop UI/theme target remains `36922ad064d65dcf25f8f48df81e1ccf9a55de67`.
Existing surface pins, source line ledgers and rendered reports retain their
original provenance. Twelve built-ins match the frozen backend target without
registry/palette changes; this does not certify custom-theme parity.

## Status

Implementation and selected RED/GREEN checks exist; full compatibility/release
is **blocked** by the public named-profile native response boundary below.
Independent exact-head CODE/product QA, required CI, merged-main APK proof and
physical deployment are separate gates. Changed input visuals remain
[pending: #378](https://github.com/donovan-yohan/hermes-agent-android/issues/378).
No backend update, new provider/model purchase or physical install is implied.

## Existing consumer matrix

| Family | Upstream source at the frozen SHA | Android adaptation / retained boundary |
|---|---|---|
| Remote native auth, WS tickets; Local static token; Managed SSH | `hermes_cli/dashboard_auth/routes.py:289,556-614`; `hermes_cli/web_server_chat.py:201-327`; `hermes_cli/main.py:2517-2540,2680-2729` | Existing PKCE/refresh/URL-bound token and mandatory TOFU/lifecycle machinery retained. Protocol selection follows authenticated readiness, not route/version. No private-ticket discovery. |
| Canonical protocol and owner identity | `tui_gateway/ws.py:309-320,427-444`; `gateway/session_controls.py:345-418`; Desktop `apps/desktop/src/api/canonical-protocol.ts:6-10` | Confirm owner via runtime.describe. Endpoint/owner/raw-ID cache projection shared by REST/RPC/plugins; raw IDs stay on the wire. Lazy profile channels isolate client provenance and single attachments. Server-native settlement limitation remains below. |
| Create, list, resume, display history | `gateway/session_controls.py:276-335,362-473`; `tui_gateway/contracts/canonical_sessions.py:17-51,120-168` | gui create with stable request ID/options; stored list scope; resume replaces obsolete activate/history calls and preserves durable binding, revision/generation/replay/subscription. REST pagination/search/archives retained. |
| Submit, queue, unknown work | `gateway/session_controls.py:506-574`; `tui_gateway/contracts/canonical_sessions.py:183-216` | Logical submission IDs, exact owner receipts, queued/started/unknown/terminal reconciliation; no auto replay of unknown accepted work. Discard uses lost admission's generation, cancel uses queued ID. |
| Session edits, branch, rewind, model controls | `gateway/session_mutations.py:69-135,210-289`; `gateway/session_tui_config.py:171-199,227,310-353`; Desktop `api/canonical-protocol.ts:110-186` | Revision/generation-fenced mutations, retained ambiguous request identity, saved-row rewind before resubmit. Explicit model warning/token consent; fast/approval configuration keeps existing owner semantics. WIP session-menu features remain WIP. |
| Persisted-row rename/sidebar/delete | `hermes_cli/web_routers/sessions.py:583-597,843-874,923-931`; `hermes_cli/web_server_sessions.py:182-219` | Authenticated mutation snapshot before write; exact request/revision/generation fences retained across same-endpoint/profile reconnect retry. Only explicit absent-route envelope permits legacy writer; 403/409/503/bare 404 do not. Tombstones come from confirmed deleted_ids. |
| Live events and reconnect | `gateway/session_events.py:29-87`; `gateway/session_controls.py:498-504` | Owner/epoch/sequence/backend-generation fences, duplicate/stale filtering, retired-epoch handling and snapshot gap reconciliation. Unstamped idle metadata and queued cancellation remain valid without inventing an execution. |
| Approval/clarify and existing native input | `gateway/session_controls.py:581-610`; `gateway/run_turn_runner.py:502-539`; `gateway/session_clarify_batch.py:16-38`; `tui_gateway/server_requests.py:308-326`; Desktop `apps/desktop/src/api/client.ts:120-123,390-395` | Canonical event/snapshot prompt IDs and generation → profile-routed respond. Batch cards stage before one confirmation. Legacy sequential locks use nullable skip; canonical one-card skip maps empty answer; native authority batch uses one answers/outcome response. GUI never forces clarify_batch. Captured callback identity releases correct session and next send. |
| Images/files/download | `gateway/session_images.py:8-44`; `gateway/session_ancillary.py:135-164`; `hermes_cli/web_routers/files.py:934` | Image staging path/MIME becomes explicit submit attachment. Regenerate downloads bounded authenticated bytes and restages, then rewinds/submits; buffers wipe. No canonical image.attach or remote unlink fallback. File refs retain existing path. |
| Voice/wake | `hermes_cli/web_routers/audio.py:86-146,293-348`; `config_env.py:80-92,117-155`; `tui_gateway/methods_voice.py:468-604` | Existing transcription/speak shape; profile captured before recording/read-aloud plus endpoint/current checks. Silence remains successful empty transcript. Existing wake service/permission ownership retained. |
| Profiles, projects, model inventory/completions | `gateway/session_group_controls.py:40-76`; `gateway/session_config.py:50-159`; `gateway/session_complete_path.py:15`; `tui_gateway/methods_profiles.py:282-450`; `methods_projects.py:91` | Existing roster/configure/assets, options, project-authoritative membership/cwd and completions retained through owner-qualified shared doors. No Android filesystem guesses. |
| PluginHost/Bots/cron/groups/Kanban/Skills/MCP | `gateway/session_group_controls.py:9-41,109-130`; `tui_gateway/methods_tools.py:1292`; `hermes_cli/web_routers/skills.py:343-395`; `mcp.py:94-108,372-396`; `plugins/kanban/dashboard/plugin_api.py:359-409` | Shared canonical adapter covers PluginHost session calls. Bots validates committed rename directly, no invented pending flag or redundant mint. Existing group send operation/reconciliation and capability gates, routine actions, Kanban reads, scoped skill/MCP readback retained. Bundled-only SDK unchanged. |
| Host status/update/log/git/theme routes | `hermes_cli/web_routers/status.py:466,826`; `actions.py:160-165,240-283,373-479`; `git.py:50-62,125-128,167-169`; `dashboard_ui.py:51-84` | Existing bounded authenticated routes retained. Composed restart may affect chat socket. Chat error-log excerpt exists; System Recent logs controls remain WIP. No live update/restart run. |

## Branch adoption and saved-row boundaries

A canonical branch receipt contains the child ID, not a child snapshot.
`gateway/session_controls.py:530-539` auto-attaches the child but discards the
resume result; Android explicitly resumes that child with display projection
before navigation. Its own revision, generation, replay/subscription and copied
transcript initialize the adapter/cache. Parent state never substitutes for
child state. A failed or wrong-child snapshot retains the original branch
request identity/fences for an explicit retry; endpoint departure or cancellation
prevents publication/adoption.

Desktop sends the cut from the selected authoritative prefix, independently of
its visible count (`apps/desktop/src/app/session/hooks/use-session-actions/index.ts:2689-2697`;
`apps/desktop/src/api/canonical-protocol.ts:79-88`). Android carries the unique
saved row selected from the same authoritative visible history. A count-only
repository caller resolves fresh visible history instead of indexing a cached
page. Reasoning/tool projections cannot shift the cut; unsaved or ambiguous
visible rows refuse mutation. Whole-chat branching omits the cut, and legacy
Gateways retain their existing count contract. The upstream folded-bubble
boundary test is `apps/desktop/src/app/session/hooks/use-session-actions/branch-boundary.test.ts:8-27`.

`CanonicalBranchRegressionTest` exercises real ViewModel → live repository →
correlated adapter dispatch with public synthetic source-derived peers, including
launch/named owners, first child submit/control without reopen, paged/filtered
history, hydration retry, endpoint and cancellation. These are client contract
checks, not live server or visual acceptance.

## Public named-profile native-response blocker

A public dashboard ticket carries user/provider/issuer, not a profile root
(`dashboard_auth/routes.py:556-564`, `web_server_chat.py:227-239`). Dashboard
`/api/ws` selects the launch authority and permits explicit sibling RPC routing
(`web_routers/chat_ws.py:715-734`, `tui_gateway/ws.py:309-320`). An id-only
native srq reply is consumed **before** profile routing and authorized only
against that launch authority (`gateway/session_controls.py:139-166,581-590`).
The global native pending record stores raw session ID without owner authority
(`gateway/session_sudo_prompt.py:25-57,80-93`).

An isolated source-bound reproduction uses temporary real SQLite authorities,
the real public ticket mint/upgrade-auth/root-selection helpers, actual
AuthorityConnection.dispatch and native _ask/fanout/waiter. A beta-only session
absent from alpha does not settle its native reply, including on a second
public ticket socket. A separate **known-request-ID authorization negative**
shows an alpha-restricted actor can settle a beta waiter when alpha has the
same raw ID. The fixture deliberately supplies request-ID knowledge; no public
transport leak is demonstrated. This is helper/dispatcher evidence, not an
ASGI/network or provider-backed end-to-end claim. Fixed-contract expectations
fail on this frozen source. No server repair has been applied.

Canonical approval.respond and clarify.respond still route by profile. Legacy
native and launch-profile paths remain available. Client channel-origin tests
prove client routing only, never native server settlement. Adding profile to a
reply/query, using the legacy request.answer registry, or opening another public
ticket socket cannot fix this boundary. Private profile-bound runtime tickets
exist (`gateway/control_socket.py:326-341`, `gateway/run_api.py:302-345`) but
remain outside the authorized public mobile authentication scope.

Upstream must bind pending ID to its exact owner and validate the authenticated
host/profile grant, or offer a supported public owner-bound responder contract.
Checking whichever store contains the raw ID is insufficient. Coordinator owns
upstream disposition; acceptance wakes on an approved contract/fixed target with
public-auth RED/GREEN, or explicit requester scope decision. No scope reduction
is inferred while that decision is pending.

## Regression and evidence limits

Core tests: CanonicalBranchRegressionTest, CanonicalGatewayRpcTest, CanonicalOwnerIdentityTest,
CanonicalExecutionFramesTest, GatewayMutationContractTest,
ProfileGatewayRpcTest, PendingAnswerJourneyTest and GatewayVoiceOwnerTest,
alongside existing repository/connection/plugin/attachment/update/theme gates.
`src/test/resources/upstream-379cf122/execution-authority.frames.json` records
public generated serializer output with provenance; additional synthetic variants
are labeled by their tests. Historical single-question fixtures stay historical.
Full-suite results and the frozen commit/check/artifact receipt belong to the PR,
not a moving success claim in this page.

## Explicit new-feature limits

This slice does not add runtime ensure/migration/drain or private-ticket bootstrap,
structured settled clarification responses-array renderer (pre-existing gap #380),
worker register/adopt/persist/ACP policy, new room create/rename/disband/attachments/
stop/retry/approve controls, native cron/kanban execution extensions, live-voice
leases/delegation, theme accent/CSS/typography controls, reactions/tours/browser/
terminal/editor bridges, or broader Skills/MCP install/test/OAuth. Existing visible
unsupported controls retain their WIP treatment. These limits do not excuse
required existing-feature compatibility failures or certify full Desktop parity.
