# Media

Screenshots and short screen recordings used by [`README.md`](../../README.md)
and the guides in [`docs/guides/`](../guides). Everything here is captured from
a running debug build and is checked into the repository, so it is public the
moment it is committed.

## Capture rule

Capture from a **clean profile**: a freshly installed app, a Gateway stood up
for the capture, and **synthetic session titles** only. Nothing in a frame may
name a real host, a real tailnet or MagicDNS name, an IP address, a host-key
fingerprint, an account, a token, or the contents of a real conversation. If a
surface would show a Gateway URL or an SSH destination, use an obviously
fictional one (`gateway.example.ts.net`, `user@host.example`). This is the same
boundary the repository applies to source and tests: no credential, host name
or fingerprint belongs in this repository, in a test, or in a screenshot.

Screenshots are PNG at the device's native resolution. Demos are recorded as
MP4 and exported to GIF; commit both, with matching base names, so the README
can show the GIF while a reader can still open the sharper MP4.

## Contents

`screenshots/`

| File | Shows |
|---|---|
| `sessions-dark.png` | Session list, dark |
| `sessions-light.png` | Session list, light |
| `chat-dark.png` | Live transcript, dark |
| `chat-light.png` | Live transcript, light |
| `composer-completions-dark.png` | Composer with completions open |
| `gateways-dark.png` | Gateways screen and the connection routes |
| `appearance-themes-dark.png` | Appearance screen and the built-in themes |
| `system-panel-dark.png` | System panel |

`demo/` — each base name below is committed twice, as `.gif` and as `.mp4`:

| Base name | Shows |
|---|---|
| `live-turn` | Sending a turn and watching it stream back |
| `switch-sessions` | Switching sessions while a turn keeps running |
| `connect-remote-gateway` | Adding a Remote Gateway connection and signing in |
