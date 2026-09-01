# Task: scope a native Kotlin Android port of Hermes Desktop

> **Historical task packet.** Its SSH-only product scope was superseded by
> [ADR 0002](../adr/0002-shared-remote-gateway.md): Remote Gateway is now the
> default and recommended sharing route, with Managed SSH as a fallback.

You are Claude Code running the **Fable** model. Follow the user-level Fable orchestration policy: remain the planner/orchestrator and delegate substantive research lanes to **Opus subagents**. This is a research and architecture spike, not implementation.

## Source of truth

Inspect the current clean upstream checkout:

- Repository: `~/.hermes/hermes-agent`
- Upstream: `NousResearch/hermes-agent`
- Pinned starting SHA: `29112bef099274229cadff79cdff7bf7b99c4b77`
- Branch: `main`

Read the repository's `AGENTS.md`, `apps/desktop/AGENTS.md`, `apps/desktop/DESIGN.md`, current official docs, and actual implementation/tests. Do not rely on docs or README claims alone.

You may inspect public community Android clients only as prior art, not as architectural authority:

- `rusty4444/hermes-android`
- `HenWorks/Hermes-agent-android-PC-companion-app`
- open upstream mobile PRs #49834 and #52673

Pin any external repository or PR source you cite to a full SHA.

## User intent

Scope a **new native Android app written in Kotlin with Jetpack Compose** that ports the current official Hermes Desktop experience as faithfully as Android permits.

The only backend path in product scope is:

1. user configures an SSH host;
2. Android establishes the SSH connection and local tunnel;
3. Android starts or reuses the remote Hermes `serve`/dashboard backend on demand;
4. Android adopts the remote session token and drives that remote Hermes installation.

Explicitly out of product scope for the first release:

- running Hermes locally on Android;
- Termux as the app runtime;
- direct public Remote Gateway URL mode;
- Hermes Cloud mode;
- embedding the existing Electron/React renderer as the product UI;
- implementing production code during this spike.

The desired app should mimic the current Desktop product broadly, including customization, session grouping, profiles/bots, Kanban, workflows, knowledge, artifacts/uploads, schedules/cron, plugins, tools/skills/MCP, memory, messaging status/config, approvals, models, and other currently shipped surfaces. Do not pretend every Electron feature ports cleanly. Identify exact parity, degraded parity, redesign requirements, and non-portable surfaces.

## Required research lanes

Delegate at least four bounded Opus research lanes and reconcile their results yourself:

1. **SSH transport and remote lifecycle**
   - Trace Desktop's SSH config, host validation, key handling, ControlMaster assumptions, host-key policy, dashboard bootstrap/reuse/lockfiles, ownership, token adoption, local forwarding, reconnection, keepalive, update, and cleanup.
   - Identify assumptions that fail on Android (no system OpenSSH, `~/.ssh/config`, ssh-agent/FIDO hardware key behavior, process model, background restrictions).
   - Compare viable Kotlin SSH libraries using current primary-source evidence. Recommend one and state missing capabilities or extensions.

2. **Desktop feature and UI inventory**
   - Inventory every meaningful current Desktop screen/surface and its authority: backend API, Electron-native bridge, local filesystem/process, renderer-only state, or plugin SDK.
   - Map concrete APIs, WebSockets, IPC contracts, and desktop-only capabilities used by sessions/grouping, Kanban, plugins, customization/themes, Bot Mode/profiles, workflows, knowledge, artifacts/uploads, schedules, tools/skills/MCP, memory, messaging, models, approvals, terminal/files/git, browser/computer use, updates, voice, and notifications.
   - Mark what can be implemented from the remote backend today versus what requires upstream protocol/API work.

3. **Native Android architecture and UX**
   - Propose a Kotlin/Compose architecture with explicit modules, state ownership, persistence, transport, background execution, security, offline/read cache, notification, file picker/upload, voice, and deep-link behavior.
   - Cover Android Keystore, biometric gating options, encrypted credential storage, host-key pinning, passphrase UX, foreground service/WorkManager limitations, process death, Doze, network handoff, reconnect, and tablet/foldable layouts.
   - Do not produce generic Clean Architecture confetti; justify every seam with production and test adapters.

4. **Delivery scope, compatibility, testing, and risk**
   - Define a vertical MVP and staged parity plan, with critical path, staffing assumptions, engineer-week ranges, acceptance gates, and explicit stop/revisit conditions.
   - Include fake SSH and fake Hermes gateway contract harnesses, Android unit/instrumentation tests, real-device matrix, backward/forward compatibility, API versioning, release/signing/distribution, telemetry/privacy stance, and upstream drift strategy.
   - Adversarially assess plugin parity, custom theme parity, remote terminal/PTY, Git/repo UX, and native ACP/code-session support.

Use additional Opus lanes if the source surface warrants it.

## Key questions the final document must answer

1. Is this technically viable without changing Hermes core?
2. What percentage/categories of current Desktop behavior are reachable through existing remote APIs?
3. What exact upstream API/protocol gaps block honest parity?
4. Can Desktop plugins be supported natively? If not, what are the viable contracts or fallbacks, and what should v1 claim?
5. What is the safest Android-native SSH design, including host verification and credential handling?
6. What is the smallest coherent MVP that proves the architecture vertically rather than shipping a mock shell?
7. What should be deferred or rejected even if the user asked for “everything”?
8. What is the recommended build/wait/reject decision, and why?

## Required artifact

Write exactly one primary deliverable in the new local-only product repository:

`docs/spikes/native-kotlin-ssh-client-scope.md`

It must be a professional, implementation-ready technical spike with:

- `tl;dr` and explicit **build / wait / reject** recommendation;
- pinned-source ledger;
- current Desktop architecture map;
- complete feature-parity matrix with columns: surface, Desktop authority, remote API/protocol, Android disposition, upstream dependency, target phase;
- SSH lifecycle sequence diagram and threat model;
- proposed Kotlin/Compose module architecture and state ownership;
- data/security model;
- protocol and upstream-change requirements, separated into hard blockers vs quality improvements;
- staged roadmap: walking skeleton, MVP, parity expansion, hardening;
- estimates with stated assumptions rather than fake precision;
- test/verification strategy;
- risk register with severity, likelihood, mitigation, and decision trigger;
- issue/work-package breakdown suitable for later GitHub tracking;
- explicit non-goals and honest parity wording;
- methodology and gaps.

Every significant claim about Hermes must cite a pinned `file:line` source reference. Use Mermaid diagrams, label every edge, and validate the fences structurally. Distinguish shipped behavior from open PR/concept behavior.

## Safety and repository boundaries

- Treat `~/.hermes/hermes-agent` as read-only. Do not edit, commit, switch branches, fetch, pull, reset, clean, or create worktrees there.
- Do not open GitHub issues/PRs or publish anything.
- Do not modify Hermes profiles, Claude/Codex config, global instruction files, credentials, or services.
- Do not expose tokens, auth files, SSH private keys, hostnames from private configs, or other secrets.
- In the `hermes-mobile` repository, the only file you may create or modify is `docs/spikes/native-kotlin-ssh-client-scope.md`. Do not edit the task packet or scaffold. Temporary research files may live under `/tmp/hermes-mobile-native-scope/` and must be removed or listed in the final report.

## Completion report

When finished, verify:

- `docs/spikes/native-kotlin-ssh-client-scope.md` exists and is non-empty;
- source ledger includes the pinned upstream SHA;
- all required sections and parity-matrix columns exist;
- Mermaid fences are balanced and edge-labelled;
- the upstream checkout is still clean at the same SHA;
- no files other than `docs/spikes/native-kotlin-ssh-client-scope.md` were changed by this research run in the `hermes-mobile` repository.

Then report the recommendation, artifact path, source SHA, Opus lanes used, and validation performed. Do not implement code.
