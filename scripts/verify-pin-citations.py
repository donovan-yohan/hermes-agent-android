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
# A parity page may narrow one pin row to a subset of its citations by naming a
# marker in that row's read-via column — "the citations marked *(registry)*
# below were taken at that SHA". The marker is the only thing that selects such
# a row, so an added narrower row cannot capture the general row's citations.
MARKER_RE = re.compile(r"\*\(([a-z0-9][a-z0-9_-]*)\)\*")
# A revision a page declares is a full SHA. A shorter hex run in prose is how the
# text names a commit it is talking *about* ("it landed as `3ec8042483`"), and
# reading one as the declaration for the citations below it is how an honest
# section ("pinned at `X` rather than at the authority above") got bound to the
# authority it was written to exclude (#240).
FULL_SHA_RE = re.compile(r"(?<![0-9a-fA-F])[0-9a-f]{40}(?![0-9a-fA-F])")
# Text that marks the token after it as a revision rather than a number: inside
# backticks, or introduced by `@` — which is how a citation's own pin and a
# wrapped continuation are both written.
_SHA_CONTEXT_RE = re.compile(r"`{1,2}\s*$|@\s*$")
HEADING_RE = re.compile(r"^\s{0,3}#{1,6}\s+\S")
# A page heads its pin section `## Pin` or `## Pin and source contract`, so the
# heading is matched on the word `Pin` and not on the whole line: five parity
# pages write the longer form, and reading one of them as declaring no pin left
# the citations under it attributed to nothing, so a drift among them went
# unreported (#297 review: the acceptance range reported 7 findings where 13 are
# true). The word boundary is what keeps `## Pinning the surface` out.
PIN_SECTION_RE = re.compile(r"^\s{0,3}#{1,6}\s*[Pp]in\b")

_blobs: dict[tuple[str, str], list[str] | None] = {}
_tree_paths: dict[str, list[str]] = {}
_ensured: set[str] = set()
_READ: pathlib.Path | None = None
_SCRATCH: tempfile.TemporaryDirectory | None = None
_MISSING: set[str] = set()


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
        body = done.stdout.split("\n")
        # `split` on a newline-terminated file leaves a trailing empty element
        # that is not a line of the file. Left in, it makes a citation one past
        # the real last line (`:N+1` on an N-line file) read as in-bounds and
        # byte-true against itself — a real citation that names nothing. Exactly
        # one trailing element is dropped, so interior blank lines survive.
        if body and body[-1] == "":
            body.pop()
        _blobs[key] = body
    return _blobs[key]


def tree_paths(sha: str) -> list[str]:
    if sha not in _tree_paths:
        if not _has(sha):
            # Not a revision of the read checkout, so it has no tree to list.
            # Returning nothing is honest — "this SHA names no file here" — and it
            # keeps a file that carries some *other* repository's SHA (a GitHub
            # Action pin, say) from aborting the whole run.
            _tree_paths[sha] = []
            return []
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
    """A working clone of the read-only reference checkout, made once per run.

    State is published only after the clone succeeds: `_SCRATCH` assigned first
    would leave a later call returning a target that was never built, and `_READ`
    unset, so every subsequent read would silently fall back to the reference
    checkout. The failed attempt is cleaned up rather than kept.
    """
    global _SCRATCH, _READ
    if _SCRATCH is not None:
        return pathlib.Path(_SCRATCH.name) / "upstream"
    scratch = tempfile.TemporaryDirectory(prefix="pin-citations-")
    target = pathlib.Path(scratch.name) / "upstream"
    try:
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
    except BaseException:
        scratch.cleanup()
        raise
    _SCRATCH = scratch
    _READ = target
    _tree_paths.clear()
    _blobs.clear()
    return target


def ensure_sha(sha: str, fetch: bool) -> None:
    """Prove the cited revision can be read, or say what is missing.

    `fetch=False` (the default, and what a workstation gets) refuses with a named
    error rather than reaching the network, so a local `check` cannot silently
    depend on a connection.

    A pin that cannot be obtained is remembered as such: without that, every
    restamped file citing it retries the same network fetch and reports the same
    failure, which is both slow and a way to get a rate-limited gate disabled.
    """
    if sha in _ensured or sha in _MISSING:
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
        _MISSING.add(sha)
        raise RuntimeError(f"cannot fetch {sha} into {target}: {fetched.stderr.strip()}")
    if not _has(sha):
        raise RuntimeError(f"{sha} was fetched into {target} but is still unreadable there")
    _ensured.add(sha)


def resolve_candidates(sha: str, cited: str) -> list[str]:
    """Every upstream path the cited string can denote, exact matches first.

    Two shorthand forms are in live use and neither is a typo. An elided
    citation (`.../dir/file.ts`) drops its leading `.../` and nothing else: the
    elision carries no path information, so what remains is an ordinary suffix.
    A bare basename (`en.ts`) can legitimately denote several files, and a
    verdict must judge every candidate its span could have meant rather than
    taking whichever tree entry happens to come first.
    """
    if blob(sha, cited) is not None:
        return [cited]
    tail = cited[4:] if cited.startswith(".../") else cited
    return [path for path in tree_paths(sha) if path == tail or path.endswith(f"/{tail}")]


def resolve_path(sha: str, cited: str) -> str | None:
    """The one path a citation names, or None when its form is ambiguous."""
    candidates = resolve_candidates(sha, cited)
    return candidates[0] if len(candidates) == 1 else None


def spans(raw: str) -> list[tuple[int, int]]:
    """Parsed line spans; a span that starts below line 1 is not a citation.

    `:0` is not a line of any file. A JSON literal (`\"\"\"{"pending":0,…}\"\"\"`) or a
    fixture string that happens to sit under a path-bearing line yields one, and
    admitting it manufactures a citation nobody wrote — which a verdict then
    reports as an out-of-bounds finding against a path that was never cited.
    Refusing it here, where citations are extracted, keeps the whole class out;
    a bounds check inside one verdict can only answer for that verdict.
    """
    out: list[tuple[int, int]] = []
    for part in raw.lstrip(":").split(","):
        if "-" in part:
            first, last = part.split("-", 1)
            if first.isdigit() and last.isdigit() and 1 <= int(first) <= int(last):
                out.append((int(first), int(last)))
        elif part.isdigit() and int(part) >= 1:
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
    """Read a file at a revision, or from the working tree for WORKTREE.

    `None` means the path is not in that revision. A `git show` that fails for
    any other reason is raised rather than reported as absence: the range diff
    lists only added/copied/modified/renamed paths, so a read that fails for an
    unrelated reason must not be silently skipped as "no file here".
    """
    if rev == WORKTREE:
        target = pathlib.Path(repo) / path
        return target.read_text(errors="replace") if target.is_file() else None
    done = subprocess.run(
        ["git", "-C", repo, "show", f"{rev}:{path}"],
        capture_output=True,
        text=True,
        errors="replace",
    )
    if done.returncode == 0:
        return done.stdout
    # Distinguish "this path is not in that revision" from "the read failed".
    # The check must run against `repo`, not the upstream checkout `tree_paths`
    # reads from: they are different repositories and different revisions.
    present = subprocess.run(
        ["git", "-C", repo, "cat-file", "-e", f"{rev}:{path}"],
        capture_output=True, text=True,
    )
    if present.returncode != 0:
        return None
    raise RuntimeError(
        f"cannot read {path} at {rev} in {repo} although it is in that tree: "
        f"{done.stderr.strip() or 'git show failed'}"
    )


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


def _sha_tokens(line: str) -> list[str]:
    """Pin-shaped tokens on a line: a revision, not a decimal literal in code.

    ``PIN_RE`` matches any hex run, and every digit is a hex digit, so a code
    line like ``assertEquals("1M", compactNumber(1000000))`` reads as a pin.
    A token is a revision when it is unambiguous on its own (a full 40-character
    SHA, or an abbreviation carrying a hex letter — only (10/16)^7 ≈ 6% of 7-char
    prefixes are all-digits) or when the text just before it marks it as one:
    inside backticks, or introduced by ``@``, which is how a citation's own pin
    and a wrapped continuation are both written.
    """
    out: list[str] = []
    for match in PIN_RE.finditer(line):
        token = match.group(0)
        if len(token) == 40 or re.search(r"[a-f]", token):
            out.append(token)
            continue
        if _SHA_CONTEXT_RE.search(line[: match.start()]):
            out.append(token)
    return out


def _match_pin(tokens: list[str], introduced: list[str]) -> str | None:
    return next(
        (pin for pin in introduced if any(pin.startswith(token) for token in tokens)),
        None,
    )


def _continuation_of_above(lines: list[str], number: int) -> bool:
    """True when this 1-based line's revision is the tail of a wrapped citation.

    A citation that wraps puts its revision on the next line — `` `path:line` @``
    / `` `sha` `` — so that revision supplies the citation above it and declares
    nothing for the lines below. Reading it as the nearest declaration is how
    every later citation in the file got re-pointed at a neighbour's pin (#297
    round 2: the `:0` and `construct-moved` false reds on honest history).
    """
    return number >= 2 and lines[number - 2].rstrip().endswith("@")


def _supplied_by_at(line: str) -> bool:
    """True when the line's revision is introduced by `@`: a citation's own pin.

    The same revision written *without* the `@` — a section saying it is "pinned
    at `sha`" — is a declaration. That one character is the whole difference.
    """
    for match in PIN_RE.finditer(line):
        if line[: match.start()].rstrip("`").rstrip().endswith("@"):
            return True
    return False


def _path_pin_tokens(lines: list[str], number: int, cited: str) -> list[str] | None:
    """Tokens of the earlier citation that names this same path, if there is one.

    A bare ``:NNN`` continues the path above it, so it continues that path's pin
    too: ``:189`` under a ``change_watcher.py:180`` names the same file at the
    same revision. A citation that spells its path out again is read at the
    revision that path was last cited at in its own section, which is how a
    wrapped continuation's pin reaches the citation below it (#297 round 2: the
    `ChatViewModel.kt:927` true positive is bound this way, and restricting
    inheritance to bare continuations loses it — measured, not assumed).

    The pin found here is only offered as a candidate: `_match_pin` accepts it
    only when this change introduced it, so a path cited at a pin the change did
    not move leaves the citation on its own pin rather than re-binding it to a
    revision the surrounding prose has moved past (#301: a doc comment
    re-pinned the file while the same path was still cited at the old pin three
    paragraphs up).
    """
    base = cited.rsplit("/", 1)[-1]
    for index in range(number - 2, -1, -1):
        line = lines[index]
        if HEADING_RE.match(line):
            break
        if not any(
            match.group("path").rsplit("/", 1)[-1] == base
            for match in PATH_RE.finditer(line)
        ):
            continue
        tokens = _sha_tokens(line)
        if not tokens and index + 1 < len(lines) and not PATH_RE.search(lines[index + 1]):
            tokens = _sha_tokens(lines[index + 1])
        return tokens
    return None


def _table_rows(lines: list[str]) -> dict[int, tuple[str, str | None, bool]]:
    """Pin-bearing table rows that are not citations, by 0-based line index.

    Each entry is ``(pin, marker or None, is_general_row)``. A table's *first*
    pin-bearing row is its general row — the page declares what it governs in
    that row's own read-via column. A *later* row is narrower: it governs only
    the citations carrying the marker it names ("the citations marked
    *(registry)* below were taken at that SHA"), and an unmarked citation falls
    back to the general row rather than to it. Reading the nearest row as the
    general one is what let an added registry row re-point every unmarked
    citation in a file, turning honest merged history red (#280, #264).
    """
    rows: dict[int, tuple[str, str | None, bool]] = {}
    inside = False
    seen = False
    for index, line in enumerate(lines):
        is_row = line.lstrip().startswith("|")
        if is_row != inside:
            inside = is_row
            seen = False
        if not is_row or PATH_RE.search(line):
            continue
        tokens = _sha_tokens(line)
        if not tokens:
            continue
        marker = MARKER_RE.search(line)
        rows[index] = (tokens[0], marker.group(1) if marker else None, not seen)
        seen = True
    return rows


def _section_start(lines: list[str], number: int) -> int:
    """1-based line of the heading that opens the citation's own section."""
    for index in range(number - 1, 0, -1):
        if HEADING_RE.match(lines[index - 1]):
            return index
    return 1


def _borrowed_above(lines: list[str], number: int) -> bool:
    """True when a table row above carries a pin of its own, in the same table.

    A table of citations lists each one with its own pin (``| q | path:line @ sha
    |``), so an unmarked row among marked ones really is unattributed: the table
    is per-row about pins, and nothing above says which revision this row is
    against. That is a table's shape, not prose's. A source file's comment that
    names no revision is governed by the file's declarations, and withholding it
    there would drop a carried citation the change re-pinned — a false pass on
    the very class this gate exists to catch (#283's `agent/display.py:446`).
    """
    if not lines[number - 1].lstrip().startswith("|"):
        return False
    for index in range(number - 2, -1, -1):
        line = lines[index]
        if not line.lstrip().startswith("|"):
            # The end of this table: the block above is a different one.
            return False
        if PATH_RE.search(line) and _sha_tokens(line):
            return True
    return False


def _declaration(lines: list[str], number: int) -> str | None:
    """The full-SHA declaration nearest above, within the citation's section.

    A declaration is prose or a table row that assigns a revision — as opposed
    to a citation's own pin, the ``@ `sha` `` tail of a wrapped one, or a row
    borrowed from another pin. All three of those are skipped: each belongs to
    one citation, and letting it shadow the page would bind every citation below
    it to a neighbour's revision.
    """
    section = _section_start(lines, number)
    rows = _table_rows(lines)
    marker = MARKER_RE.search(lines[number - 1])
    if not marker and number < len(lines):
        following = lines[number]
        if not PATH_RE.search(following):
            marker = MARKER_RE.search(following)
    marker_name = marker.group(1) if marker else None
    for index in range(number - 2, section - 2, -1):
        line = lines[index]
        row = rows.get(index)
        if row is not None:
            pin, row_marker, general = row
            if general:
                return pin
            if row_marker is not None and row_marker == marker_name:
                return pin
            # A narrower row governs only the marker it names.
            continue
        if (
            PATH_RE.search(line)
            or _supplied_by_at(line)
            or _continuation_of_above(lines, index + 1)
        ):
            continue
        named = FULL_SHA_RE.findall(line)
        if named:
            return named[0]
    return None


def _page_declaration(lines: list[str], rows: dict[int, tuple[str, str | None, bool]], marker: str | None) -> str | None:
    """The pin a page declares in its own `## Pin` section, marker-aware.

    This is where a page states what governs the citations below it. Its first
    pin-bearing row is the page pin; a row naming a marker governs only the
    citations carrying it. A section's own prose pin is checked first, so this is
    the fallback for the sections that name none.
    """
    start = next(
        (index for index, line in enumerate(lines, 1) if PIN_SECTION_RE.match(line)),
        None,
    )
    if start is None:
        return None
    general: str | None = None
    by_marker: dict[str, str] = {}
    for index in range(start + 1, len(lines) + 1):
        line = lines[index - 1]
        if HEADING_RE.match(line):
            break
        row = rows.get(index - 1)
        if row is None:
            if (
                PATH_RE.search(line)
                or _supplied_by_at(line)
                or _continuation_of_above(lines, index)
            ):
                continue
            named = FULL_SHA_RE.findall(line)
            if named and general is None:
                general = named[0]
            continue
        pin, row_marker, is_general = row
        if is_general and general is None:
            general = pin
        elif row_marker is not None:
            by_marker.setdefault(row_marker, pin)
    if marker and marker in by_marker:
        return by_marker[marker]
    return general


def _nearest_sha_above(lines: list[str], number: int) -> str | None:
    """Last resort: the nearest revision-shaped token above, within the section.

    Reached only when nothing else has bound the citation — no revision on its
    own line, none inherited from an earlier citation of the same path, no
    section declaration and no page pin. A source file's comment that names no
    revision is still answerable to the revision the file is being read at, and
    refusing to answer at all would let a carried citation the change re-pinned
    go unchecked — a silent pass on the class this gate exists to catch (#283).

    The token must be revision-shaped (see `_sha_tokens`), so a decimal literal
    cannot stand in for the pin the way `1000000` did in the #297 round-2 false
    pass. Only a full SHA is accepted here: a short run in prose names a commit
    the text is talking *about*, not the revision the citation was taken at.
    """
    for index in range(number - 2, _section_start(lines, number) - 2, -1):
        named = FULL_SHA_RE.findall(lines[index])
        if named:
            return named[0]
    return None


def citation_pin(
    lines: list[str], number: int, introduced: list[str], cited: str | None = None
) -> str | None:
    """The pin a citation sits under, or None when nothing declares one.

    Four things can bind a citation, and the order matters:

    * the revision on its own line — `` `path:line` @ `sha` `` — or, for a
      wrapped one, on the next line, when that next line holds no citation of
      its own;
    * the revision an earlier citation of the *same path* named, which is what a
      bare ``:NNN`` continuation inherits;
    * the nearest full-SHA declaration above it *within its own section*: a
      section's prose pin wins by proximity for the section it governs, and a
      `## Pin` table's general row covers the sections that declare nothing;
    * that table's marker-named row, for the citations carrying its marker.

    A declaration is a full SHA. A shorter run is how prose names a commit it is
    talking *about* ("it landed as `3ec8042483`") and a decimal literal in code
    is not a revision at all, so neither may stand in for the page's pin —
    reading them as declarations is how `1000000` in a test body swallowed the
    declaration 100 lines below it, and how a citation under a wrapped
    continuation was bound to its neighbour's pin (#297 round 2).

    Anything else — a citation under a pin this change did not move, or one that
    cannot be tied to a pin at all — is left alone: a file may carry several pins
    (only 26 name a single one), and a change that moves one stamp is not
    answerable for the citations that still name another.
    """
    own = _sha_tokens(lines[number - 1])
    if not own:
        following = lines[number] if number < len(lines) else ""
        # A continuation line carries the pin and nothing else; a line that also
        # carries a citation is a citation of its own, not this one's tail.
        if not PATH_RE.search(following):
            own = _sha_tokens(following)
    if own:
        return _match_pin(own, introduced)

    if cited is not None:
        # A bare `:NNN` continuation continues the path above it, so it continues
        # that path's pin too; and a citation that spells the same path out again
        # is read at the revision that path was last cited at in its own section.
        inherited = _path_pin_tokens(lines, number, cited)
        if inherited:
            matched = _match_pin(inherited, introduced)
            if matched is not None:
                return matched

    declaring = _declaration(lines, number)
    if declaring is not None:
        return _match_pin([declaring], introduced)

    marker = MARKER_RE.search(lines[number - 1])
    if not marker and number < len(lines):
        following = lines[number]
        if not PATH_RE.search(following):
            marker = MARKER_RE.search(following)
    page = _page_declaration(lines, _table_rows(lines), marker.group(1) if marker else None)
    if page is not None:
        return _match_pin([page], introduced)
    if _borrowed_above(lines, number):
        # A table row above carries a pin of its own with nothing declaring what
        # governs this one, so the table does not say which revision this row is
        # against. Answering with the single pin the change introduced is how a
        # borrowed row's neighbour gets judged against a revision it never named.
        return None
    fallback = _nearest_sha_above(lines, number)
    if fallback is not None:
        return _match_pin([fallback], introduced)
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
    candidates = resolve_candidates(old_sha, cited)
    if not candidates:
        return None, None
    fitting: list[tuple[str, list[str]]] = []
    for candidate in candidates:
        old = blob(old_sha, candidate)
        # A span that does not fit the old pin's own file was never a citation — a
        # `:0` lifted out of a fixture string, a `:NNN` tail the extractor re-pointed
        # at a neighbouring path — and blaming the restamp for it would be noise.
        if old is None or any(first < 1 or last > len(old) for first, last in ranges):
            continue
        fitting.append((candidate, old))
    if not fitting:
        return None, None
    resolved = fitting[0][0] if len(fitting) == 1 else None
    for candidate, old in fitting:
        moved_to = resolve_candidates(new_sha, candidate)
        if not moved_to:
            return "path-missing", resolved or candidate
        new = blob(new_sha, moved_to[0])
        if new is None:
            return "path-missing", resolved or candidate
        if any(first < 1 or last > len(new) for first, last in ranges):
            return "span-out-of-bounds", resolved or candidate
        if all(old[first - 1:last] == new[first - 1:last] for first, last in ranges):
            continue
        # An in-place edit — the construct's first and last lines survive — is still
        # the construct the citation names (#291's own accepted shape). A span whose
        # edges moved is a different construct wearing the same line numbers.
        if all(old[first - 1] == new[first - 1] and old[last - 1] == new[last - 1] for first, last in ranges):
            continue
        return "construct-moved", resolved or candidate
    # A proven shorthand must count as checked, not skipped: `(None, None)` means
    # the tool could not read the citation, which is not what happened here.
    return None, resolved or fitting[0][0]


def written_verdict(new_sha: str, cited: str, ranges: list[tuple[int, int]]) -> tuple[str | None, str | None]:
    """Floor for a citation the change itself wrote: it must fall inside its path.

    Byte truth is not mechanically checkable for a re-derived span — only the
    author knows what it claims — but a span past the end of the file it names is
    wrong whatever it claims, and so is a citation to a path that is not there:
    the change attributed it to the pin it moved to, and at that pin it names
    nothing.
    """
    if not ranges:
        return None, None
    candidates = resolve_candidates(new_sha, cited)
    if not candidates:
        # Attributed to this change's pin, so silence would be a false pass: the
        # documented floor is that the path it names exists at that SHA.
        return "path-missing", cited
    fitting = [
        candidate
        for candidate in candidates
        if (body := blob(new_sha, candidate)) is not None
        and all(1 <= first and last <= len(body) for first, last in ranges)
    ]
    if not fitting:
        # The path form has candidates, but the span fits none of them. That is not
        # evidence that the file disappeared; the honest class is unattributable.
        return "unattributable", candidates[0] if len(candidates) == 1 else cited
    return None, fitting[0] if len(fitting) == 1 else None


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


def citation_bindings(
    text: str, pins: list[str]
) -> tuple[dict[tuple[str, tuple[tuple[int, int], ...], str], int], int]:
    """Map every citation in one revision to the pin it names, with its line.

    The key is the whole binding — cited path, spans, *and* the pin it names —
    because the same span can appear twice in one file under two different pins
    (a page pin row and a row borrowed from another revision). Keyed by span
    alone, the second would be masked by the first and a re-pointed citation
    would go unexamined.

    The pin is the one on the citation's line, a wrapped continuation, or the
    nearest pin *declaration* above. A citation no pin can be tied to is counted
    as ambiguous and left out rather than given an invented owner.
    """
    out: dict[tuple[str, tuple[tuple[int, int], ...], str], int] = {}
    ambiguous = 0
    if not pins:
        return out, ambiguous
    lines = text.splitlines()
    for number, cited, ranges, _safe in located_citations(text, pins[0]):
        if not ranges:
            # A citation with no line span proves nothing about any revision, so it
            # is not a claim this gate can check. Without this, the path heuristic
            # makes tokens like `github.event.pull_request.head.sh` look like
            # citations of a shell script and binds them to whichever SHA is
            # nearby — a GitHub Action pin, whose commit the upstream checkout by
            # definition does not have, so the whole file reports unprovable.
            ambiguous += 1
            continue
        attributed = citation_pin(lines, number, pins, cited)
        if attributed is None:
            ambiguous += 1
            continue
        if not PATH_RE.search(lines[number - 1]):
            # No path on its own line: this is a bare continuation, attributed to
            # the last path above by heuristic. Require the span to fit the file
            # it would be read in at the pin it sits under. `:50000` in a JSON
            # fixture and `:2222` in an IPv6 literal are not line citations, and
            # reporting one is a finding nobody can act on.
            resolved = resolve_path(attributed, cited)
            body = blob(attributed, resolved) if resolved else None
            if not ranges or body is None or any(last > len(body) for _, last in ranges):
                ambiguous += 1
                continue
        out.setdefault((cited, tuple(ranges), attributed), number)
    return out, ambiguous


def check_range(
    base: str, head: str, repo: str = ".", fetch: bool = False
) -> tuple[list[Finding], dict[str, int], dict[str, list[str]]]:
    """Check every citation the range restamped is true at the pin it names.

    Scope is which citation→pin *bindings* the range changed, not which whole-file
    SHA values appeared and disappeared. Set subtraction over a file's pins misses
    a partial restamp: re-pointing one citation from pin A to pin B while another
    citation still names A retires and introduces nothing, and the file would pass
    unexamined. Comparing bindings catches that, and it pins each citation to the
    revision it actually named, so a citation is never accepted merely because
    some other pin the change moved happens to be byte-true for it.

    Returns findings, counters, and the pins this checkout could not supply —
    kept apart from the findings so a caller can say "not provable here" instead
    of "wrong", which are different claims and must not be conflated.
    """
    findings: list[Finding] = []
    counts = {"files": 0, "checked": 0, "skipped": 0, "unmoved": 0, "ambiguous": 0}
    unreachable: dict[str, list[str]] = {}
    for name in changed_files(repo, base, head):
        if is_provenance(name):
            continue
        base_text, head_text = text_at(repo, base, name), text_at(repo, head, name)
        if base_text is None or head_text is None:
            continue
        # Every 40-character SHA in the file is a candidate pin, because a file may
        # name several (26 do today) and attribution has to know all of them. Not
        # every SHA is an upstream pin, though: a workflow YAML pins GitHub Actions
        # by SHA. What makes a SHA this gate's business is that a *citation* is
        # bound to it, so the binds below decide both scope and which revisions
        # must be readable.
        base_pins = sorted(full_pins(base_text))
        head_pins = sorted(full_pins(head_text))
        base_bound, base_ambiguous = citation_bindings(base_text, base_pins)
        head_bound, head_ambiguous = citation_bindings(head_text, head_pins)
        if not base_bound and not head_bound:
            # Nothing in this file is a citation of an upstream revision.
            continue
        if base_bound == head_bound and base_pins == head_pins:
            # The file changed, but neither a stamp nor a citation binding did:
            # prose, a table, a test name. Not this gate's business.
            continue
        counts["files"] += 1
        counts["ambiguous"] += base_ambiguous + head_ambiguous
        # Only revisions a citation is actually judged against have to be
        # readable. Ensuring every SHA in the file would demand a GitHub Action's
        # commit from the upstream checkout, and a file that moves one would be
        # reported unprovable forever.
        used = {pin for _, _, pin in base_bound} | {pin for _, _, pin in head_bound}
        missing: list[str] = []
        for pin in sorted(used):
            try:
                ensure_sha(pin, fetch)
            except RuntimeError:
                missing.append(pin)
        if missing:
            unreachable[name] = missing
            continue
        for (cited, span_key, pin), number in sorted(head_bound.items()):
            ranges = list(span_key)
            old_pin = base_bound.get((cited, span_key, pin))
            if old_pin is not None:
                # The same citation named the same pin before this change: the
                # restamp did not touch this one, so it is not re-judged.
                counts["unmoved"] += 1
                continue
            was_on = [
                pin_before
                for (cited_before, span_before, pin_before) in base_bound
                if cited_before == cited and span_before == span_key
            ]
            if was_on:
                # Re-pointed from a revision it named before: prove it there.
                reason, resolved = carried_verdict(was_on[0], pin, cited, ranges)
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

            The borrowed row is the whole point of carrying `borrowed`: the repo
            has 26 files naming more than one pin, and a change that moves one
            stamp is not answerable for a citation that still names a different
            one. The row *after* it names no pin, so it is governed by the page
            declaration — and must stay that way even though a pin-bearing row
            sits directly above it.
            """
            return (
                "# Fixture surface\n\n## Pin\n\n| Source | Pin | Read via |\n|---|---|---|\n"
                f"| fixture | `{pin}` | `git show <sha>:<path>` |\n\n"
                "Every `path:line` below is against that SHA.\n\n"
                "| Question | Path |\n|---|---|\n"
                "| entry point | `lib/util.ts:2-4` |\n"
                f"| borrowed from another pin | `lib/util.ts:1-2` @ `{borrowed}` |\n"
                "| governed by the page pin, below a borrowed row | `lib/util.ts:4-4` |\n"
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

        # (5) A partial restamp: one citation in the file is re-pointed to a new
        #     pin while another still names the old one. No whole-file pin is
        #     retired or introduced, so a set-subtraction scan sees no move at all
        #     and the file passes unexamined. The re-pointed span is drifted at the
        #     pin it now names, so the gate must report it.
        branch("partial", pinned)
        write("lib/util.ts", "one\nelsewhere\nthree\nfour\n")
        partial_revision = commit("fixture replaces the construct's first line")
        partial_page = (
            "# Fixture surface\n\n## Pin\n\n| Source | Pin | Read via |\n|---|---|---|\n"
            f"| fixture | `{pinned}` | `git show <sha>:<path>` |\n\n"
            "Every `path:line` below is against that SHA.\n\n"
            "| Question | Path |\n|---|---|\n"
            "| entry point | `lib/util.ts:2-4` |\n"
            f"| re-pointed to a newer pin | `lib/util.ts:2-4` @ `{partial_revision}` |\n"
            "| borrowed from another pin | `lib/util.ts:1-2` @ `{borrowed}` |\n"
        )
        write("docs/page.md", partial_page)
        partial = commit("fixture re-points one citation without retiring the page pin")

        # (6) A narrower pin row is ADDED below the page's existing declaration,
        #     and the citation below names no SHA of its own. The page still
        #     assigns it to row 1, so inserting row 2 must not re-point it —
        #     that is the #280/#264 shape, where "nearest declaration above"
        #     turned 23 honest citations into findings. `narrower` carries
        #     different bytes at the cited span, so a misattribution to row 2
        #     shows up as a finding instead of hiding behind identical content.
        branch("narrow", pinned)
        write("lib/util.ts", "one\nelsewhere\nthree\nfour\n")
        narrower = commit("fixture moves the construct at a later revision")
        narrowed_page = (
            "# Fixture surface\n\n## Pin\n\n| Source | Pin | Read via |\n|---|---|---|\n"
            f"| fixture | `{pinned}` | `git show <sha>:<path>` |\n"
            f"| narrower row | `{narrower}` | the citations marked *(narrow)* below were taken there |\n\n"
            "Every `path:line` below is against the SHA its row names.\n\n"
            "| Question | Path |\n|---|---|\n"
            "| governed by row 1, unmarked | `lib/util.ts:2-4` |\n"
        )
        write("docs/page.md", narrowed_page)
        inserted = commit("fixture adds a narrower pin row below the page pin")

        # (7) Junk tokens above a real declaration, in the same file: decimal
        #     literals, a timestamp and a hex blob sit between the page pin row
        #     and the citation, and the declaration wraps onto a second line.
        #     The predicate that decides which token is a revision must not latch
        #     onto `1000000` and swallow the declaration — that is the #297
        #     round-2 false pass, where the nearest "declaration" was a number in
        #     a test body and the citation was then skipped as unattributable
        #     instead of being checked. The span is drifted at the pin it names,
        #     so a swallow shows up as a missing finding rather than a quiet pass.
        branch("junk", pinned)
        write("lib/util.ts", "one\nelsewhere\nthree\nfour\n")
        junk_revision = commit("fixture moves the construct at a later revision")
        junk_page = (
            "# Fixture surface\n\n## Pin\n\n| Source | Pin | Read via |\n|---|---|---|\n"
            f"| fixture | `{pinned}` | `git show <sha>:<path>` |\n\n"
            "Every `path:line` below is against that SHA.\n\n"
            "assertEquals(\"1M\", compactNumber(1000000))\n"
            "val stamp = 1700000000\n"
            "val mask = 0x80123456\n\n"
            "| Question | Path |\n|---|---|\n"
            "| declared on the next line, under numeric literals | `lib/util.ts:2-4` @\n"
            f"`{junk_revision}` |\n"
        )
        write("docs/page.md", junk_page)
        junk = commit("fixture moves the stamp under numeric literals")

        # (8) `:0` inside a string literal is not a line citation. A JSON blob
        #     under a resolved path yields one by the bare-basename heuristic,
        #     and admitting it manufactures a citation nobody wrote — reported
        #     against a path that was never cited, on a range that moves no pin
        #     at all (#270). The range below introduces a real pin, so the only
        #     way this stays quiet is refusing the span where citations are
        #     extracted.
        branch("zero", pinned)
        write(
            "lib/state.py",
            "def pending():\n    return {\"pending\": 0, \"title\": \"Bot Chat\"}\n",
        )
        zero_revision = commit("fixture writes a file with a zero literal")
        write("lib/util.ts", "one\ntwo\nthree\nfour\n")
        zero_page = (
            "# Fixture surface\n\n## Pin\n\n| Source | Pin | Read via |\n|---|---|---|\n"
            f"| fixture | `{pinned}` | `git show <sha>:<path>` |\n\n"
            "Every `path:line` below is against that SHA.\n\n"
            f"| state reader | `lib/state.py:2` @ `{zero_revision}` |\n"
            "    \"\"\"{\"pending\":0,\"title\":\"Bot Chat\"}\"\"\"\n"
        )
        write("docs/page.md", zero_page)
        zero = commit("fixture writes a json literal with a zero under a citation")

        # (9) A section-prose pin BELOW the page-level pin row governs its own
        #     section and wins by proximity. This is #240's own shape: the page's
        #     `## Pin` row names one revision, a later section says its half "is
        #     pinned at <other> — the repo pin — rather than at the authority
        #     above", and the citations below it name a span that fits the section
        #     pin's file but not the page pin's. Binding them to the page row
        #     reports `span-out-of-bounds` against a citation that is exactly
        #     right where the page says it lives.
        branch("section", pinned)
        write("lib/other.ts", "\n".join(f"line {n}" for n in range(1, 61)) + "\n")
        section_revision = commit("fixture grows the file the section cites")
        section_page = (
            "# Fixture surface\n\n## Pin\n\n| Source | Pin | Read via |\n|---|---|---|\n"
            f"| fixture | `{pinned}` | `git show <sha>:<path>` |\n\n"
            "Every `path:line` below is against that SHA.\n\n"
            "## The half that is pinned separately\n\n"
            "That half is pinned at\n"
            f"`{section_revision}` — the repo pin — rather than at the `{pinned[:8]}`\n"
            "authority above.\n\n"
            "| Question | Path |\n|---|---|\n"
            "| governed by the section pin | `lib/other.ts:36-50` |\n"
        )
        write("docs/page.md", section_page)
        section = commit("fixture adds a section-scoped prose pin")

        # (10) A page whose pin section is headed `## Pin and source contract`,
        #      not `## Pin` — five parity pages in this repo write it that way.
        #      The pin row is the page's declaration, and the citation sits in a
        #      later section that declares nothing, so the page pin is the only
        #      thing that can govern it. A heading match anchored on the whole
        #      line reads the page as declaring no pin, the citation is
        #      attributed to nothing, and the drift goes unreported — the #297
        #      review shape. The second row carries another revision so the
        #      single-pin fallback cannot quietly answer with the page pin.
        branch("contract", pinned)
        write("lib/util.ts", "one\nelsewhere\nthree\nfour\n")
        contract_revision = commit("fixture moves the construct at a later revision")
        contract_page = (
            "# Fixture surface\n\n## Pin and source contract\n\n| Source | Pin | Read via |\n|---|---|---|\n"
            f"| fixture | `{contract_revision}` | `git show <sha>:<path>` |\n"
            f"| borrowed from another pin | `{borrowed}` | `git show <sha>:<path>` |\n\n"
            "## A later surface that declares nothing\n\n"
            "| Question | Path |\n|---|---|\n"
            "| governed by the page pin | `lib/util.ts:2-4` |\n"
        )
        write("docs/page.md", contract_page)
        contract = commit("fixture pins a page under a qualified heading")

        def citation_page(pin: str, citation: str | tuple[str, ...] = "") -> str:
            if not citation:
                citations: tuple[str, ...] = ()
            elif isinstance(citation, str):
                citations = (citation,)
            else:
                citations = citation
            rows = "".join(f"| cited construct | `{value}` |\n" for value in citations)
            return (
                "# Shorthand fixture\n\n## Pin\n\n| Source | Pin | Read via |\n|---|---|---|\n"
                f"| fixture | `{pin}` | `git show <sha>:<path>` |\n\n"
                "## Claims\n\n| Question | Path |\n|---|---|---|\n"
                f"{rows}"
            )

        # (11) An elided `.../util.ts` path carries its tail as a suffix, not a
        # literal directory named `...`. Exercise both outcomes against real
        # revisions: an appended line stays true; replacing the first cited line
        # must name the carrier as a construct move rather than be skipped.
        branch("elided-base", pinned)
        write("docs/elided.md", citation_page(pinned, ".../util.ts:2-4"))
        elided_base = commit("fixture writes an elided citation at the old pin")
        branch("elided-true", elided_base)
        write("lib/util.ts", "one\ntwo\nthree\nfour\nfive\n")
        elided_true_pin = commit("fixture appends below an elided span")
        write("docs/elided.md", citation_page(elided_true_pin, ".../util.ts:2-4"))
        elided_true = commit("fixture restamps a byte-true elided citation")
        branch("elided-drift", elided_base)
        write("lib/util.ts", "one\nelsewhere\nthree\nfour\n")
        elided_drift_pin = commit("fixture moves an elided cited construct")
        write("docs/elided.md", citation_page(elided_drift_pin, ".../util.ts:2-4"))
        elided_drift = commit("fixture restamps a drifted elided citation")

        # (12) Two files share `i18n/en.ts`. The cited 2-6 span excludes the
        # short namesake, as a real large translation span does, while retaining
        # the ambiguity that made the old single-path resolver skip the citation.
        branch("basename-source", pinned)
        write("apps/desktop/src/i18n/en.ts", "one\ntwo\nthree\nfour\nfive\nsix\n")
        write("web/src/i18n/en.ts", "one\ntwo\nthree\nfour\n")
        basename_source = commit("fixture adds two i18n basenames")
        write("docs/basename.md", citation_page(basename_source, "i18n/en.ts:2-6"))
        basename_base = commit("fixture writes a basename citation at the old pin")
        branch("basename-true", basename_base)
        write("apps/desktop/src/i18n/en.ts", "one\ntwo\nthree\nfour\nfive\nsix\nseven\n")
        basename_true_pin = commit("fixture appends below the basename span")
        write("docs/basename.md", citation_page(basename_true_pin, "i18n/en.ts:2-6"))
        basename_true = commit("fixture restamps a byte-true basename citation")
        branch("basename-drift", basename_base)
        write("apps/desktop/src/i18n/en.ts", "one\nelsewhere\nthree\nfour\nfive\nsix\n")
        basename_drift_pin = commit("fixture moves a basename cited construct")
        write("docs/basename.md", citation_page(basename_drift_pin, "i18n/en.ts:2-6"))
        basename_drift = commit("fixture restamps a drifted basename citation")

        # (13) A newly written shorthand citation must not claim its target path
        # is missing just because only candidate resolution can identify it.
        branch("written-shorthand", basename_source)
        write("docs/written.md", citation_page(basename_source))
        written_base = commit("fixture writes an empty shorthand page")
        write("lib/util.ts", "one\ntwo\nthree\nfour\nfive\n")
        written_pin = commit("fixture moves the written citation target")
        write(
            "docs/written.md",
            citation_page(written_pin, (".../util.ts:2-4", "i18n/en.ts:2-6")),
        )
        written = commit("fixture writes two shorthand citations")

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
            if counts["unmoved"] < 1:
                raise AssertionError(
                    "the citation borrowed from another pin should have been left on that pin"
                )
            # The row below the borrowed one names no pin of its own, so the page
            # declaration governs it. If a pin-bearing row above it were read as
            # the page's, that citation would be misattributed and skipped.
            if counts["ambiguous"] != 0:
                raise AssertionError(
                    f"a citation under the page pin was left unattributed: {counts}"
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

            # A restamp that re-points one citation while leaving another on the
            # old pin retires and introduces no whole-file SHA: only a
            # binding-level comparison sees it. This is the regression for that.
            findings, counts = gate(pinned, partial, "partial restamp")
            if not findings:
                raise AssertionError(
                    "a partial restamp that left a drifted citation was accepted: "
                    f"{counts}"
                )
            if findings[0].reason != "construct-moved" or findings[0].carrier != "docs/page.md":
                raise AssertionError(f"the partial restamp was mislabelled: {findings[0]}")

            findings, _ = gate(pinned, path_missing, "moved path")
            if not findings or findings[0].reason != "path-missing":
                raise AssertionError(
                    f"a citation whose path is gone at the pin was accepted: {[str(one) for one in findings]}"
                )

            # A span one past the real last line is not a citation of anything.
            # `blob()` must not hand back a phantom trailing element that makes it
            # read as in-bounds.
            lines_of_util = blob(byte_true, "lib/util.ts")
            if lines_of_util is None or len(lines_of_util) != 5:
                raise AssertionError(
                    f"a 5-line file must read as 5 lines, not {lines_of_util and len(lines_of_util)}"
                )

            # The gate must be quiet when a range moves no pin at all: the common
            # case, and the one where a false positive would be worst.
            empty, counts = gate(pinned, pinned, "no move")
            if empty or counts["files"]:
                raise AssertionError(f"a range that moves no pin was not skipped: {[str(one) for one in empty]}")

            # Adding a narrower pin row below the page's declaration must not
            # re-point the citations the page still assigns to row 1. Before the
            # marker rule, "nearest declaration above" made the inserted row the
            # governing pin for every unmarked citation, so this range reported
            # findings against citations whose own lines it never touched — the
            # #280/#264 shape, 42 of 61 measured findings on merged history.
            findings, counts = gate(pinned, inserted, "inserted narrower row")
            if findings:
                raise AssertionError(
                    "an inserted narrower pin row re-pointed citations the page assigns to row 1: "
                    f"{[str(one) for one in findings]}"
                )
            if counts["ambiguous"] != 0:
                raise AssertionError(
                    f"a citation under a two-row Pin table was left unattributed: {counts}"
                )

            # Junk tokens above a real declaration must not shadow it. The
            # citation's revision is on the next line under numeric literals, and
            # the span is drifted at the pin it names: if the declaration
            # predicate latches onto `1000000` the citation is skipped and this
            # comes back empty — the #297 round-2 false pass on the card's own
            # acceptance range, where 3 of 3 true positives went unreported.
            findings, _ = gate(pinned, junk, "junk above a declaration")
            if not findings:
                raise AssertionError(
                    "a declaration under decimal literals was swallowed: the citation was not checked"
                )
            if findings[0].carrier != "docs/page.md" or findings[0].reason != "construct-moved":
                raise AssertionError(f"the junk-shadowed citation was mislabelled: {findings[0]}")

            # `:0` inside a string literal never becomes a line citation. Both
            # verdicts must stay silent: the written path reported it as a stale
            # citation and the carried path swallowed the class entirely.
            findings, counts = gate(pinned, zero, "json zero literal")
            stray = [one for one in findings if any(first < 1 for first, _ in one.ranges)]
            if stray:
                raise AssertionError(
                    f"a `:0` in a string literal became a line citation: {[str(one) for one in stray]}"
                )

            # A section's own prose pin governs the citations in that section,
            # below the page's `## Pin` row. This is #240's shape: the page pin
            # row names one revision, a later section pins its half elsewhere, and
            # the citation's span fits that file only at the section's pin
            # (60 lines there, four at the page pin). Binding the page row here
            # reports `span-out-of-bounds` against a citation that is exactly
            # right where the page says it lives.
            findings, counts = gate(pinned, section, "section prose pin")
            if findings:
                raise AssertionError(
                    "a section-scoped prose pin was overridden by the page pin: "
                    f"{[str(one) for one in findings]}"
                )
            if counts["checked"] < 1:
                raise AssertionError("the section fixture proved nothing; no citation was checked")

            # A page that heads its pin section `## Pin and source contract`
            # declares its pin all the same. The citation below it sits in a later
            # section that declares nothing, so that page pin is what governs it;
            # matching the heading on the whole line leaves the citation
            # unattributed and its drift unreported — the #297 review finding, and
            # the reason five pages of this repo must be recognized here.
            findings, _ = gate(pinned, contract, "qualified pin heading")
            if not findings:
                raise AssertionError(
                    "a citation under a `## Pin and source contract` heading was skipped as unattributable"
                )
            if findings[0].carrier != "docs/page.md" or findings[0].reason != "construct-moved":
                raise AssertionError(f"the qualified-heading citation was mislabelled: {findings[0]}")

            # Shorthand paths are judged by candidate sets rather than guessed
            # tree order. The byte-true controls must be checked, not skipped;
            # the moved constructs must fail and identify their carrier.
            findings, counts = gate(elided_base, elided_true, "elided byte-true")
            if findings or counts["checked"] < 1:
                raise AssertionError(f"a byte-true elided citation was not checked: {findings}, {counts}")
            findings, _ = gate(elided_base, elided_drift, "elided moved")
            if not findings or findings[0].reason != "construct-moved":
                raise AssertionError(f"a moved elided citation was skipped or mislabelled: {findings}")
            findings, counts = gate(basename_base, basename_true, "basename byte-true")
            if findings or counts["checked"] < 1:
                raise AssertionError(f"a byte-true basename citation was not checked: {findings}, {counts}")
            findings, _ = gate(basename_base, basename_drift, "basename moved")
            if not findings or findings[0].reason != "construct-moved":
                raise AssertionError(f"a moved basename citation was skipped or mislabelled: {findings}")
            findings, counts = gate(written_base, written, "written shorthand")
            if findings or counts["checked"] < 1:
                raise AssertionError(f"a written shorthand citation was rejected: {findings}, {counts}")

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
        print("ok    pin-citation gate fails a drifted span, resolves elided and basename shorthand, and passes byte-true moves")
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
        except (RuntimeError, OSError, subprocess.SubprocessError) as error:
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
            print(f"  {len(findings)} citation(s) in {counts['files']} changed file(s) are not true at the SHA they name.")
            print("  fix: re-derive each cited path and span at the new pin, or keep the citation at the pin it belongs to;")
            print("       docs/workflows/review-desktop-parity.md, `### Moving a pin`, says how to read upstream at a SHA.")
            raise SystemExit(1)
        if unreachable:
            # Exit 2 is "this checkout could not prove it", which is a different
            # claim from "the citations are wrong". A caller that cannot fetch
            # (the local read-only reference checkout) skips loudly on 2; CI
            # passes --fetch and treats 2 as a failure, because it should not
            # have happened there.
            print(f"  {len(unreachable)} changed file(s) were not checked: their pins are not in this checkout.")
            print("  fix: run with --fetch, or point --upstream at a checkout that has those revisions.")
            raise SystemExit(2)
        print(
            f"ok    {counts['files']} changed file(s) with citation activity: "
            f"{counts['checked']} citation(s) true at the SHA they name, "
            f"{counts['unmoved']} left on their own pin, {counts['skipped']} not provable, "
            f"{counts['ambiguous']} unattributable"
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
