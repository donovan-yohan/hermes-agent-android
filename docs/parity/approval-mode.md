# Approval mode: source and divergence ledger

Hermes Desktop's **Approval mode** status-bar item and its three-state menu,
ported per [`docs/workflows/port-desktop-surface.md`](../workflows/port-desktop-surface.md):
`data/gateway/ApprovalMode.kt`, `data/gateway/GatewaySessionRepository.kt`,
`ui/chat/ApprovalModeControl.kt`, `ui/common/HermesIcons.kt`,
`ui/chat/ChatViewModel.kt` and `ui/chat/ChatScreen.kt`.

## Pin

| Source | Pin | Read via |
|---|---|---|
| Desktop renderer, Gateway RPC, CLI | `hermes-agent` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3` | read-only checkout; every citation taken with `git show <sha>:<path>` |

Every `path:line` below is against that SHA.

## Paths that settled the port

| Question | Path |
|---|---|
| The item, its glyph states, its menu and the row order | `apps/desktop/src/app/shell/approval-mode-menu.tsx:21-76` |
| Where the item lives and when it is hidden | `apps/desktop/src/app/shell/hooks/use-statusbar-items.tsx:270,568-572` |
| Where it sits among the other right-hand items, and that array order is left to right | `apps/desktop/src/app/shell/hooks/use-statusbar-items.tsx:536-586`, `apps/desktop/src/app/shell/statusbar-controls.tsx:119-123` |
| The RPC contract, the optimistic write, the rollback and the revision fence | `apps/desktop/src/store/approval-mode.ts:1-97` |
| Every visible string | `apps/desktop/src/i18n/en.ts:2897-2906` |
| `config.get` answering `approvals.mode`, and that the handler is profile-scoped | `tui_gateway/methods_config.py:181-182,290-294` |
| `config.set` validating the enum, writing it, and re-emitting `session.info` | `tui_gateway/server.py:14225-14226,14584-14598` |
| What `profile` does to either handler | `tui_gateway/server.py:2463-2482` |
| The three valid modes, and that an unknown one resolves to `manual` | `hermes_cli/approval_mode.py:16`, `tools/approval.py:3405-3432`, `tui_gateway/server.py:5953-5971` |
| `session.info` carrying `approval_mode` and the effective `yolo` | `tui_gateway/server.py:7616-7631,7659-7660` |
| Which streamed `session.info` may reconcile the cache, and why it is gated | `apps/desktop/src/app/session/hooks/use-message-stream/gateway-event/session-info.ts:184-191` |
| The separate YOLO bypass this port does not take | `apps/desktop/src/lib/yolo-session.ts:1-76`, `tui_gateway/server.py:14600-14665` |

## State classification

| Desktop state | Where it comes from | Android |
|---|---|---|
| `mode` | `config.get {key: 'approvals.mode'}`, then the `config.set` echo | `ApprovalModeState.mode` on the repository's `approvalMode` flow; null until answered |
| unresolved mode before the first read | local default `'smart'` (`store/approval-mode.ts:32`) | null, and the control is not rendered |
| revision fence and confirmed value | `revisions` / `confirmedModes` maps (`:7-8`) | `approvalModeRevision` / `confirmedApprovalMode`, guarded by the repository's state lock. A reconcile bumps the fence and so discards an in-flight write's echo, exactly as `reconcileApprovalModeForProfile` does (`:40-48`) |
| `hidden` | `gatewayState !== 'open'` (`use-statusbar-items.tsx:569`) | `ChatUiState.approvalMode` is null unless the connection is `Connected` |
| `yolo` | a separate status item over `config.set {key: 'yolo'}` | `ApprovalModeState.bypassActive` is parsed from `session.info` and rendered nowhere |
| profile keying | client-side cache key only; neither RPC sends `profile` (`:56,77-80`) | the active profile is sent on both calls, and a change of active profile clears the published mode, the confirmed value and the fence together |

## Mobile adaptation ledger

| Desktop | Android | Reason |
|---|---|---|
| Status-bar item in the Electron window footer | Chip in the chat top bar's subtitle row, after the context meter | A mobile layout has no desktop footer bar; this is the placement `docs/parity/context-usage.md` already settled for a status-bar-sourced control, and the epic rules out a literal bottom status bar. The order is Desktop's own: `coreRightStatusbarItems` runs `context-usage`, `session-timer`, `approval-mode` (`use-statusbar-items.tsx:547-572`) and is laid out in a plain flex row (`statusbar-controls.tsx:119-123`) |
| `DropdownMenuRadioGroup` inside a `w-72` dropdown | The same `DropdownMenu` primitive the session actions menu uses, 280dp wide, painted from `HermesTheme.tokens` | One menu primitive for the app; the width is Desktop's `w-72` at the phone type scale |
| Tabler `IconBolt` / `IconBoltFilled` at `size-3.5` (`lib/icons.ts:125-126`) | `ZapGlyph`, Tabler's own `bolt` polygon drawn at 14dp, stroked or filled | Codicons 0.0.45 — the font every other glyph here comes from — ships no bolt; filling the same polygon keeps outline and filled provably one silhouette |
| Menu row is 12px label over an 11px description | 12sp label over 10sp description inside a 48dp row | The app's own type scale and touch floor; a pointer row has no 48dp minimum to honour |

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Approval item lives in the desktop window footer status bar | mobile-adaptation | Chip in the chat top bar subtitle row | A mobile layout has no desktop footer; the top bar is where connection-scoped status already lives, and the epic excludes a literal bottom status bar |
| Menu is a hover-and-click dropdown anchored to a footer item | mobile-adaptation | Tap-anchored dropdown from a 48dp chip | Touch has no hover, and every menu in this app opens on tap from a control that meets the touch floor |
| Neither `config.get` nor `config.set` sends `profile`, so both always act on the launch profile's config (`store/approval-mode.ts:56,77-80`) | mobile-adaptation | Both calls carry the active profile | Both handlers are `@_profile_scoped` (`methods_config.py:181-182`, `server.py:14225-14226`) and this app scopes every session RPC to the profile the rail is in; reading one profile's approvals while writing another's would be worse than either |
| The item shows `smart` before the first `config.get` answers (`store/approval-mode.ts:32`) | mobile-adaptation | Nothing is shown until the host answers | A phone chrome offers one glance, not a hover tooltip that corrects it: a control naming a security posture must not name one it is guessing |
| Streamed `session.info` reconciles only when the event is the active session's, carries its own `profile` stamp, and came from the active source (`session-info.ts:189`) | mobile-adaptation | It reconciles only while the app is scoped to the Gateway's launch profile | The same concern — "config is profile-scoped, but session.info also arrives for background sessions" (`session-info.ts:184-188`) — with the only gate this transport affords: the events here carry no renderer profile stamp, and `_session_info` resolves the mode under whichever `HERMES_HOME` is bound at emit time (`server.py:5953-5971`), which for every emit but `config.set`'s own is the launch profile |
| The YOLO bypass is reachable from the command palette and the `/yolo` slash command (`lib/yolo-session.ts:1-76`); nothing under `app/shell` renders it as a status item at this pin | omission | `session.info.yolo` is parsed; nothing renders it and nothing can turn it on | non-goal: a global approval bypass on a phone is a one-tap irreversible security change with no keyboard modifier to gate it, and this app ships neither a command palette nor slash-command entry for it; Desktop's own `yoloOn`/`yoloOff` strings (`en.ts:2982-2983`) are already dead here |
| Settings → Safety → `Approval Mode` enum row (`settings/constants.ts:235,418,580,670`) | omission | Absent | deferred: #73 — the schema-driven settings sections are that issue; the control ships in the chrome where the status-bar item was adapted |
| Status-bar customise menu can hide the Approvals item (`en.ts:2941`) | omission | The chip is always shown once the mode is known | deferred: #73 — the status-bar preference surface is that issue |
| `DropdownMenuLabel` heading is sentence-case 12px medium tertiary (`components/ui/dropdown-menu.tsx:183-198`) | mobile-adaptation | `MenuSectionLabel`: the same words in this app's uppercase, tracked, semibold panel-label treatment, in a 32dp band | A phone menu is read at ~35cm with a thumb over it, and 12px sentence case does not separate a heading from the row under it at that distance; `panelLabel` is what a heading over a list already is in this app (`ui/sessions/SessionList.kt`), so one heading composable now serves both menus rather than each drifting on its own |
| The heading shares its left inset with the words in its rows, because the selected mark is trailing (`components/ui/dropdown-menu.tsx:169-179`) | mobile-adaptation | The heading is inset to the rows' text column, past the leading mark | Touch: this app's selected mark leads the row rather than trailing it (the row above), which moves the rows' text column right; keeping Desktop's *relationship* — heading on the same column as the words — is what preserves the read, and `ApprovalModeJourneyTest` asserts the two columns are the same |
| Menu row shows a radio dot only via the shadcn radio-group indicator | mobile-adaptation | An 8dp accent dot plus `Role.RadioButton` and a `selected` semantics flag | Touch surfaces are read by screen readers that need the selection state on the row, not only its ink |
| A `config.set` echo with no `value` normalises to `manual` like any other unknown (`store/approval-mode.ts:82`) | mobile-adaptation | An accepted write whose echo omits `value` confirms the mode that was written | The phone chip is the only place the posture is named — there is no second surface to correct it — so a write the host accepted must not appear to revert; the Gateway always echoes `value` at this pin (`server.py:14598`), which makes this the failure-shape guard, not a behaviour change |

## Visual report

- pending: #73

Android half only, owed its Desktop side by #73:
`docs/parity/visual/approval-mode/approval-mode-menu-light/android/reference.png` with its
`contract.json` — the open menu with its `APPROVAL MODE` heading, light, on `emulator-5554` from this branch's debug build.

## Executable evidence

| Claim | Test |
|---|---|
| The three modes, the menu order, and that any unknown wire value resolves to `manual` | `ApprovalModeRepositoryTest` |
| `config.get` and `config.set` carry the key, the value and the active profile, and omit `profile` on the default scope | `ApprovalModeRepositoryTest` |
| The chosen mode paints before the Gateway answers, and the echo confirms it | `ApprovalModeRepositoryTest` |
| A `4002` refusal and a transport failure both roll back to the last confirmed mode, and to "unknown" when nothing was ever confirmed | `ApprovalModeRepositoryTest` |
| A streamed `session.info` reconciles the mode and the effective bypass, and a named profile scope ignores one that reports the launch profile | `ApprovalModeRepositoryTest` |
| An endpoint switch forgets everything the previous host said | `ApprovalModeRepositoryTest` |
| The control is hidden until the mode is known and while the Gateway is not connected; connecting reads it once; re-picking the selected mode writes nothing; a refusal raises the notice | `ApprovalModeViewModelTest` |
| A profile-scope change drops the previous profile's answer: nothing is shown until the new scope's `config.get` answers, and a failed read leaves it showing nothing | `ApprovalModeRepositoryTest` |
| A launch-profile `session.info` racing that scope change cannot repaint the profile just left: the scope test and the publish are one critical section | `ApprovalModeRepositoryTest` |
| An accepted write whose echo omits `value` keeps the mode that was written rather than confirming Manual | `ApprovalModeRepositoryTest` |
| The chip's label and Desktop's `Approval mode: {mode}` spoken name; the menu title; three rows in Desktop's order with every label and description verbatim; picking a row writes that mode; the chip sits to the right of the context meter | `ApprovalModeJourneyTest` under Robolectric |
| The menu heading is uppercase, vertically centred in its band, and on the same text column as the rows' words | `ApprovalModeJourneyTest` under Robolectric |
| The chip's 48dp touch band overflows the status line instead of heightening it, and the chip keeps its full word on a 360dp line | `ChatTopBarAlignmentTest` under Robolectric |
