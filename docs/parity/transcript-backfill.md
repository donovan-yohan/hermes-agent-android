# `Show earlier messages` and the transcript window: Desktop-to-Android parity

## Pin

Desktop authority is `3ca096de5f8183cb2e0ec23673f294d5978656a3`.

- Control: `apps/desktop/src/components/assistant-ui/thread/list.tsx:834-842` —
  a plain centred rounded pill at the top of the transcript content, inside the
  scroll, no glyph. `showEarlier()` at `:745-760`; the two-stage
  `dom | window | null` resolution at
  `apps/desktop/src/components/assistant-ui/thread/transcript-window.tsx:23-33`.
- Copy: `apps/desktop/src/i18n/en.ts:3218` — `showEarlier: 'Show earlier messages'`.
  There is no loading, disabled or exhausted string, because the control has no
  such state.
- Pill paint: `list.tsx:836` — `border-border/65`, `bg-(--composer-fill)`,
  `text-muted-foreground`, `rounded-full`, `mx-auto`,
  `mb-(--conversation-turn-gap)`. `--composer-fill` is
  `color-mix(in srgb, var(--dt-card) 90%, var(--dt-background))`
  (`styles.css:1789`); the two seeds are `--dt-card: var(--ui-bg-editor)`
  (`styles.css:390`) and `--dt-background: var(--ui-bg-chrome)`
  (`styles.css:388`), which are this app's `cardSurface` and `chatSurface`.
  `--conversation-turn-gap` is `0.375rem` — 6 px — at `styles.css:474`; this
  app's `spacing.turnGap` is `8.dp` (`HermesTypography.kt:56`), the same gap
  taken up to the mobile spacing step (ledgered below).
- Page size: `LATEST_SESSION_MESSAGES_LIMIT = 120`,
  `apps/desktop/src/api/sessions.ts:415`, used for both the hydration page
  (`getLatestSessionMessages`, `:417-438`) and every older page
  (`getOlderSessionMessages`, `:490-497`). Both always send
  `includeCompacted: true` (`:418-424`).
- Merge: `apps/desktop/src/app/chat/transcript-backfill.ts` whole;
  `mergeOlderTranscriptPage` at `:36-64`, `graftRefreshedTailOntoBackfill` at
  `:66-93`.
- Truncation bookkeeping: `apps/desktop/src/store/transcript-tail.ts:82-96`.
- Wiring per session: `apps/desktop/src/app/chat/index.tsx:262-326`.
- Anchoring on prepend: `list.tsx:497-505` records the distance from the bottom
  and `:762-770` re-applies it in the same commit; the reason is at `:528-533`.
- Gateway route: `hermes_cli/web_routers/sessions.py:642-715`, reading
  `hermes_state.py:12869-13016`.

## The contract split

At the pin, Desktop hydrates and refreshes a chat's transcript over REST
(`getLatestSessionMessages`, called from `use-session-actions/index.ts:1235,1478,1786`,
`use-background-sync.ts:131,203`, `use-session-tile-delegate.ts:244`,
`contrib/wiring.tsx:394`). The `session.history` RPC survives for exactly one
caller: the rewind flow, which needs the whole row-stamped conversation
(`use-prompt-actions/rewind.ts:200,226`). Android mirrors that split — the paged
route hydrates, and the RPC remains the contract for a Gateway that has no such
route.

The two contracts do not ship the same rows. `session.history` ships the
Gateway's display projection (`tui_gateway/server.py:9720-9823`); the REST route
ships the stored rows with compaction display applied and nothing else
(`sessions.py:672-708`). `RestTranscriptProjection.kt` is that projection
ported, so one parser reads both and a page fetched over REST merges into a
transcript hydrated either way.

## Which conversations are windowed, and which are not

The paged route resolves a compression chain FORWARD to its live tip and reads
that session's rows alone (`sessions.py:660-663,672-678`). `session.history`
merges the chain (`get_messages_as_conversation(..., include_ancestors=True)`,
`methods_session.py:2843-2847`). So on a conversation the Gateway has already
compressed onto a fresh id, the two contracts do not cover the same turns:
windowing it would end `Show earlier messages` at the tip's first row, with the
turns before the compression unreachable and nothing said about it.

Android does not window those. A conversation known to be a compression tip
keeps whole-history hydration and is offered no control at all. The signal is
the list route's own: `list_sessions_rich` projects a compression root forward
to its tip and stamps `_lineage_root_id` on the row it surfaces, and only on
that row (`hermes_state.py:11586-11605`); this app already parses it as
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
| The button stays clickable while a page is in flight and concurrent calls share one promise (`transcript-backfill.ts:126-133`) | mobile-adaptation | The control looks identical, and a press while a page is on the wire is ignored | A shared promise needs a promise; the repository is the one place that knows a page is in flight, so the guard lives there. Nothing visible changes — no spinner, no disabled state |
| The prepend is anchored on the scroll container's distance from the bottom (`list.tsx:497-505,762-770`) | mobile-adaptation | Anchored on the transcript row that was on top and the offset into it, restored once the page lands | A `LazyListState` has no scroll height to measure from — only an index, a key and an offset. Keying on the row also survives the leading control disappearing in the same frame, which a pure index cannot |
| The pill is a ~22 px chrome control | mobile-adaptation | The same pill at the 48 dp platform touch floor | A touch target may not be smaller than the floor; the fill, hairline, radius, ink and copy are unchanged |
| `hover:text-foreground` brightens the label on pointer-over (`list.tsx:836`) | mobile-adaptation | Not painted | Touch has no hover state to paint |
| The tool row's collapsed title is `build_tool_preview`, a per-tool phrasing (`agent/display.py:446-595` via `server.py:7740-7756`) | mobile-adaptation | The primary-argument table (`display.py:457-468`) and the generic tail (`:576-595`) are ported; the per-tool phrasings above that tail are not | Those branches rephrase the same argument rather than name a different one, and porting them would be a second copy of upstream's tool table to keep in step. The full call still rides the row as `args` and the expanded tool view renders it |
| The sidebar pager is an ellipsis glyph with a spinner and a disabled state (`apps/desktop/src/app/chat/sidebar/load-more-row.tsx:17-38`) | omission | The transcript control shares none of that markup | out-of-scope: #68 — the shared vocabulary is the interaction contract (one explicit press for more, never a scroll that asks), not the visual. Desktop's own two controls differ: the transcript's has no glyph, no spinner and no disabled state |
| `recordTranscriptTail` re-runs `tailStateFromPage` on a refresh, resetting `nextOffset` to that page's length (`transcript-tail.ts:117-125`) | mobile-adaptation | The refreshed tail's offset is taken as the further of itself and the offset the previous window had reached | Desktop drops its backfilled prefix's paging with it on a refresh and re-walks; Android keeps the prefix (`graftRefreshedTailOntoBackfill`, which Desktop also has) and must therefore not re-offer the pages that prefix already holds. Both are measured back from the newest row, so the deeper offset can only overlap — never skip |
| The RPC's tool row is `{role, name, context, args}` and nothing else (`tui_gateway/server.py:9755-9769`) | mobile-adaptation | The projected tool row also carries `content`, `row_id` and `timestamp` | This row follows the REST contract, not the RPC's projection of it: Desktop's own REST reader attaches the stored result (`lib/chat-messages/tool-parts.ts:737`, used at `hydration.ts:186`), and dropping `row_id` would leave the one row the window cannot dedupe by durable address. A tool row is therefore richer on the paged path than on the RPC path |
| `display_kind` (`model_switch`, `auto_continue`, `personality_switch`, `async_delegation_complete`) and `display_metadata` are forwarded (`server.py:9705-9717,9813-9820`) and rendered as system timeline rows (`lib/chat-messages/hydration.ts:94-116,197-208`) | omission | Only `display_kind: "hidden"` is read; the rest is dropped | out-of-scope: #68 — Android renders no system timeline row on either contract, so this is a pre-existing gap this port inherits rather than introduces. An `auto_continue` row's body is `[System note: …`, which the `[System:` filter does not match, so it reaches a user bubble on both paths |
| `build_tool_preview` masks recognizable credentials in a `browser_type` call's `text` first (`redact_tool_args_for_display`, `agent/display.py:400-414`, applied at `:456`) | mobile-adaptation | `browser_type` gets no collapsed preview at all | The masking is `redact_sensitive_text(force=True)` over thirteen credential patterns (`agent/redact.py:831-900`), not ported. A partial copy would mask the shapes it knew and print the rest while looking checked, so the preview is withheld instead. The call still rides the row as `args`, as it does upstream |
| The pill's bottom gap is `--conversation-turn-gap`, `0.375rem` = 6 px (`styles.css:474`, applied at `list.tsx:836`) | mobile-adaptation | `spacing.turnGap`, 8 dp (`HermesTypography.kt:56`) | The whole type and spacing scale is stepped up for touch; the turn gap follows it rather than being pinned to Desktop's pixel, so the pill sits on the same rhythm as every other turn on this platform |
| One read's tool-call map covers that read (`server.py:9740-9752`) | mobile-adaptation | The map covers one page | A tool row whose assistant call row fell on the other side of a page boundary renders with its stored `tool_name` and no argument preview. Carrying the map across pages would be per-session repository state with a lifetime nothing else in the projection has, for one row per page |

## Visual report

- pending: #68

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
no disabled state, reading `Show earlier messages` verbatim (`en.ts:3218`), 164 x
26 px at the top of the scrolled transcript content. The clip carries exactly one
node, and that node is the whole control.
