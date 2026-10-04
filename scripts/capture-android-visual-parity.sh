#!/usr/bin/env bash
set -euo pipefail

: "${CAPTURE_SURFACE:?CAPTURE_SURFACE is required}"
: "${CAPTURE_STATE:?CAPTURE_STATE is required}"
: "${CAPTURE_THEME:?CAPTURE_THEME is required}"
: "${RUNNER_TEMP:?RUNNER_TEMP is required}"

request_json="$RUNNER_TEMP/request.json"
git_sha_file="$RUNNER_TEMP/android-git-sha"
apk="app/build/outputs/apk/debug/app-debug.apk"
out="build/visual-parity/${CAPTURE_SURFACE}--${CAPTURE_STATE}/android"

./gradlew :app:assembleDebug --no-daemon --no-build-cache
if ! install_output="$(adb install -r "$apk" 2>&1)"; then
  printf '%s\n' "$install_output" >&2
  exit 1
fi
printf '%s\n' "$install_output"
if ! grep -qx 'Success' <<<"$install_output"; then
  echo "adb install did not report Success" >&2
  exit 1
fi

# A cold, memory-tight emulator can ANR its launcher while the fixture activity
# starts, and the system dialog then covers the window this capture reads — the
# retained tree came back as "Pixel Launcher isn't responding" instead of the
# sheet. Those dialogs are an emulator artifact, not something this lane
# measures: stop new ones being drawn, and stop the launcher the fixture never
# needs so it cannot ANR in the first place.
if [[ "$CAPTURE_SURFACE" != "sidebar-projection" ]]; then
  adb shell settings put global hide_error_dialogs 1
  adb shell am force-stop com.google.android.apps.nexuslauncher
fi

activity="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["android_activity"])' "$request_json")"
fixture="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["fixture_id"])' "$request_json")"

# The catalogued interaction list is read by kind, never by position: a state
# whose subject is at the list's own end scrolls for it, and one that opens a
# sheet taps for it. An interaction this lane cannot perform is a hard failure —
# running the capture without it would publish pixels of a state that never
# happened.
ordered_args=()
if [[ "$CAPTURE_SURFACE" == "bot-model-config" || "$CAPTURE_SURFACE" == "bot-avatar-editor" || "$CAPTURE_SURFACE" == "sidebar-projection" ]]; then
  ordered_args=(--ordered-actions "$(python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1]))["state_spec"]["interaction"]))' "$request_json")")
fi
interaction_kinds="$(python3 -c '
import json,sys
request = json.load(open(sys.argv[1]))
values = [] if (request["surface"] in ("bot-model-config", "bot-avatar-editor") or request["surface"] == "sidebar-projection") else request["state_spec"].get("interaction", [])
unsupported = [value for value in values if not (value.startswith("tap:") or value == "swipe:list-up")]
if unsupported:
    sys.stderr.write(f"unsupported catalogued interaction: {unsupported}\n")
    raise SystemExit(1)
taps = [value[len("tap:"):] for value in values if value.startswith("tap:")]
if len(taps) > 1:
    raise SystemExit("legacy capture supports only one tap; use ordered actions")
print(taps[0] if taps else "")
print("1" if "swipe:list-up" in values else "")
' "$request_json")"
tap_text="$(sed -n 1p <<<"$interaction_kinds")"
swipe_list_up="$(sed -n 2p <<<"$interaction_kinds")"
expected_accessibility="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["state_spec"].get("post_interaction_accessibility", ""))' "$request_json")"

if [[ "$CAPTURE_SURFACE" == "notification-latest" ]]; then
  adb shell pm grant com.hermesagent.mobile.debug android.permission.POST_NOTIFICATIONS
fi
# Ordered model captures launch inside Python, after installed APK identity is
# collected. This keeps signing/pull work outside the production loading budget.
launch_args=()
if [[ "$CAPTURE_SURFACE" == "bot-model-config" || "$CAPTURE_SURFACE" == "bot-avatar-editor" || "$CAPTURE_SURFACE" == "bot-installed-skills" || "$CAPTURE_SURFACE" == "bot-configured-mcp" ]]; then
  launch_args=(--launch-fixture)
else
  adb shell am start -W -S -n "$activity" \
    --es visual_parity_state "$CAPTURE_STATE" \
    --es visual_parity_theme "$CAPTURE_THEME"
fi

tap_args=()
swipe_args=()
accessibility_args=()
if [[ -n "$tap_text" ]]; then
  tap_args=(--tap-text "$tap_text")
fi
if [[ -n "$swipe_list_up" ]]; then
  swipe_args=(--swipe-list-up)
fi
if [[ -n "$expected_accessibility" ]]; then
  accessibility_args=(--expected-accessibility "$expected_accessibility")
fi

# Tap the named button of a system dialog that is holding the captured window,
# using the bounds the tree just published. The dialog is an emulator artifact,
# so the lane dismisses it the way a person would and then re-reads the window.
dismiss_system_dialog() {
  local label="$1" tree="$2" node bounds centre
  grep -qF "isn't responding" <<<"$tree" || return 0
  node="$(grep -o "<node[^>]*text=\"$label\"[^>]*>" <<<"$tree" | head -1)"
  bounds="$(grep -o 'bounds="[^"]*"' <<<"$node" | head -1)"
  centre="$(awk -F'[][,]' '/\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]/ {print int(($2+$5)/2), int(($3+$6)/2)}' <<<"$bounds")"
  [[ -n "$centre" ]] || return 0
  echo "dismissing a system dialog holding the capture window at $centre"
  # The two coordinates are intentionally split into two arguments.
  # shellcheck disable=SC2086
  adb shell input tap $centre
}

# A Compose semantics tree reaches the platform when an accessibility client
# attaches, and a lazy row's items arrive a beat after the rows around them.
# Wait, bounded, for this state's catalogued description, dismissing a system
# dialog that is already holding the window (the launcher's ANR dialog survives
# the setting above when it was drawn before the lane started). The reference
# capture re-checks the same description itself and still fails if it never
# appears.
#
# A swiping state is deliberately excluded: its subject is below the phone's
# fold until the real drag moves it into view, so waiting here would spend the
# whole budget on a row that cannot be published yet. The reference capture owns
# that wait, bounded, on the far side of the swipe.
if [[ -n "$expected_accessibility" && -z "$swipe_list_up" && ${#ordered_args[@]} -eq 0 ]]; then
  published=""
  for _ in $(seq 1 20); do
    if adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1; then
      published="$(adb shell cat /sdcard/window.xml 2>/dev/null || true)"
      if grep -qF "$expected_accessibility" <<<"$published"; then
        break
      fi
      dismiss_system_dialog Wait "$published"
    fi
    sleep 0.5
  done
  if ! grep -qF "$expected_accessibility" <<<"$published"; then
    # Retain what the platform did publish. A capture that fails without naming
    # the tree it saw cannot be diagnosed from the log alone.
    echo "::warning::the catalogued description never published; the tree held:"
    grep -o 'content-desc="[^"]*"' <<<"$published" | sort -u
    grep -o 'text="[^"]*"' <<<"$published" | grep -v 'text=""' | sort -u
    mkdir -p "$out"
    printf '%s\n' "$published" > "$out/unpublished-tree.xml"
    adb exec-out screencap -p > "$out/unpublished-tree.png" || true
  fi
fi

capture_sidebar_runtime() {
  local phase="$1"
  mkdir -p "$out"
  python3 - "$out/runtime-$phase.json" "$CAPTURE_STATE" <<'PY'
import base64, json, re, sys, subprocess, time
samples = []
for attempt in range(21):
    raw = subprocess.check_output(['adb', 'shell', 'content', 'call', '--uri', 'content://com.hermesagent.mobile.debug.sidebar-projection-runtime', '--method', 'snapshot'], text=True)
    match = re.search(r'snapshot=([A-Za-z0-9+/=]+)', raw)
    assert match, 'Synthetic runtime provider unavailable'
    data = json.loads(base64.b64decode(match[1]))
    samples.append(data)
    json.dump(samples, open(sys.argv[1] + '.samples.json', 'w'), indent=2)
    if data['ready']:
        break
    time.sleep(0.5)
assert data['fixture_id'] == 'sidebar-projection-synthetic-v1' and data['ready'], 'Synthetic fixture did not settle'
assert data['resolved_locale'] == 'en-US' and data['resolved_timezone'] == 'UTC'
assert data['now_millis'] == 1789654800000
if sys.argv[2] == 'projection-draft-reuse':
    assert data['observed_draft_edits'] == 20 and data['overview_reused'] and data['previews_reused']
json.dump(data, open(sys.argv[1], 'w'), indent=2)
PY
}
if [[ "$CAPTURE_SURFACE" == "sidebar-projection" ]]; then
  capture_sidebar_runtime before
fi

python3 .chalk/skills/port-hermes-desktop-surface/scripts/capture-android-reference.py \
  --name "${CAPTURE_SURFACE}--${CAPTURE_STATE}" \
  --state "$CAPTURE_STATE" \
  --theme "$CAPTURE_THEME" \
  --fixture-id "$fixture" \
  --git-sha "$(<"$git_sha_file")" \
  --apk "$apk" \
  --apk-kind debug \
  --activity "${activity#*/}" \
  --out "$out" \
  "${launch_args[@]}" \
  "${ordered_args[@]}" \
  "${tap_args[@]}" \
  "${swipe_args[@]}" \
  "${accessibility_args[@]}"

if [[ "$CAPTURE_SURFACE" == "sidebar-projection" ]]; then
  capture_sidebar_runtime after
fi

python3 scripts/visual_parity_contract.py check-receipt \
  --platform android \
  --receipt "$out/contract.json"

# The focused-Activity contract cannot certify SystemUI. These are supplemental
# OS evidence, not pixels covered by the fixture Activity receipt above.
if [[ "$CAPTURE_SURFACE" == "notification-latest" ]]; then
  sleep 5
  adb shell cmd statusbar expand-notifications
  sleep 1
  adb shell uiautomator dump /sdcard/notification-parity.xml
  adb pull /sdcard/notification-parity.xml "$out/system-shade.xml"
  adb exec-out screencap -p > "$out/system-shade.png"
fi
