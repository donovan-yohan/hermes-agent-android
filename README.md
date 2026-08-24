# Hermes Mobile

Native Kotlin and Jetpack Compose client for a self-hosted Hermes Agent.

The recommended setup is one host-owned **Remote Gateway** shared by Desktop
and mobile. Each client signs in independently; Mobile never starts or stops
the shared server. **Managed SSH** remains available as a fallback when Mobile
should own a private remote Gateway process.

Hermes Mobile is an active Phase 2 project, not a complete port of every
Desktop surface. See [Status and roadmap](status/ROADMAP.md) for detailed
implementation status, limitations, and next slices.

## Recommended topology

```text
Desktop ───────┐
               ├── Remote Gateway ── one Hermes profile and session database
Mobile ────────┘
```

Remote Gateway requires an HTTPS endpoint with gated native PKCE support.
Desktop and Mobile keep separate sign-ins while using the same host-owned
Gateway and backend state.

> Until the Gateway's multi-client fan-out update is available, do not open or
> control the same **running session** from Desktop and Mobile simultaneously.
> Using different sessions on the same Remote Gateway is supported.

Managed SSH is the fallback for a private, app-owned backend. It starts and
forwards a separate Gateway over SSH and must not be used to create competing
servers against the same Hermes profile.

## Desktop vs Mobile

**Supported** means Mobile has a tested native counterpart, not that the UI is
identical. **Partial** names a useful implemented subset. **Not yet** means
there is no dedicated mobile surface.

| Area | Hermes Desktop | Hermes Mobile |
|---|---|---|
| Remote connection and sign-in | Local and remote Gateway workflows | **Supported** — Remote Gateway over HTTPS with independent native browser PKCE; recommended for Desktop + Mobile |
| Managed remote backend | SSH lifecycle and forwarding | **Supported fallback** — app-managed SSH, mandatory host-key review, one selected auth method, private Gateway ownership, and guarded cleanup |
| Sessions and projects | Create, browse, search, group, rename, archive, and project views | **Partial** — list/create/open/history, date and project grouping, project creation, unread/running state, and local search; no rename/archive |
| Live chat and transcript | Streaming conversation, Markdown, tools, progress, and media | **Supported** — streamed messages, reasoning/tool rows, Markdown tables and code, attached-image thumbnails, and lightbox viewing |
| Composer and model controls | Rich editor, references, completions, model, effort, and fast mode | **Supported core** — multiline drafts, history/undo, model/provider, the full reasoning scale through Ultra, fast mode, slash/path/session/emoji completion, and plain-text references |
| Turn control and queues | Send, stop, redirect/steer, queue, park, and send next | **Supported** — target-session isolation, concurrent distinct-session sends, durable text queues, edit/delete/park/resume, redirect, steer, stop, and send next |
| Required input | Clarification, approvals, sudo, and secret prompts | **Supported** — single/batch clarification, Gateway-offered approval choices, and secure wiped sudo/secret entry |
| Attachments | Files, images, folders, paste, and drag/drop | **Partial** — Android file/image picker, bounded byte staging, preview chips, image-only send, transcript thumbnails, and lightbox; no folders, clipboard images, or drag/drop |
| Voice | Dictation, conversation, auto-speak, wake word, and barge-in | **Partial** — dictation, conversation, auto-speak, and user-started wake-word service; barge-in and complete device/recovery evidence remain open |
| Coding context and review | Branch/worktree, pull requests, Git status, diff, files, editor, terminal, and review panes | **Partial** — branch/worktree, PR link, ahead/behind and diff counters, plus changed-file metadata; no diff contents, editor, files pane, or terminal |
| Agent status | Goals, tasks, subagents, background work, previews, queues, and compaction | **Supported** — full task list and counts, goals, subagents, processes, previews, queue state, progress, and compaction |
| Appearance | Built-in and custom themes plus Desktop chrome | **Partial** — all six built-in themes at the pinned Desktop authority, system/light/dark mode, and mobile chat chrome; no custom themes |
| Desktop workbench | Multi-pane files, terminal/PTY, review, and desktop window workflows | **Not yet** — the changed-file sheet is the only native workspace view |
| Management surfaces | Profiles/bots, schedules, memory, knowledge, workflows, tools/skills/MCP, plugins, Kanban, and messaging settings | **Not yet** — no dedicated mobile screens; agents can still use Gateway-exposed capabilities in chat |
| Background lifecycle | Persistent desktop process and notifications | **Partial** — reconnect and a wake-word foreground service exist, but uninterrupted background Gateway connectivity is not claimed |

## Install the rolling APK

The project currently ships one rolling debug APK from the latest successful
`main` build:

1. Open the
   [Android exact-head workflow](https://github.com/donovan-yohan/hermes-agent-android/actions/workflows/android-exact-head.yml).
2. Select the newest successful run on `main`.
3. Download `hermes-mobile-latest`. GitHub requires sign-in to download Actions
   artifacts.
4. Extract and install the APK:

   ```bash
   adb install -r app-debug.apk
   ```

The rolling artifact uses a persistent debug signing identity, so a newer
rolling build updates an earlier rolling install in place. It cannot update a
locally built debug APK signed by a different key. Older rolling artifacts are
pruned after the replacement uploads; the remaining artifact expires after 90
days. This is a development channel, not a versioned production release.

## Build and verify

Requirements: JDK 17 and an Android SDK with platform 36 and build-tools 35/36.

```bash
export ANDROID_HOME=/opt/android-sdk
./gradlew check assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew check` runs debug and release unit tests, Android lint, product-copy
checks, theme/composer parity contracts, and repository invariants.

## Security boundaries

- Remote Gateway stores only non-secret endpoint/provider settings in ordinary
  preferences. Endpoint-scoped OAuth tokens are encrypted with Android
  Keystore and kept in no-backup storage.
- Managed SSH passwords, passphrases, and imported private-key bytes are
  in-memory only and wiped after use. Host keys require explicit first-use
  review; a changed key has no accept path.
- Secrets and credentials must not enter logs, UI status, argv, screenshots,
  ordinary preferences, or repository fixtures.
- One host-owned Remote Gateway is the sharing model. Multiple app-owned
  Gateways must not target the same effective `HERMES_HOME`.

## Documentation

- [Status and roadmap](status/ROADMAP.md)
- [Phase 2 architecture](docs/phase-2-architecture.md)
- [Remote Gateway ADR](docs/adr/0002-shared-remote-gateway.md)
- [Managed SSH ADR](docs/adr/0001-ssh-probe-to-tunnel.md)
- [Desktop surface port workflow](docs/workflows/port-desktop-surface.md)
- [Theme parity workflow](docs/workflows/sync-desktop-themes.md)
