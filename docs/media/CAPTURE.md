# How the README media was captured

Every file under `docs/media/` was recorded from the app running on an emulator,
not mocked up. This page records what each file shows, how it was produced, and
— just as important — which frames this pass could **not** produce and why.

## Provenance

| Fact | Value |
|---|---|
| App build | `75c133d3116debe6ddf2badd79a6b24fc383c354`, whose `app/` tree is identical to `origin/main` at `406f31bee29b8b3fd1035a2e21426b5549df7e65` (`main` had moved, docs-only, so the APK is byte-identical to main's) |
| APK | `./gradlew assembleDebug`, installed with `adb -s emulator-5554 install -r` |
| Package | `com.hermesagent.mobile.debug`, versionName `0.2.0-phase2` |
| Device | `emulator-5554`, AVD `Pixel_10_Pro`, 1280×2856 @480dpi, Android 17 |
| Backend | A Remote Gateway row, Hermes 0.21.0, profile `kame-qa` |
| Status bar | SystemUI demo mode: 12:00, full battery, full Wi-Fi, no notification icons |
| Captured | 2026-09-05 |

Screenshots:

```bash
adb -s emulator-5554 exec-out screencap -p > <file>.png
```

Recordings, then trimmed and converted on the host:

```bash
adb -s emulator-5554 shell screenrecord --time-limit <n> --size 640x1428 \
  --bit-rate 6000000 /sdcard/x.mp4
adb -s emulator-5554 exec-out cat /sdcard/x.mp4 > x.mp4
ffmpeg -ss <start> -t <len> -i x.mp4 -c:v libx264 -preset slow -crf 24 \
  -pix_fmt yuv420p -movflags +faststart -an <file>.mp4
ffmpeg -i <file>.mp4 -vf "fps=12,scale=540:-2:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse" <file>.gif
```

`--size 640x1428` is the phone's own aspect halved. Without it `screenrecord`
cannot configure the AVC encoder at 1280×2856, falls back to 720×1280, and
pillarboxes the frame in black.

The status bar is driven rather than left to the emulator, because the real
clock is unreadable against a dark skin:

```bash
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true -e mobile hide
adb shell am broadcast -a com.android.systemui.demo -e command exit   # afterwards
```

## Gates every file passed

1. **The app was on screen.** Before each capture, `adb shell dumpsys window`
   was read and both `mCurrentFocus` and `mFocusedApp` had to name
   `com.hermesagent.mobile.debug`; an unfocused or empty reading refused the
   capture rather than saving it. An earlier pass without this gate tapped out
   into the launcher's search bar and saved that instead.
2. **The frame was looked at.** Every PNG was opened and read. Every clip was
   reduced to a contact sheet dense enough to cover every scroll position —
   one tile per 10 GIF frames, not a handful of evenly spaced samples. The
   sparse version is what let the previous pass ship internal task ids.
3. **No real host name is visible.** The chat header shows the session title
   and a connection word (`Connected`, `Streaming · Connected`) — never an
   address. The drawer shows the connection's *label*, which is a name the
   person typed.
4. **Every visible session title is synthetic.** A separate check from the
   host-name one, and the two are not interchangeable: a frame can be free of
   addresses and still put an internal task id on the page. Titles are
   enumerated per file below.

## Screenshots

`docs/media/screenshots/`

| File | Size | Shows | Titles visible | Host name? |
|---|---|---|---|---|
| `chat-light.png` | 286 KB | One turn: the prompt, a numbered reply with bold and italic runs, the collapsed reasoning row above it, the action row with its `WIP` markers, and the composer's model chip | `Draft weekly planning meeting ag…` | No |
| `chat-dark.png` | 286 KB | The same session, same scroll position, dark | same | No |
| `composer-completions-dark.png` | 321 KB | The slash-command popup over the composer: `/new`, `/reset`, `/clear`, `/redraw`, `/history` | same | No |
| `appearance-themes-dark.png` | 226 KB | The Appearance screen: Light / Dark / System and the full skin list with per-skin swatches | none | No |
| `system-panel-dark.png` | 97 KB | The System panel: status dot and version, `Restart gateway` / `Update Hermes`, log filters behind their `WIP` marker | none | No |
| `sessions-light.png` | 152 KB | **Stale — see [Open blockers](#open-blockers).** The drawer from an earlier pass | pre-rename set | No |
| `sessions-dark.png` | 150 KB | **Stale — see [Open blockers](#open-blockers).** | pre-rename set | No |

The two chat frames are the same session at the same scroll offset, so the
collapsed reasoning row sits in the same place in both and the header is
identical. Neither clips the approval label: the earlier `Manua` truncation was
a function of that session's context string, and a session whose string is
shorter renders `Manual` in full. The keyboard was dismissed before
`composer-completions-dark.png` so the frame is the app's own surface rather
than half Gboard; the completion popup survives losing the input method.

## Demos

`docs/media/demo/`

| File | Size | Length | Shows | Titles visible |
|---|---|---|---|---|
| `live-turn.mp4` | 191 KB | 17.183 s | A new session from `No messages yet`: tap the composer, type a short prompt, send, the turn streams, the reply lands with inline code spans, the reasoning row collapses to `Thought for 1s` | `New session`, `Summarise Kotlin data classes` |
| `live-turn.gif` | 888 KB | 17.160 s, 206 frames @12 fps, 540×1204 | The same clip for GitHub-rendered Markdown | same |

**Budget: a GIF here stays under 1 MB.** It is. The wider 4 MB ceiling the brief
allowed is not the number to design to — a README hero that costs a reader four
megabytes before the first paragraph is not worth it.

The contact sheet for `live-turn.gif` is 21 tiles, one per 10 frames, covering
the clip end to end. Every tile shows only the chat surface; the drawer is never
opened, so the only titles in the file are the two above.

## Open blockers

### The sessions drawer cannot be photographed clean

`sessions-light.png` and `sessions-dark.png` are from an earlier pass and still
carry the review's P2 findings (an internal connection label and profile chip, a
`Reply with the single word pong.` preview under every row, an unreadable dark
clock). `switch-sessions.mp4` and `.gif` have been **deleted** from the tree
rather than re-recorded: their frames 40–75 showed five internal task-id rows,
and this pass could not produce a replacement.

The reason is the Gateway, not the app. The session list backfills lazily and
without bound: every mutation — pinning a row, marking one unread, switching
profile, restarting the app — triggers a page fetch that pulls another slice of
months-old history into `OLDER`, and those rows are named after Kanban task ids
(`work kanban task t_…`). Over this pass **424 rows were archived** across five
sweeps, each ending in two consecutive full passes that archived nothing, and
each time the next mutation repopulated the section within seconds. The five
seeded rows sit at the top; `OLDER` begins around y≈1470 of a 2856px screen, so
the leak is inside the viewport with no scrolling at all.

Archiving is therefore not a fix. Getting a clean drawer frame needs a Gateway
whose history *is* the demo set — a throwaway `hermes serve` over an empty
profile directory, or the Termux Local route — rather than the shared QA
instance.

**History caveat:** the deleted clip is still reachable in this branch's
history — it was introduced in `ba4dc93`, the branch's first commit, and the
branch has since been rebased, so that SHA is the one to look for rather than
the original. Deleting the file from the tree does not remove the blobs.
**Squash this branch on merge, or expunge those objects, before anything here
reaches a public remote.**

### The `demo` profile was attempted and reverted

The plan was to replace the `kame-qa` chip with a neutral `demo` profile.
`hermes profile create demo --clone-from kame-qa` copies `config.yaml`, `.env`
and `SOUL.md` but **not** `auth.json`, and the profile that results has a
673-byte credential stub against the source's 30 KB. On that profile the Gateway
refused the model switch (`Hermes could not switch the model. Try again.`) and
the first turn failed outright (`Hermes ended this turn unexpectedly`). The
supported alternative, `--clone-all`, aborts partway through copying the source
profile's home directory on stale Dart perf FIFOs. The only remaining route was
to hand-copy live provider credentials into a new profile, which is not a call a
media pass should make on its own.

The profile was deleted (`hermes profile delete demo`), its wrapper script with
it. No other profile was touched, the sticky default was left alone, and the
shared Gateway unit was never restarted. The chip in these frames therefore
still reads `kame-qa`.

### What did get fixed

The connection label was renamed to a neutral one for the captures and restored
afterwards; the seeded sessions now carry prompts that match their titles
(`Draft a five-item agenda for a weekly planning meeting.`, `List four things a
design review should check.`, `Outline the steps to refactor an auth middleware
safely.`, `Write a short release checklist for an Android app.`) instead of
`pong`; the status bar is driven; and the two chat frames share one session
state. Renaming those sessions to the shorter target titles was refused by the
Gateway — `Rename failed. Try a different title.`, because archived sessions
still hold those names — so the descriptive auto-titles stand, which satisfies
the same intent: every row's preview matches its title.

## What could not be captured at all

**The Gateways screen, and therefore `gateways-dark.png` and any
`connect-remote-gateway` clip.** `GatewayScreen` wraps itself in
`SecureScreenLifetime`
(`app/src/main/kotlin/com/hermesagent/mobile/ui/gateway/GatewayScreen.kt:125`),
which sets `FLAG_SECURE` on the window
(`app/src/main/kotlin/com/hermesagent/mobile/ui/common/SecureScreen.kt:56`).
That is the secrets policy working as designed — it is where passwords,
passphrases and Gateway URLs are typed — and it makes `screencap` and
`screenrecord` return a black frame for the whole screen, including the
connection-kind chooser and an empty editor.

The one non-secure surface that lists saved connections is the drawer's
`Registered gateways` sheet, and it renders each row's Gateway URL — including a
real tailnet address — so it is unusable for the opposite reason.
