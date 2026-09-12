#!/usr/bin/env python3
"""Which pin stamps may follow a re-pin, proved rather than assumed.

#195 set the rule this implements: a `@ <sha>` stamp moves only where every
citation under it was *verified* at the new SHA, and anything unconfirmed stays
put with its line numbers untouched, leaving the tree mixed-pin on purpose.

The verification is line-exact. For each citation the cited span is read out of
both SHAs and compared byte for byte, so an unchanged filename is never taken as
evidence that the lines under it held.

One case is refused rather than guessed. This repo writes continuations as a
bare `:NNN` under a path named earlier in the prose, and attaching one to the
wrong file mechanically is how a citation becomes confidently false. Where a
bare span falls outside its candidate file's length at the old pin, it is
reported as unattributable and its stamp stays.

Not every SHA in the tree is a citation. Some are PROVENANCE: a declaration of which
Desktop build a generated artifact was captured from. Those may only move when the
artifact is regenerated, so they are excluded here by path - moving one without
re-capturing turns a true record into a false one, and the composer-parity gate
catches exactly that by cross-checking two of them against each other.

Reads only. Prints the plan and writes it as JSON for the restamp to consume.

    ./scripts/verify-pin-citations.py <old-sha> <new-sha> [--json plan.json]
"""


from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess

UPSTREAM = pathlib.Path.home() / ".hermes/hermes-agent"
# Generated artifacts whose SHA records what was captured, not what was cited.
PROVENANCE = (
    "docs/parity/desktop-composer-inventory.json",  # an offline snapshot of Desktop's composer
    "docs/parity/composer-capture-matrix.json",     # the states that snapshot was captured in
    "docs/parity/composer-capabilities.json",       # cross-checked against the inventory's pin
    "docs/parity/visual/",                          # which Desktop build was actually rendered
)


PATH_RE = re.compile(
    r"(?P<path>(?:apps|tui_gateway|hermes_cli|gateway|agent|packages|website)/[A-Za-z0-9_./+-]+"
    r"\.(?:ts|tsx|js|jsx|py|css|json|md|yaml|yml|sh))"
    r"(?P<lines>(?::\d+(?:-\d+)?)(?:,\d+(?:-\d+)?)*)?"
)
BARE_RE = re.compile(r"`?:(\d+(?:-\d+)?(?:,\d+(?:-\d+)?)*)`?")

_blobs: dict[tuple[str, str], list[str] | None] = {}


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


def spans(raw: str) -> list[tuple[int, int]]:
    out: list[tuple[int, int]] = []
    for part in raw.lstrip(":").split(","):
        part = part.strip("`")
        if "-" in part:
            first, last = part.split("-", 1)
            if first.isdigit() and last.isdigit():
                out.append((int(first), int(last)))
        elif part.isdigit():
            out.append((int(part), int(part)))
    return out


def holds(old_sha: str, new_sha: str, path: str, ranges: list[tuple[int, int]]) -> bool | None:
    old, new = blob(old_sha, path), blob(new_sha, path)
    if old is None:
        return None  # never existed at the old pin; nothing to carry forward
    if new is None:
        return False  # deleted upstream
    if old == new:
        return True
    for first, last in ranges:
        for line in range(first, last + 1):
            if line > len(old) or line > len(new):
                return False
            if old[line - 1] != new[line - 1]:
                return False
    return True


def citations(text: str, old_sha: str):
    """Every citation, attributed, with whether the attribution itself is safe."""
    named_last: str | None = None
    for line in text.split("\n"):
        named = list(PATH_RE.finditer(line))
        for match in named:
            named_last = match.group("path")
            yield named_last, spans(match.group("lines") or ""), True
        if named or named_last is None:
            continue
        for match in BARE_RE.finditer(line):
            ranges = spans(match.group(1))
            body = blob(old_sha, named_last)
            safe = body is not None and all(last <= len(body) for _, last in ranges)
            yield named_last, ranges, safe


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("old")
    ap.add_argument("new")
    ap.add_argument("--json", default="")
    args = ap.parse_args()

    stamped = subprocess.run(
        [
            "grep", "-rl", args.old,
            "--exclude-dir=.git", "--exclude-dir=.worktrees",
            "--exclude-dir=build", "--exclude-dir=.claude", ".",
        ],
        capture_output=True,
        text=True,
    ).stdout.split()

    plan: dict[str, list[str]] = {"movable": [], "drifted": [], "unattributable": [], "provenance": []}
    for name in stamped:
        if any(marker in name for marker in PROVENANCE):
            plan["provenance"].append(name)
            continue
        text = pathlib.Path(name).read_text(errors="replace")
        drift, unattributable = [], []
        for path, ranges, safe in citations(text, args.old):
            if not safe:
                unattributable.append(path)
                continue
            if holds(args.old, args.new, path, ranges) is False:
                drift.append(path)
        if drift:
            plan["drifted"].append(name)
        elif unattributable:
            plan["unattributable"].append(name)
        else:
            plan["movable"].append(name)

    print(f"files carrying {args.old[:10]}: {len(stamped)}")
    print(f"  every citation byte-true at {args.new[:10]} -> move: {len(plan['movable'])}")
    print(f"  a cited span moved                          -> stay: {len(plan['drifted'])}")
    print(f"  a bare :NNN could not be attributed         -> stay: {len(plan['unattributable'])}")
    print(f"  provenance, not citation                    -> stay: {len(plan['provenance'])}")
    if args.json:
        pathlib.Path(args.json).write_text(json.dumps(plan, indent=1))


if __name__ == "__main__":
    main()
