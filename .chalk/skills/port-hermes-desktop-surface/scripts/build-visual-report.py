#!/usr/bin/env python3
"""Build a side-by-side Desktop/Android visual-parity report from capture packets."""

import argparse
import html
import json
from pathlib import Path


def load(path: Path) -> dict:
    if not path.is_file():
        raise SystemExit(f"missing capture contract: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def desktop_sha(receipt: dict) -> str:
    """Read current receipts first, with the pre-schema-v1 packet fallback."""
    return receipt.get("desktop_upstream_sha") or receipt.get("reference", {}).get("upstreamSha") or "unknown"


def android_device_label(receipt: dict) -> str:
    """Read viewport from current receipts and old device fields when necessary."""
    device = receipt.get("device", {})
    viewport = receipt.get("viewport", {})
    values = (
        device.get("model"),
        viewport.get("size") or device.get("size"),
        viewport.get("density") or device.get("density"),
    )
    return " · ".join(value for value in values if isinstance(value, str) and value) or "unknown device"


def main() -> None:
    parser = argparse.ArgumentParser(description="Build a side-by-side Desktop/Android visual-parity report.")
    parser.add_argument("--name", required=True, help="the shared capture name")
    parser.add_argument("--root", default="build/visual-parity", help="visual-parity output root")
    args = parser.parse_args()

    packet = Path(args.root).resolve() / args.name
    desktop_image = packet / "desktop/reference.png"
    android_image = packet / "android/reference.png"
    for image in (desktop_image, android_image):
        if not image.is_file():
            raise SystemExit(f"missing capture image: {image}")

    desktop = load(packet / "desktop/contract.json")
    android = load(packet / "android/contract.json")
    report = packet / "report.html"
    report.write_text(
        f"""<!doctype html>
<meta charset="utf-8">
<title>{html.escape(args.name)} visual parity</title>
<style>
  :root {{ color-scheme: dark; font-family: system-ui, sans-serif; background: #101013; color: #eee; }}
  body {{ margin: 0; padding: 20px; }} header {{ margin-bottom: 16px; }}
  h1 {{ margin: 0 0 6px; font-size: 18px; }} p {{ margin: 0; color: #aaa; font-size: 12px; }}
  main {{ display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; align-items: start; }}
  figure {{ margin: 0; min-width: 0; }} figcaption {{ display: flex; justify-content: space-between; gap: 8px; margin-bottom: 8px; font-size: 12px; }}
  figcaption span:last-child {{ color: #888; overflow-wrap: anywhere; text-align: right; }}
  img {{ display: block; max-width: 100%; height: auto; border: 1px solid #303038; background: #18181c; }}
  @media (max-width: 800px) {{ main {{ grid-template-columns: 1fr; }} }}
</style>
<header>
  <h1>{html.escape(args.name)}</h1>
  <p>Judge hierarchy, typography, icon family/order, spacing rhythm, surfaces, and every recorded deviation.</p>
</header>
<main>
  <figure><figcaption><strong>Desktop</strong><span>{html.escape(desktop_sha(desktop))}</span></figcaption><img src="desktop/reference.png" alt="Desktop reference"></figure>
  <figure><figcaption><strong>Android</strong><span>{html.escape(android_device_label(android))}</span></figcaption><img src="android/reference.png" alt="Android reference"></figure>
</main>
""",
        encoding="utf-8",
    )
    print(f"visual parity report: {report}")


if __name__ == "__main__":
    main()
