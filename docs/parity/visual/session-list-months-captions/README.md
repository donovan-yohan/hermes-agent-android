# session-list-months-captions

Rendered evidence for #141 / #299: the head-run cutoff, the `Sessions` pool
caption and the per-month tail.

| Folder | Sides | State | Notes |
|---|---|---|---|
| `desktop/`, `android/` | both | `pinned-sessions-month-dividers` | the side-by-side figure in `report.html` |
| `months-scrolled-dark/android/` | Android only | `months-scrolled` | the same seed dragged to its real end by the lane's bounded adb swipes |
| `results-dark/android/` | Android only | `results` | regression capture: the one `Results` pool |
| `all-pinned-dark/android/` | Android only | `all-pinned` | regression capture: the all-pinned sentence |

Provenance. Desktop is one full reference capture at pin
`437116f9497c80d242ce034ff7f5d81dc277a337`, from a clean disposable export
driven through the app's own E2E mock backend and preload bridge, fixture
`session-list-sections-synthetic-v1`, 14 synthetic rows, pinned clock
`2026-09-17T14:20:00Z` UTC, locale `en-US`, dark theme. It is a whole-seed
render in one 1121 px pane, so this one image serves both the top state and the
tail state. Android is captured at
`1457d75eb8e7e8336efa45c68fd2da5f5c34da70` — its code tree is
`2c655a09b15debbfd5dfa5e45b9eef29cc30c6cd`, the tree committed HEAD `9aa0ae8`
carries, which differs from that SHA only in `docs/parity`. Debug APK
`99b356601d18b32e3eca334bc34551c008fbdaa7a4cfb4c6b11173a6b6fdde91`, local and
installed hashes equal, on the pinned emulator shape.

Disclosure. `months-scrolled`, `results` and `all-pinned` are Android
regression captures. Desktop has no rendered counterpart for `results` or
`all-pinned` at this pin, so those two are not a Desktop pixel comparison and
are not cited as one. Android's fixed pane title and the scrolling pool's
caption can both read `SESSIONS`; the title is fixed navigation chrome, the
caption scrolls with the pool. Translation parity is not claimed: the app's
English labels are existing scope.

Every receipt here passes
`python3 scripts/visual_parity_contract.py check-receipt --platform <side>`.
Only the validated contracts and screenshots are stored — the capture
manifest is not, because it prints workstation paths.
