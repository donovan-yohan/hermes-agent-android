#!/usr/bin/env python3
"""Regression for the driver's stderr redaction, including the shutdown boundary.

Runs without pytest: `python3 test_stderr_redaction.py`.

The redaction exists because the real uvicorn access log prints the request line
and `/api/ws` carries `?token=`. It is only worth having if no fragment of the
token can reach the log, and the cases below are the ones that were each
observed to leak at some point while this was written:

  - a whole line in one write;
  - a secret split across two writes, whose first release can end part-way
    through it and whose next release then begins with the tail;
  - a byte-at-a-time drip, which leaves a partial prefix at offset 0;
  - the *exit* boundary, where a naive exit flush writes the retained partial
    prefix raw and a pump still running then writes the rest after it.

The last one is run in a subprocess, because it is a property of interpreter
shutdown rather than of the pump.
"""

from __future__ import annotations

import importlib.util
import os
import subprocess
import sys
import tempfile
import textwrap
import time

HERE = os.path.dirname(os.path.abspath(__file__))
DRIVER = os.path.join(HERE, "hm307_control_driver.py")
SECRET = "SYNTHETIC_ONLY_REDACTION_SENTINEL_123456789"


def _load_driver():
    spec = importlib.util.spec_from_file_location("hm307_driver_under_test", DRIVER)
    assert spec is not None and spec.loader is not None, f"cannot load {DRIVER}"
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _fragments_in(text: str, minimum: int = 6) -> list[str]:
    """Every substring of SECRET of at least *minimum* chars present in *text*."""
    found = []
    for start in range(len(SECRET)):
        for end in range(start + minimum, len(SECRET) + 1):
            piece = SECRET[start:end]
            if piece in text:
                found.append(piece)
    return found


def _run_case(name: str, script: str) -> tuple[int, str]:
    """Run one case as a subprocess with its stderr captured to a file."""
    with tempfile.TemporaryDirectory(prefix="hm307-redaction-") as workdir:
        log = os.path.join(workdir, "stderr.log")
        source = os.path.join(workdir, "case.py")
        with open(source, "w", encoding="utf-8") as handle:
            handle.write(textwrap.dedent(script))
        with open(log, "w", encoding="utf-8") as sink:
            code = subprocess.run(
                [sys.executable, source, DRIVER, SECRET],
                stdout=subprocess.DEVNULL, stderr=sink, timeout=60, check=False,
            ).returncode
        with open(log, encoding="utf-8", errors="replace") as sink:
            captured = sink.read()

    fragments = _fragments_in(captured)
    assert SECRET not in captured, f"{name}: the whole sentinel reached the log"
    assert not fragments, f"{name}: fragments reached the log: {fragments[:3]}"
    return code, captured


WHOLE_LINE_AND_SPLIT_AND_DRIP = """
import importlib.util, os, sys, time
spec = importlib.util.spec_from_file_location("drv", sys.argv[1])
drv = importlib.util.module_from_spec(spec); spec.loader.exec_module(drv)
secret = sys.argv[2]
drv._install_stderr_redaction([secret])

# 1. a whole access-log line, the shape that actually leaks in production
os.write(2, ('INFO: 127.0.0.1 - "GET /api/ws?token=%s HTTP/1.1" 101\\n' % secret).encode())
# 2. the same secret split across two writes
os.write(2, b"split " + secret[:10].encode()); time.sleep(0.3)
os.write(2, secret[10:].encode() + b" done\\n")
# 3. a byte-at-a-time drip, which leaves a partial prefix at offset 0
os.write(2, b"drip "); time.sleep(0.2)
for byte in secret.encode():
    os.write(2, bytes([byte])); time.sleep(0.01)
os.write(2, b" done\\n")
time.sleep(1.0)
"""

EXIT_BOUNDARY = """
import importlib.util, os, sys, time
spec = importlib.util.spec_from_file_location("drv", sys.argv[1])
drv = importlib.util.module_from_spec(spec); spec.loader.exec_module(drv)
secret = sys.argv[2]
drv._install_stderr_redaction([secret])

# Write a partial prefix and let the pump retain it, then exit *without* writing
# the rest: a naive exit flush would print this prefix raw. Nothing else writes
# the suffix, so anything captured here is the prefix the flush emitted.
os.write(2, ("tail-" + secret[:20]).encode())
time.sleep(0.6)
sys.exit(0)
"""

EXIT_RACE_WITH_LATE_SUFFIX = """
import importlib.util, os, sys, time
spec = importlib.util.spec_from_file_location("drv", sys.argv[1])
drv = importlib.util.module_from_spec(spec); spec.loader.exec_module(drv)
secret = sys.argv[2]
drv._install_stderr_redaction([secret])

# A background writer holds the suffix back until after the main thread has
# started exiting, which is the race an exit flush loses.
def late():
    time.sleep(0.5)
    os.write(2, secret[20:].encode() + b" end\\n")

import threading
threading.Thread(target=late, daemon=True).start()
os.write(2, ("head-" + secret[:20]).encode())
time.sleep(0.6)
sys.exit(0)
"""


def test_whole_line_split_and_drip() -> None:
    """The three write shapes that must never release a fragment."""
    code, captured = _run_case("write shapes", WHOLE_LINE_AND_SPLIT_AND_DRIP)
    assert code == 0, f"case exited {code}"
    assert "<redacted>" in captured, "the redaction markers are missing entirely"
    # The non-secret text either side must survive: redaction is not truncation.
    for expected in ("INFO: 127.0.0.1", "split", "drip"):
        assert expected in captured, f"{expected!r} was dropped from the log"


def test_exit_boundary_drops_the_incomplete_tail() -> None:
    """Shutdown must drop a partial prefix while keeping the text before it."""
    code, captured = _run_case("exit boundary", EXIT_BOUNDARY)
    assert code == 0, f"case exited {code}"
    # The safe bytes written before the secret are ordinary log text and pass
    # through; the retained partial prefix must not appear, and `_run_case`
    # has already failed the case if any fragment of the secret did.
    assert "tail-" in captured, "the safe text before the prefix was dropped too"
    assert len(captured) < len("tail-") + 20, (
        f"more than the safe prefix reached the log: {captured!r}"
    )


def test_exit_race_never_completes_a_secret() -> None:
    """A head released at exit plus a late suffix must not reassemble."""
    code, captured = _run_case("exit race", EXIT_RACE_WITH_LATE_SUFFIX)
    assert code == 0, f"case exited {code}"
    # Either the head was dropped at shutdown, or it was replaced; what must
    # never happen is the head surviving raw with the tail still in flight.
    assert "head-SYNTHETIC" not in captured, (
        f"the head was released raw while the tail could still follow it: {captured!r}"
    )


def test_carry_is_bounded_and_normal_output_is_intact() -> None:
    """A long line is not held up, and ordinary output passes through unchanged."""
    module = _load_driver()
    with tempfile.TemporaryDirectory(prefix="hm307-longline-") as workdir:
        log = os.path.join(workdir, "stderr.log")
        with open(log, "w", encoding="utf-8") as sink:
            code = subprocess.run(
                [sys.executable, "-c", textwrap.dedent(f"""
                    import importlib.util, os, sys, time
                    spec = importlib.util.spec_from_file_location("drv", {DRIVER!r})
                    drv = importlib.util.module_from_spec(spec); spec.loader.exec_module(drv)
                    drv._install_stderr_redaction([{SECRET!r}])
                    os.write(2, b"long " + b"x" * 9000 + b" " + {SECRET!r}.encode() + b" end\\n")
                    time.sleep(0.8)
                """)],
                stdout=subprocess.DEVNULL, stderr=sink, timeout=60, check=False,
            ).returncode
        with open(log, encoding="utf-8", errors="replace") as sink:
            captured = sink.read()

    assert code == 0
    assert "x" * 9000 in captured, "a long line must pass through, not be swallowed"
    assert SECRET not in captured
    assert not _fragments_in(captured)
    assert module.REDACTED == "<redacted>"


def main() -> int:
    tests = [
        test_whole_line_split_and_drip,
        test_exit_boundary_drops_the_incomplete_tail,
        test_exit_race_never_completes_a_secret,
        test_carry_is_bounded_and_normal_output_is_intact,
    ]
    failures = 0
    for test in tests:
        try:
            test()
        except AssertionError as failure:
            failures += 1
            print(f"FAIL {test.__name__}: {failure}")
        else:
            print(f"ok   {test.__name__}")
    print(f"\n{len(tests) - failures}/{len(tests)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
