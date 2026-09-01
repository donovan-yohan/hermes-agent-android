# ADR 0002 — Host-owned Remote Gateway with native authentication

**Status:** implemented as the default connection route, 2026-08-20
**Terminology amended:** 2026-08-24 — product label changed from Shared Gateway
to Remote Gateway; the ownership and authentication decision is unchanged
**Amended:** 2026-08-29 — the Local route addendum below extends the same
host-owned boundary to a Termux Gateway on this device
**Authority:** `NousResearch/hermes-agent` @
`59795c40fff95b3029b8f2b02164da892429070f`

## Context

ADR 0001 deliberately gives one Android install positive ownership of one
SSH-managed `hermes serve` child. That lifecycle is safe only while it is the
sole owner of its effective `HERMES_HOME`.

Running Desktop-owned and Mobile-owned `hermes serve` processes over the same
profile is not a shared-Gateway design. Process-local turn state and the shared
interrupted-turn marker can allow both processes to resume the same turn. The
result is duplicate execution across one compression lineage, and interrupt or
steer on one WebSocket cannot control the other process.

Hermes already exposes the transport contract needed by native clients:
Gateway-brokered RFC 8252 PKCE tokens, bearer-authenticated REST, and fresh
single-use WebSocket tickets. Mobile should attach to one host-owned Gateway
instead of creating another server.

## Decision

The default and recommended route is **Remote Gateway**:

```text
Desktop ───────┐
               ├── one host-owned hermes serve ── one profile / state.db
Android ───────┘
```

Android stores only a non-secret base URL and optional provider in excluded
DataStore. It never adopts process ownership, uploads spawn tokens, records a
remote pid, or reaps the server. ADR 0001 remains available as the explicitly
separate **Managed SSH** route for a private, app-owned backend.

The process-scoped `GatewayConnectionManager` still publishes one RPC client.
Its active connection is now a sealed choice:

- Remote Gateway: close the authenticated WebSocket only;
- managed SSH: close RPC, forward, positively-owned process, and SSH transport.

Changing route or endpoint first disconnects the active route. The two security
models do not share lifecycle state.

## Native authentication sequence

Source contract:

- `apps/desktop/electron/native-oauth.ts`
- `apps/desktop/electron/native-oauth-login.ts`
- `hermes_cli/dashboard_auth/routes.py:248-423,927-961,965-1097`
- `tests/hermes_cli/test_dashboard_auth_native_flow.py`

all at authority `59795c40fff95b3029b8f2b02164da892429070f`.

1. Normalize an HTTPS Gateway base URL. Reject cleartext HTTP, userinfo, query, and fragment;
   preserve a reverse-proxy path prefix.
2. Read `/api/status`. Remote mode fails closed unless authentication is gated
   and `auth_flows` contains `native_pkce`.
3. Load the endpoint-scoped token envelope. Android Keystore holds a
   non-exportable AES-256/GCM key; ciphertext is stored below
   `noBackupFilesDir/gateway-auth`. Access tokens, refresh tokens, and user ids
   never enter DataStore, logs, UI, exception text, or generated `toString()`.
4. Refresh 60 seconds before expiry. A rejected refresh falls back to one
   browser sign-in.
5. For browser sign-in, bind `127.0.0.1:0` before opening the system browser,
   generate independent PKCE verifier and CSRF state, and request
   `/auth/native/authorize`. The listener accepts one bounded HTTP request,
   validates exact state, and closes. Login is bounded to five minutes.
6. Exchange the one-time code at `/auth/native/token`; encrypt the replacement
   token envelope atomically.
7. POST `/api/auth/ws-ticket` with `Authorization: Bearer <access token>`.
   A 401/403 gets one refresh/sign-in retry. Every socket receives a newly
   minted ticket; tickets are never reused.
8. Open `[/prefix]/api/ws?ticket=<encoded>` and require a correlated
   `session.list` request before publishing Connected.

The loopback callback is current Gateway compatibility, not an embedded
WebView. It uses a literal loopback address, an ephemeral port, PKCE, state,
and a listener opened only for the authorization request. A future Gateway
contract may add an Android App Link callback; adopting it must retain the same
PKCE/token/ticket boundaries rather than introducing cookies or an embedded
browser.

## Multi-client boundary

One shared process removes cross-process duplicate execution. At authority
`59795c40`, however, one live runtime session still has one event transport.
Resuming or activating the same running session from a second client can replace
the first client's stream. Upstream issue `#86784` adds fan-out attachment but
is not part of this pin.

Until equivalent support is present, the product states the limitation beside
the Remote Gateway route: do not open or control the same running session from
Desktop and Mobile simultaneously. Shared process ownership is implemented;
concurrent multi-controller policy is not claimed.

## Consequences

- Desktop and Mobile authenticate independently to one process and one session
  database.
- Network changes close the socket and require reconnect; they never restart or
  kill the host-owned Gateway.
- Managed SSH remains available without weakening TOFU, credential wiping, or
  positive process-ownership cleanup.
- A Gateway without gated `native_pkce` support is refused with an update/auth
  action instead of a token, cookie, or WebView fallback.
- Physical validation must still prove the system browser can reach the Android
  loopback listener on the supported device/browser combinations and that a
  real gated Gateway completes refresh and one-time ticket reconnects.

## Addendum — the Local route, 2026-08-29

**Status:** accepted; transport, credential, Gateways entry and launch restore
implemented, and verified against a real Termux Gateway on an emulator — a live
turn and physical-device keep-alive are still open
**Authority:** `NousResearch/hermes-agent` @
`29112bef099274229cadff79cdff7bf7b99c4b77`

### Context

A person can run Hermes on the phone itself, inside Termux
(`website/docs/getting-started/termux.md` @ `936b970`). That is a host-owned
Gateway where the host happens to be this device, so the decision above already
covers it — but the transport does not look like either existing route, and the
difference is worth writing down rather than inferring.

The seam is fixed by the platform, not chosen.
[ADR 0001](./0001-ssh-probe-to-tunnel.md), line 12, records why the Managed SSH
route exists at all: "this app cannot read Termux files or agent sockets". Two Android apps are two sandboxes; there is no shared filesystem
path, no unix socket, and no supported way to invoke Termux's binaries. What is
left is a TCP port on loopback, which is exactly the interface `hermes serve`
already publishes.

### Decision

The **Local** route is a third connection kind: a saved connection whose
endpoint is a loopback address on this device.

- **Ownership is unchanged.** The Gateway is host-owned. This app never starts,
  adopts, stops, or reaps `hermes serve`; there is no spawn token, no pid, no
  ownership nonce and nothing to reap. Disconnecting closes a socket. The
  Termux process belongs to the person, exactly as a Remote Gateway belongs to
  its host.
- **Authentication is the static Hermes session token.** On loopback there is
  no TLS, no OAuth gate and no host key, and the server compares an
  `X-Hermes-Session-Token` header against a value fixed for the life of the
  process (`hermes_cli/web_server.py:499-504`, `:567-584` @ `936b970`). That
  token is therefore the entire boundary between this app and every other app
  on the phone, all of which may bind loopback ports without a permission. It
  is stored in the same Keystore-encrypted per-row slot the Remote route's
  token envelope uses — one file per row id below `noBackupFilesDir`, bound to
  the address that minted it, refused but kept when the row is re-addressed,
  and zeroed and unlinked by row id when the row is removed. It is required:
  reading a token off whatever is answering is an empty-slot convenience, never
  a fallback after a refusal.
- **Cleartext is permitted to loopback domains and nowhere else.**
  `res/xml/network_security_config.xml` sets `cleartextTrafficPermitted="false"`
  as the base and names exactly `127.0.0.1`, `localhost` and `::1`.
  `android:usesCleartextTraffic="true"` would grant the same thing to every
  host on the internet, so a repo invariant fails the build if it appears and
  compares the permitted domain list against the loopback set. The Remote route
  keeps refusing plain HTTP in its own normalizer regardless.
- **A port somebody typed is never substituted.** `http` only, host exactly
  `127.0.0.1`, `localhost` or `::1`, no userinfo, query or fragment, and the
  canonical form always names its port. An address that names no port takes
  the documented default, 9119; what is refused is every shape where the URL
  parser would report a port other than the one in the text — an abbreviated
  scheme (`http:host:port`), and a backslash anywhere in the input, which ends
  the authority early and moves the port into the path. Which port a row names
  decides which process on this phone receives the token, and any app may bind
  a loopback port without a permission, so a silent substitution hands the
  token away.
- **Readiness is proved the same way; the socket is the token gate.**
  `GET /api/health`, then the WebSocket, then one correlated `session.list`
  before Connected — the Managed SSH order minus the parts that belong to an
  app-owned process. The health request carries the token but does not test
  it: `/api/health` is on the Gateway's public allowlist at the pin
  (`hermes_cli/dashboard_auth/public_paths.py:33-38` @ `936b970`), so it
  answers 200 to a wrong token. The upgrade is where the token is checked
  (`web_server.py:17017-17025` @ `936b970`), and its 401/403 is read back as a
  distinct, non-retryable refusal with its own sentence rather than as a
  generic socket failure — a refused token is a wrong token, and retrying it
  or reading a second credential off the same server would turn "fix this"
  into a silent loop.

### Consequences

- Three routes, one ownership model: only Managed SSH owns a process, and it
  stays the only route that does.
- A Local connection cannot be shared. It is one phone talking to its own
  runtime, so the multi-client boundary above does not apply — but the same
  rule does: do not drive one running session from Desktop and this phone at
  once if the Termux Hermes is also reachable another way.
- Losing the network never tears a Local leg down, and there is no automatic
  redial: loopback does not travel over the network, and Termux is exactly
  where somebody is when they have none.
- Keeping the Gateway alive is Android's problem and the person's, not the
  app's: wake lock, battery exemption and the Android 12+ phantom-process
  killer. Upstream calls Termux gateway persistence best-effort
  (`termux.md:43` @ `936b970`). Setup and troubleshooting live in
  [the Termux local Gateway guide](../guides/termux-local-gateway.md).
- Physical validation is still owed: a Pixel pass with Termux `hermes serve`
  and an app Local connection producing a session list and a live turn.
