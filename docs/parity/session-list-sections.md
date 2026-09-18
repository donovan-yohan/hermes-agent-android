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
| Desktop renderer, for the citations re-read by [#146](https://github.com/donovan-yohan/hermes-agent-android/issues/146) | `hermes-agent` @ `437116f9497c80d242ce034ff7f5d81dc277a337` | same read-only checkout; the rows that carry the pin inline were taken at that SHA, where the `Pinned` and archived-section block sits twelve lines lower |

Every `path:line` below is against the first SHA unless the citation names the second one. The `#141` / `#299` caption, divider and date-group rows, and the `#146` archived-section rows, were re-read at
`437116f9497c80d242ce034ff7f5d81dc277a337`; every other citation on this page keeps the pin it was taken at.

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
| That the archived list takes no date or status dividers | `sidebar/index.tsx:1736` (`grouping='none'` while archived, @ `437116f9`) — the divider question, and only that |
| That the `Pinned` section is not gated on the archived view | `sidebar/index.tsx:1652-1674` (@ `437116f9`) — the section's own gate is `!trimmedQuery`, with no `showArchived` term |
| That the archived view's empty recents is Desktop's `noFilterMatches`, not `allPinned` | `store/layout.ts:392-396` (@ `437116f9`, `$sidebarFiltersActive` counts `$sidebarShowArchived` itself) with `sidebar/index.tsx:1706-1712` (@ `437116f9`); the sentence is `apps/desktop/src/i18n/en.ts:2666` |
| What Desktop does while the archived read is in flight, and when it fails | `store/sidebar-archive.ts:12,19,28` (`$archivedSessionsLoading` guards re-entry; nothing renders it) and `:25-27` (`catch` publishes `[]`) |
| The `Archived` checkbox, its label and its position | `apps/desktop/src/app/chat/sidebar/filter-menu.tsx:380-397` |
| `Mark all as read`, its place and its zero-unread gate | `apps/desktop/src/app/chat/sidebar/filter-menu.tsx:419-428` @ `437116f9` (the filter-menu item, `disabled` at zero); the second, hover-revealed header button at `sidebar/index.tsx:1738-1761` @ `437116f9`; `apps/desktop/src/i18n/en.ts:2805` @ `437116f9` |
| That an option row keeps the menu open | `filter-menu.tsx:124-126` (`keepOpen`) @ `3ca096de` |
| That an option with no `icon` renders no glyph | `filter-menu.tsx:116-122` (`OptionGlyph`) |
| What Desktop's mark-all actually writes | `apps/desktop/src/store/session.ts:1113` (`markAllSessionsRead`), `apps/desktop/src/store/session-unread.ts:302` (`ackAllSessionsRead`) |
| The archived row's lead glyph | `apps/desktop/src/app/chat/sidebar/session-row.tsx:284-290` |
| Which dot the two unread sources paint, and what outranks them | `apps/desktop/src/store/session-dot-state.ts:19-23,125-184` |
| Which sources the read-state *item* reads | `apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:314-315,319` (raw `unread \|\| isUnread`), `:102-103`, `:217` |
| The unread write, its optimism and its guard | `apps/desktop/src/store/session-unread-remote.ts:28-79` |
| The wire contract for all three flags | `hermes_cli/web_routers/sessions.py:97,108-125,825-841` |
| The `Sessions` / `Projects` / project-name label over the recents pool | `apps/desktop/src/app/chat/sidebar/index.tsx:1176-1181`, rendered at `:1829` @ `437116f9497c80d242ce034ff7f5d81dc277a337` |
| The panel caption: accent ink, 0.16em tracking, leading 8 px dither square | `apps/desktop/src/app/shell/sidebar-label.tsx:11-19` @ the same pin |
| The date divider: `--ui-text-quaternary`, 0.12em tracking, trailing hairline rule | `apps/desktop/src/app/chat/sidebar/chrome.tsx:134-140` @ the same pin |
| The five relative divider strings | `apps/desktop/src/i18n/en.ts:2794-2800` @ `437116f9497c80d242ce034ff7f5d81dc277a337` |
| The per-month tail and its `m-<year>-<month>` / `my-<year>-<month>` keys | `apps/desktop/src/lib/time.ts:155-165`, the `Intl` month formatters at `:30-31` @ the same pin |
| The head-run cutoff that leaves the newest run unlabelled | `apps/desktop/src/lib/session-date-groups.ts:44-88` @ the same pin |
| That a divider never labels two runs, and never re-opens a bucket it has passed | `apps/desktop/src/lib/session-date-groups.ts:130-147` @ the same pin |

## What ships

| Element | Desktop | Android |
|---|---|---|
| Pinned section | Its own collapsible `SidebarSessionsSection`, label `Pinned` (`en.ts:2205`) | A leading `PanelLabel` reading `PINNED` — accent ink and a dither square, not a bare field label — above the buckets, and the pool's own `SESSIONS` panel caption below the pinned rows (`data/session/SessionGrouping.kt:345,355`; `ui/sessions/SessionList.kt:464,478`) |
| Pinned membership | Local pin ids first, then any row the server flags `pinned` | Server `pinned` only |
| Pinned ordering | The reader's hand-picked drag order, server rows appended | Activity, newest first |
| Empty recents while everything is pinned | `Everything here is pinned. Unpin a chat to show it in recents.` (`apps/desktop/src/i18n/en.ts:2660` @ `437116f9`), chosen by `pinnedSessions.length > 0 ? s.allPinned : s.noSessions` (`sidebar/index.tsx:1710-1712` @ `437116f9`) — but only after `filtersActive`, which the `Archived` toggle itself sets (`store/layout.ts:392-396` @ `437116f9`), so this sentence is **unreachable** in the Archived view | **Same** in the live list, verbatim as one tertiary line below the section. The archived view keeps the same sentence as this app's own, ledgered below as a **mobile-adaptation** — Desktop renders `No sessions match these filters` (`apps/desktop/src/i18n/en.ts:2666` @ `437116f9`) there |
| Archived | A view of its own set: `archived: 'only'` into a second store, swapped in wholesale | **Same** — one `archived=only` read per profile leg into the one cache, swapped in by the list's pool filter and never mixed into the live page's window |
| Archived list shape | A flat run with no date or status dividers (`sidebar/index.tsx:1736` @ `437116f9`), under the same `Pinned` section the live list leads with (`:1652-1674` @ `437116f9` — the section's gate is `!trimmedQuery`, never `showArchived`) | **Same**: the archived rows are flat, and a pinned archived chat files under `PINNED` above them |
| Archived empty state | `Nothing archived` / `Archive a chat to hide it here.` (`en.ts:1154-1155`) | **Same**, verbatim — once the pool has answered |
| Archived view before its pool answers | `Nothing archived`: the set starts `[]` and `$archivedSessionsLoading` renders nothing (`sidebar-archive.ts:11-12`) | `Loading archived chats…` until the read answers, and its own sentence when it fails or the Gateway cannot be asked. Ledgered below |
| Archived row's lead slot | The `archive` codicon in `--ui-text-quaternary`, in place of the status dot | **Same** glyph, `tokens.textQuaternary`, in place of the dot |
| `Archived` filter | `OptionCheckbox` at the foot of the filter group, no glyph, and it keeps the menu open; the label is a literal, not an i18n key (`filter-menu.tsx:393-397,116-126`) | **Same** word, same position, no glyph, and the menu stays open |
| `Mark all as read` | Two controls: a plain `DropdownMenuItem` last in the filter menu after a separator, no glyph, `disabled` at zero unread (`filter-menu.tsx:404,411-413` @ `3ca096de`); and a hover-revealed `check-all` icon button in the section header (`sidebar/index.tsx:1725-1748` @ `3ca096de`) | **Same** filter-menu item — last, after the rule, no glyph, disabled at zero. The header button is the omission, ledgered below |
| Unread dot | One resolved state, claimed by the transient marker *and* the durable watermark, outranked by background / working / needs-input | **Same** rule, in `SessionSummary.displayStatus()` |
| Read-state menu item | Reads the two sources *raw* — `unread \|\| isUnread` (`session-actions-menu.tsx:314-315,319`) — not the resolved dot, so a working row that carries the watermark can still be marked read | **Same** rule, in `SessionSummary.isUnread()`, on the row menu and the chat header alike |
| An omitted `unread` field | Read | **Same** — `null` is "this Gateway never said", never unread |
| Row metadata | `['preview', 'updated']` by default: the preview line and the compact relative age (`store/layout.ts:308` @ `437116f9`) | The same two fields on every row, from the same bucket rule. The age's trailing slot and its spoken form are the two adaptations, ledgered below |

## Mobile adaptation

| Desktop | Android | Reason |
|---|---|---|
| Pinned is a collapsible section with its own header, disclosure and drag handles (`sidebar/index.tsx:1652-1674` @ `437116f9`) | An in-list `SectionLabel` above the buckets, in one scroll region | The rail has one list and one scroller; a second collapsible header on a phone costs a row of chrome to hide four rows of content. The label, its position and its word are unchanged. The section leads both views — the archived one included, since its own gate is `!trimmedQuery` and never `showArchived` (`sidebar/index.tsx:1652-1674` @ `437116f9`). |
| Pinned rows reorder by drag, persisted locally (`sessions-section.tsx:247`) | Activity order, newest first | There is no drag-reorder affordance here, and inventing a local order the reader cannot change would be an ordering with no author. The backend flag decides membership either way — Desktop says so itself (`session-index.ts:41-49`). |
| Empty Pinned section renders `Shift-click a chat to pin` (`section-states.tsx:44-54`; `en.ts:2215`) | The section is absent when nothing is pinned | The hint names a modifier chord a soft keyboard does not have, and the menu item it would point at is already in every row's menu. |
| `Sessions` heads the unpinned pool below `PINNED`, inside the list; the same `sessionsLabel` reads `Projects` at the overview and the entered project's name in project grouping (`apps/desktop/src/app/chat/sidebar/index.tsx:1176-1181,1829` @ `437116f9497c80d242ce034ff7f5d81dc277a337`) | The same caption, word and place: a `SessionListRow.SessionsLabel` below the pinned rows, drawn with the panel treatment | Ported. The caption is Desktop's, word and place; the one adaptation is the fixed pane title above the list, which keeps `SESSIONS` on screen while the pool caption scrolls with its rows. It is what gives the pinned rows a boundary of their own, so the first date divider no longer has to imply one — the per-section first-group rule is Desktop's own (`session-date-groups.ts:97-153`). Desktop spends the same label on `Projects` / the project name; on a phone that swap belongs to the pane title, which is one caption instead of two |
| Archived is fetched at `ARCHIVED_FETCH_LIMIT = 200` (`store/sidebar-archive.ts:9,22`) | One `archived=only` read per profile leg at limit 100 | The request shape is Desktop's; only the cap differs, and not by choice. Desktop reads `/api/profiles/sessions`, which allows 500 *because* its own callers ask for 200 (`profiles.py:222-228`). This app reads one profile leg at a time through `/api/sessions`, whose own ceiling is 100 (`sessions.py:91-94`) — 100 is the whole window that route will give. |
| The archived view keeps the Pinned section, and it is the same collapsible header the live list leads with (`sidebar/index.tsx:1652-1674` @ `437116f9`) | The same in-list `PINNED` caption the live list uses, above the archived rows | `showArchived` never reaches the section, so a pinned archived chat keeps the pin's only visible effect. The header-versus-label difference is the adaptation above. |
| The archived query has an RPC-free fallback path (Desktop has one contract) | The Archived view says `Archived chats need a newer Hermes on this Gateway.` on a backend that only serves `session.list` | That RPC takes only `limit` and `include_hidden` (`tui_gateway/methods_session.py:389-400`) and emits `id/title/preview/started_at/message_count/source` with no `archived` field (`:124-130`), and an empty list would read as `Nothing archived` — a claim about the account rather than about the Gateway. |
| An unanswered or failed archived read renders as `Nothing archived`: `$archivedSessions` starts `[]` and the `catch` sets it back to `[]` (`sidebar-archive.ts:11,25-27`) | `Loading archived chats…` while the read is in flight; `Couldn’t load archived chats` when it fails; `Archived chats unavailable` when the Gateway serves only `session.list` | `Nothing archived` is a claim about the account. Desktop's backend is a local process on the same machine, so a failed read there is close to impossible; here the Gateway is across a network that drops, and a phone that says the account has no archived chats because the request timed out is telling the reader something false about their data. The marker Desktop already keeps is what holds the sentence back. |
| The header's hover-revealed `check-all` mark-all button (`apps/desktop/src/app/chat/sidebar/index.tsx:1725-1748` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`) | Absent; only Desktop's filter-menu item ships | Touch has no hover, so the second control's whole affordance — a blank 24 px hole that fills in on pointer-over — has no touch equivalent. Desktop's other mark-all control is the filter-menu item, which ships verbatim. |
| Desktop's mark-all writes nothing to the Gateway: `markAllSessionsRead` clears the transient set (`store/session.ts:1113`) and `ackAllSessionsRead` acks the local persisted records (`store/session-unread.ts:302`) | One `PATCH {"unread":false}` per unread row, reporting the count that refused | There is no local persisted watermark on this platform — the durable read state *is* the Gateway's, so acking it is a write. The fan-out is serial and uncancellable, which is fine for one loaded page and is named as a limitation in the ROADMAP. |
| `unreadIds` for the zero gate is the whole transient finished-unread set (`filter-menu.tsx:172`) | The loaded, in-scope, non-archived rows whose resolved dot is unread | The count has to be the same set the verb acts on, and this verb acts on the rows the sidebar has actually loaded in the profile scope it is standing in. Like Desktop's, the item stays offered while the Archived view is on. |
| A search still renders the Pinned section above `Results` | No Pinned section while a query is live | **Same** as Desktop: `!trimmedQuery` gates both session sections (`sidebar/index.tsx:1640,1664`), and search answers in one list. |

## Evidence

| Check | Where |
|---|---|
| Pinned membership, ordering, the live list's all-pinned note, the archived view's own sentence for that state, the first-bucket label, the archived pool swap, the archived view's own `Pinned` section (present, ordered first, each pin once, no dividers under it, and hidden again while a query is live), and that an unsaid flag is not an archive | `app/src/test/kotlin/com/hermesagent/mobile/data/session/SessionGroupingTest.kt` |
| The two unread sources, the unsaid watermark, what outranks both, and that the menu item reads the raw sources rather than the resolved dot | `app/src/test/kotlin/com/hermesagent/mobile/data/session/SessionUnreadTest.kt` |
| Each PATCH shape and its profile, the optimistic paint and rollback for all three verbs, the in-place archive and what a refused one restores (rehome alias, project membership, transcript), the write fence against a stale page for all three flags, its retirement, its expiry, its reconciliation, its lineage key, the `archived=only` request shape, and an archived row outside the live page still reaching the view | `app/src/test/kotlin/com/hermesagent/mobile/data/gateway/GatewaySessionRepositoryTest.kt` |
| Opening a session retiring both sources, the unread count, mark-all's fan-out and its honest partial-failure count, the Archived toggle reading its own pool, the pool's loading / failed / unsupported / answered-and-empty states, its re-read after a connection switch, a refused write reported in its own words, and all three writes surviving the row that started them leaving the screen | `app/src/test/kotlin/com/hermesagent/mobile/ui/chat/ChatViewModelTest.kt` |
| The archived pool re-read under the profile routing the reader just chose, and not read at all while nobody is looking at it | `app/src/test/kotlin/com/hermesagent/mobile/ui/chat/ChatProfileScopeTest.kt` |
| The rendered sections: the `PINNED` label above live and archived rows alike, the all-pinned sentence in both views, the archived row's lead mark and spoken state, the archived empty state and the three states that are not it, the `Archived` row at a full touch target keeping the menu open, `Mark all as read` visible-and-disabled at zero, a working watermarked row still offering `Mark as read`, the default-on age on every row with its trailing placement, the pinned row rendered once above the archived rows with no dividers beneath, each verb reaching its caller with the row's id, and each verb handed off before the press returns | `app/src/testDebug/kotlin/com/hermesagent/mobile/ui/sessions/SessionListSectionsJourneyTest.kt` |
| The age itself: the four compact shapes Desktop's sidebar shows (`now`/`12m`/`9h`/`1d`), the clamp on a future timestamp, and the spell-out the row speaks | `app/src/test/kotlin/com/hermesagent/mobile/data/session/RelativeAgeTest.kt` |
| Menu order, the label and glyph swaps, and that Archive is never destructive-red | `app/src/test/kotlin/com/hermesagent/mobile/ui/sessions/SessionActionsMenuTest.kt` |

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Pinned is a collapsible section with its own header and disclosure (`sidebar/index.tsx:1641-1661`) | mobile-adaptation | A leading in-list `SectionLabel` in one scroll region | The rail has one list and one scroller; a second collapsible header costs a row of chrome on a phone. Label, position and word unchanged |
| Pinned rows reorder by drag, persisted locally (`sessions-section.tsx:247`) | mobile-adaptation | Activity order, newest first | Touch has no drag-reorder affordance here, and a local order the reader cannot change would have no author. Membership is the backend flag either way (`session-index.ts:41-49`) |
| Empty Pinned section shows `Shift-click a chat to pin` (`en.ts:2215`) | omission | The section is absent when nothing is pinned | non-goal: the hint names a modifier chord a soft keyboard does not have, and the verb it points at is already in every row's menu |
| `ARCHIVED_FETCH_LIMIT = 200` on the archived query (`store/sidebar-archive.ts:9,22`) | mobile-adaptation | The same `archived=only` query at limit 100 | Desktop reads `/api/profiles/sessions`, capped at 500 for exactly that caller (`profiles.py:222-228`); this app reads one leg through `/api/sessions`, whose ceiling is 100 (`sessions.py:91-94`), so 100 is the whole window available |
| The archived set is reachable on any backend the sidebar can talk to | mobile-adaptation | Refused with `Archived chats need a newer Hermes on this Gateway.` when only the `session.list` RPC is served | That RPC takes only `limit` and `include_hidden` (`tui_gateway/methods_session.py:389-400`) and emits no `archived` field (`:124-130`); an empty pool would render `Nothing archived`, which is a false claim about the account rather than a true one about the Gateway |
| An archived read that is in flight, or that failed, still renders `Nothing archived` (`sidebar-archive.ts:11,25-27`) | mobile-adaptation | `Loading archived chats…`, `Couldn’t load archived chats`, or `Archived chats unavailable` — `Nothing archived` only once the pool has answered | The sentence is a claim about the account, and this app's Gateway is across a network that drops; Desktop's is a local process. The marker Desktop keeps for re-entry (`$archivedSessionsLoading`, `sidebar-archive.ts:12,19,28`) is rendered here instead. `SessionListSectionsJourneyTest` covers all four states, `ChatViewModelTest` the state machine behind them |
| The archived set is re-read only when the `Archived` toggle goes on (`sidebar/index.tsx:1352-1358`) | mobile-adaptation | Also re-read when the endpoint or the profile routing changes while the view is on | Desktop's archived store survives a gateway switch untouched (`store/gateway-switch.ts:178-232` wipes the live lists and never names `$archivedSessions`), which leaves the previous backend's set on screen. This app clears every row on a switch through `SessionCache.resetForEndpointSwitch()`, so the same shape would leave the Archived view painting `Nothing archived` about a machine nobody has asked. The pool is re-read on the same seam instead |
| A second, hover-revealed `check-all` mark-all button in the section header (`sidebar/index.tsx:1725-1748`) | omission | Absent; Desktop's filter-menu item is the one that ships | non-goal: its whole affordance is pointer hover — a blank 24 px hole until the pointer arrives — and touch has none |
| Mark-all writes nothing to the Gateway (`store/session.ts:1113`, `store/session-unread.ts:302`) | mobile-adaptation | One `PATCH {"unread":false}` per unread row, reporting the count that refused | There is no local persisted watermark here, so the durable read state is the Gateway's and acking it is a write; the fan-out is serial and uncancellable, which is named as a limitation |
| The zero gate counts the whole transient finished-unread set (`filter-menu.tsx:172`) | mobile-adaptation | The loaded, in-scope, non-archived rows whose resolved dot is unread | The count has to describe the same rows the verb acts on, which are the ones this sidebar has loaded in the profile scope it is standing in |
| An archived all-pinned pool renders `No sessions match these filters` (`apps/desktop/src/i18n/en.ts:2666` @ `437116f9`): the recents empty state tests `filtersActive` before `allPinned` (`sidebar/index.tsx:1706-1712` @ `437116f9`), and `$sidebarFiltersActive` counts `$sidebarShowArchived` itself (`store/layout.ts:392-396` @ `437116f9`), so `s.allPinned` is unreachable while the toggle is on | mobile-adaptation | The archived view’s empty recents keeps `Everything here is pinned. Unpin a chat to show it in recents.` (`SessionGrouping.kt:149`, emitted at `:397`) — this app’s own sentence for that slot, the same one the live list shows | Desktop’s `these filters` names a `Status` / `Project` / `Profile` / `PR` filter surface this rail does not have (six controls omitted, owed to #142 above), so the sentence would point the reader at a control that does not exist. The note names the actual reason the archived recents area is empty — every archived row is pinned — which is the only one reachable here. Rendered by `SessionListSectionsJourneyTest`, ordered by `SessionGroupingTest` |
| `Ordering` (`filter-menu.tsx:260`), `Show` (`:274`), `Inbox style` (`:292`), `Status` (`:302`), `Profile` (`:334`) and `Collapse all` (`:408`), plus the `Filters` group caption that heads them (`:299`) | omission | Absent from the menu entirely; the only caption Android renders is its own `GROUPING` | pill-owed: #142 — #66 deliberately took only `Archived` and `Mark all as read`, so the rows were never built; since #101 the standing rule is that an unsupported **control** stays visible and disabled behind the `WIP` chip rather than vanishing, and six of them vanish here. The rendered pair is `docs/parity/visual/session-list-archived-filter/` |
| Archived Chats settings page, its per-row `<folder> · N messages` hint and auto-archive-after-N-days (`app/settings/sessions-settings.tsx`) | omission | Absent; the restore lives in the row's own menu | deferred: #73 — session maintenance; #66 declares them non-goals |
| Bulk selection on the archived list | omission | Absent | out-of-scope: #66 — no bulk operations |
| A `draft` dot below unread (`session-dot-state.ts:129-131`) | omission | Folded into `Idle` | out-of-scope: #66 — this list has no draft state to distinguish yet |
| `sidebar.dateDivider` reads `Earlier today` / `Yesterday` / `Earlier this week` / `Last week` / `Earlier this month`, then one divider per calendar month — a month name, then month + year from `Intl` (`apps/desktop/src/i18n/en.ts:2794-2800`; `apps/desktop/src/lib/time.ts:118-124,155-165`, formatters `:30-31` @ `437116f9497c80d242ce034ff7f5d81dc277a337`) | mobile-adaptation | **Same words, same tail, same head rule.** `Earlier today` is reachable because the head is the newest *run*, cut at a real break exactly as Desktop cuts it: `headRunCutoff` at `data/session/SessionGrouping.kt:234-271` ports `apps/desktop/src/lib/session-date-groups.ts:44-88` (5 rows / 30 min / 8 h, `:278-284`), everything above the cutoff is unlabelled, and a bucket carrying the relational word therefore always has something newer above it. Past `Earlier this month` the tail is one divider per calendar month — key `m-<year>-<month>` inside the current year, `my-<year>-<month>` outside (`calendarBucket`, `:207-211`; the key keeps JavaScript’s zero-based month, because the key is what a later read compares) — worded through ICU’s own resolved skeleton rather than enumerated (`data/session/IcuSessionBucketLabel.kt:39,50-56`) | #299. Mobile priority: the bucket label stays one resolved skeleton on the device (`data/session/IcuSessionBucketLabel.kt:39,50-56`) instead of a second hand-written enumeration, and ICU and Desktop’s `Intl` resolve the same skeleton on the same calendar, so a bucket renders the same word on both sides. The five relative strings are Kotlin constants (`SessionGrouping.kt:49-59`), so a translated Desktop is not claimed — the app’s English labels are existing scope. The head rule and the tail are read off pixels at this head: the scrolled Android capture carries `EARLIER THIS MONTH`, then `JULY` and `NOVEMBER 2025` as two distinct per-month dividers, and the same words and the same cut sit in the Desktop reference (`docs/parity/visual/session-list-months-captions/`) |
| An auto-discovered repo lane wears the `repo` glyph where an explicit project wears `folder-library`, and its accessible name ends `(Auto-discovered)` (`app/chat/sidebar/project-row.tsx` @ `564aef2946`) | mobile-adaptation | Only the auto lane is marked: it gains the `repo` glyph and the `(Auto-discovered)` suffix, and an explicit project keeps its glyphless lead | This app never carried Desktop’s `folder-library` lead glyph on a project row, so porting the swap wholesale would mean giving every row a glyph it does not have today — a separate change to a shared row. The *distinction* is what the upstream fix is for, and it survives: one lane is marked and the other is not. Desktop’s own reason for putting the cue in the accessible name rather than the tooltip — the glyph is `aria-hidden` and the tooltip only speaks on hover — is stronger on a phone, which has no hover at all |
| `Grouping` is a submenu trigger showing the active value on its right (`filter-menu.tsx:238-258`) | mobile-adaptation | An inline `GROUPING` caption over two radio rows, `Updated` (checked) and `Project` | Nested pointer submenus are brittle on a phone and the port workflow's standing rule is to flatten them; with two options the flattened form costs one caption and shows the choice without a second surface. `GROUPING` is not a Desktop string — it is this list's own section caption, applied to a Desktop control |
| The filter menu has no `Search` item; search is a persistent field in the sidebar header (`sidebar/index.tsx`, `en.ts:2200-2202`) | mobile-adaptation | A `Search` row sits in this menu, above `Archived` | Viewport: the drawer header holds the connection, the title, `+` and the filter trigger already, and a permanent field would take a row of the list on every phone. The field itself is unchanged when it opens — see `session-search.md` |
| An auto-discovered repo lane wears the `repo` glyph where an explicit project wears `folder-library`, and its accessible name ends `(Auto-discovered)` (`app/chat/sidebar/project-row.tsx` @ `564aef2946`) | mobile-adaptation | Only the auto lane is marked: it gains the `repo` glyph and the `(Auto-discovered)` suffix, and an explicit project keeps its glyphless lead | This app never carried Desktop's `folder-library` lead glyph on a project row, so porting the swap wholesale would mean giving every row a glyph it does not have today — a separate change to a shared row. The *distinction* is what the upstream fix is for, and it survives: one lane is marked and the other is not. Desktop's own reason for putting the cue in the accessible name rather than the tooltip — the glyph is `aria-hidden` and the tooltip only speaks on hover — is stronger on a phone, which has no hover at all |

## Visual report

- report: docs/parity/visual/session-list-months-captions/report.html
- commit: 808b882ec52a0ecf60b217587b2bed1a27ad3ebb

The exact-head side-by-side exists now, so this page's statements about the
panel captions, the `Sessions` pool caption, the head-run cutoff and the
per-month tail are readings of rendered pixels rather than implementation
claims. Packet: `docs/parity/visual/session-list-months-captions/` — four
Android states, each with a receipt that passes
`python3 scripts/visual_parity_contract.py check-receipt --platform android`,
against one Desktop reference whose receipt passes the same check for
`--platform desktop`.

| Side | Provenance |
|---|---|
| Desktop | One full reference at pin `437116f9497c80d242ce034ff7f5d81dc277a337`: clean disposable export, the app's own E2E mock backend and preload bridge, fixture `session-list-sections-synthetic-v1`, 14 synthetic rows, pinned clock `2026-09-17T14:20:00Z` UTC, locale `en-US`, dark theme, the whole seed in one 1121 px pane |
| Android | `808b882ec52a0ecf60b217587b2bed1a27ad3ebb`, the captured production-code commit; the packet refresh changes documentation only — four states, each captured against a debug APK whose local and installed SHA-256 are equal, on the pinned emulator shape: `105b0f9ded02a2bcbe2014d2e71e4fd3588f981de9d3a711c93f10af723a0a05` (`pinned-sessions-month-dividers`), `e6cd6dab18dfef66f0b01826bedc19c562abb01933516f3f2cce9ad219df1e4b` (`months-scrolled`), `98d1d4c1ce0389c8b048804cf37d82da13fc48e45ddfd799a44cb4df8b6d82a4` (`results`), `c870b26fa07fc34f557f582b19f5cdefece1623e574facfa1666e33fdefc7b7e` (`all-pinned`) |

What the pixels settle: Desktop's two caption treatments are spent where
Desktop spends them, the head run above `Earlier today` is unlabelled at
Desktop's own cut, the tail carries `EARLIER THIS MONTH` plus `JULY` and
`NOVEMBER 2025` as two distinct per-month dividers, `Results` is one flat
section, and the all-pinned state renders `Everything here is pinned. Unpin a
chat to show it in recents.` word for word.

What this report does not claim:

- **Desktop is one full reference, not four.** That pane renders the whole seed,
  so one image serves both the top state and the tail state. The `results` and
  `all-pinned` captures are Android regressions of states Desktop has no
  rendered counterpart for at this pin; they are not a Desktop pixel comparison
  and are not cited as one.
- **The two panes are not pixel-identical.** `SESSIONS` stays at the top of the
  Android pane and again above the scrolling pool: the drawer's fixed
  navigation chrome above Desktop's scrolling pool caption. That is Desktop's
  single header row adapted to a drawer — a mobile-adaptation, not a claim of
  identity (see the caption-hierarchy row above).
- **Translation parity.** The app renders English labels by existing scope; a
  translated Desktop is not claimed and is not a new drift blocking this
  English issue.
- **#146 is untouched.** The archived view still drops the `Pinned` section;
  this packet is about the captions and the months and does not close it.

Only validated receipts and screenshots are stored. The capture manifest is
deliberately not copied: it prints workstation paths and a device serial, while
the receipts the validator accepts carry immutable identities instead.

### Historical packet (pre-#141 / #299)

Rendered side by side at `be20b61`, before this change, and kept as history
rather than as evidence for the current head. Desktop was captured from a
disposable export at the pin then current (`3ca096de`) with a headless CDP
renderer and synthetic sessions; Android on a Pixel 10 Pro emulator in light
theme against a `kame-qa` QA profile seeded with four synthetic sessions among
its existing QA rows — one pinned pair, one unread row and one archived row.
Three states, both sides each:

| State | Report |
|---|---|
| session list sections | docs/parity/visual/session-list-sections/report.html |
| archived filter | docs/parity/visual/session-list-archived-filter/report.html |
| archived view | docs/parity/visual/session-list-archived-view/report.html |

That render was built at `be20b61`. It is what caught the divider copy and the
section-label treatment (#141), the six filter-menu controls that are absent
rather than disabled (#142), and the archived view dropping the `Pinned`
section (#146). It also shows the pre-change list: `SESSIONS` only as the pane
title, `PINNED` a bare field label, no pool caption, one terminal bucket for
everything past the current month, and rows without the trailing age — so it is
not evidence for any of those now.

**#66 stays open.** Six filter-menu controls render on Desktop and are absent
here rather than disabled, and `docs/workflows/review-desktop-parity.md:253-254`
makes that a Block, not a Concern. The issue closes when #142 lands their pills.

The row age (#143) landed after that capture, so that packet predates it:
`docs/parity/visual/session-list-sections/android/reference.png` shows rows
without the trailing age and is not evidence for the field.

**The #146 archived-view packet is likewise pre-fix.**
`docs/parity/visual/session-list-archived-view/` is the capture that *caught* the
drift — Desktop’s `PINNED … SESSIONS …` beside Android’s flat list — so it is
evidence for the finding, not for the current render. A post-fix archived capture
is dispatched from `docs/parity/visual-capture-surfaces.json` under the
`session-list-sections` surface; until that run’s artifact is committed, the layout,
ordering, single-rendering and no-divider assertions in
`SessionListSectionsJourneyTest` and the row-order assertions in
`SessionGroupingTest` stand in for it.
