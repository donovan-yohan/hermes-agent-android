# Sidebar running outline parity

## Pin and source contract

Desktop authority is `NousResearch/hermes-agent` at
`45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8` (read-only checkout).

| Contract | Desktop source | Android port |
|---|---|---|
| Owner | `apps/desktop/src/store/session-dot-state.ts:70-80` | `SessionStatus.Working` and `SessionStatus.Stalled` only |
| Rendering | `apps/desktop/src/app/chat/sidebar/session-row.tsx:254-258,404` | `SessionRow` paint sibling, including project preview rows |
| Geometry and bright stop | `apps/desktop/src/app/chat/sidebar/chrome.tsx:21-42,84-108`; `src/styles.css:1011-1040,1129-1144` | Flush 6dp rounded host; 1.25dp inset stroke; semantic `sessionRunningOutline` is foreground in dark mode and accent in light mode |
| Motion | `apps/desktop/src/styles.css:994-1008,1011-1040,1085-1113` | 300% 160-degree gradient, -10% to -50% travel, 2.23s linear infinite |
| Reduced motion | `apps/desktop/src/styles.css:1157-1161` | `ValueAnimator.areAnimatorsEnabled()` omits the Compose infinite clock; phase zero remains a visible static ring |

## State and mobile deviation ledger

State is backend-authoritative `SessionSummary.status` from `SessionCache`; no
ViewModel or animation state determines whether a ring exists.

Desktop: 1.25px stroke on a compact 26px row. Android: 1.25dp stroke on the
existing 48dp minimum touch-target row. Reason: Android's touch target is a
mobile accessibility requirement; the ring remains flush, rounded, and
non-interactive. The decoration is canvas-only and clears its semantics, so the
row keeps its existing one clickable target and one spoken label.

## Executable evidence

- `SessionRunningOutlineTest` proves the exact Working/Stalled predicate and
  excludes NeedsInput, Background, Idle, and Unread.
- `ChatAccessibilityLayoutTest` proves a running row has one, not duplicate,
  accessible label.

Capture evidence: the exact signed merged-main CI APK
(`33b652a2567940d8ff223e7aa015bafc3b628d7ba68eb55c53d1472c8f2f5752`)
was installed and cold-launched through `MainActivity` on the Server-Mac
`emulator-5554`; the fatal/ANR scan was clean.

A sanitized debug-only fixture, based on `60a5120`, rendered the real
production `SessionList` on Google `sdk_gphone16k_arm64` (Android 17/API 37,
1280x2856 at 480 dpi); fixture APK SHA-256:
`53710ffc12efdedc3f3137bf7ffc254bcbb2020f08505d5aa61bbad6091bbbfb`.

Two frames captured one second apart
(`6f8ca5376f14cb3fbd2b09bfec1ff94faee8493e2a3d99d01355bd96a8221c0b`,
`0b0ff2fc5303dc4531f6aa0262bdb26744cf9b3ba0fc12c8c41f2b155a89fa48`) and the
5-second video
(`e3ba873a16cea195252cf701e0b16ef7b4698f27f79fcda0ac81e8f50d95fbfc`) prove
movement: Working and Stalled are ringed, while NeedsInput, Background, and
Idle are unringed. Capture shared the emulator with other work; no private
data was used.

## Default-dark sidebar color contract

For Android commit `44b409c610d0c0cb8e21d1856ea2bfaf4b5a81c3` against pinned
Desktop source `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8`, the default-dark
sidebar uses the Nous palette defined in `presets.ts:174-232`: sidebar raw
`#010409`, accent `#4A84FE`, and outline `#E6EDF3`. The selected sidebar row
follows `styles.css:308-312`, with active raw `#2084ABFA`; on Android its
composited observed color is `#121827`. This raw-versus-composited distinction
is part of the visual parity contract.

Evidence for this exact implementation: production APK SHA-256
`3c9103f6c5e3ba9bb7f4ed493130971aa33841c6ac135b7fe9231174f4fd8d70`; fixture
APK SHA-256 `3e578294c1573cf30dc1612addf9317246493934e2c0b3d3bfb6cd9d40c4aa95`;
Google `sdk_gphone16k_arm64` emulator, API 37, 1280x2856 at 480 dpi; cold
launches of 719ms and 658ms; `topResumed` and fatal scans clean; frame hashes
`39f606f2dec595b000d922144cded5dcac9f2d5d4e7a55be3249d2d8dcc17960` and
`768e990bf64869acbfc9fb7d4adadfc1f367aa6559a308d77052ef85b25d1cce`;
pixel diff 19,210 pixels bounded to y=306..601.
No private data was used.
