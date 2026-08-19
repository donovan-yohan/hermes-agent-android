# Porting a Desktop surface to Android

The durable checklist behind the `port-hermes-desktop-surface` skill. Read the
skill for the contract; this is how you actually do it.

Every step here earned its place by being something a port gets wrong.

---

## 0. Pin

```bash
git -C /home/donovanyohan/.hermes/hermes-agent rev-parse HEAD
git -C /home/donovanyohan/.hermes/hermes-agent status --porcelain   # must be empty
```

Record the SHA in the change. Every `path:line` you cite is meaningless without
it, because upstream moves. The checkout is **read-only**: no fetch, no
checkout, no stash.

## 1. Read the source, then its tests

For any surface, the reading order that pays off:

| Read | Why |
|---|---|
| `apps/desktop/AGENTS.md` | State authority, identity, re-home vs reboot, resolver ladders |
| `apps/desktop/DESIGN.md` | Flatness, primitives, tokens, motion, empty/error/loading |
| the component itself | What it actually renders |
| the component's `*.test.tsx` | The invariants the component's prose does not state |
| the store/atom it subscribes to | Who is allowed to be right about this data |

Trust the code over screenshots and over prose. When DESIGN.md and a component
disagree, the component is the current contract and the doc is a bug.

**Paths that proved load-bearing so far** (extend this table as you learn):

| Surface | Upstream path | What it settles |
|---|---|---|
| Theme registry | `apps/desktop/src/themes/presets.ts` | The six built-ins, labels, descriptions, default skin |
| Theme resolution | `apps/desktop/src/themes/context.tsx:84-129` | `getBaseColors` + `synthLightColors` — dark-first presets synthesise their light half |
| Colour maths | `apps/desktop/src/themes/color.ts` | `mix`, `readableOn`, WCAG luminance |
| Derived tokens | `apps/desktop/src/styles.css:183-370,440-447` | Text/hairline ladders, conversation type scale, widget fill |
| User message | `apps/desktop/src/components/assistant-ui/thread/user-message.tsx:67` | The user bubble is the *only* bubble |
| Assistant message | `.../thread/assistant-message.tsx:189-197` | Assistant prose is flat, full width, no card |
| Tool scaffolding | `apps/desktop/src/components/chat/scaffold-row.tsx` | One colour and one size for every "what the agent did" line |
| Inline widget | `apps/desktop/src/components/chat/widget-shell.ts:12` | Shared radius, mode-derived fill, **no border** |
| Session status | `apps/desktop/src/app/chat/session-status-dot.tsx:22-77` | Six states; colour + fill/hollow, never motion |
| Session grouping | `apps/desktop/src/lib/time.ts:125-165` | Today / Yesterday / This week / Last week / This month / older |
| Grouping vs ranking | `apps/desktop/src/app/chat/sidebar/order.ts:147-159` | Order applies *within* a group, never across |
| SSH mechanics | `apps/desktop/electron/ssh-connection.ts:130-157,324-374` | `redactSecrets`, error classification, host-key change detection |
| Remote lifecycle | `apps/desktop/electron/remote-lifecycle.ts`, `remote-lifecycle.test.ts`; `hermes_cli/main.py:510-518,664-689,10947-11021`; `hermes_cli/profiles.py:2458-2492` | Login-shell discovery, explicit default/named profile home resolution, OS-home-only token allowlist, exact ownership lock, exclusive/no-follow token upload, spawn-failure cleanup, and bounded TERM proof |
| Served dashboard token | `apps/desktop/electron/dashboard-token.ts`, `dashboard-token.test.ts`, and `remote-lifecycle.ts:733-751,876-960` | The token injected by the served dashboard becomes final only after a post-fetch owned-child check; fetch/parse failure deliberately falls back |
| Remote lock consumer | `hermes_cli/dashboard_procs.py:722-838` and `tests/hermes_cli/test_orphan_desktop_serve_reap.py:113-187` | The lock is a cross-runtime ABI: exact schema/field names/types/bounds/log suffix determine whether Hermes spares the live remote backend |
| Connection config | `apps/desktop/electron/connection-config.ts` | Remote profile and connection terminology |
| JSON-RPC contract | `apps/shared/src/json-rpc-gateway.ts` | Request/error/event envelope and method names |
| Web client | `web/src/lib/gatewayClient.ts` | WebSocket auth, correlation, close and event handling |
| Gateway HTTP/WS | `hermes_cli/web_server.py` | `/api/health`, `/api/ssh/ownership`, public index token injection, and `/api/ws` authentication |
| Sessions and prompts | `tui_gateway/server.py` plus its tests | Durable/runtime identity, session methods, `prompt.submit`, event payloads |

Treat lifecycle files as contracts with every process that consumes them, not
as Android-private metadata. A structurally plausible lock with renamed keys or
a longer fingerprint is rejected by Hermes' local orphan reaper and can turn a
healthy SSH-owned backend into a reap target. Test the production-shaped JSON,
the consumer validator, and cleanup failure paths together.

The token uploaded to start a remote backend is not necessarily the token the
dashboard ultimately serves. Keep the lock at `port: 0` through the forwarded
public-index read, adopt only the exact injected JSON string, then re-inspect
the owned child before publishing the positive port and final fingerprint.
Keep the uploaded artifact fingerprint separate so adoption cannot weaken
descriptor-guarded cleanup. These lifecycle paths were inspected at pinned SHA
`f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`.

## 2. Classify the state before writing UI

Desktop's authority model, and where each kind lives here:

| Desktop authority | Android home | Rule |
|---|---|---|
| Backend-authoritative (sessions, transcripts, config) | `data/session/SessionCache` | Merge, never clobber; rows leave only on an explicit tombstone |
| Machine/runtime facts | `GatewayConnectionManager`, `RemoteHermesLifecycle`, SSH adapter | One resolver per policy |
| Connection-scoped (runtime ids, turn buffers, in-flight tools, generation) | `LiveGatewaySessionRepository` / connection owner | Dies with the connection; durable ids never enter runtime-only calls |
| UI-only (drafts, search, drawer, scroll) | ViewModel / `rememberSaveable` | Never persisted beyond what the user would expect |

If you cannot say which row a piece of state belongs to, stop and decide. That
choice is the port.

## 3. Adapt to the phone

Preserve: hierarchy, density, typography *ratios*, colour semantics, flatness,
transcript grammar (bubble vs flat prose vs scaffolding), session identity.

Replace, and say so in the PR:

| Desktop | Android |
|---|---|
| Hover reveal / tooltips | Always-visible affordance, or long-press; `contentDescription` always |
| Right-click menu | Long-press |
| Persistent sidebar | Modal drawer under 720dp, persistent rail at or above it |
| Enter submits | Explicit send tap; Enter inserts a newline (no modifier key on a soft keyboard) |
| Route overlays as cards | Full-screen destination with a back affordance; system back leaves it |
| 13px body text | 15sp, with the rest of the scale moved by the same factor |

Non-negotiables on this side: 48dp touch targets, a `contentDescription` on
every icon-only control, `imePadding()` where a composer meets the keyboard,
and no state that depends on an animation running.

## 4. Prove it

- Unit-test the pure parts (grouping, parsing, policy) with fixed clocks and
  fixed locales. A test that reads the machine's timezone is not a test.
- Drive coroutines on **virtual time**: inject timing or timeouts, use
  `StandardTestDispatcher`, assert the mid-stream state, not just the end state.
- `uiState` built with `combine` + `WhileSubscribed` needs a live collector
  *and* a `runCurrent()` before assertions; without both you assert a stale
  snapshot. (This cost an hour on the first port.)
- Compose journeys run under Robolectric in `src/testDebug/` — not `src/test/`,
  because `ui-test-manifest` is debug-only and `check` also runs the release
  unit tests.
- In the compact layout the drawer stays composed while closed, so a session
  title exists twice in the tree. Assert **counts**, and reserve
  `assertIsDisplayed` for nodes unique to one surface.
- Use `androidx.compose.ui.test.junit4.v2.createComposeRule`. The v1 rule runs
  on an `UnconfinedTestDispatcher`, so every assertion drains the main looper
  and a running turn always completes before you can see it. v2 queues work;
  use `waitUntil` when you do want a network-shaped fake to finish.
- The transcript opens scrolled to its tail, so an earlier block is
  legitimately off-screen: assert existence, not display, for anything above
  the fold.

Commands:

```bash
./gradlew check              # unit tests (debug + release), lint, repo invariants
./gradlew assembleDebug
git diff --check
```

## 5. Capture what you learned

Before you call it done, edit **this file**: add the upstream paths that
mattered, the pitfalls you hit, and delete steps that turned out to be noise.
A workflow that only grows is a diary, and nobody reads a diary.
