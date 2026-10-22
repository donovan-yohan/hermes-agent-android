# Appearance themes: source and divergence ledger

Android keeps its built-in skins, then shows validated custom themes from the
active Gateway's dashboard route. The selected name is stored on the current
connection row; a definition is never stored on the phone.

## Pin

| Source | Pin | Read via |
|---|---|---|
| Gateway dashboard-theme route and normaliser | `hermes-agent` @ `437116f9497c80d242ce034ff7f5d81dc277a337` | read-only checkout |
| Android appearance surface | `hermes-mobile` @ `1d0f6d1` rebase base (head on the PR) | current worktree |

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
| Built-in, backend, and user-theme ordering precedent | `apps/desktop/src/themes/user-themes.ts:160-172` |
| One-mode palette precedent | `apps/desktop/src/themes/skin.ts` |
| Desktop appearance settings structure and theme-picker order | `apps/desktop/src/app/settings/appearance-settings.tsx:393-972` @ `564aef2946c436500a5e80ee117b66b789b3f99a` |
| Desktop appearance copy | `apps/desktop/src/i18n/en.ts:656-789` @ `564aef2946c436500a5e80ee117b66b789b3f99a` |

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
`wt/t_71f8c749`; the rebase base contains none of this surface work. No
`visual-capture-surfaces.json` entry was added because this change adds no
capture activity.

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

## Historical backend-skin target (e05b1634)

This subsection is source revalidation at
`e05b16348b1d06a3311237423b0a4fc30d9c5aa1`, not a repin of the historical
Dashboard/UI citations or captures above. Unlike Dashboard definitions, backend
skin definitions are now cached per connection/profile; the opening description
records the original Dashboard-only port.

`apps/desktop/src/themes/backend-sync.ts:94-101,134-147` retires converted backend
`default`: connect seeds preserve the chosen theme, explicit reset paints Nous,
and duplicate announcements cannot undo a later manual choice. The apply guard
uses backend names, not normalized palette names. Android cache sanitization
excludes legacy Classic definitions while retaining other scoped skins.
`apps/desktop/src/themes/context.tsx:51-63,107-112` retires stored `default`,
`gold`, and `nous-light` at resolution without rewriting a stored custom pick.
Builtin identity is unchanged: the inventory gate discovered 11 matching entries.

Tests: `GatewayThemeParserTest`, `BackendSkinSyncTest`, `AppearanceThemeResolverTest`
and the added legacy-choice case in `HermesPreferencesTest`. The standalone JVM
subset and subsequent Android Gradle validation are green at `9bbdca7b`: all 96
focused tests passed; full debug/release suites reported 3,335/2,643 tests with
zero failures or errors and one skipped in each. `check :app:assembleDebug`
passed with one worker, no parallel execution, and in-process Kotlin compilation.
This does not replace the pending rendered comparison. See the
[scoped refresh audit](../spikes/upstream-theme-refresh-e05b1634-2026-10-01.md).

The [e05 narrow paired observation](../media/theme-refresh-e05/REPORT.md) adds
four native Android phone captures from the approved APK and eight genuine e05
Desktop wide references. Manual Mono is paired in light/dark. Android's offline
persisted `default` resolves to a visibly checked Nous without rewriting the stored
name; this is **not** Desktop's explicit backend-default action. Android backend
seed/apply/repeat transitions, matched phone/wide coverage and historical #292
Gateway states remain unverified. Both packets retain observational-schema
rejections; native Android XML does not confirm the visible selected state.
Visual comparison remains **pending: #292**. Verdict: **Concern**, not rendered
parity approval.
No palette literals, labels, descriptions, picker order or typography changed.
Existing CSS/platform and per-connection adaptations in the divergence table
still apply; remote CSS is not executed on Android.

## Current built-in and backend-skin target (36922ad0)

Source-only refresh at `36922ad064d65dcf25f8f48df81e1ccf9a55de67`:
`apps/desktop/src/themes/presets.ts:342-384,444-468` adds `classic` / Classic
Hermes after Nous Alt, making 12 built-ins. Android converts the exact light/dark
seeds and verifies every resulting field against executed Desktop converter
output. Existing historical capture pins above have not moved.

All built-in and retired backend names are reserved. Cached shadows and invalid
records are safely rewritten out on boot, preserving valid scoped customs;
`default`, `gold`, and `nous-light` still resolve/apply to Nous. Registration-only
seeds do not repaint and repeated acknowledged events preserve manual selection.
`hermes` and `ares` remain usable custom identities outside command context.

Desktop's `use-skin-command.ts:5-15` maps command-only `gold` / `hermes` to
Classic. Android has no local skin-command handler: it forwards the original
command through `prompt.submit`. No interception was introduced, and native
slash-alias parity is not claimed. Choose Classic in Appearance on Android.
See [the audit](../spikes/upstream-36922-2026-10-02.md) for source ownership and
backend-only changes that require an updated Gateway.

The [fresh Classic manual-selection packet](../media/classic-36922/REPORT.md)
adds eight observations: Android phone and pinned real Desktop wide, picker and
screen in light/dark, after selecting Nous Alt. Four comparison pairs demonstrate
manual Classic rendering; all eight receipts remain noncanonical with preserved
validator rejection. Android disconnected and Desktop synthetic Gateway-ready
screens are appearance references, not identical session states. Backend
seed/apply/repeat and matched phone/wide viewport coverage remain **pending: #292**.
The existing e05 report is unchanged historical evidence, not evidence for Classic.
Independent review verified the bounded observational packet and its immutable
artifacts; full parity remains unverified and the ceiling remains **Concern**.

## Registry-only source audit (5fc490e2, 2026-10-19)

The new implementation/registry target is
`5fc490e2b34123212d73ef316086f939567a725a`. The 36922 section and every source,
semantic golden and rendered packet above retain their historical provenance;
this appendix does not restamp or supersede their evidence.

The immutable Git blobs for `apps/desktop/src/themes/presets.ts`,
`apps/shared/src/theme-presets.ts`, `apps/shared/src/color.ts` and Desktop's
`themes/color.ts`, `themes/skin.ts`, `themes/backend-sync.ts` and
`themes/use-skin-command.ts` match the previous target. The light-synthesis body
is also byte-identical, now at `apps/desktop/src/themes/context.tsx:151-185`.
The twelve built-ins, order, descriptions, default, Classic seeds and built-in
typography therefore need no Android production-data edit. The ledger's color
interface citation moves to `apps/desktop/src/themes/types.ts:15-50`.

The [source audit](../spikes/upstream-5fc490e2-2026-10-19.md) records changed
saved-accent retints, profile inheritance, optional typography knobs and mode
requests. These paths were source-inspected to bound the repin, not fully audited
end to end, executed or ported on Android. CSS and other surfaces also changed;
registry parity is not semantic or appearance parity for those changes.

Focused theme tests and the live inventory gate passed. No new paired captures,
device acceptance, backend deployment or new appearance-behavior acceptance were
performed. **pending: #292** and the **Concern** ceiling remain unchanged.

## Final latest-main target (ddabd383, 2026-10-19)

The target advanced to `ddabd3837862022a6053331fe3ba97cf9716b9e3` during
delivery. The [follow-up audit](../spikes/upstream-ddabd383-2026-10-19.md)
proves no Desktop/shared source changes since the preceding audit. All twelve
registry entries match the new target, the focused theme tests passed again,
and no historical rendered or semantic evidence was restamped. #292 and the
Concern ceiling remain unchanged.

## Bounded Settings source continuation (2026-10-22)

This is not a theme repin or rendered review. The
[Settings-depth inventory](desktop-control-reconciliation.md#settings-depth-continuation-2026-10-22)
adds 41 source-read Appearance templates at Desktop
`81a55437f3f31a9f12a30c53b6c5d49d420898aa`, compared with native baseline
`65080e8a37e8d546869528e43c162503015b94c7`. Exact UI, named-setter, writer,
mount and native spans are in the `settings-depth.appearance.*` records.

The reading follows Simple-mode shadow/session-reveal precedence, the backend
mirrors for reactions/tips/tours, per-painted-appearance translucency, and the
specialized font autosave/rollback controllers. None is certified by the generic
config dispatcher or the twelve-theme registry check.

Current-source scope findings under #266: Desktop skin and mode assign the live
active profile, with named choices mirrored to global inheritance; native theme
selection stamps a connection row, while native mode uses one DataStore key.
The historical per-connection adaptation above remains historical and is not
retroactively withdrawn. The records flag the newer source difference instead
of calling the picker absent. Intro Splash has a native writer, but its current
Switch-versus-native-segments visual treatment was not accepted by this audit.

Other source-read Appearance controls remain provisional omissions under #157;
no native non-goal was inferred from a platform gate. Language, tray/native
material effects, Pet and contributed-card descendants, full theme conversion
and mode-layout persistence consequences remain unverified. No production UI,
private settings, theme installation, provider call or rendered capture changed.
Existing source/visual pins and pending #292 retain their provenance.
