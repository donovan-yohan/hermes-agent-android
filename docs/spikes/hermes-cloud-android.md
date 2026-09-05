# Spike: Hermes Cloud on Android

Status: spike / gap analysis. **No app code was written for this.**
Upstream reference: `~/.hermes/hermes-agent` @
`3ca096de5f8183cb2e0ec23673f294d5978656a3` (read-only; every upstream
`path:line` below is at that SHA).
Live network evidence: two unauthenticated `GET`s of public OAuth metadata,
recorded verbatim in section 2.3. No portal account, token or cookie was used.

---

## 0. The answer, first

A Hermes Cloud agent is an ordinary gated Hermes gateway with the bundled
**Nous** dashboard-auth provider registered. Everything this app already
requires of a Remote gateway — HTTPS, `auth_required`, and an `auth_flows`
array containing `native_pkce` — is what such a gateway advertises. So the
smallest natural way to support Hermes Cloud is **not a new connection kind at
all**: it is to tell people to paste the agent's dashboard URL from the portal
`/agents` page into the existing **Remote gateway** route, and to verify that
end to end on a device.

The expensive part of Desktop's Cloud mode — the portal sign-in, the
`/api/agents` roster, the org picker, the "silent cascade" — is *discovery*
ergonomics, and every load-bearing piece of it is **cookie-scoped to Desktop's
own Electron session partition**. None of it ports to a native app that signs
in through a Custom Tab. What *does* port for free is the cascade's actual
effect: the phone's browser holds the portal session, so the gateway's brokered
PKCE round trip should auto-approve there for the same reason it does in
Desktop's partition.

Confidence that a Cloud dashboard URL already works as a Remote row today:
**high on the contract, unverified on the wire.** Section 6 is the checklist
that closes that gap; it needs a Hermes Cloud account and a phone.

---

## 1. How Desktop does it at the pin

Desktop's `cloud` connection mode is three moving parts, all riding one cookie
jar: the `persist:hermes-remote-oauth` session partition.

### 1.1 Portal sign-in (cookie-only)

`apps/desktop/electron/main.ts:8298` `openPortalLoginWindow()` opens a
`BrowserWindow` on the portal root **inside that partition** and polls the
partition's cookie store until a Privy session cookie appears. The portal
authenticates with Privy, not with Hermes gateway cookies, so the liveness
check is its own thing:

- `main.ts:8093` `hasLivePortalSession()` — is there renewal material at all
  (`privy-session` / `privy-refresh-token`)?
- `main.ts:8139` `hasPortalAccessToken()` — is the short-lived `privy-token`
  access cookie present? This is "can discovery succeed *right now*".
- `main.ts:8180` `renewPortalAccessSilently()` — a hidden `BrowserWindow` on
  the portal root that lets the Privy client rotate a fresh access cookie from
  the surviving refresh session, bounded at 12 s.

The canonical portal base is `main.ts:8071`
`DEFAULT_NOUS_PORTAL_URL = 'https://portal.nousresearch.com'`, overridable via
`HERMES_PORTAL_BASE_URL` / `NOUS_PORTAL_BASE_URL` (`main.ts:8073`), matching
`hermes_cli/auth.py:113`.

### 1.2 Discovery: `GET {portal}/api/agents` (cookie-only)

`main.ts:8409` `discoverCloudAgents(org?)` fetches
`{portal}/api/agents[?org=...]` over the partition-bound net so the portal
cookie is attached automatically. Its own comment is explicit that this is a
cookie path: *"no bearer needed — NAS accepts the cookie"*. Failure handling:

- `401` — one bounded silent renewal, retry once, then a `needsCloudLogin`
  error.
- `409` with body `{"error":"org_selection_required","orgs":[...]}` — the
  renderer shows an org picker and re-calls discovery scoped to the choice.
- Success — `trimCloudAgents()` (`main.ts:8539`) projects each row to
  `{ id, name, status, dashboardUrl, dashboardGatewayState }`.

### 1.3 The silent cascade (cookie-only *in Desktop's implementation*)

`main.ts:8562` `cloudAgentSilentSignIn(dashboardUrl)` opens the **selected
agent's own `/login`** in the *same* partition. Because a live portal session
already sits in that jar, the portal's `/oauth/authorize` auto-approves for an
org member and 302s back, dropping that gateway's session cookie with no second
prompt. The comment at `main.ts:8065-8067` states the security shape plainly:
*"Each agent still completes its own PKCE exchange; SSO removes the human
click, not a security check."*

### 1.4 The UI and the registry kind

- `apps/desktop/src/app/settings/gateway-settings.tsx:1145-1288` — the Cloud
  panel: a sign-in row, an optional org picker, the "Your agents" list with a
  **Connect** button per agent (disabled and labelled *Provisioning...* when
  `dashboardUrl` is null), and a **Refresh** action.
- `apps/desktop/src/app/settings/connections-registry.tsx:43,152,162,424,596,762,779,795,875`
  — `cloud` is a first-class registry kind. It shares the Remote code paths
  everywhere that matters (dedupe key is the normalized URL, same URL field,
  same auth block); the only cloud-specific bits are the icon, the label and
  description pair, and `cloudAddHint` shown under the kind chooser.
- `connections-registry.tsx:582` — `reason === 'cloud-managed'` skips cloud
  rows in the fleet update fan-out; `en.ts:790`
  `updateSkippedCloud: 'Managed by Hermes Cloud'`.
- `apps/desktop/src/i18n/en.ts:858-888` — the Cloud panel copy, verbatim:
  `cloudTitle: 'Hermes Cloud'`,
  `cloudDesc: 'Sign in once to Hermes Cloud and pick from the agents on your account — no URL to paste.'`,
  `cloudSignIn`, `cloudSignedIn`, `cloudNeedsSignIn`, `cloudSignedInDesc`,
  `cloudAgentsTitle: 'Your agents'`, `cloudOrgPickerTitle`, `cloudOrgSelect`,
  `cloudOrgChange`, `cloudOrgRole`, `cloudLoadingAgents`, `cloudNoAgents`,
  `cloudRefresh`, `cloudConnect`, `cloudConnecting`, `cloudDiscoverFailed`,
  `cloudConnectFailed`, `cloudSignInFailed`, `cloudSignedOutTitle`,
  `cloudSignedOutMessage`, `cloudConnectedTitle`, `cloudConnectedPill`,
  `cloudConnectedTo`, `cloudAgentProvisioning`, `cloudStatusLabel`.
  The registry kind copy is `en.ts:793` `kindCloud: 'Hermes Cloud'` and
  `en.ts:797`
  `kindCloudDesc: 'A hosted instance discovered through your Hermes Cloud account.'`

### 1.5 What of that contract is unavailable to a native app

| Desktop mechanism | Native-app availability |
|---|---|
| Portal session in `persist:hermes-remote-oauth` | **Unavailable.** A Custom Tab uses the *system browser's* jar; the app cannot read it, warm it, or poll it. |
| `hasLivePortalSession` / `hasPortalAccessToken` cookie probes | **Unavailable.** No cookie inspection API exists for a Custom Tab. |
| `renewPortalAccessSilently` hidden window | **Unavailable**, and would be wrong on mobile anyway. |
| `GET /api/agents` authenticated by the portal cookie | **Unavailable as implemented.** Cookie auth is the only path evidenced at the pin (see section 2). |
| The 409 org picker | Reachable only if `/api/agents` becomes reachable. |
| The cascade's *effect* (portal SSO auto-approves the agent's authorize) | **Available.** It does not require Desktop's jar — only that *the browser doing the authorize* holds a portal session. On Android that browser is the user's own, which is a better place for it to live. |

That table is the whole finding: Desktop's Cloud mode is a *discovery* feature
built on a cookie jar the app owns. Android owns no such jar. The connection
itself was never the cookie-shaped part.

---

## 2. Portal auth flows a native client could use

### 2.1 Device code (`hermes_cli/auth.py`) — evidenced, but for inference

`hermes_cli/auth.py:250-258` registers the `nous` provider as
`auth_type="oauth_device_code"` with:

- `client_id` = `hermes-cli` (`auth.py:115` `DEFAULT_NOUS_CLIENT_ID`)
- `scope` = `inference:invoke` (`auth.py:116-118`;
  `NOUS_BILLING_MANAGE_SCOPE = "billing:manage"` is the only other one named)
- device-code endpoint `POST {portal}/api/oauth/device/code` (`auth.py:5456`)
  returning `device_code, user_code, verification_uri,
  verification_uri_complete, expires_in, interval` (`auth.py:5466`)
- token endpoint `POST {portal}/api/oauth/token` with
  `grant_type=urn:ietf:params:oauth:grant-type:device_code`
  (`auth.py:5508-5513`), polled on `authorization_pending` / `slow_down`
  (`auth.py:5522-5533`)
- refresh via the same token endpoint (`auth.py:6040`), with the refresh token
  carried in an `X-Refresh-Token` header rather than the body so it stays out
  of portal access logs (`plugins/dashboard_auth/nous/__init__.py:244-250`
  documents the same convention).

**This flow is real and a native client could run it.** What it is *not*
evidenced to do is list agents: the only scopes named anywhere at the pin are
`inference:invoke` and `billing:manage`. Nothing at the pin shows
`/api/agents` accepting a bearer minted by this flow.

### 2.2 Does `/api/agents` accept a bearer? — **not evidenced**

`/api/agents` appears at the pin **only** in Desktop code
(`main.ts`, `connection-config.ts:43,55,989,1002`,
`oauth-partition.test.ts:96`, `src/global.d.ts:1061`). The service that serves
it (NAS) is not in this checkout. Every comment describes the cookie path and
none describes a bearer path. So:

- **Evidenced:** the portal session cookie authenticates `GET /api/agents`.
- **Assumed / unknown:** whether a bearer works, and under which scope. Do not
  design against it without checking the NAS repo or a live probe.

### 2.3 Portal OAuth for native clients — evidenced, and this is the good one

`website/docs/guides/manage-hermes-cloud-with-mcp.md` documents the portal's
*agent-management* surface as an OAuth-protected MCP server at
`https://portal.nousresearch.com/mcp`, discovered by RFC 9728 / RFC 8414
metadata, with RFC 7591 Dynamic Client Registration and PKCE — *"You do **not**
need a separate API key or client secret — the server uses OAuth with PKCE, and
the login is a browser round-trip"* (guide line 39). Its tools are `agents`
(list / get / cost estimate) and `agent` (start / stop / restart / create /
destroy / update). The org picker for multi-org accounts happens **in the
browser during authorization**, not as a client-side 409 dance (guide lines
56-60).

I confirmed that surface live with two unauthenticated `GET`s:

`GET https://portal.nousresearch.com/.well-known/oauth-authorization-server`
returned `200`:

```json
{"issuer":"https://portal.nousresearch.com",
 "authorization_endpoint":"https://portal.nousresearch.com/oauth/authorize",
 "token_endpoint":"https://portal.nousresearch.com/api/oauth/token",
 "registration_endpoint":"https://portal.nousresearch.com/api/oauth/register",
 "jwks_uri":"https://portal.nousresearch.com/.well-known/jwks.json",
 "scopes_supported":["mcp:manage_agents"],
 "response_types_supported":["code"],
 "grant_types_supported":["authorization_code","refresh_token","client_credentials"],
 "code_challenge_methods_supported":["S256"],
 "token_endpoint_auth_methods_supported":["none","client_secret_post"]}
```

`GET https://portal.nousresearch.com/.well-known/oauth-protected-resource`
returned `200`:

```json
{"resource":"https://portal.nousresearch.com/mcp",
 "authorization_servers":["https://portal.nousresearch.com"],
 "scopes_supported":["mcp:manage_agents"],
 "bearer_methods_supported":["header"]}
```

(`/.well-known/oauth-protected-resource/mcp` is `404`; the unsuffixed path is
the one that answers. An unauthenticated
`GET https://portal.nousresearch.com/mcp` returns `401` with
`www-authenticate: Bearer error="invalid_token", ...,
resource_metadata="https://portal.nousresearch.com/.well-known/oauth-protected-resource"`.)

Four things follow, and they matter:

1. The portal **is** a standards-shaped authorization server for public
   clients: `code` + `S256` + `token_endpoint_auth_methods: ["none"]`. An
   Android app can be a direct client of it — no cookie jar required.
2. `registration_endpoint` means the app would register itself dynamically
   rather than shipping a client id.
3. The **device-code grant is not advertised** in `grant_types_supported`.
   The CLI's device flow is real but is not part of this discovery document —
   another reason not to build the mobile roster on it.
4. **The only scope on offer is `mcp:manage_agents`.** There is no read-only
   agent-listing scope. A phone app that wanted a roster would be asking the
   user to grant start / stop / create / **destroy** authority over their whole
   org, and would then hold a token with that authority in Keystore. That is a
   materially larger blast radius than any credential this app stores today,
   and it is the strongest single argument for not shipping discovery yet.

---

## 3. Does a Hermes Cloud agent's own gateway already work as a plain Remote row?

### 3.1 What Android requires

`app/src/main/kotlin/com/hermesagent/mobile/data/gateway/RemoteGateway.kt`:

- `normalizeRemoteGatewayUrl` (`:1341-1351`) requires `https`, a non-blank
  host, and no userinfo, query or fragment.
- `NativeGatewayAuthenticator.ticket()` (`:480-497`) probes `GET /api/status`
  and fails closed twice: on `auth_required == false`
  (*"This Gateway is not using remote authentication. Enable the Gateway auth
  gate before connecting."*) and on `NATIVE_FLOW !in auth_flows`
  (*"This Gateway does not support native sign-in. Update Hermes on the remote
  host."*), where `NATIVE_FLOW = "native_pkce"` (`:652`).
- Unlike Desktop, Android has **no** "at least one non-password provider"
  condition and **no** embedded-webview fallback. `auth_providers` is never
  read. Presence of `native_pkce` is the whole rule
  (`docs/adr/0002-shared-remote-gateway.md:65-66`).
- The flow itself (`LoopbackGatewayNativeLogin.login`, `:972-1132`) is textbook
  RFC 8252: verifier, state and S256 challenge; an ephemeral `127.0.0.1`
  listener; `redirect_uri = http://127.0.0.1:{port}/callback` (`:1041`); a
  Custom Tab on `{base}/auth/native/authorize` carrying
  `code_challenge`, `code_challenge_method=S256`, `redirect_uri`, `state` and
  an optional `provider` (`:1359-1372`); `state` validated before anything else
  is read (`:1180`); then `POST /auth/native/token` with
  `{code, code_verifier}` (`:746-762`) and `POST /auth/native/refresh` for
  rotation (`:780-797`). There is no custom-scheme intent filter in the
  manifest — the loopback listener *is* the callback surface.

### 3.2 What a Cloud gateway advertises

`hermes_cli/web_server.py:3977-3988` builds `auth_flows` on the public
`/api/status` endpoint: `"cookie"` whenever the gate is engaged, plus
`"native_pkce"` **iff `list_session_providers()` is non-empty** — that is, iff
at least one interactive session provider is registered.
`hermes_cli/dashboard_auth/registry.py:117` defines that set as every provider
with `supports_session` (defaulting True).

A Hermes Cloud instance registers exactly such a provider.
`plugins/dashboard_auth/nous/__init__.py` is the bundled
`NousDashboardAuthProvider` — *"Nous Portal OAuth via authorization-code + PKCE
(S256)"*, `name = "nous"` (`:156`). Its `register(ctx)` (`:605`) registers the
provider **iff a client id of shape `agent:{agent_instance_id}` is
configured**, and the module docstring says where that comes from:
`HERMES_DASHBOARD_OAUTH_CLIENT_ID`, *"used by Fly.io's platform-secret
injection so per-deploy values don't need to bake into config.yaml"*
(`:9-27`). That is the hosted deployment path. Self-hosted loopback operators
leave it unset and the plugin no-ops.

So: **Cloud instance implies gate engaged plus a `nous` session provider, which
implies `/api/status` returns `auth_flows: ["cookie","native_pkce"]`.** That is
exactly what Android's probe demands, and `/api/status` is deliberately public
(`web_server.py:4008-4012`: *"Always-public liveness + auth-gate shape. Safe
for external uptime probes..."*), so the probe itself needs no credential.

Upstream states the general rule outright:
`website/docs/guides/desktop-native-signin.md:103-112` — *"Native sign-in is
available automatically on any gated gateway with an interactive session
provider registered. No configuration is required... OAuth providers (e.g. the
bundled **Nous** provider) broker the upstream IDP redirect."*

### 3.3 Would the Custom Tab auto-approve like Desktop's cascade?

Trace the brokered flow with the Nous provider in place:

1. Android opens `{agent}/auth/native/authorize` in a Custom Tab.
   `hermes_cli/dashboard_auth/routes.py:271-305` validates the redirect as a
   **literal loopback IP** (`127.0.0.1` or `::1`, `http` only; `localhost` is
   deliberately refused per RFC 8252 section 8.3). Android sends `127.0.0.1`,
   so this passes.
2. With no `provider` parameter, `routes.py:352-366` auto-selects the single
   *non-password* session provider. A Cloud instance registers only `nous`, so
   the auto-select resolves — Android need not name a provider.
3. The gateway calls `provider.start_login(redirect_uri=...)`
   (`plugins/dashboard_auth/nous/__init__.py:179-205`), which builds
   `{portal}/oauth/authorize` with `response_type=code`,
   `client_id=agent:{id}`, `redirect_uri={gateway}/auth/callback`,
   `scope=agent_dashboard:access`, `state`, `code_challenge` and
   `code_challenge_method=S256`. The redirect back is the **gateway's own**
   `/auth/callback` — the provider even enforces that shape (`:384-403`: must
   be http(s) and must end in `/auth/callback`).
4. The Custom Tab lands on the portal's authorize page **in the user's own
   browser**. If that browser holds a live portal session and the user is an
   org member, this is the same auto-approve Desktop rides — the cascade,
   relocated from Electron's partition to the phone's browser. If it does not,
   the user simply signs in to the portal in the tab, which on a phone is the
   right place for it.
5. Portal 302s to `{gateway}/auth/callback`; `routes.py:554-570` mints a
   one-time loopback code and bounces the browser to
   `http://127.0.0.1:{port}/callback`. No browser session cookie is set for the
   app.
6. Android's listener takes the code, checks `state`, and exchanges it at
   `POST /auth/native/token` (`routes.py:989`) for the gateway's own access and
   refresh pair, stored per row in Keystore.

**Confidence: high on the contract, unverified on the wire.** Every hop above
is read at the pin and every Android-side requirement is met by construction.
What is *not* established at the pin, because the hosting layer is not in this
checkout:

- whether Hermes Cloud fronts the agent dashboard with a proxy or relay that
  filters `/auth/native/*` (they are documented as public pre-auth routes, but
  the front door is NAS's, not this repo's);
- whether the portal's server-side redirect-uri allowlist
  (`agent-redirect-uri.ts`, portal-side and not in this checkout) is happy for
  a request that originated from `/auth/native/authorize`;
- whether the portal's auto-approve fires without an interactive org chooser
  for multi-org accounts in a plain mobile browser;
- whether Android's Background Activity Launch return-to-app hand-back behaves
  on this route — a *known local* limitation tracked as
  [#119](https://github.com/donovan-yohan/hermes-agent-android/issues/119),
  not a Cloud-specific one.

Section 6 is the test that settles all four.

---

## 4. Android state today

Hermes Cloud is already *rendered* and deliberately *unreachable*, which is the
parity contract for a Desktop mode this app does not support yet.

- `app/src/main/kotlin/com/hermesagent/mobile/ui/gateway/ConnectionsCopy.kt:342`
  — `const val KIND_CLOUD = "Hermes Cloud"`, with the comment (`:337-341`)
  *"The kind itself is not offered — there is no Android Hermes Cloud sign-in —
  but the chooser still renders it, disabled, so the form teaches the same four
  kinds Desktop's does."* There is no `KIND_CLOUD_DESC`; `kindDescription()`
  (`:388-392`) is exhaustive over the three real kinds only.
- Same file, `GatewayModeCopy`: `:429` `CLOUD_TITLE = "Hermes Cloud"` and
  `:432-433`
  `CLOUD_DESC = "Sign in once to Hermes Cloud and pick from the agents on your account — no URL to paste."`
  — verbatim `en.ts:859`.
- `app/src/main/kotlin/com/hermesagent/mobile/ui/gateway/ConnectionsSection.kt:630-638`
  — `CONNECTION_KIND_CHOICES` lists Local, **Hermes Cloud (`kind = null`)**,
  Remote, SSH, in Desktop's order, with the comment at `:632-634`: *"a kind no
  row can be should be unrepresentable, not merely refused."* The render site
  (`:445-459`) sets `enabled = choice.kind != null`, a null-safe `onClick`, the
  `WipPill()` trailing chip and `status = WIP_SPOKEN`.
  `GatewayScreen.kt:162-192` does the same for the mode card.
- `app/src/main/kotlin/com/hermesagent/mobile/data/connections/ConnectionRegistry.kt:15-29`
  — `ConnectionKind` is `Remote, Ssh, Local`; the KDoc says *"Android ships
  three: `cloud` has no Android sign-in"*. Persisted by `Enum.name`;
  `fromStoredName` (`:66-68`) maps an unrecognised name (including a literal
  `"Cloud"`) to `Remote`, never to a keyless route.
- `app/src/main/kotlin/com/hermesagent/mobile/ui/common/SettingsPrimitives.kt:93,103`
  — `WIP_PILL = "WIP"` drawn, `WIP_SPOKEN = "Work in progress."` spoken.
- Tests that pin the shape: `ConnectionsSectionTest.kt:20,28,36`
  (`the chooser offers Desktop's four kinds, in Desktop's order`;
  `Hermes Cloud is offered but cannot be chosen`, asserting
  `assertNull(cloud.kind)`;
  `every kind a row can be is a button the chooser offers`);
  `GatewayScreenTest.kt:25,91`; `ConnectionModeCardsJourneyTest.kt:113` and
  `ConnectionKindChooserJourneyTest.kt:95` (both assert `assertIsNotEnabled()`,
  the WIP pill, the merged content description ending in `WIP_SPOKEN`, and that
  `performClick()` reports no selection); `ConnectionsJourneyTest.kt:203,407`
  (absent when editing an existing row); `ConnectionRegistryTest.kt:240-258`
  (a stored `"kind":"Cloud"` decodes to `Remote` and is not usable).
- `docs/parity/gateway-connections.md`: `:13` and `:19` fix Cloud's position and
  glyph in both choosers; `:81` classifies the registry `cloud` kind as
  *"Non-goal. There is no Android Hermes Cloud sign-in."*; `:280` classifies the
  Cloud connection mode as *coming soon* behind the shared WIP pill; `:281`
  defers `cloudAddHint` to
  [#100](https://github.com/donovan-yohan/hermes-agent-android/issues/100).
- `status/ROADMAP.md` does not mention Hermes Cloud at all — an omission this
  spike's issue should fix.

Note the tension worth flagging: `gateway-connections.md:81` calls the `cloud`
kind a **non-goal**, while `:280` calls the Cloud *mode* **coming soon**. If
section 3 holds on a device, `:81` is wrong and should be reclassified — the
kind is a deferred adaptation, not a non-goal.

---

## 5. Options, ranked

### (a) Docs-only: paste the agent dashboard URL into the Remote route — recommended

The portal `/agents` page already gives each instance a dashboard URL (it is
the same `dashboardUrl` Desktop's discovery reads, `main.ts:8539-8547`). The
user copies it, adds a **Remote gateway** connection, and signs in through the
Custom Tab as with any other gated gateway.

- **Size:** zero app code. One section in `docs/guides/`, a line in
  `status/ROADMAP.md`, and the parity reclassification noted in section 4. The
  real cost is section 6 — one live device pass with a Cloud account.
- **Why it wins:** it ships the *capability* today, stores no new credential,
  adds no new authority grant, and is the exact fallback Desktop itself
  supports (`connections-registry.tsx:762` lets a user pick `remote` for a
  hosted instance; `multi-connection-desktop.md:75-76` says cloud entries
  "normally" come from discovery, i.e. not necessarily).
- **What it does not give:** the roster. The user must visit the portal to find
  the URL, and re-visit it when they create an instance.

### (b) A native Cloud card that runs a portal flow and lists agents — defer

Two sub-variants, and the brief's version is the weaker one:

- **(b1) device-code, then `GET /api/agents`.** Unevidenced twice over: the
  device-code grant is not in the portal's advertised
  `grant_types_supported`, and no bearer path to `/api/agents` is evidenced at
  the pin (sections 2.1, 2.2). Do not build this without new evidence.
- **(b2) DCR plus authorization-code/PKCE against the portal MCP `agents`
  tool.** Fully evidenced (section 2.3) and native-shaped. But it requires the
  app to become an MCP client, to dynamically register, and — decisively — to
  hold a `mcp:manage_agents` token, the only scope on offer, which grants
  **create and destroy** over the user's whole org. A phone app holding
  org-destroy authority to render a picker is a bad trade.

**Where the portal token would live, if we ever did this.** Today
`AndroidGatewayTokenStore` is strictly per-row: the slot file is a SHA-256 of a
row-scoped digest input under `noBackupFilesDir/gateway-auth`, the ciphertext
records the `boundUrl` that minted it, a row pointed elsewhere is *refused but
kept* so a typo is recoverable, and there is exactly one credential *kind* per
row (`KIND_NATIVE` versus `KIND_SESSION`). A portal token belongs to none of
that: it is account-scoped, not row-scoped. It would need a fourth thing in the
secrets policy — an **account slot**, keyed by portal origin rather than row id,
with its own bound-origin check, its own zeroing rule, and an explicit answer to
"what erases it" (removing the last cloud row? an explicit sign-out? both?).
AGENTS.md's current sentence — *"Two secrets have a disk slot... one kind of
credential per row"* — would have to be rewritten, and the repo invariant that
enforces it updated. That is a policy change, not a feature, and it should be
its own decision.

### (c) The better thing found at the pin: the provider auto-select, not a picker

The genuinely small win hiding in section 3.2 is that a Cloud gateway registers
exactly **one** non-password session provider, so `/auth/native/authorize`
auto-selects it (`routes.py:352-366`) and Android's existing `provider`
parameter can stay empty. There is nothing to build. Combined with (a), the
whole Cloud story on Android reduces to "paste the URL, sign in in the tab" —
which is why (a) is the recommendation and (b) is a separate, later,
policy-bearing decision.

A cheap follow-on that is *not* discovery: once a Remote row's sign-in
succeeds, the token response carries `provider` (`RemoteGateway.kt:878`). A row
whose provider is `nous` could render the **Hermes Cloud** label and glyph
instead of the generic Remote one, giving the parity affordance without any
portal credential. That is a small, honest step and is worth its own issue if
(a) verifies.

---

## 6. Live-verification checklist (needs a Hermes Cloud account and a phone)

Run on a physical device with a debug APK. Record redacted evidence only — no
URL, host, token or account detail in the issue or PR.

1. **Probe is public.** `GET {agent}/api/status` unauthenticated. **Expect**
   `200` with `auth_required: true` and `auth_flows` containing both `cookie`
   and `native_pkce`. *If `native_pkce` is absent, everything below is moot and
   section 3 is wrong.*
2. **URL is accepted.** Add a **Remote gateway** connection with the dashboard
   URL from the portal `/agents` page. **Expect** it saves — that is, it is
   `https` with no query or fragment. Note whether the portal hands out a URL
   with a path prefix.
3. **Cold sign-in.** With the phone's default browser signed **out** of the
   portal, tap Sign in. **Expect** the Custom Tab shows the portal login, then
   the agent connects and lands back in the app.
4. **Warm sign-in (the cascade).** Sign out of the *gateway* in the app but
   stay signed in to the portal in the browser, then sign in again. **Expect**
   no second prompt — straight through to the loopback callback. This is the
   claim in section 3.3 step 4.
5. **Multi-org.** With an account in more than one org, repeat step 3.
   **Expect** either a clean auto-approve or an in-browser org chooser. Record
   which — this decides whether org selection needs any app surface.
6. **Return hand-back.** Note whether the app is foregrounded automatically or
   the user must swipe back — and cross-reference
   [#119](https://github.com/donovan-yohan/hermes-agent-android/issues/119)
   rather than filing a duplicate.
7. **The session works.** Open a session, send a turn, receive a response.
   **Expect** the WebSocket ticket mint (`POST /api/auth/ws-ticket`) succeeds
   with the bearer.
8. **Refresh rotates.** Leave the app long enough for the access token to
   expire (the Nous provider's session is a 24 h rotating refresh,
   `plugins/dashboard_auth/nous/__init__.py:38-43`), then reconnect. **Expect**
   `POST /auth/native/refresh` rotates rather than re-prompting.
9. **Instance restart.** Stop and start the instance from the portal, then
   reconnect. **Expect** the saved row recovers without re-adding.
10. **Removal is clean.** Remove the row. **Expect** the Keystore slot is
    zeroed and unlinked (`AndroidGatewayTokenStore.clear()`), addressable by
    row id.

A pass on 1-4 and 7 is enough to ship (a). A failure on 1 makes this a backend
conversation, not an app one.

---

## 7. What shipping the recommendation owes

**Acceptance criteria for (a):**

- A `docs/guides/` page that walks a Hermes Cloud user from the portal
  `/agents` page to a working Remote connection, in product-facing language,
  naming the one limitation (no in-app roster) beside the action.
- `status/ROADMAP.md` names Hermes Cloud and says what works and what does not.
- `docs/parity/gateway-connections.md:81` reclassified from **non-goal** to a
  classified divergence with this spike cited, if and only if section 6 steps
  1-4 pass.
- The Cloud card and kind stay rendered, disabled, behind the WIP pill; every
  test in section 4 stays green. Nothing about (a) makes the card selectable —
  the card is about *discovery*, which (a) does not ship.
- No new string is added to `ConnectionsCopy.kt` without running
  `scripts/check-product-copy.py`.

**Parity rows it would owe** (only if UI ever changes; (a) as scoped changes
none): `gateway-settings.tsx:1116-1123` for the Cloud mode card (the parity
page cites this block as `1049-1082` / `1057-1064`; at this pin the mode-card
grid is `1108-1140`) and `gateway-settings.tsx:1145-1288` for the Cloud panel
body, each classified in
`docs/parity/gateway-connections.md` with a rendered side-by-side, per
`docs/workflows/review-desktop-parity.md`.

**Secrets-policy implications of (a):** none. A Cloud row is a Remote row: one
Keystore slot, one credential kind, bound to the gateway URL that minted it,
zeroed and unlinked on removal. The portal credential never touches the app —
it stays in the browser, where the user already manages it. This is the single
best reason to prefer (a) over (b).

**Must be verified live before shipping:** section 6 steps 1-4 and 7, on a real
device, with a real Hermes Cloud instance. Until then every claim in section 3
is a contract reading, and the guide must say so.

---

## 8. Open questions

1. Does NAS front the agent dashboard in a way that filters `/auth/native/*`?
   Not answerable from this checkout.
2. Does `GET /api/agents` accept a bearer, and under what scope? Needs the NAS
   repo or a live probe with a token.
3. Is there any read-only portal scope planned besides `mcp:manage_agents`?
   If one appears, option (b2) gets much more attractive.
4. Does the portal hand out dashboard URLs with a path prefix? Step 2 of
   section 6 answers it, and it decides whether the guide needs a note about
   prefixes.
