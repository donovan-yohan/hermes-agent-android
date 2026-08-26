#!/usr/bin/env python3
"""Keep the Android exact-head delivery contract explicit.

This covers two files: the exact-head GitHub workflow and the rolling debug
signing config in `app/build.gradle.kts` that the workflow opts into. It is
intentionally standard-library-only and validates the runner-facing
requirements which a YAML formatter cannot infer: event coverage, JDK/SDK pins,
exact Gradle gate, rolling main APK lifecycle, the env-gated debug signing
config both ends have to agree on, and the instrumented emulator lane — its
job, its pinned device, its stated boundary, and the fact that it has tests to
run at all.
"""
from __future__ import annotations

from pathlib import Path
import re
import sys

WORKFLOW = Path(".github/workflows/android-exact-head.yml")
BUILD_FILE = Path("app/build.gradle.kts")
ROLLING_KEYSTORE_ENV = "HERMES_ROLLING_DEBUG_KEYSTORE_PATH"
ANDROID_TEST_SOURCES = Path("app/src/androidTest/kotlin")
# The lane's honesty is part of its contract, so it is asserted rather than
# left to a reviewer to notice going missing.
INSTRUMENTED_BOUNDARY = (
    "This emulator lane does not substitute for physical acceptance."
)
INSTRUMENTED_MATRIX = (
    "PKCE browser hand-off, real radio, network handoff, TalkBack and media "
    "stay on the device matrix."
)
# The chat surface switches to its wide layout above 720 dp, and the rotation
# test asserts the landscape window really crosses it. A different profile
# would turn that assertion vacuous, so the device is part of the contract.
INSTRUMENTED_DEVICE = (
    "reactivecircus/android-emulator-runner@v2",
    "api-level: 34",
    "target: google_apis",
    "arch: x86_64",
    "profile: pixel_6",
)
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
    'keystore="$RUNNER_TEMP/rolling-debug.keystore"',
    "./gradlew check assembleDebug --no-daemon --no-build-cache",
    "Verify rolling APK signing identity",
    "verify --print-certs",
    "expected_digest,,",
    "actual_digest,,",
    "actions/upload-artifact@v4",
    "- name: Upload debug APK",
    "github.event_name == 'push' && env.ROLLING_ARTIFACT",
    "app/build/outputs/apk/debug/app-debug.apk",
    "if-no-files-found: error",
    "retention-days: 90",
    "prune:",
    "rolling APK upload did not return an artifact id",
    "--method DELETE",
    "instrumented:",
    "Enable KVM",
    "~/.android/avd/*",
    "~/.android/adb*",
    "./gradlew :app:connectedDebugAndroidTest --no-daemon --no-build-cache",
    "app/build/outputs/androidTest-results/connected/**",
)
# Only the opt-in seam is asserted here; the runtime `apksigner verify` step is
# authoritative for the keystore's actual store/alias/password material.
BUILD_REQUIRED = (
    f'providers.environmentVariable("{ROLLING_KEYSTORE_ENV}")',
    'getByName("debug")',
    # The emulator job runs `connectedDebugAndroidTest`; that task compiles
    # nothing unless the source set and its test runtime are still wired up.
    'getByName("androidTest").kotlin.srcDir("src/androidTest/kotlin")',
    "androidTestImplementation(libs.compose.ui.test.junit4)",
    'testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"',
    # `check` compiles and dexes the lane so a broken one fails locally rather
    # than an emulator job away.
    'dependsOn("assembleDebugAndroidTest")',
)


def _read_text(path: Path) -> str | None:
    """Return a file's text, or None after reporting why it could not be read."""
    try:
        return path.read_text(encoding="utf-8")
    except OSError as error:
        print(f"FAIL  {path}: {error}")
        return None


def _effective_text(text: str, comment: str) -> str:
    """Return text with comments removed so a commented-out contract cannot pass."""
    if comment == "//":
        text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(
        line for line in text.splitlines()
        if not line.lstrip().startswith(comment)
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
    text = _read_text(WORKFLOW)
    build_text = _read_text(BUILD_FILE)
    if text is None or build_text is None:
        return 1
    failures = []
    if "\t" in text:
        failures.append("tabs are not allowed in workflow YAML")
    effective = _effective_text(text, "#")
    for required in REQUIRED:
        if required not in effective:
            failures.append(f"missing required exact-head workflow contract: {required}")
    effective_build = _effective_text(build_text, "//")
    for required in BUILD_REQUIRED:
        if required not in effective_build:
            failures.append(f"missing required rolling signing configuration: {required}")

    workflow_permissions = _indented_block(effective, "permissions:")
    signing_step = _indented_block(effective, "      - name: Restore rolling debug keystore")
    gradle_step = _indented_block(effective, "      - name: Check and assemble exact head")
    verification_step = _indented_block(effective, "      - name: Verify rolling APK signing identity")
    removal_step = _indented_block(effective, "      - name: Remove rolling debug keystore")
    upload_step = _indented_block(effective, "      - name: Upload debug APK")
    prune_job = _indented_block(effective, "  prune:")
    if "if: always()" in upload_step:
        failures.append("rolling upload must run only after a successful Gradle gate")
    if "if: github.event_name == 'push'" not in signing_step:
        failures.append("persistent rolling signing material must be restored only on main pushes")
    exports_keystore_path = (
        f"{ROLLING_KEYSTORE_ENV}=" in signing_step and '"$GITHUB_ENV"' in signing_step
    )
    if not exports_keystore_path:
        failures.append("rolling keystore path must be exported for the Gradle build")
    if '-keystore "$HERMES_ROLLING_DEBUG_KEYSTORE_PATH"' not in verification_step:
        failures.append("finished APK must be compared with the explicitly configured keystore")
    if "if: github.event_name == 'push'" not in verification_step:
        failures.append("rolling APK signing verification must run on main pushes")
    if "if: always()" not in removal_step or "debug.keystore" not in removal_step:
        failures.append("rolling signing material must be removed after every build, even a failed one")
    if "$RUNNER_TEMP/rolling-debug.keystore" not in removal_step:
        failures.append("rolling signing cleanup must cover restore failures before the path export")
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
    instrumented_job = _indented_block(effective, "  instrumented:")
    if not instrumented_job:
        failures.append(
            "the instrumented emulator lane job is missing; "
            "app/src/androidTest/ is only a claim until CI runs it on a device"
        )
    else:
        if "timeout-minutes:" not in instrumented_job:
            failures.append("the instrumented lane must be time-bounded")
        if "needs:" in instrumented_job:
            failures.append(
                "the instrumented lane must not depend on another job; "
                "an emulator failure must not withhold the rolling APK"
            )
        if "connectedDebugAndroidTest" not in instrumented_job:
            failures.append("the instrumented lane must run the connected androidTest task")
        for pin in INSTRUMENTED_DEVICE:
            if pin not in instrumented_job:
                failures.append(f"the instrumented lane must pin its device: {pin}")
        if "Enable KVM" not in instrumented_job:
            failures.append("the instrumented lane needs KVM to fit its timeout")
        if "actions/cache@v4" not in instrumented_job:
            failures.append("the instrumented lane must cache the AVD")
        if "actions/upload-artifact@v4" not in instrumented_job:
            failures.append("the instrumented lane must upload its results as evidence")
        for sentence in (INSTRUMENTED_BOUNDARY, INSTRUMENTED_MATRIX):
            if sentence not in instrumented_job:
                failures.append(
                    f"the instrumented lane must state its boundary in the workflow: {sentence}"
                )
    if "needs: instrumented" in effective:
        failures.append("no job may gate on the instrumented lane; it is evidence, not a build step")

    instrumented_upload = _indented_block(
        effective, "      - name: Upload instrumented lane evidence"
    )
    if instrumented_upload and "if: always()" not in instrumented_upload:
        failures.append("a failing instrumented run is exactly when its results are needed")

    lane_tests = sorted(ANDROID_TEST_SOURCES.rglob("*Test.kt")) if ANDROID_TEST_SOURCES.is_dir() else []
    if not lane_tests:
        failures.append(
            f"{ANDROID_TEST_SOURCES} holds no *Test.kt; "
            "the emulator job would pass by running nothing"
        )

    ordered = (
        ("rolling debug keystore restore", signing_step),
        ("Gradle build", gradle_step),
        ("rolling APK signing verification", verification_step),
        ("rolling APK upload", upload_step),
        ("artifact pruning", prune_job),
    )
    present = [(name, effective.index(block)) for name, block in ordered if block]
    for (first, first_at), (second, second_at) in zip(present, present[1:]):
        if first_at > second_at:
            failures.append(f"{first} must run before {second}")
    if failures:
        for failure in failures:
            print(f"FAIL  {failure}")
        return 1
    print(
        "ok    Android exact-head workflow gates PR/main, uploads one rolling main APK, "
        "prunes superseded artifacts, runs the instrumented lane on a pinned emulator "
        f"with its boundary stated ({len(lane_tests)} test classes), and "
        "app/build.gradle.kts keeps the rolling debug signing config env-gated"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
