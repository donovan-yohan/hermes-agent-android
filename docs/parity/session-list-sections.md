# Session list sections: pinned, archived and the unread dot

The session list's leading `Pinned` section, the `Archived` view, the durable
unread dot and the two whole-list verbs in the filter menu
(`ui/sessions/SessionList.kt`, `data/session/SessionGrouping.kt`,
`data/session/SessionModel.kt`), ported per
[`docs/workflows/port-desktop-surface.md`](../workflows/port-desktop-surface.md).

The per-row menu that turns these on and off is
[`session-actions-menu.md`](session-actions-menu.md); this page is the *list's*
half.

## Pin

| Source | Pin | Read via |
|---|---|---|
| Desktop renderer and i18n | `hermes-agent` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3` | read-only checkout; the working tree has drifted, so every citation below was taken with `git show <sha>:<path>` |

Every `path:line` below is against that SHA.

## Paths that settled the port

| Question | Path |
|---|---|
| The Pinned section and its position | `apps/desktop/src/app/chat/sidebar/index.tsx:1640-1661` |
| Which rows the Pinned section holds | `apps/desktop/src/app/chat/sidebar/session-index.ts:35-95` (`resolvePinnedSessions`) |
| That the backend flag is the authority | `session-index.ts:41-49` |
| The empty-recents sentence and when it is chosen | `sidebar/index.tsx:1688-1702`; `apps/desktop/src/i18n/en.ts:2214` |
| That Archived swaps the pool rather than filtering it | `sidebar/index.tsx:488-495,1301-1307,1352-1358` |
| That the archived set is its own capped query | `apps/desktop/src/store/sidebar-archive.ts:7-30` (`ARCHIVED_FETCH_LIMIT = 200`, `archived: 'only'`) |
| Why 200 is available there and 100 here | `hermes_cli/web_routers/profiles.py:222-228` (`le=500`, "real desktop callers use limit=200") vs `hermes_cli/web_routers/sessions.py:91-94` (`le=100`) |
| That the archived list is flat | `sidebar/index.tsx:1719-1723` (`grouping='none'`) |
| What Desktop does while the archived read is in flight, and when it fails | `store/sidebar-archive.ts:12,19,28` (`$archivedSessionsLoading` guards re-entry; nothing renders it) and `:25-27` (`catch` publishes `[]`) |
| The `Archived` checkbox, its label and its position | `apps/desktop/src/app/chat/sidebar/filter-menu.tsx:380-397` |
| `Mark all as read`, its place and its zero-unread gate | `apps/desktop/src/app/chat/sidebar/filter-menu.tsx:404,411-413` (the filter-menu item, `disabled` at zero); the second, hover-revealed header button at `sidebar/index.tsx:1725-1748`; `en.ts:2356` |
| That an option row keeps the menu open | `filter-menu.tsx:124-126` (`keepOpen`) |
| That an option with no `icon` renders no glyph | `filter-menu.tsx:116-122` (`OptionGlyph`) |
| What Desktop's mark-all actually writes | `apps/desktop/src/store/session.ts:1113` (`markAllSessionsRead`), `apps/desktop/src/store/session-unread.ts:302` (`ackAllSessionsRead`) |
| The archived row's lead glyph | `apps/desktop/src/app/chat/sidebar/session-row.tsx:284-290` |
| Which dot the two unread sources paint, and what outranks them | `apps/desktop/src/store/session-dot-state.ts:19-23,125-184` |
| Which sources the read-state *item* reads | `apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:314-315,319` (raw `unread \|\| isUnread`), `:102-103`, `:217` |
| The unread write, its optimism and its guard | `apps/desktop/src/store/session-unread-remote.ts:28-79` |
| The wire contract for all three flags | `hermes_cli/web_routers/sessions.py:97,108-125,825-841` |

## What ships

| Element | Desktop | Android |
|---|---|---|
| Pinned section | Its own collapsible `SidebarSessionsSection`, label `Pinned` (`en.ts:2205`) | A leading `SectionLabel` reading `PINNED` above the date buckets |
| Pinned membership | Local pin ids first, then any row the server flags `pinned` | Server `pinned` only |
| Pinned ordering | The reader's hand-picked drag order, server rows appended | Activity, newest first |
| Empty recents while everything is pinned | `Everything here is pinned. Unpin a chat to show it in recents.` (`en.ts:2214`) | **Same**, verbatim, as one tertiary line below the section |
| Archived | A view of its own set: `archived: 'only'` into a second store, swapped in wholesale | **Same** — one `archived=only` read per profile leg into the one cache, swapped in by the list's pool filter and never mixed into the live page's window |
| Archived list shape | Flat: no pinned group, no date or status dividers | **Same** |
| Archived empty state | `Nothing archived` / `Archive a chat to hide it here.` (`en.ts:1154-1155`) | **Same**, verbatim — once the pool has answered |
| Archived view before its pool answers | `Nothing archived`: the set starts `[]` and `$archivedSessionsLoading` renders nothing (`sidebar-archive.ts:11-12`) | `Loading archived chats…` until the read answers, and its own sentence when it fails or the Gateway cannot be asked. Ledgered below |
| Archived row's lead slot | The `archive` codicon in `--ui-text-quaternary`, in place of the status dot | **Same** glyph, `tokens.textQuaternary`, in place of the dot |
| `Archived` filter | `OptionCheckbox` at the foot of the filter group, no glyph, and it keeps the menu open; the label is a literal, not an i18n key (`filter-menu.tsx:393-397,116-126`) | **Same** word, same position, no glyph, and the menu stays open |
| `Mark all as read` | Two controls: a plain `DropdownMenuItem` last in the filter menu after a separator, no glyph, `disabled` at zero unread (`filter-menu.tsx:404,411-413`); and a hover-revealed `check-all` icon button in the section header (`sidebar/index.tsx:1725-1748`) | **Same** filter-menu item — last, after the rule, no glyph, disabled at zero. The header button is the omission, ledgered below |
| Unread dot | One resolved state, claimed by the transient marker *and* the durable watermark, outranked by background / working / needs-input | **Same** rule, in `SessionSummary.displayStatus()` |
| Read-state menu item | Reads the two sources *raw* — `unread \|\| isUnread` (`session-actions-menu.tsx:314-315,319`) — not the resolved dot, so a working row that carries the watermark can still be marked read | **Same** rule, in `SessionSummary.isUnread()`, on the row menu and the chat header alike |
| An omitted `unread` field | Read | **Same** — `null` is "this Gateway never said", never unread |

## Mobile adaptation

| Desktop | Android | Reason |
|---|---|---|
| Pinned is a collapsible section with its own header, disclosure and drag handles (`sidebar/index.tsx:1641-1661`) | An in-list `SectionLabel` above the buckets, in one scroll region | The rail has one list and one scroller; a second collapsible header on a phone costs a row of chrome to hide four rows of content. The label, its position and its word are unchanged. |
| Pinned rows reorder by drag, persisted locally (`sessions-section.tsx:247`) | Activity order, newest first | There is no drag-reorder affordance here, and inventing a local order the reader cannot change would be an ordering with no author. The backend flag decides membership either way — Desktop says so itself (`session-index.ts:41-49`). |
| Empty Pinned section renders `Shift-click a chat to pin` (`section-states.tsx:44-54`; `en.ts:2215`) | The section is absent when nothing is pinned | The hint names a modifier chord a soft keyboard does not have, and the menu item it would point at is already in every row's menu. |
| The first calendar divider is unlabelled | Labelled when a Pinned section renders above it | The unlabelled-first rule exists because there is nothing above the newest group to separate it from. With Pinned above there is, and an unlabelled bucket would read as more pinned rows. |
| Archived is fetched at `ARCHIVED_FETCH_LIMIT = 200` (`store/sidebar-archive.ts:9,22`) | One `archived=only` read per profile leg at limit 100 | The request shape is Desktop's; only the cap differs, and not by choice. Desktop reads `/api/profiles/sessions`, which allows 500 *because* its own callers ask for 200 (`profiles.py:222-228`). This app reads one profile leg at a time through `/api/sessions`, whose own ceiling is 100 (`sessions.py:91-94`) — 100 is the whole window that route will give. |
| The archived query has an RPC-free fallback path (Desktop has one contract) | The Archived view says `Archived chats need a newer Hermes on this Gateway.` on a backend that only serves `session.list` | That RPC takes only `limit` and `include_hidden` (`tui_gateway/methods_session.py:246-266`) and emits `id/title/preview/started_at/message_count/source` with no `archived` field (`:267-282`), and an empty list would read as `Nothing archived` — a claim about the account rather than about the Gateway. |
| An unanswered or failed archived read renders as `Nothing archived`: `$archivedSessions` starts `[]` and the `catch` sets it back to `[]` (`sidebar-archive.ts:11,25-27`) | `Loading archived chats…` while the read is in flight; `Couldn’t load archived chats` when it fails; `Archived chats unavailable` when the Gateway serves only `session.list` | `Nothing archived` is a claim about the account. Desktop's backend is a local process on the same machine, so a failed read there is close to impossible; here the Gateway is across a network that drops, and a phone that says the account has no archived chats because the request timed out is telling the reader something false about their data. The marker Desktop already keeps is what holds the sentence back. |
| The header's hover-revealed `check-all` mark-all button (`sidebar/index.tsx:1725-1748`) | Absent; only Desktop's filter-menu item ships | Touch has no hover, so the second control's whole affordance — a blank 24 px hole that fills in on pointer-over — has no touch equivalent. Desktop's other mark-all control is the filter-menu item, which ships verbatim. |
| Desktop's mark-all writes nothing to the Gateway: `markAllSessionsRead` clears the transient set (`store/session.ts:1113`) and `ackAllSessionsRead` acks the local persisted records (`store/session-unread.ts:302`) | One `PATCH {"unread":false}` per unread row, reporting the count that refused | There is no local persisted watermark on this platform — the durable read state *is* the Gateway's, so acking it is a write. The fan-out is serial and uncancellable, which is fine for one loaded page and is named as a limitation in the ROADMAP. |
| `unreadIds` for the zero gate is the whole transient finished-unread set (`filter-menu.tsx:172`) | The loaded, in-scope, non-archived rows whose resolved dot is unread | The count has to be the same set the verb acts on, and this verb acts on the rows the sidebar has actually loaded in the profile scope it is standing in. Like Desktop's, the item stays offered while the Archived view is on. |
| A search still renders the Pinned section above `Results` | No Pinned section while a query is live | **Same** as Desktop: `!trimmedQuery` gates both session sections (`sidebar/index.tsx:1640,1664`), and search answers in one list. |

## Evidence

| Check | Where |
|---|---|
| Pinned membership, ordering, the all-pinned note, the first-bucket label, the archived pool swap, and that an unsaid flag is not an archive | `app/src/test/kotlin/com/hermesagent/mobile/data/session/SessionGroupingTest.kt` |
| The two unread sources, the unsaid watermark, what outranks both, and that the menu item reads the raw sources rather than the resolved dot | `app/src/test/kotlin/com/hermesagent/mobile/data/session/SessionUnreadTest.kt` |
| Each PATCH shape and its profile, the optimistic paint and rollback for all three verbs, the in-place archive and what a refused one restores (rehome alias, project membership, transcript), the write fence against a stale page for all three flags, its retirement, its expiry, its reconciliation, its lineage key, the `archived=only` request shape, and an archived row outside the live page still reaching the view | `app/src/test/kotlin/com/hermesagent/mobile/data/gateway/GatewaySessionRepositoryTest.kt` |
| Opening a session retiring both sources, the unread count, mark-all's fan-out and its honest partial-failure count, the Archived toggle reading its own pool, the pool's loading / failed / unsupported / answered-and-empty states, its re-read after a connection switch, a refused write reported in its own words, and all three writes surviving the row that started them leaving the screen | `app/src/test/kotlin/com/hermesagent/mobile/ui/chat/ChatViewModelTest.kt` |
| The archived pool re-read under the profile routing the reader just chose, and not read at all while nobody is looking at it | `app/src/test/kotlin/com/hermesagent/mobile/ui/chat/ChatProfileScopeTest.kt` |
| The rendered sections: the `PINNED` label, the all-pinned sentence, the archived row's lead mark and spoken state, the archived empty state and the three states that are not it, the `Archived` row at a full touch target keeping the menu open, `Mark all as read` visible-and-disabled at zero, a working watermarked row still offering `Mark as read`, each verb reaching its caller with the row's id, and each verb handed off before the press returns | `app/src/testDebug/kotlin/com/hermesagent/mobile/ui/sessions/SessionListSectionsJourneyTest.kt` |
| Menu order, the label and glyph swaps, and that Archive is never destructive-red | `app/src/test/kotlin/com/hermesagent/mobile/ui/sessions/SessionActionsMenuTest.kt` |

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Pinned is a collapsible section with its own header and disclosure (`sidebar/index.tsx:1641-1661`) | mobile-adaptation | A leading in-list `SectionLabel` in one scroll region | The rail has one list and one scroller; a second collapsible header costs a row of chrome on a phone. Label, position and word unchanged |
| Pinned rows reorder by drag, persisted locally (`sessions-section.tsx:247`) | mobile-adaptation | Activity order, newest first | Touch has no drag-reorder affordance here, and a local order the reader cannot change would have no author. Membership is the backend flag either way (`session-index.ts:41-49`) |
| Empty Pinned section shows `Shift-click a chat to pin` (`en.ts:2215`) | omission | The section is absent when nothing is pinned | non-goal: the hint names a modifier chord a soft keyboard does not have, and the verb it points at is already in every row's menu |
| `ARCHIVED_FETCH_LIMIT = 200` on the archived query (`store/sidebar-archive.ts:9,22`) | mobile-adaptation | The same `archived=only` query at limit 100 | Desktop reads `/api/profiles/sessions`, capped at 500 for exactly that caller (`profiles.py:222-228`); this app reads one leg through `/api/sessions`, whose ceiling is 100 (`sessions.py:91-94`), so 100 is the whole window available |
| The archived set is reachable on any backend the sidebar can talk to | mobile-adaptation | Refused with `Archived chats need a newer Hermes on this Gateway.` when only the `session.list` RPC is served | That RPC takes only `limit` and `include_hidden` (`tui_gateway/methods_session.py:246-266`) and emits no `archived` field (`:267-282`); an empty pool would render `Nothing archived`, which is a false claim about the account rather than a true one about the Gateway |
| An archived read that is in flight, or that failed, still renders `Nothing archived` (`sidebar-archive.ts:11,25-27`) | mobile-adaptation | `Loading archived chats…`, `Couldn’t load archived chats`, or `Archived chats unavailable` — `Nothing archived` only once the pool has answered | The sentence is a claim about the account, and this app's Gateway is across a network that drops; Desktop's is a local process. The marker Desktop keeps for re-entry (`$archivedSessionsLoading`, `sidebar-archive.ts:12,19,28`) is rendered here instead. `SessionListSectionsJourneyTest` covers all four states, `ChatViewModelTest` the state machine behind them |
| The archived set is re-read only when the `Archived` toggle goes on (`sidebar/index.tsx:1352-1358`) | mobile-adaptation | Also re-read when the endpoint or the profile routing changes while the view is on | Desktop's archived store survives a gateway switch untouched (`store/gateway-switch.ts:178-232` wipes the live lists and never names `$archivedSessions`), which leaves the previous backend's set on screen. This app clears every row on a switch through `SessionCache.resetForEndpointSwitch()`, so the same shape would leave the Archived view painting `Nothing archived` about a machine nobody has asked. The pool is re-read on the same seam instead |
| A second, hover-revealed `check-all` mark-all button in the section header (`sidebar/index.tsx:1725-1748`) | omission | Absent; Desktop's filter-menu item is the one that ships | non-goal: its whole affordance is pointer hover — a blank 24 px hole until the pointer arrives — and touch has none |
| Mark-all writes nothing to the Gateway (`store/session.ts:1113`, `store/session-unread.ts:302`) | mobile-adaptation | One `PATCH {"unread":false}` per unread row, reporting the count that refused | There is no local persisted watermark here, so the durable read state is the Gateway's and acking it is a write; the fan-out is serial and uncancellable, which is named as a limitation |
| The zero gate counts the whole transient finished-unread set (`filter-menu.tsx:172`) | mobile-adaptation | The loaded, in-scope, non-archived rows whose resolved dot is unread | The count has to describe the same rows the verb acts on, which are the ones this sidebar has loaded in the profile scope it is standing in |
| The first calendar divider is unlabelled | mobile-adaptation | Labelled when a Pinned section renders above it | The rule exists because nothing sits above the newest group; with Pinned above it, an unlabelled bucket reads as more pinned rows |
| `Ordering` (`filter-menu.tsx:260`), `Show` (`:274`), `Inbox style` (`:292`), `Status` (`:302`), `Profile` (`:334`) and `Collapse all` (`:408`) | omission | Absent from the menu entirely | pill-owed: #142 — #66 deliberately took only `Archived` and `Mark all as read`, so the rows were never built; since #101 the standing rule is that an unsupported **control** stays visible and disabled behind the `WIP` chip rather than vanishing, and six of them vanish here. The rendered pair is `docs/parity/visual/session-list-archived-filter/` |
| Archived Chats settings page, its per-row `<folder> · N messages` hint and auto-archive-after-N-days (`app/settings/sessions-settings.tsx`) | omission | Absent; the restore lives in the row's own menu | deferred: #73 — session maintenance; #66 declares them non-goals |
| Bulk selection on the archived list | omission | Absent | out-of-scope: #66 — no bulk operations |
| A `draft` dot below unread (`session-dot-state.ts:129-131`) | omission | Folded into `Idle` | out-of-scope: #66 — this list has no draft state to distinguish yet |
| `sidebar.dateDivider` reads `Earlier today` / `Yesterday` / `Earlier this week` / `Last week` / `Earlier this month`, then a month name and month + year from `Intl` (`en.ts:2345-2351`; `lib/time.ts:125-165`) | drift | `Today` / `Yesterday` / `This week` / `Last week` / `This month` / `Older` (`SessionGrouping.kt:21-28`) | #141. Three labels are re-phrased and every month bucket collapses into one `Older`. The captures read `YESTERDAY · EARLIER THIS WEEK · LAST WEEK · JULY` against `TODAY · LAST WEEK · OLDER`. Upstream's `Earlier …` is load-bearing: the newest run is left unlabelled above the first divider, which is what makes the word true (`lib/time.ts:118-124`) |
| Two distinct captions: `SidebarPanelLabel` for `Pinned` / `Sessions` — accent `--theme-primary` ink, tracking 0.16em, leading 8 px dither square (`app/shell/sidebar-label.tsx:9-22`) — and `SidebarDateDivider` for the buckets — `--ui-text-quaternary`, tracking 0.12em, trailed by a hairline rule (`sidebar/chrome.tsx:51-97`) | drift | One `SectionLabel` for both: `textTertiary`, no glyph, no rule (`ui/common/Primitives.kt:87-94`) | #141. `PINNED` therefore reads as another date bucket, and Desktop's two-level hierarchy flattens to one. Visible in `docs/parity/visual/session-list-sections/` |
| Every row carries a right-aligned relative age — `12m`, `9h`, `1d`, `39d` — from the default row meta `['preview', 'updated']` (`store/layout.ts:300`; units at `en.ts:2340-2343`) | omission | The title and the preview line only; no age | deferred: #143 — the `preview` half of Desktop's default ships, the `updated` half does not, and nothing in `app/src/main/kotlin` reads those four keys. An omitted field rather than a control |
| `Grouping` is a submenu trigger showing the active value on its right (`filter-menu.tsx:238-258`) | mobile-adaptation | An inline `GROUPING` caption over two radio rows, `Updated` (checked) and `Project` | Nested pointer submenus are brittle on a phone and the port workflow's standing rule is to flatten them; with two options the flattened form costs one caption and shows the choice without a second surface. `GROUPING` is not a Desktop string — it is this list's own section caption, applied to a Desktop control |
| The filter menu has no `Search` item; search is a persistent field in the sidebar header (`sidebar/index.tsx`, `en.ts:2200-2202`) | mobile-adaptation | A `Search` row sits in this menu, above `Archived` | Viewport: the drawer header holds the connection, the title, `+` and the filter trigger already, and a permanent field would take a row of the list on every phone. The field itself is unchanged when it opens — see `session-search.md` |

## Visual report

Rendered side by side at `be20b61`. Desktop was captured from a disposable
export at the pin with a headless CDP renderer and synthetic sessions; Android
on a Pixel 10 Pro emulator in light theme against a clean `kame-qa` profile
holding four synthetic sessions, one pinned pair, one unread row and one
archived row. Three states, both sides each:

- report: docs/parity/visual/session-list-sections/report.html
- report: docs/parity/visual/session-list-archived-filter/report.html
- report: docs/parity/visual/session-list-archived-view/report.html
- commit: be20b61

The `Pinned` section renders first and the archived view swaps the pool rather
than filtering it, both as ported, and the unread dot is the same filled green
mark in the same slot. One state the pair does **not** settle: Desktop's archived view keeps the
`Pinned` section, so a row that is both pinned and archived still files under
`PINNED`. The seeded archived row was unpinned, so the Android half never
rendered that combination and this pass makes no claim about it.

The render is what caught the divider copy and the
section-label treatment (#141), the six filter-menu controls that are absent
rather than disabled (#142), and the missing row age (#143).
