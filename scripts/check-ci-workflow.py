#!/usr/bin/env python3
"""Keep the Android exact-head GitHub workflow's delivery contract explicit.

This is intentionally standard-library-only and validates the runner-facing
requirements which a YAML formatter cannot infer: event coverage, JDK/SDK pins,
exact Gradle gate, and debug APK upload path.
"""
from __future__ import annotations

from pathlib import Path
import sys

WORKFLOW = Path(".github/workflows/android-exact-head.yml")
REQUIRED = (
    "pull_request:",
    "push:",
    "permissions:\n  contents: read",
    "timeout-minutes: 45",
    "actions/setup-java@v4",
    'java-version: "17"',
    "cache: gradle",
    "android-actions/setup-android@v3",
    "packages:",
    "platform-tools",
    "platforms;android-36",
    "build-tools;36.0.0",
    "./gradlew check assembleDebug --no-daemon",
    "actions/upload-artifact@v4",
    "app/build/outputs/apk/debug/app-debug.apk",
    "if-no-files-found: error",
)


def main() -> int:
    try:
        text = WORKFLOW.read_text(encoding="utf-8")
    except OSError as error:
        print(f"FAIL  {WORKFLOW}: {error}")
        return 1
    failures = []
    if "\t" in text:
        failures.append("tabs are not allowed in workflow YAML")
    effective = "\n".join(
        line for line in text.splitlines()
        if not line.lstrip().startswith("#")
    )
    for required in REQUIRED:
        if required not in effective:
            failures.append(f"missing required exact-head workflow contract: {required}")
    if failures:
        for failure in failures:
            print(f"FAIL  {failure}")
        return 1
    print("ok    Android exact-head workflow declares PR/push, JDK 17, SDK 36, Gradle check+APK, and artifact upload")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
