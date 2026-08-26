# Status and roadmap

> **Last implementation audit:** 2026-08-24 against `hermes-mobile`
> `2de8ae2d4c257ef0ae0df026bfb97637750e7c3a`. This is a directional roadmap,
> not a release schedule.

Hermes Mobile is an active Phase 2 Android client, not a complete port of every
Hermes Desktop surface. The concise product comparison lives in the
[README](../README.md#desktop-vs-mobile). This document records the evidence,
limitations, and likely next slices behind that table.

## Current baseline

| Item | Current state |
|---|---|
| Preferred shared topology | **Remote Gateway**. Desktop and mobile authenticate independently to one host-owned Gateway. |
| Fallback topology | **Managed SSH**. Mobile owns a private remote Gateway process, SSH tunnel, and positively proved cleanup lifecycle. |
| Android support | API 26+ (Android 8.0+), target API 36. |
| App version | `0.2.0-phase2` (`versionCode` 2). |
| Distribution | One rolling, persistently debug-signed `hermes-mobile-latest` artifact from successful `main` builds. No production release channel yet. |
| Validation boundary | JVM and Robolectric gates are broad, and an instrumented lane runs on a CI emulator for the claims Robolectric cannot make. Exact-head physical Pixel acceptance remains open. |

## What works now

### Connection and security

- **Remote Gateway is the default and recommended route.** Mobile accepts an
  HTTPS Gateway URL, requires gated native PKCE support, opens browser sign-in,
  encrypts endpoint-scoped tokens with Android Keystore, obtains a fresh
  single-use WebSocket ticket, and proves JSON-RPC readiness before reporting a
  connection.
- A Remote Gateway is host-owned. Mobile never starts, adopts, stops, or reaps
  its `hermes serve` process. This is the safe route for sharing one Hermes
  profile between Desktop and mobile.
- **Managed SSH is an explicit fallback**, not the sharing model. It provides
  strict destination parsing, mandatory first-use host-key review, changed-key
  refusal, one selected authentication method with no fallback, in-memory
  credential handling, loopback forwarding, remote process ownership proofs,
  and guarded cleanup.
- Default-network loss or handoff closes stale connection state. While the app
  is foregrounded, Remote Gateway retries transient failures with full-jitter
  backoff and resumes immediately after network recovery or foreground return;
  background redials pause. Managed SSH exposes a manual reconnect path. A
  Remote Gateway disconnect never kills the host-owned server.

See [ADR 0002](../docs/adr/0002-shared-remote-gateway.md) for Remote Gateway
ownership and authentication, and [ADR 0001](../docs/adr/0001-ssh-probe-to-tunnel.md)
for the Managed SSH lifecycle.

### Sessions and projects

- Gateway-authored session list, create, resume/activate, history, live events,
  tool progress, errors, and interrupt.
- Project overview, project creation, authoritative project membership, and
  project-specific session creation when the Gateway exposes the project RPCs.
  Older Gateways retain the flat session view.
- Date or project grouping, unread/running state, and local title/preview search.
- Per-session drafts, running-turn isolation, and concurrent sends to distinct
  idle sessions. Identifier-less events stay on one safe runtime pin rather
  than being painted into whichever session is visible.
- Session rename and archive do not have mobile product surfaces yet. Search is
  local filtering rather than a backend query.

### Profiles

- The Gateway's profiles at the sidebar foot: a default/all toggle pinned left,
  the named profiles between, and "Manage profiles…" pinned right, with the
  active profile in its own colour. Past the phone's width budget the strip
  collapses to a picker sheet.
- Picking a profile scopes the session list to it and starts fresh there; the
  All-profiles view unifies every profile's rows and tags each with its owner.
  Switching never interrupts a turn running in the profile being left, and
  queued composer text cannot cross profiles.
- `session.list`, `session.create` and `session.resume` carry the profile
  parameter the Gateway already accepts; the unified view fans `session.list`
  out over the launch profile and each named one.
- A read-only roster behind "Manage profiles…" — label, Default badge, path,
  model, provider and skill count. Creating, renaming, deleting, recolouring,
  reordering, avatars and the SOUL.md editor are absent.
- The project catalog remains the Gateway's own profile's: the Gateway's project
  RPCs take no profile, so the Project grouping states that outside the default
  scope rather than listing another profile's projects.

### Chat and composer

- Live transcript with Markdown paragraphs, headings, lists, tables, code
  fences, reasoning/tool activity, attached-image thumbnails, and image
  lightbox viewing.
- Long-press text selection scoped to one turn's prose and to user bubbles,
  plus a per-reply copy control that writes the reply as rendered plain text.
  Tool output, inline diffs and reasoning text are not selectable yet, though
  Desktop selects them; the platform selection toolbar has no device capture.
  Selection is for settled text: while a turn streams, a selection inside the
  block the next token rewrites is cleared, and only a selection in the already
  settled prefix survives. The copy control is the path for a live turn — it
  works throughout and copies the reply as far as it has arrived.
- Multiline drafts, history and undo/redo, provider/model selection, the full
  backend reasoning scale through Ultra, fast mode, slash/path/session
  completions, emoji search, and plain-text URL/prompt insertion.
- Send, stop, redirect, steer, durable text queues, queue edit/delete, park,
  resume, and send-next behavior scoped to the target session.
- Clarification, dangerous-command approval, and secure sudo/secret response
  surfaces. Sensitive entry uses a secure dialog and is wiped before that
  window loses its secure flag.
- Android file and image picking with bounded in-memory reads, preview chips,
  byte staging through the Gateway, image-only sends, transcript thumbnails,
  and payload wiping on removal.
- Gateway-backed dictation, voice conversation, auto-speak, and a user-started
  wake-word foreground service. Barge-in, complete permission-recovery journeys,
  streaming-TTS fallback, and physical-device acceptance remain open.

### Coding and agent status

- Authenticated repository status for the Gateway-authored session working
  directory: branch/worktree, pull-request link, ahead/behind, additions,
  deletions, untracked state, and a changed-file review sheet.
- Full task-list rows and completion counts, goals, subagents, background
  processes, previews, generic progress, compaction, and queued prompts in the
  composer status stack.
- Gateway-supplied inline diffs render in transcript tool rows. The dedicated
  coding/review surface does not yet provide repository file contents,
  changed-file patches, an editor, terminal/PTY, or a full Desktop-style review
  pane.

### Relay channels

- Relay workspace behind a Settings entry point: the channel list in the
  hub's own order with archived channels annotated in place, one channel's
  transcript oldest-to-newest by the hub's `seq`, and a composer under the
  transcript. Each draft is sent once under one client message id that a
  retry reuses byte-for-byte; the encoded body is bounded before any request
  goes out, and a conflict keeps the draft instead of claiming delivery.
- Honest states for every answer the plugin can give — a Gateway with no Relay,
  a lane that needs authorization on the host, an offline or errored lane, an
  unreadable answer, a saved Gateway that is down, and a device with no Gateway
  set up yet, which asks for one rather than offering a retry with no target.
  Relay's own words render beside this app's sentence, never instead of it.
- The pane on screen refreshes every three seconds while the surface is
  resumed and the lane is ready. A failed refresh keeps the last good answer
  under one quiet stale line instead of blanking the screen or raising an
  error.
- Editing and thread replies are absent. No pagination
  beyond the frozen contract's bounded 50-message window, no Harnesses
  inspector, and no `POST /connection/authorize` write. Selection is never
  persisted. Physical-device capture and acceptance for this surface are open.

### Appearance and Android adaptation

- All six built-in Desktop themes at the pinned theme authority, in the same
  registry order, with light/dark/system mode and mobile chat-chrome choices.
- Phone drawer and wide persistent session rail, IME-aware composer layouts,
  48 dp semantic actions, and desktop-keyboard shortcuts guarded against IME
  composition.
- Custom Desktop themes and the final physical TalkBack, large-font, reduced
  motion, orientation, and keyboard matrix remain open.

## Known limitations

| Limitation | What is true now | Exit condition |
|---|---|---|
| Same running session on two clients | One Remote Gateway safely shares process and session storage, but Desktop and mobile should not open or control the same running session simultaneously. Different sessions are safe. | Gateway multi-client event fan-out with equivalent mobile coverage. |
| Android background lifecycle | There is no general foreground service for the Gateway connection. Automatic Remote Gateway redials pause while the app is backgrounded — including when only the wake-word service remains active — and resume on foreground return. Android may suspend or stop the app, so uninterrupted background connectivity is not claimed. | A justified Android lifecycle design with notification, power, privacy, reconnect, and process-death acceptance evidence. |
| Managed SSH reconnect | A reconnect starts a fresh owned backend; safe lockfile reuse is not implemented. Positively unowned or ambiguous processes are never killed. | Full lock, argv, profile, home, token, HTTP ownership, and RPC readiness proof before reuse. |
| Session management | Create/open/history work; rename and archive are absent. Search is local. | Authoritative Gateway methods and mobile journeys for every exposed action. |
| Attachments | Files and images work; folder acquisition, clipboard images, drag/drop, robust reconnect reacquisition, and in-place retry/detach cleanup are incomplete. | Bounded Android acquisition/recovery flows plus Gateway and physical-device evidence. |
| Voice | The core path exists, but barge-in and several recovery/fallback journeys are incomplete. | Permission, audio-focus, interruption, process-death, headset/Bluetooth, and physical-device matrix passes. |
| Coding workspace | Status counters and changed-file metadata work, and Gateway-supplied inline diffs render in transcript tool rows. The coding/review surface does not provide repository file contents, changed-file patches, editing, terminal, or review workflows. | Authenticated Gateway contracts and purpose-built Android surfaces rather than local-path assumptions. |
| Desktop management breadth | The Relay plugin surface ships channels, transcripts, and sending only. Profiles have a read-only roster and no editing. There are still no dedicated mobile screens for bots, schedules, memory, knowledge, workflows, tools/skills/MCP, plugin management, Kanban, or messaging configuration. Agents may still use backend capabilities in chat when the Gateway exposes them. | Backend authority identified per surface, then an Android adaptation with tests and honest unsupported states. |
| Distribution | The rolling artifact is a debug APK behind GitHub sign-in. It is not a production-signed release or store package. | Versioned release signing, upgrade policy, distribution, and rollback/recovery gates. |
| Device evidence | An instrumented emulator lane runs on every CI build and covers what an emulator honestly can: connection-state copy on a real display, 48 dp targets and labels as the platform accessibility tree publishes them, real IME insets under the composer, a real orientation change, and saved-state restore across a real Activity recreate. It is not physical acceptance and does not stand in for it: PKCE browser hand-off, real radio, network handoff, TalkBack, media, and a system-initiated process kill remain unproven, and exact-head physical Pixel acceptance is incomplete. | Repeatable acceptance matrix on the target device against a non-personal test Gateway. |

## Roadmap direction

Roadmap order follows risk, not feature count. Items move only when their backend
and Android acceptance boundaries are known.

### 1. Close the shipping slice

- Run exact-head physical Pixel acceptance for Remote Gateway browser PKCE,
  reconnect and network handoff, session/turn flows, attachments, voice, IME,
  TalkBack, and process death.
- Finish attachment and voice recovery paths found by that matrix.
- Define and verify a production signing, versioning, distribution, and upgrade
  path. Keep the rolling debug artifact as a development channel.

### 2. Finish daily-driver gaps

- Add authoritative session rename/archive rather than local-only mutations.
- Adopt Gateway multi-client fan-out before claiming simultaneous control of one
  running session from Desktop and mobile.
- Extend the authenticated coding surface from status metadata to diff contents
  and review flows. File editing and terminal access require separate threat and
  mobile-UX decisions.
- Close remaining composer capture, accessibility, and lifecycle evidence.

### 3. Expand Desktop surface coverage

Evaluate each surface independently instead of promising a fake blanket port:

- profiles/bots and messaging configuration;
- schedules, memory, knowledge, and workflows;
- tools, skills, MCP, and plugin management;
- Kanban and plugin-provided dashboards;
- files, terminal/PTY, richer review, and custom themes.

A feature belongs on mobile only when its source of truth is available through
an authenticated Gateway contract or a deliberately local Android authority.
Electron-only filesystem, process, browser-window, and plugin-renderer behavior
cannot be copied honestly without a new protocol or a mobile redesign.

## Intentional non-goals

- Running Hermes itself inside Android or using Termux as the app runtime.
- Copying Desktop cookies, session tokens, SSH agents, or credentials. Remote
  Gateway clients sign in independently.
- Starting separate app-owned Gateway processes against one shared
  `HERMES_HOME`. Use one host-owned Remote Gateway for sharing.
- Accept-all SSH host keys, changed-key bypass, fallback auth chains, or secrets
  in logs, UI, argv, ordinary preferences, screenshots, or test fixtures.
- Claiming uninterrupted Android background operation before it is designed and
  physically verified.
- Pixel-for-pixel Electron imitation where Android input, lifecycle, security,
  or accessibility requires a different surface.

## Evidence map

- [Desktop surface port workflow](../docs/workflows/port-desktop-surface.md)
- [Phase 2 architecture and current connection sequence](../docs/phase-2-architecture.md)
- [Remote Gateway ADR](../docs/adr/0002-shared-remote-gateway.md)
- [Managed SSH ADR](../docs/adr/0001-ssh-probe-to-tunnel.md)
- [Composer capability contract](../docs/parity/composer-capabilities.json)
- [Transcript selection and copy parity](../docs/parity/transcript-selection-copy.md)
- [Inline diff token parity](../docs/parity/inline-diff-tokens.md)
- [Profile rail and roster parity](../docs/parity/profile-switcher.md)
- [Theme parity workflow](../docs/workflows/sync-desktop-themes.md)
