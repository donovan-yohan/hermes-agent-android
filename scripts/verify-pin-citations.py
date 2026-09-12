#!/usr/bin/env python3
"""Classify upstream pin stamps by proving each cited span at both revisions.

A stamp may move only when every citation in its file resolves uniquely and every
cited line is byte-identical at the candidate revision. Generated capture
artifacts are provenance, not citations, and are never movable here.

    ./scripts/verify-pin-citations.py <old-sha> <new-sha> [--json plan.json]
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess

UPSTREAM = pathlib.Path.home() / ".hermes/hermes-agent"
PROVENANCE = (
    "docs/parity/desktop-composer-inventory.json",
    "docs/parity/composer-capture-matrix.json",
    "docs/parity/composer-capabilities.json",
    "docs/parity/visual/",
)

# Keep longer extensions first: `tsx` must not be parsed as `ts`, etc.
PATH_RE = re.compile(
    r"(?P<path>(?:[A-Za-z0-9_+.-]+/)*[A-Za-z0-9_+.-]+"
    r"\.(?:tsx|jsx|yaml|yml|json|css|md|py|ts|js|sh))"
    r"(?P<lines>(?::\d+(?:-\d+)?)(?:,\d+(?:-\d+)?)*)?"
)
BARE_RE = re.compile(r"`?:(\d+(?:-\d+)?(?:,\d+(?:-\d+)?)*)`?")

_blobs: dict[tuple[str, str], list[str] | None] = {}
_tree_paths: dict[str, list[str]] = {}


def blob(sha: str, path: str) -> list[str] | None:
    key = (sha, path)
    if key not in _blobs:
        done = subprocess.run(
            ["git", "-C", str(UPSTREAM), "show", f"{sha}:{path}"],
            capture_output=True,
            text=True,
            errors="replace",
        )
        _blobs[key] = done.stdout.split("\n") if done.returncode == 0 else None
    return _blobs[key]


def tree_paths(sha: str) -> list[str]:
    if sha not in _tree_paths:
        done = subprocess.run(
            ["git", "-C", str(UPSTREAM), "ls-tree", "-r", "--name-only", sha],
            capture_output=True,
            text=True,
            check=True,
        )
        _tree_paths[sha] = done.stdout.splitlines()
    return _tree_paths[sha]


def resolve_path(sha: str, cited: str) -> str | None:
    """Return an exact path or a uniquely matching upstream suffix; never guess."""
    if blob(sha, cited) is not None:
        return cited
    matches = [path for path in tree_paths(sha) if path.endswith(f"/{cited}")]
    return matches[0] if len(matches) == 1 else None


def spans(raw: str) -> list[tuple[int, int]]:
    out: list[tuple[int, int]] = []
    for part in raw.lstrip(":").split(","):
        if "-" in part:
            first, last = part.split("-", 1)
            if first.isdigit() and last.isdigit() and int(first) <= int(last):
                out.append((int(first), int(last)))
        elif part.isdigit():
            out.append((int(part), int(part)))
    return out


def holds(old_sha: str, new_sha: str, path: str, ranges: list[tuple[int, int]]) -> bool | None:
    """None means citation cannot be attributed; an empty span is never proof."""
    if not ranges:
        return None
    resolved = resolve_path(old_sha, path)
    if resolved is None:
        return None
    old, new = blob(old_sha, resolved), blob(new_sha, resolved)
    if old is None:
        return None
    if new is None:
        return False
    for first, last in ranges:
        if first < 1 or last > len(old) or last > len(new):
            return False
        if old[first - 1:last] != new[first - 1:last]:
            return False
    return True


def citations(text: str, old_sha: str):
    """Yield cited path, parsed spans, and safe attribution for every citation."""
    named_last: str | None = None
    for line in text.splitlines():
        named = list(PATH_RE.finditer(line))
        for match in named:
            cited = match.group("path")
            ranges = spans(match.group("lines") or "")
            resolved = resolve_path(old_sha, cited)
            named_last = resolved
            yield cited, ranges, resolved is not None and bool(ranges)
        if named or named_last is None:
            continue
        for match in BARE_RE.finditer(line):
            ranges = spans(match.group(1))
            body = blob(old_sha, named_last)
            safe = bool(ranges) and body is not None and all(last <= len(body) for _, last in ranges)
            yield named_last, ranges, safe


def classify(text: str, old_sha: str, new_sha: str) -> tuple[str, list[dict[str, object]]]:
    evidence: list[dict[str, object]] = []
    result = "movable"
    for path, ranges, safe in citations(text, old_sha):
        item: dict[str, object] = {
            "path": path,
            "resolved_path": resolve_path(old_sha, path),
            "ranges": ranges,
        }
        if not safe:
            item["result"] = "unattributable"
            result = "unattributable"
        else:
            held = holds(old_sha, new_sha, path, ranges)
            item["result"] = "unchanged" if held else "drifted" if held is False else "unattributable"
            if held is False and result != "unattributable":
                result = "drifted"
            elif held is None:
                result = "unattributable"
        evidence.append(item)
    if not evidence:
        result = "unattributable"
    return result, evidence


def is_provenance(name: str) -> bool:
    return any(marker in name for marker in PROVENANCE)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("old")
    ap.add_argument("new")
    ap.add_argument("--json", default="")
    args = ap.parse_args()

    stamped = subprocess.run(
        ["grep", "-rl", args.old, "--exclude-dir=.git", "--exclude-dir=.worktrees",
         "--exclude-dir=build", "--exclude-dir=.claude", "."],
        capture_output=True, text=True, check=False,
    ).stdout.split()
    plan: dict[str, list[dict[str, object]]] = {
        "movable": [], "drifted": [], "unattributable": [], "provenance": [],
    }
    for name in stamped:
        if is_provenance(name):
            plan["provenance"].append({"file": name})
            continue
        classification, evidence = classify(pathlib.Path(name).read_text(errors="replace"), args.old, args.new)
        plan[classification].append({"file": name, "citations": evidence})

    print(f"files carrying {args.old[:10]}: {len(stamped)}")
    print(f"  every cited span byte-true at {args.new[:10]} -> move: {len(plan['movable'])}")
    print(f"  a cited span moved or vanished                    -> stay: {len(plan['drifted'])}")
    print(f"  missing/ambiguous path or missing/empty span      -> stay: {len(plan['unattributable'])}")
    print(f"  provenance, not citation                          -> stay: {len(plan['provenance'])}")
    if args.json:
        pathlib.Path(args.json).write_text(json.dumps(plan, indent=2) + "\n")


if __name__ == "__main__":
    main()
