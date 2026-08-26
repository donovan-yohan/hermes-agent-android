# Gateway connections registry and session-rail switcher parity

## Pin and source contract

Desktop authority is `NousResearch/hermes-agent` at
`f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`, read through
`git show <sha>:<path>` on a read-only checkout. Every line number below is at
that SHA.

| Contract | Desktop source | Android port |
|---|---|---|
| Registry section | `apps/desktop/src/app/settings/connections-registry.tsx:221-888` | `ui/gateway/ConnectionsSection.kt` |
| Where it lives | `apps/desktop/src/app/settings/gateway-settings.tsx:1499-1502` — foot of the Gateways page, below the connection controls | Same page, below the same controls, inside whichever route's scroll is showing |
| Kind glyphs (`KIND_ICONS`) | `connections-registry.tsx:26-31` — `cloud`/`local`/`remote`/`ssh` → Cloud/Monitor/Globe/Terminal | `ConnectionKind.glyph`: Remote → Codicon `globe`, Ssh → Codicon `terminal` |
| Row grammar | `settings/primitives.tsx:108-155` (`ListRow`), `:27-29` (`Pill` over `components/ui/badge.tsx:7-21`), `components/ui/empty-state.tsx:7-23` | `ui/common/SettingsPrimitives.kt` — `SettingsListRow`, `Pill`, plus the existing `EmptyState` |
| Row content | `connections-registry.tsx:578-641` — kind glyph, label, Current/Primary pills, `kind · endpoint` description | Kind glyph, label, `Current` pill, `kind · endpoint · auth mode`, all through `redact()` |
| Duplicate rule | `connections-registry.tsx:89-168` (`normalizeGatewayUrl`, `sshCompositeKey`, `findDuplicateConnection`) | `data/connections/ConnectionRegistry.kt`, same three functions, minus the Local rule |
| Removal | `connections-registry.tsx:866-874` (`ConfirmDialog`, destructive) | `ui/common/ConfirmSheet` — a bottom sheet with the same title, description and destructive confirm |
| Display order | `lib/connection-display.ts:11-23` (`Intl.Collator`, numeric, base sensitivity) | `sortConnectionsForDisplay`, numeric-aware and case-insensitive, id breaking ties |
| Search threshold | `lib/connection-display.ts:3` (`CONNECTION_SEARCH_THRESHOLD = 8`) | `CONNECTION_SEARCH_THRESHOLD = 8`, in both the settings list and the rail sheet |
| Search matching | `lib/connection-display.ts:29-58` — NFKD, marks stripped, every needle must match | `connectionMatchesQuery`, same normalisation |
| Endpoint string | `lib/connection-display.ts:61-75` (`connectionEndpoint`) | `SavedConnection.endpoint` |
| Rail switcher | `apps/desktop/src/app/chat/sidebar/connection-switcher.tsx:40-322` | `ui/sessions/ConnectionSwitcher.kt` |
| Hidden for one source | `connection-switcher.tsx:118-120` | `ConnectionsUiState.switchable`; the rail renders no chrome |
| Radio group + active check | `connection-switcher.tsx:205-233` (`DropdownMenuRadioGroup`/`RadioItem`) | 48dp rows with `Role.RadioButton`, selected semantics, and a Codicon `check` |
| Trailing manage item | `connection-switcher.tsx:234-237` + `i18n/en.ts:1772` | A hairline, then a `Manage gateways…` row that opens Gateways |
| Pending state | `connection-switcher.tsx:133,272` (`aria-busy`, spinner) | `stateDescription = "Connecting…"` on the trigger and a per-row `Connecting…` |
| Switch semantics | `store/connections.ts:153-225` (`selectConnection`) and `store/gateway-switch.ts:47-96` (`wipeSessionListsForGatewaySwitch`) | `data/connections/ConnectionSwitchController.kt` plus `SessionCache.resetForEndpointSwitch()` |
| Copy | `i18n/en.ts:703-764`, `:1770`, `:1772` | `ui/gateway/ConnectionsCopy.kt`, one constant per line, cited |

## State classification

| Kind of state | Home | Rule |
|---|---|---|
| Saved connections and which is active | `HermesPreferences` (`connections.v1.saved`, `connections.v1.activeId`) | Client-local authority. No Gateway contract exists for it; it is never sent anywhere. |
| The active row's endpoint fields | The same rows | `hostProfile`, `remoteGatewayProfile` and `gatewayConnectionMode` are **projections** of the active row, so there is one copy and nothing can drift. |
| A Remote row's sign-in | `AndroidGatewayTokenStore`, one Keystore-encrypted file per row id, under `noBackupFilesDir` | Never in preferences, never in a row, never in a log. Removing a row erases exactly its file. |
| An SSH row's password/passphrase/key | Nowhere | Unchanged: in-memory for one attempt, zeroed after. There is no per-row SSH secret because there is no SSH secret on disk at all. |
| Sessions and transcripts | `SessionCache` | Merge, never clobber — except a connection switch, which clears wholesale because the next backend is a different machine. |
| Which session is open, search text, project drill-in | `ChatViewModel` | UI-only. Dropped when the endpoint identity changes. |
| Editor form state, search text, sheet open | `ConnectionsViewModel` / `rememberSaveable` | UI-only. Never persisted. |

## Mobile adaptation ledger

| Desktop | Android | Reason |
|---|---|---|
| `DropdownMenu` + `DropdownMenuRadioGroup` anchored to the rail trigger | `ModalBottomSheet` with 48dp radio rows | Pointer menus are brittle on a phone; the sheet is this app's established equivalent (`ComposerAddSheet`, `ModelControl`). Order, checkmark and search threshold are unchanged. |
| `ConfirmDialog` | `ConfirmSheet` bottom sheet | Same reason; same title, description, destructive confirm and cancel. |
| `ListRow` with the control beside the label above `@2xl` | Always stacked | This *is* Desktop's own narrow rendering — the query is on the row's pane width, and a phone is always below the breakpoint. |
| Icon-only ghost `Pencil`/`Trash2` with `aria-label` "Edit"/"Remove" | Same glyphs in 48dp targets, `contentDescription` "Edit ⟨label⟩" / "Remove ⟨label⟩" | Touch floor, and a list of rows needs the label to tell two identical buttons apart. |
| Hover `title` tooltip carrying label + endpoint (`connection-display.ts:78-82`) | The endpoint is rendered under the label in the sheet, and in the row description in settings | Touch has no hover; the information is shown rather than hidden. |
| Row description `kind · endpoint` | `kind · endpoint · auth mode` | The issue's acceptance list asks for the auth mode on the row; it is a mode name, never a secret. |
| `EmptyState` with a title only | Title plus one next-action line | Product-copy rule: state the outcome *and* the next action. |
| `Test`, `Make primary`, `Update all instances`, launch-mode toggle, extra-header editor, plain-text-keyring consent | Absent | Omissions, not adaptations. `Test`/`Update all` have no Android equivalent yet; `primary` is meaningless with one active connection; header editing and the keyring consent are explicit non-goals of the issue. Recorded so the port stays honestly incomplete. |
| `local` and `cloud` kinds | Absent | Non-goals. This app never hosts a runtime, and there is no Android Hermes Cloud sign-in. |
| `intro` names Cloud; `stagedNote` names profiles and cron jobs | Both shortened to what Android ships | Copy must be true on the device it is on. Source lines still cited. |
| Kind is fixed once created (`connections-registry.tsx:649-654`) | Same in the list editor. The route control at the top of Gateways still changes the **active** row's kind | That control predates this slice and is the active connection's own form; both endpoint slots persist per row, so nothing is lost by flipping it. |
| Registry may be empty (`empty: 'No connections registered yet.'`) | Always at least one row; removing the last is refused | Android has exactly one active connection and no "disconnected from everything" state to fall back to. The empty state is still implemented and reachable in tests. |

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
- `GatewayTokenSlotTest` — two rows never share a slot, removing one erases only
  its own credential (and zeroes the bytes before unlinking, proven through a
  hard link), and the pre-registry file is adopted exactly once.
- `ChatEndpointSwitchTest` — a changed endpoint drops the open session, the
  search and the project drill-in, then lands on the new endpoint's most
  recently active session; a profile change on the same endpoint does not.
- `ConnectionsJourneyTest`, `ConnectionSwitcherJourneyTest` — the rendered
  list with its `Current` marker and redacted summary, the empty state, the
  add/edit flows (including the kind picker disappearing on edit), the
  destructive confirm sheet, the inline duplicate error, and — on the rail —
  no chrome for one connection, the sheet's rows and their selected state, the
  eight-connection search threshold with its no-matches copy, the pending
  `Connecting…` state, and `Manage gateways…`.

Rendered visual capture against a Desktop dev renderer has **not** been done for
this slice; the surface is evidenced by the Compose journeys and this ledger.
That is an omission, recorded here rather than implied away.
