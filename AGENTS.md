# hermes-mobile

Native Kotlin/Jetpack Compose Android client for a self-hosted Hermes Agent.
The preferred shared topology is an authenticated, host-owned Remote Gateway;
Managed SSH remains an explicit fallback for an app-owned private backend.
Phase 2 ships both connection routes, backend sessions, and live turns.

## Directory map

| Path | What lives there | When to read it |
|---|---|---|
| `app/src/main/kotlin/.../ui/theme/` | Theme registry, palette, semantic tokens, type scale | Any colour, font or spacing question |
| `app/src/main/kotlin/.../ui/` | Compose surfaces: `chat/`, `sessions/`, `appearance/`, `ssh/`, `common/` primitives | Changing what the app looks like or does |
| `app/src/main/kotlin/.../data/session/` | `SessionCache` (backend-authoritative), model, calendar grouping | Anything about sessions or transcripts |
| `app/src/main/kotlin/.../data/ssh/` | sshj transport/opener/probe, destination parser, TOFU policy, redaction | SSH, destinations, host keys, secrets |
| `app/src/main/kotlin/.../data/connections/` | Saved connections registry, dedupe/display rules, the connection switch | Adding, editing, removing or switching a saved Gateway |
| `app/src/main/kotlin/.../data/gateway/` | Remote lifecycle, HTTP/JSON-RPC connection, live session repository, network monitor | Gateway startup, ownership, forwarding, sessions, live turns |
| `app/src/main/kotlin/.../data/profiles/` | Hermes profile roster, identity colour, active scope, session-RPC routing | Which profile the sidebar is in, or what a session RPC is scoped to |
| `app/src/test/kotlin/` | JVM unit tests, incl. the offline theme-parity gate | Adding or fixing tests |
| `app/src/testDebug/kotlin/` | Compose journeys under Robolectric | UI tests (debug-only: `ui-test-manifest` is a debug artifact) |
| `app/src/androidTest/kotlin/` | The instrumented emulator lane: real display density, the platform accessibility tree, a real input-method binding, real rotation, real Activity recreate | A claim Robolectric structurally cannot make |
| `status/` | Current shipping status, limitations, and roadmap direction | Checking what works now or remains deferred |
| `docs/workflows/` | Durable port + theme-sync checklists | Before porting a Desktop surface or syncing themes |
| `docs/adr/` | Decisions with consequences | Before changing the SSH seam |
| `docs/spikes/` | The research this repo was founded on | Background; long |
| `.chalk/` | chalkbag source (skills, permissions, providers) | Editing agent config; see `.chalk/README.md` |
| `scripts/` | Repo invariants, run by `./gradlew check` | Adding a repo-level gate |

Start with `status/ROADMAP.md` for current capabilities and limitations, then
`docs/phase-2-architecture.md` for connection sequence, state map, and evidence.

## Commands

```bash
export ANDROID_HOME=/opt/android-sdk        # JDK 17; platform 36, build-tools 35/36
./gradlew check                             # unit tests (debug+release) + lint + repo invariants
./gradlew assembleDebug                     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest --tests '*ThemeParityTest*'   # one test class
./gradlew :app:lintDebug
./gradlew :app:assembleDebugAndroidTest    # compile and package the instrumented lane
./gradlew :app:connectedDebugAndroidTest   # run it on an attached device or emulator
./scripts/check-repo-invariants.sh          # symlink, ignore rules, theme pin
python3 .chalk/skills/sync-hermes-desktop-themes/scripts/check-theme-parity.py \
  --upstream "${HERMES_AGENT_UPSTREAM:-$HOME/.hermes/hermes-agent}" # live upstream diff
chalkbag validate && chalkbag build --yes && chalkbag doctor  # after editing .chalk/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Non-obvious rules

**Upstream is read-only.** `~/.hermes/hermes-agent` is a
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
ViewModel outlives the screen. Per saved connection, only a random local row
id, a label, the route, the Gateway URL and optional sign-in provider, and the
SSH host, port, username, optional remote profile, auth method and accepted
fingerprint reach disk, plus which row is active and a random per-install
ownership id — that one stays per install, because it namespaces this app's
remote processes on a host rather than an endpoint. The imported key's display
name is screen state the store drops. Two secrets have a disk slot, and they
share one machinery: a Remote row's OAuth tokens, and a Local row's static
Hermes session token. Keystore ciphertext below `noBackupFilesDir`, one file
per row id, naming the Gateway that minted it, and one kind of credential per
row. Either is refused — and kept on
disk, so a mistyped address is recoverable — if that row later points
elsewhere, and it is zeroed and unlinked when the row is removed — addressable by row id alone, so a row whose URL was blanked or
mistyped can still be cleaned up rather than orphaning its credential.
Source files carry no NUL byte: one is Git's own binary heuristic, and a file
Git calls binary shows no diff in review, so write the escape.
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

**Connections are a list, and the active row is the app's one connection.**
`connections.v1.*` holds the saved rows and which one is active; every
single-connection reader is a projection of that row, so a connection edit has
one writer and no second copy. The pre-registry `host.single.*` /
`gateway.single.*` keys were migrated into row one, not overloaded.

**The SSH destination is one field.** `user@host`, port 22 implicit,
`user@[ipv6]:port` supported. `parseSshDestination` refuses rather than guesses,
only a value that parses reaches the profile, and the parsed host/port/username
are the canonical persisted copy — the raw string is UI-only. Changing the host
or port drops the accepted fingerprint; changing only the username keeps it.

**Backend-authoritative data merges, never clobbers.** `SessionCache` is the
cache of live Gateway truth: partial refreshes layer, rows leave only through
an explicit tombstone, and a no-op upsert preserves reference identity. UI-only
state (draft, search, drawer) never
goes in there. Changing endpoint is the one wholesale clear, because the next
backend is a different machine that can recycle the same durable ids: the
connection switch calls `resetForEndpointSwitch()`, and nothing else may.

**Foreground isolation.** A running turn writes to the session that started it.
Switching sessions never cancels it and never paints into the session now on
screen; it lands as an unread dot. Sends are gated per target session: another
session's live turn is informational only and never blocks this composer. The
gateway repository supports concurrent per-session app-submitted turns;
identifier-less (unstamped) events are pinned to the single safe local runtime,
with rollback, stop/redirect/steer, pre-start grace, and pin inheritance all
keyed on per-runtime liveness rather than a global lock.

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

**The instrumented lane earns its cost or does not exist.** `src/androidTest/`
takes only what Robolectric structurally cannot prove, and only what the lane is
actually green on: the real display density, the chat chrome's arrival in the
platform `AccessibilityNodeInfo` tree, a real input method binding to the
composer, a real orientation change, and a real Activity destroy/recreate. Font
scale is not among them — the CI emulator runs at 1.0, and a device-wide change
would move what every other test on the lane can see. Neither is the keyboard's
own window: whether a headless emulator draws one depends on the system image
and the AVD, so `imePadding` stays on the physical device matrix. Neither is
auditing the labels and touch sizes of what that accessibility tree publishes:
the sweep populates from nodes a screen reader never visits and never reads
`hintText`, so that claim is
[#91](https://github.com/donovan-yohan/hermes-agent-android/issues/91) and not
yet this lane's. A test that would pass identically under Robolectric belongs in
`src/testDebug/`. Nothing there reaches a Gateway or names a host — a repo
invariant refuses a URL, an SSH destination, an address, a fingerprint or key
material in those sources. Backtick method names are unavailable: D8 rejects
identifiers with spaces below minSdk 30. The lane is not physical acceptance,
and neither the workflow nor the ROADMAP may imply it is.

## Scoped guides

| Path | Covers |
|---|---|
| `status/ROADMAP.md` | Current capabilities, evidence limits, known gaps, and roadmap direction |
| `docs/workflows/port-desktop-surface.md` | Porting any Desktop UI/capability: pinning, source-and-test reading, state classification, mobile adaptation, evidence |
| `docs/workflows/sync-desktop-themes.md` | Desktop theme/token changes: inventory diff, mapping, fonts, parity, visual checks |
| `docs/adr/0002-shared-remote-gateway.md` | Preferred Remote Gateway topology, native authentication, and multi-client boundary |
| `docs/adr/0001-ssh-probe-to-tunnel.md` | Managed SSH transport, remote ownership, Gateway readiness, and restart limitation |
| `.chalk/README.md` | chalkbag source-of-truth rules |
| `docs/workflows/review-product-copy.md` | Reviewing rendered product copy and reasoned gate exceptions |
| `docs/parity/profile-switcher.md` | Profile rail, active-profile scope, and the read-only roster: pin, adaptation, deviations |
