# Media

Screenshots and short screen recordings used by [`README.md`](../../README.md)
and the guides in [`docs/guides/`](../guides). Android images are captured from
running debug builds; the routine-inspector supplement also contains genuine
Electron Desktop captures. Everything here is checked into the repository, so it
is public the moment it is committed.

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

## Model editor supplement

[Model editor v2](bot-model-v2/REPORT.md): paired Android/Electron evidence,
original capture provenance and explicit platform adaptations; bounded model
editor acceptance only. See [capture index](CAPTURE.md#model-editor-v2-supplement).

## Avatar editor supplement

[Avatar editor v1](bot-avatar-v1/REPORT.md): 16 Android and 16 real Electron
state/theme captures, canonical receipts and reconstructible source provenance.
Android fixture-staged outcomes are not Desktop native-gesture equivalence;
Desktop clear/shape-backfill remains drift under #194. See the
[capture index](CAPTURE.md#bot-avatar-v1-supplement).

## Contents

`screenshots/` holds PNGs of the session list, the transcript, the composer,
the Appearance screen and the System panel, dark and light where both were
taken. `demo/` holds the short recordings, each committed twice under one base
name — `.gif` for the README, `.mp4` beside it for anyone who wants the sharper
version.

**[`CAPTURE.md`](CAPTURE.md) is the per-file log**: what each asset shows, and
the device, build and conditions it was taken under. Add a row there when you
add an asset; this page stays the short index and the rule.

**The Gateways surfaces are not captured, and cannot be.** They set
`FLAG_SECURE` for as long as they are on screen
(`app/src/main/kotlin/com/hermesagent/mobile/ui/gateway/GatewayScreen.kt:125`
via `SecureScreenLifetime`, `ui/common/SecureScreen.kt:56`), which is the same
flag that stops a screenshot of a password field — so `screencap` returns black
by design. That covers the connection list, the connection editor and the
sign-in flow. Do not add a screenshot or a demo of them here; describe them in
prose instead.
