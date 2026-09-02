# Session search: the sidebar field, the Results section and the backend index

The sessions rail's search — its field, its debounce, what it matches locally,
what it asks the Gateway for, and the one section it answers in
(`ui/common/Primitives.kt`, `ui/sessions/SessionList.kt`,
`data/session/SessionGrouping.kt`, `ui/chat/ChatViewModel.kt`,
`data/gateway/GatewayRestClient.kt`,
`data/gateway/GatewaySessionRepository.kt`), ported per
[`docs/workflows/port-desktop-surface.md`](../workflows/port-desktop-surface.md).

The sections this search *replaces* while it is live — `Pinned` and the date
buckets — are [`session-list-sections.md`](session-list-sections.md).

## Pin

| Source | Pin | Read via |
|---|---|---|
| Desktop renderer, i18n and Gateway route | `hermes-agent` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3` | read-only checkout; the working tree has drifted, so every citation below was taken with `git show <sha>:<path>` |

Every `path:line` below is against that SHA.

## Paths that settled the port

| Question | Path |
|---|---|
| The field, and what it is given | `apps/desktop/src/app/chat/sidebar/index.tsx:1593-1603` |
| The field's own chrome: leading glyph, clear button, no debounce | `apps/desktop/src/components/ui/search-field.tsx:57-103` (glyph `:69`, spoken name `:71`, clear `:90-100`) |
| Copy | `apps/desktop/src/i18n/en.ts:2200-2204` (`searchAria`, `searchPlaceholder`, `clearSearch`, `noMatch`, `results`); the clear button's own label at `:3565` |
| Search state | `sidebar/index.tsx:434-444` (`searchQuery`, `serverMatches`, `searchPending`, `trimmedQuery`) |
| The debounce, and why search goes to the backend at all | `sidebar/index.tsx:619-653` — "Full-text search across *all* sessions (not just the loaded page) so 699 sessions stay findable", `setTimeout(…, 200)` at `:647` |
| That a failed search is swallowed | `sidebar/index.tsx:641` (`.catch(() => undefined)`) |
| The merge: local matches first, server hits appended, loaded row wins | `sidebar/index.tsx:655-678` |
| The index a server hit is resolved through | `apps/desktop/src/app/chat/sidebar/session-index.ts:17-33` (`buildSessionByAnyId`: live id *and* lineage root) |
| What a server hit becomes when nothing is loaded for it | `sidebar/index.tsx:265-293` (`stripFtsMarkers`, `searchResultToSession`) |
| Which fields the client-side match reads | `apps/desktop/src/lib/session-search.ts:7-23` |
| The source terms, labels and aliases behind that last field | `apps/desktop/src/lib/session-source.ts:3-40,111-130` |
| What "normalised" means | `apps/desktop/src/lib/text.ts:11` (`trim().toLowerCase()`) |
| The `Results` section, its skeletons and its empty sentence | `sidebar/index.tsx:1611-1638` |
| That Pinned and Recents are hidden while a query is live | `sidebar/index.tsx:1640,1664` |
| The skeleton rows' shape | `apps/desktop/src/app/chat/sidebar/section-states.tsx:12-24` |
| The REST call Desktop makes | `apps/desktop/src/api/sessions.ts:348-352` (`q` alone) |
| The response contract | `apps/desktop/src/types/hermes.ts:1193-1208` |
| The route, its parameters and its clamp | `hermes_cli/web_routers/sessions.py:205-213,224-229` |
| That it ranks direct session-id hits first | `sessions.py:353-377` (`search_sessions_by_id`, `include_archived=True`) |
| That it dedupes by compression lineage root and returns the tip | `sessions.py:306-321` |
| That partial words match | `sessions.py:379-389` (automatic `*` suffix) |
| The palette precedent for calling a keyboard surface a non-goal | [`system-panel.md`](system-panel.md) ledger row 1 |

## What ships

| Element | Desktop | Android |
|---|---|---|
| Field placeholder | `Search sessions…` (`en.ts:2201`) | **Same**, verbatim, ellipsis included |
| Field spoken name | `Search sessions` (`en.ts:2200`) | **Same**, as the editable node's content description |
| Leading glyph | `search` Codicon, `size-3.5`, muted (`search-field.tsx:69`) | **Same** glyph — `HermesIcon.Search`, `U+EA6D` — at 14 sp in `tokens.textTertiary` |
| Clear affordance | `close` Codicon button labelled `Clear search`, shown only when the field is non-empty (`search-field.tsx:90-100`) | **Same** glyph, **same** label, same condition, at the 48 dp touch floor |
| Field chrome | Borderless until focus, then an underline | **Same**: `Hairline`, accent while non-empty |
| Debounce | 200 ms on the trimmed query, no minimum length (`sidebar/index.tsx:634-653`) | **Same** 200 ms, same trim, same no-minimum, keyed on the query |
| Instant client-side match | `sessionMatchesSearch` over the loaded rows (`session-search.ts:7-23`) | **Same** seven fields: id, lineage root, title, preview, cwd, git branch, source terms |
| Source terms | id, label, aliases (`session-source.ts:121-130`) | **Same** table, **same** aliases, same title-cased fallback for an unknown source |
| Backend call | `GET /api/sessions/search?q=…` | **Same** route, plus `limit` at the route's own default 20 and the rail's `profile` — ledgered below |
| Result merge | Local matches first, then server hits not already present; the loaded row object always wins | **Same** order, **same** precedence |
| A server hit nothing is loaded for | A stub: id, lineage root, snippet as preview with FTS markers stripped, `session_started` as activity, no title (`sidebar/index.tsx:272-293`) | **Same** stub, field for field. The snippet also passes `redact()` and the bound this app puts on every piece of Gateway prose |
| Where a stub lives | The sidebar's own memo, never the session store | **Same**: returned to the ViewModel, never written to `SessionCache` |
| Section while a query is live | One `SidebarSessionsSection` labelled `Results` (`en.ts:2204`), Pinned and Recents hidden | **Same**: one `RESULTS` section label, no pinned group and no date dividers |
| While the backend is still answering and nothing matched locally | Five `SidebarSessionSkeletons` rows (`section-states.tsx:12-24`) | **Same** five rows, **same** five widths, hidden from the accessibility tree as Desktop's `aria-hidden` hides its own |
| Settled with nothing | `No sessions match “{query}”.` (`en.ts:2203`) | **Same** sentence, verbatim, typographic quotes included |
| A Gateway that does not serve the route | n/a — Desktop ships with its own backend | The 404 is remembered per connection and search falls back to the loaded rows alone; no error banner, because there is nothing the reader can do |
| Pasting a raw session id | The route answers direct id matches first (`sessions.py:353-377`), and the row opens like any other | **Same** — the row the search returns is a row, and tapping it opens the session |

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| The request carries `q` alone (`api/sessions.ts:348-352`) | mobile-adaptation | Also sends `profile`, through the same `scopeQuery` every other leg uses | Desktop's sidebar is one profile's by construction; this app's foot rail can stand in a named profile or in the unified view, and a search that ignored that scope would answer with conversations the list beside it does not show |
| The request carries no `limit`, taking the route's default (`sessions.py:208`) | mobile-adaptation | Sends `limit=20` explicitly | Same number, said out loud: this client refuses a limit outside the route's own `1..100` clamp (`sessions.py:229`) rather than letting a caller's arithmetic quietly read a page it did not ask for, which is the rule `listSessions` already applies |
| The field is always present above the sections (`sidebar/index.tsx:1593-1603`) | mobile-adaptation | Behind the filter menu's `Search` / `Hide search` toggle, and forced open whenever the query is non-empty | A 360 dp rail spends a whole row of fixed chrome on a field that is empty most of the time; the toggle is one tap away in the menu that already holds the list's other view verbs, and the field cannot hide a live query |
| `⌘K` / `⌘P` command palette with a `Go to session` section (`app/command-palette/index.tsx`, `en.ts:1573-1579`) | omission | Absent; the sidebar field is the whole session-lookup surface | non-goal: a phone has no chord keyboard to open it, and its session rows are a client-side scan of 200 loaded sessions — strictly less than the field's backend index. Same judgement as the System panel's palette row in `system-panel.md` |
| `session.focusSearch` hotkey focusing the field (`sidebar/index.tsx:446-453`, `en.ts:302`) | omission | Absent | non-goal: a soft keyboard has no chord to bind, and the field is already reached by the tap that reveals it |
| Skeleton rows are `SidebarRowShell` with Tailwind `w-32 w-40 w-28 w-36 w-24` (`section-states.tsx:15-20`) | mobile-adaptation | Five rows of the same five widths in dp, drawn from `tokens.strokeQuaternary` at this rail's row height | Compose has no Tailwind scale and no `Skeleton` component; the ragged five-width edge is what the placeholder communicates, so that is what was ported. Theme tokens only — no raw colour |
| A server hit is deduped against loaded rows by its own `session_id` only (`sidebar/index.tsx:669-674`) | mobile-adaptation | Also deduped against the stub's `lineage_root`, and a loaded row's own root is a key | Desktop resolves the hit through an index keyed by live id *and* lineage root (`session-index.ts:17-33`) but then files the result under the hit's id, so one conversation can occupy two rows when the hit arrives under the root of a row already on screen. The route already collapses a lineage to one result (`sessions.py:306-321`), so honouring that key here costs nothing and cannot show a chat twice |
| A failed search leaves the previous query's hits on screen (`sidebar/index.tsx:641` never clears `serverMatches`) | mobile-adaptation | A failure, a refusal or an absent route drops the server half and leaves the local matches | On a phone the Gateway is across a network that drops, so this failure is ordinary rather than exceptional; showing a stale answer under a new query would be a claim about the wrong words. The local matches — which is what Desktop is really protecting — are untouched |
| A query inside the `Archived` view searches through the same `Results` section (`sidebar/index.tsx:1611` is not gated on the archived toggle) | mobile-adaptation | Stays a local filter over the archived pool: no `Results` label, no server hits | The archived set is its own capped read here (`ArchivedPoolState`, ledgered in `session-list-sections.md`), while the search contract carries no `archived` field at all (`types/hermes.ts:1193-1208`) and Desktop's own stub hardcodes `archived: false` (`sidebar/index.tsx:276`). Merging server hits into that pool would put live rows in the Archived view, which is a worse answer than a narrower one |
| The project overview has no search of its own | mobile-adaptation | Keeps its own field copy — `Search projects`, `No project or recent session contains “…”.` — untouched by this port | Desktop reaches projects through a different surface entirely; there is no Desktop string to be verbatim against, so the app-authored sentence stays and the session sentences do not leak onto it |
| Cross-profile search | omission | Search is scoped to the profile the rail is standing in | deferred: #73 — cross-profile search is its own bullet on that epic, and it needs a route this Gateway does not expose |

## Visual report

- pending: #73

Not captured. The Desktop reference capture in the port workflow needs a
disposable pinned dev renderer with CDP, which was not available for this
slice; the copy, glyph vocabulary, section order and skeleton shape are pinned
against source and the shipped font instead, and the rendered structure is
asserted under Robolectric by `SessionSearchJourneyTest`.
