"""Run a new synthetic capture in an explicitly configured disposable export."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from urllib.parse import urlsplit

PIN = "5fc490e2b34123212d73ef316086f939567a725a"
PACKET = Path(__file__).resolve().parent

def main():
    required = ("ROOT", "CRON_CHROMIUM", "CRON_EVIDENCE_DIR", "CRON_REFERENCE_URL")
    missing = [key for key in required if not os.environ.get(key)]
    if missing:
        raise SystemExit("Required environment variables: " + ", ".join(missing))
    repo = Path(os.environ["ROOT"]).expanduser().resolve()
    browser = Path(os.environ["CRON_CHROMIUM"]).expanduser().resolve()
    output = Path(os.environ["CRON_EVIDENCE_DIR"]).expanduser().resolve()
    if output == PACKET or PACKET in output.parents or output in PACKET.parents:
        raise SystemExit("Capture output must be separate from this published packet")
    if output == repo or repo in output.parents or output in repo.parents:
        raise SystemExit("Capture output must be separate from the disposable checkout")
    url = urlsplit(os.environ["CRON_REFERENCE_URL"])
    if url.scheme != "http" or url.hostname != "127.0.0.1" or url.username or url.password or url.query or url.fragment or url.path not in ("", "/") or not url.port:
        raise SystemExit("CRON_REFERENCE_URL must be an explicit loopback HTTP origin with a port")
    if not browser.is_file() or not os.access(browser, os.X_OK):
        raise SystemExit("CRON_CHROMIUM must be an existing executable")
    pin = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
    if pin != PIN:
        raise SystemExit("ROOT must be a disposable checkout at the documented pin")
    for filename in ("cron-reference.html", "cron-reference.tsx", "cron-reference.capture.mjs"):
        if (repo / "apps/desktop" / filename).read_bytes() != (PACKET / "fixtures" / filename).read_bytes():
            raise SystemExit("Apply the published additive fixture patch in ROOT first")
    if output.exists() and any(output.iterdir()):
        raise SystemExit("CRON_EVIDENCE_DIR must be a new or empty directory; never overwrite evidence")
    env = {k: v for k, v in os.environ.items() if not k.startswith("HERMES")}
    env.update(HOME=str(output / "home"), TMPDIR=str(output / "tmp"), CRON_EVIDENCE_DIR=str(output), CRON_CHROMIUM=str(browser))
    if "--preflight" not in sys.argv[1:]:
        output.mkdir(parents=True, exist_ok=True)
        (output / "home").mkdir()
        (output / "tmp").mkdir()
        shutil.copytree(PACKET / "fonts", output / "fonts")
        tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=repo).decode().split("\0")
        before = {name: hashlib.sha256((repo / name).read_bytes()).hexdigest() for name in tracked if name}
        (output / "tracked-before.json").write_text(json.dumps(before, indent=2))
    command = ["node", "apps/desktop/cron-reference.capture.mjs", *sys.argv[1:]]
    result = subprocess.run(command, cwd=repo, env=env, capture_output=True, text=True)
    print(result.stdout + result.stderr, end="")
    if "--preflight" not in sys.argv[1:]:
        with (output / "commands.jsonl").open("a") as log:
            log.write(json.dumps({"command": command, "cwd": str(repo), "environment": "HERMES* removed; isolated HOME/TMPDIR; explicit browser and loopback URL", "exit_code": result.returncode, "output": result.stdout + result.stderr}) + "\n")
    return result.returncode

if __name__ == "__main__":
    raise SystemExit(main())
