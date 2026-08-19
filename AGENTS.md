# hermes-mobile

Native Kotlin/Jetpack Compose Android client for a self-hosted Hermes Agent,
reached over an app-managed SSH tunnel. Phase 2 ships a live remote lifecycle,
authenticated Gateway transport, backend sessions, and live turns.

## Directory map

| Path | What lives there | When to read it |
|---|---|---|
| `app/src/main/kotlin/.../ui/theme/` | Theme registry, palette, semantic tokens, type scale | Any colour, font or spacing question |
| `app/src/main/kotlin/.../ui/` | Compose surfaces: `chat/`, `sessions/`, `appearance/`, `ssh/`, `common/` primitives | Changing what the app looks like or does |
| `app/src/main/kotlin/.../data/session/` | `SessionCache` (backend-authoritative), model, calendar grouping | Anything about sessions or transcripts |
| `app/src/main/kotlin/.../data/ssh/` | sshj transport/opener/probe, destination parser, TOFU policy, redaction | SSH, destinations, host keys, secrets |
| `app/src/main/kotlin/.../data/gateway/` | Remote lifecycle, HTTP/JSON-RPC connection, live session repository, network monitor | Gateway startup, ownership, forwarding, sessions, live turns |
| `app/src/test/kotlin/` | JVM unit tests, incl. the offline theme-parity gate | Adding or fixing tests |
| `app/src/testDebug/kotlin/` | Compose journeys under Robolectric | UI tests (debug-only: `ui-test-manifest` is a debug artifact) |
| `docs/workflows/` | Durable port + theme-sync checklists | Before porting a Desktop surface or syncing themes |
| `docs/adr/` | Decisions with consequences | Before changing the SSH seam |
| `docs/spikes/` | The research this repo was founded on | Background; long |
| `.chalk/` | chalkbag source (skills, permissions, providers) | Editing agent config; see `.chalk/README.md` |
| `scripts/` | Repo invariants, run by `./gradlew check` | Adding a repo-level gate |

Start with `docs/phase-2-architecture.md` — connection sequence, state map,
limitations, and evidence.

## Commands

```bash
export ANDROID_HOME=/opt/android-sdk        # JDK 17; platform 36, build-tools 35/36
./gradlew check                             # unit tests (debug+release) + lint + repo invariants
./gradlew assembleDebug                     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest --tests '*ThemeParityTest*'   # one test class
./gradlew :app:lintDebug
./scripts/check-repo-invariants.sh          # symlink, ignore rules, theme pin
python3 .chalk/skills/sync-hermes-desktop-themes/scripts/check-theme-parity.py \
  --upstream /home/donovanyohan/.hermes/hermes-agent          # live upstream diff
chalkbag validate && chalkbag build --yes && chalkbag doctor  # after editing .chalk/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Non-obvious rules

**Upstream is read-only.** `/home/donovanyohan/.hermes/hermes-agent` is a
reference checkout pinned at `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`. Never
write to it, never fetch or check out inside it. Cite `path:line` **with** that
SHA or the citation means nothing.

**Theme parity is an invariant.** Every Desktop built-in at the pinned SHA has
an Android preset with the same name, label, description and registry order.
Adding a theme must be a data edit to `BuiltinThemes.ALL` — if a chat component
has to change, that is the bug. Components read `HermesTheme.tokens`, never a
palette field, never a raw colour, never a preset name. `ThemeParityTest`
enforces it offline; the parity script diffs a live upstream checkout.

**Secrets policy.** Passwords, passphrases and private keys are in-memory only
and are zeroed after use, and the whole screen's material is wiped when the
Gateways surface leaves — before `FLAG_SECURE` is cleared, because the
ViewModel outlives the screen. Only host, port, username, optional remote
profile, auth method, accepted fingerprint, and a random per-install ownership
id reach disk; the imported key's display name is screen state the store drops.
Everything user-visible goes through `redact()`. No credential, host name or fingerprint belongs in this repo, in a
test, or in a screenshot. There is no accept-all host-key verifier and a changed
host key has no accept path.

**One auth method, one attempt, no fallback.** `AuthMethod` maps to an
`SshAuthType`; both the probe and session opener use one transport attempt.
`SshAuthType.None` is Tailscale SSH's deliberate auth type `none`, never a step
in a chain, and a refusal is its
own `ProbeFailure.TailscaleSshRefused` rather than a generic auth failure.
Tailscale SSH still goes through the same mandatory TOFU review — sshj does not
consume Tailscale's client-side `known_hosts`. The auth method is persisted by
enum *name*, so entries may be reordered; an unrecognised name falls back to
Password, never to a keyless method.

**The SSH destination is one field.** `user@host`, port 22 implicit,
`user@[ipv6]:port` supported. `parseSshDestination` refuses rather than guesses,
only a value that parses reaches the profile, and the parsed host/port/username
are the canonical persisted copy — the raw string is UI-only. Changing the host
or port drops the accepted fingerprint; changing only the username keeps it.

**Backend-authoritative data merges, never clobbers.** `SessionCache` is the
cache of live Gateway truth: partial refreshes layer, rows leave only through
an explicit tombstone, and a no-op upsert preserves reference identity. UI-only
state (draft, search, drawer) never
goes in there.

**Foreground isolation.** A running turn writes to the session that started it.
Switching sessions never cancels it and never paints into the session now on
screen; it lands as an unread dot. Permit one app-submitted turn at a time
because an unscoped event cannot be routed safely between concurrent turns.

**Product copy is product-facing.** State the task, outcome, and next action;
reuse truthful Desktop terminology. Keep implementation/security detail out of
primary UI; put one concise limitation beside its action. Errors explain what
happened and a safe next step, never raw exceptions or secrets. Run
`scripts/check-product-copy.py`; rare long strings need a nearby reasoned
`product-copy-allow` marker and review under `docs/workflows/review-product-copy.md`.

**`CLAUDE.md` is a symlink to `AGENTS.md`** and `./gradlew check` fails if it
stops being one. Generated `.agents/`, `.claude/`, `.codex/`, `.opencode/` and
`opencode.json` are ignored and must never be committed.

**Testing shape.** Coroutines are tested on virtual time with injected timing —
never real delays. A `combine` + `WhileSubscribed` state flow needs a live
collector *and* a `runCurrent()` before you assert. Compose journeys go in
`src/testDebug/`. Fixed clock, fixed timezone, fixed locale for anything
calendar-shaped.

## Scoped guides

| Path | Covers |
|---|---|
| `docs/workflows/port-desktop-surface.md` | Porting any Desktop UI/capability: pinning, source-and-test reading, state classification, mobile adaptation, evidence |
| `docs/workflows/sync-desktop-themes.md` | Desktop theme/token changes: inventory diff, mapping, fonts, parity, visual checks |
| `docs/adr/0001-ssh-probe-to-tunnel.md` | SSH transport, remote ownership, Gateway readiness, and restart limitation |
| `.chalk/README.md` | chalkbag source-of-truth rules |
| `docs/workflows/review-product-copy.md` | Reviewing rendered product copy and reasoned gate exceptions |
