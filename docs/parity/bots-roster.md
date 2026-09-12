# Bots roster: the port's pin, its rendered contract and its divergences

Hermes Desktop's Bot Mode **roster** — the pane reached from the Bots rail entry,
with its search, its two filter axes, its hidden section, its user sections and
its seven empty/error/stale states — ported per
[`docs/workflows/port-desktop-surface.md`](../workflows/port-desktop-surface.md)
into `app/src/main/kotlin/com/hermesagent/mobile/plugins/bots/`:
`BotsPlugin.kt` (the two contributions), `BotsRosterScreen.kt` (the surface),
`BotsRosterDerivation.kt` (ordering, filtering, grouping, presentation),
`BotsRosterModel.kt` (rows, limits, copy), `BotsRowLabels.kt` (age, name,
handle, preview), `BotsPluginRepository.kt` (`profiles.list` and its parser),
`BotsViewModel.kt` (state) and `BotsAttention.kt` (the badge rule).

## Pin

| Source | Pin | Read via |
|---|---|---|
| Desktop roster plugin, plugin bundle copy, core `en.ts` time labels, Gateway `profiles.list` | `hermes-agent` @ `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd` | the read-only checkout; every citation below taken with `git show <sha>:<path>` |

Every `path:line` in this page and in the port's source is against that SHA.
The read-only checkout is **not** at the pin; nothing here was read from its
working tree.

## Paths that settled the port

| Question | Path |
|---|---|
| The roster row's payload: name, display name, path, model, provider, skills, sessions | `tui_gateway/methods_profiles.py:204-219,236-254` |
| Row ordering, the `max(created, lastMsg)` activity term, the gateway axis | `apps/desktop/src/plugins/hermes-bots/roster-pane-derivation.ts` |
| The row's age label, @handle, display name and preview | `apps/desktop/src/plugins/hermes-bots/bot-row.tsx:79-88`, `labels.ts:14-90`, `data.ts:952-963` |
| The coarse age buckets (`now`, `m`, `h`, `d`) | `apps/desktop/src/lib/time.ts:194-215`, core `apps/desktop/src/i18n/en.ts:2534-2537` |
| Worker liveness, and the 150 s window that bridges one missed heartbeat | `apps/desktop/src/plugins/hermes-bots/row-helpers.ts:72-86` |
| Which bars the roster draws, and when | `apps/desktop/src/plugins/hermes-bots/roster-pane-derivation.ts` (`deriveRosterPresentation`) |
| The state order: loading, error, empty, all-hidden, no-match, list | `apps/desktop/src/plugins/hermes-bots/roster-pane-content.tsx:66-178` |
| The stale banner's exact sentence, including the joined space | `apps/desktop/src/plugins/hermes-bots/roster-pane.tsx:395-400` |
| The plain flat list when no sections are made | `apps/desktop/src/plugins/hermes-bots/roster-pane-sections.tsx:72-75` |
| Unassigned is an unnamed, header-less block | `apps/desktop/src/plugins/hermes-bots/user-sections.ts:222-231` |
| Unassigned's label belongs to the drop-zone heading | `apps/desktop/src/plugins/hermes-bots/user-sections-ui.tsx:148-160` |
| Every rendered string | `apps/desktop/src/plugins/hermes-bots/i18n.ts:270-329` |
| The attention classification, and that a rate limit, a 5xx or a timeout never badge | `apps/desktop/src/plugins/hermes-bots/attention.ts`, `audit §1.4` |

## The seven states, as the surface renders them

| State | Desktop | Android |
|---|---|---|
| Loading, no roster | a spinner (`:66-70`) | `BotsRosterCopy.WAITING_FOR_GATEWAY` in the roster message slot, which is also where a cold start waits for the connection |
| Error, no roster | `ready` sentence and a Retry button (`:71-83`) | the same sentence and `RETRY_NOW` |
| True empty | `PanelEmpty` with `emptyTitle` / `emptyDesc` (`:84-85`) | the same two strings |
| All bots hidden | explainer plus a `showHidden` button that expands the section (`:86-105`) | the same explainer and the same button, which expands the hidden section in place |
| Filtered to nothing | `PanelEmpty` with one of four copies (`:106-120`) | the filters copy, or the query copy when a query is set |
| Stale over a held roster | the banner above the list (`:63-65`) | the same banner above the same list |
| Normal list | flat list, or sectioned once sections exist | the same, with user sections |

**A connection switch is not one of the seven.** It is the one transition that
*drops* a state rather than entering one: changing endpoint empties the roster
and takes the stale notice with it, which lands the surface back in the waiting
state, and the new connection's own edge is what reads the new endpoint
(`BotsViewModel.dropRosterForEndpointSwitch`). The attention badges recorded
against those rows go with them (`BotAttentionStore.clearAll`): they are keyed by
roster key alone, which the next machine can recycle.
Desktop draws the same boundary a
different way — its roster query is keyed by the active connection id
(`data.ts:638` @ the pin), so one machine's answer is never the next machine's
cache — and the rule is the one `AGENTS.md` states for the session cache: the
next backend is a different machine that can recycle the same durable ids. A
reconnect to the *same* endpoint drops nothing; the last good list under its
banner is exactly what that path is for.

## Copy

Every string below is byte-identical to the plugin bundle at the pin. Curly
quotes and the `…` are the source characters, not a rendering choice.

| Desktop key | Desktop string | Android |
|---|---|---|
| `roster.search` | `Search bots and group chats` | `BotsRosterCopy.SEARCH` |
| `roster.searchPlaceholder` | `Search bots and group chats…` | `BotsRosterCopy.SEARCH_PLACEHOLDER` |
| `roster.emptyTitle` | `No bots yet` | `BotsRosterCopy.EMPTY_TITLE` |
| `roster.emptyDesc` | `Create your first bot.` | `BotsRosterCopy.EMPTY_DESC` |
| `roster.noMatchFilters` | `No bots or group chats match these filters.` | `BotsRosterCopy.NO_MATCH_FILTERS` |
| `roster.noMatchQuery` | `No bots or group chats match “{query}”` | `BotsRosterCopy.noMatchQuery` |
| `roster.clearFilters` | `Clear filters` | `BotsRosterCopy.CLEAR_FILTERS` |
| `roster.allHidden` | `All bots are hidden` | `BotsRosterCopy.ALL_HIDDEN` |
| `roster.allHiddenDesc` | `They keep working and retain their history.` | `BotsRosterCopy.ALL_HIDDEN_DESC` |
| `roster.showHidden` | `Show hidden bots` | `BotsRosterCopy.SHOW_HIDDEN` |
| `roster.noHiddenMatch` | `No hidden bots match these filters.` | `BotsRosterCopy.NO_HIDDEN_MATCH` |
| `roster.hiddenFromRoster` | `Hidden from the roster` | `BotsRosterCopy.HIDDEN_FROM_ROSTER` |
| `roster.pinned` | `Pinned` | `BotsRosterCopy.PINNED` |
| `roster.needsAttention` | `needs attention` | `BotsRosterCopy.NEEDS_ATTENTION` |
| `roster.botsAndGroups` | `Bots and group chats` | `BotsRosterCopy.BOTS_AND_GROUPS` |
| `roster.botsOnly` | `Bots only` | `BotsRosterCopy.BOTS_ONLY` |
| `roster.groupsOnly` | `Group chats only` | `BotsRosterCopy.GROUPS_ONLY` |
| `roster.anyActivity` | `Any activity` | `BotsRosterCopy.ANY_ACTIVITY` |
| `roster.activeNow` | `Active now` | `BotsRosterCopy.ACTIVE_NOW` |
| `roster.recentlyActive` | `Recently active` | `BotsRosterCopy.RECENTLY_ACTIVE` |
| `roster.older` | `Older` | `BotsRosterCopy.OLDER` |
| `roster.retryNow` | `Retry now` | `BotsRosterCopy.RETRY_NOW` |
| `roster.rosterUnavailable` | `Roster unavailable: {reason}. If your gateway predates profiles.list, update Hermes and restart the gateway.` | `BotsRosterCopy.rosterUnavailable` |
| `roster.waitingForGateway` | `Waiting for the gateway connection… (remote gateways can take a few seconds; retries automatically)` | `BotsRosterCopy.WAITING_FOR_GATEWAY` |
| `sections.unassigned` | `Unassigned` | `BotsRosterCopy.UNASSIGNED` |
| `roster-pane.tsx` literal | `Roster refresh failed — showing the last good list.` + ` Waiting for the gateway to reconnect…` | `BotsRosterCopy.refreshFailed(connectionUp)` |
| core `en.ts` time labels | `now`, `m`, `h`, `d` (`:2534-2537`) | `BotRowAgeLabels` defaults |

## Mobile adaptation ledger

| Desktop | Android | Reason |
|---|---|---|
| A docked pane in the rail, with a right-click row menu | A full-screen destination entered from Settings → Bots, with a back affordance | A phone has one column; the rail cannot hold a pane beside the conversation, and touch has no right-click |
| `refetchInterval: 5000` on the roster query, plus an immediate refetch when the socket opens | A read on the connection's edge, a read when the surface resumes, and a repeated read if one lands mid-flight | A 5 s poll is a per-5 s Gateway round trip against a screen a person is looking at; the edge and the surface entry carry every case the poll exists for, and a bot created since the last look still appears |
| Every row action is revealed on hover | Nothing is hidden behind hover; what exists is always drawn | Touch has no hover, so a control that appears on hover never appears |
| Sections are filed by dragging a row onto a heading | The same sections render; filing is not on this surface yet | Drag inside a scrolling list is a known phone failure; the audit's plan is an explicit "Move to section" sheet |
| Desktop-scale glyphs inside its own hit boxes | The same glyphs inside 48dp targets, and a `contentDescription` or a label on every control | Touch targets are a platform floor, and the visual size of the glyph is not what grows |

## Visual report

- pending: #216

Nothing in this card produced a rendered side-by-side, and no render is
described in prose here on purpose.

**What blocked it, exactly.**

1. **Android.** `capture-android-reference.py` refuses to capture unless `adb`
   reports the app both resolvable and focused. On this host `adb devices`
   lists no device, and `/opt/android-sdk` carries no `emulator` package
   (`build-tools`, `cmake`, `cmdline-tools`, `licenses`, `ndk`, `platform-tools`,
   `platforms`), so there is no emulator to start. There is no Android capture
   to build a report from.
2. **Desktop.** The capture is taken from the *running dev renderer* over CDP
   (`capture-desktop-reference.mjs --port 9222 --match …`). No renderer answers
   on `127.0.0.1:9222`, `$DISPLAY` is unset, and the roster pane additionally
   needs that renderer paired to a live Gateway serving bot profiles. A
   disposable pinned export *was* made
   (`git clone --no-hardlinks --no-checkout` of the read-only checkout, then
   `git checkout 72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`), so the export step
   is known-good; rendering it means `npm install` in `apps/desktop`, its vite
   dev server, Electron under a virtual display, and a Gateway pairing, none of
   which fits this card.

**What was actually run, and what each one said.**

```
python3 .chalk/skills/port-hermes-desktop-surface/scripts/capture-android-reference.py --name bots-roster
  → CalledProcessError: adb get-state exit 1  (adb devices: no device attached)

node .chalk/skills/port-hermes-desktop-surface/scripts/capture-desktop-reference.mjs \
  --name bots-roster --match bots --upstream <disposable pin export> \
  --expect-sha 72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd
  → TypeError: fetch failed — connect ECONNREFUSED 127.0.0.1:9222
```

The second run is past the pin gate — the export is accepted — and dies on the
missing renderer, not on the pin. Pointing the same script at the read-only
checkout instead refuses earlier with "upstream checkout is dirty".

Neither blocker is a claim that the pixels match. The surface's states are
exercised through the real rendered tree in
`app/src/testDebug/kotlin/com/hermesagent/mobile/ui/BotsRosterJourneyTest.kt`,
which is evidence for behaviour and for the semantics tree, not for the visual
contract. The rendered comparison stays owed against #216.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| `New bot or group chat` dropdown: New Bot, New Group, New section (`i18n.ts:274,308-311`) | drift | Absent; no marker chip stands in its place | #189 owes it as a visible, disabled control behind the WIP chip, not as a silently missing one |
| Gateway filter group, and the gateway sections the roster buckets rows into (`roster-sections.tsx`) | drift | Absent; the roster is one flat Gateway, and the active-filter count covers the two axes that exist | #189 — this app is single-connection by design (`docs/adr/0002-shared-remote-gateway.md`), but the axis still owes its disabled chip |
| Group-chat rows inside the roster (`roster-pane-derivation.ts`, `bot-row.tsx:483-528`) | drift | Absent; selecting `Group chats only` honestly matches nothing | #189; group chats ride the gateway's hosted-room protocol (`docs/adr/0004-hosted-rooms-for-group-chats.md`) and are their own slice |
| Pin, hide, rename, reorder and section editing (`user-sections-ui.tsx`, `bot-row.tsx:100-444`) | drift | The roster renders pinned and hidden state from `BotMeta`, and nothing can write it yet | #189 — the editing surface is the writer the port's read-only `metaByKey` constructor input is waiting for |
| Desktop's row is a mood-driven face and an avatar (`bot-row.tsx`, `avatar-picker.tsx`) | drift | Absent; the row is the name, the @handle, the age and the preview | #189, with the avatar/pet surface |
| `Active now` means a live turn or a live worker (`row-helpers.ts:125-186`) | drift | Activity inside 90 s, or a live worker inside 150 s, from `worker_session` | #189 — the worker half landed with this port; the live-turn half is the live-state slice |
| Row ordering uses `max(created, lastMsg)` (`roster-pane-derivation.ts` `sortRosterBots`) | drift | Ordering drops the `created` term, so a bot created and never spoken to sorts last | #189 — `created` is on the wire and nothing in this app reads it |
| The stale banner needs `error && !live && roster.length` (`roster-pane.tsx:395-400`) | drift | Banner shows for any failed refresh over a held roster; there is no live-turn signal to exclude | #189 — the `!live` term arrives with the live-state slice |
| An error card carries the sentence and Retry, with no heading (`roster-pane-content.tsx:71-83`) | drift | The same sentence and button, drawn under `emptyTitle`, so a failure reads under "No bots yet" | #189 — the failure slot borrows the empty state's heading; a failure has no heading in Desktop |
| The no-match card is `aria-live="polite" role="status"` (`:108-119`) | drift | A plain failure slot with no live region | #189 — the announcement is owed with the semantics sweep |
| Empty *named* sections stay rendered as dashed drop slots (`roster-pane-sections.tsx:126-133`) | mobile-adaptation | Empty named sections are dropped; a section with rows renders | Without drag-and-drop there is no drop target, so an empty slot would be a heading with nothing to do and no way to fill it |
| Desktop's own type, spacing and radii | mobile-adaptation | Drawn from `HermesTheme.tokens` and `HermesTheme.type`, one-to-one with Desktop's categories | The port preserves the design language; the token layer is this app's contract for it, and a raw colour or a preset name would import a different language |
