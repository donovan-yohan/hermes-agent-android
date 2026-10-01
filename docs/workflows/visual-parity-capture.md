# Visual-parity capture lane

`visual-parity-capture.yml` is a manual, artifact-only lane. Dispatch one
catalogued surface/state/theme at a 40-character immutable Android commit SHA;
the inline checkout gate verifies that `HEAD` equals that requested SHA before
running repository code. It captures the debug-only synthetic Compose activity on
the same pinned Pixel 6/KVM emulator shape as the instrumented lane. The Android
receipt records its source SHA, local and package-manager base-APK SHA-256 values
(which must match), package version/signing identity, fixture/state, theme,
viewport, focused application, and retained post-interaction accessibility
evidence. It rejects packets that contain secrets, home/private paths, serials,
or uncatalogued or wrong-surface fixture identifiers.

```text
ref: <lowercase 40-character commit SHA>
surface: one key in docs/parity/visual-capture-surfaces.json
state: one key in docs/parity/visual-capture-surfaces.json
theme: dark | light
```

`tool-name-aliases / todo-name-aliases` records a **source-only** Desktop half: no
E2E fixture was identified at its pin that mounts Desktop's composer task panel
or its tool block, so the packet names the pinned `Updated todos` title and the
`todo` icon entry with no companion rendered Desktop pixels rather than inventing
a same-pin comparison.

No workflow job has a write permission, bot token, auto-commit step, or mutation
of the existing `android-exact-head.yml` lane. Artifacts expire after 30 days.

## Bot controls and notification fixture captures

Use a clean debug install on the dedicated synthetic emulator, with no saved
connections. Fixtures never take arbitrary message text, endpoint addresses or
provider inputs. Do not tap notification deep links: those deliberately retain
the production MainActivity target, not a fake route.

```bash
# Set STATE to bot-roster, bot-new, bot-edit, bot-duplicate, bot-move or bot-section.
adb shell am start -W -S -n com.hermesagent.mobile.debug/com.hermesagent.mobile.ProfileAvatarsParityActivity --es visual_parity_state "$STATE" --es visual_parity_theme dark
# The real roster's plus and row ellipsis expose their actual menus.
adb shell am start -W -S -n com.hermesagent.mobile.debug/com.hermesagent.mobile.ComposerReferenceParityActivity --es visual_parity_state steer-controls --es visual_parity_theme dark

adb shell pm grant com.hermesagent.mobile.debug android.permission.POST_NOTIFICATIONS
# Set STATE to latest-preview or latest-preview-off.
adb shell am start -W -S -n com.hermesagent.mobile.debug/com.hermesagent.mobile.NotificationParityActivity --es visual_parity_state "$STATE" --es visual_parity_theme dark
sleep 5
adb shell cmd statusbar expand-notifications
adb exec-out screencap -p > system-shade.png
adb shell uiautomator dump /sdcard/notification-parity.xml
adb pull /sdcard/notification-parity.xml system-shade.xml
```

Both theme values are accepted; SystemUI uses the device's theme, not the
Activity's Compose theme. Configure the OS theme separately for shade comparisons.
The notification fixture waits through the notifier's real startup quiet window,
then posts an ongoing child with “The synthetic release checklist now has three
reviewed items.” and a completed-turn child with “The synthetic release checklist
is ready for review.” These represent separate synthetic presentation cases,
not a live lifecycle transition: the ongoing row intentionally remains visible.
Preview-off uses the production status/conversation fallback. Both children have
generic public versions, verified by `NotificationParityFixtureTest`; testing a
secure lock screen's actual redaction policy still needs a configured device.

The workflow registers `bot-management`, `bot-steer`, and `notification-latest`.
Its standard receipt proves only the focused fixture Activity; notification
`system-shade.png` and `system-shade.xml` are supplemental OS evidence, not
covered by that focused-Activity receipt. Inspect them before making a visual
claim. No Desktop pixels are implied. Production management reads use a tiny
in-memory transport; writes refuse safely (section storage is memory-only).
Steer callbacks are inert: this fixture proves control layout, not dispatch.

## Desktop boundary at the declared pins

The Desktop capture script still captures real Chrome/Electron pixels and now
requires an exact clean disposable export, pinned SHA, synthetic fixture/state,
and theme. Its receipt retains only the upstream SHA and computed DOM/style
contract, never the temporary export path or local renderer URL.

A real **Desktop status-stack or URL-chip** packet cannot yet be automated from
the supplied pins. At `564aef2946c436500a5e80ee117b66b789b3f99a`, Desktop's
public E2E fixture API only launches a mock backend (`apps/desktop/e2e/fixtures.ts`
`setupMockBackend`); it exposes no state-seeder or renderer injection for the
composer-status stores. The actual status stack consumes renderer-local stores,
so setting those stores from a new Playwright process would not mount the pinned
component. The older URL-chip pin has the same absence of a public captured-state
fixture. Handwritten HTML or a copied screenshot would evade this boundary and is
not evidence, so the workflow uploads a `BLOCKED.md` boundary packet rather than
fabricating Desktop pixels.

Unblock Desktop capture by adding a **test-only** export-local E2E scenario at the
upstream pin that mounts the real composer/status components through their real
providers and writes `reference.png`; then call
`.chalk/skills/port-hermes-desktop-surface/scripts/capture-desktop-reference.mjs`
against its CDP page with the catalog's selector, fixture ID, state and theme.
Do not run `perf:serve`, use a real profile, or edit `~/.hermes/hermes-agent`.
