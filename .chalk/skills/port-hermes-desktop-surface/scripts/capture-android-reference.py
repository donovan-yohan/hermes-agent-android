#!/usr/bin/env python3

"""Capture one Android visual-parity reference, fenced on who is on screen.

A screenshot proves nothing unless the app in it is this app, so every capture
is gated on `adb` agreeing that the expected package/activity is both resolvable
and focused.

Two facts about where that focus lives, both learned the hard way:

* `dumpsys window windows` carried `mCurrentFocus` / `mFocusedApp` through
  Android 15. On API 36+ images they moved to the display-contents section that
  plain `dumpsys window` prints, and the `windows` subcommand emits neither — so
  the gate aborted every capture on a Pixel 10 Pro emulator however correct the
  foreground app was. The subcommand is still asked first and plain `dumpsys
  window` is the fallback, because older images answer the subcommand and never
  reach it.
* A Compose dropdown or dialog is its own window, so `mCurrentFocus` reads
  `Pop-Up Window` while `mFocusedApp` still names the activity. The two lines are
  therefore **joined** before matching: the popup is this app's popup exactly
  when the joined text names this app. Matching `mCurrentFocus` alone would
  refuse every menu capture.

The check is not loosened by either: an empty focus reading is a refusal, not a
pass, and the joined text must still carry the expected package and activity.
"""

import argparse
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_PACKAGE = "com.hermesagent.mobile.debug"
DEFAULT_ACTIVITY = "com.hermesagent.mobile.MainActivity"


def adb(serial: str | None, *args: str, binary: bool = False):
    command = ["adb"]
    if serial:
        command += ["-s", serial]
    command += list(args)
    return subprocess.run(
        command,
        check=True,
        capture_output=True,
        text=not binary,
    ).stdout


def shell(serial: str | None, *command: str) -> str:
    return adb(serial, "shell", *command).strip()


def focus_lines(dump: str) -> str:
    """The `mCurrentFocus` / `mFocusedApp` lines of a `dumpsys window` dump, joined.

    Joined rather than matched one at a time: see the module docstring — a
    Compose popup owns `mCurrentFocus` and leaves the activity name on
    `mFocusedApp`.
    """
    return "\n".join(
        line.strip()
        for line in dump.splitlines()
        if "mCurrentFocus" in line or "mFocusedApp" in line
    )


def read_focus(serial: str | None) -> str:
    """Whichever `dumpsys window` form this image answers with focus lines."""
    focused = focus_lines(shell(serial, "dumpsys", "window", "windows"))
    if focused:
        return focused
    # API 36+ moved them out of the `windows` subcommand entirely.
    return focus_lines(shell(serial, "dumpsys", "window"))


def verify_app_identity(serial: str | None, package: str, activity: str) -> dict[str, str]:
    component = f"{package}/{activity}"
    abbreviated_activity = f".{activity.rsplit('.', 1)[-1]}"

    def contains_expected(text: str) -> bool:
        return package in text and (activity in text or f"/{abbreviated_activity}" in text)

    resolved = shell(serial, "cmd", "package", "resolve-activity", "--brief", component)
    focused = read_focus(serial)
    if not contains_expected(resolved):
        raise SystemExit(f"capture target did not resolve to {component}: {resolved!r}")
    if not focused:
        raise SystemExit(
            "adb reported no mCurrentFocus/mFocusedApp lines from either "
            "`dumpsys window windows` or `dumpsys window`; the capture target "
            "cannot be proved to be on screen"
        )
    if not contains_expected(focused):
        raise SystemExit(f"capture target is not the focused Android activity: expected {component}")
    return {"component": component, "resolvedActivity": resolved, "focusedWindow": focused}


def main() -> None:
    parser = argparse.ArgumentParser(description="Capture an Android visual-parity reference from a connected device.")
    parser.add_argument("--name", required=True, help="surface and state, for example projects-overview")
    parser.add_argument("--out", help="output directory; defaults to build/visual-parity/<name>/android")
    parser.add_argument("--serial", help="adb device serial when more than one device is connected")
    parser.add_argument("--package", default=DEFAULT_PACKAGE, help="expected Android application package")
    parser.add_argument("--activity", default=DEFAULT_ACTIVITY, help="expected focused Android activity")
    args = parser.parse_args()

    state = adb(args.serial, "get-state").strip()
    if state != "device":
        raise SystemExit(f"adb device is not ready: {state!r}")
    identity = verify_app_identity(args.serial, args.package, args.activity)

    output = Path(args.out or f"build/visual-parity/{args.name}/android").resolve()
    output.mkdir(parents=True, exist_ok=True)
    screenshot = adb(args.serial, "exec-out", "screencap", "-p", binary=True)
    (output / "reference.png").write_bytes(screenshot)

    contract = {
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "name": args.name,
        "serial": args.serial,
        "application": identity,
        "device": {
            "model": shell(args.serial, "getprop", "ro.product.model"),
            "manufacturer": shell(args.serial, "getprop", "ro.product.manufacturer"),
            "android": shell(args.serial, "getprop", "ro.build.version.release"),
            "sdk": shell(args.serial, "getprop", "ro.build.version.sdk"),
            "size": shell(args.serial, "wm", "size"),
            "density": shell(args.serial, "wm", "density"),
            "fontScale": shell(args.serial, "settings", "get", "system", "font_scale"),
        },
    }
    (output / "contract.json").write_text(json.dumps(contract, indent=2) + "\n", encoding="utf-8")
    print(f"android reference: {output / 'reference.png'}")
    print(f"device contract: {output / 'contract.json'}")


if __name__ == "__main__":
    main()
