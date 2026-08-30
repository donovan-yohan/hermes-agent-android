# Gateway connections registry and session-rail switcher parity

## Pin and source contract

Desktop authority is `NousResearch/hermes-agent` at
`f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`, read through
`git show <sha>:<path>` on a read-only checkout. Every line number below is at
that SHA.

| Contract | Desktop source | Android port |
|---|---|---|
| Connection mode cards | `apps/desktop/src/app/settings/gateway-settings.tsx:1044-1084`, `ModeCard` at `:88-135`, emphasis from `lib/selectable-card.ts:22-31` | `GATEWAY_MODE_CARDS` in `ui/gateway/GatewayScreen.kt`, rendered by `ModeCard` / `ModeCardGrid` / `selectableCardModifier` in `ui/common/Primitives.kt` |
| Mode card order and glyphs | `gateway-settings.tsx:1049-1082` — Local gateway (`Monitor`), Hermes Cloud (`Cloud`), Remote gateway (`Globe`), Connect via SSH (`Terminal`) | The same four, in the same order, with Codicon `vm`/`cloud`/`globe`/`terminal` |
| Mode grid breakpoints | `gateway-settings.tsx:1048` — `grid-cols-1 sm:grid-cols-2 min-[72rem]:grid-cols-4`, both **viewport** queries | `modeCardColumnsFor`: 1 column, 2 at 600dp, 4 at 720dp, off the window width |
| Mode copy | `i18n/en.ts:776-783`, `:865-868` | `GatewayModeCopy` in `ui/gateway/ConnectionsCopy.kt`, one constant per line, cited |
| Registry kind chooser | `connections-registry.tsx:648-671` — plain `Button`s, `grid-cols-2 @2xl:grid-cols-4`, a **container** query | `CONNECTION_KIND_CHOICES` + `ChoiceButton`, 2 columns stepping to 4 at a 672dp editor |
| Registry section | `apps/desktop/src/app/settings/connections-registry.tsx:221-888` | `ui/gateway/ConnectionsSection.kt` |
| Where it lives | `apps/desktop/src/app/settings/gateway-settings.tsx:1499-1502` — foot of the Gateways page, below the connection controls | Same page, below the same controls, inside whichever route's scroll is showing |
| Kind glyphs (`KIND_ICONS`) | `connections-registry.tsx:26-31` — `cloud`/`local`/`remote`/`ssh` → Cloud/Monitor/Globe/Terminal | `ConnectionKind.glyph`: Remote → Codicon `globe`, Ssh → Codicon `terminal`, Local → Codicon `vm`, this family's monitor (Codicons 0.0.45 ships no `device-desktop`). Cloud has no `ConnectionKind`; the chooser still renders it, disabled |
| Row grammar | `settings/primitives.tsx:108-155` (`ListRow`), `:27-29` (`Pill` over `components/ui/badge.tsx:7-21`), `components/ui/empty-state.tsx:7-23` | `ui/common/SettingsPrimitives.kt` — `SettingsListRow`, `Pill`, plus the existing `EmptyState` |
| Row content | `connections-registry.tsx:578-641` — kind glyph, label, Current/Primary pills, `kind · endpoint` description | Kind glyph, label, `Current` pill, `kind · endpoint · auth mode`, all through `redact()` |
| Row actions | `connections-registry.tsx:586-625` — inline, not an overflow menu, in the order `Test` (always), `Make primary` (when not primary), `Edit`, `Remove` (both icon-only, both hidden for `local`); copy at `en.ts:717`, `:718`, `:722`, `:723` | `ConnectionRowActions` — same four in the same order, wrapping instead of overflowing, with a `Switch` action ahead of them. `Test` and `Make primary` are rendered disabled behind a `Coming soon` pill; `Edit`/`Remove` are shown on every kind |
| Duplicate rule | `connections-registry.tsx:89-168` (`normalizeGatewayUrl`, `sshCompositeKey`, `findDuplicateConnection`) | `data/connections/ConnectionRegistry.kt`, same three functions, plus `localGatewayKey`: Local rows collide on the normalized loopback address (`127.0.0.1`, `localhost` and `[::1]` on one port are one server), not on being local at all |
| Removal | `connections-registry.tsx:866-874` (`ConfirmDialog`, destructive) | `ui/common/ConfirmSheet` — a bottom sheet with the same title, description and destructive confirm |
| Display order | `lib/connection-display.ts:11-23` (`Intl.Collator`, numeric, base sensitivity) | `sortConnectionsForDisplay`, numeric-aware and case-insensitive, id breaking ties |
| Search threshold | `lib/connection-display.ts:3` (`CONNECTION_SEARCH_THRESHOLD = 8`) | `CONNECTION_SEARCH_THRESHOLD = 8`, in both the settings list and the rail sheet |
| Search matching | `lib/connection-display.ts:29-58` — NFKD, marks stripped, every needle must match | `connectionMatchesQuery`, same normalisation |
| Endpoint string | `lib/connection-display.ts:61-75` (`connectionEndpoint`) | `SavedConnection.endpoint` |
| Rail switcher | `apps/desktop/src/app/chat/sidebar/connection-switcher.tsx:40-322` | `ui/sessions/ConnectionSwitcher.kt` |
| Hidden for one source | `connection-switcher.tsx:118-120` | `ConnectionsUiState.switchable`; the rail renders no chrome |
| Radio group + active check | `connection-switcher.tsx:205-233` (`DropdownMenuRadioGroup`/`RadioItem`) | 48dp rows with `Role.RadioButton`, selected semantics, and a Codicon `check` |
| Trailing manage item | `connection-switcher.tsx:234-237` + `i18n/en.ts:1772` | A hairline, then a `Manage gateways…` row that opens Gateways |
| Pending state | `connection-switcher.tsx:133,272` (`aria-busy`, spinner) | `stateDescription = "Connecting…"` on the rail trigger, and on the registry row the switch control itself reads `Connecting…` from the same `ConnectionsCopy.CONNECTING` constant. Keyed on **pending, not on not-current**: `ConnectionSwitchController.select` writes the active marker *before* it waits for the dial, so the target row is `Current` and still connecting for the whole settle window |
| Switch semantics | `store/connections.ts:153-225` (`selectConnection`) and `store/gateway-switch.ts:47-96` (`wipeSessionListsForGatewaySwitch`) | `data/connections/ConnectionSwitchController.kt` plus `SessionCache.resetForEndpointSwitch()` |
| A route that cannot come up unattended is not waited on (`store/connections.ts:186-190` throws rather than hanging when the target never becomes active) | `SavedConnection.restorable` — one rule, read by `ConnectionSwitchController.awaitSettle` for whether to hold a pending badge and by the Gateways row for whether to explain that nothing dialled | Restating "which kinds self-restore" as a `kind == Ssh` check in the UI is how the two copies drift the first time a kind is added. Only the *sentence* is per-kind, because only the reason is. |
| Switch is re-entrant-safe | `store/connections.ts:159-161` — a repeat `selectConnection` for the target already in flight returns before it touches anything | `ConnectionsViewModel.select` drops the tap while `switchJob` is active, and `ConnectionSwitchController.select` serialises on one mutex |
| Copy | `i18n/en.ts:703-764`, `:1770`, `:1772` | `ui/gateway/ConnectionsCopy.kt`, one constant per line, cited |

## State classification

| Kind of state | Home | Rule |
|---|---|---|
| Saved connections and which is active | `HermesPreferences` (`connections.v1.saved`, `connections.v1.activeId`) | Client-local authority. No Gateway contract exists for it; it is never sent anywhere. |
| The active row's endpoint fields | The same rows | `hostProfile`, `remoteGatewayProfile` and `gatewayConnectionMode` are **projections** of the active row, so there is one copy and nothing can drift. |
| A Remote row's sign-in | `AndroidGatewayTokenStore`, one Keystore-encrypted file per row id, under `noBackupFilesDir` | Never in preferences, never in a row, never in a log. The blob names the Gateway that minted it and is **refused** for any other — refusal is the guarantee, and a read never erases, so a mistyped URL is recoverable. Erasing is deliberate: removing the row, or re-addressing it through the editor. Erasure is addressable by row id alone, so a row whose URL was blanked can still be cleaned up. |
| A Local row's Hermes session token | The same store, the same slot machinery | One kind of credential per row: a session token is never returned as a sign-in, or the reverse. Same binding, same refusal, same erase-by-row-id. Desktop has no equivalent because its local connection is a runtime its own process manages and needs no credential from the user. |
| An SSH row's password/passphrase/key | Nowhere | Unchanged: in-memory for one attempt, zeroed after. There is no per-row SSH secret because there is no SSH secret on disk at all. |
| Sessions and transcripts | `SessionCache` | Merge, never clobber — except a connection switch, which clears wholesale because the next backend is a different machine. `resetForEndpointSwitch()` is `internal` and only `ConnectionSwitchController` calls it. |
| Which session is open, search text, project drill-in | `ChatViewModel` | UI-only. Dropped whenever the composer scope changes — address *or* profile, since two SSH remote profiles on one host are two Hermes homes with two session histories. |
| Private draft text | `SessionDraftStore` | Keyed by durable session id only, and two gateways can recycle one. Cleared on a real endpoint *transition* — picking another connection, re-addressing the active one through the editor's discrete Save, or removing the connection this device is on — and never by the Gateways route form, which persists per keystroke and cannot tell a finished address from a half-typed one. |
| Editor form state, search text, sheet open | `ConnectionsViewModel` / `rememberSaveable` | UI-only. Never persisted. |

## Mobile adaptation ledger

| Desktop | Android | Reason |
|---|---|---|
| Mode grid pinned above the panel in the page's own scroll | The chooser travels into whichever route's scroll is showing | Desktop's page scrolls as one, and so does this one. It used to be a pinned header, which a single segmented control could afford; four cards one-per-row on a phone would have left the route's own form a sliver of what was left. |
| `ModeCard` hint: hover `Tip` on a `HelpCircle` (`gateway-settings.tsx:113-124`) | The same glyph as a button that reveals the same sentence under the description | Touch has no hover. Revealing beats hiding — the sentence lands on screen rather than one undiscoverable gesture away — and the revealer sits in a 48dp touch area it does not paint, so the hit box clears the platform floor without the 14sp glyph growing. Every card reserves that header height so the four do not sit at two different heights in one column. |
| `DropdownMenu` + `DropdownMenuRadioGroup` anchored to the rail trigger | `ModalBottomSheet` with 48dp radio rows | Pointer menus are brittle on a phone; the sheet is this app's established equivalent (`ComposerAddSheet`, `ModelControl`). Order, checkmark and search threshold are unchanged. |
| `ConfirmDialog` | `ConfirmSheet` bottom sheet | Same reason; same title, description, destructive confirm and cancel. |
| `ListRow` with the control beside the label above `@2xl` | Always stacked | This *is* Desktop's own narrow rendering — the query is on the row's pane width, and a phone is always below the breakpoint. |
| Icon-only ghost `Pencil`/`Trash2` with `aria-label` "Edit"/"Remove" | Same glyphs in 48dp targets, `contentDescription` "Edit ⟨label⟩" / "Remove ⟨label⟩" | Touch floor, and a list of rows needs the label to tell two identical buttons apart. |
| Hover `title` tooltip carrying label + endpoint (`connection-display.ts:78-82`) | The endpoint is rendered under the label in the sheet, and in the row description in settings | Touch has no hover; the information is shown rather than hidden. |
| Row description `kind · endpoint` | `kind · endpoint · auth mode` | The issue's acceptance list asks for the auth mode on the row; it is a mode name, never a secret. |
| `EmptyState` with a title only | Title plus one next-action line | Product-copy rule: state the outcome *and* the next action. |
| `Test` (`en.ts:723`), `Make primary` (`en.ts:722`) | Present on every row, disabled, each behind a `Coming soon` pill | **Omission → coming-soon.** Still not implemented, but no longer invisible: an absent control reads as a surface that never had the feature, so a person goes looking for it elsewhere. `Test` needs a route-independent reachability probe this app does not have; `Make primary` needs the launch-mode registry field (`launchMode`, `registry.primary`) Android does not persist, and with exactly one active connection `primary` has nothing to distinguish it from `Current`. |
| `Make primary` is hidden on the row that already is primary (`connections-registry.tsx:601`) | Shown on every row | **Divergence, classified.** The condition has nothing to test against here: Android persists no `registry.primary` / `launchMode` field, so no row is ever the one Desktop would hide it on. Rendering it uniformly disabled is the honest reading of "this app has no primary"; hiding it on an arbitrary row would invent the concept. Revisit if launch mode is ever ported. |
| No equivalent — Desktop's SSH connection is main-process-owned with stored credentials, so it never lands active-but-undialled | A row that cannot come up unattended says so and names `Connect`, but only while the route pane above is actually offering that button (neither `Connected` nor `Connecting`, per `SshScreen.kt`'s status `when`) | Gating on the button's own condition is what keeps the sentence from becoming stale advice about a problem that is already over. |
| `Update all instances`, launch-mode toggle, extra-header editor, plain-text-keyring consent | Absent | Omissions, not adaptations, and not row actions — they are page-level controls. `Update all` has no Android equivalent; header editing and the keyring consent are explicit non-goals of the issue. Recorded so the port stays honestly incomplete. |
| No way to switch connection from the registry at all — Desktop switches from the sidebar radio group (`connection-switcher.tsx:212-227`), and `stagedNote` says so in as many words: "Switch gateways from Sessions" (`en.ts:706`) | A `Switch` action on every non-active row, ahead of Desktop's four, calling the same `selectConnection` semantics | **Adaptation.** Desktop's Settings is a window beside a sidebar that is always there; a phone's Gateways screen is a destination you navigate to, and the person is already standing on it when they add or repair a gateway. Sending them back to Sessions to start using what they just saved is a round trip Desktop never has to make. The verb is Desktop's own (`en.ts:706`), so the two surfaces name one act. It is a discrete target rather than a whole-row tap because a switch drops the live connection, this endpoint's cached sessions and its unsent drafts — not what a thumb reaching for Edit should land on. |
| Saving a new connection leaves it staged: `save()` calls `bridge.save`, republishes the registry and closes the editor, and nothing else (`connections-registry.tsx:277-370`). The component only *reads* `$activeConnectionId` (`:224`, `:571`) — it never calls `selectConnection`, and `setPrimary` (`:400`) sets the launch default, not the active row | Same. `ConnectionsViewModel.saveEditor` writes the row; only a re-address of the row that is *already* active redials | **Parity, not a gap.** An emulator pass read "a freshly saved row is neither activated nor dialled" as a defect; Desktop behaves identically, and the string Desktop calls `stagedNote` is named for exactly this. Auto-activating on save would also be destructive: adding a gateway would tear down the connection you are on, drop its cached sessions and discard its unsent drafts. What was genuinely missing is that the staged row had no way to *become* active from this screen — which is what the `Switch` action above fixes, one tap, on the row. |
| `ListRow`'s body is not a target: a plain grid `div` with title/description/hint/`below` and an `action` slot, no `onClick`, no `role`, no `tabIndex` (`settings/primitives.tsx:108-155`) | Same — `SettingsListRow` has no click handler, so a row tap and a long-press do nothing | **Parity, deliberate.** The same emulator pass read the inert row body as a defect. It is Desktop's treatment, and the right one here: the destructive act on this row is the switch, and a whole-row target is what a thumb reaching for Edit or Remove hits by accident. Every action on the row is an explicit, named target. |
| `stagedNote`: "Switch gateways from Sessions." (`en.ts:706`) | "Switch gateways here or from Sessions." | **Adaptation**, forced by the row above. Naming Sessions as the only route would now be false, and would point at the longer of the two. The rest of the sentence is unchanged. |
| A pending switch is reported on the rail trigger only; the radio menu closes on the click, so no row is on screen while its own switch is in flight (`connection-switcher.tsx:133,272`) | The registry row itself reads `Connecting…` and is disarmed, as is every other row's switch | **Adaptation.** This list does not close when you tap it, so the row that is moving has to say so itself. The word is the same constant the rail uses. |
| `Edit`/`Remove` are hidden on the `local` kind (`connections-registry.tsx:604`) | Shown on every kind, including Local | **Drift, deliberate.** Desktop's Local connection is the runtime its own app manages, so there is nothing for a person to edit and removing it is meaningless. Android's Local row is a Hermes the person runs in Termux: its address and its session token are theirs to change, and the row is theirs to delete. Already recorded in the Local-kind row above. |
| `local` kind: "The Hermes runtime managed by this app." (`en.ts:733`, `:737`), `Monitor` glyph (`connections-registry.tsx:26-31`), at most one ever (`connections-registry.tsx:132-134`, `en.ts:753`, `:757`) | `ConnectionKind.Local`, label `Local`, description "A Hermes running on this device.", `HermesIcon.Monitor`, and one row per loopback address rather than one row full stop. The Gateways form — the kind entry, the prefilled address, the **Session token** field and the limitation line beside Save — lands with S-A2 ([#96](https://github.com/donovan-yohan/hermes-agent-android/pull/96)); the transport, the token slot and the copy constants are already here | The word is Desktop's; the ownership is not. Desktop's local connection is the runtime its own app manages, so there can only be one and it needs no credential. Android's is a Hermes the person runs in Termux on the same phone, which this app only connects to: the description has to say whose it is, and the count rule follows the address because two Termux servers on two ports are two Gateways. On loopback there is no TLS, no sign-in and no host key, so the static Hermes session token is the whole boundary and the form has to ask for it. Setup lives in [the Termux local Gateway guide](../guides/termux-local-gateway.md). |
| `cloud` kind | Absent | Non-goal. There is no Android Hermes Cloud sign-in. |
| `intro` names Cloud; `stagedNote` names profiles and cron jobs | Both shortened to what Android ships | Copy must be true on the device it is on. Source lines still cited. |
| Kind is fixed once created (`connections-registry.tsx:649-654`) | Same in the list editor. The route control at the top of Gateways still changes the **active** row's kind | That control predates this slice and is the active connection's own form. The Remote and SSH endpoint slots persist per row, so flipping between those two loses nothing. Flipping *away from* Local is not free: `GatewaySettingsViewModel.setMode` erases that row's session token, because a credential minted for a server the row no longer names has nothing left to authenticate. That branch lands with S-A2 ([#96](https://github.com/donovan-yohan/hermes-agent-android/pull/96)). |
| Registry may be empty (`empty: 'No connections registered yet.'`) | Always at least one row; removing the last is refused | Android has exactly one active connection and no "disconnected from everything" state to fall back to. The empty state is still implemented and reachable in tests. |

### Minor drift, recorded rather than argued

| Desktop | Android | Note |
|---|---|---|
| Unread markers survive a source switch — the transient paint layer is wiped but `session-unread.ts` keeps durable per-session watermarks that repaint (`gateway-switch.ts:70-76`) | Unread is a field on the cached row, and the cache is cleared, so unread state goes with it | Android has no durable unread store to survive the wipe. A session that was unread on gateway A is not marked unread when you come back. |
| No-results text carries `role="status"` (`connection-switcher.tsx:216-221`) | Plain `Text` | Not yet given a live-region role; the sheet's list is the thing being searched and the count change is visible. |
| A separator sits between the search field and the radio group (`connection-switcher.tsx:202`) | No separator | The sheet's padding already separates them; the hairline sits below the list instead, before `Manage gateways…`. |
| The searchable list is capped at `h-48` and scrolls (`connection-switcher.tsx:208`) | Capped at 320dp | Same intent, phone-scaled; the cap is a `LazyColumn` `heightIn(max = …)` rather than a fixed height. |

## Deviations that are not Desktop's to have

- **Per-connection Keystore slot.** Desktop keeps secrets in the OS keyring
  through its main process. Android names one encrypted file per row id and
  erases it with the row. An install upgrading from the single-connection build
  adopts its old URL-named file exactly once, by rename, so nobody is silently
  signed out and no second row can inherit it.
- **The SSH fingerprint rule is per row.** `HostProfile.withDestination` is what
  enforces it, so a row that changes host or port drops its accepted
  fingerprint and a row that only renames the user keeps it. Desktop has no
  equivalent because its SSH host key handling is in the main process.
- **The per-install Gateway ownership id stays per install.** The issue lists it
  among per-row fields; it is not one. It namespaces this app's remote processes
  on a host and one install has exactly one, so moving it per row would change
  what the SSH ownership lock means.
- **A downgrade is refused, not overwritten.** The stored registry document is
  versioned. A build that cannot read it shows an empty registry *and refuses
  every write*, because answering "no connections" is that build's ignorance
  rather than the truth, and reseeding over it would make the downgrade
  permanent. The consequence, stated plainly: after installing an older build,
  saved connections are invisible and unmodifiable until a build that
  understands the document runs again — and the Connections section says so, in
  one line, rather than letting a save appear to succeed. The same refusal
  covers a document that cannot be parsed at all, which is the corrupt case:
  nothing is overwritten, and nothing can be saved, until it is replaced.
- **Managed SSH is not dialled by a switch.** Its credential is built in the UI
  and dies with the connection, so there is nothing to restore without asking.
  Selecting an SSH row lands disconnected on that row, and Gateways is where the
  connection is made.

## Executable evidence

- `ConnectionRegistryTest` — the dedupe rules, the display order, the search
  normalisation, the endpoint string, the codec's closed/fail-safe decoding, and
  the per-row fingerprint rule.
- `ConnectionSwitchControllerTest` — on virtual time: the teardown happens
  through the existing disconnect, the cache is cleared *before* the active
  marker moves, the pending marker is held and released, and an unknown or
  already-active row is a no-op.
- `HermesPreferencesTest` — the single-connection keys become row one, active,
  with every field intact and the legacy keys removed; the single-connection
  readers are projections of the active row; an edit writes the active row
  rather than a second copy.
- `GatewayTokenSlotTest` — two rows never share a slot; removing one erases only
  its own credential and zeroes the bytes before unlinking (proven through a
  hard link to the same inode); a row whose URL was blanked is still erasable by
  its id alone; the pre-registry file is adopted exactly once *and rebound* to
  the host it came from; a credential whose row points at another gateway is
  refused **and left on disk**, so fixing a typo restores it; a row-named blob
  naming no host is refused on read and removed only by the deliberate path;
  the row's file name is pinned to
  `SHA-256("connection" + U+0000 + id)`; and end to end, a re-addressed row
  presents no bearer minted for the gateway it left — `ticket()` asks for a
  sign-in instead.
- `ChatEndpointSwitchTest` — a changed endpoint drops the open session, the
  search and the project drill-in, then lands on the new endpoint's most
  recently active session; two SSH remote profiles on one host are treated as
  two session histories; and the first bind tears nothing down at startup.
- `ConnectionsViewModelTest` — delete erases the credential and then removes the
  row (including a row whose URL is blank), the last row cannot be removed, an
  unaddressable Remote URL is refused with product copy, a duplicate URL is
  refused inline, re-addressing the active row tears the old endpoint down and
  erases the credential it abandoned, renaming it tears nothing down, and a
  registry this build may not write refuses both a save and a removal in place
  rather than appearing to succeed. On switching: two taps in one flight tear
  the old endpoint down exactly once and leave no pending marker behind,
  re-selecting the active row is a no-op, and activating a Managed SSH row moves
  the marker without spending a single millisecond of virtual time waiting for a
  dial that is not coming.
- `GatewayScreenTest` — the four mode cards in Desktop's order with Desktop's
  glyphs; every description and hint asserted against its `en.ts` line, so a
  copy edit that drifts has to come through the citation; only Remote and SSH
  carry a hint, as only they do on Desktop; Hermes Cloud has no
  `GatewayConnectionMode` at all; every route has exactly one card; and the
  1 / 2 / 4 column mapping at and either side of both breakpoints.
- `ConnectionsSectionTest` — the registry chooser offers Desktop's four kinds in
  Desktop's order, Cloud is unselectable by having no `ConnectionKind`, every
  kind a row can be has a button, and each kind carries Desktop's glyph.
- `ConnectionModeCardsJourneyTest` — rendered: the verbatim heading and all four
  cards, each description on screen, the active route checked and the others
  not, a tap changing the route, Hermes Cloud displayed with its **Coming soon**
  pill and refusing the tap, the hint glyph revealing and re-hiding Desktop's
  tooltip sentence, no revealer on a card Desktop gives no hint, and the column
  count measured at 411dp, 600dp and 840dp.
- `ConnectionKindChooserJourneyTest` — rendered: all four kinds offered on
  create, the editor's kind selected, a choice reported, Cloud disabled behind
  the pill, and Local still offered because this registry has no one-Local rule.
- `HermesIconFontTest` — `Monitor` and `Cloud` resolve in the shipped Codicons
  0.0.45 font, so neither is a blank box on a device.
- `SecretRedactionTest` — URL userinfo never reaches a screen or a screen
  reader, and an ordinary URL is left alone.
- `HermesPreferencesTest` — a registry document this build cannot read is never
  written over.
- `ConnectionsJourneyTest`, `ConnectionSwitcherJourneyTest` — the rendered
  list with its `Current` marker and redacted summary, the empty state, the
  add/edit flows (including the kind picker disappearing on edit), the
  destructive confirm sheet, the inline duplicate error, and — on the rail —
  no chrome for one connection, the sheet's rows and their selected state, the
  eight-connection search threshold with its no-matches copy, the pending
  `Connecting…` state, and `Manage gateways…`. On switching from the registry:
  the active row offers no switch to itself and the other row does, taking it
  moves the `Current` marker and the offer with it; a row mid-switch reads
  `Connecting…` — driven by the real `ConnectionSwitchController`, so the
  assertion sees the genuine window in which the target row is `Current` *and*
  still dialling — and neither it nor any sibling will take a second tap; `Test`
  and `Make primary` render with the shared `ComingSoonAction` primitive's
  pill and its spoken "⟨label⟩. Coming soon"; and activating a Managed SSH row — driven through the
  whole `GatewayScreen`, because the claim is about where the person lands —
  leaves it `Current`, states why nothing dialled, and puts the Managed SSH
  pane's own `Connect` on screen above the list — and that sentence is gone
  once the connection is up and the pane offers `Disconnect` instead.

Rendered visual capture against a Desktop dev renderer has **not** been done for
this slice; the surface is evidenced by the Compose journeys and this ledger.
That is an omission, recorded here rather than implied away.

The port skill's `capture-desktop-reference.mjs` was considered and is not
usable here. It attaches over CDP to an **already running** Electron renderer
(`--port`, `--match`) rather than rendering from source, so a reference shot of
this surface would need the Desktop app built and launched from a checkout at
the pinned SHA, on a display, with a registry holding at least two connections
so the row actions and the `Current`/`Primary` pills have anything to show. None
of that is available in this environment, and the pinned upstream checkout is
read-only, so the Desktop side of every claim above is cited to the JSX and the
copy table instead — `connections-registry.tsx:586-625` for the action cluster
and its order, `connection-switcher.tsx:212-227` for where switching actually
lives, and `i18n/en.ts:703-764` for the words.

## Divergences

Classified for `scripts/check-parity-evidence.py`; the ledgers above carry the
argument.

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| `DropdownMenu` + `DropdownMenuRadioGroup` anchored to the rail trigger | mobile-adaptation | `ModalBottomSheet` with 48dp radio rows | Pointer menus are brittle on a phone; order, checkmark and search threshold are unchanged |
| `ConfirmDialog` | mobile-adaptation | `ConfirmSheet` | Same touch reason; same title, description, destructive confirm and cancel |
| Hover `title` tooltip carrying label + endpoint (`connection-display.ts:78-82`) | mobile-adaptation | Endpoint under the label in the sheet, and in the settings row description | Touch has no hover, so the information is shown rather than hidden |
| Icon-only ghost `Pencil`/`Trash2` with `aria-label` | mobile-adaptation | Same glyphs in 48dp targets, `contentDescription` "Edit ⟨label⟩" / "Remove ⟨label⟩" | Touch floor, and a list of rows needs the label to tell two identical buttons apart |
| Searchable list capped at `h-48` (`connection-switcher.tsx:208`) | mobile-adaptation | `heightIn(max = 320.dp)` on a `LazyColumn` | Same intent, phone-scaled |
| `local` is the runtime the app manages, at most one ever (`connections-registry.tsx:132-134`) | mobile-adaptation | A Termux Hermes the person runs; one row per loopback address. The glyph is Desktop's `Monitor` again | The word is Desktop's and the ownership is not; two Termux servers on two ports are two Gateways, so the count rule follows the address. The glyph used to be `device-mobile` here, which changed Desktop's picture to make a point the description already makes — the parity gate calls that drift, so it is back |
| `ModeCard` hint is a hover `Tip` on a `HelpCircle` (`gateway-settings.tsx:113-124`) | mobile-adaptation | The same glyph as a tap-to-reveal button showing the same sentence in the card | Touch has no hover; the sentence is shown rather than hidden, in a 48dp touch area that does not paint over the 14sp glyph |
| Mode grid sits in the page scroll above the panel for the chosen mode (`gateway-settings.tsx:1044-1089`) | mobile-adaptation | The chooser scrolls with whichever route's pane is showing | Viewport space: four cards one-per-row on a phone cannot be a pinned header without crowding out the form they select |
| `sm:grid-cols-2` at 640px, `min-[72rem]:grid-cols-4` at 1152px (`gateway-settings.tsx:1048`) | mobile-adaptation | 2 columns at 600dp, 4 at 720dp | 600dp is Android's own compact/medium boundary and the nearest standard line to Desktop's; 720dp is already this app's wide breakpoint (`ui/chat/ChatScreen.kt:95-97`), and Desktop's 1152px window is also carrying a sidebar and a chat pane this settings column is not |
| `localDesc`: "Start a private Hermes backend on localhost. This is the default and works offline." (`en.ts:778`) | mobile-adaptation | "Connect to a private Hermes backend you run on this phone. Works offline." | Two of Desktop's three clauses are false here: this app starts nothing — the person runs `hermes serve` in Termux and this app only dials it — and ADR-0002 makes the host-owned Remote Gateway, not Local, the preferred route on mobile |
| `remoteDesc`: "Connect this **desktop shell** to a remote Hermes backend." (`en.ts:780`) | mobile-adaptation | "Connect this app to a remote Hermes backend." | One word, and it named the wrong client; the rest of the sentence is Desktop's |
| `sshDesc` ends "Requires working **key-based** SSH access to the host" (`en.ts:866-867`) | mobile-adaptation | "Requires working SSH access to the host." | This route offers three methods and only one is a key (`AuthMethod.TailscaleSsh`, `Password`, `PrivateKey` — `data/ssh/SshModel.kt:80`); left verbatim the line tells a password or tailnet user the route will not work for them, which is a deterrent rather than a cosmetic difference. The rest of the sentence is Desktop's word for word |
| `rounded-lg` on the card, **measured** at 2.4px in the captured theme (`--radius: 0.75rem`, `styles.css:426`, scaled down by the active preset) | drift | A fixed 10dp, this app's container radius | #100. Desktop scales radii per theme; this app has no radius token at all — `HermesTokens` carries colours and nothing else — so every container is 10dp regardless of preset. Making only these four cards 2.4dp would fragment the app rather than fix it; the fix is a radius token, which is a theme-layer change |
| `ModeCard` `disabled:opacity-50` (`gateway-settings.tsx:99`) | mobile-adaptation | The disabled card drops each text role one tier (title to tertiary, body and glyph to quaternary) rather than compositing at 50% | Accessibility: a flat 50% alpha over a themed surface lands at a contrast ratio nothing in the token set controls, and several presets are already low-contrast. Stepping the semantic roles keeps the disabled state legible in every theme, and the tokens are the layer this app is allowed to reach for |
| Local kind button disabled while the one managed local entry exists, with `localAddHint` (`connections-registry.tsx:654`, `en.ts:757`) | mobile-adaptation | Local stays offered; a genuine duplicate is refused by loopback address instead | Desktop's registry holds at most one Local; this one keys Local rows by address, so there is no one-Local rule to disable on and the hint would announce a rule this app does not have |
| Switching happens in the sidebar radio group; `stagedNote` says "Switch gateways from Sessions" (`connection-switcher.tsx:212-227`, `en.ts:706`) | mobile-adaptation | A `Switch` action on every non-active row, ahead of Desktop's four, calling the same `selectConnection` semantics | Desktop's Settings sits beside a sidebar that is always there; a phone's Gateways screen is a destination, and the person is already standing on it when they add or repair a gateway. The verb is Desktop's own, so the two surfaces name one act |
| `stagedNote`: "Switch gateways from Sessions." (`en.ts:706`) | mobile-adaptation | "Switch gateways here or from Sessions." | Forced by the row above: naming Sessions as the only route would now be false, and would point at the longer of the two |
| A pending switch shows on the rail trigger only, because the radio menu closes on the click (`connection-switcher.tsx:133,272`) | mobile-adaptation | The registry row itself reads `Connecting…` and is disarmed, as is every other row's switch | This list does not close when you tap it, so the row that is moving has to say so itself; the word is the constant the rail uses |
| SSH is main-process-owned with stored credentials, so a row never lands active-but-undialled | mobile-adaptation | A row that cannot come up unattended says so and names `Connect`, but only while the route pane above is offering that button | No Desktop equivalent to port. Gating on the button's own condition keeps the sentence from becoming stale advice about a problem that is already over |
| `Edit`/`Remove` are hidden on the `local` kind (`connections-registry.tsx:604`) | mobile-adaptation | Shown on every kind, including Local | Desktop's Local is the runtime its own app manages, so there is nothing to edit and removing it is meaningless. Android's Local is a Hermes the person runs in Termux: its address and session token are theirs to change, and the row is theirs to delete |
| No-results text carries `role="status"` (`connection-switcher.tsx:216-221`) | drift | Plain `Text` | Not a live region here; #85 |
| A separator sits between the search field and the radio group (`:202`) | drift | No separator; the hairline sits below the list | #85 |
| Unread markers survive a source switch (`gateway-switch.ts:70-76`) | drift | Unread is a cached row field, and the cache is cleared | No durable unread store exists yet; #66 |
| Resting card fill `bg-(--ui-bg-quinary)` — a translucent accent wash (`styles.css:288-292`), **measured** at `srgb(0.044 0.210 0.554 / 0.059)` in the capture | drift | `tokens.widgetSurface`, an opaque card-derived fill | #100. The token layer has no quinary equivalent, and it is pinned at a different SHA and gated by `ThemeParityTest`, so adding one is a theme-sync change rather than this slice's. The capture puts a number on it: Desktop's resting card is a ~6% accent wash over whatever is behind it, ours is opaque |
| `Make primary` is hidden on the row that already is primary (`connections-registry.tsx:601`) | drift | Rendered on every row, uniformly disabled | Android persists no `registry.primary` / `launchMode`, so the condition has nothing to test against and no row is the one Desktop would hide it on. Hiding it on an arbitrary row would invent the concept; revisit when launch mode is ported — #100 |
| `Test` (`en.ts:723`) and `Make primary` (`en.ts:722`) | omission | Present on every row, disabled, each behind a `Coming soon` pill via the shared `ComingSoonAction` primitive | coming soon — the pill ships today (S-C1, #104). `Test` needs a route-independent reachability probe this app does not have; `Make primary` needs the `launchMode` / `registry.primary` field Android does not persist |
| `Update all instances`, and the launch-mode toggle | omission | Absent | pill-owed: #101 — page-level controls Desktop renders, so each owes the same disabled row `Test` and `Make primary` now have (#100) |
| Extra-header editor | omission | Absent | out-of-scope: #100 named it an explicit non-goal of that issue; nothing about the platform refuses it |
| Hermes Cloud connection mode (`gateway-settings.tsx:1057-1064`) and `cloud` kind (`connections-registry.tsx:652`) | omission | Rendered, disabled, in Desktop's position in both choosers, behind the shared `Coming soon` pill | coming soon — the pill ships in this change. There is no Android Hermes Cloud sign-in yet, and `GatewayConnectionMode`/`ConnectionKind` deliberately have no member for it, so it cannot be selected or saved |
| `cloudAddHint` under the kind chooser (`en.ts:758-759`) | omission | Absent | deferred: #100 — a hint, not a control: it renders only while the editor's kind *is* cloud, which a disabled Cloud button makes unreachable, and it returns with the sign-in the card above is waiting on |
| `envOverride` disabling every mode card (`gateway-settings.tsx:1052`, `en.ts:773-775`) | omission | Absent | non-goal: `HERMES_DESKTOP_REMOTE_URL`/`_TOKEN` are desktop-process environment variables, and an Android app has no shell environment to be overridden by |
| Plain-text-keyring consent | omission | Absent | non-goal: every secret here is a Keystore slot, so there is nothing to consent to |

## Visual report

- report: docs/parity/visual/gateway-connection-mode/ (`reference.png` + `contract.json`, 45 nodes)
- commit: 47d03b3

**Desktop half only.** There is a real capture of Desktop's mode grid at the
pin now, but no Android capture beside it, so no side-by-side was built and
nothing here has been *compared* by eye at pixel level — the ceiling
`review-desktop-parity` sets for that still applies. What the packet does give
is measurements, and three rows above are now numbers instead of readings of
the JSX.

How it was taken, so it can be repeated or disbelieved:

```
node .chalk/skills/port-hermes-desktop-surface/scripts/capture-desktop-reference.mjs \
  --name gateway-connection-mode --selector 'div.grid.auto-rows-fr' \
  --upstream <disposable export at the pin> \
  --expect-sha f82f2dbabd9e66b714f2b4f8a40447fe0c13e732 --match 5174
```

`contract.json` records `upstreamSha:
f82f2dbabd9e66b714f2b4f8a40447fe0c13e732` and the root node's classes as
`grid auto-rows-fr grid-cols-1 gap-2 sm:grid-cols-2 min-[72rem]:grid-cols-4`,
which is `gateway-settings.tsx:1048` verbatim — the selector matched the right
element.

The packet is committed rather than left in the untracked `build/` tree, so it
survives a `./gradlew clean` and a reviewer can open it from the branch. **One
byte-range was changed on the way in:** `reference.upstream` held the absolute
path of the throwaway export, which carries a username and a session id, and
the port skill bans a filesystem path from a capture as firmly as it bans a
host. It now reads `<disposable export checked out at the pin>`; the SHA beside
it is untouched, and nothing else in the file differs from what the script
wrote. The screenshot needed no scrub — every string in it is Desktop's own
product copy, and the whole capture ran against empty synthetic state.

Three caveats, none of them cosmetic:

- **Synthetic bridge.** `GatewaySettings` early-returns its unavailable state
  unless `window.hermesDesktop.getConnectionConfig` exists
  (`gateway-settings.tsx:1015`), and `scripts/dev-mock.mjs` launches the built
  Electron app rather than seeding a browser one. The renderer was driven with
  an injected stub returning empty strings, `false` and `null` — no host,
  credential, fingerprint or path — so the cards show the `local` default with
  every field blank.
- **Container width is not authoritative.** The grid measured 694px wide with
  four 167.5px columns inside a 1600px viewport, because the surface mounted in
  a narrower pane than the standalone settings column. Treat copy, order,
  glyphs, structure, type and colour as authoritative; treat absolute widths as
  container-dependent.
- **One theme, light.** Every colour above is that preset's.

The Android half still needs a device, and this pane is `FLAG_SECURE`, so
`screencap` returns black and an accessibility dump is the substitute. None was
attached for this slice. #85 carries the device re-run of the switch path.
