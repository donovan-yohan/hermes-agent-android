#!/usr/bin/env python3
"""Diff the Hermes Desktop theme registry against this repo's Android port.

Compares upstream ``apps/desktop/src/themes/presets.ts`` (identity, typography,
registry) and ``apps/shared/src/theme-presets.ts`` (the palette literals, which
upstream extracted into the shared package in September 2026) with
``app/src/main/kotlin/com/hermesagent/mobile/ui/theme/BuiltinThemes.kt`` and the
offline ledger at
``app/src/test/kotlin/com/hermesagent/mobile/ui/theme/DesktopThemeLedger.kt``.

Checked: preset names, registry order, labels, descriptions, the default skin,
and which presets ship a hand-tuned ``darkColors`` block. Colour *values* are
deliberately not compared — Desktop writes them as ``color-mix`` expressions and
Android ports the expressions, so a value diff would be noise. The token
contract itself is asserted offline by ``ThemeParityTest``.

Exit codes
----------
0  parity
1  drift (every difference is printed)
2  the upstream checkout is missing, or a file could not be parsed

Usage
-----
    python3 .chalk/skills/sync-hermes-desktop-themes/scripts/check-theme-parity.py \
      --upstream ~/.hermes/hermes-agent
"""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys
from dataclasses import dataclass, field
from typing import NoReturn

PRESETS_REL = "apps/desktop/src/themes/presets.ts"
PALETTES_REL = "apps/shared/src/theme-presets.ts"
ANDROID_REL = "app/src/main/kotlin/com/hermesagent/mobile/ui/theme/BuiltinThemes.kt"
LEDGER_REL = "app/src/test/kotlin/com/hermesagent/mobile/ui/theme/DesktopThemeLedger.kt"


@dataclass
class Preset:
    name: str
    label: str
    description: str
    has_dark: bool


@dataclass
class Report:
    problems: list[str] = field(default_factory=list)

    def check(self, ok: bool, message: str) -> None:
        if not ok:
            self.problems.append(message)


def die(message: str) -> NoReturn:
    print(f"error: {message}", file=sys.stderr)
    sys.exit(2)


# ── upstream ────────────────────────────────────────────────────────────────

def parse_shared_palettes(source: str) -> dict[str, bool]:
    """`has darkColors` per preset key from apps/shared/src/theme-presets.ts.

    Upstream moved the palette literals out of presets.ts into the shared
    package (September 2026), leaving each preset a
    `...THEME_PRESET_PALETTES.<key>` spread. Anchored scanning here too: a shape
    change must fail loudly rather than quietly report every theme as
    synthesised.
    """
    lines = source.split("\n")
    try:
        start = next(
            i for i, line in enumerate(lines)
            if line.startswith("export const THEME_PRESET_PALETTES")
        )
    except StopIteration:
        die(f"could not find THEME_PRESET_PALETTES in {PALETTES_REL}")

    out: dict[str, bool] = {}
    pattern = re.compile(r"^  (?:'([\w-]+)'|(\w+)): \{\s*$")
    index = start + 1
    while index < len(lines):
        line = lines[index]
        if line.startswith("}"):
            break
        match = pattern.match(line)
        if not match:
            index += 1
            continue
        key = match.group(1) or match.group(2)
        end = index
        while end + 1 < len(lines) and lines[end + 1] not in ("  },", "  }"):
            end += 1
        out[key] = bool(
            re.search(r"^\s{4}darkColors:", "\n".join(lines[index:end + 1]), re.MULTILINE)
        )
        index = end + 1

    if not out:
        die(f"could not parse any preset palette in {PALETTES_REL}")
    return out


def parse_desktop(source: str, palettes: dict[str, bool]) -> tuple[list[Preset], str]:
    """Pull the registry out of presets.ts without a TypeScript parser.

    The file is a flat list of object literals with one shape, so anchored
    regexes are honest here — and a shape change should make this script fail
    loudly rather than quietly half-parse.
    """
    blocks = re.findall(
        r"export const (\w+Theme): DesktopTheme = \{(.*?)\n\}\n",
        source,
        re.DOTALL,
    )
    if not blocks:
        die(f"could not find any `export const …Theme: DesktopTheme` in {PRESETS_REL}")

    by_symbol: dict[str, Preset] = {}
    for symbol, body in blocks:
        name = _field(body, "name", symbol)
        spread = re.search(r"\.\.\.THEME_PRESET_PALETTES(?:\.(\w+)|\[\s*'([\w-]+)'\s*\])", body)
        if spread:
            key = spread.group(1) or spread.group(2)
            if key not in palettes:
                die(
                    f"preset `{symbol}` spreads THEME_PRESET_PALETTES.{key}, "
                    f"which {PALETTES_REL} does not define"
                )
            has_dark = palettes[key]
        else:
            has_dark = bool(re.search(r"^\s{2}darkColors:", body, re.MULTILINE))
        by_symbol[symbol] = Preset(
            name=name,
            label=_field(body, "label", symbol),
            description=_field(body, "description", symbol),
            has_dark=has_dark,
        )

    registry = re.search(
        r"export const BUILTIN_THEMES: Record<string, DesktopTheme> = \{(.*?)\}",
        source,
        re.DOTALL,
    )
    if not registry:
        die("could not find BUILTIN_THEMES in presets.ts")

    ordered: list[Preset] = []
    for quoted_key, bare_key, symbol in re.findall(
        r"(?:'([^']+)'|(\w+)):\s*(\w+Theme)", registry.group(1)
    ):
        key = quoted_key or bare_key
        if symbol not in by_symbol:
            die(f"BUILTIN_THEMES references unknown symbol `{symbol}`")
        preset = by_symbol[symbol]
        if preset.name != key:
            die(f"registry key `{key}` does not match preset name `{preset.name}`")
        ordered.append(preset)

    default = re.search(r"DEFAULT_SKIN_NAME = '([^']+)'", source)
    if not default:
        die("could not find DEFAULT_SKIN_NAME in presets.ts")

    return ordered, default.group(1)


def _field(body: str, key: str, symbol: str) -> str:
    match = re.search(rf"^\s*{key}: '([^']*)'", body, re.MULTILINE)
    if not match:
        die(f"preset `{symbol}` has no `{key}` field")
    return match.group(1)


# ── android ─────────────────────────────────────────────────────────────────

def parse_android(source: str) -> tuple[list[Preset], str]:
    by_symbol: dict[str, Preset] = {}
    for symbol, body in re.findall(
        r"val (\w+) = HermesThemePreset\((.*?)\n    \)\n", source, re.DOTALL
    ):
        by_symbol[symbol] = Preset(
            name=_kt_field(body, "name", symbol),
            label=_kt_field(body, "label", symbol),
            description=_kt_field(body, "description", symbol),
            has_dark=bool(re.search(r"^\s*darkColors = ", body, re.MULTILINE)),
        )

    if not by_symbol:
        die(f"could not find any `val … = HermesThemePreset(` in {ANDROID_REL}")

    registry = re.search(r"val ALL: List<HermesThemePreset> = listOf\(([^)]*)\)", source)
    if not registry:
        die("could not find `val ALL` in BuiltinThemes.kt")

    ordered = []
    for symbol in [s.strip() for s in registry.group(1).split(",") if s.strip()]:
        if symbol not in by_symbol:
            die(f"ALL references unknown preset `{symbol}`")
        ordered.append(by_symbol[symbol])

    default = re.search(r'DEFAULT_NAME: String = "([^"]+)"', source)
    if not default:
        die("could not find DEFAULT_NAME in BuiltinThemes.kt")

    return ordered, default.group(1)


def _kt_field(body: str, key: str, symbol: str) -> str:
    match = re.search(rf'^\s*{key} = "([^"]*)"', body, re.MULTILINE)
    if not match:
        die(f"Android preset `{symbol}` has no `{key}`")
    return match.group(1)


def parse_ledger(source: str) -> tuple[str, list[Preset]]:
    sha = re.search(r'PINNED_SHA = "([0-9a-f]{40})"', source)
    if not sha:
        die("DesktopThemeLedger has no 40-character PINNED_SHA")

    entries = [
        Preset(name=name, label=label, description=description, has_dark=has_dark == "true")
        for name, label, description, has_dark in re.findall(
            r'Entry\("([^"]+)", "([^"]+)", "([^"]+)", (true|false), "[^"]*"\)', source
        )
    ]
    if not entries:
        die("DesktopThemeLedger has no Entry rows")
    return sha.group(1), entries


# ── main ────────────────────────────────────────────────────────────────────

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--upstream",
        default=str(pathlib.Path.home() / ".hermes" / "hermes-agent"),
        help="path to a read-only hermes-agent checkout",
    )
    parser.add_argument(
        "--repo",
        default=str(pathlib.Path(__file__).resolve().parents[4]),
        help="path to this repository (default: inferred from this script)",
    )
    args = parser.parse_args()

    upstream = pathlib.Path(args.upstream)
    repo = pathlib.Path(args.repo)

    presets_file = upstream / PRESETS_REL
    if not presets_file.is_file():
        die(f"no upstream registry at {presets_file}. Pass --upstream.")
    palettes_file = upstream / PALETTES_REL
    if not palettes_file.is_file():
        die(f"no upstream palettes at {palettes_file}. Pass --upstream.")

    android_file = repo / ANDROID_REL
    ledger_file = repo / LEDGER_REL
    for path in (android_file, ledger_file):
        if not path.is_file():
            die(f"missing {path}. Pass --repo.")

    desktop, desktop_default = parse_desktop(
        presets_file.read_text(), parse_shared_palettes(palettes_file.read_text())
    )
    android, android_default = parse_android(android_file.read_text())
    ledger_sha, ledger = parse_ledger(ledger_file.read_text())

    head = _git_head(upstream)
    print(f"upstream {upstream}")
    print(f"  HEAD          {head or 'unknown'}")
    print(f"  ledger pins   {ledger_sha}")
    if head and head != ledger_sha:
        print("  note: upstream HEAD differs from the pinned SHA; this diff is against HEAD.")

    report = Report()
    _compare("Android registry", desktop, android, report)
    _compare("offline ledger", desktop, ledger, report)
    report.check(
        desktop_default == android_default,
        f"default skin: Desktop `{desktop_default}` vs Android `{android_default}`",
    )

    if report.problems:
        print(f"\nDRIFT ({len(report.problems)}):")
        for problem in report.problems:
            print(f"  - {problem}")
        print("\nFix BuiltinThemes.kt and DesktopThemeLedger.kt together, then rerun.")
        return 1

    names = ", ".join(p.name for p in desktop)
    print(f"\nparity: {len(desktop)} presets in the same order ({names})")
    return 0


def _compare(what: str, desktop: list[Preset], other: list[Preset], report: Report) -> None:
    desktop_names = [p.name for p in desktop]
    other_names = [p.name for p in other]

    for missing in [n for n in desktop_names if n not in other_names]:
        report.check(False, f"{what}: Desktop ships `{missing}` and it is absent")
    for extra in [n for n in other_names if n not in desktop_names]:
        report.check(False, f"{what}: `{extra}` exists but Desktop does not ship it")

    if desktop_names != other_names and set(desktop_names) == set(other_names):
        report.check(False, f"{what}: order differs — Desktop {desktop_names}, got {other_names}")

    by_name = {p.name: p for p in other}
    for preset in desktop:
        mine = by_name.get(preset.name)
        if mine is None:
            continue
        report.check(mine.label == preset.label, f"{what}: `{preset.name}` label `{mine.label}` != `{preset.label}`")
        report.check(
            mine.description == preset.description,
            f"{what}: `{preset.name}` description `{mine.description}` != `{preset.description}`",
        )
        report.check(
            mine.has_dark == preset.has_dark,
            f"{what}: `{preset.name}` hand-tuned dark palette {mine.has_dark}, Desktop {preset.has_dark}",
        )


def _git_head(path: pathlib.Path) -> str | None:
    try:
        return subprocess.run(
            ["git", "-C", str(path), "rev-parse", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None


if __name__ == "__main__":
    sys.exit(main())
