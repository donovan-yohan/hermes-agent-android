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

Historical panel/update citations below retain that SHA. The Recent logs
implementation audit has its own explicitly scoped pin; it does not restamp
the old panel/update source or any rendered evidence.

## Recent logs implementation audit (2026-10-22)

Inspected source: `81a55437f3f31a9f12a30c53b6c5d49d420898aa`.
Every citation in this subsection is against that SHA.

| Contract | Source |
|---|---|
| Four files and four levels, defaults agent/ALL | `apps/desktop/src/app/command-center/index.tsx:52-53,175-181` |
| Refresh on entry/file/level; request 200 lines | `apps/desktop/src/app/command-center/index.tsx:212-233,258-278` |
| Authenticated profile-scoped GET; ALL omitted from query | `apps/desktop/src/api/config.ts:94-128` |
| Profile-owned path, missing file is successful empty; level filtering delegated to tail reader | `hermes_cli/web_routers/status.py:841-881` |
| Profile query selects a directory scope, not a record-by-record isolation guarantee | `hermes_cli/web_server_profiles.py:277-324` |
| File keys include desktop | `hermes_cli/logs.py:33-54` |
| Backend minimum-severity ordering | `hermes_cli/logs.py:74-78` |
| Actual log controls, error and tail | `apps/desktop/src/app/command-center/index.tsx:523-569` |
| Current search highlights and steps, rather than hiding lines | `apps/desktop/src/components/chat/log-search.tsx:21-39,50-112` |
| Literal, case-insensitive search and severity-column regression fixtures | `apps/desktop/src/lib/log-search.test.ts:5-41` |
| Shared tail now adds jump/copy actions, follow-tail and severity rules | `apps/desktop/src/components/chat/log-tail.tsx:23-38,56-94,96-153` |
| Heading/empty and current search placeholder | `apps/desktop/src/i18n/en.ts:2437-2439,2458-2460` |

Android now enables the existing Recent logs block, not a new destination.
It reads through the connection-owned authenticated `GatewayHttp`, including
the cancellable guarded enqueue path. No origin, token, raw HTTP client or
backend-written exception is exposed to this feature. ALL omits `level`;
other levels send uppercase IDs. The backend owns minimum-level filtering.
A legacy blank/unspecified profile is omitted; a concrete selection, including
literal `default`, stays explicit so a Gateway launched in a named profile
cannot silently serve that launch profile instead.

Current upstream selects the profile's log directory; these are still host-produced
file tails, not a client-enforced per-record authorization stream. Shared or
unscoped records may appear in host log files, and unsupported older hosts may
ignore the query. Selection/result fencing does not prove record isolation.

The read captures ready-leg transport identity, endpoint generation, active
in-memory profile plus its monotonic scope generation and a selection/lifecycle
generation. The scope generation also fences a fast A → B → A switch whose
intermediate StateFlow value is conflated. `isCurrent` checks
these again after credential resolution and at enqueue; return publication
checks them too. The profile comes from the chat scope's read-only host
projection, not its asynchronously saved preference. File/level changes cancel
the previous read and clear its tail. Leaving/resuming the destination or
backgrounding/foregrounding the Activity revokes/restarts log work. Connection
or profile changes clear the tail, selection and query. No log text is persisted.

Bounds: request 200 rows, refuse a body over 512 KiB or a malformed/non-string
row list or more than 200 rows, then redact before applying the existing
per-line cap. Decode/sanitization runs on a separate Default dispatcher, with
cancellation checks between passes, during identifier/value scans and per row.
A monotonic credential tokenizer consumes each identifier/value once, rather
than retrying long credential-key prefixes at every suffix. Whole-tail private
key redaction precedes assignment parsing so complete/truncated PEM blocks and
quoted multiline values are not split into visible continuations.
Log-specific passes remove credential assignments (including AWS access-key,
secret-key and session/security-token labels), Bearer/Basic headers, structured
POSIX/Windows paths, URLs, hostname-shaped text, IPs and fingerprints. Single-label
hosts are masked in explicit host/server/machine/node/peer/endpoint assignments
and known connection/host phrases such as `connected to`.
The conservative hostname rule also hides dotted logger/file names. This is
not a claim to detect arbitrary unlabelled secrets or machine names in prose,
nor to erase all
immutable strings or HTTP stack buffers. The transferred response byte array
is wiped, including on stale returns. Raw refusal envelopes are never painted.

The guarded HTTP continuation remains suspended through bounded body consumption,
not merely response headers. Cancelling a log read therefore cancels the actual
OkHttp call while a chunked body is held; response ownership closes in `use`.
Results abandoned during continuation resumption or the IO-to-caller return are
explicitly wiped (both success bytes and captured refusal envelopes). This does
not claim that the already admitted host request was retracted, or that okio's
pooled segments and immutable decoded strings were erased. Unguarded legacy
HTTP calls retain their existing blocking execution path; full-lifetime call
cancellation here is a guarded-path guarantee.

The requested substring filter remains local, literal, trimmed and
case-insensitive over the redacted bounded tail. It does not send search text
to the host or refetch. Current Desktop instead highlights matches and exposes
previous/next controls (visible disabled with WIP here while filtering). This is documented drift under #127, not latest-source
search parity. `Filter log lines...` remains the historical truthful filter
copy; heading, file/level labels and `No logs loaded yet.` are unchanged at the
new pin. Refresh is an explicit touch action replacing the Desktop refresh
hotkey. Safe load/reconnect failure copy is Android-owned rather than a raw
exception. Loading, successful empty, failure/retry and populated tails are
distinct states.

Focused evidence: `SystemLogsControllerTest` (virtual-time ownership, selection,
redaction, empty/malformed/error/retry and buffer ownership),
`SystemLogsTransportTest` (production authenticated transport, real bound,
pre-enqueue revocation and post-header pause), `GatewayHttpBodyOwnershipTest`
(held chunked socket body, success/refusal cancellation-race disposal and
unknown-length streaming cap), `SystemLogsJourneyTest` (usable controls, selection,
filter/clear, states and lifecycle disposal), and updated `SystemJourneyTest`.
No emulator or physical-device operations were performed in this slice.
Rendered logs evidence remains pending: #127, the existing open Recent logs
owner. Review ceiling: Concern; independent review and device acceptance are
not claimed. #127 also retains background update checks and contract-skew
warning; this capability does not close the whole issue. The latest shared
LogTail's top/bottom/copy controls are also visible disabled with WIP, rather
than silently missing. Automatic follow-tail and severity gutter decoration
remain explicit deferred details, not claimed as implemented by this read port.

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

## Candidate-source control audit (2026-10-22; not a visual repin)

A separate [nested-control audit](desktop-control-reconciliation.md) inspected
Desktop `81a55437f3f31a9f12a30c53b6c5d49d420898aa` separately. It does not replace
the historical Pin or report below. At that candidate, Recent logs imports real nested controls:
previous/next match (`apps/desktop/src/components/chat/log-search.tsx:89-103`),
Clear (`apps/desktop/src/components/ui/search-field.tsx:114-128`), and Copy / Jump
to top / Jump to bottom (`apps/desktop/src/components/chat/log-tail.tsx:90-122`).
Those actions are absent from the baseline System placeholder, not implemented
by its painted labels. #127 owns the missing controls as well as retrieval and
redaction. Command Center Maintenance also has curator and builtin-memory reset
operations; those remain separate #73 omissions, not part of a logs feature.
The Android comparison baseline is `65080e8a37e8d546869528e43c162503015b94c7`.
The historical ledger below retains Desktop `3ca096de5f8183cb2e0ec23673f294d5978656a3`.

## Divergences

Classified for `scripts/check-parity-evidence.py`; the ledgers above carry the argument.

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| System panel lives in the command palette | mobile-adaptation | A Settings destination, placed after Gateways and before Plugins | A phone has no palette; a 360 dp viewport routes by list-then-detail, and the panel's subject is the Gateway the rows above it configure |
| Overlay scrim is `bg-black/22` over a `0.125rem` backdrop blur, black in every skin (`app/overlays/overlay-view.tsx:77`; the session picker's is `bg-black/15` with a 1px blur, `components/session-picker.tsx:48`) | mobile-adaptation | Black at 32% with no blur, one `overlayScrim` token every sheet and the sessions drawer read | Desktop separates the overlay from the page twice — a wash *and* a blur — a `ModalBottomSheet` scrim on this minSdk has no blur to spend — `RenderEffect` only exists from API 31 and Compose exposes no backdrop blur for the content *behind* a modal — so the wash has to do both jobs alone; 0.32 is the weight that reads as separation on a 360 dp viewport where the sheet covers most of what is behind it. Black rather than the theme foreground is Desktop's own literal: seeding it from `textPrimary` made a dark skin's scrim *lighten* the transcript behind it. `ThemeSemanticParityTest` pins the token in both modes for every preset, and every sheet, the picker and the drawer pass it explicitly rather than leaning on Material's default. Rendered side-by-side: pending: #147 |
| Panel's `Update Hermes` fire-and-forgets the POST and polls 21.6 s (`index.tsx:266-301`) | mobile-adaptation | Opens the updates sheet, driven by the app-scoped `runBackendUpdate` port | An apply takes minutes and the person puts the phone down; Desktop's own robust path for the same endpoint is `store/updates.ts:638-766` plus the overlay, which its `Update Hermes` toast action already routes to |
| Budget exhausted mid-restart reports `applyStatus.failed` (`updates.ts:745-751`) | mobile-adaptation | Reports `applyStatus.noReturn` when the deadline expired while the host was not answering; `failed` otherwise | On the Remote route over a tunnel — the topology this app is built around (`docs/adr/0002-shared-remote-gateway.md`) — the restart blackout is the ordinary way an apply ends, so each of Desktop's two strings is used for the state it was written for rather than telling someone their working server failed |
| Recent logs file/level reads | mobile-adaptation | Enabled authenticated bounded tail; lifecycle cancellation and connection/profile/selection fences | Touch destination lifetime replaces Desktop panel visibility; #127, scoped implementation audit above |
| Current Desktop highlight log search | drift | Requested local substring filter retains historical filter copy | #127 — latest source search delta explicitly retained for follow-up, not claimed as parity |
| Previous/next match navigation | omission | Visible disabled behind WIP when the filter has text; clear remains enabled | coming soon — #127 owns current Desktop search navigation; labels verbatim `apps/desktop/src/i18n/en.ts:663-665` at `81a55437f3f31a9f12a30c53b6c5d49d420898aa` |
| Shared LogTail top/bottom/copy controls | omission | Visible disabled behind WIP in a scrollable touch row above the tail | coming soon — #127; `apps/desktop/src/components/chat/log-tail.tsx:96-123` and `apps/desktop/src/i18n/en.ts:5016-5020` at `81a55437f3f31a9f12a30c53b6c5d49d420898aa`; labels replace hover-only glyphs for discoverability on touch |
| Shared LogTail auto-follow and severity gutter | omission | Manual scrolling and the backend's severity text only | deferred: #127 — `apps/desktop/src/components/chat/log-tail.tsx:23-38,56-94,138-153` at `81a55437f3f31a9f12a30c53b6c5d49d420898aa`; current tail decoration/scroll details are not claimed by this slice |
| Desktop refresh hotkey, pointer tabs and flexible tail pane | mobile-adaptation | Explicit Refresh action, scrollable 48 dp tab targets and bounded-height tail | Phones have no refresh chord or hover and must keep controls reachable in a narrow destination |
| Raw log/error text | mobile-adaptation | Redacted bounded tail and safe actionable error; conservative hostname masking can hide dotted logger names | Logs can carry credentials and machine identities; #127 explicitly owns the redaction contract |
| Overlay footer `applyingClose` (`en.ts:2642` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`) | omission | Absent | non-goal: it describes an Electron window closing itself and the app relaunching, neither of which a phone does; saying it would describe something that will not happen |
| Overlay `BrandMark` above the title (`updates-overlay.tsx:247`) | omission | Absent | non-goal: a 64 px logo at the top of a bottom sheet is a fifth of a phone's visible height, and the sheet is already inside Hermes — Desktop's overlay is a separate window that has to identify itself |
| Blockers view and `formatBlockerCommandLine` (`updates-overlay.tsx:83,433-455`) | omission | Absent | non-goal: the blocker scan is an Electron-main spawn (`electron/main.ts:3882-3892`) with no HTTP route, and it is client-target only |
| `Branch X · Commit Y` hint (`about-settings.tsx:204`) | omission | Absent | non-goal: it is the Electron clone's own git state; no Gateway route exposes a branch or a sha outside the update receipt |
| Client self-update, `See what's new` for the client target | omission | Absent | non-goal: this app is not a Hermes install and does not update itself; its updates come from the store |
| `restartBackend` / "Restart backend" (`model-settings.tsx:940-955`) | omission | Absent | non-goal: it is Electron IPC `hermes:backend:recycle` (`preload.ts:479`); no HTTP endpoint restarts `hermes serve` itself |
| `ManagedUpdatesSection` / `updateAll` (`managed-updates.ts:34-47`) | omission | Absent | non-goal: gated on the Electron SSH bridge (`preload.ts:191,194`) and renders null without it; this slice is the Remote route's HTTP surface |
| 30-minute background update poll and the `Update ready` toast (`updates.ts:956-997`, `:209-249`) | omission | Absent | out-of-scope: #127 — this app has no in-app notification stack (#73), so the surfacing decision belongs with that issue |
| Backend contract-skew warning `REQUIRED_BACKEND_CONTRACT` (`updates.ts:95-108,151-180`) | omission | Absent | out-of-scope: #127 — a sticky toast with an `Update Hermes` action, which needs the same missing stack |
| `pre_update` → `post_update` versions and `fresh_recovery.serve_units` | omission | Read and held in `GatewayUpdateState.receipt`, rendered nowhere | deferred: #127 — Desktop has no string for any of it, and inventing one is the drift this gate exists to catch |
| `actionStartedWaiting` synthesised log line (`en.ts:1572`) | omission | Absent | non-goal: Desktop synthesises that line only when all 18 restart polls expire with no status; Android reports restart progress in its action state instead of injecting client-authored text into the host-produced Recent logs tail |

The four log-file tabs are **not** a divergence: `desktop` is a real file the
Gateway's own `/api/logs` serves (`hermes_cli/logs.py:32-41` @ the pin), so all
four ship exactly as Desktop lists them, now enabled by the scoped logs port.

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
| Row placement, traversal order, touch targets, spoken description and enabled log controls | `SystemJourneyTest`, `SystemLogsJourneyTest` |
| Every sheet branch renders its verbatim Desktop copy; an apply offers no way out | `UpdatesOverlayJourneyTest` |

## Visual report

- pending: #126 — the System panel and the backend updates sheet
- pending: #147 — the `overlayScrim` wash, on every sheet and the sessions drawer
- pending: #127 — the newly enabled Recent logs states at `81a55437f3f31a9f12a30c53b6c5d49d420898aa`

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

## Historical panel/update review verdict

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
