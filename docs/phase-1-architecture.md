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

So the app asks for its own credentials, keeps them in memory, and says so on
screen. The alternative — implying the phone already has access — produces a
confusing failure the first time someone taps Probe.

## 3. Current auth choices, and their limits

| Method | How it works | Limit, stated honestly |
|---|---|---|
| Password | Typed, held in memory for the screen, zeroed after the probe | Never persisted. Retyped every probe. |
| Imported OpenSSH private key | Picked through the Storage Access Framework, read once (64 KB cap), held in memory | Never persisted, and **no persistable URI permission is taken** — a key the app can silently re-read after a restart is a key the app effectively stores. Re-import each session. |

What reaches disk: host, port, username, auth *method*, the accepted host-key
fingerprint, the imported key's display name. That is the whole list, and
`HostProfileStore` accepts nothing else by type.

Host-key policy (`data/ssh/HostKeyPolicy.kt`):

- **First use** shows a `SHA256:…` fingerprint in `ssh-keygen -lf` format and
  aborts *before* authentication. Nothing secret has been sent when you see it.
- **Match** connects.
- **Change** is a hard stop with no accept path in the app. Stricter than
  Desktop, which shows a banner (`ssh-connection.ts:368-374`).

Not yet, and not pretended: hardware-backed keys (AndroidKeystore P-256),
biometric gating, ProxyJump, key generation on device. The spike's §7.1 has the
decided design; none of it is stubbed here.

Known residual risks: first-use acceptance without out-of-band verification is
TOFU, the same posture as OpenSSH's default. In-memory secrets are best-effort
zeroed — the JVM may retain copies. `FLAG_SECURE` is not applied yet.

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
│   └── ssh/                  SshProbe seam, sshj adapter, fake, TOFU policy, redaction
└── ui/
    ├── theme/                registry, palette, tokens, type scale   ← the whole theme system
    ├── common/               shared primitives (one per concern)
    ├── chat/                 ChatViewModel, ChatScreen, Transcript, Composer
    ├── sessions/             SessionList
    ├── appearance/           theme picker
    └── ssh/                  SshViewModel, host + probe screen
```

State, by authority (`apps/desktop/AGENTS.md`, "Decide state by authority"):

| Kind | Home | Rule |
|---|---|---|
| Backend-authoritative | `SessionCache`, owned by `HermesApplication` | Merge, never clobber. Rows leave only via `removeSession`. A no-op upsert returns the same instance so Compose does not recompose. Process-scoped because a retained ViewModel outlives the Activity. |
| Connection-scoped | `ChatViewModel` fields (`jobs`, `generations`) | Dies with the scope. A per-session generation counter drops deltas from a superseded turn. |
| UI-only | `ChatViewModel` flows, `rememberSaveable` | Draft, search, drawer, destination. |
| Persisted | `HermesPreferences` | Keys carry their scope (`appearance.*`, `host.single.*`). |

Two behaviours worth naming because they are the ones a naive port gets wrong:

- **The foreground is isolated.** A turn writes to the session that started it.
  Switching sessions never cancels it and never paints into the session you are
  now looking at; it lands as an unread dot instead.
- **Switching is a re-home, not a reboot.** The draft for the session you leave
  is dropped rather than carried across.

### Seams, and whether the tests hit them

A seam earns its keep when it has two real implementations that differ. There
are exactly two here, and both are exercised:

| Seam | Implementations | What the tests drive |
|---|---|---|
| `SshProbe` | `SshjProbe` (real), `FakeSshProbe` (deterministic) | `SshViewModelTest` drives the full onboarding journey through the fake, but the fake runs the **real** `evaluateHostKey`, so the policy under test is production policy. `HostKeyPolicyTest` additionally drives the real sshj `HostKeyVerifier` with generated EC keys. |
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

The gap worth naming: `SshjProbe` itself has no test that opens a socket. Its
policy, its redaction and its verifier are covered; the handshake is not, and
cannot be without a host.

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
./gradlew check          # 94 debug + 85 release unit tests, 0 failures;
                         # lint clean; repo invariants pass
./gradlew assembleDebug  # app/build/outputs/apk/debug/app-debug.apk, ~16.5 MB
git diff --check         # clean
```

`check` runs: debug + release unit tests, Android Lint, and
`scripts/check-repo-invariants.sh`.

Compose journeys run under **Robolectric** in `app/src/testDebug/`, so the core
phone journey is covered without an emulator: open the drawer, group by date,
switch session, search, create, send a prompt, and the composer's send↔stop
swap.

**Not verified, and not claimed:**

- **No live SSH probe has been run.** There is no connected device and no test
  host credentials were provided. `SshjProbe` is compiled, linted, and its
  policy and redaction are unit-tested with real key material — but "sshj
  completes a handshake against a real sshd from a real phone" is unproven.
- **No physical-device dogfood.** Real IME behaviour, gesture navigation, fold
  and unfold, rendering fidelity, and colour on an actual panel are
  emulator/device-only.
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
