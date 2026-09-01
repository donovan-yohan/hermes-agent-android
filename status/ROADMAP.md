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
| Same-device topology | **Local**. A Termux-hosted `hermes serve` on `127.0.0.1`, saved as a connection and authenticated by its session token, restored on launch. Host-owned like Remote; verified against a real Termux Gateway on an emulator, with a live turn and physical-device keep-alive still open. |
| Android support | API 26+ (Android 8.0+), target API 36. |
| App version | `0.2.0-phase2` (`versionCode` 2). |
| Distribution | One rolling, persistently debug-signed `hermes-mobile-latest` artifact from successful `main` builds. No production release channel yet. |
| Validation boundary | JVM and Robolectric gates are broad, and an instrumented lane runs on a CI emulator for five claims Robolectric cannot make. Font scale, the keyboard's own window and exact-head physical Pixel acceptance remain open. |

## What works now

### Connection and security

- **Remote Gateway is the default and recommended route.** Mobile accepts an
  HTTPS Gateway URL, requires gated native PKCE support, opens browser sign-in,
  encrypts endpoint-scoped tokens with Android Keystore, obtains a fresh
  single-use WebSocket ticket, and proves JSON-RPC readiness before reporting a
  connection.
- **The PKCE hand-off is fixed but not yet accepted on a device.** Sign-in opens
  in a Custom Tab whose service binding is held for the whole flow, so the
  loopback callback listener is not frozen out of a cached process; the listener
  is owned by the process rather than by the Gateways screen, so it survives an
  Activity being destroyed behind the browser; the callback's `state` is checked
  before anything is written back to the browser; and state mismatch, an
  authorization code the Gateway will not redeem, a listener that closed, and an
  abandoned flow each surface a distinct message with a next step. That is JVM
  and Robolectric evidence only. An emulator repro against a mock Gateway
  established two independent sufficient causes on the pre-fix build: a valid
  callback delivered while the app was backgrounded served its "Signed in" page
  and then produced no token request at all, while a refusal delivered the same
  way still reached the UI; and a Home press for sixty seconds left the process
  cached with callback connections accepted by the kernel and never read. Both
  are addressed here, but neither fix has been re-run on that emulator lane, and
  the case only a phone can answer — screen-off, a real doze window, and a
  delayed return from a real provider on a physical Pixel — remains open
  ([#114](https://github.com/donovan-yohan/hermes-agent-android/issues/114)).
- A Remote Gateway is host-owned. Mobile never starts, adopts, stops, or reaps
  its `hermes serve` process. This is the safe route for sharing one Hermes
  profile between Desktop and mobile.
- **Managed SSH is an explicit fallback**, not the sharing model. It provides
  strict destination parsing, mandatory first-use host-key review, changed-key
  refusal, one selected authentication method with no fallback, in-memory
  credential handling, loopback forwarding, remote process ownership proofs,
  and guarded cleanup.
- **Local is the same-device route.** A Hermes the person runs in Termux on this
  phone is a saved connection reached over loopback: `http://127.0.0.1:9119`,
  authenticated by the static Hermes session token, which is kept in the same
  Keystore-encrypted per-row slot a Remote sign-in uses and is bound to the
  address that saved it. Cleartext stays refused by default and is permitted for
  the three loopback names only; there is no `usesCleartextTraffic`. The app
  never starts, adopts, stops, or reaps that Termux process, and disconnecting
  closes a socket and nothing else. The active Local row comes back on launch on
  the token it already holds, never on one read off whatever answers the port. A
  Hermes that has stopped — the common case, since Android suspends Termux — says
  so and says what starts it, whether Connect finds it gone or a live connection
  loses it. Setup lives in the
  [Termux local Gateway guide](../docs/guides/termux-local-gateway.md).
- Default-network loss or handoff closes stale connection state. While the app
  is foregrounded, Remote Gateway retries transient failures with full-jitter
  backoff and resumes immediately after network recovery or foreground return;
  background redials pause. Managed SSH exposes a manual reconnect path. A
  Remote Gateway disconnect never kills the host-owned server.

See [ADR 0002](../docs/adr/0002-shared-remote-gateway.md) for Remote Gateway
ownership and authentication — and its addendum for the Local route — and
[ADR 0001](../docs/adr/0001-ssh-probe-to-tunnel.md) for the Managed SSH
lifecycle.

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
- Terminal-shaped tool output is ANSI-parsed rather than printed as escape
  codes, with stdout and stderr as separate labelled sections, the command on a
  `$` prompt line, and the process exit code. Web-search results render as
  structured hits under the query that produced them. The painted output is
  clamped so a chatty build log cannot flood the transcript, and the per-row
  copy control hands over the whole payload the backend sent, which the gateway
  itself caps at 32 KB. No syntax highlighting, no
  inline image results, and no artifact detection.
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

### Notifications

- OS notifications while a Gateway connection is live, from the same events
  every route delivers, so Remote, Managed SSH and Local behave identically:
  an approval, a question, a sudo or secret prompt, and a finished turn.
- Attention kinds break through for off-screen sessions even while the app is
  open; a finished turn alerts for any session whenever the app is away
  (foreground is isolated via the in-app unread dot). A four-second quiet window
  after every socket opens suppresses immediate firing, deferring unannounced
  prompts until the window closes while deduplicating prompts already announced
  pre-disconnect (including across multiple outstanding prompts replayed incrementally).
- An approval can be answered from the shade with Approve or Reject, through
  the same request path as the in-app bar. A persistent grant is not offered
  there. An approval somebody already answered elsewhere disappears without a
  word; one this app can no longer answer keeps its place and says to open the
  app instead.
- Notifications carry a redacted session title and nothing else the Gateway
  sent — never a command, tool output, a sudo prompt, or a secret name — and a
  locked screen is told only what kind of thing is waiting.
- Grouped per conversation, cleared when the prompt resolves or the
  conversation is opened, and tapping one opens it.
- The per-kind settings screen, notifications for a failed turn, a lost
  connection, and replying to a question from the shade are not built yet.

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
| Android background lifecycle | Automatic Remote Gateway redials pause while the app is backgrounded and resume on foreground return. A turn-scoped `dataSync` foreground service (`TurnForegroundService`) and persistent notification now keep the process unfrozen and permit automatic redials during active turns or pending approvals this client submitted or is streaming, stopping after a 5-second linger grace upon completion. Android may suspend or stop the app, so uninterrupted background connectivity is not claimed; socket retention across backgrounding and Doze on physical devices remains unproven on device/emulator hardware. | A justified Android lifecycle design with notification, power, privacy, reconnect, and process-death acceptance evidence, plus physical device validation for turn background retention. |
| Local route on Termux | A device pass on a Pixel 10 Pro emulator (Android 17, arm64, 16 KB pages) ran the route end to end against a real Termux `hermes serve` at `f82f2db`: install, token gating, connect, a Gateway-sourced session list and repository, launch restore after a force-stop, the token-refusal negative, and no token in `logcat`. The stopped-server negative is what that pass *caught*: it answered with the Remote route's "check the host" wording, fixed in [#98](https://github.com/donovan-yohan/hermes-agent-android/pull/98) and covered by unit tests against a real refused loopback connection rather than by a device re-run. Two things that pass does not claim. A **live turn**: no provider key was on the device, so every turn ended in the app's turn-failure copy — correct for the condition, and no evidence about turns. And **keep-alive on a physical phone**: an emulator with the app foregrounded proves nothing about a pocket, so wake lock, battery exemption and the Android 12+ phantom-process killer remain community advice, and upstream calls Termux gateway persistence best-effort. The route still carries no automatic redial after a failure: nothing loops behind a refusal or a stopped server, and a reconnect is an explicit action. The launch restore is the one unattended dial, and it happens before any failure, on a token the row already holds. The install itself needed three documented deviations from upstream's manual path — see the [Termux local Gateway guide](../docs/guides/termux-local-gateway.md). | A physical Pixel pass in [#93](https://github.com/donovan-yohan/hermes-agent-android/issues/93): a provider-backed live turn, and `hermes serve` surviving a screen-off background period. |
| Managed SSH reconnect | A reconnect starts a fresh owned backend; safe lockfile reuse is not implemented. Positively unowned or ambiguous processes are never killed. | Full lock, argv, profile, home, token, HTTP ownership, and RPC readiness proof before reuse. |
| Session management | Create/open/history work; rename and archive are absent. Search is local. | Authoritative Gateway methods and mobile journeys for every exposed action. |
| Attachments | Files and images work; folder acquisition, clipboard images, drag/drop, robust reconnect reacquisition, and in-place retry/detach cleanup are incomplete. | Bounded Android acquisition/recovery flows plus Gateway and physical-device evidence. |
| Notifications | Connected-only. Notifications arrive while the app holds a live Gateway socket; once that socket is gone, nothing arrives — there is no foreground service holding the connection and no push infrastructure upstream. Prompts parked while the app was disconnected are replayed on reconnect, deferred during the post-connect quiet window, and raised once the window expires (unless already announced pre-disconnect or answered in-app, preserved across multiple outstanding prompts replayed one event at a time). The in-app surfaces still show it. `POST_NOTIFICATIONS` is requested once, at the first live Gateway; a refusal is respected rather than re-prompted, and re-enabling is an OS settings action. No physical-device pass has answered a real approval from a real shade. | The background-lifecycle decision above, then a `dataSync` foreground service with Doze and battery honesty; and the emulator pass in [#99](https://github.com/donovan-yohan/hermes-agent-android/issues/99) driving real events through the Termux Local route. |
| Voice | The core path exists, but barge-in and several recovery/fallback journeys are incomplete. | Permission, audio-focus, interruption, process-death, headset/Bluetooth, and physical-device matrix passes. |
| Coding workspace | Status counters and changed-file metadata work, and Gateway-supplied inline diffs render in transcript tool rows. The coding/review surface does not provide repository file contents, changed-file patches, editing, terminal, or review workflows. | Authenticated Gateway contracts and purpose-built Android surfaces rather than local-path assumptions. |
| Desktop management breadth | The Relay plugin surface ships channels, transcripts, and sending only. Profiles have a read-only roster and no editing. There are still no dedicated mobile screens for bots, schedules, memory, knowledge, workflows, tools/skills/MCP, plugin management, Kanban, or messaging configuration. Agents may still use backend capabilities in chat when the Gateway exposes them. | Backend authority identified per surface, then an Android adaptation with tests and honest unsupported states. |
| Distribution | The rolling artifact is a debug APK behind GitHub sign-in. It is not a production-signed release or store package. | Versioned release signing, upgrade policy, distribution, and rollback/recovery gates. |
| Device evidence | An instrumented emulator lane runs on every CI build and covers what an emulator is green on: 48 dp touch targets at the real display density, the chat chrome arriving in the platform accessibility tree in the window under test, a real input method binding to the composer, a real orientation change, and the open destination surviving a real Activity destroy and rebuild. It is not physical acceptance and does not stand in for it: the PKCE browser hand-off (freezer-proofed and covered by JVM/Robolectric tests, never yet run screen-off on a physical device), real radio, network handoff, TalkBack, media, an enlarged font scale, the keyboard's own window under the composer, a system-initiated process kill, and the label and touch-size audit of what that accessibility tree publishes ([#91](https://github.com/donovan-yohan/hermes-agent-android/issues/91)) remain unproven, and exact-head physical Pixel acceptance is incomplete. | Repeatable acceptance matrix on the target device against a non-personal test Gateway. |

## Roadmap direction

Roadmap order follows risk, not feature count. Items move only when their backend
and Android acceptance boundaries are known.

### 1. Close the shipping slice

- Run exact-head physical Pixel acceptance for Remote Gateway browser PKCE —
  including screen-off and a three-minute delay before finishing provider auth,
  which is the case the freezer fix exists for — plus reconnect and network
  handoff, session/turn flows, attachments, voice, IME, TalkBack, and process
  death.
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

- Running Hermes itself inside the APK. This app hosts no runtime, bundles no
  Python, and starts no agent process. A Hermes the person runs in Termux on
  the same phone is a supported *connection* — the Local route — and it stays
  host-owned: the app connects to it over loopback and never starts, adopts,
  stops, or reaps it.
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
- [Remote Gateway ADR](../docs/adr/0002-shared-remote-gateway.md), including the
  Local route addendum
- [Termux local Gateway setup guide](../docs/guides/termux-local-gateway.md)
- [Managed SSH ADR](../docs/adr/0001-ssh-probe-to-tunnel.md)
- [Composer capability contract](../docs/parity/composer-capabilities.json)
- [Transcript selection and copy parity](../docs/parity/transcript-selection-copy.md)
- [Inline diff token parity](../docs/parity/inline-diff-tokens.md)
- [Profile rail and roster parity](../docs/parity/profile-switcher.md)
- [Theme parity workflow](../docs/workflows/sync-desktop-themes.md)
