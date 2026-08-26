# Spike: supporting Hermes plugin surfaces (start: `hermes-plugin-relay`)

Status: spike / gap analysis, no product code yet.
Upstream reference: `hermes-agent` local checkout
`~/Documents/Programs/personal/hermes-agent` @ `608a56ed7f50926ecfae1db39447b280a0bc4d1e`.
Plugin source of truth: `~/Documents/Programs/personal/hermes-plugin-relay`
@ `563a8c8` (the installed copy under the ebi profile is an older snapshot —
cite the repo, not the install).

---

## 1. What "desktop plugin support" actually is

A desktop plugin can contribute three independent pieces. Relay uses two:

| Piece | Lives in | Consumed by | Transport |
|---|---|---|---|
| Python backend (FastAPI `router`) | `dashboard/plugin_api.py`, declared in `plugin.yaml` (`api:`) | Desktop JS via `ctx.rest()` | Plain REST under `/api/plugins/<name>/…` on the **gateway** |
| Renderer UI | `desktop/plugin.js` (uncompiled ESM, imports `@hermes/plugin-sdk`) | The Electron renderer only | Not a network surface |
| Optional WS stream | same router | `ctx.socket()` | `/api/plugins/<name>/…?token=…` (Relay ships none — 3 s polling) |

Load-bearing upstream facts (all verified at the SHA above):

- Plugin backends are mounted by the **dashboard/gateway process**, not by
  Electron: `hermes_cli/web_server.py:_mount_plugin_api_routes()`
  (~line 19006 at this SHA). Import is restricted to bundled/user sources and
  requires the plugin in `plugins.enabled` in config.yaml. A runtime gate
  middleware re-checks enablement per request and returns 404 for disabled
  plugins.
- `ctx.rest` is not an Electron-only door:
  `apps/desktop/src/api/plugins.ts:53` resolves to plain
  `hermesApi({ path: "/api/plugins/<id>…" })` on the active backend
  connection. Any authenticated client can call the same URLs.
- The relay plugin's backend performs **no inbound caller checks**; its
  loopback rule (`RELAY_IDE_URL` must be literal loopback) governs only its
  own outbound hop to the Relay hub. It also exposes **no `/events`**
  endpoint on purpose; polling every ~3 s while visible is the contract.

Consequence: mobile never ports `plugin.js`. It ports the *surface* — a
native Kotlin client of `/api/plugins/hermes-plugin-relay/`.

## 2. What mobile can already do

- `GatewayHttp` (`data/gateway/GatewayHttp.kt`) is a connection-owned,
  authenticated transport taking arbitrary paths with bounded responses;
  existing consumers use paths like `api/git/status`. A relay repository
  would call it with `api/plugins/hermes-plugin-relay/connection/status`
  etc. No new credential plumbing needed.
- Native PKCE sign-in already mints bearer tokens via
  `POST /auth/native/token` and rotates via `POST /auth/native/refresh`
  (`hermes_cli/dashboard_auth/routes.py`). In OAuth-gated mode the gate's
  `_verify_bearer` path authenticates **any** non-public route, including
  `/api/plugins/...`, from the `Authorization: Bearer` header
  (`hermes_cli/dashboard_auth/middleware.py:gated_auth_middleware`).
  Verified live: anonymous request to a gated gateway's plugin namespace
  returns structured 401 JSON (`reason: no_cookie`), i.e. the route exists,
  is mounted, and is behind auth rather than absent.

## 3. Gap analysis — what we'd have to support

### G1. Auth mode matrix (must decide + test)

| Gateway bind | Plugin routes | Mobile story |
|---|---|---|
| Loopback/no-auth | open, but loopback-only by definition | N/A over Tailscale/LAN — unreachable, fine |
| Token mode (`_SESSION_TOKEN`) | session token or `?token=` accepted | SSH-tunneled leg already injects the loopback session token → works today |
| OAuth-gated | cookie or native bearer | works via existing PKCE bearer |

Work: add conformance tests for all reachable legs; handle the runtime-gate
404 (disabled plugin) as a distinct "not available on this gateway" state so
we never render a fake error.

### G2. Feature detection (must)

There is no registry endpoint ("list installed plugins"). Detection strategy:
probe `GET api/plugins/hermes-plugin-relay/connection/status`; map
HTTP 200 → available; 404 → plugin missing/disabled; 401 → needs sign-in /
token refresh per existing flow. Never show raw status codes.

### G3. Contract pinning (must)

Code against the frozen v1 endpoints documented in the plugin repo
(`docs/desktop.md`): `/connection/status`, `/connection/authorize`,
`/channels`, `/channels/:id/messages` (limit 1–50), message post body
`{text, format, clientMessageId}` exactly. Treat unknown fields as absent.
The newer harness-login surface in the repo checkout is unreleased — do not
consume it until the plugin ships it.

### G4. Credential boundary (must keep)

The plugin holds operator-client and actor credentials in its own process;
mobile never sees or supplies them. `POST /connection/authorize` may redeem a
grant server-side; mobile just renders the resulting status. Keep the
secrets policy intact: nothing credential-shaped in logs, prefs, or tests.

### G5. Polling discipline (should)

Desktop refreshes visible pages every 3 s. Mobile equivalent: refresh only
while the Relay screen is resumed/visible, pause on background, honor
existing connection liveness signals instead of blind timers.

### G6. UI port (the actual feature)

Native Compose surface following `docs/workflows/port-desktop-surface.md`:
channels list → transcript → composer with idempotent send via
`clientMessageId`. Reuse theme tokens; composer rules (48 dp targets,
`imePadding`, explicit send). Out of scope for v1: Harnesses inspector
(read-only observation surface), pane/dock concepts (no desktop layout on a
phone).

### G7. Upstream asks (optional, later)

- A stable "installed plugins" discovery endpoint would replace probing.
- Plugin-scoped WS streams exist upstream but Relay doesn't use them; if a
  future plugin does, mobile needs ticket-minted socket support for
  `/api/plugins/<id>/...`.

## 4. Suggested slice order

1. `RelayPluginRepository`: typed models + `GatewayHttp` calls for the five
   frozen endpoints, unit-tested against recorded fixtures.
2. Availability state machine (G1/G2): probe → available / signin-required /
   unavailable-on-gateway; conformance tests per auth leg.
3. Minimal Compose read path: channels + transcript (polling per G5).
4. Composer post with `clientMessageId` retry-safe semantics.
5. Emulator audible/visual QA per OMP shipping rules before any release claim.

## 5. Evidence log

- Live smoke vs ebi gateway (loopback binds :8642/:8644): those processes are
  RPC listeners, not the dashboard app → 404 `Not Found` on plugin paths.
- Live smoke vs the isolated serve instance (`dev.fish-rattlesnake.ts.net`,
  bound tailnet-only :9120): `/api/health` host-guarded; plugin path returns
  structured 401 unauthenticated envelope — mount confirmed, auth enforced,
  shape matches `middleware.py`.
- `register_token_route` seam exists but has zero core registrations (only
  the drain plugin); irrelevant to this migration.
