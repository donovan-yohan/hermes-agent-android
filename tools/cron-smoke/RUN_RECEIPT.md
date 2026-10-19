# Executed smoke receipt

Upstream: `5fc490e2b34123212d73ef316086f939567a725a`.
Boundary: **actual upstream cron APIRouter + real stores and manual script fire**;
not full Gateway/native-auth acceptance.

## Test-first evidence

Each vertical slice was executed RED before implementation:

- Lifecycle: `FAIL: real-upstream server driver is not implemented`.
- Acceptance: `FAIL: real REST acceptance runner is not implemented`.
- Cross-language launcher: `FAIL: cross-language command launcher is not implemented`.
- Non-fixture state protection: `FAIL: AssertionError: 0 == 0` (previous host
  accepted the nonempty state; the new refusal made this test green).

An initial real startup also exposed `SessionDB` requiring a `Path`, not a string;
corrected in harness code. No upstream production file was patched.

## Final checks executed

```text
python -I -B -m unittest discover -s tools/cron-smoke -p 'test_*.py' -v
Ran 4 tests in 9.101s
OK
```

```text
python -I -B tools/cron-smoke/acceptance.py --upstream <pinned-checkout> --state-dir <new-empty-scratch>
status: passed
check_count: 17
agent_imports: 0
registration_failure_injected: false
```

Verified assertions: header gate; real `worker_alpha` discovery/store; `all`
listing and `default` separation; create/readback; update/schedule/model-pair
clear readback; pause/resume readbacks; delivery and blueprint discovery;
built-in blueprint instantiation; empty history; one script-only Trigger;
`HM_CRON_SCRIPT_OK` in history and output document; exactly one completed
execution row; actual process restart persistence; delete/list/file absence;
no token in any persisted fixture file.

`git diff --check` exited 0. Servers exited under caller-owned stdin lifecycle;
no daemon/ticker was started. Four tests include a real independent HTTP-client
command invoked through `with_server.py`.

## Remaining boundary

Production Kotlin `GatewayCronApi` has **not** been exercised by this worker.
The reusable host/command wrapper and exact parent-owned JVM invocation are in
`README.md`. No mobile source/test was changed. No external registration failure,
real provider, LLM, platform delivery or full Gateway startup was claimed.
