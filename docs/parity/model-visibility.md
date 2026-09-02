# Model visibility: source and divergence ledger

Hermes Desktop's **Edit models…** row and its **Models** dialog, plus the
visibility filter the model picker applies, ported per
[`docs/workflows/port-desktop-surface.md`](../workflows/port-desktop-surface.md):
`data/composer/ModelVisibility.kt`, `data/prefs/ComposerControlsStore.kt`,
`data/prefs/HermesPreferences.kt`, `ui/chat/composer/ModelVisibilitySheet.kt`,
`ui/chat/composer/ModelControl.kt` and `ui/chat/ChatViewModel.kt`.

## Pin

| Source | Pin | Read via |
|---|---|---|
| Desktop renderer, Gateway RPC, CLI | `hermes-agent` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3` | read-only checkout; every citation taken with `git show <sha>:<path>` |

Every `path:line` below is against that SHA.

## Paths that settled the port

| Question | Path |
|---|---|
| The stored set, the `provider::model` key, the hide-all sentinel and the family collapse | `apps/desktop/src/store/model-visibility.ts:6-69` |
| The curated default, the working set, the display set and both toggles | `apps/desktop/src/store/model-visibility.ts:102-262` |
| The dialog: title, search, provider label, tri-state checkbox, per-model switch, empty state, footer | `apps/desktop/src/components/model-visibility-dialog.tsx:81-190` |
| The `Edit models…` row, its glyph, and that it closes the catalog | `apps/desktop/src/app/shell/model-catalog-menu.tsx:521-535` |
| That the picker honours the set, that search spans everything, and that the current model is pinned | `apps/desktop/src/app/shell/model-catalog-menu.tsx:546-601` |
| That the picker resolves the shortlist against the catalog it fetched before grouping | `apps/desktop/src/app/shell/model-catalog-menu.tsx:179-188` |
| Every visible string | `apps/desktop/src/i18n/en.ts:2847-2852,2861` |
| That `model.options` carries no visibility field, and what `featured_models` is | `apps/desktop/src/types/hermes.ts:384-427`, `tui_gateway/methods_complete.py:469-490`, `hermes_cli/inventory.py:513-568` |
| Which providers the dialog lists | `apps/desktop/src/components/model-visibility-dialog.tsx:61-64` |
| Where the collapsed-provider set lives | `apps/desktop/src/store/provider-collapse.ts:1-28` |

## State classification

| Desktop state | Where it comes from | Android |
|---|---|---|
| `$visibleModels` | `localStorage['hermes.desktop.visible-models']`, one global entry | `ComposerControlsStore.visibleModels(scope)`, one DataStore document per connection/profile scope |
| null vs. empty set | null = never customised, empty = everything hidden (`model-visibility.ts:87-89,144-146`) | the same two states, and `ModelVisibilityCodec` keeps them apart on disk |
| hide-all sentinel | `${provider}::` (`:16-26`) | `emptyProviderSentinelKey`, preserved by every write and stripped for display |
| curated default | `featured_models`, else the top 50 collapsed families (`:114-132`) | `expandProviderDefaults`, over `ModelProvider.featured` parsed from `model.options`. Both the picker and the Models sheet reach it the way Desktop does — through `effectiveVisibleKeys(stored, providers)`, resolved against the catalog actually fetched (`model-catalog-menu.tsx:182-188`) — so `groupModels`' top-N `else` branch has no counterpart here |
| collapsed providers | `localStorage['hermes.desktop.collapsed-providers']`, shared with the picker | sheet-local state, for the life of the sheet |
| model display name | `modelDisplayParts` prettifier (`lib/model-status-label.ts:74-91`) | the Gateway's own `label`, which is what this app's picker has always rendered |
| provider setup | `onOpenProviders()` opens the provider dialog | absent; the footer ships disabled behind the marker chip |

## Mobile adaptation ledger

| Desktop | Android | Reason |
|---|---|---|
| `Dialog` with `max-w-xs` and a `55vh` scroll area | `ModalBottomSheet` with a bounded model list and a scrolling content root | The app's consistent pattern for a searchable list; the sheet's root scrolls so the footer stays reachable when the keyboard is up |
| One global `localStorage` key for every profile and host | One document per connection/profile scope | Two Gateways are two catalogs: a shortlist that crossed between them would name models the other host does not serve, exactly as a saved model pick would |
| `Checkbox` and `Switch` from shadcn/Radix | The same two affordances drawn from `HermesTheme.tokens` | Every control in this app is painted from the token layer; Material's own switch would import Material's shape, motion and colour defaults into one surface |
| Provider label reveals its disclosure caret on hover | The caret is always drawn | Touch has no hover, so a control that only appears on hover is a control that never appears |
| A model row is a `<label>` whose whole width toggles the switch | A 48dp row with `Role.Switch`, the label and the switch inside it | The row is the touch target, and the semantics have to carry the on/off state for a screen reader |

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Models dialog is a centred modal `Dialog` | mobile-adaptation | Modal bottom sheet | Mobile touch viewports use bottom sheets rather than centred desktop dialogs |
| Visibility is one global `localStorage` entry across every host and profile | mobile-adaptation | One stored document per connection/profile scope | Two Gateways are two catalogs; a shortlist carried across them would name models the other host cannot serve, which is the same rule the saved model pick already follows |
| `Edit models…` is the last row of the whole catalog menu, after the MoA presets and the host footer (`model-catalog-menu.tsx:527-535`) | mobile-adaptation | Last row of the model list, above the adapted Reasoning and Fast sections | Desktop's reasoning and fast controls are per-row submenus, not sections; the phone sheet has them as sections below, so "the end of the list" is the row directly after the models |
| MoA preset rows above the footer (`model-catalog-menu.tsx:495-518`) | omission | Absent | deferred: #73 — Mixture-of-Agents presets are their own capability and no part of this batch |
| `Add provider…` closes the dialog and opens provider setup | omission | Visible and disabled behind the marker chip | pill-owed: #101 — provider setup is not ported, so the control is marked rather than missing |
| Collapsed providers persist in `localStorage` and are shared with the picker (`store/provider-collapse.ts:22`) | omission | Collapse lives for the life of the sheet | deferred: #73 — the picker here has no collapse to share, and a presentation preference is a detail rather than a control |
| Model names run through `modelDisplayParts`, which strips date pins and lifts a variant tag out of the name | omission | The Gateway's own `label` is rendered | deferred: #73 — the prettifier is a shared label concern for every model surface, and the picker already renders the same string today |
| Provider rows are sorted alphabetically by name in the picker (`model-catalog-menu.tsx:596-598`) | omission | `model.options` order is kept | deferred: #73 — the picker's group order predates this change and is the same on both of its surfaces |
| A search highlights its matches inside each row (`HighlightMatches`) | omission | Matching rows are shown unhighlighted | deferred: #73 — highlighting is a shared text primitive this app has nowhere yet |
| A pending catalog query draws a `GlyphSpinner` and only says `No authenticated providers.` once it settles (`model-visibility-dialog.tsx:101-104`) | mobile-adaptation | `Loading model choices…` while the read is in flight, the Desktop sentence once it settles | This app has no spinner primitive and every other pending read in the composer says this line; a viewport this small cannot afford a control that flashes the wrong answer at a host that does have providers |
| Provider and model names are rendered as the backend supplies them (`model-visibility-dialog.tsx:131-133,155-158`) | mobile-adaptation | The same, unredacted, while every host name, destination and fingerprint in this app goes through `redact()` | A model catalog is not a credential surface: `redact()` exists for hosts, destinations, fingerprints and key material, and this sheet renders exactly the strings the model picker has always rendered. Redacting one of the two surfaces would leave the picker and the sheet disagreeing about the same label |

## Executable evidence

| Claim | Test |
|---|---|
| A `-fast` sibling collapses into its base, a stray `-fast` model stands alone, and a date-pinned snapshot superseded by its alias is dropped | `ModelVisibilityTest` |
| The curated default is the top 50 families, and the backend's `featured_models` wins where a provider ships one | `ModelVisibilityTest` |
| The picker's own default is the resolved curated set, not a bare top-N, and a provider that appeared after the last customisation is still offered | `ModelVisibilityTest` |
| A provider that was never touched keeps its default; a hide-all records a sentinel that survives another provider's edit and does not read as a model | `ModelVisibilityTest` |
| Re-enabling one model clears only that provider's sentinel and restores nothing else | `ModelVisibilityTest` |
| The provider master switch enables every family, hiding records the sentinel, and a provider with no models strands none | `ModelVisibilityTest` |
| The picker shows only the visible set, search spans the whole catalog, the current model is always offered, and a `-fast` session keeps its base family | `ModelVisibilityTest` |
| `featured_models` is parsed off `model.options`; its strings are kept verbatim and a non-string entry is dropped rather than coerced | `GatewaySessionRepositoryTest` |
| The stored document round-trips, keeps sentinels, tells "everything hidden" from "never customised", and fails closed on a future version | `HermesPreferencesTest` |
| A shortlist restores only for its own connection/profile scope | `HermesPreferencesTest` |
| The saved shortlist reaches the picker, and a toggle publishes then persists it | `ApprovalModeViewModelTest` |
| `Edit models…` sits after the last model row and before the Reasoning section; the sheet carries Desktop's title, search and uppercase provider label; a switch hides one model and the reopened picker stops offering it; a provider added after the last customisation still reaches the picker; the provider checkbox bulk-toggles and reports its partial state; `Add provider…` ships disabled behind the marker chip | `ModelVisibilityJourneyTest` under Robolectric |

## Visual report

- pending: #73
