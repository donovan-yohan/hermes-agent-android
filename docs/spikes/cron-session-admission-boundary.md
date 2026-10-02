# Cron execution-session admission

Status: implemented in the isolated `fix/cron-session-admission` worktree after explicit authorization of the narrow app-owned detail GET. Based on Android `489baa559ad242a77aa410db48f830e12235d8ea`. No other worktree was merged or modified.

## Upstream contract

Inspected read-only at **`e05b16348b1d06a3311237423b0a4fc30d9c5aa1`**:

- `apps/desktop/src/app/cron/open-cron-run.ts:14-15,35-52`: execution IDs match `^cron_.+_\d{8}_\d{6}$` after trimming. Non-null `ended_at` is resumable, including zero. Otherwise literal boolean `scheduler_owned` wins over activity, then literal boolean `is_active`, then numeric `last_active` with strictly less than 300 seconds elapsed. Android additionally rejects nonfinite timestamps and never coerces quoted numbers or booleans.
- Same file `:97-124`: failed detail keeps a known verdict; an unknown execution fails closed. A nonempty authoritative non-cron source clears the restriction. Unknown lookup failure is not evidence that the scheduler died.
- `apps/desktop/src/app/session/hooks/use-prompt-actions/submit.ts:292-319`: owner-resolved detail is refreshed before submit; selection drift refuses dispatch and preserves the draft.
- `hermes_cli/web_routers/sessions.py:524-544`: `GET /api/sessions/{session_id}` reads the explicitly scoped database read-only, stamps the serving profile and adds scheduler ownership when available.
- `hermes_cli/web_routers/cron.py:447-499`: ownership is execution-ledger/process truth, not routine enabled/paused status. A closed execution reports false ownership, hence closed-state precedence.
- `tui_gateway/methods_session.py:857-872`: the resume guard is transcript-size safety, not scheduler ownership. RPC does not supply this client admission rule.

## Implementation and architecture

- `GatewayRestClient.sessionDetail` is an app-owned typed GET, not a generic plugin HTTP bypass. It captures the connection-owned HTTP object before the IO hop, encodes exactly one path segment, requires an exact named profile (no `current` alias or surrounding whitespace), bounds the request to 15 seconds/1 MiB, wipes transferred bytes, and rejects missing/mismatched exact response identity/profile. The read never probes another profile or endpoint.
- `GatewaySessionDetail` retains strict nullable ownership/activity booleans, non-null ended-at presence, source and finite numeric activity seconds. No list timestamp, routine status or runtime liveness is substituted for ownership.
- Verdicts are keyed by endpoint generation, exact profile and durable execution ID. Failed reads retain only that owner's known verdict. Unknown reads remain separately classified as unavailable, not fabricated unowned executions. Each admission read has a sequence; an older response cannot overwrite a newer read, and a newer read revokes an older pre-wire permission.
- The repository checks admission before writable resume/activate, runtime acquisition and each prompt dispatch. Cron calls opt into the existing process-shared `EndpointDispatchFence` even when ordinary calls use reconnect-following RPC. Endpoint/client identity, runtime-profile provenance, admission revision and the UI selection lease are rechecked around immediate `wire.send`. Unsupported guarded RPC implementations fail closed. Non-cron sessions do not acquire a new detail prerequisite.
- `SessionSelectionFence` carries a captured endpoint/profile/session intent through the coroutine context. Rehoming or changing profile scope invalidates the epoch, including A→B→A. Revocation and the immediate dispatch callback share a lock. The UI captures before launching selection/submission work.
- A refused cron open hydrates stored REST messages without `session.resume`, `session.activate` or a `session.history` fallback requiring a runtime. Foreign-profile cached history is discarded before tail grafting. A successful read-only open explains its restriction rather than loading a writable composer. A missing named owner or unavailable messages route cannot safely fall back to a writable runtime; the open reports failure instead.
- Cron drafts remain visible while admission is pending and after definite refusal. Accepted delivery clears only its original, unchanged draft on the same endpoint/profile. Pending sends are owner-keyed, so navigation ABA does not authorize a duplicate while the original is unresolved; duplicate taps cannot claim newly added attachments. Refused stale prompts cannot roll old cached history into a replacement runtime/profile.
- `OkHttpGatewayHttp` now explicitly disables HTTP/HTTPS redirects. The shared Remote client previously inherited redirect defaults; custom authorization headers are not protected by OkHttp's special treatment of `Authorization`. A real loopback test proves the detail request does not follow a credential-bearing redirect.

## Verification

RED/GREEN was exercised for the detail API, policy, read-only repository open, selection leases, draft retention, read-only explanation, redirect refusal, admission revocation and foreign-profile history isolation. Focused tests cover:

- strict ownership/activity precedence, closed `ended_at=0`, string `false` fallback, finite numeric timestamps and the exact 300-second boundary with a fixed clock;
- known allowed/refused versus unknown failed GET, authoritative non-cron source, owner-key isolation and non-cron route independence;
- exact encoded path/query/authentication, response-identity refusal, bounded HTTP and original-transport capture before queued IO;
- delayed detail and immediate resume/prompt wire ABA, endpoint replacement, owned→unowned refresh and newer verdict revocation;
- runtime-free transcript hydration, same-ID foreign-profile history isolation, pending/refused draft preservation and UI lease invalidation.

Focused `GatewayRestClientTest`, `GatewaySessionDetailTransportTest`, `CronSessionAdmissionTest`, `GatewaySessionRepositoryTest` and `ChatViewModelTest` passed. Full check/build results are recorded in the final handoff; all Gradle invocations are serial, max workers 1, in-process Kotlin, Xmx6g.

## Availability and evidence limits

- Detail and messages are existing upstream routes, verified from pinned source and contract-faithful tests; no authenticated live backend was exercised. Older servers without detail or profile stamps leave unknown runs view-only. A previously known verdict survives same-owner lookup failure as Desktop specifies.
- No server-issued admission/ownership token exists. A scheduler transition unseen between GET and wire send cannot be fenced by the client, and an already-sent frame cannot be retracted.
- Non-cron behavior and plugin HTTP namespaces are unchanged. Routine pause/remove semantics are not part of execution-session admission.
- The metadata worktree overlaps `GatewaySessionRepository.kt`; integration remains the parent's responsibility after review. No metadata branch was cherry-picked here.
- This is JVM/transport/build evidence, not emulator or physical-device visual acceptance. Existing parity work still owes a rendered view-only/draft journey; see the parity ledger.
