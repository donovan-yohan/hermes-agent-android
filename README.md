# hermes-mobile

Native Kotlin/Jetpack Compose client for operating a remote Hermes Agent installation over an app-managed SSH tunnel.

## Status

Architecture and feasibility research only. No production implementation exists yet.

## Product boundary

The intended first backend path is SSH-only: connect to a host, start or reuse the remote Hermes backend, tunnel it to the device, and present a native Android interface. Running Hermes locally on Android, Hermes Cloud, and direct public gateway URLs are not initial targets.

## Research

- [Native Kotlin SSH client scope](docs/spikes/native-kotlin-ssh-client-scope.md) — pending
