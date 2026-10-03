# ADR 0005 — Private-network HTTP Remote Gateways

**Status:** implemented; supersedes only the HTTPS-only / loopback-only transport
requirements in ADR 0002. Process ownership and authentication are unchanged.

## Desktop policy inspected

At `36922ad064d65dcf25f8f48df81e1ccf9a55de67`, Desktop's
`apps/desktop/electron/connection-config.ts:47-80` accepts HTTP and HTTPS,
prefixes scheme-less addresses with HTTP, strips query/fragment, and preserves
the port and path prefix. `connection-config.ts:91-96` selects WS or WSS from
the base scheme for single-use tickets. There is no private-address restriction
in that normalizer. `apps/desktop/src/i18n/en.ts:1559` describes HTTP(S) over LAN,
Tailscale, or the internet. These are inspected source contracts, not a claim
that public HTTP protects credentials.

## Decision

Android supports HTTP Remote Gateways at **numeric** RFC1918 IPv4 addresses
(`10/8`, `172.16/12`, `192.168/16`), CGNAT/Tailnet IPv4 (`100.64/10`), and IPv6
ULA (`fc00/7`, including Tailscale's ULA range). Scheme-less private addresses
use HTTP, as Desktop does. HTTPS is accepted for any valid host. Ports and
reverse-proxy prefixes survive normalization and all auth/HTTP/WebSocket legs.

The mobile policy is deliberately narrower than Desktop: public HTTP, plain-HTTP
DNS names (including MagicDNS), link-local addresses, userinfo, query and fragment
are refused. DNS resolution is not evidence of trust: validating a private result
then resolving again permits rebinding. Use the numeric Tailnet IP for HTTP or
Tailscale Serve HTTPS for a hostname. Remote loopback remains the separate Local
route, with its existing token and port rules.

Android's network-security XML accepts exact domains, not arbitrary IP ranges.
Consequently its base cleartext permission must be enabled. This **removes the
platform-wide cleartext backstop**; the shared `gatewayTransportClient` enforces
private numeric / exact loopback HTTP before any DNS or I/O, including WS
handshakes. Redirects are disabled to prevent a credential-bearing private request
from following another destination; system HTTP proxies are disabled so they
cannot receive private-hop credentials. HTTPS certificate verification is not
changed. All production OkHttp clients, including manager defaults, derive from
this guarded factory; a repository gate rejects new independent client creation.
This structural gate is a maintenance backstop, not a sandbox for arbitrary code.

Native PKCE, auth-required discovery, short-lived bearer refresh, one-use tickets,
Keystore-encrypted per-row storage, origin binding, redaction, and sign-out/removal
remain unchanged. This is not static-token or unauthenticated Remote support.

## Security tradeoff and limitations

HTTP has no TLS confidentiality, integrity or server identity. A private IP alone
proves neither a trusted LAN nor an active Tailscale tunnel. Tailscale supplies
network encryption only when that route actually travels over Tailscale; CGNAT
addresses can also belong to other networks. On an ordinary LAN, a network attacker
can read or modify credentials and session traffic. Use HTTP only on a network
you trust; prefer HTTPS (including Tailscale Serve) otherwise. PKCE and encrypted
storage do not protect bearer/refresh tokens in transit. The browser sign-in leg
also uses the chosen HTTP origin. Corporate/system proxy routing is no longer
supported by the Gateway HTTP graph.

## Evidence

- `RemoteGatewayTest`: observed Tailnet HTTP regression fail before the fix;
  auth and ticket URL construction, private ranges, scheme-less addresses,
  public/DNS HTTP refusals, credentials/query/fragment rejection.
- `GatewayTransportPolicyTest`: observed public HTTP admission, redirect policy,
  platform configuration, and system-proxy policy fail before their fixes;
  checks the actual Application client and inherited transports under Robolectric.
- `PrivateGatewayCopyTest`: observed HTTPS-only guidance fail before copy updates.
- `scripts/check-gateway-transport.py --self-test`: XML/client inventory and
  deliberately removed guard/redirect/proxy mutations.

These tests are not a physical-device sign-in or a real Tailnet packet capture.
Device validation and rendered Desktop/Android copy comparison remain review
obligations; no screenshot is implied by a source or Robolectric assertion.
