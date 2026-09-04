# System panel, Update Hermes and Restart gateway: source and divergence ledger

Hermes Desktop's command-center **System panel** and its **updates overlay**
(backend target), ported per
[`docs/workflows/port-desktop-surface.md`](../workflows/port-desktop-surface.md)
as a Settings destination plus a bottom sheet:
`ui/system/SystemScreen.kt`, `ui/system/UpdatesOverlay.kt`,
`ui/system/SystemViewModel.kt`, `data/updates/`, and the six new routes on
`data/gateway/GatewayRestClient.kt`.

## Pin

| Source | Pin | Read via |
|---|---|---|
| Desktop renderer, Gateway HTTP, CLI | `hermes-agent` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3` | read-only checkout; every citation taken with `git show <sha>:<path>` |

Every `path:line` below is against that SHA.

## Paths that settled the port

| Question | Path |
|---|---|
| The panel: order, dot, actions, progress line, logs block | `apps/desktop/src/app/command-center/index.tsx:423-505` |
| The panel's own restart mechanics (18 × 1200 ms, `lines=180`) | `apps/desktop/src/app/command-center/index.tsx:266-301`, `store/system-actions.ts:19-48` |
| Where Desktop shows the backend version | `command-center/index.tsx:440-442` — `/api/status`, not About |
| The robust backend-update path | `apps/desktop/src/store/updates.ts:638-766` |
| Post-update socket recovery | `store/updates.ts:538-557`, comment `:543-550` |
| Check mapping (`supported`, `behind`, `targetSha`) | `store/updates.ts:351-364,366-393` |
| The updates overlay, backend target | `apps/desktop/src/app/updates-overlay.tsx:72-98,112,176-280,385-431` |
| Changelog grouping, caps, hidden types, dedupe | `apps/desktop/src/lib/commit-changelog.ts:40-179` and its test file |
| "no release notes for this install type" branch | `apps/desktop/src/lib/update-copy.ts:34-41` |
| Backend status payload | `hermes_cli/web_server.py:3771,4011-4031` |
| Update check | `hermes_cli/web_server.py:5211-5303` |
| Update start, and **refusal as HTTP 200** | `hermes_cli/web_server.py:5078-5154`, esp. `:5088-5095,5117-5124` |
| Action status, durable action id, receipt summary | `hermes_cli/web_server.py:5814-5887,5890-5920`, `:4814-4839` |
| Full receipt, incl. `serve_units` / `stale_runtimes` | `hermes_cli/web_server.py:5923-5945`, `hermes_cli/update_receipt.py:60-73,135-155` |
| Gateway restart is the *messaging* gateway, and exit 0 is a handoff | `hermes_cli/web_server.py:4988-5002,4842-4843,4939`, **`:4598-4604`** |
| Log files the host actually serves | `hermes_cli/logs.py:32-41` |
| Copy | `apps/desktop/src/i18n/en.ts:1548,1561-1575,1594-1596,2597-2675` |

## State classification

| Desktop state | Where it comes from | Android |
|---|---|---|
| `status === null` | no `/api/status` answer yet | `SystemUiState.status == null` → the loading line |
| gateway running / stopped | `status.gateway_running` only; `gateway_state` is read by neither | dot + `Messaging gateway running` / `stopped` |
| version + session count | `/api/status` | `Hermes {version} · Active sessions {count}` |
| action running / done / failed | `getActionStatus` poll | `SystemActionState` + `SystemActionPhase` |
| `systemError` | a thrown action | `SystemUiState.actionError` (and `statusError` for the read) |
| overlay: checking / no status / unsupported / check-failed / up to date / available | `$backendUpdateStatus` | `SystemUiState.checking` + `UpdateCheckState` |
| overlay: prepare / pull / restart / done / manual / error | `$backendUpdateApply.stage` | `GatewayUpdateStage` |
| `applyStatus.*` | `translateNow('updates.applyStatus.…')` | `GatewayUpdateStatusKey` → `SystemCopy.applyStatus` |

## Mobile adaptation ledger

| Desktop | Android | Reason |
|---|---|---|
| Reached from the command palette (`⌘K`), section `system` | A row in Settings, third of four | A phone has no command palette; the panel is about the Gateway, so it sits with the Gateway rows |
| Status row with the actions at its right edge | Actions stacked under the sub-line | Desktop stacks them itself below `47.5rem` (`index.tsx:427,444`), and every phone is below it |
| `Button size="xs"` | The same two labels at the 48 dp touch floor | The Android touch-target minimum, which Desktop does not have to meet |
| `text` / `textStrong` button variants | `TextButton(color = textTertiary)` / `TextButton(strong = true)` | Same ink, same weight-and-underline distinction (`components/ui/button.tsx:31,34`) |
| Error line inside the logs header row | Directly under the action it is about | A phone column has no header row to hang it in |
| Modal overlay window | Bottom sheet | The app's one modal shape; `ConfirmSheet` set the precedent |
| Overlay scrim `bg-black/22 backdrop-blur-[0.125rem]` (`app/overlays/overlay-view.tsx:77`) | Black at 32%, no blur (`HermesTokens.overlayScrim`) | Android composites no backdrop blur behind a modal, so the alpha carries the whole separation on its own |
| Lemniscate loader | `WorkingDots` | The app's one reduced-motion-safe working indicator |

## Divergences

Classified for `scripts/check-parity-evidence.py`; the ledgers above carry the argument.

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| System panel lives in the command palette | mobile-adaptation | A Settings destination, placed after Gateways and before Relay channels | A phone has no palette; a 360 dp viewport routes by list-then-detail, and the panel's subject is the Gateway the rows above it configure |
| Overlay scrim is `bg-black/22` over a `0.125rem` backdrop blur, black in every skin (`app/overlays/overlay-view.tsx:77`; the session picker's is `bg-black/15` with a 1px blur, `components/session-picker.tsx:48`) | mobile-adaptation | Black at 32% with no blur, one `overlayScrim` token every sheet and the sessions drawer read | Desktop separates the overlay from the page twice — a wash *and* a blur — a `ModalBottomSheet` scrim on this minSdk has no blur to spend — `RenderEffect` only exists from API 31 and Compose exposes no backdrop blur for the content *behind* a modal — so the wash has to do both jobs alone; 0.32 is the weight that reads as separation on a 360 dp viewport where the sheet covers most of what is behind it. Black rather than the theme foreground is Desktop's own literal: seeding it from `textPrimary` made a dark skin's scrim *lighten* the transcript behind it. `ThemeSemanticParityTest` pins the token in both modes for every preset, and every sheet, the picker and the drawer pass it explicitly rather than leaning on Material's default. Rendered side-by-side: pending: #147 |
| Panel's `Update Hermes` fire-and-forgets the POST and polls 21.6 s (`index.tsx:266-301`) | mobile-adaptation | Opens the updates sheet, driven by the app-scoped `runBackendUpdate` port | An apply takes minutes and the person puts the phone down; Desktop's own robust path for the same endpoint is `store/updates.ts:638-766` plus the overlay, which its `Update Hermes` toast action already routes to |
| Budget exhausted mid-restart reports `applyStatus.failed` (`updates.ts:745-751`) | mobile-adaptation | Reports `applyStatus.noReturn` when the deadline expired while the host was not answering; `failed` otherwise | On the Remote route over a tunnel — the topology this app is built around (`docs/adr/0002-shared-remote-gateway.md`) — the restart blackout is the ordinary way an apply ends, so each of Desktop's two strings is used for the state it was written for rather than telling someone their working server failed |
| `Recent logs`: heading, four file tabs, four level tabs, filter field, empty state | omission | All six render, all six disabled behind the `WIP` chip | pill-owed: #127 — log fetch, redaction and level filtering are that issue; the controls ship visible so the surface is not silently smaller |
| Overlay footer `applyingClose` (`en.ts:2642`) | omission | Absent | non-goal: it describes an Electron window closing itself and the app relaunching, neither of which a phone does; saying it would describe something that will not happen |
| Overlay `BrandMark` above the title (`updates-overlay.tsx:247`) | omission | Absent | non-goal: a 64 px logo at the top of a bottom sheet is a fifth of a phone's visible height, and the sheet is already inside Hermes — Desktop's overlay is a separate window that has to identify itself |
| Blockers view and `formatBlockerCommandLine` (`updates-overlay.tsx:83,433-455`) | omission | Absent | non-goal: the blocker scan is an Electron-main spawn (`electron/main.ts:3882-3892`) with no HTTP route, and it is client-target only |
| `Branch X · Commit Y` hint (`about-settings.tsx:204`) | omission | Absent | non-goal: it is the Electron clone's own git state; no Gateway route exposes a branch or a sha outside the update receipt |
| Client self-update, `See what's new` for the client target | omission | Absent | non-goal: this app is not a Hermes install and does not update itself; its updates come from the store |
| `restartBackend` / "Restart backend" (`model-settings.tsx:940-955`) | omission | Absent | non-goal: it is Electron IPC `hermes:backend:recycle` (`preload.ts:479`); no HTTP endpoint restarts `hermes serve` itself |
| `ManagedUpdatesSection` / `updateAll` (`managed-updates.ts:34-47`) | omission | Absent | non-goal: gated on the Electron SSH bridge (`preload.ts:191,194`) and renders null without it; this slice is the Remote route's HTTP surface |
| 30-minute background update poll and the `Update ready` toast (`updates.ts:956-997`, `:209-249`) | omission | Absent | out-of-scope: #127 — this app has no in-app notification stack (#73), so the surfacing decision belongs with that issue |
| Backend contract-skew warning `REQUIRED_BACKEND_CONTRACT` (`updates.ts:95-108,151-180`) | omission | Absent | out-of-scope: #127 — a sticky toast with an `Update Hermes` action, which needs the same missing stack |
| `pre_update` → `post_update` versions and `fresh_recovery.serve_units` | omission | Read and held in `GatewayUpdateState.receipt`, rendered nowhere | deferred: #127 — Desktop has no string for any of it, and inventing one is the drift this gate exists to catch |
| `actionStartedWaiting` synthesised log line (`en.ts:1572`) | omission | Absent | non-goal: Desktop synthesises that line only when all 18 restart polls expire with no status; this surface renders no log tail |

The four log-file tabs are **not** a divergence: `desktop` is a real file the
Gateway's own `/api/logs` serves (`hermes_cli/logs.py:32-41` @ the pin), so all
four ship exactly as Desktop lists them, disabled with the rest of the block.

## Executable evidence

| Claim | Test |
|---|---|
| Every route's exact path, method, query, timeout and bound | `GatewaySystemRestClientTest` |
| A refusal arrives as HTTP 200 and is read from `ok`, not the status code | `GatewaySystemRestClientTest` |
| A 404 on the receipt route is a capability, not a retry | `GatewaySystemRestClientTest` |
| Changelog grouping, caps, hidden types, dedupe, capitalisation | `CommitChangelogTest` — Desktop's own fixtures, translated |
| The apply state machine: refusal, exit code, log marker, receipt proof, legacy fallback, budgets, adoption, reset | `GatewayUpdateControllerTest`, on virtual time |
| The forced redial fires once on success and never on SSH, Local, or a user-driven Disconnected | `GatewayBackendUpdateRedialTest` |
| The restart poll's 18 × 1200 ms cadence, and that it stops when the child exits | `SystemViewModelTest`, with a `waits` counter |
| Row placement, traversal order, touch targets, spoken description, disabled row | `SystemJourneyTest` |
| Every sheet branch renders its verbatim Desktop copy; an apply offers no way out | `UpdatesOverlayJourneyTest` |

## Visual report

- pending: #126 — the System panel and the backend updates sheet
- pending: #147 — the `overlayScrim` wash, on every sheet and the sessions drawer

Hermes Desktop was **not rendered** for this change.
`.chalk/skills/port-hermes-desktop-surface/scripts/capture-desktop-reference.mjs`
needs a disposable pinned Desktop dev renderer with CDP, and
`capture-android-reference.py` needs an attached device or emulator; neither was
available in this environment. The capture is recorded as missing rather than
fabricated.

What a renderer would compare, in order:

1. The status block: dot colour and size against `bg-emerald-500` / `bg-amber-500`
   at `size-2`, the running/stopped sentence's weight, and the sub-line's tone.
2. Action **order** and treatment: `Restart gateway` (text) before
   `Update Hermes` (textStrong), right-aligned, no glyphs.
3. The action progress line's exact string, `{name} · running|done|failed`.
4. The logs block's structure: uppercase heading, file tabs then level tabs then
   the filter field, with the level labels lowercased.
5. The updates sheet, every branch: checking, check-failed, unsupported,
   connection-retry, all-set, available (title, body, group labels, bullet rows,
   both buttons, overflow line), applying (stage title, body, status line,
   indeterminate progress, log tail), manual, error, done.

## Review verdict

```text
Parity: System panel + updates overlay (backend) @ 3ca096de5f8183cb2e0ec23673f294d5978656a3
Report: pending: #126
Desktop: app/command-center/index.tsx:423-505, app/updates-overlay.tsx:72-431,
         store/updates.ts:638-766, i18n en.ts:1548,1561-1575,2597-2675
Copy:    verbatim, diffed against en.ts at the pin (curly apostrophes included);
         no invented string where Desktop has one
Order:   unchanged (Restart gateway → Update Hermes; Update now → Maybe later;
         status → sub-line → actions → progress → logs)
States:  every Desktop state above is rendered and asserted in
         SystemJourneyTest / UpdatesOverlayJourneyTest; none was rendered
         side by side against Desktop
Divergences: 4 mobile-adaptation, 0 drift, 12 omission
Verdict: Concern — the change is correct against the pinned source and fully
         classified, but it ships without a rendered side-by-side comparison,
         which the workflow caps at Concern (#126 owes it).
```
