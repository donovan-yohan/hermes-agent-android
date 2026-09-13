#!/usr/bin/env python3
"""Compatibility tests for visual-parity report receipt schemas."""
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / ".chalk/skills/port-hermes-desktop-surface/scripts/build-visual-report.py"
spec = importlib.util.spec_from_file_location("build_visual_report", SCRIPT)
assert spec and spec.loader
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class BuildVisualReportTest(unittest.TestCase):
    def test_reads_current_receipt_fields(self) -> None:
        self.assertEqual("a" * 40, report.desktop_sha({"desktop_upstream_sha": "a" * 40}))
        self.assertEqual("Pixel · Physical size: 1080x2400 · Physical density: 420", report.android_device_label({
            "device": {"model": "Pixel"},
            "viewport": {"size": "Physical size: 1080x2400", "density": "Physical density: 420"},
        }))

    def test_keeps_old_packet_compatibility(self) -> None:
        self.assertEqual("legacy-sha", report.desktop_sha({"reference": {"upstreamSha": "legacy-sha"}}))
        self.assertEqual("Legacy · 1080x2400 · 420", report.android_device_label({
            "device": {"model": "Legacy", "size": "1080x2400", "density": "420"},
        }))

    def test_builds_report_from_current_schema_packet(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            packet = root / "fixture"
            for platform in ("desktop", "android"):
                path = packet / platform
                path.mkdir(parents=True)
                (path / "reference.png").write_bytes(b"png")
            (packet / "desktop/contract.json").write_text('{"desktop_upstream_sha":"desktop-pin"}', encoding="utf-8")
            (packet / "android/contract.json").write_text('{"device":{"model":"Pixel"},"viewport":{"size":"1080x2400","density":"420"}}', encoding="utf-8")
            import sys
            previous = sys.argv
            try:
                sys.argv = [str(SCRIPT), "--root", str(root), "--name", "fixture"]
                report.main()
            finally:
                sys.argv = previous
            html = (packet / "report.html").read_text(encoding="utf-8")
        self.assertIn("desktop-pin", html)
        self.assertIn("Pixel · 1080x2400 · 420", html)


if __name__ == "__main__":
    unittest.main()
