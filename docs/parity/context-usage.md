# Context meter and Context Usage panel: source and divergence ledger

Hermes Desktop's top-bar **Context Meter** and its **Context Usage panel**
(bottom sheet), ported per
[`docs/workflows/port-desktop-surface.md`](../workflows/port-desktop-surface.md):
`ui/chat/ContextUsageFormat.kt`, `ui/chat/ContextUsageSheet.kt`,
`data/session/ContextBreakdown.kt`, `data/gateway/GatewaySessionRepository.kt`,
`ui/chat/ChatViewModel.kt`, and `ui/chat/ChatScreen.kt`.

## Pin

| Source | Pin | Read via |
|---|---|---|
| Desktop renderer, Gateway HTTP, CLI | `hermes-agent` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3` | read-only checkout; every citation taken with `git show <sha>:<path>` |

Every `path:line` below is against that SHA.

## Paths that settled the port

Every row was re-read with `git show 3ca096de5f8183cb2e0ec23673f294d5978656a3:<path>`.

| Question | Path |
|---|---|
| Top bar Context Meter status label and glyph bar | `apps/desktop/src/lib/statusbar.tsx:37-60` |
| Context Usage panel layout, header, token metrics, category list, segment bar | `apps/desktop/src/app/shell/context-usage-panel.tsx:19-99` |
| Context breakdown fetch lifecycle, gating, caching | `apps/desktop/src/app/shell/hooks/use-context-breakdown.ts:31-62` |
| `gaugeUsage`: which figures the breakdown overrides, and which it never does | `apps/desktop/src/app/shell/hooks/use-statusbar-items.tsx:238-268` |
| Number compacting logic and scale thresholds | `apps/desktop/src/lib/format.ts:4-24` |
| Gateway RPC method `session.context_breakdown`, and that it routes on `session_id` alone | `tui_gateway/methods_session.py:1756-1783`, `tui_gateway/server.py:3564-3584` |
| Gateway event `session.usage`, and that its ticker is joined before `message.complete` | `tui_gateway/server.py:12815-12851` |
| `message.complete` carries the authoritative end-of-turn usage | `tui_gateway/server.py:13431` |
| Which colour the Gateway sends for a category, and that it filters `tokens > 0` | `agent/context_breakdown.py:19-28,152-162` |
| What those eight CSS variables resolve to | `apps/desktop/src/styles.css:210-224`, `:root.dark:556-558` |
| Localized category names and panel copy | `apps/desktop/src/i18n/en.ts:2963-2980` |

## State classification

| Desktop state | Where it comes from | Android |
|---|---|---|
| `contextUsed`, `contextMax`, `contextPercent`, `estimatedTotal`, `model` | `session.context_breakdown` RPC or streamed `session.info` / `session.usage` | `ContextBreakdown` and `SessionUsage` on `SessionSummary` / `ChatUiState.contextMeter` |
| `categories` | `session.context_breakdown` | `List<ContextUsageCategory>` (capped at 16, safe hex color fallback) |
| `loading` | `useContextBreakdown` fetch in-flight | `ContextMeterState.loading` |
| routing | Desktop sends `{ session_id }` alone (`use-context-breakdown.ts:41`) | Android also sends `profile`. It is defensive and unread: `_sess_nowait` resolves the session from `session_id` alone (`tui_gateway/server.py:3564-3584`), and this keeps the routing shape every other session RPC in `GatewaySessionRepository` sends |
| `empty` | `categories.isEmpty() && !loading` | `ContextUsageSheet` empty state |

## Mobile adaptation ledger

| Desktop | Android | Reason |
|---|---|---|
| Context meter rendered in the Electron window footer / status bar | Subtitle row in the top bar beside connection status | Android uses an app top bar rather than an Electron footer; the status bar is where context consumption is visible at a glance |
| Meter reads `30k/200k` then `[████░░░░░░] 40%` as text (`statusbar.tsx:37-60`) | A 14dp pie that fills with the percentage, then the percentage; the figures move into the sheet and into what the meter speaks | The Electron footer is a desktop window wide; the phone's status line is one row that also carries the connection line and the approval chip, and the 24-character text form pushed both off it — the ring is the same proportion in the space a phone has |
| Dropdown / popover panel | Modal bottom sheet (`ContextUsageSheet`) | The app's consistent pattern for secondary inspectable metadata on mobile |
| Category colours are CSS variables the browser resolves at paint (`styles.css:217-224`) | The same eight expressions resolved once in the semantic token layer, `HermesTokens.contextUsage` | Android has no CSS custom properties. The Gateway sends only variable *names* (`context_breakdown.py:19-28`), so the names are resolved through the token group; a literal hex still parses and anything unrecognised falls back to `textTertiary`, which is what Desktop's own `var(--ui-text-tertiary)` default resolves to |

## Divergences

Classified for `scripts/check-parity-evidence.py`; the ledgers above carry the argument.

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Context meter is in the desktop window footer status bar | mobile-adaptation | Placed in the chat top bar subtitle row | A mobile layout has no desktop footer bar; placing the meter in the top bar keeps usage visible without taking transcript space |
| Meter spells the figures out beside a ten-cell glyph bar — `30k/200k [████░░░░░░] 40%` (`statusbar.tsx:37-60`) | mobile-adaptation | A 14dp pie filled to the same percentage, then the percentage; `used / max` stays in the sheet's header and in the meter's spoken description | Viewport space: the desktop footer runs the width of a window, the phone's status line is one row shared with the connection line and the approval chip, and the text form squeezed both out — the chip truncated to `Sm`. `ChatTopBarAlignmentTest` holds the figures' width on a 360dp line and `ContextUsageJourneyTest` holds what the pie speaks |
| Context usage details display as a dropdown popover | mobile-adaptation | Displays as a modal bottom sheet | Mobile touch viewports use bottom sheets rather than hoverable dropdown popovers |
| Popover fixed width (`w-72` / 18rem) | mobile-adaptation | Bottom sheet spans display width | Mobile bottom sheet spans device width rather than desktop popover width |
| Category colour is a CSS variable resolved by the browser (`styles.css:217-224`) | mobile-adaptation | The same eight expressions resolved in `HermesTokens.contextUsage`, joined to the Gateway's variable names by `resolveCategoryColor` | Android has no CSS custom properties, and the Gateway sends a name rather than a value (`agent/context_breakdown.py:19-28`); `ThemeSemanticParityTest` re-derives all eight per preset and mode, and `ContextUsageSwatchInkTest` reads the painted pixels back |
| Every category gets `min-w-px`, so a zero-token one still shows a 1px sliver (`context-usage-panel.tsx:89`) | mobile-adaptation | A zero-token category paints no segment | `Modifier.weight` has no minimum-width floor, and the producer already filters `if tokens > 0` (`agent/context_breakdown.py:161`), so no such category reaches this client at the pin |
| Panel type is 12px medium / 11px (`context-usage-panel.tsx:38,42`) | mobile-adaptation | 17sp SemiBold title / 13sp body (`HermesTypography.kt:103,75`) | A desktop popover is read at arm's length with a pointer on it; the app's own type scale is what every other sheet uses, and 11px does not survive a phone at a metre |
| Meter hidden by default behind statusbar preference toggle | omission | Shown whenever context usage data exists | deferred: #73 — statusbar preference toggle surface is that issue; on mobile the meter is displayed when data is present |
| Statusbar item right-click context menu | omission | Absent | non-goal: mobile touch surfaces do not support desktop statusbar right-click context menus |

## Visual report

- pending: #73

Android half only, owed its Desktop side by #73:
`docs/parity/visual/context-usage/chat-top-bar-meter-dark/android/reference.png` with its
`contract.json` — the chat chrome with the compact pie meter beside the approval chip, dark, on `emulator-5554` from this branch's debug build.

## Executable evidence

| Claim | Test |
|---|---|
| Exact `compactNumber` scale thresholds (999.5, 999_950), glyph bar, and localized copy mapping | `ContextUsageFormatTest` |
| Every `var(--context-usage-*)` the Gateway sends resolves to its own ink; an unknown name, a malformed value and a null fall back to `textTertiary` | `ContextUsageFormatTest` |
| All eight inks re-derived from `styles.css:217-224` for every preset in both modes, and asserted distinct | `ThemeSemanticParityTest` |
| Gateway RPC `session.context_breakdown` parsing, the 16-category cap, negative clamping, missing keys, label redaction and the 40-char cap | `ContextBreakdownRepositoryTest` |
| A failed breakdown RPC keeps the last one; an endpoint switch empties the per-session cache; the read never opens a session that has no runtime | `ContextBreakdownRepositoryTest` |
| `session.info`, `session.usage` and `message.complete` all merge usage, absent keys keeping the last value | `ContextBreakdownRepositoryTest` |
| `gaugeUsage` overrides only the three context fields, never `total` or `model`; a breakdown with `context_max: 0` hides the meter | `ContextMeterViewModelTest` |
| Fifty transcript deltas on another session issue zero extra RPCs; a backend that answers nothing is asked once; each turn end re-reads exactly once | `ContextMeterViewModelTest` |
| Compose rendering of the compact top-bar meter, the figures it speaks, the no-context-window fallback, its spoken name, click opens `ContextUsageSheet`, the pinned category labels and counts | `ContextUsageJourneyTest` under Robolectric |
| The meter and the approval chip keep their full width on a 360dp status line while the connection line ellipsises, and neither inflates the line's height | `ChatTopBarAlignmentTest` under Robolectric |
| Each swatch and each bar segment paints its own context-usage ink, read back from the drawn window | `ContextUsageSwatchInkTest` under Robolectric, native graphics |
