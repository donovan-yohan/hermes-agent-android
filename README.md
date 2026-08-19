# hermes-mobile

Native Kotlin/Jetpack Compose client for operating a remote Hermes Agent
installation over an app-managed SSH tunnel.

## Status

**Phase 1 vertical slice — builds and runs.** The chat and session surface is
real and usable offline against deterministic demo data. The SSH onboarding and
`probe` path is real code over sshj, with a strict trust-on-first-use host-key
policy.

There is no Hermes gateway transport yet: no WebSocket, no real sessions, no
model output. `docs/phase-1-architecture.md` is the honest inventory of what is
real, what is demo, and what has not been verified.

## Build

```bash
export ANDROID_HOME=/opt/android-sdk        # JDK 17; platform 36, build-tools 35/36
./gradlew check                             # unit tests + lint + repo invariants
./gradlew assembleDebug                     # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

minSdk 26, compile/target 36. The debug build installs as
`com.hermesagent.mobile.debug`.

## What Phase 1 contains

- The six Hermes Desktop themes (`nous`, `midnight`, `ember`, `mono`,
  `cyberpunk`, `slate`) ported from upstream at a pinned SHA, with light and
  dark for each, an in-app picker, and a parity test that fails on drift.
- Chat as the home surface: transcript with markdown blocks, inline tool
  scaffolding, streaming and stop; a composer; and a session list with calendar
  grouping, search, status dots and create/rename/archive. Adaptive: a drawer on
  a phone, a persistent rail at 720dp and above.
- A real SSH slice: host profile, password or SAF-imported private key,
  trust-on-first-use with an explicit fingerprint review, a hard stop on a
  changed key, and a bounded `probe` that runs one harmless command. Credentials
  stay in memory; only non-secret profile fields reach disk.

Termux reaching the host proves the route and that `sshd` accepts your account.
It does **not** give this app Termux's keys, agent or `~/.ssh/config` — separate
Android packages, separate sandboxes. The app asks for its own credentials and
says so on screen.

## Product boundary

SSH-only for the first backend path: connect to a host, start or reuse the
remote Hermes backend, tunnel it to the device, present a native Android
interface. Running Hermes locally on Android, Hermes Cloud, and direct public
gateway URLs are not initial targets.

## Docs

- [Phase 1 architecture and next slice](docs/phase-1-architecture.md) — start here
- [ADR 0001: the SSH seam](docs/adr/0001-ssh-probe-to-tunnel.md)
- [Porting a Desktop surface](docs/workflows/port-desktop-surface.md)
- [Syncing Desktop themes](docs/workflows/sync-desktop-themes.md)
- [Native Kotlin SSH client scope](docs/spikes/native-kotlin-ssh-client-scope.md) — the founding research
