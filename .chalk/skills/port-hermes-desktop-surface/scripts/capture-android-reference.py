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
SCREEN_SIZE = re.compile(r"\b(\d+)x(\d+)\b")

# A phone is shorter than any catalogued list, so a state whose subject sits at
# the list's own end is reached by real drags, never by a grown pane or a
# shortened seed. Both the drag count and the settle are bounded, so a lane that
# cannot reach the state fails loudly instead of swiping forever.
SWIPE_MAX_ATTEMPTS = 12
SWIPE_SETTLE_ATTEMPTS = 4
SWIPE_DURATION_MILLIS = 350
SWIPE_SETTLE_SECONDS = 0.4
SWIPE_RENDER_ATTEMPTS = 8
SWIPE_RENDER_PAUSE_SECONDS = 0.5


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


def published_accessibility(root: ET.Element) -> tuple[list[dict[str, object]], set[str]]:
    """The platform's published accessibility text, node by node.

    A Compose node's spoken name is its `text` or its `content-desc` depending on
    which semantic it set: a session row merges its whole sentence into a
    `contentDescription`, and a plain note is published as `text`. A capture that
    read only one of the two would claim a state it cannot actually see.
    """
    nodes: list[dict[str, object]] = []
    published: set[str] = set()
    for node in root.iter("node"):
        text = node.attrib.get("text", "")
        description = node.attrib.get("content-desc", "")
        published.update(value for value in (text, description) if value)
        if text or description:
            nodes.append({
                "class": node.attrib.get("class", ""),
                "text": text,
                "content_description": description,
                "clickable": node.attrib.get("clickable") == "true",
                "enabled": node.attrib.get("enabled") == "true",
                "selected": node.attrib.get("selected") == "true",
            })
    return nodes, published


def accessibility_snapshot(
    serial: str | None,
    expected_description: str | None = None,
    *,
    attempts: int = 6,
    retry_seconds: float = 0.25,
) -> dict[str, object]:
    """Retain a compact post-interaction snapshot after bounded platform publication retries."""
    for attempt in range(attempts):
        nodes, published = published_accessibility(ui_hierarchy(serial))
        if not expected_description or expected_description in published:
            return {"expected_description": expected_description, "nodes": nodes}
        if attempt + 1 < attempts:
            time.sleep(retry_seconds)
    raise SystemExit(f"post-interaction state did not expose {expected_description!r} in accessibility")


def screen_size(serial: str | None) -> tuple[int, int]:
    """The screen the capture is really on, as the platform reports it.

    Derived from `wm size` on the device in front of us — an override included —
    so a drag is never issued against a phone's pixel grid that this capture
    merely assumed.
    """
    reported = shell(serial, "wm", "size")
    matches = SCREEN_SIZE.findall(reported)
    if not matches:
        raise SystemExit(f"could not derive the screen size from `wm size`: {reported!r}")
    width, height = (int(value) for value in matches[-1])
    if width <= 0 or height <= 0:
        raise SystemExit(f"the platform reported a degenerate screen size: {width}x{height}")
    return width, height


def swipe_list_up(
    serial: str | None,
    expected_description: str | None,
    *,
    attempts: int = SWIPE_MAX_ATTEMPTS,
    settle_attempts: int = SWIPE_SETTLE_ATTEMPTS,
    duration_millis: int = SWIPE_DURATION_MILLIS,
    settle_seconds: float = SWIPE_SETTLE_SECONDS,
    render_attempts: int = SWIPE_RENDER_ATTEMPTS,
    render_pause_seconds: float = SWIPE_RENDER_PAUSE_SECONDS,
) -> dict[str, object]:
    """Drag the real list up with real adb swipes until the catalogued state publishes.

    The catalogued seed is longer than the phone, so a state whose subject sits at
    the list's own end is off screen as launched. The drag is a real input event
    inside the screen the platform reports, bounded in swipe count and duration,
    and the capture fails if the catalogued text never appears: nothing here grows
    the pane, drops a row from the seed, or stitches pixels.
    """
    if not expected_description:
        raise SystemExit("a bounded list swipe needs the catalogued post-interaction description to scroll to")
    width, height = screen_size(serial)
    centre = width // 2
    from_y = int(height * 0.75)
    to_y = int(height * 0.25)
    drag = {"from": [centre, from_y], "to": [centre, to_y], "duration_millis": duration_millis}
    swipes = 0

    def drag_once() -> None:
        nonlocal swipes
        shell(serial, "input", "swipe", str(centre), str(from_y), str(centre), str(to_y), str(duration_millis))
        swipes += 1
        time.sleep(settle_seconds)

    # A drag against a window that has not drawn yet scrolls nothing and proves
    # nothing, so wait — bounded — for the fixture to publish some text at all.
    for attempt in range(render_attempts):
        _, rendered = published_accessibility(ui_hierarchy(serial))
        if rendered:
            break
        if attempt + 1 < render_attempts:
            time.sleep(render_pause_seconds)
    else:
        raise SystemExit("no accessibility text published before the bounded list swipe; the capture target never rendered")

    for _ in range(attempts):
        drag_once()
        _, published = published_accessibility(ui_hierarchy(serial))
        if expected_description in published:
            break
    else:
        raise SystemExit(
            f"the catalogued description {expected_description!r} never published in {attempts} bounded list swipes"
        )

    # Reachable is not the same as reached: a fling can publish the final note
    # while it is still travelling. Keep dragging until the tree stops changing,
    # so the screenshot records the list's own end and not a transient frame.
    for _ in range(settle_attempts):
        before = ET.tostring(ui_hierarchy(serial), encoding="unicode")
        drag_once()
        after = ET.tostring(ui_hierarchy(serial), encoding="unicode")
        if after == before:
            break
    return {"swipes": swipes, "screen": f"{width}x{height}", "drag": drag}


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


def interaction_receipt(tap_text: str | None, swipe_list_up: bool) -> list[str]:
    """The interactions actually performed, in the catalogued spelling."""
    interactions: list[str] = []
    if tap_text:
        interactions.append(f"tap:{tap_text}")
    if swipe_list_up:
        interactions.append("swipe:list-up")
    return interactions


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
    parser.add_argument(
        "--swipe-list-up",
        action="store_true",
        help="drag the real list up with bounded adb swipes until --expected-accessibility publishes",
    )
    parser.add_argument("--expected-accessibility", help="exact post-interaction accessibility text or description")
    args = parser.parse_args()

    if adb(args.serial, "get-state").strip() != "device":
        raise SystemExit("adb device is not ready")
    if not args.apk.is_file():
        raise SystemExit(f"APK does not exist: {args.apk}")
    provenance = installed_apk_provenance(args.serial, args.package, args.apk)
    if args.tap_text:
        tap_visible_text(args.serial, args.tap_text)
    list_swipe: dict[str, object] = {}
    if args.swipe_list_up:
        list_swipe = swipe_list_up(args.serial, args.expected_accessibility)
    # Identity is proved after the interactions, so the receipt also shows the
    # fixture held the focused window through them.
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
        "interactions": interaction_receipt(args.tap_text, args.swipe_list_up),
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
    if list_swipe:
        # How the state above the fold was actually reached: real drags, on the
        # screen the platform reported. No serial and no path ever lands here.
        contract["list_swipe"] = list_swipe
    validate_receipt(contract, "android")
    (output / "contract.json").write_text(json.dumps(contract, indent=2) + "\n", encoding="utf-8")
    print(f"android reference: {output / 'reference.png'}")
    print(f"device contract: {output / 'contract.json'}")


if __name__ == "__main__":
    main()
