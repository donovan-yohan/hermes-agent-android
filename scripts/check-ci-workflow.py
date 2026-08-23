#!/usr/bin/env python3
"""Keep the Android exact-head GitHub workflow's delivery contract explicit.

This is intentionally standard-library-only and validates the runner-facing
requirements which a YAML formatter cannot infer: event coverage, JDK/SDK pins,
exact Gradle gate, and rolling main APK lifecycle.
"""
from __future__ import annotations

from pathlib import Path
import sys

WORKFLOW = Path(".github/workflows/android-exact-head.yml")
REQUIRED = (
    "pull_request:",
    "push:\n    branches: [main]",
    "permissions:\n  contents: read",
    "cancel-in-progress: true",
    "ROLLING_ARTIFACT: hermes-mobile-latest",
    "timeout-minutes: 45",
    "actions/setup-java@v4",
    'java-version: "17"',
    "cache: gradle",
    "android-actions/setup-android@v3",
    "packages:",
    "platform-tools",
    "platforms;android-36",
    "build-tools;36.0.0",
    "secrets.HERMES_ROLLING_DEBUG_KEYSTORE_BASE64",
    "base64 --decode",
    "keytool -list",
    "./gradlew check assembleDebug --no-daemon",
    "actions/upload-artifact@v4",
    "- name: Upload debug APK",
    "github.event_name == 'push' && env.ROLLING_ARTIFACT",
    "app/build/outputs/apk/debug/app-debug.apk",
    "if-no-files-found: error",
    "retention-days: 90",
    "prune:",
    "rolling APK upload did not return an artifact id",
    "--method DELETE",
)


def _indented_block(text: str, marker: str) -> str:
    """Return a YAML block from an exact marker to its next sibling."""
    lines = text.splitlines()
    try:
        start = lines.index(marker)
    except ValueError:
        return ""
    indent = len(marker) - len(marker.lstrip())
    end = start + 1
    while end < len(lines):
        line = lines[end]
        if line.strip() and len(line) - len(line.lstrip()) <= indent:
            break
        end += 1
    return "\n".join(lines[start:end])


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

    workflow_permissions = _indented_block(effective, "permissions:")
    signing_step = _indented_block(effective, "      - name: Restore rolling debug keystore")
    gradle_step = _indented_block(effective, "      - name: Check and assemble exact head")
    removal_step = _indented_block(effective, "      - name: Remove rolling debug keystore")
    upload_step = _indented_block(effective, "      - name: Upload debug APK")
    prune_job = _indented_block(effective, "  prune:")
    if "if: always()" in upload_step:
        failures.append("rolling upload must run only after a successful Gradle gate")
    if "if: github.event_name == 'push'" not in signing_step:
        failures.append("persistent rolling signing material must be restored only on main pushes")
    if "if: always()" not in removal_step or "debug.keystore" not in removal_step:
        failures.append("rolling signing material must be removed after every build, even a failed one")
    if "shell: bash" not in prune_job:
        failures.append("prune step must use the pipefail-enabled bash shell")
    if "if: github.event_name == 'push'" not in prune_job:
        failures.append("prune job must run only on main pushes")
    if effective.count("actions: write") != 1 or "actions: write" not in prune_job:
        failures.append("actions: write must be scoped only to the prune job")
    if "actions: write" in workflow_permissions:
        failures.append("workflow-level permissions must not grant actions: write")
    if "needs: check" not in prune_job:
        failures.append("prune job must depend on the successful check/upload job")
    if "select(.id != ${CURRENT_ARTIFACT_ID})" not in prune_job:
        failures.append("prune query must exclude the newly uploaded artifact id")
    if signing_step and gradle_step and effective.index(signing_step) > effective.index(gradle_step):
        failures.append("rolling debug keystore must be restored before the Gradle build")
    if upload_step and prune_job and effective.index(upload_step) > effective.index(prune_job):
        failures.append("rolling APK must be uploaded before superseded artifacts are pruned")
    if failures:
        for failure in failures:
            print(f"FAIL  {failure}")
        return 1
    print("ok    Android exact-head workflow gates PR/main, uploads one rolling main APK, and prunes superseded artifacts")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
