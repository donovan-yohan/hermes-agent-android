# live-gateway-smoke

An **opt-in** lane that pairs the production Android Gateway client with a real
upstream Hermes Gateway process, over a real WebSocket, with no fake in between.

Everything else in `app/src/test/.../data/gateway/` answers with a fake wire,
which is what makes those tests deterministic and also why they cannot show a
capability that dies with the socket. This lane starts an actual `hermes serve`
backend and drives it with `OkHttpGatewayRpcClient`, so the capability
advertisement is genuinely re-sent on every connection instead of assumed.

It is not part of `check` or CI. `check` runs on an Android runner with no Python
toolchain and no upstream snapshot, and the lane deliberately never starts a
Gateway unless it is asked to.

## Frozen upstream

The Gateway side must be the snapshot pinned by this slice:

```
d177b119e9c56c9ddc0b7379ffce52341ec06584
```

The driver reports, and the test asserts, the commit it imported from plus the
module paths the running server was actually loaded from, so a stand-in that
merely emits the same first frame cannot pass as the pin. Point the lane at a
different commit and the SHA assertion fails on purpose.

## Prerequisites

Python 3.11–3.13, and `uv`. From a **read-only** checkout of the pin above, build
a disposable environment from its own lockfile — install nothing globally:

```bash
SNAPSHOT=/path/to/hermes-agent@d177b119   # read-only; never fetch or check out in it
VENV=$(mktemp -d)/venv
UV_PROJECT_ENVIRONMENT="$VENV" uv sync --frozen --no-install-project --project "$SNAPSHOT"
```

`uv sync --frozen` resolves exactly what the snapshot pinned. This was verified
against that SHA with `uvicorn` 0.41.0 and `websockets` 15.0.1.

Nothing in this directory needs to be built or installed.

## Running it

```bash
./gradlew :app:testDebugUnitTest --tests '*LiveGatewaySmokeTest*' \
  -Dhermes.liveGateway=1 \
  -Dhermes.liveGateway.driver="$PWD/tools/live-gateway-smoke/hm307_control_driver.py" \
  -Dhermes.liveGateway.project="$SNAPSHOT" \
  -Dhermes.liveGateway.python="$VENV/bin/python"
```

`app/build.gradle.kts` forwards exactly those four properties into the test JVM;
`-D` alone does not reach it. Keep `ANDROID_HOME` pointed at an SDK with platform
36 and run on JDK 17, as the rest of the module does.

| Property | Meaning |
|---|---|
| `hermes.liveGateway` | Opt-in flag. Absent ⇒ the test is **skipped**, not passed. |
| `hermes.liveGateway.driver` | `hm307_control_driver.py` in this directory. |
| `hermes.liveGateway.project` | Read-only snapshot root the server is imported from. |
| `hermes.liveGateway.python` | Interpreter of the disposable venv above. |

Once opted in, a missing or unreadable path is a **failure**, not a skip, so a
broken invocation cannot look like a clean one.

## What it asserts

Against the real server, read from the server's own settlement:

- `gateway.ready` arrives and the production client parses it;
- `client.capabilities {server_requests: true}` is acknowledged with the server's
  real capability list, so the pin registers the method rather than answering
  `-32601`;
- a real `srq-` request parked on the advertised connection arrives carrying its
  `session_id`, and the client's response frame settles the server's blocking
  `send()` with the answer it sent;
- a connection that never advertised receives **no frame at all** (asserted after
  the server's own 10s deadline);
- a fresh connection is answered again — the advertisement dies with the socket.

Removing the `advertiseServerRequests()` call makes the round trip time out.

## Redaction and cleanup contract

The harness holds itself to the same rules the rest of the Gateway code does:

- **Token.** Minted per run into the child's `HERMES_DASHBOARD_SESSION_TOKEN`.
  Never printed, logged or written to a file. The child's environment is built
  from an empty map — no profile, no config, no inherited provider credential.
  The driver also re-cleans its own environment before importing the server.
- **Captured stderr.** The real uvicorn access log prints the request line, and
  `/api/ws` carries `?token=`. The driver scrubs its own fd 2 of the run token
  before the server import; the JVM side scrubs again before anything reaches an
  assertion message; the test-only diagnostics record host, port and path only.
- **Isolation.** A throwaway `HERMES_HOME` and `HOME` per run, loopback bind only,
  a harness-allocated port, and a bounded wait. The driver sets
  `sys.dont_write_bytecode`, so the snapshot gets no `__pycache__`.
- **Reaping.** The child is stopped in `finally`; a run that fails to reach its
  readiness marker is torn down rather than left behind. Temp directories are the
  platform's, not the repo's.

## Limits — what this is not

- **Not model, tool or physical-prompt acceptance.** The driver injects a
  deterministic `clarify`, so this is real *protocol* integration, not an LLM turn.
- **Not proof the manager advertises automatically.** The test calls
  `advertiseServerRequests`, the function the production connection paths call;
  that those paths reach it is the deterministic manager tests' claim.
- **No reconnect replay** (`session.resume` / `session.events.since`
  `open_requests`) — the live-frame path only.
- **Robolectric, no emulator or device.**
