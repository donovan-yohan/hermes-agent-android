# Appearance themes: source and divergence ledger

Android keeps its built-in skins, then shows validated custom themes from the
active Gateway's dashboard route. The selected name is stored on the current
connection row; a definition is never stored on the phone.

## Pin

| Source | Pin | Read via |
|---|---|---|
| Gateway dashboard-theme route and normaliser | `hermes-agent` @ `437116f9497c80d242ce034ff7f5d81dc277a337` | read-only checkout |
| Android appearance surface | `hermes-mobile` @ `b23133b4fa53cd0e8c0c9ae2ff099dbfdc2a88b7` base | current worktree |

Dashboard-contract citations below are against
`437116f9497c80d242ce034ff7f5d81dc277a337`; Desktop structure and copy
citations name their UI pin beside the path.

## Paths that settled the port

| Question | Path |
|---|---|
| Dashboard theme-list response and selection write | `hermes_cli/web_routers/dashboard_ui.py:46-70` |
| Built-in and custom-definition normalisation | `hermes_cli/web_server_dashboard.py:229-421` |
| Dashboard theme wire types | `web/src/themes/types.ts:22-208` |
| Semantic colour derivation from the three palette layers | `web/src/index.css:157-181` |
| Picker swatch fallback | `web/src/components/ThemeSwitcher.tsx:315-331` |
| Built-in, backend, and user-theme ordering precedent | `apps/desktop/src/themes/user-themes.ts:161-175` |
| One-mode palette precedent | `apps/desktop/src/themes/skin.ts` |
| Desktop appearance settings structure and theme-picker order | `apps/desktop/src/app/settings/appearance-settings.tsx:509-803` @ `564aef2946c436500a5e80ee117b66b789b3f99a` |
| Desktop appearance copy | `apps/desktop/src/i18n/en.ts:588-629` @ `564aef2946c436500a5e80ee117b66b789b3f99a` |

The Gateway-theme section has no Desktop appearance-settings counterpart at the
UI pin. Its fixed status copy is Android-specific; the Dashboard endpoint's
wire names are not product-copy strings. Existing built-in skin and Intro
Splash copy retain their cited Desktop wording and order.

## State classification

| State | Authority | Android handling |
|---|---|---|
| Gateway custom-theme list and Gateway-reported active name | Backend-authoritative | Read through the active connection's authenticated dashboard route; discarded on endpoint switch |
| Chosen theme name | Connection-scoped persistence | Stored only on that saved connection row and restored before a custom definition is fetched |
| Picker position, previews, and retry affordance | UI-only | Derived from the row selection and the current Gateway list |

## Visual report

- pending: #292

The report will be captured at the implementation commit produced on
`wt/t_71f8c749`; `b23133b4fa53cd0e8c0c9ae2ff099dbfdc2a88b7` is only the base
and contains none of this surface work. No `visual-capture-surfaces.json` entry
was added because this change adds no capture activity.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Dashboard picker lists the dashboard's built-ins with its custom themes | mobile-adaptation | Android keeps `BuiltinThemes.ALL`, then lists only custom entries carrying a definition from the active Gateway | The server built-ins describe the web dashboard and carry no definition; preserving Android's existing preset registry avoids substituting another product's skins |
| Dashboard typography, layout, CSS, assets, and colour extras can affect the web shell | mobile-adaptation | Only the three palette layers map through Android semantic tokens | Android has no safe equivalent for remote CSS, asset URLs, or web font URLs; the bounded validator recognises harmless wire fields and rejects executable or external presentation fields |
| Dashboard's host-wide active theme is restored by the host | mobile-adaptation | Each saved connection restores its own selected name, while the host value remains informational | A phone can move among saved Gateways; restoring one host's appearance while viewing another would make the local selection depend on a remote side effect |
| No Gateway-theme section in Desktop Appearance settings | mobile-adaptation | A labelled Gateway themes section follows Android's built-in Skin rows | The custom list is backend-authoritative and endpoint-scoped; grouping it after the immutable local registry makes its source and retry state visible without changing the existing built-in picker order |
| Rendered Desktop-versus-Android side-by-side | drift | Not yet captured | #292 |

## Executable evidence

| Claim | Test |
|---|---|
| Built-ins remain selectable offline; custom rows, failure copy, retry action, and spoken custom-row description render | `AppearanceThemesJourneyTest` under Robolectric |
| Dashboard envelope validation, palette mapping, endpoint generation fence, and REST path/body/echo guards | `GatewayThemeParserTest`, `GatewayThemeRepositoryTest`, and `GatewayRestClientTest` |
