#!/usr/bin/env python3
"""Reject essay-length primary Kotlin UI literals.

This is deliberately a length gate, not a tone or keyword oracle. It joins
normal/triple-quoted literals separated only by Kotlin `+`, so splitting a
paragraph across source lines does not bypass the threshold. Review still owns
meaning, Desktop terminology, screenshots, and semantics.
"""

from __future__ import annotations

import argparse
import dataclasses
import pathlib
import re
import sys
import tempfile

MAX_WORDS = 36
MAX_CHARS = 240
ALLOW = re.compile(r"//\s*product-copy-allow:\s*(\S.{11,})\s*$")
WORDS = re.compile(r"[\w]+(?:[-'][\w]+)*", re.UNICODE)

# These data-layer sources own strings that a production UI renders verbatim.
# Keep the list explicit: scanning every data/protocol/test literal would turn
# this focused product-copy gate into a source-code prose-length gate.
RENDERED_DATA_SOURCES = (
    "app/src/main/kotlin/com/hermesagent/mobile/data/connections/ConnectionRegistry.kt",
    "app/src/main/kotlin/com/hermesagent/mobile/data/gateway/GatewayConnection.kt",
    "app/src/main/kotlin/com/hermesagent/mobile/data/gateway/GatewayRestClient.kt",
    "app/src/main/kotlin/com/hermesagent/mobile/data/gateway/GatewayRpc.kt",
    "app/src/main/kotlin/com/hermesagent/mobile/data/gateway/GatewaySignInBrowser.kt",
    "app/src/main/kotlin/com/hermesagent/mobile/data/gateway/GatewaySessionRepository.kt",
    "app/src/main/kotlin/com/hermesagent/mobile/data/gateway/LocalGateway.kt",
    "app/src/main/kotlin/com/hermesagent/mobile/data/gateway/RemoteGateway.kt",
    "app/src/main/kotlin/com/hermesagent/mobile/data/gateway/RemoteLifecycle.kt",
    "app/src/main/kotlin/com/hermesagent/mobile/data/notifications/NotificationCopy.kt",
    "app/src/main/kotlin/com/hermesagent/mobile/data/relay/RelayAvailabilityController.kt",
    "app/src/main/kotlin/com/hermesagent/mobile/data/relay/RelayPluginRepository.kt",
    "app/src/main/kotlin/com/hermesagent/mobile/data/ssh/KeyImport.kt",
    "app/src/main/kotlin/com/hermesagent/mobile/data/ssh/SshDestination.kt",
    "app/src/main/kotlin/com/hermesagent/mobile/data/ssh/SshjProbe.kt",
    "app/src/main/kotlin/com/hermesagent/mobile/data/ssh/SshProbe.kt",
    "app/src/main/kotlin/com/hermesagent/mobile/data/ssh/SshSessionOpener.kt",
    "app/src/main/kotlin/com/hermesagent/mobile/data/voice/SpeechText.kt",
    "app/src/main/kotlin/com/hermesagent/mobile/data/voice/VoicePolicy.kt",
)


@dataclasses.dataclass(frozen=True)
class Literal:
    start: int
    end: int
    line: int
    value: str


@dataclasses.dataclass(frozen=True)
class Violation:
    line: int
    words: int
    chars: int
    sample: str


def _literals(source: str) -> list[Literal]:
    """Small Kotlin lexer: strings out, comments ignored, positions retained."""
    found: list[Literal] = []
    i = 0
    line = 1
    size = len(source)
    while i < size:
        if source.startswith("//", i):
            newline = source.find("\n", i + 2)
            if newline < 0:
                break
            i = newline
            continue
        if source.startswith("/*", i):
            end = source.find("*/", i + 2)
            if end < 0:
                break
            line += source.count("\n", i, end + 2)
            i = end + 2
            continue
        if source.startswith('"""', i):
            start, start_line = i, line
            end = source.find('"""', i + 3)
            if end < 0:
                break
            raw = source[i + 3 : end]
            found.append(Literal(start, end + 3, start_line, raw))
            line += raw.count("\n")
            i = end + 3
            continue
        if source[i] == '"':
            start, start_line = i, line
            i += 1
            value: list[str] = []
            while i < size:
                char = source[i]
                if char == "\\" and i + 1 < size:
                    value.append(source[i + 1])
                    i += 2
                    continue
                if char == '"':
                    i += 1
                    break
                if char == "\n":
                    line += 1
                value.append(char)
                i += 1
            found.append(Literal(start, i, start_line, "".join(value)))
            continue
        if source[i] == "\n":
            line += 1
        i += 1
    return found


def _allowed(source: str, line: int) -> bool:
    lines = source.splitlines()
    for candidate in range(max(0, line - 3), min(len(lines), line)):
        if ALLOW.search(lines[candidate]):
            return True
    return False


def _non_rendered_script(source: str, literal: Literal) -> bool:
    """Ignore an embedded executable script, never product-facing Kotlin copy."""
    line_start = source.rfind("\n", 0, literal.start) + 1
    declaration = source[line_start : literal.start]
    line_end = source.find("\n", literal.end)
    suffix = source[literal.end : line_end if line_end >= 0 else len(source)]
    return bool(
        re.search(r"\bval\s+[A-Za-z0-9_]*script[A-Za-z0-9_]*\s*=\s*$", declaration, re.IGNORECASE)
        and re.match(r"\s*\.trimIndent\(\)", suffix)
    )


def violations(source: str) -> list[Violation]:
    literals = _literals(source)
    groups: list[list[Literal]] = []
    for literal in literals:
        if groups and re.fullmatch(r"[\s+]*", source[groups[-1][-1].end : literal.start]):
            groups[-1].append(literal)
        else:
            groups.append([literal])

    failures: list[Violation] = []
    for group in groups:
        if len(group) == 1 and _non_rendered_script(source, group[0]):
            continue
        visible = " ".join(part.value for part in group)
        visible = re.sub(r"\s+", " ", visible).strip()
        word_count = len(WORDS.findall(visible))
        char_count = len(visible)
        if word_count <= MAX_WORDS and char_count <= MAX_CHARS:
            continue
        if _allowed(source, group[0].line):
            continue
        sample = visible[:96] + ("..." if len(visible) > 96 else "")
        failures.append(Violation(group[0].line, word_count, char_count, sample))
    return failures


def default_paths(root: pathlib.Path) -> list[pathlib.Path]:
    ui = root / "app/src/main/kotlin/com/hermesagent/mobile/ui"
    data_sources = [root / relative for relative in RENDERED_DATA_SOURCES]
    missing = [path for path in data_sources if not path.is_file()]
    if missing:
        rendered = ", ".join(str(path.relative_to(root)) for path in missing)
        raise AssertionError(f"rendered product-copy source is missing: {rendered}")
    return sorted([*(path for path in ui.rglob("*.kt") if path.is_file()), *data_sources])


def self_test() -> None:
    essay = '''Text(
        "This deliberately long primary explanation is split across Kotlin source " +
        "but still forms one rendered paragraph with far too many words for a " +
        "normal product surface because it discusses background mechanics instead " +
        "of giving the user one clear state and a useful next action right now."
    )'''
    caught = violations(essay)
    if len(caught) != 1:
        raise AssertionError(f"synthetic concatenated essay was not caught: {caught}")

    allowed = '''// product-copy-allow: mandated confirmation text is reviewed verbatim
Text("one two three four five six seven eight nine ten eleven twelve thirteen fourteen " +
     "fifteen sixteen seventeen eighteen nineteen twenty twentyone twentytwo twentythree " +
     "twentyfour twentyfive twentysix twentyseven twentyeight twentynine thirty thirtyone " +
     "thirtytwo thirtythree thirtyfour thirtyfive thirtysix thirtyseven")'''
    if violations(allowed):
        raise AssertionError("a nearby reasoned allow marker was ignored")

    embedded_script = '''val cleanupScript = """
        this deliberately long remote executable script contains many implementation words but is never rendered
        as product copy and therefore must stay outside the primary copy threshold even when its Kotlin owner is
        an explicitly scanned source because exception strings from that same source do render transitively
    """.trimIndent()'''
    if violations(embedded_script):
        raise AssertionError("an embedded non-rendered executable script was treated as product copy")

    with tempfile.TemporaryDirectory() as temporary:
        root = pathlib.Path(temporary)
        future_surface = root / "app/src/main/kotlin/com/hermesagent/mobile/ui/future/FutureSurface.kt"
        future_surface.parent.mkdir(parents=True)
        future_surface.write_text('Text("Ready")', encoding="utf-8")
        rendered_sources = [root / relative for relative in RENDERED_DATA_SOURCES]
        for source in rendered_sources:
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text('val renderedError = "Try again."', encoding="utf-8")

        protocol = root / "app/src/main/kotlin/com/hermesagent/mobile/data/gateway/GatewayProtocol.kt"
        protocol.write_text('const val METHOD = "session.list"', encoding="utf-8")
        test_source = root / "app/src/test/kotlin/CopyFixtureTest.kt"
        test_source.parent.mkdir(parents=True)
        test_source.write_text('val fixture = "not rendered"', encoding="utf-8")
        documentation = root / "docs/protocol-example.md"
        documentation.parent.mkdir(parents=True)
        documentation.write_text('"not rendered"', encoding="utf-8")

        expected = sorted([future_surface, *rendered_sources])
        discovered = default_paths(root)
        if discovered != expected:
            raise AssertionError("production scope did not include only UI and rendered error sources")
        for source in rendered_sources:
            if source not in discovered:
                raise AssertionError(f"explicit rendered source was not discovered: {source.relative_to(root)}")
        for excluded in (protocol, test_source, documentation):
            if excluded in discovered:
                raise AssertionError(f"excluded source entered product-copy scope: {excluded.relative_to(root)}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("paths", nargs="*")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        print("ok    product-copy gate catches a synthetic concatenated essay")

    root = pathlib.Path(__file__).resolve().parents[1]
    paths = [pathlib.Path(path) for path in args.paths]
    if not paths:
        paths = default_paths(root)

    failed = False
    for path in paths:
        actual = path if path.is_absolute() else root / path
        for issue in violations(actual.read_text(encoding="utf-8")):
            failed = True
            relative = actual.relative_to(root) if actual.is_relative_to(root) else actual
            print(f"FAIL  {relative}:{issue.line}: primary UI copy is {issue.words} words/{issue.chars} chars")
            print(f"  {issue.sample}")
            print("  fix: state the task/outcome/next action concisely, move detail to docs/help, or add")
            print("       // product-copy-allow: <specific reason> directly above a rare necessary string")

    if failed:
        return 1
    print(f"ok    product copy within {MAX_WORDS} words/{MAX_CHARS} characters")
    return 0


if __name__ == "__main__":
    sys.exit(main())
