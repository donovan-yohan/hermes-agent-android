# Pinned Desktop group-message link capture

This harness mounts the **production `GroupChatMainView`**, obtained from the
production `openGroupChat()` → `host.openWorkspace(..., { render })` callback.
It imports the existing production SDK, group module, `MessageTextContent`,
ThemeProvider, i18n, and stylesheet. It does not manufacture rendered Markdown,
copy a Vite configuration, or use HTML approximations of group message bodies.
Only `host.openWorkspace` (capture its render callback) and `host.paneVisibility`
(always visible) are fixture window-host seams. Room log/roster/meta are public,
synthetic in-memory inputs. Nothing reads a private conversation.

## Reproduce

Required checkout HEAD:
`36922ad064d65dcf25f8f48df81e1ccf9a55de67`.
Newer `FETCH_HEAD` is recorded but never checked out or used as a source pin.

Place `index.html`, `main.tsx`, `capture.mjs`, and this README at
`apps/desktop/ignored/group-message-links/` inside a disposable pinned checkout
(or apply `capture-only.patch` at its root). Reuse compatible installed workspace
dependencies and a local Google Chrome installation. No installation is performed
by the harness. From `apps/desktop`:

```sh
node ignored/group-message-links/capture.mjs
```

The default packet path is `build/visual-parity/group-message-links/desktop`
under the disposable pinned upstream checkout root, not the Android worktree.
Override it with `DESKTOP_LINKS_OUT=/absolute/path` when reproducing elsewhere.
The recorded capture explicitly writes to the Android worktree's build packet;
from the disposable checkout's `apps/desktop`, its command is:

```sh
DESKTOP_LINKS_OUT=/Users/donovanyohan/Documents/Programs/personal/hermes-mobile-chat-links/build/visual-parity/group-message-links/desktop node ignored/group-message-links/capture.mjs
```

The manifest records the resolved output as an explicit `DESKTOP_LINKS_OUT`
assignment so replay does not depend on an ambient environment override.
Do not run against or write into `~/.hermes/hermes-agent`.

The existing production Vite config runs on **127.0.0.1:52762**, `strictPort`,
`envDir:false`, `configLoader:'runner'`, no HMR. Vite's writable cache is explicitly
under this ignored capture directory. Runner loading avoids Vite's default
bundled-config `.vite-temp` writes through reused dependency symlinks. Playwright
uses a fresh headless Chrome context per theme. Every browser request outside the exact
loopback origin is aborted; service workers are disabled. No backend, auth,
Electron, Gradle, Android production edit or downloaded artifact is involved.

## Fixtures and verification boundary

The first room message is exactly:

```md
[download the latest apk](https://github.com/donovan-yohan/hermes-agent-android/actions/runs/39120359182/artifacts/12134162983)
```

The second is the equivalent bare URL. Further messages exercise a heading,
list, inline code, structured Kotlin fence, and unsafe JavaScript/data links.
The historical labeled-link edge is reproducible as the first room entry:
its visible label must remain `download the latest apk`, and its anchor href
must be the full exact URL above, not text with appended Markdown punctuation.

DOM assertions verify exact label/href, bare-URL identity, heading/list structure,
non-linkified inline/fenced code, and absence of active unsafe-scheme anchors.
A real Playwright touch tap records a trusted `pointerType=touch` click with a
one-shot target `preventDefault` safety barrier. A second trusted tap, with that
barrier removed, runs the **unmodified production click handler** and verifies
its exact URL in the production preview store. The browser document stays on
the loopback fixture route, and no preview webview is mounted or fetched.
This distinguishes anchor destination / Desktop preview routing from browser
navigation and from an actual file download. **No attachment/download claim.**

Screenshots are at 412 CSS-pixel width, scale factor 1, mono light and dark,
with reduced motion and a fixed public fixture clock. Production ThemeProvider
applies the themes, not a palette copied into fixture CSS. Android's inspected
`AppearanceSelection()` default is **Nous / System**, not Mono; Mono is an
explicit paired comparison setting. Platform fonts and native layouts may differ.

## Packet

- `desktop-group-mono-{light,dark}-412.png`: actual group UI viewport.
- `desktop-labeled-apk-mono-{light,dark}-412.png`: real first message crop.
- `capture-manifest.json`: exact pin, versions, command, source SHA-256 + pinned
  Git blob IDs (including loaded tracked source), harness hashes, screenshot
  hashes, theme/DOM measurements, tap/routing records, checks and limitations.
- `network-requests.json`: allowed origin and actual request/blocked lists.
- Harness copies and `capture-only.patch`: reproduction without production edits.

### Capture issues

Initial attempts exposed the production theme's inherited remote Courier Prime
stylesheet request; the allow-only-loopback guard correctly aborted it. The final
packet retains the expected blocked-font console messages and request records.
No remote content was fetched. Package versions with hidden `package.json`
exports are read by resolving and walking to the actual package manifest.

The first two unsuccessful attempts used Vite's default bundled config loader.
Inspection showed that loader writes then unlinks transient `.vite-temp` config
files through the reused node_modules symlink into the installed upstream tree.
That was an unintended write outside scope; no installed production source was
edited. The final harness switches to `configLoader:'runner'` to prevent that
write path. This qualification applies to the initial attempts, not just the
reproducibility recipe, and must not be omitted when reporting scope compliance.

This is a production **group-surface fixture**, not a full installed Desktop /
Electron screenshot or live-room end-to-end test. It proves rendered semantics
and local production routing, not artifact availability, GitHub permissions,
remote browser behavior or APK installability. No private content is captured.
