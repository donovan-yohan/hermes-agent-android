# Porting a Desktop surface to Android

The durable checklist behind the `port-hermes-desktop-surface` skill. Read the
skill for the contract; this is how you actually do it.

Every step here earned its place by being something a port gets wrong.

---

## 0. Pin

```bash
HERMES_AGENT_UPSTREAM="${HERMES_AGENT_UPSTREAM:-$HOME/.hermes/hermes-agent}"
git -C "$HERMES_AGENT_UPSTREAM" rev-parse HEAD
git -C "$HERMES_AGENT_UPSTREAM" status --porcelain   # must be empty
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
| Running-session row outline | `apps/desktop/src/store/session-dot-state.ts:70-80`; `apps/desktop/src/app/chat/sidebar/session-row.tsx:254-258,404`; `apps/desktop/src/styles.css:994-1008,1011-1040,1085-1113,1129-1144,1157-1161`; `apps/desktop/src/app/chat/sidebar/chrome.tsx:21-42,84-108` | Working and stalled alone own the flush 1.25px travelling outline; needs-input stays live but yields to its amber dot, and reduced motion leaves a static ring |
| Transcript tail and jump control | `apps/desktop/src/components/assistant-ui/thread/list.tsx:389-398,651-680`; `apps/desktop/src/app/chat/scroll-to-bottom-button.tsx` | Open at the newest content, follow only while parked there, and expose one floating return action after the reader scrolls away |
| Sticky current user prompt | `apps/desktop/src/components/assistant-ui/thread/list.tsx:178-215,333-355`; `apps/desktop/src/components/assistant-ui/thread/user-message.tsx:28-52,321-367`; `apps/desktop/src/styles.css:1538-1569`; `apps/desktop/src/components/assistant-ui/thread/timeline.tsx:113-125` @ `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8` | Group each human turn with the following assistant/tool content; pin that turn's bubble only after its source is fully above, and jump by stable message id without claiming scroll ownership |
| Session grouping | `apps/desktop/src/lib/time.ts:125-165` | Today / Yesterday / This week / Last week / This month / older |
| Grouping vs ranking | `apps/desktop/src/app/chat/sidebar/order.ts:147-159` | Order applies *within* a group, never across |
| Project catalog and selection | `apps/desktop/src/store/projects.ts`; `apps/desktop/src/app/chat/sidebar/index.tsx` | Backend-authored project identity, active project, overview vs drill-in navigation |
| Project grouping and order | `apps/desktop/src/app/chat/sidebar/projects/workspace-groups.ts`; `apps/desktop/src/app/chat/sidebar/order.ts` | Home/explicit/auto tiers, previews, and deterministic fallback ordering |
| Project Gateway RPCs | `tui_gateway/project_tree.py`; `tui_gateway/methods_config.py` | `projects.tree` overview and complete `projects.project_sessions` membership snapshots |
| Sidebar section header | `apps/desktop/src/app/shell/sidebar-label.tsx`; `apps/desktop/src/app/chat/sidebar/sessions-section.tsx:55-93` | Uppercase accent label, tracking, dither mark, header rhythm and trailing action cluster |
| Sidebar header actions | `apps/desktop/src/app/chat/sidebar/index.tsx:245-255,1696-1777`; `apps/desktop/src/app/chat/sidebar/filter-menu.tsx:205-223` | Add-before-filter order, Codicon glyphs, 12px visual size, hover vs always-visible navigation treatment |
| Sidebar grouping selector | `apps/desktop/src/app/chat/sidebar/filter-menu.tsx:74-79,226-246`; `apps/desktop/src/store/layout.ts:217-294,359-362,577-608`; `layout-sidebar-view.test.ts:21-78` | "Updated" is the persisted default; "Project" switches the same catalog into the project tree and leaving it restores the time-grouped list |
| Project creation | `apps/desktop/src/app/chat/sidebar/project-dialog.tsx`; `apps/desktop/src/store/projects.ts:898-960` | New Project owns name + folder input and calls `projects.create`; the header plus is not New Session in overview mode |
| SSH mechanics | `apps/desktop/electron/ssh-connection.ts:130-157,324-374` | `redactSecrets`, error classification, host-key change detection |
| Remote lifecycle | `apps/desktop/electron/remote-lifecycle.ts`, `remote-lifecycle.test.ts`; `hermes_cli/main.py:510-518,664-689,10947-11021`; `hermes_cli/profiles.py:2458-2492` | Login-shell discovery, explicit default/named profile home resolution, OS-home-only token allowlist, exact ownership lock, exclusive/no-follow token upload, spawn-failure cleanup, and bounded TERM proof |
| Served dashboard token | `apps/desktop/electron/dashboard-token.ts`, `dashboard-token.test.ts`, and `remote-lifecycle.ts:733-751,876-960` | The token injected by the served dashboard becomes final only after a post-fetch owned-child check; fetch/parse failure deliberately falls back |
| Remote lock consumer | `hermes_cli/dashboard_procs.py:722-838` and `tests/hermes_cli/test_orphan_desktop_serve_reap.py:113-187` | The lock is a cross-runtime ABI: exact schema/field names/types/bounds/log suffix determine whether Hermes spares the live remote backend |
| Connection config | `apps/desktop/electron/connection-config.ts` | Remote profile and connection terminology |
| JSON-RPC contract | `apps/shared/src/json-rpc-gateway.ts` | Request/error/event envelope and method names |
| Web client | `web/src/lib/gatewayClient.ts` | WebSocket auth, correlation, close and event handling |
| Gateway HTTP/WS | `hermes_cli/web_server.py` | `/api/health`, `/api/ssh/ownership`, public index token injection, and `/api/ws` authentication |
| Sessions and prompts | `tui_gateway/server.py` plus its tests | Durable/runtime identity, session methods, `prompt.submit`, event payloads |
| Composer contract | `apps/desktop/src/app/chat/composer/index.tsx:880-1085`, `controls.tsx:42-390`, `attachments.tsx:18-233`, `model-pill.tsx:26-173` | Editor, send/stop/queue, attachment and model visual/functional boundaries |
| Composer state seams | `apps/desktop/src/app/chat/composer/hooks/use-composer-{submit,draft,queue,esc-cancel,voice}.ts` and their tests | Submit acknowledgement, draft identity, parked turns, queue, safe cancel and voice lifecycle |
| Composer correction/status authority | `apps/desktop/src/app/chat/composer/hooks/use-composer-submit.ts`, `apps/desktop/src/app/chat/composer/status-stack/`, `apps/desktop/src/lib/desktop-git.ts` | Redirect is distinct from steer; queue ownership is durable-session local; Desktop remote coding uses its authenticated `/api/git` facade, which is not an Android Gateway authority |
| Composer completions/status | `apps/desktop/src/app/chat/composer/{completion-drawer,context-menu,contrib,status-stack}/` plus tests | Context actions, all completion providers, contribution gaps and status stack states |
| Composer model authority | `apps/desktop/src/app/chat/composer/model-pill.tsx:26-173`; `apps/desktop/src/app/session/hooks/use-model-controls.ts:238-286`; `apps/desktop/src/store/session.ts:20-29,616-620` | Catalog/effective state is Gateway truth; a fresh-draft pin is scoped local state, while live deferred model changes remain next-turn intent until `session.info` confirms |
| Composer completion authority | `apps/desktop/src/app/chat/composer/hooks/use-live-completion-adapter.ts:24-153`; `apps/desktop/src/app/chat/composer/hooks/use-slash-completions.ts:61-250`; `apps/desktop/src/app/chat/composer/hooks/use-at-completions.ts:16-214`; `apps/desktop/src/app/chat/composer/url-refs.ts:1-103`; `apps/desktop/src/app/chat/composer/path-refs.ts:1-103` | Fence async results by trigger, text, runtime/cwd and generation; serialize URL/path/session references as canonical text before considering rich chips |
| Required input | `apps/desktop/src/app/session/hooks/use-message-stream/gateway-event.ts:1159-1390`; `clarify-tool.tsx`, `tool/approval.tsx`, `prompt-overlays.tsx` | Clarify, approval, sudo, secret, response routing and safe refusal |

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

## 2. Freeze the rendered visual contract

Source tells you intent; the renderer tells you what won. Capture both before
styling Android. Do not use a screenshot as a substitute for reading source,
and do not use source as an excuse to skip looking at the actual pixels.

The Desktop app must be a dev renderer with CDP enabled. Never relaunch or kill
the user's app to obtain a port. Use a **disposable pinned clone/export** plus
the existing e2e mock sandbox or Playwright fixture; never run bare
`npm run perf:serve`, because it copies real config, `.env`, and auth by
default. Allocate explicit dev-server and CDP ports for the capture run. Put the
target surface into a temporary local-only seeded state containing no private
session text, host, fingerprint, path, token, or credential. Do not add a
production demo session to obtain evidence.

```bash
node .chalk/skills/port-hermes-desktop-surface/scripts/capture-desktop-reference.mjs \
  --name projects-overview \
  --selector '[data-slot="sidebar"]' \
  --expect-sha f82f2dbabd9e66b714f2b4f8a40447fe0c13e732 \
  --match 5174

python3 .chalk/skills/port-hermes-desktop-surface/scripts/capture-android-reference.py \
  --name projects-overview

python3 .chalk/skills/port-hermes-desktop-surface/scripts/build-visual-report.py \
  --name projects-overview
```

The Desktop capture writes `reference.png` plus `contract.json`. The JSON is
not a DOM dump: it records the selected subtree's rendered rectangles,
typography, spacing, colour, borders, opacity, labels, roles, and pseudo-element
font glyphs. The Android capture records the screenshot and device geometry.
Both land under `build/visual-parity/`, which stays untracked. Before accepting
an Android screenshot, record the expected package/activity identity (for this
app, debug `com.hermesagent.mobile.debug` / `com.hermesagent.mobile.MainActivity`)
from `cmd package resolve-activity` and the focused window in the ignored
capture evidence. The report script writes `report.html` there; open that file
and judge both surfaces together. If a future state is unsupported, record it
as `missing` or `partial` in the manifest; never fabricate a screenshot.

For every surface, compare this inventory explicitly:

| Contract | Default |
|---|---|
| Header copy and capitalization | Same words and casing |
| Typeface category, weight, tracking, line height | Same treatment; scale only for phone readability |
| Icon family, glyph, visual size | Same family and glyph; keep Android's 48dp hit box around it |
| Control order and alignment | Same order and edge alignment |
| Insets, gaps, row rhythm, radii | Same rhythm, scaled only where touch/readability requires it |
| Surface, stroke, accent and text roles | Same semantic token |
| Visible states | Capture default, selected/open, loading, empty, error and disabled when they exist |

Write a deviation ledger beside the implementation evidence:

```text
Desktop: hover-revealed 12px Add glyph inside a 24px header action.
Android: always-visible same glyph inside a 48dp target.
Reason: touch has no hover; visual weight and placement are unchanged.
```

Valid reasons: touch mechanics, viewport space, accessibility, or an explicit
mobile priority. “Material does this by default” is not a reason. Neither is
“not implemented yet”: call that an omission and keep the port incomplete.

## 3. Classify the state before writing UI

Desktop's authority model, and where each kind lives here:

| Desktop authority | Android home | Rule |
|---|---|---|
| Backend-authoritative sessions/transcripts | `data/session/SessionCache` | Merge, never clobber; rows leave only on an explicit tombstone |
| Backend-authoritative project catalog/membership | `data/session/SessionCache` | Replace the overview snapshot and one selected project's hydrated membership; never infer from cwd |
| Machine/runtime facts | `GatewayConnectionManager`, `RemoteHermesLifecycle`, SSH adapter | One resolver per policy |
| Connection-scoped (runtime ids, turn buffers, in-flight tools, generation) | `LiveGatewaySessionRepository` / connection owner | Dies with the connection; durable ids never enter runtime-only calls |
| Saved view preferences | `SidebarViewStore` / `HermesPreferences` | Persist the user's grouping choice locally; never send it to the Gateway or use it as project authority |
| Durable local draft text | `SessionDraftStore` + ViewModel | Canonical durable session id only; text only; no backup/transfer; bounded 50-entry MRU; never Gateway/cache authority |
| UI-only (search, drawer, scroll, draft selection/model/attachment state) | ViewModel / `rememberSaveable` | Never persisted beyond what the user would expect |

If you cannot say which row a piece of state belongs to, stop and decide. That
choice is the port.

## 4. Adapt to the phone

Preserve: hierarchy, density, typography ratios and treatment, icon family,
control order, spacing rhythm, colour semantics, flatness, transcript grammar
(bubble vs flat prose vs scaffolding), and session identity.

Replace, and say so in the PR:

| Desktop | Android |
|---|---|
| Hover reveal / tooltips | Always-visible affordance, or long-press; `contentDescription` always |
| Right-click menu | Long-press |
| Nested grouping submenu | One flat grouping section in the filter menu; nested pointer menus are brittle on a phone |
| Persistent sidebar | Modal drawer under 720dp, persistent rail at or above it |
| Enter submits | Explicit send tap; Enter inserts a newline (no modifier key on a soft keyboard) |
| Route overlays as cards | Full-screen destination with a back affordance; system back leaves it |
| 13px body text | 15sp, with the rest of the scale moved by the same factor |

Non-negotiables on this side: 48dp touch targets, a `contentDescription` on
every icon-only control, `imePadding()` where a composer meets the keyboard,
and no state that depends on an animation running.

## 5. Prove it

- Unit-test the pure parts (grouping, parsing, policy) with fixed clocks and
  fixed locales. A test that reads the machine's timezone is not a test.
- Capture Desktop and Android in the same named state. Inspect the images and
  computed contract; a green Compose test cannot prove visual parity.
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
./gradlew check              # offline unit tests (debug + release), lint, repo invariants
./gradlew assembleDebug
python3 scripts/check-composer-parity.py \
  --upstream "$HOME/.hermes/hermes-agent" # optional read-only pin/path/citation drift check
python3 scripts/check-ci-workflow.py       # static exact-head workflow contract
git diff --check
```

## 6. Capture what you learned

Before you call it done, edit **this file**: add the upstream paths that
mattered, the pitfalls you hit, and delete steps that turned out to be noise.
A workflow that only grows is a diary, and nobody reads a diary.

For composer controls, two boundaries are easy to lose: `model.options` and
`session.info` own effective model/provider/reasoning/fast state, while a new
draft's manual pick is only a connection/profile-scoped local preference until
the create request is linearized. Likewise, completion results are remote
workspace suggestions, not Android file paths. Keep `content://`, Android local
paths, and clipboard data out of wire text. File/image acquisition uses bounded
Android reads plus the staged Gateway byte handoff; folders and clipboard-image
acquisition stay unavailable until equivalent flows exist. The safe completion
surface is URL/snippet insertion plus canonical text completion with
stale-result fencing.

Slice 4 found a similarly important authority boundary: Desktop remote coding
inspects repository state through its authenticated
`apps/desktop/src/lib/desktop-git.ts` and `/api/git` facade. Android now uses the
same boundary through its connection-owned `GatewayHttp`: SSH connections add
the loopback session token, native remote connections add their bearer token,
and feature code sees neither credential nor origin. A status request is only
legal for the exact bounded `cwd` reported by `session.info`; never infer a
repository path from a title, project label, phone filesystem, or stale global
checkout. `GET /api/git/status` supplies branch and line deltas,
`POST /api/git/review/pr-list` supplies the branch PR link, and
`GET /api/git/review/list` is the branch/delta link destination. If either the
authenticated transport or server path is absent, render no coding claim.

Desktop's task panel is likewise not a generic tool feed. Only `todo` tool
payloads enter it, parsed in Desktop field priority (`todos`, `result`, then
arguments) with bounded JSON-string/wrapper recursion. Show every valid task in
the stack's bounded scroll region; never truncate the list. An unfinished list
clears at authoritative turn settlement, while a completed/cancelled list
lingers for four seconds so the final checkmark can land. Keep the queue
profile-scoped and session-keyed locally, and keep runtime IDs and Android URIs
out of its durable records.

The running-session outline added a motion-specific sidebar pitfall: derive the
ring from the same backend-authoritative status that paints the dot, never from
a local animator or a streaming callback. In particular, `Working` and
`Stalled` get the ring, while `NeedsInput` is still live but deliberately yields
to its amber action cue; `Background` remains unringed. Keep the decoration as
a sibling paint layer so it cannot change row measurement, hit testing, or the
single row accessibility label. Android's animation clock respects system
duration scale; use `ValueAnimator.areAnimatorsEnabled()` to omit the infinite
clock and paint a visible phase-zero ring when animations are removed. The full
source/deviation ledger is
[`docs/parity/sidebar-running-outline.md`](../parity/sidebar-running-outline.md).
