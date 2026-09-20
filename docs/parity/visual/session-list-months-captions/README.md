# session-list-months-captions

Rendered evidence for #141 / #299: the head-run cutoff, the `Sessions` pool
caption and the per-month tail.

| Folder | Sides | State | Notes |
|---|---|---|---|
| `desktop/`, `android/` | both | `pinned-sessions-month-dividers` | the side-by-side figure in `report.html` |
| `months-scrolled-dark/android/` | Android only | `months-scrolled` | the same seed dragged to its real end by the lane's bounded adb swipes |
| `results-dark/android/` | Android only | `results` | regression capture: the one `Results` pool |
| `all-pinned-dark/android/` | Android only | `all-pinned` | regression capture: the all-pinned sentence |
| `archived-pinned-dark/android/` | Android only | `archived-pinned` | regression capture (#146): the Archived view with the `PINNED` section above the pool |

Provenance. Desktop is one full reference capture at pin
`437116f9497c80d242ce034ff7f5d81dc277a337`, from a clean disposable export
driven through the app's own E2E mock backend and preload bridge, fixture
`session-list-sections-synthetic-v1`, 14 synthetic rows, pinned clock
`2026-09-17T14:20:00Z` UTC, locale `en-US`, dark theme. It is a whole-seed
render in one 1121 px pane, so this one image serves both the top state and the
tail state. Every Android state is captured at the committed head
`808b882ec52a0ecf60b217587b2bed1a27ad3ebb`, from a debug APK whose local and
installed SHA-256 are equal, on the pinned emulator shape. Per-state APK
receipts, as the contracts record them:

| State | `android_git_sha` | APK SHA-256 (local = installed) |
|---|---|---|
| `pinned-sessions-month-dividers` | `808b882ec52a0ecf60b217587b2bed1a27ad3ebb` | `105b0f9ded02a2bcbe2014d2e71e4fd3588f981de9d3a711c93f10af723a0a05` |
| `months-scrolled` | `808b882ec52a0ecf60b217587b2bed1a27ad3ebb` | `e6cd6dab18dfef66f0b01826bedc19c562abb01933516f3f2cce9ad219df1e4b` |
| `results` | `808b882ec52a0ecf60b217587b2bed1a27ad3ebb` | `98d1d4c1ce0389c8b048804cf37d82da13fc48e45ddfd799a44cb4df8b6d82a4` |
| `all-pinned` | `808b882ec52a0ecf60b217587b2bed1a27ad3ebb` | `c870b26fa07fc34f557f582b19f5cdefece1623e574facfa1666e33fdefc7b7e` |

`archived-pinned-dark/android/` is a later Android-only capture, taken at the
committed head `e85c69a2119b7c4067ba580d23c87e13b1dd88f2` (APK SHA-256
`f1c8c4a5a8e41641ed98f59dea075f0a4ff9a6e2d3451db39134ddfd07c812ae`, local =
installed) by the `session-list-sections` capture lane,
CI run [`35311626235`](https://github.com/donovan-yohan/hermes-agent-android/actions/runs/35311626235).
Its receipt passes `check-receipt --platform android` at the same path pattern
as the rest.

Disclosure. `months-scrolled`, `results`, `all-pinned` and `archived-pinned` are
Android regression captures. Desktop has no rendered counterpart for `results`
or `all-pinned` at this pin, so those two are not a Desktop pixel comparison and
are not cited as one. `archived-pinned` is the same kind of capture: the closest
Desktop render is `docs/parity/visual/session-list-archived-view/desktop/`, but
that packet is pinned at `3ca096de` (a different pin and a different fixture), so
it is historical rather than a same-pin, same-fixture comparison and is not
cited as one. Android's fixed pane title and the scrolling pool's caption can
both read `SESSIONS`; the title is fixed navigation chrome, the caption scrolls
with the pool. Translation parity is not claimed: the app's English labels are
existing scope.

Every receipt here passes
`python3 scripts/visual_parity_contract.py check-receipt --platform <side>`.
Only the validated contracts and screenshots are stored — the capture
manifest is not, because it prints workstation paths.
