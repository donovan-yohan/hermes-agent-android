# hermes-mobile

Native Kotlin/Jetpack Compose client for operating a remote Hermes Agent over
an app-managed SSH tunnel.

## Status

**Phase 2 gateway vertical slice — `0.2.0-phase2`.** The app opens a verified
SSH connection, starts a loopback-bound remote `hermes serve`, holds a
loopback-only local forward, proves authenticated HTTP ownership and JSON-RPC
WebSocket readiness, then lists/resumes/creates real sessions and sends,
streams, or interrupts a live turn.

Production startup has no demo seed or local turn engine. Offline tests use
fakes at SSH, process, HTTP, and WebSocket seams.

The slice deliberately starts a fresh positively-owned remote process after a
reconnect; safe lockfile reuse is not implemented yet. It also has no Android
foreground service, so it does not promise an uninterrupted background
connection. Submitted turns are serialized because upstream stream events may
omit their session id. See [the Phase 2 architecture](docs/phase-2-architecture.md).

## Build

```bash
export ANDROID_HOME=/opt/android-sdk        # JDK 17; platform 36, build-tools 35/36
./gradlew check                             # unit tests + lint + repo invariants
./gradlew assembleDebug                     # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

minSdk 26, compile/target 36. The debug build installs as
`com.hermesagent.mobile.debug`.

## What Phase 2 contains

- The six pinned Hermes Desktop themes with light/dark resolution, semantic
  tokens, an in-app picker, and an offline parity gate.
- Native chat and sessions backed by authenticated Gateway JSON-RPC:
  `session.list`, `session.create`, `session.resume`, `session.activate`,
  `session.history`, `prompt.submit`, and `session.interrupt`.
- Explicit durable-to-runtime session identity and connection-generation
  handling, so switching sessions cannot steal an unscoped live stream.
- SSH password, SAF-imported private key, or Tailscale SSH auth with mandatory
  host-key review, no auth fallback, memory-only credentials, and redacted
  failures.
- Linux remote discovery/capability checks, per-install ownership namespace,
  stdin-only token upload, owned-process cleanup, and a bind-and-hold
  `127.0.0.1` forward.
- Concise Gateway copy plus a tracked review workflow and deterministic source
  gate for essay-length primary UI strings.

Rename and archive are not presented as local durable actions in this slice;
search remains UI-local and session creation/navigation are backend-authoritative.

## Product boundary

SSH is the backend path: connect to a host, start remote Hermes, forward the
private Gateway to the device, and present a native Android interface. Running
Hermes locally on Android, Hermes Cloud, and direct public Gateway URLs are not
targets of this slice.

## Docs

- [Phase 2 architecture and evidence](docs/phase-2-architecture.md) — start here
- [ADR 0001: SSH transport and Gateway lifecycle](docs/adr/0001-ssh-probe-to-tunnel.md)
- [Product-copy review](docs/workflows/review-product-copy.md)
- [Porting a Desktop surface](docs/workflows/port-desktop-surface.md)
- [Syncing Desktop themes](docs/workflows/sync-desktop-themes.md)
- [Phase 1 historical baseline](docs/phase-1-architecture.md)
