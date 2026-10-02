# ADR 0005 — Typed, revocable core Skills HTTP capability

**Status:** implemented host foundation. The separately added
[installed-only autosave editor](../parity/bot-installed-skills.md) consumes this
contract; the foundation decision and host semantics below are unchanged.
**Authority:** `NousResearch/hermes-agent` @ `587e673e2a2fae0616d8b750bb189217080f621a`.
The parent workstream's delta audit reports Skills HTTP and profile RPC sources
unchanged at `e05b16348b1d06a3311237423b0a4fc30d9c5aa1`. This is not a claim of
latest full Bot or rendered parity.

## Decision

Add `PluginHost.skills`, a typed core-host door with `openScope(endpoint, profile)`,
`listInstalled`, individual `toggleInstalled`, and irrevocable `close`. No generic
path, verb, JSON, URL, credential, or exception is exposed. Ordinary/unsupported
hosts return `MissingCapability`; they never fall back to unguarded HTTP, RPC,
CLI, or config-file writes. `PluginRest` remains confined to `/api/plugins/<id>`.
The application injects the existing connection-owned HTTP slot and the **same**
`EndpointDispatchFence` already owned by connection switching and RPC dispatch.

Why not `profiles.configure.disabled_skills`: describe returns installed rows,
not the full disabled set. Replacing from those rows loses absent/unknown names.
The real HTTP PUT reads/modifies only the named member under upstream's config
mutation lock. See `docs/spikes/bot-installed-skills-handoff.md` for the concrete
counterexample. No new RPC method or replacement editor is introduced.

## Authority and route trace

All upstream paths/lines here are at the authority above:

- `apps/desktop/src/api/skills.ts:14-43`: exact `GET /api/skills` and
  `PUT /api/skills/toggle`; individual autosave, not a staged Save operation.
- `hermes_cli/web_routers/skills.py:343-394`: installed list and named delta.
  `hermes_cli/web_routers/_common.py:26-37` scopes the target before the config
  mutation lock. The PUT echoes the request; this is not persisted-state proof.
- `hermes_cli/web_server.py:1032-1035`: the full served dashboard mounts Skills.
  RPC reachability and native-auth negotiation do **not** prove those HTTP paths
  are exposed by an older server or a deployment's proxy.
- `hermes_cli/dashboard_auth/middleware.py:163-190`: the gated server verifies
  the native bearer before proceeding. The legacy loopback server uses the
  session-token middleware. These are host/dashboard credentials, not a new
  Android-created per-profile ACL.
- `hermes_cli/web_server_profiles.py:190-199,243-270,277-320`: explicit names are
  validated/resolved server-side, with home/secret scope and restored Skills
  globals. `current` is a serving-profile alias, not a named target, and is
  refused by this host. Android passes the exact named profile in the query;
  PUT also carries the same explicit profile in its body, never a conflicting
  selector. The server remains authoritative for profile existence and access.

Production `GatewayConnection.kt` constructs each HTTP adapter with captured
leg state, not closures that follow a replacement endpoint:

| Route | Captured endpoint and authorization | Availability, not an assumption |
| --- | --- | --- |
| Remote | `profile.normalizedBaseUrl`, `connector.accessToken(profile)` for that captured profile | Requires the authenticated core paths at that base/prefix. A functioning socket or `native_pkce` is insufficient. |
| Managed SSH | The original forwarded loopback port and eagerly captured session token | The app-owned `hermes serve` mounts the full router at the inspected pin; older/custom backends can still lack it. No SSH command fallback. |
| Local | The original validated loopback base and `authorized` local leg | Requires the person's Termux `hermes serve` to expose those paths. Android neither starts another process nor reads Termux config. |

Missing HTTP/guarded adapter yields `MissingCapability` on every route. A named
GET 404 triggers **only** an unscoped read-only GET capability probe. Only its
404 proves `UnavailableOnGateway`. Success, authorization failure, oversized
response, or inconclusive/failed probe preserves the original scoped `Refused`;
unscoped inventory is never parsed/published. No unscoped mutation is possible.
Authentication/server rejection is `Refused`; transport failure is `Unreachable`.
A missing PUT alone remains a refusal rather than being probed destructively.
No live Remote, SSH, or Local server was mutated to certify deployment support.

## Dispatch proof and exact limitation

1. `openScope` captures the original HTTP object and the endpoint lease before
   any suspension. Each scope permanently owns one explicit profile.
2. `OkHttpGatewayHttp.executeAtDispatch` prepares credentials/request, then hops
   to the injected IO dispatcher. Guarded execution is a separate optional
   transport capability; a plain `GatewayHttp` cannot accidentally satisfy it.
3. At **OkHttp `Call.enqueue`**, the shared endpoint monitor validates the lease
   and original HTTP identity, then the scope monitor checks `closed` and hands
   the prepared call to OkHttp. Endpoint `invalidate` uses the first monitor;
   scope `close` uses the second. There is no gap between a Boolean predicate
   returning and a later unguarded dispatch. Lock order is endpoint → scope.
4. Both monitors are released immediately after enqueue, before waiting for any
   response. A held network reply cannot block endpoint switching or closing.
   Scope close never reopens: A→B→A creates a new object and cannot revive A.
5. Answers recheck lease, transport identity, and closed state. Old answers are
   not current truth. An admitted PUT followed by a stale/unconfirmed result
   carries `mutationMayHaveApplied = true`; it must never be automatically replayed.

**Enqueue is the irreversible admission boundary**, analogous to WebSocket
`send` queue admission, not proof of a kernel socket write. OkHttp may schedule
admitted work later. Revocation cannot retract an already admitted request,
undo a committed delta, or provide server CAS against another client. No mutex
is held through DNS, connect, request-body IO, or response duration. The host
promises no stale *admission*, not that previously admitted bytes cannot arrive
later. The same-endpoint live-slot check additionally rejects retired adapters;
the endpoint-switch fence and explicit scope close are the atomic revocations.

Guarded calls disable redirects (including SSL redirects), authenticators, and
connection retries. Guarded mutation bodies are one-shot, which also prevents
HTTP 408/503 follow-up replay. Custom session headers therefore cannot follow
an out-of-scope redirect, regardless of header case. Ordinary HTTP behavior is
unchanged. There is no request re-targeting or credential fallback.

## Readback and privacy

The typed list contains only exact `name` and Boolean `enabled`; no path,
description, usage, provenance, raw error, or credential is returned. Identifiers
are bounded, nonblank, unique, untrimmed/un-normalized, and rejected if redaction
would change them. Response/envelope arrays are wiped by the existing consuming
helpers; this is not a claim to erase all copies in the HTTP/JSON runtimes.

A toggle first verifies the name is in the scoped installed inventory, sends one
individual delta, checks the exact typed receipt, and performs a fresh named
GET on the same captured scope. Only matching readback is success. An echoed
`hermes-agent=false` receipt with persisted `true` is `Unconfirmed`, never success.
This proves observed global selection at that read, not cross-platform usability,
inheritance, an allowlist pin, or protection from a later concurrent write.

## Tests and editor handoff

`GatewayHttpDispatchTest` exercises the production transport, including actual
loopback HTTP redirects/503 responses. `PluginSkillsTest` covers exact scoped
routes/body and essential readback. `PluginSkillsSafetyTest` drives actual
OkHttp adapters through credential and pre-IO barriers, endpoint/dialog ABA,
held PUT responses, missing capability, named-vs-route 404, malformed responses,
raw-error suppression, and cancellation. Existing HTTP/PluginRest/PluginHost
regressions remain in the focused lane; full `check assembleDebug` is required.

The remaining editor must create/close a scope per dialog ownership epoch,
retain accepted pending operations by endpoint/profile/name across close/reopen,
reconcile after completion without replay, and fence publication separately.
Installed-only filtering is an explicit bounded subset of Desktop's broader
catalog. Toggle is autosave; outer Cancel cannot roll it back. Keep all other
Bot drafts/editors unchanged. No UI, capture fixture, bulk operation, catalog,
install/detail/archive, or `skills.manage` enable/disable path ships here.
