"""Host-only readiness checks; fake adb never contacts a device or starts Gradle."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class InstrumentationReadinessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        directory = Path(self.temp.name)
        self.log = directory / "adb.log"
        adb = directory / "adb"
        adb.write_text('''#!/usr/bin/env python3
import os, sys
with open(os.environ["ADB_LOG"], "a") as log:
    log.write(" ".join(sys.argv[1:]) + "\\n")
if os.environ.get("FAIL_ADB") == "1":
    sys.exit(9)
if sys.argv[1:] == ["shell", "ime", "list", "-s", "-a"]:
    print("fixture.ime/.Service")
''')
        adb.chmod(0o755)
        self.env = dict(os.environ, PATH=f"{directory}:{os.environ['PATH']}",
                        ADB_LOG=str(self.log))

    def prepare(self):
        return subprocess.run(["bash", str(ROOT / "scripts/prepare-ci-emulator.sh")],
                              env=self.env, capture_output=True, text=True)

    def test_wakes_before_dismissing_keyguard_without_system_dump(self):
        result = self.prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.log.read_text()
        self.assertLess(calls.index("KEYCODE_WAKEUP"), calls.index("dismiss-keyguard"))
        self.assertIn("ime set fixture.ime/.Service", calls)
        self.assertNotIn("dumpsys", calls)

    def test_failed_wake_is_not_ignored(self):
        self.env["FAIL_ADB"] = "1"
        self.assertNotEqual(0, self.prepare().returncode)
        self.assertNotIn("dismiss-keyguard", self.log.read_text())

    def test_compile_precedes_prepare_and_instrumentation(self):
        workflow = (ROOT / ".github/workflows/android-exact-head.yml").read_text()
        compile_at = workflow.index("./gradlew :app:assembleDebug :app:assembleDebugAndroidTest")
        prepare_at = workflow.index("./scripts/prepare-ci-emulator.sh", compile_at)
        run_at = workflow.index("./gradlew :app:connectedDebugAndroidTest", prepare_at)
        self.assertLess(compile_at, prepare_at)
        self.assertLess(prepare_at, run_at)

    def test_ci_preserves_only_original_test_report_upload(self):
        workflow = (ROOT / ".github/workflows/android-exact-head.yml").read_text()
        lane = workflow.split("  instrumented:", 1)[1].split("  prune:", 1)[0]
        self.assertEqual(1, lane.count("uses: actions/upload-artifact@v4"))
        upload = lane.split("      - name: Upload instrumented lane evidence", 1)[1]
        self.assertIn("if: always()", upload)
        paths = upload.split("          path: |\n", 1)[1].split("          if-no-files-found:", 1)[0]
        self.assertEqual([
            "app/build/outputs/androidTest-results/connected/**",
            "app/build/reports/androidTests/connected/**",
        ], paths.split())

    def test_readiness_is_inside_activity_lifetime_without_export(self):
        source = ROOT / "app/src/androidTest/kotlin/com/hermesagent/mobile/device"
        for name in ("ComposerImeTest", "PlatformAccessibilityTest"):
            text = (source / f"{name}.kt").read_text()
            self.assertIn("@get:Rule(order = 0)\n    val compose", text)
            self.assertIn("@get:Rule(order = 1)\n    val windowReadiness", text)
        rule = (source / "WindowReadinessRule.kt").read_text()
        for token in ("isInteractive", "isKeyguardLocked", "isDeviceLocked",
                      "hasWindowFocus()", "base.evaluate()"):
            self.assertIn(token, rule)
        for token in ("takeScreenshot", "executeShellCommand", "rootInActiveWindow",
                      "getExternalFilesDir", "additionalTestOutputDir", "File("):
            self.assertNotIn(token, rule)


if __name__ == "__main__":
    unittest.main()
