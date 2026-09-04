# How the README media was captured

Every file under `docs/media/` was recorded from the app running on an emulator,
not mocked up. This page records what each file shows and exactly how it was
produced, so a later pass can reproduce or replace it.

## Provenance

| Fact | Value |
|---|---|
| Build SHA | `0e4c38e84b00ea41be9b8f9f47ae14ba8590e895` (branch `docs/media-captures`, off `main`) |
| APK | `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`, installed with `adb -s emulator-5554 install -r` |
| Package | `com.hermesagent.mobile.debug`, versionName `0.2.0-phase2` |
| Device | `emulator-5554`, AVD `Pixel_10_Pro`, 1280×2856 @480dpi, Android 17 |
| Backend | A Remote Gateway row labelled `QA Remote`, Hermes 0.21.0, profile `kame-qa` |
| Captured | 2026-09-04 |

Screenshots were taken with:

```bash
adb -s emulator-5554 exec-out screencap -p > <file>.png
```

Recordings were taken with, then trimmed and converted on the host:

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

## Gates every file passed

1. **The app was on screen.** Before each capture, `adb shell dumpsys window`
   was read and both `mCurrentFocus` and `mFocusedApp` had to name
   `com.hermesagent.mobile.debug`; an unfocused or empty reading refused the
   capture rather than saving it. An earlier pass without this gate tapped out
   into the launcher's search bar and saved that instead.
2. **The frame was looked at.** Every PNG, and a sampled contact sheet of every
   recording, was opened and read before it was committed.
3. **No real host name is visible.** Checked per file below. The chat header
   shows the session title and a connection word (`Connected`, `Streaming ·
   Connected`) — never an address. The sessions drawer shows the connection's
   *label*, `QA Remote`, which is a name the person typed, not a host.
4. **Session content is synthetic.** Every prompt in these captures was written
   for this pass and is about the Kotlin language. Nothing here is private
   conversation.

## Screenshots

`docs/media/screenshots/`

| File | Size | Shows | Real host name visible? |
|---|---|---|---|
| `sessions-light.png` | 288 KB | The sessions drawer over a transcript: `PINNED` / `TODAY` / `THIS WEEK` / `LAST WEEK` / `OLDER` calendar sections, the active row highlighted, one unread dot, the profile chip at the foot | No |
| `sessions-dark.png` | 289 KB | The same drawer in dark mode | No |
| `chat-light.png` | 174 KB | One turn in a session: the sent prompt, the reply with inline code spans, the per-message action row with its `WIP` markers, the collapsed reasoning row, and the composer with its model chip | No |
| `chat-dark.png` | 174 KB | The same transcript in dark mode | No |
| `composer-completions-dark.png` | 230 KB | The slash-command completion popup open over the composer after typing `/`: `/new`, `/reset`, `/clear`, `/redraw`, `/history` with their descriptions | No |
| `appearance-themes-dark.png` | 232 KB | The Appearance screen: the Light / Dark / System mode control and the full skin list with per-skin swatches, Nous selected | No |
| `system-panel-dark.png` | 97 KB | The System panel: gateway status dot and version line, `Restart gateway` and `Update Hermes` actions, and the log filters behind their `WIP` marker | No |

The keyboard was dismissed before `composer-completions-dark.png` so the frame
is the app's own surface rather than half Gboard; the completion popup survives
losing the input method.

## Demos

`docs/media/demo/`

| File | Size | Length | Shows |
|---|---|---|---|
| `live-turn.mp4` | 123 KB | 12.0 s | A new session from `No messages yet`: tap the composer, type a short prompt, send, the turn streams with an elapsed counter, and the reply lands as a fenced Kotlin block |
| `live-turn.gif` | 791 KB | 12.3 s, 147 frames @12 fps, 540×1204 | The same clip as a GIF, for GitHub-rendered Markdown |
| `switch-sessions.mp4` | 162 KB | 10.0 s | Open the sessions drawer from one session, tap a different one, and watch the transcript replace itself with the other session's turn |
| `switch-sessions.gif` | 895 KB | 10.6 s, 127 frames @12 fps, 540×1204 | The same clip as a GIF |

Both GIFs are well under the 4 MB budget. Neither clip shows a host name: the
drawer in `switch-sessions` shows connection labels and session titles only.

## What could not be captured

**The Gateways screen, and therefore `gateways-dark.png` and any
`connect-remote-gateway` clip.** `GatewayScreen` wraps itself in
`SecureScreenLifetime`
(`app/src/main/kotlin/com/hermesagent/mobile/ui/gateway/GatewayScreen.kt:125`),
which sets `FLAG_SECURE` on the window
(`app/src/main/kotlin/com/hermesagent/mobile/ui/common/SecureScreen.kt:56`).
That is the secrets policy working as designed — it is where passwords,
passphrases and Gateway URLs are typed — and it makes `screencap` and
`screenrecord` return a black frame for the whole screen, including the
connection-kind chooser and an empty editor. Confirmed on this pass: the capture
came back black while the accessibility tree read the screen correctly.

The one non-secure surface that lists saved connections is the drawer's
`Registered gateways` sheet, and it renders each row's Gateway URL — including a
real tailnet address — so it is unusable here for the opposite reason.

Capturing this surface would need either a disposable connection set whose
labels and URLs are placeholders, or a build that drops `FLAG_SECURE`. Neither
belongs in a media pass, so the README should not reference a Gateways
screenshot until one of them exists.
