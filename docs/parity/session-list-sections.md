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
| Archived empty state | `Nothing archived` / `Archive a chat to hide it here.` (`en.ts:1154-1155`) | **Same**, verbatim |
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
| The archived query has an RPC-free fallback path (Desktop has one contract) | The Archived view says `Archived chats need a newer Hermes on this Gateway.` on a backend that only serves `session.list` | That RPC has no archived filter at all (`hermes_cli/rpc/methods_session.py:204-214`), and an empty list would read as `Nothing archived` — a claim about the account rather than about the Gateway. |
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
| Opening a session retiring both sources, the unread count, mark-all's fan-out and its honest partial-failure count, the Archived toggle reading its own pool, a refused write reported in its own words, and a write surviving the row that started it leaving the screen | `app/src/test/kotlin/com/hermesagent/mobile/ui/chat/ChatViewModelTest.kt` |
| The rendered sections: the `PINNED` label, the all-pinned sentence, the archived row's lead mark and spoken state, the archived empty state, the `Archived` row at a full touch target keeping the menu open, `Mark all as read` visible-and-disabled at zero, a working watermarked row still offering `Mark as read`, and each verb reaching its caller with the row's id | `app/src/testDebug/kotlin/com/hermesagent/mobile/ui/sessions/SessionListSectionsJourneyTest.kt` |
| Menu order, the label and glyph swaps, and that Archive is never destructive-red | `app/src/test/kotlin/com/hermesagent/mobile/ui/sessions/SessionActionsMenuTest.kt` |

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Pinned is a collapsible section with its own header and disclosure (`sidebar/index.tsx:1641-1661`) | mobile-adaptation | A leading in-list `SectionLabel` in one scroll region | The rail has one list and one scroller; a second collapsible header costs a row of chrome on a phone. Label, position and word unchanged |
| Pinned rows reorder by drag, persisted locally (`sessions-section.tsx:247`) | mobile-adaptation | Activity order, newest first | Touch has no drag-reorder affordance here, and a local order the reader cannot change would have no author. Membership is the backend flag either way (`session-index.ts:41-49`) |
| Empty Pinned section shows `Shift-click a chat to pin` (`en.ts:2215`) | omission | The section is absent when nothing is pinned | non-goal: the hint names a modifier chord a soft keyboard does not have, and the verb it points at is already in every row's menu |
| `ARCHIVED_FETCH_LIMIT = 200` on the archived query (`store/sidebar-archive.ts:9,22`) | mobile-adaptation | The same `archived=only` query at limit 100 | Desktop reads `/api/profiles/sessions`, capped at 500 for exactly that caller (`profiles.py:222-228`); this app reads one leg through `/api/sessions`, whose ceiling is 100 (`sessions.py:91-94`), so 100 is the whole window available |
| The archived set is reachable on any backend the sidebar can talk to | mobile-adaptation | Refused with `Archived chats need a newer Hermes on this Gateway.` when only the `session.list` RPC is served | That RPC has no archived filter (`methods_session.py:204-214`); an empty pool would render `Nothing archived`, which is a false claim about the account rather than a true one about the Gateway |
| A second, hover-revealed `check-all` mark-all button in the section header (`sidebar/index.tsx:1725-1748`) | omission | Absent; Desktop's filter-menu item is the one that ships | non-goal: its whole affordance is pointer hover — a blank 24 px hole until the pointer arrives — and touch has none |
| Mark-all writes nothing to the Gateway (`store/session.ts:1113`, `store/session-unread.ts:302`) | mobile-adaptation | One `PATCH {"unread":false}` per unread row, reporting the count that refused | There is no local persisted watermark here, so the durable read state is the Gateway's and acking it is a write; the fan-out is serial and uncancellable, which is named as a limitation |
| The zero gate counts the whole transient finished-unread set (`filter-menu.tsx:172`) | mobile-adaptation | The loaded, in-scope, non-archived rows whose resolved dot is unread | The count has to describe the same rows the verb acts on, which are the ones this sidebar has loaded in the profile scope it is standing in |
| The first calendar divider is unlabelled | mobile-adaptation | Labelled when a Pinned section renders above it | The rule exists because nothing sits above the newest group; with Pinned above it, an unlabelled bucket reads as more pinned rows |
| Status grouping, cost/token ordering and the rest of the filter menu (`filter-menu.tsx:200-378`) | omission | Absent | out-of-scope: #66 — that issue takes only `Archived` and `Mark all as read` |
| Archived Chats settings page, its per-row `<folder> · N messages` hint and auto-archive-after-N-days (`app/settings/sessions-settings.tsx`) | omission | Absent; the restore lives in the row's own menu | deferred: #73 — session maintenance; #66 declares them non-goals |
| Bulk selection on the archived list | omission | Absent | out-of-scope: #66 — no bulk operations |
| A `draft` dot below unread (`session-dot-state.ts:129-131`) | omission | Folded into `Idle` | out-of-scope: #66 — this list has no draft state to distinguish yet |

## Visual report

- pending: #66

Not captured. The Desktop reference capture in the port workflow needs a
disposable pinned dev renderer with CDP, which was not available for this
slice; the section order, copy, glyph vocabulary and colour roles are pinned
against source and the shipped font instead, and the rendered structure is
asserted under Robolectric by `SessionListSectionsJourneyTest`.
