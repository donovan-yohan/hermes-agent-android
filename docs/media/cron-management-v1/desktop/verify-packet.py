"""Read-only verification of the curated publication, not a live recapture."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import re

ROOT = Path(__file__).resolve().parent
PIN = "5fc490e2b34123212d73ef316086f939567a725a"

def sha(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()

def load(name):
    return json.loads((ROOT / name).read_text())

def checked_path(name):
    path = PurePosixPath(name)
    assert not path.is_absolute() and ".." not in path.parts, "Unsafe manifest path"
    target = ROOT / name
    assert not target.is_symlink(), "Symlinks are not publication evidence"
    return target

def verify():
    manifest = load("publication-manifest.json")
    original = load("SHA256SUMS.json")
    assert sha(ROOT / "SHA256SUMS.json") == manifest["source_packet"]["manifest_sha256"]
    assert len(original) == manifest["source_packet"]["manifest_entries"]
    files = manifest["included_files"]
    actual = {p.relative_to(ROOT).as_posix() for p in ROOT.rglob("*") if p.is_file()}
    assert actual == set(files) | {"publication-manifest.json"}, "Unexpected/missing publication files"
    for name, digest in files.items():
        assert sha(checked_path(name)) == digest, f"Publication hash mismatch: {name}"
    exclusions = {row["path"]: row["sha256"] for row in manifest["excluded_files"]}
    for name, digest in exclusions.items():
        assert not (ROOT / name).exists() and original[name] == digest, "Excluded file restored or misidentified"
    transformations = {row["path"]: row for row in manifest["transformations"]}
    preserved = 0
    for name, digest in original.items():
        if name in exclusions:
            continue
        if name in transformations:
            row = transformations[name]
            assert row["original_sha256"] == digest and row["published_sha256"] == files[name]
            assert row["original_sha256"] != row["published_sha256"]
        else:
            assert files[name] == digest, f"Unexplained source change: {name}"
            preserved += 1
    index = load("capture-index.json")
    summary = load("execution-summary.json")
    events = load("interaction-events.json")
    assert len(index) == summary["captures"] == 40
    assert len({c["name"] for c in index}) == 20
    assert {(c["name"], c["theme"]) for c in index} == {(name, theme) for name in summary["states"] for theme in ("dark", "light")}
    assert summary["assertions"] == sum(e["kind"] == "assert" for e in events) == 340
    assert summary["errors"] == 0 and load("browser-errors.json") == []
    sources, fonts, historical_derivatives = set(), set(), set()
    screenshot_files, receipt_files = set(), set()
    for capture in index:
        screenshot_files.add(capture["file"])
        receipt_files.add(capture["receipt"])
        assert files[capture["file"]] == original[capture["file"]] == capture["sha256"]
        assert files[capture["receipt"]] == original[capture["receipt"]]
        receipt = load(capture["receipt"])
        assert receipt["file"] == capture["file"] and receipt["sha256"] == capture["sha256"]
        assert receipt["pin"] == PIN and receipt["synthetic"] is True and receipt["liveBackend"] is False
        assert receipt["target"] and receipt["theme"] == capture["theme"]
        assert receipt["viewport"] == {"width": 1280, "height": 960, "deviceScaleFactor": 1}
        assert receipt["trackedBeforeManifestSha256"] == exclusions["tracked-before.json"]
        for name, digest in receipt["sourceHashes"].items():
            published = "fixtures/" + PurePosixPath(name).name if name.startswith("apps/desktop/cron-reference.") else "source/" + name
            assert original[published] == digest
            if published in transformations:
                assert transformations[published]["original_sha256"] == digest
                historical_derivatives.add(published)
            else:
                assert files[published] == digest
                sources.add(published)
        for font in receipt["fontSources"]:
            name = "fonts/" + font["file"]
            assert files[name] == original[name] == font["sha256"]
            fonts.add(name)
    assert screenshot_files == {name for name in files if name.startswith("screenshots/")}
    assert receipt_files == {name for name in files if name.startswith("receipts/")}
    historical = load("verification.json")
    assert historical["trackedFiles"] == 20366 and historical["changedTrackedSha256"] == [] and historical["gitDiffHeadExitCode"] == 0
    assert historical["captureFiles"] == 40 and historical["assertions"] == 340 and historical["browserErrors"] == 0
    assert exclusions["tracked-before.json"] == exclusions["tracked-after.json"]
    patterns = {
        "absolute_user_path": r"(?:/[U]sers/|/[h]ome/|[A-Za-z]:\\Users\\)[A-Za-z0-9_.-]+",
        "private_hostname": r"(?<![\w.-])(?:[A-Za-z0-9-]+\.)+(?:ts\.net|tailscale\.com)\b|(?<![\w.-])[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.local(?=[/:\s\"'<>]|$)",
        "email_address": r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b",
        "private_key": r"-----BEGIN (?:[A-Z ]+)?PRIVATE KEY-----",
        "credential_token": r"\b(?:sk-|ghp_|github_pat_)[A-Za-z0-9_-]{15,}|\bBearer\s+[A-Za-z0-9_.-]{12,}",
        "nonloopback_ipv4": r"\b(?!(?:127\.0\.0\.1|0\.0\.0\.0)\b)(?:\d{1,3}\.){3}\d{1,3}\b",
    }
    # Exact benign upstream-source matches, not a blanket file/rule bypass.
    benign = {
        ("source/apps/desktop/src/app/cron/index.tsx", "private_hostname", "c.deliveryLabels." + "local"): "TypeScript property access, not a hostname",
        ("source/apps/desktop/src/i18n/en.ts", "email_address", "you@" + "example.com"): "Reserved example-domain UI placeholder, not personal data",
    }
    findings, benign_matches, texts = [], [], 0
    for name in sorted(actual):
        data = checked_path(name).read_bytes()
        if name.endswith((".png", ".ttf")):
            continue
        text = data.decode("utf-8")
        assert "\0" not in text, "Unexpected binary text"
        texts += 1
        for rule, expression in patterns.items():
            for match in re.finditer(expression, text):
                key = (name, rule, match.group())
                location = {"file": name, "line": text.count("\n", 0, match.start()) + 1, "rule": rule}
                if key in benign:
                    benign_matches.append({**location, "explanation": benign[key]})
                else:
                    findings.append(location)
    assert not findings, json.dumps(findings)
    return {
        "status": "passed",
        "scope": "Read-only publication integrity and heuristic text privacy scan; no browser recapture or visual judgment",
        "sourceManifestEntries": len(original),
        "publishedFiles": len(actual),
        "publicationManifestHashEntries": len(files),
        "unchangedOriginalFiles": preserved,
        "transformedAuxiliaryFiles": len(transformations),
        "excludedOriginalFiles": len(exclusions),
        "canonicalScreenshotsUnchanged": len(screenshot_files),
        "canonicalReceiptsUnchanged": len(receipt_files),
        "pairedStates": len(summary["states"]),
        "historicalAssertionsCorroboratedByEvents": summary["assertions"],
        "historicalFinalBrowserErrors": summary["errors"],
        "receiptSourceFilesByteVerified": len(sources),
        "historicalSourceHashMappingsNotOriginalBytes": sorted(historical_derivatives),
        "fontFilesByteVerified": len(fonts),
        "historicalTrackedFileAudit": "Recorded only; original before/after manifests excluded with identical SHA-256; no checkout re-audit",
        "textFilesScannedIncludingPatch": texts,
        "privacyScanRules": sorted(patterns),
        "privacyFindings": findings,
        "privacyBenignSourceMatches": benign_matches,
        "liveBackend": False,
        "fullDesktopShell": False,
        "visualReviewPerformed": False,
    }

if __name__ == "__main__":
    print(json.dumps(verify(), indent=2))
