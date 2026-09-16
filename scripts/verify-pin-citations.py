#!/usr/bin/env python3
"""Classify upstream pin stamps by proving each cited span at both revisions.

A stamp may move only when every citation in its file resolves uniquely and every
cited line is byte-identical at the candidate revision. Generated capture
artifacts are provenance, not citations, and are never movable here.

    ./scripts/verify-pin-citations.py <old-sha> <new-sha> [--json plan.json]

`--check-range` is the gate a change runs against its own pin moves: every
citation the change restamped must be true at the SHA it now names, and a
citation that is not is reported as `<carrier>:<line>`.

    ./scripts/verify-pin-citations.py --check-range <base>..<head> [--fetch]

The range mode is deliberately diff-driven. The carrier scan above finds files by
grepping for the old SHA, so a file whose stamp has already been rewritten to the
new SHA drops out of that scan — exactly the file a pin-move change just edited.
A restamp is a diff event, so the diff is where it is discovered.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import tempfile

UPSTREAM = pathlib.Path.home() / ".hermes/hermes-agent"
PROVENANCE = (
    "docs/parity/desktop-composer-inventory.json",
    "docs/parity/composer-capture-matrix.json",
    "docs/parity/composer-capabilities.json",
    "docs/parity/visual/",
)

WORKTREE = "WORKTREE"

# Keep longer extensions first: `tsx` must not be parsed as `ts`, etc.
PATH_RE = re.compile(
    r"(?P<path>(?:[A-Za-z0-9_+.-]+/)*[A-Za-z0-9_+.-]+"
    r"\.(?:tsx|jsx|yaml|yml|json|css|md|py|ts|js|sh))"
    r"(?P<lines>(?::\d+(?:-\d+)?)(?:,\d+(?:-\d+)?)*)?"
)
# The `:` of a bare continuation must not be preceded by an identifier character:
# `SHA256:0pXQ…` in a redaction fixture is not a citation of line 0, and reading
# it as one is how the gate would report a finding nobody can act on.
BARE_RE = re.compile(r"`?(?<![A-Za-z0-9_]):(\d+(?:-\d+)?(?:,\d+(?:-\d+)?)*)`?")
# A full SHA is what a stamp moves; a shorter hex run still names a pin, so a
# citation carrying one is not this restamp's business unless it names the new
# SHA. Seven is git's own abbreviation floor.
PIN_RE = re.compile(r"(?<![0-9a-fA-F])[0-9a-f]{7,40}(?![0-9a-fA-F])")

_blobs: dict[tuple[str, str], list[str] | None] = {}
_tree_paths: dict[str, list[str]] = {}
_ensured: set[str] = set()
_READ: pathlib.Path | None = None
_SCRATCH: tempfile.TemporaryDirectory | None = None


def blob(sha: str, path: str) -> list[str] | None:
    key = (sha, path)
    if key not in _blobs:
        if path not in tree_paths(sha):
            _blobs[key] = None
            return None
        done = subprocess.run(
            ["git", "-C", str(read_root()), "show", f"{sha}:{path}"],
            capture_output=True,
            text=True,
            errors="replace",
            check=True,
        )
        _blobs[key] = done.stdout.split("\n")
    return _blobs[key]


def tree_paths(sha: str) -> list[str]:
    if sha not in _tree_paths:
        done = subprocess.run(
            ["git", "-C", str(read_root()), "ls-tree", "-r", "--name-only", sha],
            capture_output=True,
            text=True,
            check=True,
        )
        _tree_paths[sha] = done.stdout.splitlines()
    return _tree_paths[sha]


def read_root() -> pathlib.Path:
    """Where cited blobs are read from: the reference checkout, or its scratch clone.

    The reference checkout is read-only by policy — never `git fetch` inside it,
    never check anything out in it. A pin it does not have is obtained by fetching
    into a temporary clone that borrows its object store (`--reference`), and from
    then on reads come from that clone, which can see everything the reference
    checkout had plus what was fetched.
    """
    return _READ or UPSTREAM


def _has(sha: str) -> bool:
    return subprocess.run(
        ["git", "-C", str(read_root()), "cat-file", "-e", f"{sha}^{{commit}}"],
        capture_output=True, text=True,
    ).returncode == 0


def _scratch_clone() -> pathlib.Path:
    """A working clone of the read-only reference checkout, made once per run."""
    global _SCRATCH, _READ
    if _SCRATCH is None:
        _SCRATCH = tempfile.TemporaryDirectory(prefix="pin-citations-")
        target = pathlib.Path(_SCRATCH.name) / "upstream"
        origin = subprocess.run(
            ["git", "-C", str(UPSTREAM), "config", "--get", "remote.origin.url"],
            capture_output=True, text=True,
        ).stdout.strip()
        clone = subprocess.run(
            ["git", "clone", "--quiet", "--filter=blob:none", "--no-checkout",
             "--reference", str(UPSTREAM), str(UPSTREAM), str(target)],
            capture_output=True, text=True,
        )
        if clone.returncode != 0:
            raise RuntimeError(f"cannot build a scratch clone of {UPSTREAM}: {clone.stderr.strip()}")
        if origin:
            subprocess.run(
                ["git", "-C", str(target), "remote", "set-url", "origin", origin],
                capture_output=True, text=True, check=True,
            )
        _READ = target
        _tree_paths.clear()
        _blobs.clear()
    return pathlib.Path(_SCRATCH.name) / "upstream"


def ensure_sha(sha: str, fetch: bool) -> None:
    """Prove the cited revision can be read, or say what is missing.

    `fetch=False` (the default, and what a workstation gets) refuses with a named
    error rather than reaching the network, so a local `check` cannot silently
    depend on a connection.
    """
    if sha in _ensured:
        return
    if _has(sha):
        _ensured.add(sha)
        return
    if not fetch:
        raise RuntimeError(
            f"upstream checkout {UPSTREAM} does not have {sha}; "
            f"fetch it (--fetch), or point --upstream at a checkout that has it"
        )
    target = _scratch_clone()
    fetched = subprocess.run(
        ["git", "-C", str(target), "fetch", "--no-tags", "--depth", "1",
         "--filter=blob:none", "origin", sha],
        capture_output=True, text=True,
    )
    if fetched.returncode != 0:
        raise RuntimeError(f"cannot fetch {sha} into {target}: {fetched.stderr.strip()}")
    if not _has(sha):
        raise RuntimeError(f"{sha} was fetched into {target} but is still unreadable there")
    _ensured.add(sha)


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


def located_citations(text: str, sha: str):
    """Yield cited path, parsed spans, safe attribution, and carrying line.

    `sha` is the revision the text is read at, so a citation's spans are checked
    against the same revision its path was resolved in.
    """
    named_last: str | None = None
    for number, line in enumerate(text.splitlines(), 1):
        named = list(PATH_RE.finditer(line))
        for match in named:
            cited = match.group("path")
            ranges = spans(match.group("lines") or "")
            resolved = resolve_path(sha, cited)
            named_last = resolved
            yield number, cited, ranges, resolved is not None and bool(ranges)
        if named or named_last is None:
            continue
        for match in BARE_RE.finditer(line):
            ranges = spans(match.group(1))
            body = blob(sha, named_last)
            safe = bool(ranges) and body is not None and all(last <= len(body) for _, last in ranges)
            yield number, named_last, ranges, safe


def citations(text: str, old_sha: str):
    """Yield cited path, parsed spans, and safe attribution for every citation."""
    for _number, cited, ranges, safe in located_citations(text, old_sha):
        yield cited, ranges, safe


def classify(text: str, old_sha: str, new_sha: str) -> tuple[str, list[dict[str, object]]]:
    evidence: list[dict[str, object]] = []
    result = "movable"
    for number, path, ranges, safe in located_citations(text, old_sha):
        item: dict[str, object] = {
            "line": number,
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


def stamped_files(old_sha: str) -> list[str]:
    """Return every carrier, aborting rather than mistaking a failed scan for zero."""
    done = subprocess.run(
        ["grep", "-rlZ", old_sha, "--exclude-dir=.git", "--exclude-dir=.worktrees",
         "--exclude-dir=build", "--exclude-dir=.claude", "."],
        capture_output=True,
        text=True,
    )
    if done.returncode == 1:
        return []
    if done.returncode != 0:
        detail = done.stderr.strip() or "no diagnostic"
        raise RuntimeError(f"citation carrier scan failed (grep exit {done.returncode}): {detail}")
    return [name for name in done.stdout.split("\0") if name]


def text_at(repo: str, rev: str, path: str) -> str | None:
    """Read a file at a revision, or from the working tree for WORKTREE."""
    if rev == WORKTREE:
        target = pathlib.Path(repo) / path
        return target.read_text(errors="replace") if target.is_file() else None
    done = subprocess.run(
        ["git", "-C", repo, "show", f"{rev}:{path}"],
        capture_output=True,
        text=True,
        errors="replace",
    )
    return done.stdout if done.returncode == 0 else None


def changed_files(repo: str, base: str, head: str) -> list[str]:
    command = ["git", "-C", repo, "diff", "--name-only", "--diff-filter=ACMR", base]
    if head != WORKTREE:
        command.append(head)
    done = subprocess.run(command, capture_output=True, text=True)
    if done.returncode != 0:
        raise RuntimeError(f"cannot diff {base}..{head} in {repo}: {done.stderr.strip()}")
    return [name for name in done.stdout.splitlines() if name]


def full_pins(text: str) -> set[str]:
    return set(re.findall(r"(?<![0-9a-fA-F])[0-9a-f]{40}(?![0-9a-fA-F])", text))


def citation_pin(lines: list[str], number: int, introduced: list[str]) -> str | None:
    """The pin a citation sits under, or None when it is not this change's business.

    Three shapes carry a citation, and all three are in the tree today:

    * the revision on the citation's own line — `` `path:line` @ `sha` ``;
    * the revision on the *next* line, when the line wrapped — `` `path:line` `` /
      ``@ `sha` `` — which is only a continuation when that next line holds no
      citation of its own, or a table row beside it would lend its pin here;
    * the page-level pin, declared once in a parity page's `## Pin` table, with
      the citations below it naming no SHA of their own.

    Anything else — a citation under a pin this change did not move, or one that
    cannot be tied to a pin at all — is left alone: a file may carry several pins
    (only 26 name a single one), and a change that moves one stamp is not
    answerable for the citations that still name another.
    """
    own = PIN_RE.findall(lines[number - 1])
    if not own:
        following = lines[number] if number < len(lines) else ""
        # A continuation line carries the pin and nothing else; a line that also
        # carries a citation is a citation of its own, not this one's tail.
        if not PATH_RE.search(following):
            own = PIN_RE.findall(following)
    for pin in introduced:
        if any(pin.startswith(token) for token in own):
            return pin
    if own:
        return None
    # No pin named here and none on the next line: the nearest pin declared above
    # (a page's `## Pin` table) governs, and only if this change introduced it.
    for line in reversed(lines[:number - 1]):
        above = PIN_RE.findall(line)
        if above:
            for pin in introduced:
                if any(pin.startswith(token) for token in above):
                    return pin
            return None
    return introduced[0] if len(introduced) == 1 else None


def carried_verdict(
    old_sha: str, new_sha: str, cited: str, ranges: list[tuple[int, int]]
) -> tuple[str | None, str | None]:
    """Prove a citation that was carried across the restamp; (reason, resolved).

    `reason` is None when the citation holds. Attribution is judged at the *old*
    pin — the revision the citation was written against — because that is where
    the claim can be read: a citation with no parsed span, a path that does not
    resolve there, or a span past the end of the file it names was already not a
    provable upstream citation before this change, and passing it over is the only
    honest reading. A citation that *was* attributable and is not any more is this
    change's doing, and is reported.
    """
    if not ranges:
        return None, None
    resolved = resolve_path(old_sha, cited)
    if resolved is None:
        return None, None
    old = blob(old_sha, resolved)
    # A span that does not fit the old pin's own file was never a citation — a
    # `:0` lifted out of a fixture string, a `:NNN` tail the extractor re-pointed
    # at a neighbouring path — and blaming the restamp for it would be noise.
    if old is None or any(first < 1 or last > len(old) for first, last in ranges):
        return None, None
    moved_to = resolve_path(new_sha, cited)
    if moved_to is None:
        return "path-missing", resolved
    new = blob(new_sha, moved_to)
    if new is None:
        return "path-missing", resolved
    if any(first < 1 or last > len(new) for first, last in ranges):
        return "span-out-of-bounds", resolved
    if all(old[first - 1:last] == new[first - 1:last] for first, last in ranges):
        return None, resolved
    # An in-place edit — the construct's first and last lines survive — is still
    # the construct the citation names (#291's own accepted shape). A span whose
    # edges moved is a different construct wearing the same line numbers.
    if all(old[first - 1] == new[first - 1] and old[last - 1] == new[last - 1] for first, last in ranges):
        return None, resolved
    return "construct-moved", resolved


def written_verdict(new_sha: str, cited: str, ranges: list[tuple[int, int]]) -> tuple[str | None, str | None]:
    """Floor for a citation the change itself wrote: it must fall inside its path.

    Byte truth is not mechanically checkable for a re-derived span — only the
    author knows what it claims — but a span past the end of the file it names is
    wrong whatever it claims.
    """
    if not ranges:
        return None, None
    resolved = resolve_path(new_sha, cited)
    if resolved is None:
        return None, None
    body = blob(new_sha, resolved)
    if body is None:
        return None, resolved
    if any(first < 1 or last > len(body) for first, last in ranges):
        return "span-out-of-bounds", resolved
    return None, resolved


class Finding:
    """One citation that a restamp left untrue at the SHA it names."""

    __slots__ = ("carrier", "line", "cited", "ranges", "resolved", "reason")

    def __init__(
        self,
        carrier: str,
        line: int,
        cited: str,
        ranges: list[tuple[int, int]],
        resolved: str | None,
        reason: str,
    ) -> None:
        self.carrier = carrier
        self.line = line
        self.cited = cited
        self.ranges = ranges
        self.resolved = resolved
        self.reason = reason

    def __str__(self) -> str:
        span = ",".join(f"{first}-{last}" if first != last else str(first) for first, last in self.ranges)
        target = f"{self.cited}:{span}" if span else self.cited
        return f"{self.carrier}:{self.line}: {target} — {self.reason}"

    def evidence(self) -> dict[str, object]:
        """What the reviewer needs to re-read the span, and nothing that leaks."""
        return {
            "carrier": self.carrier,
            "line": self.line,
            "cited": self.cited,
            "resolved": self.resolved,
            "ranges": self.ranges,
            "reason": self.reason,
        }


def check_range_spec(
    spec: str, repo: str = ".", fetch: bool = False
) -> tuple[list[Finding], dict[str, int], dict[str, list[str]]]:
    """Read a `<base>..<head>` range spec, where an empty head means `HEAD`.

    `<base>..` is the working tree — what a developer runs before committing, and
    what a CI checkout has not committed. `<base>...` is git's own merge-base
    form, and it is resolved through `git merge-base` rather than treated as
    `<base>..`: on a branch that trails its base, the two-dot diff would include
    reversing every pin the base branch advanced, and the gate would report the
    base branch's own history as this change's moves. Merge-base is the range a
    reviewer reads, so it is the range that is checked.
    """
    base, separator, head = spec.partition("..")
    if not separator:
        raise RuntimeError(f"{spec!r} is not a range; write <base>..<head>")
    merge_base = head.startswith(".")
    head = head.lstrip(".") or "HEAD"
    if merge_base:
        done = subprocess.run(
            ["git", "-C", repo, "merge-base", base, head],
            capture_output=True, text=True,
        )
        if done.returncode != 0:
            raise RuntimeError(
                f"cannot find the merge base of {base} and {head} in {repo}: "
                f"{done.stderr.strip() or 'no such revision'}"
            )
        base = done.stdout.strip()
    return check_range(base, head, repo=repo, fetch=fetch)


def check_range(
    base: str, head: str, repo: str = ".", fetch: bool = False
) -> tuple[list[Finding], dict[str, int], dict[str, list[str]]]:
    """Check every citation the range restamped is true at the pin it names.

    Returns findings, counters, and the pins this checkout could not supply —
    kept apart from the findings so a caller can say "not provable here" instead
    of "wrong", which are different claims and must not be conflated.
    """
    findings: list[Finding] = []
    counts = {"files": 0, "checked": 0, "skipped": 0, "ambiguous": 0}
    unreachable: dict[str, list[str]] = {}
    for name in changed_files(repo, base, head):
        if is_provenance(name):
            continue
        base_text, head_text = text_at(repo, base, name), text_at(repo, head, name)
        if base_text is None or head_text is None:
            continue
        retired = full_pins(base_text) - full_pins(head_text)
        introduced = full_pins(head_text) - full_pins(base_text)
        if not retired or not introduced:
            continue
        counts["files"] += 1
        retired_list = sorted(retired)
        introduced_list = sorted(introduced)
        missing: list[str] = []
        for pin in retired_list + introduced_list:
            try:
                ensure_sha(pin, fetch)
            except RuntimeError:
                missing.append(pin)
        if missing:
            unreachable[name] = missing
            continue
        # Dispatch is by what the *base* revision cited, not by what it could
        # attribute: a citation present in both revisions is a carried one, and
        # `carried_verdict` is where attributability at the old pin is judged.
        base_carried = {
            (cited, tuple(ranges))
            for old_sha in retired_list
            for _, cited, ranges, _ in located_citations(base_text, old_sha)
        }
        lines = head_text.splitlines()
        for number, cited, ranges, _safe in located_citations(head_text, introduced_list[0]):
            pin = citation_pin(lines, number, introduced_list)
            if pin is None:
                counts["ambiguous"] += 1
                continue
            key = (cited, tuple(ranges))
            if key in base_carried:
                considered = False
                reasons: list[tuple[str | None, str | None]] = []
                for old_sha in retired_list:
                    reason, resolved = carried_verdict(old_sha, pin, cited, ranges)
                    if reason is None and resolved is None:
                        continue
                    considered = True
                    reasons.append((reason, resolved))
                if not considered:
                    counts["skipped"] += 1
                    continue
                if any(reason is None for reason, _ in reasons):
                    counts["checked"] += 1
                    continue
                reason, resolved = reasons[0]
                findings.append(Finding(name, number, cited, ranges, resolved, reason or "drifted"))
            else:
                reason, resolved = written_verdict(pin, cited, ranges)
                if reason is None and resolved is None:
                    counts["skipped"] += 1
                    continue
                if reason is None:
                    counts["checked"] += 1
                    continue
                findings.append(Finding(name, number, cited, ranges, resolved, reason))
    return findings, counts, unreachable


def self_test() -> None:
    """Prove the gate on a fixture: a drifted span fails, a byte-true move passes.

    The fixture is a real repository with a real history, because the gate's whole
    claim is about resolving one revision against another — a stubbed `git show`
    would prove the stub, not the gate.
    """
    global UPSTREAM
    original = UPSTREAM
    with tempfile.TemporaryDirectory() as directory:
        repo = pathlib.Path(directory) / "fixture"
        repo.mkdir()
        subprocess.run(["git", "init", "-q", str(repo)], check=True)
        identity = ["-c", "user.email=fixture@example.invalid", "-c", "user.name=fixture"]

        def git(*args: str) -> str:
            done = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True)
            if done.returncode != 0:
                raise AssertionError(f"fixture git {args} failed: {done.stderr.strip()}")
            return done.stdout.strip()

        def write(relative: str, text: str) -> None:
            (repo / relative).parent.mkdir(parents=True, exist_ok=True)
            (repo / relative).write_text(text, encoding="utf-8")

        def commit(message: str) -> str:
            git("add", "-A")
            git(*identity, "commit", "-q", "--no-gpg-sign", "-m", message)
            return git("rev-parse", "HEAD")

        def page(pin: str, borrowed: str) -> str:
            """A parity page: its own pin in the table, one citation under another.

            The second row is the whole point of carrying `borrowed`: the repo has
            26 files naming more than one pin, and a change that moves one stamp is
            not answerable for a citation that still names a different one.
            """
            return (
                "# Fixture surface\n\n## Pin\n\n| Source | Pin | Read via |\n|---|---|---|\n"
                f"| fixture | `{pin}` | `git show <sha>:<path>` |\n\n"
                "Every `path:line` below is against that SHA.\n\n"
                "| Question | Path |\n|---|---|\n"
                "| entry point | `lib/util.ts:2-4` |\n"
                f"| borrowed from another pin | `lib/util.ts:1-2` @ `{borrowed}` |\n"
            )

        write("lib/util.ts", "one\ntwo\nthree\nfour\n")
        write("docs/page.md", "# placeholder\n")
        first = commit("fixture opens the repository")
        # A pin the page never moves: it is a different revision's citation and
        # must survive every move below untouched.
        write("docs/notes.md", "unrelated\n")
        borrowed = commit("fixture writes an unrelated file")
        write("docs/page.md", page(first, borrowed))
        pinned = commit("fixture records a pin")
        git("branch", "-M", "main")

        def branch(name: str, source: str) -> None:
            git("checkout", "-q", "-b", name, source)

        # (1) A byte-true move: only the stamp moves, the cited span is untouched.
        branch("append", pinned)
        write("lib/util.ts", "one\ntwo\nthree\nfour\nfive\n")
        appended = commit("fixture appends below the cited span")
        write("docs/page.md", page(appended, borrowed))
        byte_true = commit("fixture moves the stamp past an append")

        # (2) The cited construct edited in place: its interior changed, its first
        #     and last lines did not. This is #291's own accepted shape —
        #     `statusbar.tsx:44-50` gained the ported `~` at :46 and stayed true.
        branch("edit", pinned)
        write("lib/util.ts", "one\ntwo\nthree and a half\nfour\n")
        edited = commit("fixture edits inside the cited span")
        write("docs/page.md", page(edited, borrowed))
        in_place = commit("fixture moves the stamp over an in-place edit")

        # (3) A drift: the span's own boundary line now holds another construct, so
        #     the citation no longer names what it says it does.
        branch("drift", pinned)
        write("lib/util.ts", "one\nelsewhere\nthree\nfour\n")
        drifted = commit("fixture replaces the construct's first line")
        write("docs/page.md", page(drifted, borrowed))
        construct_moved = commit("fixture moves the stamp over a drift")

        # (4) The cited path is no longer there at the pin; the citation names
        #     nothing. A short `:NNN` tail cannot rescue it.
        branch("moved", pinned)
        git("mv", "lib/util.ts", "lib/elsewhere.ts")
        renamed = commit("fixture moves the cited file")
        write("docs/page.md", page(renamed, borrowed))
        path_missing = commit("fixture moves the stamp over a moved path")

        UPSTREAM = repo
        _blobs.clear()
        _tree_paths.clear()
        _ensured.clear()

        def gate(base: str, head: str, label: str) -> tuple[list[Finding], dict[str, int]]:
            _blobs.clear()
            _tree_paths.clear()
            try:
                findings, counts, unreachable = check_range(base, head, repo=str(repo))
            except Exception as error:  # noqa: BLE001 - reported as the fixture's own failure
                raise AssertionError(f"{label}: the gate raised {error!r}") from error
            if unreachable:
                raise AssertionError(f"{label}: the fixture's own pins were unreadable: {unreachable}")
            return findings, counts

        try:
            findings, counts = gate(pinned, byte_true, "byte-true move")
            if findings:
                raise AssertionError(f"a byte-true move was rejected: {[str(one) for one in findings]}")
            if counts["checked"] < 1:
                raise AssertionError("the byte-true fixture proved nothing; no citation was checked")
            if counts["ambiguous"] < 1:
                raise AssertionError(
                    "the citation borrowed from another pin should have been left alone, not checked"
                )

            findings, counts = gate(pinned, in_place, "in-place edit")
            if findings:
                raise AssertionError(f"an in-place edit was rejected: {[str(one) for one in findings]}")

            findings, _ = gate(pinned, construct_moved, "drift")
            if not findings:
                raise AssertionError("a drifted span was accepted")
            if findings[0].carrier != "docs/page.md":
                raise AssertionError(f"the drift was not attributed to its carrier: {findings[0].carrier}")
            if findings[0].reason != "construct-moved":
                raise AssertionError(f"a drift was mislabelled: {findings[0].reason}")
            if "lib/util.ts" not in str(findings[0]):
                raise AssertionError(f"the drifted citation was not named: {findings[0]}")
            if len(findings) != 1:
                raise AssertionError(
                    f"a citation outside this move's pin was counted as its own: "
                    f"{[str(one) for one in findings]}"
                )

            findings, _ = gate(pinned, path_missing, "moved path")
            if not findings or findings[0].reason != "path-missing":
                raise AssertionError(
                    f"a citation whose path is gone at the pin was accepted: {[str(one) for one in findings]}"
                )

            # The gate must be quiet when a range moves no pin at all: the common
            # case, and the one where a false positive would be worst.
            empty, counts = gate(pinned, pinned, "no move")
            if empty or counts["files"]:
                raise AssertionError(f"a range that moves no pin was not skipped: {[str(one) for one in empty]}")
        finally:
            UPSTREAM = original


def main() -> None:
    global UPSTREAM

    ap = argparse.ArgumentParser()
    ap.add_argument("old", nargs="?")
    ap.add_argument("new", nargs="?")
    ap.add_argument("--json", default="")
    ap.add_argument("--check-range", default="", metavar="BASE..HEAD",
                    help="prove the pin moves this range makes; HEAD may be WORKTREE")
    ap.add_argument("--repo", default=".", help="the checkout the range is read from")
    ap.add_argument("--upstream", default="", help=f"read-only upstream checkout (default {UPSTREAM})")
    ap.add_argument("--fetch", action="store_true",
                    help="shallow-fetch pins the upstream checkout does not have yet")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.upstream:
        UPSTREAM = pathlib.Path(args.upstream).expanduser()

    if args.self_test:
        self_test()
        print("ok    pin-citation gate fails a drifted span and a moved path, passes a byte-true move")
        return

    if args.check_range:
        # A tool failure must not exit 1: that code means "a citation is not true
        # at the SHA it names", and a caller acting on it goes hunting citations
        # that are fine while the real fault — an unresolvable range, a diff that
        # would not run — goes unreported. Exit 3 is "this could not be decided",
        # which is a third claim and needs its own code.
        try:
            findings, counts, unreachable = check_range_spec(
                args.check_range, repo=args.repo, fetch=args.fetch
            )
        except RuntimeError as error:
            print(f"ERROR  the pin-citation range check could not run: {error}")
            print("  this is a tool or revision problem, not a verdict on any citation.")
            raise SystemExit(3)
        if args.json:
            out = pathlib.Path(args.json)
            out.write_text(json.dumps([one.evidence() for one in findings], indent=2) + "\n")
        for finding in findings:
            print(f"FAIL  {finding}")
        for name, pins in sorted(unreachable.items()):
            shown = ", ".join(pin[:10] for pin in pins)
            print(f"UNPROVABLE  {name}: this checkout cannot read {shown}")
        if findings:
            print(f"  {len(findings)} citation(s) in {counts['files']} restamped file(s) are not true at the SHA they name.")
            print("  fix: re-derive each cited path and span at the new pin, or keep the citation at the pin it belongs to;")
            print("       docs/workflows/review-desktop-parity.md, `### Moving a pin`, says how to read upstream at a SHA.")
            raise SystemExit(1)
        if unreachable:
            # Exit 2 is "this checkout could not prove it", which is a different
            # claim from "the citations are wrong". A caller that cannot fetch
            # (the local read-only reference checkout) skips loudly on 2; CI
            # passes --fetch and treats 2 as a failure, because it should not
            # have happened there.
            print(f"  {len(unreachable)} restamped file(s) were not checked: their pins are not in this checkout.")
            print("  fix: run with --fetch, or point --upstream at a checkout that has those revisions.")
            raise SystemExit(2)
        print(
            f"ok    {counts['files']} restamped file(s): {counts['checked']} citation(s) true at the SHA they name, "
            f"{counts['skipped']} not provable, {counts['ambiguous']} under another pin"
        )
        return

    if not (args.old and args.new):
        ap.error("give <old-sha> <new-sha>, or --check-range <base>..<head>, or --self-test")

    output = pathlib.Path(args.json) if args.json else None
    if output is not None:
        output.unlink(missing_ok=True)
    stamped = stamped_files(args.old)
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
    if output is not None:
        output.write_text(json.dumps(plan, indent=2) + "\n")


if __name__ == "__main__":
    main()
