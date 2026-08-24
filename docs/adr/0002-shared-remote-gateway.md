# ADR 0002 — Host-owned Remote Gateway with native authentication

**Status:** implemented as the default connection route, 2026-08-20
**Terminology amended:** 2026-08-24 — product label changed from Shared Gateway
to Remote Gateway; the ownership and authentication decision is unchanged
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
