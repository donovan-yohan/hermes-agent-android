#!/usr/bin/env python3
"""Capture one Android visual-parity reference with installed-byte provenance.

A screenshot is accepted only after Android resolves and focuses the requested
fixture activity, and after the package manager's base APK is pulled and shown
to be byte-for-byte equal to the local APK named in the receipt.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(REPO_ROOT / "scripts"))
from visual_parity_contract import sha256, validate_receipt

DEFAULT_PACKAGE = "com.hermesagent.mobile.debug"
DEFAULT_ACTIVITY = "com.hermesagent.mobile.MainActivity"
PEM_CERTIFICATE = re.compile(r"-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----", re.DOTALL)
VERSION_CODE = re.compile(r"\bversionCode=(\d+)")
VERSION_NAME = re.compile(r"\bversionName=([^\s]+)")


def adb(serial: str | None, *args: str, binary: bool = False):
    command = ["adb"]
    if serial:
        command += ["-s", serial]
    command += list(args)
    return subprocess.run(command, check=True, capture_output=True, text=not binary).stdout


def shell(serial: str | None, *command: str) -> str:
    return adb(serial, "shell", *command).strip()


def focus_lines(dump: str) -> str:
    return "\n".join(
        line.strip() for line in dump.splitlines() if "mCurrentFocus" in line or "mFocusedApp" in line
    )


def read_focus(serial: str | None) -> str:
    focused = focus_lines(shell(serial, "dumpsys", "window", "windows"))
    return focused or focus_lines(shell(serial, "dumpsys", "window"))


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
        raise SystemExit("adb reported no mCurrentFocus/mFocusedApp lines; the capture target cannot be proved on screen")
    if not contains_expected(focused):
        raise SystemExit(f"capture target is not the focused Android activity: expected {component}")
    return {"component": component, "resolved_activity": resolved, "focused_window": focused}


def ui_hierarchy(serial: str | None) -> ET.Element:
    dumped = shell(serial, "uiautomator", "dump", "/sdcard/window.xml")
    if "UI hierchary dumped" not in dumped and "UI hierarchy dumped" not in dumped:
        raise SystemExit(f"could not dump Android UI hierarchy: {dumped!r}")
    return ET.fromstring(shell(serial, "cat", "/sdcard/window.xml"))


def accessibility_snapshot(serial: str | None, expected_description: str | None = None) -> dict[str, object]:
    """Retain a compact, post-interaction accessibility snapshot without bounds."""
    root = ui_hierarchy(serial)
    nodes = []
    descriptions = set()
    for node in root.iter("node"):
        text = node.attrib.get("text", "")
        description = node.attrib.get("content-desc", "")
        if description:
            descriptions.add(description)
        if text or description:
            nodes.append({
                "class": node.attrib.get("class", ""),
                "text": text,
                "content_description": description,
                "clickable": node.attrib.get("clickable") == "true",
                "enabled": node.attrib.get("enabled") == "true",
                "selected": node.attrib.get("selected") == "true",
            })
    if expected_description and expected_description not in descriptions:
        raise SystemExit(f"post-interaction state did not expose {expected_description!r} in accessibility")
    return {"expected_description": expected_description, "nodes": nodes}


def resolve_apksigner() -> str:
    """Resolve apksigner from PATH or the build-tools installed in the Android SDK."""
    on_path = shutil.which("apksigner")
    if on_path:
        return on_path
    sdk_roots = {
        Path(value)
        for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT")
        if (value := os.environ.get(name))
    }
    candidates = [
        candidate
        for root in sdk_roots
        for candidate in (root / "build-tools").glob("*/apksigner")
        if candidate.is_file() and os.access(candidate, os.X_OK)
    ]
    if not candidates:
        raise SystemExit("apksigner was not found on PATH or under Android SDK build-tools")

    def version_key(candidate: Path) -> tuple[int, ...]:
        return tuple(int(part) for part in re.findall(r"\d+", candidate.parent.name))

    return str(max(candidates, key=version_key))


def signing_certificate_sha256(apksigner_output: str) -> str:
    """Hash the first signer certificate's DER bytes instead of scraping display labels."""
    certificate = PEM_CERTIFICATE.search(apksigner_output)
    if not certificate:
        raise SystemExit("apksigner did not emit an installed APK signing certificate")
    try:
        der = base64.b64decode("".join(certificate.group(1).split()), validate=True)
    except ValueError as error:
        raise SystemExit("apksigner emitted an invalid PEM signing certificate") from error
    if not der:
        raise SystemExit("apksigner emitted an empty PEM signing certificate")
    return hashlib.sha256(der).hexdigest()


def tap_visible_text(serial: str | None, text: str) -> None:
    """Tap one exact synthetic control label through the real accessibility tree."""
    # Coordinates locate the real control but are intentionally not retained.
    root = ui_hierarchy(serial)
    bounded = [node for node in root.iter("node") if text in node.attrib.get("text", "")]
    if len(bounded) != 1:
        raise SystemExit(f"expected exactly one visible control containing {text!r}, found {len(bounded)}")
    numbers = [int(value) for value in re.findall(r"\d+", bounded[0].attrib.get("bounds", ""))]
    if len(numbers) != 4:
        raise SystemExit(f"control {text!r} has invalid bounds")
    left, top, right, bottom = numbers
    shell(serial, "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
    time.sleep(0.2)


def installed_apk_provenance(serial: str | None, package: str, local_apk: Path) -> dict[str, str]:
    """Pull Android's installed base APK and prove it equals the local artifact."""
    paths = [line.removeprefix("package:") for line in shell(serial, "pm", "path", package).splitlines() if line.startswith("package:")]
    base_paths = [path for path in paths if path.endswith("/base.apk")]
    if len(base_paths) != 1:
        raise SystemExit(f"expected one installed base APK for {package}, found {len(base_paths)}")
    with tempfile.TemporaryDirectory(prefix="visual-parity-installed-") as directory:
        installed = Path(directory) / "base.apk"
        adb(serial, "pull", base_paths[0], str(installed))
        installed_sha = sha256(installed)
        local_sha = sha256(local_apk)
        if installed_sha != local_sha:
            raise SystemExit("installed base APK SHA-256 does not match the local capture APK")
        signer = subprocess.run(
            [resolve_apksigner(), "verify", "--verbose", "--print-certs-pem", str(installed)],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
    certificate_sha256 = signing_certificate_sha256(signer)
    package_dump = shell(serial, "dumpsys", "package", package)
    version_code = VERSION_CODE.search(package_dump)
    version_name = VERSION_NAME.search(package_dump)
    if not version_code or not version_name:
        raise SystemExit("package manager did not report installed versionCode and versionName")
    return {
        "apk_sha256": local_sha,
        "installed_apk_sha256": installed_sha,
        "version_code": version_code.group(1),
        "version_name": version_name.group(1),
        "signing_certificate_sha256": certificate_sha256,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Capture an Android visual-parity reference from a connected device.")
    parser.add_argument("--name", required=True, help="surface and state, for example composer-status-stack--goal-active")
    parser.add_argument("--out", help="output directory; defaults to build/visual-parity/<name>/android")
    parser.add_argument("--serial", help="adb device serial when more than one device is connected")
    parser.add_argument("--package", default=DEFAULT_PACKAGE, help="expected Android application package")
    parser.add_argument("--activity", default=DEFAULT_ACTIVITY, help="expected focused Android activity")
    parser.add_argument("--git-sha", required=True, help="exact Android source commit that produced the APK")
    parser.add_argument("--apk", type=Path, required=True, help="debug or test APK actually installed for this capture")
    parser.add_argument("--apk-kind", choices=("debug", "androidTest"), required=True)
    parser.add_argument("--fixture-id", required=True, help="catalogued synthetic fixture identifier")
    parser.add_argument("--state", required=True, help="catalogued synthetic state identifier")
    parser.add_argument("--theme", choices=("light", "dark"), required=True)
    parser.add_argument("--tap-text", help="synthetic visible control to open before capture")
    parser.add_argument("--expected-accessibility", help="exact post-interaction accessibility description")
    args = parser.parse_args()

    if adb(args.serial, "get-state").strip() != "device":
        raise SystemExit("adb device is not ready")
    if not args.apk.is_file():
        raise SystemExit(f"APK does not exist: {args.apk}")
    provenance = installed_apk_provenance(args.serial, args.package, args.apk)
    if args.tap_text:
        tap_visible_text(args.serial, args.tap_text)
    identity = verify_app_identity(args.serial, args.package, args.activity)
    accessibility = accessibility_snapshot(args.serial, args.expected_accessibility)

    output = Path(args.out or f"build/visual-parity/{args.name}/android").resolve()
    output.mkdir(parents=True, exist_ok=True)
    screenshot = adb(args.serial, "exec-out", "screencap", "-p", binary=True)
    (output / "reference.png").write_bytes(screenshot)
    contract = {
        "schema_version": 1,
        "captured_at": datetime.now(timezone.utc).isoformat(),
        "surface": args.name.split("--", 1)[0],
        "state": args.state,
        "fixture_id": args.fixture_id,
        "theme": args.theme,
        "android_git_sha": args.git_sha,
        "apk_sha256": provenance["apk_sha256"],
        "installed_apk_sha256": provenance["installed_apk_sha256"],
        "apk_kind": args.apk_kind,
        "interactions": [f"tap:{args.tap_text}"] if args.tap_text else [],
        "viewport": {"size": shell(args.serial, "wm", "size"), "density": shell(args.serial, "wm", "density")},
        "application": {"package_name": args.package, **identity, **{key: provenance[key] for key in ("version_code", "version_name", "signing_certificate_sha256")}},
        "accessibility": accessibility,
        "device": {
            "model": shell(args.serial, "getprop", "ro.product.model"),
            "manufacturer": shell(args.serial, "getprop", "ro.product.manufacturer"),
            "android": shell(args.serial, "getprop", "ro.build.version.release"),
            "sdk": shell(args.serial, "getprop", "ro.build.version.sdk"),
            "font_scale": shell(args.serial, "settings", "get", "system", "font_scale"),
        },
    }
    validate_receipt(contract, "android")
    (output / "contract.json").write_text(json.dumps(contract, indent=2) + "\n", encoding="utf-8")
    print(f"android reference: {output / 'reference.png'}")
    print(f"device contract: {output / 'contract.json'}")


if __name__ == "__main__":
    main()
