# Cron management capture and live verification

The core Cron surface uses the `cron-management-synthetic-v1` fixture and the
Desktop/Gateway source pin `5fc490e2b34123212d73ef316086f939567a725a`.
It is separate from historical `bot-routines` evidence.

## Android

Use a dedicated synthetic emulator, never a person's configured app instance.
The fixture accepts only catalogued state/theme inputs. It uses the production
controller and composables over an in-memory API, not a live scheduler.

Build from clean immutable source, install that APK, and record its exact commit
before capture. The existing capture script pulls the installed base APK and
checks byte identity/signing, resolved/focused activity, geometry and retained
platform accessibility labels. Repeat each state in dark and light.

```bash
export CAPTURE_SURFACE=cron-management
export CAPTURE_STATE=detail
export CAPTURE_THEME=dark
export ANDROID_SERIAL=emulator-5560 # dedicated disposable device only
export RUNNER_TEMP="$(mktemp -d "${TMPDIR:?}/cron-capture.XXXXXX")"
test -z "$(git status --porcelain)"
python3 scripts/visual_parity_contract.py describe \
  --surface "$CAPTURE_SURFACE" --state "$CAPTURE_STATE" --theme "$CAPTURE_THEME" \
  > "$RUNNER_TEMP/request.json"
git rev-parse HEAD > "$RUNNER_TEMP/android-git-sha"
bash scripts/capture-android-visual-parity.sh
```

The catalog registers populated, empty, error, detail, sidebar, queued,
delete-confirm, script and blueprints. The editor deliberately retains secure
window protection and is **not** a device-screenshot state. Its fields/actions
are verified by Compose journeys and source comparison; a black screenshot is
not substituted as visual evidence. No capture bypass is added to production.

Queue, delete confirmation and selected-detail states are fixture-staged through
production controller actions. A capture proves those layouts, not a human
trigger/delete gesture or actual mutation. Sidebar expansion is driven through
the catalogued touch action and recorded separately.

For a local batch after a single immutable build/install, call
`.chalk/skills/port-hermes-desktop-surface/scripts/capture-android-reference.py`
directly with the catalogued arguments and `--launch-fixture`. This avoids
rebuilding the same APK eighteen times. Give each state/theme its own output
directory and still require installed-byte/signature, focused-activity and
bracketed accessibility checks. The shell wrapper remains the CI entry point.
For the sidebar, do not wait for Run history before its expansion tap; that
heading is inside the collapsed content. The reference script checks it after
the catalogued tap.

## Desktop

The checked-in component packet under `docs/media/cron-management-v1/desktop`
mounts unchanged real components and styles against a narrowly synthetic API.
Its own report distinguishes fixture framing, public font assets, redacted
publication derivatives and excluded machine-specific diagnostics. Canonical
screenshots and receipts preserve original hashes. Run its publication verifier
before using or republishing it.

This is a real component reference, not a native Electron/full-shell screenshot
or a live backend test. Paired review must classify copy, action order, glyphs,
phone adaptations and any uncaptured states explicitly.

## Actual backend boundary

`tools/cron-smoke/README.md` documents scratch-only real-upstream router startup,
Python acceptance and the opt-in production Kotlin client test. The host uses
real profile discovery/stores/manual script execution and no LLM. It never
contacts a person's Gateway or imports their profile. Its header gate is harness
machinery, not native sign-in evidence.

Use a new empty scratch directory for each run. The Kotlin test is forced to
execute when the smoke URL is present; it cannot be reported from Gradle test
cache. Tokens remain in process memory/environment and never become test inputs,
properties, logs or screenshot content. Tests skipped without opt-in are not
live passes.

## Build isolation

Never execute Gradle concurrently in one checkout, including separate test
filters. A second invocation can overwrite runtime class files and XML reports
under the first. Isolated worktrees are required for parallel red/green runs.
Freeze source, run the integrated gate serially, then capture the resulting APK.
