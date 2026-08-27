#!/usr/bin/env bash
# Put the CI emulator into the state the instrumented lane assumes.
#
# This exists as a file rather than inline workflow YAML because the emulator
# action runs its `script:` input one line at a time, each in its own `sh -c`:
# a multi-line shell construct there would be split apart mid-statement.
#
# Everything here is a precondition of a test in `app/src/androidTest/`, and
# every one of them is invisible on a developer's own device, where the screen
# is on, the keyguard is gone and an input method has been chosen long ago.
set -euo pipefail

say() { printf '  %s\n' "$1"; }

# 1. Input focus. A window that does not have it is never served by the input
#    method: `View.onFocusChanged` only calls `InputMethodManager.focusIn` when
#    `mAttachInfo.mHasWindowFocus` is set, and nothing retries afterwards. A
#    keyguard over a freshly booted emulator is the ordinary way to lose it.
adb shell wm dismiss-keyguard >/dev/null 2>&1 || say "keyguard dismissal was refused; continuing"
adb shell svc power stayon true >/dev/null 2>&1 || say "stay-awake was refused; continuing"

# 2. The soft keyboard. The AVD reports a hardware keyboard, which suppresses
#    the soft one unless this setting says otherwise.
adb shell settings put secure show_ime_with_hard_keyboard 1

# 3. A bound input method. `InputMethodManager.isActive` stays false until one
#    is bound, and which one a system image enables by default is not a
#    contract, so bind whichever one the image actually ships.
selected="$(adb shell ime list -s -a 2>/dev/null | tr -d '\r' | head -1 || true)"
if [ -n "$selected" ]; then
  adb shell ime enable "$selected" >/dev/null 2>&1 || true
  adb shell ime set "$selected" >/dev/null 2>&1 || true
  say "input method: $selected"
else
  say "this system image lists no input method; ComposerImeTest will say so"
fi

# 4. Evidence. When the lane goes red on the input connection, the first
#    question is which method was bound and to what, and the answer has to be in
#    the log that already exists rather than in a rerun.
adb shell dumpsys input_method 2>/dev/null | sed -n '1,12p' || true
