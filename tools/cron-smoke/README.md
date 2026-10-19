# Real-upstream cron REST smoke

**Boundary:** untouched `hermes_cli.web_routers.cron.router` mounted on a minimal
FastAPI host, pinned to **`5fc490e2b34123212d73ef316086f939567a725a`**. This is
real REST, profile discovery, `cron/jobs.json`, scheduler manual-fire execution,
`executions.db`, output documents and run history. It is **not** full Gateway
startup, native OAuth/session authentication, WebSocket notification delivery,
recurring scheduler execution, external provider registration or Android UI
acceptance. The outer header gate is harness-only; no production route response
is replaced or mocked. No injected 424 scenario is implemented.

## Files

- `server.py`: reusable host; port-only readiness protocol, stdin-owned lifetime.
- `acceptance.py`: Python REST acceptance and restart/persistence verification.
- `with_server.py`: owns the server while an arbitrary JVM/test command runs.
- `test_*.py`: stdlib unittest coverage for lifecycle, acceptance and command handoff.

Use a disposable read-only upstream checkout at the exact pin and a Python 3.14
environment with its **frozen upstream web dependencies** installed. No live
Hermes installation, user configuration or credentials are needed. The test
runner itself uses only the standard library; the server needs upstream deps.

Set these non-secret paths to your existing disposable checkout/environment:

```sh
export HM_CRON_SMOKE_UPSTREAM=/absolute/disposable/upstream
export CRON_PYTHON=/absolute/disposable/venv/bin/python
export TMPDIR=/absolute/authorized/scratch/cron-live-evidence
mkdir -p "$TMPDIR"
```

From the mobile integration checkout:

```sh
"$CRON_PYTHON" -I -B -m unittest discover \
  -s tools/cron-smoke -p 'test_*.py' -v

# Each acceptance invocation requires a NEW EMPTY directory.
run_dir=$(mktemp -d "$TMPDIR/acceptance.XXXXXX")
"$CRON_PYTHON" -I -B tools/cron-smoke/acceptance.py \
  --upstream "$HM_CRON_SMOKE_UPSTREAM" --state-dir "$run_dir"
```

The acceptance process prints and retains `receipt.json` containing only fixture
labels, the upstream pin, checked boundaries and results — no token, URL or local
path. It verifies create/detail/edit/model-pair-clear/pause/resume/delete readbacks,
concrete `worker_alpha` vs `default` stores and `all` listing, actual delivery and
blueprint discovery, built-in blueprint instantiation, initially empty history,
**one script-only trigger**, exact `HM_CRON_SCRIPT_OK` output, one completed
execution ledger row, process restart persistence and no credential on disk.
Deleted job records are verified absent while output/ledger evidence is retained.

## Production Kotlin CronApi handoff

No mobile-owned test source is changed by this harness. Add an **opt-in JVM test**
in the normal owner-controlled data test slice. Read both environment variables
and skip when absent:

- `HM_CRON_SMOKE_URL`: loopback URL, ephemeral port.
- `HM_CRON_SMOKE_TOKEN`: ephemeral `X-Hermes-Session-Token` value.

Construct the **production** `GatewayHttp` using that URL and in-memory header
resolver, then production `GatewayCronApi`, an endpoint StateFlow and real dispatch
fence. Exercise `openScope(endpoint, "worker_alpha")`, jobs/discovery/create,
update, Pause/Resume/Delete and readbacks. No mock HTTP response queue belongs in
this test. Keep the token out of Gradle properties, test names, assertions, logs,
XML output and process arguments. Do not print response bodies (upstream records
include fixture filesystem paths).

```sh
run_dir=$(mktemp -d "$TMPDIR/kotlin.XXXXXX")
"$CRON_PYTHON" -I -B tools/cron-smoke/with_server.py \
  --upstream "$HM_CRON_SMOKE_UPSTREAM" --state-dir "$run_dir" -- \
  ./gradlew :app:testDebugUnitTest --tests '*GatewayCronLiveTest*' \
  --no-daemon --no-parallel --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process
```

`GatewayCronLiveTest` is supplied in the normal data-test source set. It has
executed against this host using production `GatewayHttp` and `GatewayCronApi`,
including trimmed/generated names, a natural-language schedule and both retained
finite and recurring script-trigger completion. Ordinary non-opted test runs
skip it. The presence of the smoke URL disables Gradle test-result caching.
Set the repository's usual JDK/Android SDK environment before invoking Gradle.
The server gets a separate empty-allowlist environment even though the Gradle
child inherits build settings. Command exit status is preserved; the server is
always stopped in `finally`.

The public `CronDraft` does not create script-only jobs. To test production
`act(Trigger)` safely, seed a script-only job through **the actual REST create**
endpoint in the test (not a fixture response), then let production `detail` load
it and production `act` trigger it. The server pre-creates `scripts/harmless.sh`
for each scratch profile. Example JSON, always with `?profile=worker_alpha`:

```json
{
  "name": "Cron smoke fixture",
  "prompt": "",
  "schedule": "0 9 * * *",
  "deliver": "local",
  "model": null,
  "provider": null,
  "script": "harmless.sh",
  "no_agent": true,
  "repeat": "forever",
  "paused": false
}
```

Then inspect `/api/cron/jobs/{id}/runs?profile=worker_alpha`. The real result is a
`cron_output` row, **not** a conversation session. Assert the output marker and
non-conversation behavior; do not fabricate a source=`cron` session. Manual jobs
and built-in blueprint jobs may be created/edited/deleted, but never trigger an
agent-mode job. Both import guards and outbound-network guards reject accidental
provider execution; they do not stand in for scheduler results.

## Direct server protocol

A caller may instead generate an unpredictable token **in memory**, pass it in
`HM_CRON_SMOKE_TOKEN`, and launch:

```text
<python> -I -B tools/cron-smoke/server.py --upstream <pin-checkout> --state-dir <empty-scratch-dir>
```

Keep stdin/stdout pipes owned. Read the single stdout readiness line:
`HM_CRON_SMOKE_PORT=<decimal-port>`. Connect to `127.0.0.1`, add the header above,
then send `quit\n` on stdin and wait for exit. EOF also stops it. Access logging is
off. Unknown/non-fixture nonempty state is refused; marked harness state may be
reopened for persistence checks. Store state under the authorized scratch tree.
The server cannot be pointed at a real profile directory as an unmarked state.

### Isolation and limits

Before any upstream import the driver re-execs with an explicit small environment:
`HOME`, `HERMES_HOME`, temp paths, fixed locale/timezone, system-only PATH, bytecode
disabled, its own isolation marker and ephemeral token. It never reads the caller's
`.env` or live profile. Both default and named profile homes are beneath its own
scratch `home/.hermes`. Upstream profile discovery is real and checked at startup.
No scheduler ticker or gateway process is started. The built-in provider handles
registration as its actual no-op, and executes the one manual script fire normally.

This is profile/storage isolation, **not an OS security sandbox**. Use only the
reviewed pinned upstream source and harmless fixture script. Never put real
credentials or a symlink to live state inside the scratch fixture. The fixture
script is exactly a shell `printf`; it invokes no LLM. The server rejects non-loopback
outbound Python socket connects and imports of `run_agent`.
