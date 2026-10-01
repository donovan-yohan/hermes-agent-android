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


def request(catalog: dict[str, Any], surface: str, state: str, theme: str,
            fixture_id: str | None = None, platform: str | None = None) -> dict[str, Any]:
    if not IDENTIFIER.fullmatch(surface) or not IDENTIFIER.fullmatch(state):
        raise ValueError("surface and state must be lowercase identifiers")
    spec = catalog["surfaces"].get(surface)
    if not isinstance(spec, dict):
        raise ValueError(f"unknown capture surface: {surface}")
    if fixture_id is not None and fixture_id != spec["fixture_id"]:
        version = spec.get("fixture_versions", {}).get(fixture_id)
        if not isinstance(version, dict):
            raise ValueError("unknown fixture version")
        spec = {**spec, **version, "fixture_id": fixture_id}
        # A future worker must use the selected state/platform locator, never
        # accidentally inherit the historical surface-wide Edit profile crop.
        spec.pop("desktop_selector", None)
        for state_mapping in spec.get("states", {}).values():
            mappings = state_mapping.get("platforms")
            if not isinstance(mappings, dict) or set(mappings) != {"android", "desktop"}:
                raise ValueError("versioned fixture needs explicit platform mappings")
            for mapping in mappings.values():
                if (not isinstance(mapping, dict)
                        or any(not isinstance(mapping.get(k), str) or not mapping[k]
                               for k in ("selector", "presentation", "interaction_semantics", "capture_boundary", "action_origin"))
                        or not isinstance(mapping.get("locator"), dict) or not mapping["locator"]
                        or not isinstance(mapping.get("assertions"), dict)
                        or not {"model_write_count", "confirmed_write_count", "authoritative_model", "inventory_outcome"} <= mapping["assertions"].keys()
                        or any(not isinstance(mapping.get(k), list)
                               or any(not isinstance(v, str) or not v for v in mapping[k])
                               for k in ("required_events", "required_labels", "ordered_actions"))):
                    raise ValueError("invalid platform state mapping")
    if state not in spec.get("states", {}):
        raise ValueError(f"unknown capture state {state!r} for {surface}")
    if theme not in {"light", "dark"}:
        raise ValueError("theme must be light or dark")
    result = {"surface": surface, "state": state, "theme": theme, **spec, "state_spec": spec["states"][state]}
    if platform is not None:
        if platform not in {"android", "desktop"}:
            raise ValueError("unknown capture platform")
        mapping = result["state_spec"].get("platforms", {}).get(platform)
        if not isinstance(mapping, dict):
            raise ValueError("fixture has no explicit platform mapping; use legacy dispatch")
        result["platform_spec"] = mapping
    return result


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
    return request(load_catalog(), receipt["surface"], receipt["state"], receipt["theme"], receipt["fixture_id"])


def accessibility_labels(evidence: Any) -> set[str]:
    if not isinstance(evidence, dict) or not isinstance(evidence.get("nodes"), list):
        raise ValueError("accessibility evidence needs nodes")
    labels = set()
    for node in evidence["nodes"]:
        if not isinstance(node, dict):
            raise ValueError("accessibility node must be an object")
        for key in ("text", "content_description"):
            value = node.get(key, "")
            if not isinstance(value, str):
                raise ValueError("accessibility label must be a string")
            if value:
                labels.add(value)
    return labels


def validate_receipt(receipt: dict[str, Any], platform: str) -> None:
    if not isinstance(receipt, dict):
        raise ValueError("receipt must be an object")
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
        if receipt.get("surface") == "bot-model-config":
            steps = receipt.get("ordered_action_evidence")
            if not isinstance(steps, list) or any(not isinstance(step, dict) for step in steps) or [step.get("action") for step in steps] != receipt.get("interactions"):
                raise ValueError("Model capture needs every ordered action's evidence")
            for step in steps:
                labels = accessibility_labels(step.get("accessibility"))
                kind, _, label = step["action"].partition(":")
                if kind == "scroll" and label not in labels:
                    raise ValueError("ordered scroll evidence lacks its exact label")
                if kind == "tap":
                    target = step.get("pre_tap", {})
                    if not isinstance(target, dict) or label not in accessibility_labels({"nodes": [target.get("target")]}):
                        raise ValueError("ordered tap needs retained exact pre-tap target")
                    node = target["target"]
                    ancestor = target.get("clickable_ancestor")
                    path = target.get("ancestor_path")
                    if node.get("enabled") is not True or not isinstance(ancestor, dict) or ancestor.get("enabled") is not True or ancestor.get("clickable") is not True:
                        raise ValueError("ordered tap needs enabled target and clickable ancestor")
                    if (not isinstance(path, list) or not path or path[0] != node or path[-1] != ancestor
                            or any(not isinstance(n, dict) or n.get("enabled") is not True
                                   or n.get("package") != application["package_name"] for n in path)):
                        raise ValueError("ordered tap needs retained owned enabled ancestor path")
        expected = spec["state_spec"].get("post_interaction_accessibility")
        evidence = receipt.get("accessibility")
        if not isinstance(evidence, dict) or not isinstance(evidence.get("nodes"), list):
            raise ValueError("Android receipt needs retained post-interaction accessibility evidence")
        labels = accessibility_labels(evidence)
        if expected and (evidence.get("expected_description") != expected or expected not in labels):
            raise ValueError("Android receipt lacks catalogued post-interaction accessibility state")
        if receipt["state"] in ("bot-model-inventory-loading", "bot-avatar-loading"):
            deadline = 60 if receipt["state"] == "bot-avatar-loading" else 20
            bracket = receipt.get("screenshot_bracket")
            if not isinstance(bracket, dict):
                raise ValueError("loading capture needs screenshot state bracket")
            for side in ("before", "after"):
                if expected not in accessibility_labels(bracket.get(side)):
                    raise ValueError("loading state did not bracket screenshot")
            timing = bracket.get("timing")
            if not isinstance(timing, dict) or timing.get("basis") != "monotonic-before-fixture-launch" or timing.get("deadline_seconds") != deadline:
                raise ValueError("loading capture needs production deadline proof")
            values = [timing.get(key) for key in ("screenshot_start_seconds", "screenshot_end_seconds", "postcheck_seconds")]
            if any(type(value) not in (int, float) for value in values) or not 0 <= values[0] <= values[1] <= values[2] < deadline:
                raise ValueError(f"loading screenshot/check must finish before the production {deadline}-second deadline")
    elif platform == "desktop":
        if receipt.get("desktop_upstream_sha") != spec["desktop_sha"]:
            raise ValueError("Desktop receipt SHA does not match its catalogued surface pin")
        if receipt.get("fixture_origin") != "pinned-desktop-e2e-mock":
            raise ValueError("Desktop receipt must identify the pinned E2E mock fixture")
    else:
        raise ValueError(f"unknown platform {platform}")
    if "platforms" in spec["state_spec"]:
        validate_state_proof(receipt, spec, platform)
    reject_private(receipt)


def validate_state_proof(receipt: dict[str, Any], spec: dict[str, Any], platform: str) -> None:
    """Validate retained observations; acceptance is not independent pixel proof."""
    mapping = spec["state_spec"]["platforms"][platform]
    if receipt.get("capture_mapping") != mapping:
        raise ValueError("receipt needs exact declared platform/state mapping")
    if any(key in receipt for key in ("desktop_selector", "selector", "locator", "presentation",
                                      "platforms", "state_spec", "capture_boundary", "overrides")):
        raise ValueError("undeclared capture overrides are forbidden")
    for key in ("screenshot_sha256", "fixture_implementation_sha256"):
        if not SHA256.fullmatch(str(receipt.get(key, ""))):
            raise ValueError("v2 needs screenshot and fixture implementation hashes")
    if receipt.get("capture_inputs") != {**spec["capture_inputs"], "theme": receipt["theme"]}:
        raise ValueError("capture inputs must match the declared normalized inputs")
    if receipt.get("synthetic_inputs") != spec["synthetic_inputs"]:
        raise ValueError("synthetic inputs must match the declared payload")
    proof = receipt.get("state_proof")
    if not isinstance(proof, dict) or proof.get("source") != "runtime-capture-worker":
        raise ValueError("receipt needs runtime state proof")
    for key in ("selector", "locator", "presentation", "interaction_semantics", "action_origin", "ordered_actions"):
        if proof.get(key) != mapping[key]:
            raise ValueError("state proof contradicts declared " + key)
    actions = proof.get("action_evidence")
    if (not isinstance(actions, list) or any(not isinstance(a, dict) for a in actions)
            or [a.get("action") for a in actions] != mapping["ordered_actions"]):
        raise ValueError("ordered actions need retained runtime evidence")
    for index, action in enumerate(actions):
        if (action.get("origin") != mapping["action_origin"]
                or type(action.get("sequence")) is not int or action["sequence"] != index + 1
                or not accessibility_labels({"nodes": action.get("nodes")})):
            raise ValueError("ordered action proof needs origin, order and observed nodes")
    if proof.get("events") != mapping["required_events"]:
        raise ValueError("state proof lacks ordered production transition events")
    labels = accessibility_labels({"nodes": proof.get("nodes")})
    if not labels or not set(mapping["required_labels"]) <= labels:
        raise ValueError("state proof lacks observed visible labels")
    if type(proof.get("locator_matches")) is not int or proof["locator_matches"] != 1:
        raise ValueError("capture locator must match exactly one visible boundary")
    inputs = spec["synthetic_inputs"]
    assertions = mapping["assertions"]
    calls = proof.get("model_calls")
    if not isinstance(calls, list) or len(calls) != assertions["model_write_count"]:
        raise ValueError("wrong model write count")
    sequences = []
    for index, call in enumerate(calls):
        if (not isinstance(call, dict) or type(call.get("sequence")) is not int
                or call["sequence"] <= 0 or call.get("confirmed") is not (index == 1)
                or call.get("profile") != inputs["name"]
                or call.get("patch") != {"provider": inputs["provider"], "model": inputs["requested_model"]}):
            raise ValueError("model write must be named, model-only, and correctly confirmed")
        sequences.append(call["sequence"])
        outcome = "refused" if receipt["state"] == "bot-model-save-refused" else "warning" if index == 0 else "applied"
        response = {"ok": True, "confirm_required": True, "confirm_message": inputs["confirmation_message"], "applied": {"model": False}} if outcome == "warning" else {"ok": True, "applied": {"model": True}} if outcome == "applied" else None
        # JSON equality alone treats 0/1 as booleans; require exact wire types.
        if call.get("outcome") != outcome or json.dumps(call.get("response"), sort_keys=True) != json.dumps(response, sort_keys=True):
            raise ValueError("model write outcome contradicts the capture phase")
    if sequences != sorted(set(sequences)):
        raise ValueError("model writes must be strictly ordered")
    if proof.get("authoritative_model") != assertions["authoritative_model"]:
        raise ValueError("authoritative model contradicts capture phase")
    # Desktop 587e673e model-picker.tsx:148–153 returns only a spinner while
    # loading. Original authoritative state still requires named describe proof.
    hidden_fields = receipt["state"] in {"bot-model-confirmation", "bot-model-save-refused"} or (
        platform == "desktop" and receipt["state"] == "bot-model-inventory-loading")
    expected_fields = None if hidden_fields else {
        "provider": inputs["provider"], "model": assertions["authoritative_model"]}
    if "fields" not in proof or proof["fields"] != expected_fields:
        raise ValueError("visible fields must match the editor phase; hidden fields must not be claimed")
    reads = proof.get("describe_reads")
    if (not isinstance(reads, list) or not reads
            or any(not isinstance(read, dict) or type(read.get("sequence")) is not int
                   or read["sequence"] <= 0 or read.get("profile") != inputs["name"] for read in reads)
            or [read["sequence"] for read in reads] != sorted(set(read["sequence"] for read in reads))
            or reads[0].get("model") != inputs["initial_model"]
            or (sequences and reads[0]["sequence"] >= sequences[0])):
        raise ValueError("capture requires ordered named describe reads")
    if receipt["state"] == "bot-model-saved":
        if reads[-1]["sequence"] <= sequences[-1] or reads[-1].get("model") != inputs["requested_model"]:
            raise ValueError("saved needs named describe after authoritative apply")
        if platform == "desktop":
            reopen = proof.get("reopen_sequence")
            if type(reopen) is not int or not sequences[-1] < reopen < reads[-1]["sequence"]:
                raise ValueError("Desktop saved must reopen before named readback")
    inventory = proof.get("inventory")
    if (not isinstance(inventory, dict) or inventory.get("method") != "model.options"
            or inventory.get("scope") != spec["transport_mapping"][platform]
            or inventory.get("outcome") != assertions["inventory_outcome"]):
        raise ValueError("inventory needs actual outcome and platform routing proof")
    if receipt["state"] in {"bot-model-manual", "bot-model-inventory-error"} and proof.get("manual_fields_visible") is not True:
        raise ValueError("manual state needs visible provider/model fields")
    if receipt["state"] == "bot-model-inventory-loading":
        validate_loading_proof(proof.get("loading_bracket"), receipt["screenshot_sha256"])
    if mapping.get("runtime_discovery"):
        validate_refusal_discovery(proof.get("discovery"), receipt["screenshot_sha256"], inputs, sequences[-1])


def validate_loading_proof(bracket: Any, screenshot_hash: str) -> None:
    if (not isinstance(bracket, dict) or bracket.get("screenshot_sha256") != screenshot_hash
            or bracket.get("basis") != "monotonic-since-interception"
            or not isinstance(bracket.get("request_id"), str) or not bracket["request_id"]):
        raise ValueError("loading needs per-PNG monotonic request bracket")
    times = []
    for side in ("before", "after"):
        point = bracket.get(side)
        if (not isinstance(point, dict) or point.get("request_id") != bracket["request_id"]
                or point.get("pending") is not True or "response" not in point or "error" not in point
                or point["response"] is not None or point["error"] is not None
                or type(point.get("elapsed_ms")) not in (int, float)
                or not 0 <= point["elapsed_ms"] < 20000):
            raise ValueError("loading must remain pending strictly before 20000 ms")
        times.append(point["elapsed_ms"])
    if times[0] > times[1]:
        raise ValueError("loading bracket time moved backwards")


def validate_refusal_discovery(discovery: Any, screenshot_hash: str, inputs: dict[str, Any], refusal_sequence: int) -> None:
    if (not isinstance(discovery, dict) or discovery.get("phase") != "initial-write-refused"
            or type(discovery.get("editor_closed")) is not bool):
        raise ValueError("initial refusal must be runtime-discovered, never assumed or retried")
    artifacts = discovery.get("artifacts")
    expected = ["initial-refusal-notice", "refused-reopened"] if discovery["editor_closed"] else ["initial-refusal-notice"]
    if (not isinstance(artifacts, list) or any(not isinstance(a, dict) for a in artifacts)
            or [a.get("kind") for a in artifacts] != expected):
        raise ValueError("refusal requires separately named native notice and optional reopened editor")
    for artifact in artifacts:
        locator = artifact.get("locator")
        if (not isinstance(locator, dict) or locator.get("kind") not in {"role", "css"}
                or (locator["kind"] == "role" and (not isinstance(locator.get("role"), str)
                    or not locator["role"] or not isinstance(locator.get("name"), str) or not locator["name"]))
                or (locator["kind"] == "css" and (not isinstance(locator.get("value"), str)
                    or not locator["value"].strip() or locator["value"].strip() in {"body", "html", "*"}))
                or type(artifact.get("locator_matches")) is not int or artifact["locator_matches"] != 1
                or not SHA256.fullmatch(str(artifact.get("screenshot_sha256", "")))
                or not accessibility_labels({"nodes": artifact.get("nodes")})):
            raise ValueError("refusal subartifact needs unique native locator, visible nodes and PNG hash")
        if artifact["kind"] == "refused-reopened" and locator != {"kind": "role", "role": "dialog", "name": "Edit profile", "exact": True}:
            raise ValueError("reopened refusal artifact must be the named editor")
        if artifact["kind"] == "refused-reopened":
            read = artifact.get("describe_read")
            reopen = artifact.get("reopen_sequence")
            if (artifact.get("fields") != {"provider": inputs["provider"], "model": inputs["initial_model"]}
                    or not isinstance(read, dict) or read.get("profile") != inputs["name"]
                    or read.get("model") != inputs["initial_model"]
                    or type(reopen) is not int or type(read.get("sequence")) is not int
                    or not refusal_sequence < reopen < read["sequence"]):
                raise ValueError("refused reopen needs original fields and ordered named readback")
    if artifacts[0]["screenshot_sha256"] != screenshot_hash:
        raise ValueError("primary refusal PNG must be the discovered native notice")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    describe = commands.add_parser("describe")
    describe.add_argument("--surface", required=True)
    describe.add_argument("--state", required=True)
    describe.add_argument("--theme", required=True)
    describe.add_argument("--catalog", type=Path, default=CATALOG)
    describe.add_argument("--fixture-id")
    describe.add_argument("--platform", choices=("android", "desktop"))
    check = commands.add_parser("check-receipt")
    check.add_argument("--platform", choices=("android", "desktop"), required=True)
    check.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "describe":
            print(json.dumps(request(load_catalog(args.catalog), args.surface, args.state, args.theme, args.fixture_id, args.platform), sort_keys=True))
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
