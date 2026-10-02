# Cron execution-session admission: prerequisite boundary

Status: **blocked before implementation**. Android base `651d659bded4deb7b125e48ef746c899857837e0`; isolated branch `fix/cron-session-admission`. No runtime fix or test-pass claim is made.

## Missing Android contract

At this base, `app/src/main/kotlin/com/hermesagent/mobile/data/gateway/GatewayRestClient.kt:451-572` exposes session list, search and messages, but no `GET /api/sessions/{id}` detail read. The same resource path at `:589-623` is PATCH, not detail GET. `SessionSummary` (`data/session/SessionModel.kt:81-177`) has source/profile/activity but no ended-at, scheduler ownership or literal active flag. `parseRestSession` (`GatewaySessionRepository.kt:6856-6889`) does not preserve these fields. Searching production Kotlin for `scheduler_owned`, `ended_at`, session-detail methods and cron execution ID patterns found no admission implementation.

A new foundational HTTP door and raw-detail model are therefore required. Per the task's stop condition, obtain scope approval before adding that door; do not substitute list timestamps or live status for ownership. No production or test files were changed.

The eventual repository integration overlaps the metadata worker's `GatewaySessionRepository.kt`; integrate branches later, never edit that worker's worktree. Current resume routing captures a connection and owner profile at `:2222-2250`, then activates or resumes at `:2255-2285`; those are writable admission boundaries. Existing transcript hydration follows at `:2327`, so simply throwing before resume does not by itself fulfill view-only transcript access.

## Inspected upstream contract

All citations below are from read-only `git show` at **`e05b16348b1d06a3311237423b0a4fc30d9c5aa1`**:

- `apps/desktop/src/app/cron/open-cron-run.ts:14-15,51-52`: execution IDs match `^cron_.+_\d{8}_\d{6}$`, tested after trimming. This identifies execution sessions, not routine rows.
- Same file `:35-48`: non-null `ended_at` is resumable; otherwise a literal boolean `scheduler_owned` wins even when `is_active` disagrees. Absent/nonboolean ownership falls back to literal boolean `is_active`, then numeric last activity within strictly less than 300 seconds. The requested Android finite-number guard must explicitly reject infinities/NaN; Desktop's expression itself only tests JavaScript number type, so finite validation is an intentional hardening, not verbatim parity.
- Same file `:97-124`: detail failure retains a known verdict; an unknown execution fails closed. An authoritative truthy non-cron source clears the restriction. Neither an unreadable detail nor unknown ownership proves the scheduler is dead: classify lookup failure separately and avoid misleading claims.
- `apps/desktop/src/app/session/hooks/use-prompt-actions/submit.ts:292-319`: owner-resolved detail is refreshed before submit; selection drift returns false and preserves the draft; read-only transcript targets are refused before sending.
- `hermes_cli/web_routers/sessions.py:524-544`: detail GET accepts an explicit profile, stamps the serving profile/default flag, attaches `scheduler_owned` when available and reads the scoped DB read-only.
- `hermes_cli/web_routers/cron.py:447-499`: scheduler ownership is for a cron-source execution session matching the run-ID pattern; a closed execution reports false ownership, which is why ended-at must take precedence in resumability. Ownership uses the owner's execution ledger and process/claim, not routine enabled/paused status.
- `tui_gateway/methods_session.py:857-872`: the resume guard is transcript-size safety, not ownership admission; this module has no `scheduler_owned` check. Do not assume RPC provides the client gate.

## Required next scope and acceptance tests (not executed)

1. Add bounded authenticated detail GET using the existing captured endpoint transport, encoded session identifier, and explicit named owner profile. Preserve raw literal types needed for the policy; test exact path/query and malformed responses.
2. Test policy precedence: closed values including zero; true/false ownership overriding activity; absent/nonboolean ownership; literal active fallback; finite timestamp boundary at 300 seconds; nonfinite and nonnumeric rejection; execution-ID matching and non-cron source override. Never apply this to routine pause/remove.
3. Test known-allowed and known-refused verdicts surviving detail failure, unknown lookup failure refusing without a fabricated ownership conclusion, and verdicts scoped to endpoint/profile/session.
4. Admit before writable resume and each send. Test captured endpoint/profile, endpoint or selection changes during detail suspension, no wrong-target RPC, refusal without draft loss, and readable stored transcript without a writable runtime.
5. Integrate with metadata commit `6a5792e0` later, after isolated review. Do not modify shared files in another worktree.

## Verification and build lane

Source inspection and `git diff --check` only. **Zero tests executed; no Gradle invoked.** The later message released the Gradle lane, but did not remove the prerequisite scope boundary. This task does not hold that lane. After scope approval, run focused RED/GREEN and full check/assembleDebug serially, max workers 1, no parallelism, in-process Kotlin compilation and Xmx6g as instructed. No APK or runtime behavior is certified by this report.
