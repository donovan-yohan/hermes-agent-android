# `Show earlier messages` and the transcript window: Desktop-to-Android parity

## Pin

Desktop authority is `3ca096de5f8183cb2e0ec23673f294d5978656a3`.

- Control: `apps/desktop/src/components/assistant-ui/thread/list.tsx:1033-1041` —
  a plain centred rounded pill at the top of the transcript content, inside the
  scroll, no glyph. `showEarlier()` at `:940-955`; the two-stage
  `dom | window | null` resolution at
  `apps/desktop/src/components/assistant-ui/thread/transcript-window.tsx:23-33`.
- Copy: `apps/desktop/src/i18n/en.ts:3520` — `showEarlier: 'Show earlier messages'` (fixed stale en.ts line; the old-pin citation was already off by the known per-region offset).
  There is no loading, disabled or exhausted string, because the control has no
  such state.
- Pill paint: `list.tsx:1035` — `border-border/65`, `bg-(--composer-fill)`,
  `text-muted-foreground`, `rounded-full`, `mx-auto`,
  `mb-(--conversation-turn-gap)`. `--composer-fill` is
  `color-mix(in srgb, var(--dt-card) 90%, var(--dt-background))`
  (`styles.css:1804`); the two seeds are `--dt-card: var(--ui-bg-editor)`
  (`styles.css:390`) and `--dt-background: var(--ui-bg-chrome)`
  (`styles.css:388`), which are this app's `cardSurface` and `chatSurface`.
  `--conversation-turn-gap` is `0.375rem` — 6 px — at `styles.css:479`; this
  app's `spacing.turnGap` is `8.dp` (`HermesTypography.kt:56`), the same gap
  taken up to the mobile spacing step (ledgered below).
- Page size: `LATEST_SESSION_MESSAGES_LIMIT = 120`,
  `apps/desktop/src/api/sessions.ts:440`, used for both the hydration page
  (`getLatestSessionMessages`, `:442-463`) and every older page
  (`getOlderSessionMessages`, `:515-522`). Both always send
  `includeCompacted: true` (`:443-449`).
- Merge: `apps/desktop/src/app/chat/transcript-backfill.ts` whole;
  `mergeOlderTranscriptPage` at `:36-64`, `graftRefreshedTailOntoBackfill` at
  `:66-93`.
- Truncation bookkeeping: `apps/desktop/src/store/transcript-tail.ts:82-96`.
- Wiring per session: `apps/desktop/src/app/chat/index.tsx:272-341` (the per-session window state moved from a single ref to a `Map<string, SessionWindowMemo>` keyed per runtime id at the new pin; the wiring site itself is unchanged).
- Anchoring on prepend: `list.tsx:502-518` records the distance from the bottom
  and `:957-969` re-applies it in the same commit; the reason is at `:541-546`. (At
  the new pin `anchorBeforePrepend` gained an early-return guard — it now skips
  recording entirely rather than recording 0 — while unsettled; a Desktop-internal
  refinement this app's own row/offset anchoring does not depend on.)
- Gateway route: `hermes_cli/web_routers/sessions.py:528-561`, reading
  `hermes_state.py:12869-13016` @ `3ca096de` (range not re-verified against the
  new pin; `hermes_state.py` was broken into many modules and this multi-method
  range was not re-resolved in time).

### The automatic route, pinned separately

`Show earlier messages` grew a second way in upstream after this page was
written, so that half is pinned at
`564aef2946c436500a5e80ee117b66b789b3f99a` — the repo pin — rather than at the
`3ca096de` authority above. It landed as `3ec8042483` and was trimmed by
`474143da81`.

- Gate: `apps/desktop/src/components/assistant-ui/thread/transcript-window.tsx:36-74`
  — the `TOP_EDGE_PX` constant, the `ShouldAutoShowEarlierInput` shape and the
  `shouldAutoShowEarlier` predicate. `474143da81` deleted the injectable
  `topEdgePx` knob (one caller, one constant) and folded the tail into a single
  predicate.
- Wiring: `list.tsx:959-994` — a `scroll` and a `wheel` listener on the scroll
  container, both routed into the same `showEarlier()` the pill calls at
  `:1077`.
- Tests: `should-auto-show-earlier.test.ts` is one invariant test over the pure
  predicate; `list-auto-show-earlier.test.tsx` is one rendered-list journey.
  Android keeps that split — `AutoShowEarlierGateTest` and the lower half of
  `ShowEarlierJourneyTest`.
- There is no new copy. Desktop adds no string for this, and neither does this
  app.

## Reaching the head on a phone

Desktop's signal is a mouse wheel pointing up while `scrollTop` is clamped at 0.
The wheel is not decoration: a browser emits no further `scroll` event once
`scrollTop` is already 0, which is exactly where the reader who wants more
history is standing, so without the wheel the arrival is unobservable.

A phone has no wheel. It has a drag and a fling, and the honest question is
which Compose signal carries the same fact. Three were considered:

1. **The scroll position alone** (`firstVisibleItemIndex`/`ScrollOffset`). Wrong:
   a `LazyColumn` sits at index 0, offset 0 before its opening jump to the tail,
   when a page lands and before the anchor restore, and for the whole life of a
   conversation shorter than the screen. Position alone cannot tell those apart
   from reading intent, which is the same problem Desktop's `loadSettled`,
   `restorePending` and `isAtBottom` gates exist to patch.
2. **`isScrollInProgress` plus `lastScrolledBackward`.** Closer, but neither is
   updated by a delta the list refuses — `LazyListState` returns early at its
   edge — so the direction flag goes stale exactly at the clamp, and a
   programmatic `scrollToItem` opens a scroll session that looks like a gesture.
3. **What the list refused.** A `nestedScroll` connection above the
   `LazyColumn` receives, in `onPostScroll` and `onPostFling`, the part of the
   gesture the list could not consume. That remainder is non-zero *only* at a
   hard clamp, and a positive `y` there means the finger is still pulling
   towards earlier turns with nothing left to give. It is the same fact Desktop
   reconstructs from the wheel, reported rather than inferred, and it covers
   both halves of a touch gesture with one predicate.

This app takes the third. It has a property the wheel does not: every scroll the
app performs for itself — the pane's opening jump to the tail, the prepend
anchor restore — moves a `LazyListState` without dispatching nested scroll at
all, so none of them can forge reading intent. That is why Desktop's
`loadSettled` needs no Android counterpart.

**When the ask is spent, and why that is a correctness rule.** The gesture
*notices* that it reached the head; `onPostFling` — which runs once, after the
drag has ended and its fling has run out — *spends* it. Rate is the smaller half
of the reason: a wheel notch is discrete and a drag is continuous, so asking per
frame would walk a whole conversation in. The larger half is the prepend anchor.
The pane restores it by scrolling the list back to the row the reader was on,
and a `LazyListState` scroll asked for at `MutatePriority.Default` while the
reader's finger owns the list at `UserInput` is **cancelled rather than
queued** — so a page delivered mid-drag lands with its anchor discarded and
drops the reader at the very top of the history they just pulled in. Spending
the reach at the end of the gesture leaves the restore the list to itself;
`ShowEarlierJourneyTest.aPageThePullAskedForLandsWhereTheReaderWasReading` is
that claim, and it fails when the ask is spent mid-drag.

The pill is unchanged and keeps its place as the transcript's leading row. It is
also the only route a reader who cannot drag has — a switch-access or screen
reader user reaches the page through the control, exactly as on Desktop, where
the wheel is likewise the optional half.

## The contract split

### `session.history` request shape

The pinned Gateway (`NousResearch/hermes-agent` `3ca096de5f8183cb2e0ec23673f294d5978656a3`) stamps durable `row_id` values while handling `session.history`; its handler reads the session selector and calls the database with `include_row_ids=True` internally. The client must therefore send only `session_id`. Sending `include_row_ids` as a request field is not a compatibility hedge: strict Gateway validation rejects unknown fields before the handler runs (`GatewayRpcError: invalid params`, `include_row_ids: Extra inputs are not permitted`). Android preserves returned `row_id` values and leaves `TranscriptEntry.rowId` null when the response has no authoritative stamp; local rendering keys are never promoted to durable addresses.

At the pin, Desktop hydrates and refreshes a chat's transcript over REST
(`getLatestSessionMessages`, called from `use-session-actions/index.ts:1235,1478,1786`,
`use-background-sync.ts:131,203`, `use-session-tile-delegate.ts:244`,
`contrib/wiring.tsx:394` — all four paths @ `3ca096de`; not re-resolved against
the new pin, where these files no longer exist at these paths and were not
relocated in time). The `session.history` RPC survives for exactly one
caller: the rewind flow, which needs the whole row-stamped conversation
(`use-prompt-actions/rewind.ts:200,226` @ `3ca096de`; same caveat). Android mirrors that split — the paged
route hydrates, and the RPC remains the contract for a Gateway that has no such
route.

The two contracts do not ship the same rows. `session.history` ships the
Gateway's display projection (`tui_gateway/session_history.py:180-238`); the
REST route ships the stored rows with compaction display applied and nothing
else (`sessions.py:503-525,546-554`). `RestTranscriptProjection.kt` is that projection
ported, so one parser reads both and a page fetched over REST merges into a
transcript hydrated either way.

## Which conversations are windowed, and which are not

The paged route resolves a compression chain FORWARD to its live tip and reads
that session's rows alone (`sessions.py:537-540,546-548`). `session.history`
merges the chain (`get_messages_as_conversation(..., include_ancestors=True)`,
`tui_gateway/methods_session.py:1657-1662`). So on a conversation the Gateway has already
compressed onto a fresh id, the two contracts do not cover the same turns:
windowing it would end `Show earlier messages` at the tip's first row, with the
turns before the compression unreachable and nothing said about it.

Android does not window those. A conversation known to be a compression tip
keeps whole-history hydration and is offered no control at all. The signal is
the list route's own: `list_sessions_rich` projects a compression root forward
to its tip and stamps `_lineage_root_id` on the row it surfaces, and only on
that row (`hermes_state_sessions.py:956-972`, moved from `hermes_state.py`;
the new pin also adds a `_lineage_ids` sibling field, additive and not read by
this app); this app already parses it as
`SessionSummary.lineageRootId`.

That gate is only as good as the fact behind it, and the boundary is stated
rather than papered over: `_lineage_root_id` rides the REST session list and
**not** the `session.list` RPC, so a conversation this connection has only ever
seen over the RPC — an older Gateway, or a leg that fell back — reports nothing
and is windowed at its tip. A compressed conversation reached that way still
loses its ancestors, exactly as Desktop's would. No control copy says otherwise,
because Desktop has no such string and this port invents none.

A session can also be hydrated before any list row for it arrives — a reconnect
resume, a restored active id, a session opened straight from a notification —
and is windowed on the evidence available at that moment. When a later list does
report `_lineage_root_id` for it, the window is retracted there and then and the
control stops being offered (`retractWindowsForCompressionTipsLocked`). The
transcript already on screen is left alone; the next open hydrates it whole.

`include_compacted=false` ending history at the compaction boundary — the case
#68 asked to surface — cannot arise: every read this app makes sends
`include_compacted=true`, as Desktop's does, so in-place compaction summaries
are in the rows rather than a silent cut.

Re-opening a session re-reads only its newest page, so the refreshed tail is
grafted onto the prefix `Show earlier messages` had already loaded rather than
replacing it — Desktop's rule at `transcript-backfill.ts:66-93`. Where the next
page then starts is the window's own arithmetic — the further of the refreshed
tail's end and the offset the previous window had already reached — because an
offset counts stored rows and the kept prefix holds projected entries, which the
projection both splits and drops.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| `resolveShowEarlierAction` spends a materialized DOM page before it asks the store for more (`transcript-window.tsx:23-33`) | mobile-adaptation | Only the `window` stage exists; a press always asks the window | The DOM stage is a render budget for a list that materializes every row it holds. `LazyColumn` composes only what is on screen, so there is no unmaterialized-but-held page to spend first |
| The button stays clickable while a page is in flight and concurrent calls share one promise (`transcript-backfill.ts:126-133`, verbatim) | mobile-adaptation | The control looks identical, and a press while a page is on the wire is ignored | A shared promise needs a promise; the repository is the one place that knows a page is in flight, so the guard lives there. Nothing visible changes — no spinner, no disabled state |
| The prepend is anchored on the scroll container's distance from the bottom (`list.tsx:502-518,957-969`) | mobile-adaptation | Anchored on the transcript row that was on top and the offset into it, restored once the page lands | A `LazyListState` has no scroll height to measure from — only an index, a key and an offset. Keying on the row also survives the leading control disappearing in the same frame, which a pure index cannot |
| The pill is a ~22 px chrome control | mobile-adaptation | The same pill at the 48 dp platform touch floor | A touch target may not be smaller than the floor; the fill, hairline, radius, ink and copy are unchanged |
| `hover:text-foreground` brightens the label on pointer-over (`list.tsx:836`) | mobile-adaptation | Not painted | Touch has no hover state to paint |
| The tool row's collapsed title is `build_tool_preview`, a per-tool phrasing (`agent/display.py:446-595` via `server.py:7740-7756` @ `3ca096de`; not re-resolved) | mobile-adaptation | The primary-argument table (`agent/display.py:353-360` @ `437116f9`) and the generic tail (`:464-469` @ `437116f9`) are ported; the per-tool phrasings above that tail are not | Those branches rephrase the same argument rather than name a different one, and porting them would be a second copy of upstream's tool table to keep in step. The full call still rides the row as `args` and the expanded tool view renders it. One entry moved under the table's stamp: the cron tool's display key is `cronjob_manage` at the pin (`agent/display.py:357` @ `437116f9`), and the pre-rename `cronjob` an older Gateway stored resolves to the same entry (`_LEGACY_TOOL_ALIASES`, `model_tools.py:601-604` @ `437116f9`) so both spellings preview the same action |
| The sidebar pager is an ellipsis glyph with a spinner and a disabled state (`apps/desktop/src/app/chat/sidebar/load-more-row.tsx:17-38` @ `3ca096de`; not re-resolved) | omission | The transcript control shares none of that markup | out-of-scope: #68 — the shared vocabulary is the interaction contract (one explicit press for more, never a scroll that asks), not the visual. Desktop's own two controls differ: the transcript's has no glyph, no spinner and no disabled state |
| `recordTranscriptTail` re-runs `tailStateFromPage` on a refresh, resetting `nextOffset` to that page's length (`transcript-tail.ts:117-125`) | mobile-adaptation | The refreshed tail's offset is taken as the further of itself and the offset the previous window had reached | Desktop drops its backfilled prefix's paging with it on a refresh and re-walks; Android keeps the prefix (`graftRefreshedTailOntoBackfill`, which Desktop also has) and must therefore not re-offer the pages that prefix already holds. Both are measured back from the newest row, so the deeper offset can only overlap — never skip |
| The RPC's tool row is `{role, name, context, args}` and nothing else (`tui_gateway/session_history.py:205-211`) | mobile-adaptation | The projected tool row also carries `content`, `row_id` and `timestamp` | This row follows the REST contract, not the RPC's projection of it: Desktop's own REST reader attaches the stored result (`lib/chat-messages/tool-parts.ts:737` @ `3ca096de`, used at `hydration.ts:186` @ `3ca096de`; not re-resolved), and dropping `row_id` would leave the one row the window cannot dedupe by durable address. A tool row is therefore richer on the paged path than on the RPC path |
| Typed display metadata becomes a system timeline item | mobile-adaptation | Recognized metadata is preserved through REST projection and shared history parsing, then rendered as a compact timeline disclosure; untyped marker-shaped user text remains a user message | `TimelineEventProjectionTest`, `RestTranscriptProjectionTest`, and `TimelineRowRenderTest`; rendered comparison pending: #71 |
| `build_tool_preview` masks recognizable credentials in a `browser_type` call's `text` first (`redact_tool_args_for_display`, `agent/display.py:400-414` @ `3ca096de`, applied at `:456` @ `3ca096de`; not re-resolved) | mobile-adaptation | `browser_type` gets no collapsed preview at all | The masking is `redact_sensitive_text(force=True)` over thirteen credential patterns (`agent/redact.py:831-900` @ `3ca096de`; not re-resolved), not ported. A partial copy would mask the shapes it knew and print the rest while looking checked, so the preview is withheld instead. The call still rides the row as `args`, as it does upstream |
| The pill's bottom gap is `--conversation-turn-gap`, `0.375rem` = 6 px (`styles.css:474`, applied at `list.tsx:836`) | mobile-adaptation | `spacing.turnGap`, 8 dp (`HermesTypography.kt:56`) | The whole type and spacing scale is stepped up for touch; the turn gap follows it rather than being pinned to Desktop's pixel, so the pill sits on the same rhythm as every other turn on this platform |
| One read's tool-call map covers that read (`tui_gateway/session_history.py:196-204`) | mobile-adaptation | The map covers one page | A tool row whose assistant call row fell on the other side of a page boundary renders with its stored `tool_name` and no argument preview. Carrying the map across pages would be per-session repository state with a lifetime nothing else in the projection has, for one row per page |
| `shouldAutoShowEarlier` reads a `wheel` event's `deltaY` at a clamped `scrollTop`, because a browser emits no `scroll` event at 0 (`transcript-window.tsx:53-74`, `list.tsx:959-994` @ `564aef2946`) | mobile-adaptation | Reads the part of a drag or fling the `LazyColumn` refused, through a `nestedScroll` connection above the list | Touch has no wheel. Compose reports unconsumed scroll in `onPostScroll` and `onPostFling`, and that remainder exists only at a hard clamp — the same fact Desktop reconstructs from the wheel, reported rather than inferred, and one predicate covers a drag and a fling alike |
| `TOP_EDGE_PX = 48` gives the wheel 48 px of slack around `scrollTop` 0 (`transcript-window.tsx:36-41` @ `564aef2946`) | mobile-adaptation | The head is exactly `firstVisibleItemIndex` 0 at `firstVisibleItemScrollOffset` 0, and no slack constant exists | The slack absorbs a wheel notch that stops a few pixels short, which only matters while scroll *position* is the signal. A refused delta is reported at the clamp itself, so there is nothing for slack to absorb and no threshold to tune — one fewer knob, for the reason `474143da81` deleted upstream's injectable one |
| `loadSettled` withholds the automatic page until a session's opening scroll restore has landed (`list.tsx:974`, `transcript-window.tsx:63` @ `564aef2946`) | mobile-adaptation | No counterpart | Every scroll this app performs for itself — the pane's opening jump to the tail, the prepend anchor restore — moves a `LazyListState` without dispatching nested scroll, so the seam this gate listens on never hears them. Desktop needs the flag because its signal is a scroll position that reads 0 before its restore |
| A wheel notch asks the moment it lands, and `restorePending` is what stops a wheel that keeps turning from asking over a parked prepend (`transcript-window.tsx:64`, `list.tsx:962-994` @ `564aef2946`) | mobile-adaptation | The reach is noticed during the gesture and spent in `onPostFling`, once the drag and its fling are over, so one gesture is one ask and `restorePending` has no input | A wheel notch is discrete; a drag is continuous, and asking on each of its frames walks a whole conversation in. It is also a correctness rule, not only a rate limit: the pane restores the prepend by scrolling the list back to the reader's row, and a `LazyListState` scroll asked for at `MutatePriority.Default` while the finger holds the list at `UserInput` is cancelled rather than queued — a page delivered mid-drag would land with its anchor discarded and drop the reader at the top of the history they pulled in |

## Visual report

- pending: #68, #221

**Half a pair, and `pending:` is the honest half.** The Desktop reference exists
and is stored in this repo at `docs/parity/visual/transcript-show-earlier/desktop/`
(`reference.png` + `contract.json`, captured from a disposable export at the pin
against a seeded 120-turn transcript, at `be20b61`). It is deliberately **not**
recorded as `report:` — `scripts/check-parity-evidence.py` refuses a page that
claims a report and owes one at the same time, and the side that is missing is
Android's, which is the side a parity review is actually about. There is no
`report.html` under that name either, because a side-by-side needs two sides.

Why the Android half is owed rather than skipped: the pill renders only once a
conversation holds more than the 120-row hydration page, and no session on any
Gateway this pass could reach does. Two capture runs probed the six longest
sessions in the active profile and in the QA profile and reached the first user
message in every one with no pill on screen; the large token counts there come
from a few very big messages, not from many rows. Manufacturing one by sending
turns was outside the capture pass. Until a long transcript exists on a reachable
Gateway, the control is proved by Robolectric and the repository tests —
structure, not pixels — and this page reviews at **Concern** for it.

What the stored Desktop half does settle, against what the port claimed from
source: the control is a plain centred rounded pill with no glyph, no spinner and
no disabled state, reading `Show earlier messages` verbatim (`en.ts:3520`), 164 x
26 px at the top of the scrolled transcript content. The clip carries exactly one
node, and that node is the whole control.

**#221 owes the same capture, and nothing extra.** The automatic route paints no
pixels of its own: Desktop draws no indicator for it and neither does this app,
so its whole visible surface is the pill that was already there and the older
turns that arrive under it. The capture it owes is therefore the one #68 owes —
a conversation longer than a hydration page, on a reachable Gateway — with the
head reached by a drag rather than a tap. Until that exists the behaviour is
proved by `AutoShowEarlierGateTest` and by the six reaching journeys in
`ShowEarlierJourneyTest`, which is structure rather than pixels, and this page
continues to review at **Concern** for it.
