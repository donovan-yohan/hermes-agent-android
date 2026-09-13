#!/usr/bin/env python3
"""Validate visual-parity capture requests and provenance packets.

The capture lane only accepts catalogued synthetic fixtures. Receipts intentionally
record immutable identities, never workstation paths, device serials, or secrets.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[1]
CATALOG = REPO_ROOT / "docs/parity/visual-capture-surfaces.json"
SHA = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
IDENTIFIER = re.compile(r"^[a-z][a-z0-9-]*$")
FORBIDDEN = re.compile(
    r"(?i)(-----BEGIN|\b(?:api[_-]?key|access[_-]?token|auth(?:orization)?|password|secret|credential)\b|"
    r"/(?:home|users|private|var|tmp)/|[A-Z]:\\(?:Users|home)\\|~[/\\]|\.ssh[/\\])"
)


def load_catalog(path: Path = CATALOG) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1 or not isinstance(data.get("surfaces"), dict):
        raise ValueError("invalid visual-capture catalog")
    return data


def request(catalog: dict[str, Any], surface: str, state: str, theme: str) -> dict[str, Any]:
    if not IDENTIFIER.fullmatch(surface) or not IDENTIFIER.fullmatch(state):
        raise ValueError("surface and state must be lowercase identifiers")
    spec = catalog["surfaces"].get(surface)
    if not isinstance(spec, dict):
        raise ValueError(f"unknown capture surface: {surface}")
    if state not in spec.get("states", {}):
        raise ValueError(f"unknown capture state {state!r} for {surface}")
    if theme not in {"light", "dark"}:
        raise ValueError("theme must be light or dark")
    return {"surface": surface, "state": state, "theme": theme, **spec, "state_spec": spec["states"][state]}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def reject_private(value: Any, label: str = "packet") -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            if FORBIDDEN.search(str(key)):
                raise ValueError(f"{label} has forbidden field name {key!r}")
            reject_private(item, label)
    elif isinstance(value, list):
        for item in value:
            reject_private(item, label)
    elif isinstance(value, str) and FORBIDDEN.search(value):
        raise ValueError(f"{label} contains a secret or private path")


def catalogued_receipt_spec(receipt: dict[str, Any]) -> dict[str, Any]:
    """Return the authoritative catalog record for a receipt or reject it."""
    return request(load_catalog(), receipt["surface"], receipt["state"], receipt["theme"])


def validate_receipt(receipt: dict[str, Any], platform: str) -> None:
    required = {"schema_version", "surface", "state", "fixture_id", "theme", "viewport"}
    if receipt.get("schema_version") != 1 or not required <= receipt.keys():
        raise ValueError("receipt misses required common provenance")
    if not all(isinstance(receipt[key], str) and IDENTIFIER.fullmatch(receipt[key]) for key in ("surface", "state", "fixture_id")):
        raise ValueError("receipt has invalid synthetic identifiers")
    if receipt["theme"] not in {"light", "dark"} or not isinstance(receipt["viewport"], dict):
        raise ValueError("receipt has invalid theme or viewport")

    spec = catalogued_receipt_spec(receipt)
    if receipt["fixture_id"] != spec["fixture_id"]:
        raise ValueError("receipt fixture does not match its catalogued surface")

    if platform == "android":
        if not SHA.fullmatch(str(receipt.get("android_git_sha", ""))):
            raise ValueError("Android receipt needs exact git SHA")
        if not SHA256.fullmatch(str(receipt.get("apk_sha256", ""))) or receipt.get("apk_sha256") != receipt.get("installed_apk_sha256"):
            raise ValueError("Android receipt needs a matching local and installed APK SHA-256")
        if receipt.get("apk_kind") not in {"debug", "androidTest"}:
            raise ValueError("Android receipt needs apk_kind debug or androidTest")
        application = receipt.get("application")
        if not isinstance(application, dict):
            raise ValueError("Android receipt needs package identity")
        if application.get("component") != spec["android_activity"]:
            raise ValueError("Android receipt activity does not match its catalogued fixture")
        if application.get("package_name") != spec["android_activity"].split("/", 1)[0]:
            raise ValueError("Android receipt package does not match its catalogued activity")
        if not isinstance(application.get("version_code"), str) or not application["version_code"].isdigit():
            raise ValueError("Android receipt needs package-manager version_code")
        if not isinstance(application.get("version_name"), str) or not application["version_name"]:
            raise ValueError("Android receipt needs package-manager version_name")
        if not SHA256.fullmatch(str(application.get("signing_certificate_sha256", ""))):
            raise ValueError("Android receipt needs the installed APK signing certificate SHA-256")
        if receipt.get("interactions") != spec["state_spec"].get("interaction", []):
            raise ValueError("Android receipt interactions do not match its catalogued state")
        expected = spec["state_spec"].get("post_interaction_accessibility")
        evidence = receipt.get("accessibility")
        if not isinstance(evidence, dict) or not isinstance(evidence.get("nodes"), list):
            raise ValueError("Android receipt needs retained post-interaction accessibility evidence")
        if expected and evidence.get("expected_description") != expected:
            raise ValueError("Android receipt lacks catalogued post-interaction accessibility state")
    elif platform == "desktop":
        if receipt.get("desktop_upstream_sha") != spec["desktop_sha"]:
            raise ValueError("Desktop receipt SHA does not match its catalogued surface pin")
        if receipt.get("fixture_origin") != "pinned-desktop-e2e-mock":
            raise ValueError("Desktop receipt must identify the pinned E2E mock fixture")
    else:
        raise ValueError(f"unknown platform {platform}")
    reject_private(receipt)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    describe = commands.add_parser("describe")
    describe.add_argument("--surface", required=True)
    describe.add_argument("--state", required=True)
    describe.add_argument("--theme", required=True)
    describe.add_argument("--catalog", type=Path, default=CATALOG)
    check = commands.add_parser("check-receipt")
    check.add_argument("--platform", choices=("android", "desktop"), required=True)
    check.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "describe":
            print(json.dumps(request(load_catalog(args.catalog), args.surface, args.state, args.theme), sort_keys=True))
        else:
            value = json.loads(args.receipt.read_text(encoding="utf-8"))
            validate_receipt(value, args.platform)
            print(f"ok    {args.platform} capture receipt is safe, complete, and catalogued")
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"FAIL  {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
