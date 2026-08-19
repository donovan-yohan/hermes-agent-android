# Phase 1 — what is real, what is demo, and what comes next

Native Kotlin/Compose Android client for Hermes. This document is the honest
inventory of the first vertical slice.

**Upstream pin:** `NousResearch/hermes-agent` @
`f82f2dbabd9e66b714f2b4f8a40447fe0c13e732` (read-only checkout at
`/home/donovanyohan/.hermes/hermes-agent`). Every `path:line` below resolves
against that SHA.

---

## 1. Real vs demo

| Area | Status |
|---|---|
| Six Desktop themes, light + dark, ported values | **Real.** Registry, colour maths and light-synthesis are line-for-line ports; parity is a test. |
| Theme + mode persistence | **Real.** DataStore. |
| Adaptive chat/session layout | **Real.** Drawer under 720dp, persistent rail at or above. |
| Transcript rendering, markdown blocks, tool scaffolding | **Real** rendering of **demo** content. |
| Streaming, stop, background-turn isolation | **Real** state machine on a **fake** producer. |
| Session list: grouping, search, status dots, create/rename/archive | **Real** logic over an **in-memory** session set. |
| SSH host profile, key import via SAF, host-key TOFU policy | **Real.** |
| One-field `user@host` destination, port 22 implicit | **Real.** Parser, formatter and round-trip are tested. |
| Tailscale SSH: SSH auth type `none`, no secret collected | **Real** code path. Unverified against a host that actually runs Tailscale SSH — see §3 and §6. |
| `probe`: connect → verify → auth → run one command → close | **Real**, over sshj 0.40.0. Unverified against a live host — see §6. |
| Secret redaction and in-memory-only credentials | **Real**, and tested. |
| Hermes gateway, WebSocket, real sessions, real model output | **Absent.** Not stubbed, not mocked. |
| SSH tunnel, `hermes serve` lifecycle, token adoption | **Absent.** See `docs/adr/0001-ssh-probe-to-tunnel.md`. |

The demo turn engine is deterministic — the reply is a pure function of the
prompt — so the UI can be exercised, demoed and tested offline. It is not a
mock of the gateway protocol; that seam does not exist yet, and building one
now would be architecture with no consumer.

## 2. Why Termux success does not transfer

The user can SSH to the Hermes box from Termux. That proves two useful things:
the network route works, and `sshd` accepts the account. It grants this app
nothing else, for one reason: **Android sandboxes packages from each other.**
Termux's `~/.ssh/config`, its `known_hosts`, its agent socket and its private
keys live in Termux's private data directory, and no ordinary app can read them.

There is also no system `ssh` binary an app may exec, and no agent to ask for a
signature. Desktop's entire strategy — shell out to OpenSSH and inherit the
user's config for free (`apps/desktop/electron/ssh-connection.ts:4-8`) — has no
Android equivalent.

So the app brings its own auth: credentials it collects and keeps in memory, or
Tailscale SSH, which needs none because the tailnet has already authenticated
the node. Either way it says so on screen. The alternative — implying the phone
already has access because *something else* on it does — produces a confusing
failure the first time someone taps Probe.

## 3. Where you are going, and how you prove who you are

### The destination

One field, the same string you would type after `ssh`: `you@hermes-box`,
`you@hermes-box:2222`, `you@[fd7a:115c::1]:2222`. Port 22 is implicit on input
and omitted on output, so the value round-trips. A host, a port and a username
as three separate boxes was three chances to mistype and two boxes whose answer
never changes.

`parseSshDestination` refuses rather than guesses: whitespace inside is an
error instead of being stripped (silently deleting it turns a typo into a
different, valid host), and a bare IPv6 address is an error because
`fd7a:115c::1:2222` has no single reading — the same reason `ssh` wants
brackets. A value that does not parse is shown with its reason and never
reaches the profile, so a half-typed host cannot overwrite a working one.

Trust is scoped to the host key, so `HostProfile.withDestination` keeps an
accepted fingerprint when only the username changes and drops it when the host
or port changes. Dropping is the safe direction; the cost is one extra
fingerprint review.

### Auth choices, and their limits

| Method | How it works | Limit, stated honestly |
|---|---|---|
| Tailscale SSH (default for a fresh profile) | Nothing is collected and nothing is sent: `client.auth(username, AuthNone())`. The tailnet already authenticated the node over WireGuard and checked the tailnet SSH policy, so the SSH layer uses auth type `none` (<https://tailscale.com/docs/features/tailscale-ssh>) | Works **only** if the target runs Tailscale SSH *and* the tailnet SSH policy allows the connection. Being on the same tailnet buys reachability and a MagicDNS name (<https://tailscale.com/docs/features/magicdns>) and nothing more — a box running ordinary OpenSSH over Tailscale still wants a password or a key, and says so as `ProbeFailure.TailscaleSshRefused`. There is no automatic fallback to another method. |
| Password | Typed and held in memory for the screen, then dropped after the probe | Never persisted. Retyped every probe. It arrives from the text field as a JVM `String`, so it cannot be scrubbed in place. |
| Imported OpenSSH private key | Picked through the Storage Access Framework, read once (64 KB cap), held in memory | Never persisted, and **no persistable URI permission is taken** — a key the app can silently re-read after a restart is a key the app effectively stores. Re-import each session. |

The three claims that get conflated, kept apart on purpose: the tailnet gives a
route, MagicDNS gives a name, and Tailscale SSH gives an identity the server
accepts. The first two do not imply the third.

What reaches disk: host, port, username, auth *method*, the accepted host-key
fingerprint, the imported key's display name. That is the whole list, and
`HostProfileStore` accepts nothing else by type. The destination is not a
seventh key — it is derived from the parsed host, port and username, so there
is one canonical copy. The auth method is persisted by enum *name*, so entries
can be reordered or added without rewriting an existing install's choice; a
name this build does not recognise falls back to Password rather than to the
fresh default, because quietly moving someone onto a keyless method is the one
wrong answer.

Tailscale distributes host keys to its own client through a custom
`known_hosts`; sshj does not consume that, so the TOFU review below is
unchanged and still mandatory for Tailscale SSH.

Host-key policy (`data/ssh/HostKeyPolicy.kt`):

- **First use** shows a `SHA256:…` fingerprint in `ssh-keygen -lf` format and
  aborts *before* authentication. Nothing secret has been sent when you see it.
- **Match** connects.
- **Change** is a hard stop with no accept path in the app. Stricter than
  Desktop, which shows a banner (`ssh-connection.ts:368-374`).

Not yet, and not pretended: hardware-backed keys (AndroidKeystore P-256),
biometric gating, ProxyJump, key generation on device. The spike's §7.1 has the
decided design; none of it is stubbed here.

Trust is anchored to `(host, port)` and to the form that asked for it. A review
carries the pair that offered the key and stops counting the moment the
destination points elsewhere, so retargeting the field and then tapping accept
trusts nothing; every edit bumps a generation, cancels a probe in flight, and
makes a late answer stale rather than letting it paint the screen.

The screen drops the password, passphrase and key as soon as a probe has used
them — success, refusal, cancellation or timeout alike. A first-use review is
the one exception: nothing was sent, so accepting and retrying does not ask
again. `FLAG_SECURE` is applied while the SSH surface is on screen and cleared
when it leaves, so screenshots, recordings, casting and the recent-apps preview
skip credential entry and host-key review without blacking out the rest of the
app. The accepted fingerprint is excluded from cloud backup and device transfer,
so a restored install reviews the key again on the device that will use it.

Known residual risks: first-use acceptance without out-of-band verification is
TOFU, the same posture as OpenSSH's default. The private key is a `char[]` and
is really zeroed; the password and passphrase come from a text field as JVM
`String`s, so they can only be dropped, not scrubbed.

## 4. Module and state map

One Gradle module, `:app`. A multi-module split earns its keep when modules
have different consumers or different build costs; today it would be ceremony.
The package boundaries are already where the module boundaries would go.

```
com.hermesagent.mobile
├── HermesApplication         process-scoped state: the session cache, preferences
├── MainActivity              the one wiring site (no DI framework — one graph)
├── data/
│   ├── session/              SessionCache (backend-authoritative), model, grouping
│   ├── markdown/             block parser for the transcript
│   ├── demo/                 deterministic seed + turn engine   ← replaced by the gateway
│   ├── prefs/                DataStore: appearance + host profile
│   └── ssh/                  SshProbe seam, sshj adapter, fake, destination parser,
│                              TOFU policy, redaction
└── ui/
    ├── theme/                registry, palette, tokens, type scale   ← the whole theme system
    ├── common/               shared primitives (one per concern)
    ├── chat/                 ChatViewModel, ChatScreen, Transcript, Composer
    ├── sessions/             SessionList
    ├── appearance/           theme picker
    └── ssh/                  SshViewModel, destination + auth + probe screen
```

State, by authority (`apps/desktop/AGENTS.md`, "Decide state by authority"):

| Kind | Home | Rule |
|---|---|---|
| Backend-authoritative | `SessionCache`, owned by `HermesApplication` | Merge, never clobber. Rows leave only via `removeSession`. A no-op upsert returns the same instance so Compose does not recompose. Process-scoped because a retained ViewModel outlives the Activity. |
| Connection-scoped | `ChatViewModel` fields (`jobs`, `generations`) | Dies with the scope. A per-session generation counter drops deltas from a superseded turn. |
| UI-only | `ChatViewModel` / `SshViewModel` flows, `rememberSaveable` | Draft, search, drawer, and the SSH destination exactly as typed — its parsed halves are what get persisted. |
| Persisted | `HermesPreferences` | Keys carry their scope (`appearance.*`, `host.single.*`). |

Two behaviours worth naming because they are the ones a naive port gets wrong:

- **The foreground is isolated.** A turn writes to the session that started it.
  Switching sessions never cancels it and never paints into the session you are
  now looking at; it lands as an unread dot instead.
- **Switching is a re-home, not a reboot.** The draft for the session you leave
  is dropped rather than carried across.

### Seams, and whether the tests hit them

A seam earns its keep when it isolates a real authority or a blocking boundary.
There are three here, and all are exercised:

| Seam | Implementations | What the tests drive |
|---|---|---|
| `SshProbe` | `SshjProbe` (real), `FakeSshProbe` (deterministic) | `SshViewModelTest` drives the full onboarding journey through the fake, but the fake runs the **real** `evaluateHostKey`, so the policy under test is production policy. `HostKeyPolicyTest` additionally drives the real sshj `HostKeyVerifier` with generated EC keys. |
| `SshTransport` | `SshjTransport` (real), focused blocking doubles in `SshjProbeTest` | Proves cancellation/timeout closes the active transport, blocks later auth/commands, bounds command reads, and rejects non-exact probe results without requiring a live server. The seam is internal and has one production implementation. |
| `HostProfileStore` | `HermesPreferences` (DataStore), an in-memory double in the test source set | Lets the SSH ViewModel run on a plain JVM, and lets a test assert that nothing secret reaches the store. |

Everything else is a concrete class, on purpose. `SessionCache`,
`DemoTurnEngine` and the theme registry have one implementation each and are
tested directly; wrapping them in interfaces would add indirection with no
second caller. `DemoTurnEngine` takes its timing as a parameter instead, which
is what makes the streaming and stop tests deterministic on virtual time.

Where the tests sit relative to those seams:

- **Pure logic** (colour maths, markdown, calendar buckets, host-key policy,
  redaction) is tested directly, with fixed clocks and locales.
- **State machines** (`ChatViewModel`, `SshViewModel`) are tested at the
  ViewModel boundary on virtual time — not through the UI, so a layout change
  cannot break them.
- **The UI** is tested as a journey through Compose semantics, asserting what a
  user can see and reach. It does not re-assert what the ViewModel tests
  already pin.

The gap worth naming: `SshjProbe` has no test that opens a real socket. Its
blocking lifecycle is covered through `SshTransport`, and its policy, redaction,
provider setup and verifier are covered directly; an actual handshake still
needs a host and physical Android runtime.

The Tailscale SSH branch is covered as far as it goes offline. `AuthMethod`
maps to an `SshAuthType`, the adapter switches on that value, and
`HostProfileTest` pins that exactly one method sends `None` — so the branch
cannot be widened by accident, and no method can acquire a silent fallback.
`SshCredential.carriesSecret` lets `FakeSshProbe` record, per call, that
nothing secret reached the transport. What no offline test can show is sshj's
`AuthNone` completing against a real Tailscale SSH server.

## 5. Theming

The centrepiece, and the thing most likely to drift. Details in
`docs/workflows/sync-desktop-themes.md`.

- `BuiltinThemes.ALL` is the registry. **Adding a theme is a data edit** — no
  component knows a theme name, and `ThemeParityTest` fails if one does.
- `paletteFor(dark)` ports `getBaseColors` (`themes/context.tsx:120-129`):
  `nous` ships a hand-tuned dark half; the other five are dark-first and get
  their light half from `synthLightColors`, the line-for-line port of
  `context.tsx:84-118`. No light palette is invented.
- `HermesTokens` is the semantic layer. Components read
  `HermesTheme.tokens.scaffoldText`, never a palette field. The ladders are
  ported from `styles.css`: text at 94/74/54/36% of the foreground, scaffolding
  at 64/44%, hairlines as the accent mixed into a 10/7/5/3% foreground wash.
- Colour *expressions* stay expressions (`mixPremultiplied`, `mix`,
  `readableOn`), so a change to `NOUS_BLUE` upstream is a one-line follow-up.

### Typography substitutions

No webfont is bundled and **no font is fetched at runtime**. Desktop's stacks
map to platform families:

| Preset | Desktop asks for | Android uses | Note |
|---|---|---|---|
| nous | Courier Prime (Google Fonts) for mono | `FontFamily.Monospace` | |
| midnight | JetBrains Mono (Google Fonts) | `FontFamily.Monospace` | |
| ember | IBM Plex Mono (Google Fonts) | `FontFamily.Monospace` | |
| mono | — | platform defaults | |
| cyberpunk | Courier for **sans and mono** | `FontFamily.Monospace` for both | Load-bearing: the whole UI goes monospace, and that is preserved |
| slate | JetBrains Mono (no URL) | `FontFamily.Monospace` | |

The type scale moves as a block: Desktop's 13px body becomes 15sp, and the
caption/tool sizes and line heights move by the same ~1.15×, so the hierarchy
is identical at phone reading distance. The table is in `HermesTypography.kt`.

## 6. Evidence, and what remains unverified

Commands, and what they proved on 2026-08-19 (JDK 17, `ANDROID_HOME=/opt/android-sdk`):

```bash
./gradlew check          # 200 debug + 184 release unit tests, 0 failures;
                         # lint clean; repo invariants pass
./gradlew assembleDebug  # app/build/outputs/apk/debug/app-debug.apk, ~16.5 MB
git diff --check         # clean
```

`check` runs: debug + release unit tests, Android Lint, and
`scripts/check-repo-invariants.sh`.

Compose journeys run under **Robolectric** in `app/src/testDebug/`, so the core
phone journeys are covered without an emulator: open the drawer, group by date,
switch session, search, create, send a prompt, and the composer's send↔stop
swap; and on the SSH screen, one destination field with no separate host/port/
username, the three auth choices, first-use review through to a successful
probe, and the Tailscale-SSH-refused copy.

**Verified on a physical device, and what it found:**

- **The real probe was run on a Pixel 10 Pro (Android 17 / API 37) with the
  Tailscale VPN connected, and it failed** — before host-key review, with
  `no such algorithm: X25519 for provider BC`. That was not the network: the
  same phone's Termux reaches the same host. It was a JCE provider-name
  collision. Android ships a stripped, deprecated provider that owns the name
  `BC`, so sshj's `SecurityUtils.registerSecurityProvider` cannot install the
  bundled BouncyCastle (`Security.addProvider` returns `-1`), smoke-tests MD5
  and DH against the *instance* it built, and then records the provider by
  **name** — after which every lookup lands on the stripped one and
  curve25519-sha256, Ed25519, ECDSA, RSA-SHA2 and HmacSHA256 all disappear.
  `data/ssh/SshSecurityProvider.kt` registers the bundled provider under a name
  Android does not own, pins sshj to it, and verifies it — including a live
  X25519 agreement — before anything uses it.
  `SshSecurityProviderTest` reproduces the failure offline (down to the exact
  error string) and asserts the repair, its idempotence, that the platform
  provider keeps its name and its position, and that an incomplete provider is
  refused rather than half-installed.

**Not verified, and not claimed:**

- **No SSH handshake has completed against a real sshd from a phone.** The
  provider repair is proven on the JVM, not on Android: what a JVM test cannot
  show is that *Android's* `BC` is stripped in exactly the way the device error
  implies, or that `Provider.putAll` behaves identically on API 26. The Pixel
  rerun is the evidence for that, and it belongs to Ebi.
- **API 26 has no coverage at all.** The unit tests run on the JVM and the
  Compose journeys under Robolectric at SDK 34. Nothing has executed on the
  minimum supported API level.
- **Tailscale SSH has not been observed succeeding.** A local development
  target was confirmed to run traditional OpenSSH over Tailscale rather than
  Tailscale SSH; endpoint and account details are intentionally not recorded
  here. The app's Tailscale SSH path is therefore exercised only against
  `FakeSshProbe`; that path is expected to produce
  `ProbeFailure.TailscaleSshRefused`, which is the honest answer, not a bug.
  Enabling Tailscale SSH on a target and writing the tailnet policy is out of
  this slice's scope.
- **Insets, `FLAG_SECURE` and the recents preview are device-only.** The SSH
  column now consumes the IME and navigation-bar insets and sets `FLAG_SECURE`
  while it is composed, but "the keyboard does not cover the Accept button on a
  real Pixel" and "the recents card is blank" cannot be asserted from
  Robolectric.
- **No other physical-device dogfood.** Fold and unfold, rendering fidelity, and
  colour on an actual panel are emulator/device-only.
- **No release build path.** No signing config, no shrinking. `proguard-rules.pro`
  carries the sshj/BouncyCastle keeps so the first release attempt is not a
  surprise, but it has never been exercised.

## 7. Next vertical slice

Tunnel + remote lifecycle + gateway health + one live chat turn. The sequence,
the reused Desktop command strings, and the two mobile-only design forces
(client-owned keepalive, tear-down-and-re-dial on network handoff) are in
`docs/adr/0001-ssh-probe-to-tunnel.md`. The extension is additive: `probe`
gains siblings on the same seam, and no UI written in this slice is rewritten.

## 8. Build and install

```bash
export ANDROID_HOME=/opt/android-sdk        # JDK 17
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK installs as `com.hermesagent.mobile.debug`, so it can sit
alongside a future release build. Everything in it runs offline; the only
network the app can make is the SSH probe you ask for.
