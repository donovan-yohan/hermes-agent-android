# Profile rail, active-profile scope, and roster: source and deviation ledger

The sidebar-foot profile rail (`ui/sessions/ProfileRail.kt`), the active-profile
scope (`data/profiles/`), and the read-only roster (`ui/profiles/`), ported per
[`docs/workflows/port-desktop-surface.md`](../workflows/port-desktop-surface.md).

## Pin

| Source | Pin | Read via |
|---|---|---|
| Desktop renderer, Gateway, CLI | `hermes-agent` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3` | read-only checkout; the working tree has drifted, so every citation below was taken with `git show <sha>:<path>` |

Every `path:line` below is against that SHA.

## Paths that settled the port

| Question | Path |
|---|---|
| The rail: layout, pinned pills, collapse threshold | `apps/desktop/src/app/chat/sidebar/profile-switcher.tsx:119-345` |
| The condensed rail's own menu, and what a fleet group heads its list with | `apps/desktop/src/app/chat/sidebar/profile-switcher.tsx:722-829` and `:808-824` |
| A profile's mark (`home` vs tinted initial) | `apps/desktop/src/components/ui/profile-glyph.tsx:10-43` |
| The identity colour and its hash | `apps/desktop/src/lib/profile-color.ts:6-55` |
| The owning-profile chip on a session row | `apps/desktop/src/app/chat/profile-tag.tsx:12-29` and `profile-tag.test.tsx:24-50` |
| Which sessions one scope shows | `apps/desktop/src/app/chat/sidebar/profile-scope.ts:5-13` and `profile-scope.test.ts:12-29` |
| Canonical key, label, ordering | `apps/desktop/src/store/profile.ts:22-33,92-106` |
| Scope state: `showAll` over the live gateway profile | `apps/desktop/src/store/profile.ts:423-448` |
| Switching, and what a switch resets | `apps/desktop/src/store/profile.ts:453-483` |
| Stale-answer guard on the roster fetch | `apps/desktop/src/store/profile.ts:49-74` |
| The roster panel | `apps/desktop/src/app/profiles/index.tsx:105-268` |
| Panel grammar (header, list, row, pill, meta, empty) | `apps/desktop/src/app/overlays/panel.tsx:68-142,170-211,295-372` |
| Panel narrow layout: list above detail | `apps/desktop/src/app/overlays/panel.tsx:88-98,126-128` |
| Copy | `apps/desktop/src/i18n/en.ts:1770-1827,2193` |
| Roster rows over JSON-RPC | `tui_gateway/methods_profiles.py:22-33,190-255` |
| `profiles.list` is a slow-lane method | `tui_gateway/server.py:297-305` |
| `profile` on the session RPCs | `tui_gateway/methods_session.py:38-43,163-165,241-243,327-330` |
| A blank profile means the launch profile | `tui_gateway/server.py:1556-1583,1599-1613` |
| `profile_name` echoed on session payloads | `tui_gateway/methods_session.py:157`; `tui_gateway/server.py:1574-1583` |
| Desktop's cross-profile session union (REST only) | `apps/desktop/src/hermes.ts:520-559` |
| `.env` presence is REST-only | `hermes_cli/web_server.py:14498` |

## What is preserved

The rail's shape and grammar: a default↔all toggle pinned left, the coloured
named profiles between, "Manage profiles…" pinned right, and the active profile
popping in its own colour as the "where am I" cue. The `home`/`layers`/`ellipsis`
codicons at their Desktop glyph sizes and code points. The 16px `ProfileGlyph`
and the 20px rail square, their `rounded-[3px]` corners, their
`color-mix(in srgb, colour 22%, transparent)` fill (30% and a 1.5px ring when
active), and their uppercase initial. The rail square's resting `opacity-55`
over the *whole* mark — tint, ring and initial together — popping to full
strength for the active profile (`profile-switcher.tsx:696-698`), while the same
glyph on a session row, a roster line or the picker is never dimmed at all. The
`home` face belonging to the default profile alone: a profile that resolves to
no identity colour still carries its initial, tinted against
`--ui-text-quaternary` and written in the ink it inherits from its container —
`color: color ?? undefined` (`profile-glyph.tsx:21-41`,
`profile-switcher.tsx:704`), which is `LocalContentColor` here. The deterministic hue — same 32-bit
rolling hash, same `hsl(h 68% 58%)` — so a profile is the same colour on both
clients. The default profile having no colour of its own. The toggle and the
squares staying hidden until a second profile exists, while Manage is always
reachable. Alphabetical order for named profiles. `filterSessionsByProfileScope`
including its rule that a row with no profile is a `default` row. `ProfileTag`
on rows in the unified view only. Switching profiles starting fresh there;
re-picking the profile you are already in leaving your session be; toggling the
unified view leaving the active profile alone. The Panel grammar for the roster,
including the Default badge living on the detail rather than the row.

## Mobile adaptation

| Desktop | Android | Reason |
|---|---|---|
| 20px square, 4px gap, hover tooltip | Same 20px glyph inside a 48dp target, `contentDescription` = `Switch to {name}` | Touch floor; touch has no hover |
| Strip collapses past **13** profiles to a pointer dropdown (`profile-switcher.tsx:49,225-238`) | Collapses as soon as the squares stop fitting beside the two pinned pills, into a modal bottom sheet | A phone's budget is width, not count. A dropdown anchored to a 20px square is not a phone control, so the sheet is the phone default rather than the exception |
| Drag-reorder, long-press-recolour, per-square context menu | Not ported | Explicit non-goals for this slice; they map later onto the reorderable-row grammar |
| The condensed `ProfileDropdown` lists named profiles only in its radio group (`profile-switcher.tsx:722-829`) | The picker sheet heads its list with the default profile - the roster's own row, or the canonical `default` row when the rail's last branch would render one - carrying the same home mark and the same `Switch to {name}` sentence as the rail | Desktop keeps that pill on screen beside the trigger at every width, and a pointer reads its tooltip. On a phone the same pill is a default-to-all toggle whose face reads the *scope*, so from the unified view the one route home wears a `layers` mark and a reader who collapsed the strip cannot find it. Desktop heads a fleet group's list with `[group.defaultAgent, ...group.named]` for exactly that reason (`:808-824`), which is the shape the sheet takes |
| `+` create and import buttons in the strip | Not ported | Profile create/import is an explicit non-goal |
| Manage overlay is a card over the app | One full-screen destination with a back affordance; system back leaves it | Viewport space, and this app's route-overlay rule |
| Panel list beside detail on a wide card | List above detail, list height-capped | Desktop's *own* narrow behaviour (`panel.tsx:88-98,126-128`); the phone is always narrow |
| `PanelMeta` label column `5rem` | 76dp label column, plain quiet text | Same treatment at this app's type scale; deliberately not the uppercase `SectionLabel`, which Desktop does not use there |
| `PanelEmpty` carries a codicon | Title + description, no icon | This app's `EmptyState` is "centered, no icon pile, no card" (`DESIGN.md`), already the established grammar |
| Roster count in `PanelHeader` subtitle | The overlay header's subtitle | Same place, one header |
| Per-profile Electron backend pool (`store/profile.ts:303`) | The `profile` parameter on the session RPCs | Electron-only; the parameter is the portable equivalent |
| Cross-profile union via `GET /api/profiles/sessions?profile=all` | A bounded `session.list` fan-out: the launch profile plus each named profile | The JSON-RPC lane has no twin for that REST route. Rows land in the backend-authoritative cache, which merges and never drops |
| Session rows carry `profile` (`/api/profiles/sessions`) | Rows out of a named profile's leg are stamped with the profile that was asked for, except any row the launch leg already answered with, and nothing at all when a requested launch leg failed | `session.list`'s compact rows carry no profile at the pin (`methods_session.py:267-282`). The launch-profile leg is left unstamped, which is the `default` bucket by the filter's own rule (`profile-scope.ts:12`); a profile a `session.info` event named authoritatively is never taken away by a later listing. A profile the Gateway cannot resolve is not an error there — `_profile_home` answers None and `_profile_db` hands back the launch handle (`server.py:1476-1491,1519-1533`) — so the named leg can return the launch profile's own rows, and the fan-out asks the launch profile first precisely so those rows can be left alone |
| Per-profile project catalog (its backend resolves `projects.tree` under that profile's home) | The catalog is the launch profile's, and the Project grouping says so in every scope that is not it: the unified view keeps the catalog under that line, a named scope hides it and names the way back | `projects.tree` and `projects.project_sessions` take no `profile` and resolve through the Gateway's own home (`tui_gateway/methods_config.py:108-132,135`). Silently showing one profile's projects while every profile is in view reads as "these are all of them", and showing them under another profile's scope reads as that profile's |
| Roster is `$profiles`, a renderer atom | `ProfileRosterCache`, with the same epoch guard | Same invariant, this app's authority model |
| `plug` pill beside Manage deep-linking to the Gateways page while only one connection exists (`profile-switcher.tsx:334-341`) | Not ported | Gateway identity is a separate surface here: PR #76 owns the connections registry and its switcher at the sidebar head. A second route to it from the foot would give this app two answers to "where do I change Gateway" |
| — | One `profiles.list` answer replaces the roster; a failed one keeps the last good | `profiles.list` enumerates every profile and emits every field of each row (`methods_profiles.py:203-255`), so layering fields could only resurrect a model, colour or display name the host cleared. The "merge, never clobber" rule is carried by the failure path and the epoch guard, which is where it is actually load-bearing |

## Deviation ledger

**`profiles.list` is asked on a connection edge, not on window focus.**
Desktop re-pulls the rail whenever the window regains focus or visibility
(`use-profile-rail-refresh-on-active.ts`, wired at `profile-switcher.tsx:179`).
*Reason:* mobile lifecycle. This app already treats a Gateway connection edge as
its refresh trigger, and `profiles.list` is a slow-lane call; putting it on every
foreground would spend seconds of a cold backend's time for a roster that
changes rarely.

**The `.env` pill is implemented and dark at this pin.**
Desktop's roster shows `.env` from `profile.has_env` (`app/profiles/index.tsx:237`),
which only the REST route serves (`hermes_cli/web_server.py:14498`);
`profiles.list` never sends it (`methods_profiles.py:205-249`).
*Reason:* the field is parsed and rendered when a Gateway offers it, so the shape
stays covered by tests, but at the pinned Gateway it never appears. Reading the
roster over REST instead would have made the surface depend on a route only one
of this app's two connection legs reaches.

**One `ui_meta` key is read; the rest is retained by the server and ignored.**
The pinned backend fixes no `ui_meta` vocabulary — it stores whatever
`profile.yaml` holds (`methods_profiles.py:221-236`) — and Desktop's rail colour
is a local pick, not `ui_meta`.
*Reason:* a server-offered `ui_meta.color` is honoured because a roster served to
several clients should paint the same; anything else would be an invented
contract. With no `ui_meta.color`, the deterministic hue answers.

**The rail is absent until a Gateway answers, and stays after that.**
Desktop always renders Manage, because its renderer only runs inside a connected
app.
*Reason:* this app can be looking at no Gateway at all, and before the first
answer a rail has nothing to switch between and nothing to manage. Once one
`profiles.list` has answered the rail stays — including across a reconnect, and
including an empty roster, because it is the only way out of a profile scope and
the only route to the roster. Only losing the Gateway outright drops it.

A scope that is not the Gateway's own profile keeps the rail whether or not the
roster ever answers. The scope is persisted and `profiles.list` is a slow-lane
call an older or refusing Gateway may never answer; a sidebar scoped to a
profile with no control to leave it is a trap Desktop cannot have, because its
rail only exists inside a connected app. With no roster to name the default
profile's label, that one control is named canonically — `Switch to default`.

**A persisted scope the Gateway does not have falls back rather than being sent.**
Desktop's scope follows a live gateway it just opened, so it cannot name a
profile that does not exist.
*Reason:* this app persists the scope, and the Gateway does not refuse an
unresolvable name — `_profile_home` answers None and `_profile_db` hands back the
launch handle (`tui_gateway/server.py:1556-1571,1599-1613`), so a stale scope
would quietly list the launch profile's rows under a name that is gone. Once
`profiles.list` has actually answered, a scope it does not contain returns to the
Gateway's own profile with `That profile is no longer available.`; a roster that
has not answered leaves the scope alone, because losing it there would take away
the rail's way out. In the window before that, a listing made under the stale
scope stamps the launch profile's rows with the missing name, and a later
listing does not take that stamp back; those rows stay visible in the All view
and each one is corrected when it is opened, because `_response_profile_name`
(`server.py:1494-1503`) reports the profile the Gateway really acted under on
`session.info`-shaped payloads (`methods_session.py:157`, `server.py:5688,
8462`) — not on `session.list` rows. A cold restart is clean, since the
corrected scope is persisted. Tracked as a follow-up.

**Leaving a profile leaves the project drill-in too.**
Desktop's project catalog is per-profile because `projects.tree` resolves through
that profile's `HERMES_HOME`.
*Reason:* keeping another profile's project open while scoped elsewhere would
show a membership list that cannot contain a visible row. The switch exits to the
profile's own session list, which is where Desktop lands too.

**The composer queue's third key component is appended, not always present.**
`ComposerQueueScope.forConnectionProfile` adds a `hermes:<name>` component only
when a named profile is active.
*Reason:* an install that has never used the rail keeps the queue it already has,
while text parked under one Hermes profile can never be presented under another.

**`Profile: {name}`, not `Owned by {profile}`.**
Issue #61's acceptance names the *i18n key* (`sidebar.row.ownedByProfile`); the
value at the pin is `Profile: ${profile}` (`i18n/en.ts:2175`), asserted verbatim
by Desktop's own `profile-tag.test.tsx:27,34,47`.
*Reason:* the rule is copy verbatim from the pin, and the pinned string wins over
a key name transcribed into the issue.

## Omissions

Not deviations — things this slice does not ship, stated rather than hidden.

- **Profile create, rename, delete, export/import, and the SOUL.md editor.**
  Non-goals; the roster is read-only.
- **Avatars.** `has_avatar` is parsed; `profiles.get_asset` is not called.
- **Drag-reorder and long-press-recolour.** Non-goals.
- **`POST /api/profiles/active`.** It sets the CLI sticky default and does not
  retarget a running Gateway (`hermes_cli/web_routers/profiles.py:922`), so it is
  deliberately never called.
- **`session.most_recent`.** This client does not call it; the sidebar chooses
  the newest row it already holds. The `profile` parameter it accepts
  (`methods_session.py:241-243`) is therefore untouched here.
- **Cron and messaging slices.** Desktop scopes those by profile too; this app
  ships neither.
- **The default profile's rows in the unified view when the Gateway launched
  under a named profile.** The fan-out asks for the launch profile (no
  parameter) plus every named profile; it never sends `profile: "default"`,
  because doing so would change the request a single-profile install makes
  today. On a Gateway launched as `default` — every Remote Gateway install, and
  managed SSH without a remote profile — this covers everything. On a
  named-launch Gateway the same fan-out also asks twice for that one profile
  (once unnamed, once named), which is a wasted request rather than a wrong
  answer: nothing here knows the launch profile's name, because `session.list`
  does not report it.
- **A launch profile that is also a roster profile keeps no stamp in the unified
  view.** The fan-out asks the launch profile first with no parameter, so its
  rows are left unstamped and land in the `default` bucket — even on a Gateway
  launched under a profile the roster also lists by name. The rows are all
  present and the filter agrees with itself; only the per-row tag reads
  `Profile: default` rather than that profile's name. Naming it would take
  either a `profile_name` on `session.list`'s compact rows, which the pin does
  not send (`methods_session.py:267-282`), or a second request purely to learn
  the launch profile's own name.
- **Profile-scoped projects.** The project catalog stays the launch profile's;
  see the adaptation table. The Project grouping states that outside the default
  scope rather than listing another profile's projects.
- **`Select a profile to view its details.`** (`i18n/en.ts:1779`). Desktop
  renders it only when `selected` is null while the roster has rows
  (`app/profiles/index.tsx:156-160`), and its own selection resolves to
  `profiles.find(…) ?? profiles[0] ?? null` (`:74-80`) — so on a roster with
  rows it cannot be null, and on an empty roster the `No profiles yet.` branch
  answers first (`:109-119`). The string is unreachable upstream, and this port
  does not invent a state to reach it.
- **Rendered visual capture.** See below.

## Divergences

Classified for `scripts/check-parity-evidence.py`; the ledger and omissions
above carry the argument.

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| `profiles.list` re-pulls on window focus or visibility (`profile-switcher.tsx:179`) | mobile-adaptation | Asked on a connection edge | Mobile lifecycle: `profiles.list` is a slow-lane call, and putting it on every foreground would spend seconds of a cold backend's time on a roster that changes rarely |
| `DropdownMenu` rail trigger and roster page | mobile-adaptation | Rail plus a bottom sheet, 48 dp rows | Pointer menus are brittle on a phone; order and checkmark are unchanged |
| The condensed `ProfileDropdown` radio group lists named profiles only (`profile-switcher.tsx:722-829`) | mobile-adaptation | The sheet pins the default profile at its head, home mark and all, selected while the scope is default | Desktop's own fleet groups head a gateway's list with its default agent (`profile-switcher.tsx:808-824`). The pill the strip collapsed away from reads the scope rather than the action, so in the unified view the only route back to the default profile wears a `layers` glyph with no tooltip a touch reader can hover; the head row is the visible affordance, and its presence rule is the rail's own so the sheet never offers a switch the rail would not |
| Manage is always rendered, because the renderer only runs inside a connected app | mobile-adaptation | The rail is absent until a Gateway answers, and stays after that | This app can be looking at no Gateway at all, and before the first answer a rail has nothing to switch between; once one `profiles.list` has answered it stays, because it is the only way out of a profile scope |
| The `.env` pill reads `profile.has_env`, served only by the REST route (`hermes_cli/web_server.py:14498`) | mobile-adaptation | Parsed and rendered when a Gateway offers it; dark at this pin | Reading the roster over REST would tie the surface to a route only one of this app's two connection legs reaches |
| Scope follows a live gateway, so it cannot name a profile that does not exist | drift | A stale persisted scope stamps the launch profile's rows with the missing name until each is opened | #81 |
| Profile create, rename, delete, export/import, and the SOUL.md editor | omission | Absent | non-goal: the roster is read-only |
| Avatars (`profiles.get_asset`) | omission | `has_avatar` is parsed; the asset is never fetched | non-goal: a read-only roster does not fetch profile assets |
| Drag-reorder and long-press-recolour | omission | Absent | non-goal: the roster is read-only, so there is no order or colour of its own to change |
| `POST /api/profiles/active` | omission | Never called | non-goal: it sets the CLI sticky default and does not retarget a running Gateway (`hermes_cli/web_routers/profiles.py:922`) |
| Cron and messaging slices, scoped by profile | omission | Absent | non-goal: this app ships neither |
| `Select a profile to view its details.` (`i18n/en.ts:1779`) | omission | Never rendered | non-goal: the string is unreachable upstream (`app/profiles/index.tsx:74-80,109-119,156-160`), and this port does not invent a state to reach it |

## Visual report

- pending: #43

`capture-android-reference.py` needs an attached device or emulator and
`capture-desktop-reference.mjs` needs a disposable pinned Desktop dev renderer
with CDP. Neither is available in this environment, so the capture is recorded
as **missing**, not fabricated, and belongs to the device-QA slice (issue #43).

What this slice ships instead: `@Preview` composables in phone light and dark for
the rail (default, unified, collapsed) and the roster (populated, empty), the
same in-source affordance `ChatScreen.kt` and `RelayScreen.kt` use. Every
profile, path and session in them is invented; nothing in this repo corresponds
to a real host, profile or person.
