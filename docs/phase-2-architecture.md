# Phase 2 — live remote Gateway vertical slice

Authorities: `NousResearch/hermes-agent` at
`29112bef099274229cadff79cdff7bf7b99c4b77` for the original Desktop/SSH
contract and `59795c40fff95b3029b8f2b02164da892429070f` for native Remote
Gateway authentication. The pinned source and tests were inspected read-only;
the checkout was neither modified nor fetched.

## What is real

| Area | Current behavior |
|---|---|
| Remote Gateway | Default and recommended route. Connects to one host-owned authenticated Gateway without SSH process ownership. Gated native PKCE, Keystore-encrypted tokens, bearer REST, a fresh single-use WebSocket ticket, and a JSON-RPC readiness request are required. |
| SSH | sshj opens one verified connection using exactly the selected auth method. First use stops before auth; a changed key has no accept path. |
| Remote lifecycle | Linux/architecture gate, login-shell Hermes discovery, executable and capability checks, descriptor-validated stdin-only token upload, detached loopback `hermes serve`, exact readiness marker, served-dashboard token adoption, the shared ownership-lock schema, and positive argv/death proof before cleanup. |
| Forward | The listener is bound and held on `127.0.0.1:0` before sshj receives it. It closes with the connection. |
| Gateway readiness | Authenticated `/api/health` and `/api/ssh/ownership` use the spawn token to prove the spawned nonce and protocol version `1`; a bounded public-root fetch then adopts the token actually served by that still-owned child for the final lock and WebSocket, followed by an authenticated `session.list` round trip. HTTP 200 alone is not Connected. |
| Sessions | Live list, create, resume/activate, history, send, stream, tools, status, error, and interrupt. Durable navigation ids and runtime ids are separate. |
| Projects | Gateway-authored `projects.tree` overview plus `projects.project_sessions` drill-in. Android does not infer membership from cwd; older Gateways retain the flat session list. |
| App graph | `HermesApplication` owns the process-scoped connection, remote authenticator/token store, repository, network monitor, and backend-authoritative `SessionCache`. Production startup seeds nothing. |
| UI | Gateways defaults to Remote Gateway URL/provider and browser sign-in. Managed SSH is a separate fallback with destination, optional remote Hermes profile, one selected auth method, host-key review, concise status/connect controls, and a secondary SSH diagnostic. Chat reports the same short connection states. |

## Connection sequence

### Remote Gateway (recommended)

1. Normalize the HTTPS base URL; reject cleartext HTTP, credentials, query, and fragment.
2. Require a gated `/api/status` advertising `native_pkce`.
3. Load the endpoint-scoped token envelope from Android Keystore-encrypted,
   no-backup storage. Refresh 60 seconds before expiry.
4. When sign-in is needed, bind `127.0.0.1:0`, generate PKCE and CSRF state,
   then open the system browser. Accept one bounded callback, validate state,
   exchange the code, and close the listener.
5. Request a fresh single-use ticket from `/api/auth/ws-ticket` with the bearer
   access token. One rejected token gets one refresh/sign-in retry.
6. Open `[/prefix]/api/ws?ticket=<ticket>` and require a correlated
   `session.list` before publishing Connected.

This route never starts, owns, or reaps `hermes serve`. See
`docs/adr/0002-shared-remote-gateway.md` for the multi-client boundary.

### Managed SSH

1. Parse `user@host[:port]` into the canonical non-secret `HostProfile`.
2. Open SSH and complete mandatory host-key verification before authentication.
3. Clear the mutable credential copy as soon as authentication returns; the
   authenticated transport continues without it.
4. Require Linux and a supported architecture. Resolve the configured absolute
   Hermes path strictly, or discover and verify a known candidate.
5. Require `hermes serve --help` to expose `ssh-session-token-file` and
   `ssh-owner-nonce`.
6. Generate a 32-byte random token and 8-byte nonce. Upload the encoded token
   only over exec stdin to a bounded, exclusively-created `0600` regular file
   opened with no-follow semantics under the pinned server allowlist at
   `$HOME/.hermes/desktop-ssh/<ownership>/<nonce>.token`. This consumed staging
   path is deliberately independent of the login shell's `HERMES_HOME`.
7. Read the login-shell `HERMES_HOME` (falling back to `$HOME/.hermes`) and
   explicitly select its Hermes root as the effective lifecycle home: that
   root for `--profile default`, or its existing `profiles/<name>` child for a
   selected profile. The child environment, ownership lock, log, and orphan
   reaper all use that same effective home. Write the schema/protocol lock and
   log below its `desktop-ssh` directory with `port: 0` before consuming
   readiness. Start `env -u HERMES_PROFILE HERMES_HOME=<effective-home>
   HERMES_DESKTOP=1 hermes --profile <default-or-name> serve --isolated --host
   127.0.0.1 --port 0 …` with `setsid`/`nohup`, and accept only the two exact
   readiness markers.
8. Open the bind-and-hold loopback forward. Use the spawn token only for
   authenticated HTTP health and exact ownership proof.
9. Fetch only `http://127.0.0.1:<localPort>/`, without redirects, authentication,
   or a query, under a three-second timeout and bounded body. Accept only the
   exact injected JSON-string bootstrap emitted by pinned Hermes. A missing,
   malformed, oversized, or failed response deliberately falls back to the spawn
   token. After the fetch, re-prove the exact live child argv before accepting
   any drift; a mismatched token from a dead/replaced child is foreign.
10. Write the positive port and adopted-token fingerprint to the final lock,
   then use that adopted token for WebSocket auth and one JSON-RPC request.
11. Publish the RPC client. The repository refreshes Gateway sessions and maps
   live events into `SessionCache`.

Any failure closes RPC, forward, owned remote process where ownership can be
proved, and SSH. Tokens are never placed in argv, UI, persistence, log messages,
or exception text.

## State and identity

| Authority | Home | Invariant |
|---|---|---|
| Backend sessions/transcripts | `SessionCache` | Partial refreshes merge. Only an explicit tombstone removes a row. No-op upserts preserve state identity. |
| Project catalog/membership | `SessionCache` | Overview snapshots replace the catalog; selected-project snapshots replace that project's membership. Rehome and tombstone updates publish atomically with session identity. |
| Remote or SSH/process/forward/RPC | `GatewayConnectionManager` | One process-scoped active connection. Remote close ends only RPC; Managed SSH close tears down every positively-owned leg. |
| Durable to runtime ids | `LiveGatewaySessionRepository` | Mapping is connection-scoped and cleared on reconnect. Runtime-only RPC methods never receive durable ids. |
| Stream ownership | `LiveGatewaySessionRepository` runtime guards | Scoped events route to their runtime. An event without `session_id` remains on one safe runtime pin rather than following the visible session; concurrent distinct-session submits are allowed, and pin inheritance occurs only when exactly one remaining local runtime is safe. |
| Draft text | `SessionDraftStore` + `ChatViewModel` | Private local UI data keyed only by canonical durable session id. Dedicated no-backup DataStore; text only, 50-entry MRU cap; never backend/cache truth. |
| Search/project selection/navigation notice | `ChatViewModel` | Screen-lifetime UI state; never written into backend cache or the draft store. |
| Connection configuration | `HermesPreferences` | Route, non-secret Remote URL/provider, or SSH host, port, username, optional remote profile, auth method, accepted fingerprint, and random install ownership id only. OAuth tokens are not DataStore values. |

Rename and archive are absent from the product surface because this slice does
not wire authoritative backend methods for them. Search is explicitly local
filtering. Project overview and drill-in use authoritative Gateway membership;
the selected project's backend path is used as the cwd for a new session.
Create, open, history, send, and stop are live Gateway operations.
The user can switch sessions while a turn runs. The same target session remains
busy, while another idle session can submit concurrently. Scoped events route
per runtime; identifier-less events remain on the single safe local pin instead
of being painted into the session currently on screen.

## Remote ownership and current restart policy

The ownership namespace is a persistent random 32-hex install id, never an
endpoint. Each spawned backend gets a random 16-hex nonce. The lock is the
shared schema accepted by pinned Desktop and Hermes' orphan reaper:
`schemaVersion: 2`, `protocolVersion: 1`, camel-case `ownershipId` and
`spawnNonce`, bounded pid/port/string fields, the exact spawn-log suffix, and a
32-hex truncated SHA-256 final authentication-token fingerprint. Its other
required fields are the string profile, Hermes path/effective home, a
`desktop-ssh/<ownershipId>/<nonce>.log` path below that home, and start
timestamp. A custom-root lock records its actual absolute `logPath`; a
default-root record may use canonical `~/.hermes` spelling when that is how the
implementation encodes it. The spawned child receives that same effective home
and explicit profile, so Hermes' orphan reaper scans the directory containing
the lock even when the SSH login shell selected a non-default `HERMES_HOME`.
The uploaded token artifact retains its own immutable fingerprint for guarded
deletion even if the served token is adopted for the final lock. This contract
comes from `NousResearch/hermes-agent` at
`29112bef099274229cadff79cdff7bf7b99c4b77`,
`apps/desktop/electron/remote-lifecycle.ts:32-60,292-370,876-960` and
`hermes_cli/dashboard_procs.py:722-783`.

Served-token resolution follows the same pinned source at
`apps/desktop/electron/dashboard-token.ts:10-101`,
`apps/desktop/electron/remote-lifecycle.ts:733-751,920-931`, and
`hermes_cli/web_server.py:17242-17310`: the public dashboard is authoritative
for the token it injects, but token drift is accepted only while the exact
lock-owned child remains alive.

The install ownership id is only a local lifecycle-cleanup namespace; it is
not a Gateway readiness field. Readiness requires the authenticated ownership
response to contain `ok: true`, the exact spawned `sshOwnerNonce`, and
`protocolVersion: 1` as defined by
`NousResearch/hermes-agent` at
`29112bef099274229cadff79cdff7bf7b99c4b77`,
`hermes_cli/web_server.py:3445-3450`.

This slice does **not** reuse an existing lockfile process. Reconnecting starts
a new nonce and process. Cleanup sends TERM only after live
`/proc/<pid>/cmdline` proves one exact executable, `serve`, isolated flag,
nonce, token path, and profile shape. It then waits a bounded five seconds and
requires two death observations before removing anything. It does not escalate
to KILL: pinned Desktop's lifecycle contract uses TERM plus a bounded wait
(`apps/desktop/electron/remote-lifecycle.ts:474-518` at the same SHA). A live,
foreign, replaced, or ambiguous process retains every artifact. After confirmed
death, token removal additionally proves the no-follow regular file's owner,
mode, size, fingerprint, and inode; log/lock removal requires the exact current
lock body to match one of the at-most-two records known around the final atomic
rewrite. A nonce-scoped temporary lock is removed only when it independently
passes the same descriptor, mode, size, exact-body, and inode checks. Safe reuse
can be added later without weakening those proofs.

## Mobile lifecycle boundary

The SSH client owns keepalive. A default-network handoff or loss cancels an
in-progress connect or closes the active SSH/forward/RPC graph and exposes
Needs attention with a reconnect action.

There is no general foreground service for the Gateway connection. The
user-started wake-word service is narrowly scoped to microphone listening and a
persistent notification. Android may still suspend or stop the app, so
uninterrupted background Gateway operation is not claimed. A process restart
begins disconnected and reconnects explicitly.

## Offline evidence and physical-device gap

Focused tests cover request correlation, timeout/close rejection, event and
malformed-frame parsing, command/path rejection, token placement, strict served
token parsing/fallback/body wiping, adopted-token WebSocket and final-lock use,
readiness, ownership decisions, no foreign-pid kill, transport cancellation,
loopback binding, session mapping, durable/runtime identity, stream pinning,
project ordering/membership/drill-in/reconnect, submit/interrupt, empty
production startup, concise Gateway copy, and truthful connection UI.

Offline fakes prove contracts at network/process seams; they do not prove a
particular remote installation or radio. Exact-head physical Pixel validation
still requires a device, a deliberately configured non-personal test Gateway,
and credentials supplied outside the repository. Required device checks are:

- first-use review, reconnect, and changed-key hard stop;
- remote start plus authenticated HTTP/WebSocket readiness;
- real list/history/create/turn/interrupt;
- Wi-Fi/cellular handoff teardown and reconnect;
- IME/insets, TalkBack, and rendering on the target Pixel.
