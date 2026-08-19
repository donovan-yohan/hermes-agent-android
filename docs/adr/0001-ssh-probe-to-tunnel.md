# ADR 0001 — The SSH seam: probe now, tunnel next

**Status:** accepted, 2026-08-19
**Scope:** Phase 1 (this commit) and the shape of the next vertical slice
**Source:** `docs/spikes/native-kotlin-ssh-client-scope.md` §5, §7
**Upstream pin:** `NousResearch/hermes-agent` @ `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`

## Context

Hermes Desktop never links an SSH library. It shells out to the system OpenSSH
client precisely so it inherits `~/.ssh/config`, the agent, ProxyJump and
hardware keys for free (`apps/desktop/electron/ssh-connection.ts:4-8`).

None of that exists on Android. There is no system `ssh` binary an app may
exec, no agent socket, and — the part users are most surprised by — no access
to another app's files. Termux having a working key proves the host is
reachable and that `sshd` accepts the account; it grants this app nothing.

So on Android the layer below `ssh.exec()` has to be rebuilt, while everything
*above* it — the remote command strings, the lockfile dance, the token
handling — ports unchanged, because those are the protocol.

## Decision

**Phase 1 ships exactly one SSH capability: `probe`.** One method, one
interface:

```kotlin
interface SshProbe {
    suspend fun probe(profile: HostProfile, credential: SshCredential): ProbeResult
}
```

It connects, verifies the host key, authenticates, runs
`printf HERMES_ANDROID_SSH_OK`, and closes. Implementation: **sshj 0.40.0**,
the spike's recommendation (§7.2) — the only candidate that satisfied all ten
traced capabilities from primary sources, with a pluggable `HostKeyVerifier`
that maps one-to-one onto the TOFU design. Nothing found while building this
slice argued for Apache MINA SSHD instead, and the swap stays cheap because the
interface is one method wide.

Four rules are load-bearing and are asserted by tests, not by convention:

1. **Host-key policy is real code, not a flag.** `evaluateHostKey` returns
   `Trusted` / `FirstUse` / `Changed`. A first use aborts the transport *before*
   authentication, so no credential is ever sent to an unverified host. A
   changed key is a hard failure with no accept path anywhere in the app —
   stricter than Desktop's stderr-regex plus banner
   (`ssh-connection.ts:368-374`). There is no accept-all verifier in the
   codebase; `HostKeyPolicyTest` drives the real sshj verifier with real keys.
2. **Secrets are in-memory only.** `SshCredential` is a plain class with a
   redacting `toString`, zeroed after use. The only thing that touches disk is
   `HostProfile`: host, port, username, auth *method*, accepted fingerprint,
   imported-key display name. The type system enforces it — `HostProfileStore`
   accepts nothing else.
3. **One method, one attempt, no fallback.** sshj will happily be handed a list
   of auth methods to try in turn; it is not. `AuthMethod` maps to an
   `SshAuthType` and the adapter switches on that, so `SshAuthType.None` — SSH
   auth type `none`, which is what Tailscale SSH uses after WireGuard and the
   tailnet SSH policy have already authenticated the node
   (<https://tailscale.com/docs/features/tailscale-ssh>) — is a deliberate
   choice and never a step in a chain. A fallback is how a keyless choice
   quietly becomes a password on the wire. A refusal is its own typed failure,
   `ProbeFailure.TailscaleSshRefused`, because "this host is not running
   Tailscale SSH" and "your password is wrong" are different problems with
   different fixes.
4. **Everything user-visible is redacted.** `redact()` ports Desktop's
   `redactSecrets` (`ssh-connection.ts:130-157`) plus two Android-specific
   shapes (a pasted PEM, a labelled password). `SecretRedactionTest` feeds known
   secrets through every carrier the app emits.

**Deliberately not in Phase 1:** port forwarding, `hermes serve` lifecycle,
token adoption, gateway chat, a foreground service, ProxyJump, key generation
in the Keystore. None of it is stubbed, mocked, or hinted at in an interface.

## Why one method and not a `SshTransport` interface family

The spike's module graph is a good map of where this ends up, and it would have
been easy to write the destination's interfaces today. That would have been
four interfaces with one implementation each and no second caller — shape
without content, and shape that would have to be rewritten the moment the real
constraints (Doze, network handoff, foreground-service policy) arrive.

The seam that exists is the one with two real implementations: `SshjProbe` and
`FakeSshProbe`, which genuinely differ and are both used.

## How probe becomes a tunnel

The next slice adds siblings to `probe`, not a rewrite:

```kotlin
interface SshTransport {           // SshProbe grows into this
    suspend fun probe(...): ProbeResult
    suspend fun openForward(local: Int, remote: Int): Forward   // direct-tcpip, loopback only
    suspend fun exec(command: String, stdin: ByteArray?): ExecResult
}
```

Ordered, from the spike's §5.2 sequence:

1. **`exec`** — the same connection, a bounded command with optional stdin.
   Everything the remote lifecycle needs is a command string, and Desktop's are
   reusable verbatim (`remote-lifecycle.ts`, 21 call sites, ~14 distinct
   commands).
2. **Gate and discover** — `uname -s` / `uname -m`, then `command -v hermes`
   and the candidate paths (`remote-lifecycle.ts:39`, `:182-205`).
3. **Reuse or spawn** — read `backend.lock.json`, `kill -0`, argv-audit
   ownership proof, token-fingerprint match (`:292-472`, `:787-874`). Never kill
   an unproven pid.
4. **Spawn** — mint a 32-byte token and an 8-byte nonce, upload the token *via
   exec stdin, never argv* (`:606-637`), then `setsid sh -c "env HERMES_DESKTOP=1
   HERMES_TUI_WS_ORPHAN_REAP_GRACE_S=300 hermes serve --isolated --host 127.0.0.1
   --port 0 …"` (`:523-540`). The reap-grace injection is the spike's verified
   mobile fix for the 20 s orphan reap (§5.2).
5. **`openForward`** — `direct-tcpip` to `127.0.0.1:<remote>`, with a
   **bind-and-hold** local listener. Bind first, then use the port: Desktop's
   `pickLocalPort` has a TOCTOU race (`ssh-connection.ts:971-985`) that this app
   should not inherit.
6. **Health, then one live chat turn** — `GET /api/health` with
   `X-Hermes-Session-Token`, then the WebSocket dial. Prove the leg you will
   actually use: an HTTP 200 while the WS/auth leg fails is the classic false
   positive (`apps/desktop/AGENTS.md`).

Two mobile facts that shape the design and have no Desktop equivalent:
**client-owned keepalive** (Desktop sets no `ServerAliveInterval` anywhere;
mobile NAT drops idle TCP in 30–300 s) and **tear-down-and-re-dial on network
handoff** (holding the old `Network` turns a minutes-long stall into ~2 s).

## Consequences

- Phase 1 can be dogfooded against a real host today, and the answer it gives —
  "your credentials work from this app" — is the actual unknown.
- The UI already carries the honest story about Termux, so the tunnel slice
  inherits correct expectations instead of correcting them.
- APK cost is paid up front: BouncyCastle is ~8 MB of the 16.5 MB debug APK, and
  it is needed regardless of which JVM SSH library wins. A Conscrypt
  `SecurityProviderRegistrar` is the long-term route off it (spike §7.2).
- If sshj ever fails on-device in a way this slice did not surface, the
  narrowness of `SshProbe` is what makes the swap days, not weeks.
