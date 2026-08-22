#!/usr/bin/env python3
"""Validate the checked composer parity contract against a pinned Desktop tree.

The manifest deliberately records an exact source/test inventory rather than a
best-effort grep.  This makes Desktop changes visible before a later Android
slice can accidentally claim parity.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path
from typing import Any

VALID_STATUSES = {"missing", "partial", "parity", "mobile-adapted", "not-applicable"}
VALID_AUTHORITIES = {
    "backend-authoritative",
    "connection-scoped",
    "persisted-preference",
    "ui-only",
}
ANDROID_DEBUG_PACKAGE = "com.hermesagent.mobile.debug"
ANDROID_MAIN_ACTIVITY = "com.hermesagent.mobile.MainActivity"
REQUIRED_CAPTURE_VARIANTS = {
    "themes": ["light", "dark"],
    "container_widths": [561, 560, 321, 320, 319],
    "orientations": ["portrait", "landscape"],
    "ime": ["closed", "open"],
    "font_scale": ["default", "large"],
    "motion": ["normal", "reduced"],
}
FORBIDDEN_DEVIATION_REASONS = re.compile(
    r"^\s*$|not implemented|not yet|todo|tbd|material does this|mobile should be simpler",
    re.IGNORECASE,
)
CITATION_RE = re.compile(r"^(apps/desktop/[A-Za-z0-9_./-]+):(\d+)(?:-(\d+))?$")
LOCAL_CITATION_RE = re.compile(r"^((?:app|docs|scripts)/[A-Za-z0-9_./-]+):(\d+)(?:-(\d+))?$")
PRIVATE_FIXTURE_MARKERS = re.compile(r"private|credential|token|hostname|host name|fingerprint", re.IGNORECASE)


class ContractError(Exception):
    """An actionable manifest contract failure."""


def git(upstream: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(upstream), *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode:
        raise ContractError(f"Desktop git command failed: git {' '.join(args)}: {result.stderr.strip()}")
    return result.stdout


def source_inventory(upstream: Path, sha: str, roots: list[str], extras: list[str]) -> dict[str, str]:
    output = git(upstream, "ls-tree", "-r", sha, "--", *roots, *extras)
    inventory: dict[str, str] = {}
    for line in output.splitlines():
        metadata, path = line.split("\t", 1)
        _, object_type, oid = metadata.split()
        if object_type != "blob":
            raise ContractError(f"Desktop inventory path is not a blob: {path}")
        inventory[path] = oid
    missing = set(extras) - set(inventory)
    if missing:
        raise ContractError(f"Desktop integration path does not exist at the pin: {sorted(missing)[0]}")
    return inventory


def resolve_citation(upstream: Path | None, sha: str, citation: str, line_counts: dict[str, int]) -> str | None:
    match = CITATION_RE.fullmatch(citation)
    if not match:
        return f"must be apps/desktop/path:line, got {citation!r}"
    path, start_text, end_text = match.groups()
    line_count = line_counts.get(path)
    if not isinstance(line_count, int):
        return f"does not resolve in the pinned offline inventory: {citation}"
    start = int(start_text)
    end = int(end_text) if end_text else start
    if start < 1 or end < start or end > line_count:
        return f"line range does not resolve in the pinned offline inventory: {citation} (file has {line_count} lines)"
    if upstream is not None:
        try:
            content = git(upstream, "show", f"{sha}:{path}")
        except ContractError:
            return f"does not resolve at pinned SHA: {citation}"
        if len(content.splitlines()) != line_count:
            return f"line-count drift between upstream and offline inventory for {path}"
    return None


def resolve_local_citation(citation: str) -> str | None:
    match = LOCAL_CITATION_RE.fullmatch(citation)
    if not match:
        return f"must be a local app/docs/scripts path:line citation, got {citation!r}"
    path_text, start_text, end_text = match.groups()
    path = Path(path_text)
    try:
        line_count = len(path.read_text(encoding="utf-8").splitlines())
    except OSError:
        return f"does not resolve in this repository: {citation}"
    start = int(start_text)
    end = int(end_text) if end_text else start
    if start < 1 or end < start or end > line_count:
        return f"line range does not resolve in this repository: {citation} (file has {line_count} lines)"
    return None


def add(errors: list[str], message: str) -> None:
    errors.append(message)


def validate(
    manifest: dict[str, Any],
    matrix: dict[str, Any],
    ledger: dict[str, Any],
    upstream: Path | None,
    require_complete: bool,
) -> list[str]:
    errors: list[str] = []
    sha = manifest.get("desktop_sha")
    if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{40}", sha):
        return ["desktop_sha must be a 40-character lowercase commit SHA"]
    if ledger.get("desktop_sha") != sha:
        return ["manifest desktop_sha must match the checked-in offline inventory pin"]
    if matrix.get("desktop_sha") != sha:
        add(errors, "capture matrix desktop_sha must match the composer manifest pin")
    if matrix.get("variants") != REQUIRED_CAPTURE_VARIANTS:
        add(errors, "capture matrix must retain the required theme, width, orientation, IME, font, and motion variants")

    harness = matrix.get("harness_contract")
    if not isinstance(harness, dict):
        add(errors, "capture matrix must define a safe harness_contract")
    else:
        desktop_harness = harness.get("desktop")
        if (
            not isinstance(desktop_harness, str)
            or "disposable pinned" not in desktop_harness.lower()
            or "e2e mock" not in desktop_harness.lower()
            or "never bare" not in desktop_harness.lower()
        ):
            add(errors, "Desktop capture harness must require a disposable pin, e2e mock data, and forbid bare perf:serve")
        identity = harness.get("android_identity")
        if not isinstance(identity, dict):
            add(errors, "capture matrix must define Android package/activity identity")
        else:
            if identity.get("package") != ANDROID_DEBUG_PACKAGE:
                add(errors, f"Android capture package must be {ANDROID_DEBUG_PACKAGE}")
            if identity.get("activity") != ANDROID_MAIN_ACTIVITY:
                add(errors, f"Android capture activity must be {ANDROID_MAIN_ACTIVITY}")
            verification = identity.get("verification")
            if (
                not isinstance(verification, str)
                or "resolve-activity" not in verification
                or "focus" not in verification.lower()
            ):
                add(errors, "Android capture identity must require resolve-activity and focused-window evidence")
    line_counts = ledger.get("path_line_counts")
    if not isinstance(line_counts, dict) or not line_counts or not all(isinstance(path, str) and isinstance(count, int) and count > 0 for path, count in line_counts.items()):
        return ["offline inventory must provide positive path_line_counts"]
    blob_oids = ledger.get("path_blob_oids")
    if (
        not isinstance(blob_oids, dict)
        or set(blob_oids) != set(line_counts)
        or not all(isinstance(path, str) and isinstance(oid, str) and re.fullmatch(r"[0-9a-f]{40,64}", oid) for path, oid in blob_oids.items())
    ):
        return ["offline inventory must provide one Git blob OID for every pinned path"]

    scope = manifest.get("desktop_scope")
    if not isinstance(scope, dict):
        return ["desktop_scope must be an object"]
    roots = scope.get("roots")
    extras = scope.get("integration_paths")
    if not isinstance(roots, list) or not all(isinstance(item, str) for item in roots):
        add(errors, "desktop_scope.roots must be a list of source roots")
        roots = []
    if not isinstance(extras, list) or not all(isinstance(item, str) for item in extras):
        add(errors, "desktop_scope.integration_paths must be a list of paths")
        extras = []
    discovered = set(line_counts)
    expected_scope = {path for path in discovered if any(path == root or path.startswith(root + "/") for root in roots)} | set(extras)
    if expected_scope != discovered:
        add(errors, "offline inventory contains paths outside desktop_scope or scope omits pinned paths")
    if upstream is not None:
        try:
            actual = git(upstream, "rev-parse", f"{sha}^{{commit}}").strip()
            if actual != sha:
                add(errors, f"desktop_sha did not resolve exactly: expected {sha}, got {actual}")
            live_inventory = source_inventory(upstream, sha, roots, extras)
            if set(live_inventory) != discovered:
                add(errors, "pinned upstream source/test paths drift from the checked-in offline inventory")
            elif live_inventory != blob_oids:
                add(errors, "pinned upstream source/test blobs drift from the checked-in offline inventory")
        except ContractError as error:
            add(errors, str(error))

    inventory = manifest.get("desktop_inventory")
    if not isinstance(inventory, list) or not inventory:
        return errors + ["desktop_inventory must be a non-empty list"]
    inventory_paths: set[str] = set()
    inventory_capabilities: dict[str, set[str]] = {}
    capability_ids = {item.get("id") for item in manifest.get("capabilities", []) if isinstance(item, dict)}
    for entry in inventory:
        if not isinstance(entry, dict):
            add(errors, "desktop_inventory entries must be objects")
            continue
        path = entry.get("path")
        kind = entry.get("kind")
        classified_by = entry.get("capability_ids")
        if not isinstance(path, str):
            add(errors, "desktop_inventory entry has no path")
            continue
        if path in inventory_paths:
            add(errors, f"desktop inventory duplicates {path}")
        inventory_paths.add(path)
        expected_kind = "test" if ".test." in path or "/e2e/" in path else "source"
        if kind != expected_kind:
            add(errors, f"desktop inventory {path} must classify as {expected_kind}")
        if not isinstance(classified_by, list) or not classified_by:
            add(errors, f"desktop inventory {path} is unclassified (capability_ids required)")
        elif any(item not in capability_ids for item in classified_by):
            add(errors, f"desktop inventory {path} references an unknown capability")
        else:
            inventory_capabilities[path] = set(classified_by)
    for path in sorted(discovered - inventory_paths):
        add(errors, f"unclassified Desktop source/test path: {path}")
    for path in sorted(inventory_paths - discovered):
        add(errors, f"removed or out-of-scope Desktop path still in inventory: {path}")

    captures = matrix.get("states") if isinstance(matrix, dict) else None
    if not isinstance(captures, list) or not captures:
        add(errors, "capture matrix must contain non-empty states")
        capture_ids: set[str] = set()
    else:
        capture_ids = set()
        for state in captures:
            if not isinstance(state, dict) or not isinstance(state.get("id"), str):
                add(errors, "capture matrix state needs an id")
                continue
            state_id = state["id"]
            if state_id in capture_ids:
                add(errors, f"capture matrix duplicates state {state_id}")
            capture_ids.add(state_id)
            if state.get("test_only") is not True or state.get("contains_private_data") is not False:
                add(errors, f"capture state {state_id} must be test-only and private-data-free")
            if state.get("capture_targets") != ["desktop", "android"]:
                add(errors, f"capture state {state_id} must plan matched Desktop and Android evidence")
            fixture = state.get("fixture")
            if not isinstance(fixture, str) or PRIVATE_FIXTURE_MARKERS.search(fixture):
                add(errors, f"capture state {state_id} needs a non-sensitive fixture name")

    capabilities = manifest.get("capabilities")
    if not isinstance(capabilities, list) or not capabilities:
        return errors + ["capabilities must be a non-empty list"]
    seen_ids: set[str] = set()
    for capability in capabilities:
        if not isinstance(capability, dict):
            add(errors, "capability entries must be objects")
            continue
        identifier = capability.get("id")
        if not isinstance(identifier, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]*", identifier):
            add(errors, f"capability has invalid id {identifier!r}")
            continue
        if identifier in seen_ids:
            add(errors, f"duplicate capability id {identifier}")
        seen_ids.add(identifier)
        for key in ("category", "family", "visible_contract", "android_behavior", "android_tests", "platform_dependency"):
            if not isinstance(capability.get(key), str) or not capability[key].strip():
                add(errors, f"{identifier}: {key} is required")
        if capability.get("state_authority") not in VALID_AUTHORITIES:
            add(errors, f"{identifier}: invalid state_authority")
        status = capability.get("android_status")
        if status not in VALID_STATUSES:
            add(errors, f"{identifier}: invalid android_status {status!r}")
        if require_complete and status in {"missing", "partial"}:
            add(errors, f"{identifier}: completion mode rejects unresolved {status} status")

        citations = capability.get("desktop_citations")
        if not isinstance(citations, dict):
            add(errors, f"{identifier}: desktop_citations is required")
        else:
            sources = citations.get("source")
            tests = citations.get("tests")
            if not isinstance(sources, list) or not sources:
                add(errors, f"{identifier}: at least one Desktop source citation is required")
            else:
                for citation in sources:
                    if not isinstance(citation, str):
                        add(errors, f"{identifier}: source citation must be a string")
                    else:
                        failure = resolve_citation(upstream, sha, citation, line_counts)
                        if failure:
                            add(errors, f"{identifier}: source citation {failure}")
                        else:
                            citation_path = citation.rsplit(":", 1)[0]
                            if identifier not in inventory_capabilities.get(citation_path, set()):
                                add(errors, f"{identifier}: source citation path is not classified for this capability: {citation_path}")
            test_gap = citations.get("test_gap")
            if not isinstance(tests, list):
                add(errors, f"{identifier}: Desktop test citations must be a list")
            elif not tests and not isinstance(test_gap, str):
                add(errors, f"{identifier}: uncited Desktop test gap needs an explicit behavior-gap note")
            else:
                for citation in tests:
                    if not isinstance(citation, str):
                        add(errors, f"{identifier}: test citation must be a string")
                    else:
                        failure = resolve_citation(upstream, sha, citation, line_counts)
                        if failure:
                            add(errors, f"{identifier}: test citation {failure}")
                        else:
                            citation_path = citation.rsplit(":", 1)[0]
                            if identifier not in inventory_capabilities.get(citation_path, set()):
                                add(errors, f"{identifier}: test citation path is not classified for this capability: {citation_path}")
        required_states = capability.get("capture_states")
        if not isinstance(required_states, list) or not required_states:
            add(errors, f"{identifier}: capture_states is required")
        elif any(state not in capture_ids for state in required_states):
            add(errors, f"{identifier}: references a capture state not in the matrix")
        if status in {"mobile-adapted", "not-applicable"}:
            deviation = capability.get("deviation")
            if not isinstance(deviation, dict):
                add(errors, f"{identifier}: {status} needs deviation reason, evidence, and approval")
            else:
                reason = deviation.get("reason")
                if not isinstance(reason, str) or FORBIDDEN_DEVIATION_REASONS.search(reason):
                    add(errors, f"{identifier}: invalid {status} deviation reason")
                for key in ("evidence", "approved_by"):
                    if not isinstance(deviation.get(key), str) or not deviation[key].strip():
                        add(errors, f"{identifier}: {status} requires deviation {key}")
                if isinstance(deviation.get("evidence"), str):
                    failure = resolve_local_citation(deviation["evidence"])
                    if failure:
                        add(errors, f"{identifier}: deviation evidence {failure}")
        elif capability.get("deviation"):
            # A partial row may already ship one mobile adaptation (for example,
            # explicit soft-keyboard send) while later parity work remains.
            # Keep its rationale checked instead of forcing the row to claim
            # full mobile adaptation.
            deviation = capability.get("deviation")
            if not isinstance(deviation, dict) or not isinstance(deviation.get("reason"), str) or FORBIDDEN_DEVIATION_REASONS.search(deviation["reason"]):
                add(errors, f"{identifier}: partial/parity deviation needs a concrete reason")
            elif not isinstance(deviation.get("evidence"), str) or not deviation["evidence"].strip():
                add(errors, f"{identifier}: partial/parity deviation requires evidence")
            elif not isinstance(deviation.get("approved_by"), str) or not deviation["approved_by"].strip():
                add(errors, f"{identifier}: partial/parity deviation requires approval")
            else:
                failure = resolve_local_citation(deviation["evidence"])
                if failure:
                    add(errors, f"{identifier}: deviation evidence {failure}")
    return errors


def report_text(manifest: dict[str, Any], matrix: dict[str, Any], errors: list[str]) -> str:
    capabilities = manifest.get("capabilities", [])
    counts = Counter(capability.get("android_status", "invalid") for capability in capabilities if isinstance(capability, dict))
    inventory = manifest.get("desktop_inventory", [])
    source_count = sum(1 for item in inventory if isinstance(item, dict) and item.get("kind") == "source")
    test_count = sum(1 for item in inventory if isinstance(item, dict) and item.get("kind") == "test")
    lines = [
        "# Composer parity contract report",
        "",
        f"Desktop pin: `{manifest.get('desktop_sha', 'invalid')}`",
        f"Inventory: {source_count} source paths, {test_count} test paths",
        f"Capture states: {len(matrix.get('states', [])) if isinstance(matrix, dict) else 0}",
        "",
        "## Android status",
        "",
        "| Status | Count |",
        "|---|---:|",
    ]
    for status in sorted(VALID_STATUSES):
        lines.append(f"| {status} | {counts[status]} |")
    lines += ["", "## Capability matrix", "", "| ID | Family | Status |", "|---|---|---|"]
    for capability in capabilities:
        if isinstance(capability, dict):
            lines.append(f"| {capability.get('id', '?')} | {capability.get('family', '?')} | {capability.get('android_status', '?')} |")
    if errors:
        lines += ["", "## Failures", ""] + [f"- {error}" for error in errors]
    return "\n".join(lines) + "\n"


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ContractError(f"could not read {path}: {error}") from error
    if not isinstance(value, dict):
        raise ContractError(f"{path} must contain a JSON object")
    return value


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=Path("docs/parity/composer-capabilities.json"))
    parser.add_argument("--matrix", type=Path, default=Path("docs/parity/composer-capture-matrix.json"))
    parser.add_argument("--ledger", type=Path, default=Path("docs/parity/desktop-composer-inventory.json"))
    parser.add_argument("--upstream", type=Path, help="Optional read-only verification against the immutable Desktop git object.")
    parser.add_argument("--require-complete", action="store_true", help="Reject missing/partial rows for epic completion.")
    parser.add_argument("--report", type=Path, help="Write a compact Markdown report (normally under build/).")
    args = parser.parse_args(argv)
    try:
        manifest = load_json(args.manifest)
        matrix = load_json(args.matrix)
        ledger = load_json(args.ledger)
        errors = validate(manifest, matrix, ledger, args.upstream, args.require_complete)
    except ContractError as error:
        errors = [str(error)]
        manifest, matrix = {}, {}
    report = report_text(manifest, matrix, errors)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(report, encoding="utf-8")
    print(report, end="")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
