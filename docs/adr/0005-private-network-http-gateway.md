# ADR 0005 — HTTP(S) Remote Gateways, matching Desktop

**Status:** implemented; amended to remove the earlier private-IP-only HTTP
restriction. Supersedes the HTTPS-only / loopback-only transport requirements in
ADR 0002. Process ownership and native authentication are unchanged.

## Desktop policy inspected

At `36922ad064d65dcf25f8f48df81e1ccf9a55de67`, Desktop's
`apps/desktop/electron/connection-config.ts:47-80` accepts HTTP and HTTPS,
prefixes scheme-less addresses with HTTP, strips query/fragment, and preserves
the port and path prefix. `connection-config.ts:91-96` selects WS or WSS from
the base scheme for single-use tickets. There is no private-address restriction
in that normalizer. `apps/desktop/src/i18n/en.ts:1559` describes HTTP(S) over LAN,
Tailscale, or the internet. These scoped inspections do not restamp older surface
pins or rendered evidence.

## Decision

Remote accepts ordinary HTTP(S) hostnames and IPv4/IPv6 addresses, including
MagicDNS names, public hosts and loopback. Scheme-less addresses use HTTP.
Ports, hostname and reverse-proxy prefixes survive normalization and all
native-auth/HTTP/WebSocket legs. No suffix allowlist or DNS trust classifier is
used; normal system DNS is used by OkHttp. The earlier numeric-private-only
normalizer and application interceptor were an incomplete Desktop port and are
removed, rather than replaced with a special case for Tailnet names.

Existing Android URL userinfo, query and fragment refusal is retained as requested.
This is not identical to Desktop's normalizer (which strips query/fragment and
does not itself reject userinfo); no broader validation-parity claim is made.
Local retains its explicit loopback address/port and static-token rules. Remote
loopback still requires the same gated native authentication as other Remote URLs.

Android platform cleartext permission remains enabled for HTTP and WS; there is
no private-host admission backstop. Production clients derive from the shared
`gatewayTransportClient`. Redirects and system proxies remain disabled as existing
auth safeguards, avoiding changes to credential recipients in this URL-acceptance
fix. Desktop's inspected `apps/desktop/electron/api-transport.ts:40-46,221-227`
at the same scoped SHA uses direct Node HTTP(S) agents and explicitly documents
that REST callers never follow redirects. Retaining the Android safeguards is
consistent with that inspected REST contract, not a claim about every Desktop
browser/session path. Proxy-dependent routes or redirecting base URLs remain
unsupported. HTTPS certificate and hostname
verification are untouched. The client-inventory gate is maintenance evidence,
not a security sandbox or DNS guard.

Native PKCE, auth-required discovery, short-lived bearer refresh, one-use tickets,
Keystore-encrypted per-row storage, hostname/scheme/port/path URL binding,
redaction, and sign-out/removal remain unchanged. Resolving a hostname does not
rewrite its credential slot to an IP. This is not static-token or unauthenticated
Remote support.

## Transport tradeoff

The user explicitly requested Desktop HTTP link acceptance, not a private-network
restriction. HTTP provides no TLS confidentiality, integrity or server identity;
bearer/refresh tokens, browser sign-in and session traffic can be read or modified
on an untrusted network. PKCE and encrypted storage do not encrypt transit.
Tailscale supplies network encryption only if traffic actually uses its route;
a `.ts.net` name alone proves neither routing nor confidentiality. Prefer HTTPS
when transport confidentiality is required. A supported URL is not evidence that
its DNS, network route, browser callback, auth flow or WebSocket works on a phone.

## Evidence and remaining gates

- `RemoteGatewayTest`: general HTTP hostname/IP, scheme-less, private IPv4/IPv6,
  HTTPS, synthetic multi-label `.ts.net:9120`, preserved auth/ticket paths,
  unsafe URL validation and existing native-auth lifecycle regressions.
- `GatewayTransportPolicyTest`: actual Application-derived HTTP admission and
  asynchronous WebSocket admission to controlled DNS with hostname intact;
  redirects/proxies remain disabled and platform HTTP permission is checked.
  These admission tests do not claim a real authenticated socket connection.
- `PrivateGatewayCopyTest`: HTTP(S) guidance, Desktop description and LAN HTTP
  placeholder; copy failures observed before production edits.
- `scripts/check-gateway-transport.py --self-test`: shared-client inventory,
  platform HTTP permission, redirect/proxy safeguard mutations.

Unauthenticated metadata at the reported endpoint returned HTTP 200 and advertised
`auth_required: true` and `auth_flows: ["cookie", "native_pkce"]`. No credentials,
cookies or host identifiers are recorded here. Discovery prerequisites therefore
pass from the build host, but physical-phone DNS/Tailnet reachability, browser
PKCE callback, exchange/refresh and authenticated WebSocket/RPC remain unverified.
Rendered copy comparison and real device connection evidence remain owed; app
launch alone is not evidence of Gateway success.

Local verification: the focused HTTP(S), Application transport, copy, token-slot,
registry and Local regression lane passed **83 tests** after observed URL/HTTP/WS
and copy failures. `assembleDebug`, debug/release lint and repo invariants passed
(the invariant script ran 196 Python tests). The full `check assembleDebug
--continue` run is **not green**: debug ran 3470 tests with two failures and one
skip; release ran 2756 with one failure and one skip. The unchanged callback
attack test timed out in both variants, and the debug routines capture formatting
test failed. Both failures were reproduced individually at unchanged
`45aafe670a943b760747ac064bc19bf929c12f97` in an isolated baseline worktree; they are not silently
skipped or rewritten by this fix.
