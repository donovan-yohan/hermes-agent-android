# Latest-main follow-up: ddabd383

## Target

Upstream main advanced during the Cron delivery run. The final new-port and
built-in theme registry target is `ddabd3837862022a6053331fe3ba97cf9716b9e3`,
resolved from the remote main ref before final PR verification. The preceding
[scoped source audit](upstream-5fc490e2-2026-10-19.md) remains historical evidence
at `5fc490e2b34123212d73ef316086f939567a725a`.

## Delta inspected

The intervening commits change relay startup's launch-profile login refresh and
contributor attribution. `git diff --name-only <previous> <target>` enumerates
three paths. None is Desktop UI, theme data, core Cron REST/store or the session
admission contract used by this port. No relay change is ported by this repin.

An executed zero-diff comparison covers `apps/desktop`, `apps/shared/src`,
`hermes_cli/web_routers/cron.py`, `cron/jobs.py`, `hermes_cli/web_models.py` and
`hermes_cli/web_server_cron.py` between the two exact revisions. Registry names,
order, labels, descriptions, palettes and all captured Cron component source
bytes therefore remain identical. A new disposable detached checkout at the
final target is used for the live registry gate. Installed Hermes stays untouched.

## Evidence boundaries

- `AGENTS.md`, the offline registry ledger and new-port workflow defaults name
  the final target. Registry and citation gates are rerun against it.
- Core Cron's source/readback/router tests and unchanged Desktop captures retain
  their actual `5fc490e2` provenance. Android capture source remains
  `64d7cf1cbc208e912e5e801c3671ae0b643c6798`.
- No historical receipts, per-surface pins, semantic goldens or screenshot hashes
  are rewritten to pretend they were executed at the newer revision.
- This is a source/registry repin, not a backend update, new runtime test or full
  appearance compatibility certification. Existing #292 limits remain.
