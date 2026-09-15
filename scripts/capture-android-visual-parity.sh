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

activity="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["android_activity"])' "$request_json")"
fixture="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["fixture_id"])' "$request_json")"
tap_text="$(python3 -c 'import json,sys; values=json.load(open(sys.argv[1]))["state_spec"]["interaction"]; print(values[0][4:] if values else "")' "$request_json")"
expected_accessibility="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["state_spec"].get("post_interaction_accessibility", ""))' "$request_json")"

adb shell am start -W -n "$activity" \
  --es visual_parity_state "$CAPTURE_STATE" \
  --es visual_parity_theme "$CAPTURE_THEME"

tap_args=()
accessibility_args=()
if [[ -n "$tap_text" ]]; then
  tap_args=(--tap-text "$tap_text")
fi
if [[ -n "$expected_accessibility" ]]; then
  accessibility_args=(--expected-accessibility "$expected_accessibility")
fi

# A Compose semantics tree reaches the platform when an accessibility client
# attaches, and a lazy rail's rows arrive one composition later than the rows in
# a plain column. Wait, bounded, for this state's catalogued description so a
# receipt never retains a half-published tree; the reference capture re-checks
# the same description itself and still fails if it never appears.
if [[ -n "$expected_accessibility" ]]; then
  for _ in $(seq 1 20); do
    if adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 &&
      adb shell cat /sdcard/window.xml 2>/dev/null | grep -qF "$expected_accessibility"; then
      break
    fi
    sleep 0.5
  done
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
  "${tap_args[@]}" \
  "${accessibility_args[@]}"

python3 scripts/visual_parity_contract.py check-receipt \
  --platform android \
  --receipt "$out/contract.json"
