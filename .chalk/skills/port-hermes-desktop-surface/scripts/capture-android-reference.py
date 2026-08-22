#!/usr/bin/env python3

import argparse
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path


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


def main() -> None:
    parser = argparse.ArgumentParser(description="Capture an Android visual-parity reference from a connected device.")
    parser.add_argument("--name", required=True, help="surface and state, for example projects-overview")
    parser.add_argument("--out", help="output directory; defaults to build/visual-parity/<name>/android")
    parser.add_argument("--serial", help="adb device serial when more than one device is connected")
    args = parser.parse_args()

    state = adb(args.serial, "get-state").strip()
    if state != "device":
        raise SystemExit(f"adb device is not ready: {state!r}")

    output = Path(args.out or f"build/visual-parity/{args.name}/android").resolve()
    output.mkdir(parents=True, exist_ok=True)
    screenshot = adb(args.serial, "exec-out", "screencap", "-p", binary=True)
    (output / "reference.png").write_bytes(screenshot)

    def shell(*command: str) -> str:
        return adb(args.serial, "shell", *command).strip()

    contract = {
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "name": args.name,
        "serial": args.serial,
        "device": {
            "model": shell("getprop", "ro.product.model"),
            "manufacturer": shell("getprop", "ro.product.manufacturer"),
            "android": shell("getprop", "ro.build.version.release"),
            "sdk": shell("getprop", "ro.build.version.sdk"),
            "size": shell("wm", "size"),
            "density": shell("wm", "density"),
            "fontScale": shell("settings", "get", "system", "font_scale"),
        },
    }
    (output / "contract.json").write_text(json.dumps(contract, indent=2) + "\n", encoding="utf-8")
    print(f"android reference: {output / 'reference.png'}")
    print(f"device contract: {output / 'contract.json'}")


if __name__ == "__main__":
    main()
