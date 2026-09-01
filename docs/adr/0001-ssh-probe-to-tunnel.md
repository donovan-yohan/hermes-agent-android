# ADR 0001 — App-owned SSH transport and remote Gateway lifecycle

**Status:** implemented for the Phase 2 vertical slice, 2026-08-19
**Amended:** 2026-08-24 — current per-runtime concurrency contract recorded below
**Source:** `docs/spikes/native-kotlin-ssh-client-scope.md` §5, §7
**Authority:** `NousResearch/hermes-agent` @
`29112bef099274229cadff79cdff7bf7b99c4b77`

## Context

Hermes Desktop can shell out to system OpenSSH and inherit machine config,
agents, and keys. Android cannot: this app cannot read Termux files or agent
sockets and has no supported system SSH binary to delegate to. The app must own
SSH transport while preserving Desktop's remote lifecycle and Gateway
contracts.

The Phase 1 probe established sshj, mandatory trust-on-first-use review,
changed-key refusal, one auth method with no fallback, in-memory credentials,
redaction, cancellation by socket close, and deterministic provider setup.
Phase 2 needs the authenticated connection to outlive one command.

## Decision

This is now the explicit **Managed SSH** route. It is not the default topology
for Desktop plus Mobile; ADR 0002 defines the host-owned Remote Gateway route.
No two app-owned servers may target the same effective `HERMES_HOME`.

Extend the existing internal `SshTransport` with only the capabilities consumed
by the vertical slice:

- bounded exec with optional stdin;
- a bind-and-hold loopback local forward;
- client-owned keepalive;
- transport close that closes active exec/socket/forward work.

`SshSessionOpener` performs the same crypto and host-key policy as the probe,
authenticates exactly once, clears credentials immediately, and transfers the
live transport to `GatewayConnectionManager`.

The connection manager owns this graph:

```text
SSH transport
  -> positively owned remote hermes serve
  -> 127.0.0.1 local forward
  -> spawn-token HTTP health and ownership
  -> served-dashboard token adoption plus exact child reinspection
  -> adopted-token JSON-RPC WebSocket and readiness request
```

Connected is published only after every leg succeeds. Close proceeds in the
reverse direction and clears the repository's live client.

## Remote lifecycle invariants

- Linux is the required physical target. Unsupported operating systems and
  architectures fail before process creation.
- A configured executable must be a strict absolute path. Otherwise discovery
  checks login-shell `command -v hermes` and the frozen candidate list, then
  verifies executability. This preserves the launcher path instead of rewriting
  it to an interpreter (`apps/desktop/electron/remote-lifecycle.ts:136-205` at
  the pinned SHA).
- `serve --help` must expose `ssh-session-token-file` and `ssh-owner-nonce`.
- The ownership namespace is a persistent random 32-hex install id. A spawn
  nonce is random 16 hex. Neither is derived from an endpoint.
- The ownership directory is `~/.hermes/desktop-ssh/<ownershipId>`, mode 0700.
  Remote Python opens that directory and validates its descriptor. The token is
  a bounded stdin read into a no-follow, exclusive, descriptor-relative regular
  file whose owner, link count, and 0600 mode are checked.
- The 32-byte random spawn token is encoded for the file and sent only through SSH
  exec stdin. It never appears in argv, persistence, UI, logs, errors, or test
  snapshots. Its immutable fingerprint remains the guarded token-artifact
  deletion proof even if the dashboard serves a different final token.
- Spawn uses `HERMES_DESKTOP=1`, optional profile before `serve`, `--isolated`,
  host `127.0.0.1`, port `0`, token-file path, and nonce under
  `setsid`/`nohup`.
- Readiness accepts only exact `HERMES_BACKEND_READY port=<n>` or
  `HERMES_DASHBOARD_READY port=<n>` lines from a bounded log.
- The ownership lock is written with `port: 0` before readiness is consumed.
  It remains at port zero until authenticated readiness, served-token adoption,
  and post-fetch exact child inspection have all passed. The final lock exactly uses
  schema version 2 and protocol version 1, the pinned camel-case required
  fields/types/bounds, canonical
  `~/.hermes/desktop-ssh/<ownershipId>/<nonce>.log` path, and a 32-hex truncated
  SHA-256 fingerprint of the token used for final authentication
  (`apps/desktop/electron/remote-lifecycle.ts:876-960` and
  `hermes_cli/dashboard_procs.py:722-783` at the pinned SHA).
- A pid receives TERM only when live argv proves one exact executable, `serve`,
  isolated flag, nonce, token path, and profile shape. Cleanup waits at most
  five seconds and needs two death observations before touching artifacts.
  There is no KILL escalation because pinned Desktop's lifecycle cleanup does
  not define one (`apps/desktop/electron/remote-lifecycle.ts:474-518`). A live
  or ambiguous process retains every artifact; a dead process gets
  descriptor/fingerprint/inode-guarded token cleanup and exact-lock-guarded
  log/lock cleanup. If the final atomic lock rewrite reports failure, cleanup
  accepts only the known port-zero or positive-port record and removes the
  nonce-scoped temporary record only after the same exact descriptor/body/inode
  proof.

## Forward and Gateway readiness

The local listener is bound directly to `127.0.0.1:0` and handed to sshj; there
is no pick-port-then-bind window. It is part of the SSH connection lifetime.

Readiness is ordered:

1. `GET /api/health` with `X-Hermes-Session-Token`;
2. `GET /api/ssh/ownership` with the same header and an exact `ok: true`,
   spawned `sshOwnerNonce`, and `protocolVersion: 1` match; the install
   ownership id remains a local cleanup namespace only;
3. unauthenticated `GET /` at the strict loopback-forward origin with redirects
   disabled, a three-second timeout, a bounded body, strict UTF-8, and only the
   exact injected `window.__HERMES_SESSION_TOKEN__=<JSON string>;` bootstrap;
4. exact live-child argv reinspection, deliberate spawn-token fallback when no
   valid served token is available, and the final lock write using the adopted
   token fingerprint;
5. `/api/ws?token=<encoded>` WebSocket upgrade using the adopted token;
6. correlated `session.list` JSON-RPC round trip.

This ordering ports pinned Desktop's served-token contract at
`29112bef099274229cadff79cdff7bf7b99c4b77`,
`apps/desktop/electron/dashboard-token.ts:10-101`,
`apps/desktop/electron/remote-lifecycle.ts:733-751,920-931`, and
`hermes_cli/web_server.py:17242-17310`. The public dashboard token may drift
benignly only while the exact spawned child remains owned; a mismatched token
after child death/replacement is refused as foreign.

A health 200 without WebSocket auth and JSON-RPC is not Connected. Requests
have bounded timeouts, close rejects pending calls, malformed unsolicited
frames and unknown events are ignored, malformed matching responses fail their
request, and protocol errors are typed without echoing secrets.

## Session identity and isolation

`SessionSummary.id` remains durable navigation identity. The live repository
holds a connection-scoped durable-to-runtime map: resume accepts a durable id;
activate, history, submit, and interrupt receive runtime ids only. Reconnect
clears the map.

An event carrying a runtime id maps directly. An unscoped live-turn event stays
on one safe runtime pin. Selecting another session does not retarget it;
completion can mark its source session unread. Distinct sessions may submit
concurrently because scoped events retain per-runtime ownership. A second
concurrent submit never steals the unscoped pin, and exactly one remaining
local runtime may inherit it after the prior owner settles.

## Current limitation: restart, not reuse

Safe reuse requires lock schema validation, exact ownership, live pid, argv,
profile/path/home/token-fingerprint checks, authenticated ownership HTTP, and
WebSocket readiness. This slice does not implement that full decision.

Reconnect therefore starts a fresh nonce and backend. It never kills a prior
or foreign pid without current positive argv proof. This can leave an owned
backend for its server-side isolation/reaping policy after an abrupt client
loss; it cannot turn incomplete evidence into a kill decision. Future reuse
must implement the full proof, not weaken it.

## Mobile lifecycle consequence

sshj keepalive is client-owned. Android default-network loss or handoff closes
the stale connect/SSH/forward/WebSocket state and exposes reconnect. Holding an
old network until TCP timeout is explicitly rejected.

No foreground service is included. The app does not claim durable background
connectivity, and the limitation belongs here rather than in primary product
copy.
