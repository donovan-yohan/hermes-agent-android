# ADR 0006 — Typed, revocable MCP selection HTTP capability

**Status:** host foundation only; no UI or deployment certification.
**Authority:** `NousResearch/hermes-agent` at
`36922ad064d65dcf25f8f48df81e1ccf9a55de67`. `36922` is a short Git SHA, **not a port**.
**Mobile base:** `5477daf02af33bccd6f614b0394458c1c9d427ca`.

## Source verification

A fresh network-created bare scratch clone of
`https://github.com/NousResearch/hermes-agent.git` fetched both the target above
and baseline `e05b16348b1d06a3311237423b0a4fc30d9c5aa1`. `git rev-parse
<commit>:<path>` returned identical Git blob IDs at both commits:

| Upstream path | Baseline and target blob ID |
| --- | --- |
| `hermes_cli/web_routers/mcp.py` | `32714e873dc57b476ec8461aa7a92aeb4469a016` |
| `hermes_cli/web_models.py` | `202a629694f77e7d4f72e05cb6ad191cf728e0e8` |
| `hermes_cli/web_server_mcp.py` | `98c6af570f7f274a45b5f29429e1eaf781709017` |

This is source-contract evidence, not a live Gateway test. No running Gateway,
dashboard, user host, credentials, or deployment availability was consulted.

## Route and preservation contract

At the target pin:

- [mcp.py:94–107](https://github.com/NousResearch/hermes-agent/blob/36922ad064d65dcf25f8f48df81e1ccf9a55de67/hermes_cli/web_routers/mcp.py#L94-L107)
  defines `GET /api/mcp/servers?profile=<name>` and the `{servers:[...]}` envelope.
- [web_server_mcp.py:84–105](https://github.com/NousResearch/hermes-agent/blob/36922ad064d65dcf25f8f48df81e1ccf9a55de67/hermes_cli/web_server_mcp.py#L84-L105)
  emits name, Boolean enabled, source and additional sensitive transport metadata.
- [web_models.py:350–352](https://github.com/NousResearch/hermes-agent/blob/36922ad064d65dcf25f8f48df81e1ccf9a55de67/hermes_cli/web_models.py#L350-L352)
  defines `MCPEnabledToggle(enabled, profile)`.
- [mcp.py:372–395](https://github.com/NousResearch/hermes-agent/blob/36922ad064d65dcf25f8f48df81e1ccf9a55de67/hermes_cli/web_routers/mcp.py#L372-L395)
  defines `PUT /api/mcp/servers/{name}/enabled`. Under `config_write_scope`, it
  loads the existing config, refuses plugin-owned/unknown/malformed servers,
  changes only `servers[name]["enabled"]`, and saves that same config. All other
  server fields, other servers, unknown keys and unrelated config remain in the
  loaded structure. This preservation claim comes from server implementation,
  not from replacing config or from a mocked persistence test.

Android sends a percent-encoded single name segment, query `profile`, and body
exactly `{enabled: Boolean, profile: String}`. It never sends a server map,
command, args, URL, environment, tools list, or general config patch. This is
persisted selection for the next session/gateway, **not live MCP health, tool
availability, immediate reconnect, or proof of successful server execution**.

## Host wiring and scope

`PluginHost.mcp` exposes only `openScope(endpoint, profile)`, `listServers`,
`setEnabled(name, enabled)`, and irrevocable `close`. Ordinary hosts and plain
unguarded HTTP adapters return `MissingCapability`. `PluginRest` remains
namespaced; there is no RPC/CLI/SSH/config-file fallback.

This follows [ADR 0005](0005-scoped-core-skills-http.md). The real
`HermesApplication.kt:290–297` host factory already injects the connection-owned
HTTP slot, cache endpoint generation and the same application
`EndpointDispatchFence` used for switching. `GatewayPluginHost` constructs MCP
from these same inputs, alongside Skills; it creates no new production fence.
`GatewayConnection.kt:743–747,963–970,1131–1138` captures respectively Local's
validated base/authorized leg, Remote's original profile base/token resolver,
and managed SSH's original forwarded endpoint/eager session token. A connected
RPC socket does not establish HTTP availability on any of those routes.

Each scope captures the exact guarded HTTP object, endpoint lease and explicit
profile synchronously. The serving-profile alias `current` is refused. At actual
OkHttp `Call.enqueue`, the shared endpoint fence and then scope monitor gate
admission. Closing A and opening B then A cannot revive the original scope.
Credential and dispatcher suspension cannot retarget to replacement transports.
Locks are released immediately after enqueue, never held through a response.
Answers recheck ownership before publication.

Enqueue is queue admission, not kernel socket transmission. Already admitted
requests may arrive after revocation; cancellation cannot prove rollback.
Failures after admitted PUT carry `mutationMayHaveApplied=true`. Consumers must
retain pending-operation ownership across reopening, reconcile with fresh reads,
and never automatically replay an uncertain/cancelled mutation. The foundation
provides neither server CAS nor protection from another client changing config
after preflight or after readback.

## Typed projection, preflight and verification

Only exact bounded name, literal Boolean enabled and closed-enum source escape
the host. `config` becomes `Config`; `plugin` becomes `Plugin`; missing, malformed
or unknown provenance becomes `Other`, never arbitrary source text. Only
`Config` is writable. Names are not normalized: blank/trim-changing/control,
redaction-changing, overlong, slash/backslash and dot-only path segments fail
closed. Duplicate rows and non-Boolean enabled values reject the inventory.

Each mutation reads the same named profile first, requires a matching config
server, sends one named delta, validates literal `ok=true`, exact name and exact
Boolean enabled in the receipt, then reads the same profile afresh. Only matching
name/enabled/**Config source** readback is success. An echo, missing target,
malformed receipt, unchanged enabled state or replacement plugin is not proof.

A scoped collection 404 triggers only a bounded unscoped **GET** availability
probe. Only its 404 establishes `UnavailableOnGateway`; success, authorization
failure or inconclusive probes preserve scoped refusal. Unscoped inventory is
never parsed or published. Missing PUT is a refusal, never a destructive probe.
Redirects, authenticators and connection retries stay disabled by the existing
guarded transport; one-shot bodies also prevent 408/503 follow-up replay.

URL/command/args/env/plugin identity/other raw metadata are discarded inside the
host, never returned to plugins or written into plugin logs, storage, or stores.
Failures are enums, not server bodies or exception messages. The original
HTTP response **does enter process memory**, including immutable JSON strings
and pooled Okio buffers; wiping transferred byte arrays does not erase all
runtime copies. This is a projection boundary, not a raw-HTTP secrecy/erasure
claim. No new request or response logging is introduced.

## Verification scope

`PluginMcpTest` exercises real host wiring, encoded names, exact scoped request
bodies, metadata canaries, receipt validation and readback provenance.
`PluginMcpSafetyTest` exercises production OkHttp adapters with synthetic
interceptors/controlled scheduling: unsupported hosts, read-only availability,
unknown names, non-config sources, malformed/duplicate rows, endpoint ABA,
scope close/reopen, transport retirement, held PUT replies, cancellation,
raw-error suppression and uncertain mutation rejection.

Existing `GatewayHttpDispatchTest` remains the transport regression for real
**test-owned synthetic** HTTP 302/307/503 responses; it is not a Gateway or
deployment check. Focused MCP/Skills/host/REST/HTTP tests and serial full
`check assembleDebug` are the acceptance gates. No UI, catalog, install, delete,
OAuth, server health probe, deployment or live mutation belongs to this change.

Verified locally with no concurrent Gradle build, `--no-daemon --no-parallel
--max-workers=1`, `-Dorg.gradle.jvmargs='-Xmx6g -XX:MaxMetaspaceSize=2g
-Dfile.encoding=UTF-8'` and `-Pkotlin.compiler.execution.strategy=in-process`:

- Focused `:app:testDebugUnitTest` filters `*PluginMcp*`, `*PluginSkills*`,
  `*PluginHostTest`, `*PluginSdkTest`, `*GatewayHttp*`: 85 tests, no failures,
  errors or skips; 18 are MCP tests.
- Full `check assembleDebug`: **BUILD SUCCESSFUL**. Debug XML: 3,416 tests,
  zero failures/errors, one skip. Release XML: 2,719 tests, zero failures/errors,
  one skip. Counts include each variant's execution, not unique test identities.
- Debug APK produced at `app/build/outputs/apk/debug/app-debug.apk`.
- RED runs observed missing MCP host/API, then the non-config mutation/path
  guards, then readback source substitution; corresponding GREEN runs passed.
- Existing compiler/experimental/deprecation warnings remain in unchanged files.
