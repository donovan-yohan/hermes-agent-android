#!/usr/bin/env python3
"""Every parity page must name a visual report and classify its divergences.

Desktop is the spec. A parity page that only argues in prose can claim anything,
because nothing in it can fail. This gate makes two things fail instead:

  * `## Visual report` must name a rendered side-by-side report and the commit
    it was built at, or an explicit `pending: #<issue>` so the gap is visible
    rather than silent.
  * `## Divergences` must classify every row as exactly one of
    mobile-adaptation / drift / omission, and each class carries its own
    obligation: an adaptation states a real mobile reason, drift names the
    issue that will close it, and an omission is either a declared non-goal or
    owes a disabled "coming soon" pill.

It is deliberately a structure gate, not a judge of whether the pixels match.
Reviewing the report is `.chalk/skills/review-desktop-parity/SKILL.md`.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

PARITY_DIR = "docs/parity"
VISUAL_SECTION = "Visual report"
DIVERGENCE_SECTION = "Divergences"
CLASSES = ("mobile-adaptation", "drift", "omission")
HEADERS = ("desktop", "class", "android", "evidence")
NO_DIVERGENCES = "None."

HEADING = re.compile(r"^##\s+(.+?)\s*$")
FIELD = re.compile(r"^\s*[-*]\s*([A-Za-z][A-Za-z -]*?)\s*:\s*(\S.*?)\s*$")
COMMIT = re.compile(r"(?:^|/)([0-9a-f]{7,40})$")
ISSUE = re.compile(r"#\d+")
# An omission has to say which kind it is, and say it *first*: this is matched
# against the start of the Evidence cell, so "this is not a non-goal because ..."
# is refused rather than passing on the substring it negates. `non-goal:` carries
# a platform judgement and must be followed by the reason for it; the other four
# name an owner instead.
OMISSION_MARKER = re.compile(
    r"^(?:"
    r"non-goal:\s*\S"          # this platform will never have it, and why
    r"|coming soon\b"          # the disabled pill already ships
    r"|pill-owed:\s*#\d+"      # a control or mode that owes the pill
    r"|out-of-scope:\s*#\d+"   # that issue deliberately excluded it
    r"|deferred:\s*#\d+"       # a detail that is not a control
    r")",
    re.IGNORECASE,
)
FENCE = re.compile(r"^\s*(?:```|~~~)")
CELL = re.compile(r"(?<!\\)\|")
SEPARATOR = re.compile(r"^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$")
EMPHASIS = re.compile(r"[`*_]")

# A reason that explains nothing. The port workflow already rejects these in
# prose; here they fail the build.
NON_REASON = re.compile(
    r"^\s*$|^n/?a\.?$|not implemented|not yet|todo|tbd|material does this|"
    r"mobile should be simpler|by default",
    re.IGNORECASE,
)


def sections(text: str) -> dict[str, list[str]]:
    """Split on level-2 headings; deeper headings stay inside their parent."""
    found: dict[str, list[str]] = {}
    current: str | None = None
    fenced = False
    for line in text.splitlines():
        # A fenced block is an example of the format, not a claim in it. The
        # workflow doc shows a `pending:` line and a table inside fences; a page
        # that quotes either must not be credited with having one.
        if FENCE.match(line):
            fenced = not fenced
            continue
        if fenced:
            continue
        heading = HEADING.match(line)
        if heading:
            current = heading.group(1)
            found.setdefault(current, [])
            continue
        if current is not None:
            found[current].append(line)
    return found


def fields(body: list[str]) -> dict[str, str]:
    found: dict[str, str] = {}
    for line in body:
        match = FIELD.match(line)
        if match:
            found[match.group(1).strip().lower()] = match.group(2).strip()
    return found


def cells(line: str) -> list[str]:
    stripped = line.strip()
    if stripped.startswith("|"):
        stripped = stripped[1:]
    if stripped.endswith("|") and not stripped.endswith("\\|"):
        stripped = stripped[:-1]
    return [cell.replace("\\|", "|").strip() for cell in CELL.split(stripped)]


def check_visual_report(body: list[str] | None, problems: list[str]) -> None:
    if body is None:
        problems.append(
            f"has no `## {VISUAL_SECTION}` section; add one naming the rendered "
            f"side-by-side report, or `- pending: #<issue>`"
        )
        return

    named = fields(body)
    pending = named.get("pending")
    report = named.get("report")
    commit = named.get("commit")

    if pending and (report or commit):
        problems.append(
            f"`## {VISUAL_SECTION}` claims both a report and `pending:`; a page "
            f"either has a report or owes one"
        )
        return
    if pending:
        if not ISSUE.search(pending):
            problems.append(
                f"`## {VISUAL_SECTION}` has `pending: {pending}` with no issue "
                f"number; write `pending: #<issue>` so the gap has an owner"
            )
        return
    if not report and not commit:
        problems.append(
            f"`## {VISUAL_SECTION}` names neither a report nor a pending issue; "
            f"add `- report: <path>` and `- commit: <sha>`, or `- pending: #<issue>`"
        )
        return
    if not report:
        problems.append(f"`## {VISUAL_SECTION}` names a commit but no `- report: <path>`")
    if not commit:
        problems.append(f"`## {VISUAL_SECTION}` names a report but no `- commit: <sha>`")
    elif not COMMIT.search(commit):
        problems.append(
            f"`## {VISUAL_SECTION}` has `commit: {commit}`, which is not a commit "
            f"SHA; a report built at an unnamed revision proves nothing"
        )


def check_divergences(body: list[str] | None, problems: list[str]) -> None:
    if body is None:
        problems.append(
            f"has no `## {DIVERGENCE_SECTION}` section; add the classified table, "
            f"or the single line `{NO_DIVERGENCES}`"
        )
        return

    content = [line for line in body if line.strip()]
    if [line.strip() for line in content] == [NO_DIVERGENCES]:
        return

    table = [line for line in content if "|" in line and not SEPARATOR.match(line)]
    if not table:
        problems.append(
            f"`## {DIVERGENCE_SECTION}` has no table and does not say "
            f"`{NO_DIVERGENCES}`; prose cannot be checked"
        )
        return

    header = [EMPHASIS.sub("", cell).strip().lower() for cell in cells(table[0])]
    if tuple(header) != HEADERS:
        problems.append(
            f"`## {DIVERGENCE_SECTION}` header is {header}; it must be exactly "
            f"| {' | '.join(name.capitalize() for name in HEADERS)} |"
        )
        return

    rows = table[1:]
    if not rows:
        problems.append(
            f"`## {DIVERGENCE_SECTION}` table has a header and no rows; say "
            f"`{NO_DIVERGENCES}` if there is genuinely nothing"
        )
        return

    for index, line in enumerate(rows, start=1):
        row = cells(line)
        if len(row) != len(HEADERS):
            problems.append(
                f"`## {DIVERGENCE_SECTION}` row {index} has {len(row)} cells, not "
                f"{len(HEADERS)} (escape a literal pipe as `\\|`)"
            )
            continue
        desktop, klass, android, evidence = row
        klass = EMPHASIS.sub("", klass).strip().lower()

        if klass not in CLASSES:
            problems.append(
                f"`## {DIVERGENCE_SECTION}` row {index} ({desktop or 'unnamed'!r}) is "
                f"classified {klass or 'nothing'!r}; use one of {', '.join(CLASSES)}"
            )
            continue
        if not desktop:
            problems.append(
                f"`## {DIVERGENCE_SECTION}` row {index} names no Desktop element"
            )
        if not android:
            problems.append(
                f"`## {DIVERGENCE_SECTION}` row {index} ({desktop!r}) says nothing "
                f"about Android"
            )

        if klass == "mobile-adaptation" and NON_REASON.search(evidence):
            problems.append(
                f"`## {DIVERGENCE_SECTION}` row {index} ({desktop!r}) is a "
                f"mobile-adaptation with no reason: {evidence!r}. Valid reasons are "
                f"touch mechanics, viewport space, accessibility, or an explicit "
                f"mobile priority"
            )
        elif klass == "drift" and not ISSUE.search(evidence):
            problems.append(
                f"`## {DIVERGENCE_SECTION}` row {index} ({desktop!r}) is drift with "
                f"no issue number; drift is a finding and needs an owner"
            )
        elif klass == "omission" and not OMISSION_MARKER.match(evidence):
            problems.append(
                f"`## {DIVERGENCE_SECTION}` row {index} ({desktop!r}) omits part of "
                f"Desktop without saying which kind of omission it is. The Evidence "
                f"cell must *begin* with one of: `non-goal: <reason>` (this platform "
                f"will never have it), `coming soon` (the disabled pill ships today), "
                f"`pill-owed: #<issue>` (a control or mode that owes one), "
                f"`out-of-scope: #<issue>` (that issue deliberately excluded it), or "
                f"`deferred: #<issue>` (a detail that is not a control). "
                f"Got: {evidence[:60]!r}"
            )


def check(text: str) -> list[str]:
    found = sections(text)
    problems: list[str] = []
    check_visual_report(found.get(VISUAL_SECTION), problems)
    check_divergences(found.get(DIVERGENCE_SECTION), problems)
    return problems


def pages(root: pathlib.Path) -> list[pathlib.Path]:
    directory = root / PARITY_DIR
    if not directory.is_dir():
        raise SystemExit(f"FAIL  {PARITY_DIR} is missing; the parity-evidence gate lost its subject.")
    return sorted(directory.glob("*.md"))


REPORT = """## Visual report

- report: build/visual-parity/a-surface/report.html
- commit: 86d97421f0ac0f6b0d2b0a2f4a6b8c0d1e2f3a4b
"""

ADAPTATION = (
    "| Hover-revealed kebab | mobile-adaptation | Always visible in a 48dp target"
    " | Touch has no hover; weight and placement unchanged |"
)
DRIFT = '| No-results text has `role="status"` | drift | Plain `Text` | Not a live region here; #85 |'
NON_GOAL = "| Local terminal | omission | Absent | non-goal: this platform has no terminal |"
OUT_OF_SCOPE = "| Syntax highlighting | omission | Absent | out-of-scope: #71 |"
PILL_OWED_ROW = "| Rename | omission | Absent | pill-owed: #101 |"
ROWS = (ADAPTATION, DRIFT, NON_GOAL, OUT_OF_SCOPE, PILL_OWED_ROW)


def as_table(*rows: str, header: str = "| Desktop | Class | Android | Evidence |") -> str:
    return "\n".join((header, "|---|---|---|---|", *rows))


def page(report: str = REPORT, divergences: str = as_table(*ROWS)) -> str:
    return f"# A surface\n\n{report}\n## {DIVERGENCE_SECTION}\n\n{divergences}\n"


def swap(row: str, replacement: str) -> str:
    """The good page with one row replaced, so a fixture edit cannot rot silently."""
    if row not in ROWS:
        raise AssertionError(f"{row!r} is not one of the fixture rows")
    return page(divergences=as_table(*(replacement if each == row else each for each in ROWS)))


def self_test() -> None:
    def accepts(name: str, text: str) -> None:
        problems = check(text)
        if problems:
            raise AssertionError(f"{name}: a valid parity page was rejected: {problems}")

    def rejects(name: str, text: str, needle: str) -> None:
        problems = check(text)
        if not any(needle in problem for problem in problems):
            raise AssertionError(f"{name}: expected a problem mentioning {needle!r}, got {problems}")

    accepts("well-formed page", page())
    accepts("explicitly pending report", page(report="## Visual report\n\n- pending: #43\n"))
    accepts("no divergences", page(divergences=NO_DIVERGENCES))
    accepts("fenced example is not a claim", page() + "\n```\n- pending: #1\n```\n")
    accepts(
        "omitted non-control with a named owner",
        page(divergences=as_table(*ROWS, "| `summary` on a row | omission | Not projected | deferred: #56 |")),
    )

    rejects(
        "missing visual report",
        page().replace(f"## {VISUAL_SECTION}", "## Notes"),
        f"no `## {VISUAL_SECTION}` section",
    )
    rejects(
        "missing divergences",
        page().replace(f"## {DIVERGENCE_SECTION}", "## Deviation ledger"),
        f"no `## {DIVERGENCE_SECTION}` section",
    )
    rejects(
        "report with no commit",
        page(report="## Visual report\n\n- report: build/visual-parity/a-surface/report.html\n"),
        "no `- commit:",
    )
    rejects(
        "commit that is not a SHA",
        page(report="## Visual report\n\n- report: a/report.html\n- commit: latest\n"),
        "not a commit",
    )
    rejects(
        "pending with no issue",
        page(report="## Visual report\n\n- pending: device QA\n"),
        "no issue number",
    )
    rejects(
        "report and pending together",
        page(report=REPORT + "- pending: #43\n"),
        "either has a report or owes one",
    )
    rejects("prose instead of a table", page(divergences="Everything matches."), "has no table")
    rejects("header and no rows", page(divergences=as_table()), "header and no rows")
    rejects("wrong header", page(divergences=as_table(*ROWS, header="| Desktop | Android | Why |")), "header is")
    rejects(
        "unclassified row",
        swap(NON_GOAL, "| Local terminal | deviation | Absent | non-goal: no terminal here |"),
        "is classified 'deviation'",
    )
    rejects(
        "adaptation with no reason",
        swap(ADAPTATION, "| Hover-revealed kebab | mobile-adaptation | Always visible | Material does this by default |"),
        "with no reason",
    )
    rejects("drift with no owner", swap(DRIFT, "| No-results text | drift | Plain `Text` | Not a live region |"), "no issue number")
    rejects("silent omission", swap(PILL_OWED_ROW, "| Rename | omission | Absent | Later |"), "which kind of omission")
    rejects("ragged row", swap(PILL_OWED_ROW, "| Rename | omission | Absent |"), "cells, not 4")
    rejects(
        "negated marker",
        swap(NON_GOAL, "| Delete button | omission | Absent | this is not a non-goal, we just have not built it |"),
        "must *begin* with one of",
    )
    rejects(
        "bare non-goal with no reason",
        swap(NON_GOAL, "| Local terminal | omission | Absent | non-goal |"),
        "must *begin* with one of",
    )
    rejects(
        "non-goal colon with no reason",
        swap(NON_GOAL, "| Local terminal | omission | Absent | non-goal: |"),
        "must *begin* with one of",
    )
    rejects(
        "marker buried mid-cell",
        swap(PILL_OWED_ROW, "| Rename | omission | Absent | we will get to it, pill-owed: #101 |"),
        "must *begin* with one of",
    )
    rejects(
        "non-reason buried mid-cell",
        swap(ADAPTATION, "| Hover-revealed kebab | mobile-adaptation | Always visible | Always visible because Material does this by default |"),
        "with no reason",
    )
    rejects(
        "table inside a fence is not a table",
        page(divergences="```\n" + as_table(*ROWS) + "\n```"),
        "has no table",
    )
    rejects(
        "pending inside a fence is not a report",
        page(report="## Visual report\n\n```\n- pending: #43\n```\n"),
        "names neither a report nor a pending issue",
    )

    with tempfile.TemporaryDirectory() as temporary:
        root = pathlib.Path(temporary)
        parity = root / PARITY_DIR
        parity.mkdir(parents=True)
        (parity / "a-surface.md").write_text(page(), encoding="utf-8")
        (parity / "capture-matrix.json").write_text("{}\n", encoding="utf-8")
        discovered = pages(root)
        if [found.name for found in discovered] != ["a-surface.md"]:
            raise AssertionError(f"page discovery took something other than the parity pages: {discovered}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("paths", nargs="*")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        print("ok    parity-evidence gate catches a missing report and an unclassified row")

    root = pathlib.Path(__file__).resolve().parents[1]
    paths = [pathlib.Path(path) for path in args.paths] or pages(root)
    if not paths:
        print(f"FAIL  {PARITY_DIR} holds no parity page; the gate has nothing to check.")
        return 1

    failed = False
    for path in paths:
        actual = path if path.is_absolute() else root / path
        relative = actual.relative_to(root) if actual.is_relative_to(root) else actual
        for problem in check(actual.read_text(encoding="utf-8")):
            failed = True
            print(f"FAIL  {relative}: {problem}")

    if failed:
        print("  fix: see docs/workflows/review-desktop-parity.md; every parity page names a")
        print("       visual report (or a pending issue) and classifies each divergence as")
        print(f"       {' / '.join(CLASSES)}.")
        return 1
    print(f"ok    {len(paths)} parity pages name a visual report and classify every divergence")
    return 0


if __name__ == "__main__":
    sys.exit(main())
