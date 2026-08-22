#!/usr/bin/env python3

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


def verify_app_identity(serial: str | None, package: str, activity: str) -> dict[str, str]:
    component = f"{package}/{activity}"
    abbreviated_activity = f".{activity.rsplit('.', 1)[-1]}"

    def contains_expected(text: str) -> bool:
        return package in text and (activity in text or f"/{abbreviated_activity}" in text)

    resolved = shell(serial, "cmd", "package", "resolve-activity", "--brief", component)
    windows = shell(serial, "dumpsys", "window", "windows")
    focused = "\n".join(
        line.strip()
        for line in windows.splitlines()
        if "mCurrentFocus" in line or "mFocusedApp" in line
    )
    if not contains_expected(resolved):
        raise SystemExit(f"capture target did not resolve to {component}: {resolved!r}")
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
