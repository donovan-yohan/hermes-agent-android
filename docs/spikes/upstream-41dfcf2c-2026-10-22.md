# Upstream observed-main freeze: 41dfcf2c

## Target and provenance

New-port and built-in theme registry target:
`41dfcf2c1bd7146d4ad46aea08b7dd007900602e`, public main resolved by HTTPS
on 2026-10-22. This is a finite observed snapshot, not a promise to track main
indefinitely. The [926 source reading](upstream-92663ae8-2026-10-22.md),
[continuation records](../parity/desktop-audit-continuation.json), original
418-template inventory, per-surface pins and captures keep their actual revisions.
The backend compatibility target remains separate. No historical evidence is
restamped by this refresh.

## Exact old-to-new assessment

Compared with `92663ae8054ae81f207f46d83512d3f879dc698d`: 22 changed paths,
1063 additions and 95 deletions. The immutable diff was read in a disposable
HTTPS-fetched checkout. The complete path manifest is:

- `.github/actions/setup-pm/action.yml`
- `.github/workflows/ci.yaml`
- `pm/build_env.py`
- `pm/build_operations.py`
- `pm/client.py`
- `pm/environment.py`
- `pm/operations.py`
- `pm/packages.py`
- `pm/pip_check.py`
- `pyproject.toml`
- `scripts/ci/cache_report.py`
- `scripts/ci/pm_cache_log.py`
- `scripts/ci/timings_report.py`
- `scripts/termux/build_environment.py`
- `scripts/termux/build_wheels.py`
- `scripts/termux/termux_build.sh`
- `tests/ci/test_cache_report.py`
- `tests/ci/test_cache_report_collection.py`
- `tests/pm/test_pip_check.py`
- `tests/scripts/test_pm_build_consumers.py`
- `tests/test_project_metadata.py`
- `uv.lock`

The `apps/`, `tui_gateway/`, `hermes_cli/` and `plugin-catalog/` trees are
byte-identical. Theme presets, shared palettes and conversion sources therefore
remain unchanged. This permits a registry-only target update, not new behavioral
or rendered certification.

PM now passes the lock's override requirements through exported-requirement
installs and closing dependency checks. Only lock-accounted installed-version
mismatches are filtered; an unparseable or truncated report still fails.
Python 3.14's Pillow dependency and override move to 13.0.0. Termux build
consumers pass the lock to these operations. CI adds cache/timing reporting.
These changes establish no demonstrated Android client wire repair; they do
change host/package compatibility boundaries.

## Limits

PM environments, upstream tests, Pillow codecs and Termux builds were not
executed in this Android audit. No installed host, device, credentials or global
configuration changed. Android checks cannot qualify a host environment upgrade.
`semantic_complete:false` and the twelve finite residual categories remain:
source-review debt, missing implementation, #266 tooling/workflow, #67 voice
engine, provider/host, rendered and physical/signing acceptance are not erased
by moving this target.
