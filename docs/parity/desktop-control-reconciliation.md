# Nested Desktop control reconciliation — bounded source audit

## Pin

Desktop source: `81a55437f3f31a9f12a30c53b6c5d49d420898aa`.
Android implementation baseline: `65080e8a37e8d546869528e43c162503015b94c7`.
These are analysis revisions, not replacements for historical visual/source pins.

## Evidence layers

The [family inventory](desktop-surface-audit.md) remains the ownership index.
This continuation adds three separate artifacts:

- [Reviewed control records](desktop-control-records.json): 212 source-read operation/entrypoint/control templates, with exact immutable UI and handler/state/transport spans, mount predicates, Android baseline citations, status, parity page and existing issue. Repeated entrypoints are recorded separately, not counted as separate features. A route entry proves access to a destination, not implementation of everything inside it.
- [Syntax census](desktop-control-candidates.json): 3283 syntax candidates across production-source candidates, extracted using TypeScript 5.9.3 without evaluating upstream modules. JSX attributes, compiled `jsx`/`jsxs`/`createElement` props, action objects, native roles, registrations, Settings manifest properties and dynamic spreads are separate kinds. File/name/import references are navigation aids, not a resolved interprocedural call graph or proof of mounting.
- [Registry reconciliation](desktop-registry-reconciliation.json): 21 root registries joined to their actual syntax entries, source hashes, existing parity/issue owners and overlapping reviewed UI spans. An overlap never upgrades a candidate to semantic acceptance. The root counts are not control or feature counts.

All three deliberately retain `semantic_complete: false`. This is an incremental semantic audit, not the mission's exhaustive-acceptance receipt. Structural success must not be used to close #73, #184 or #266.

## What was actually followed

The reviewed records cover these bounded trees rather than merely renaming source prefixes:

- All twelve `APP_ROUTES` destinations, query stripping, workspace-fronting and contributed-route reservation (`apps/desktop/src/app/routes.ts:64-77,194-214,237-257,291-318`). Destination coverage is explicitly narrower than feature coverage.
- Command Center navigation, sessions search/open/pin/export/delete/load-more, Usage period/retry, System restart/update/log file/level/search, and the imported log-search match navigation (`apps/desktop/src/app/command-center/index.tsx:147-352,354-588`; `apps/desktop/src/components/chat/log-search.tsx:26-39,54-103`).
- Maintenance doctor/security audit/backup/debug share, per-returned-link copy, curator pause/resume/run, and memory/user reset with confirmation, size/busy gates and action-tail state (`apps/desktop/src/app/command-center/maintenance.tsx:69-193,204-347,386-419`; `apps/desktop/src/api/system.ts:107-179,270-313`). No maintenance operation or upload was invoked.
- Settings rail and footer, including nested Billing/Providers/Keys, capability-gated plans, launch-flag-gated host-local models, generated subpages, config export/import/reset, and the legacy Connections alias (`apps/desktop/src/app/settings/index.tsx:72-99,133-241,244-434,487-536`).
- The generic config dispatcher in actual precedence: structured fallback, boolean, searchable select, open-world combobox, closed select, number, list, object JSON, long text, short text. Lists commit on blur/Enter; JSON retains the last valid object. Drafts save serialized diffs to the captured read owner, not an arbitrary newly active host (`apps/desktop/src/app/settings/config-field.tsx:108-297`; `apps/desktop/src/app/settings/config-settings.tsx:120-190,216-313`; `apps/desktop/src/api/config.ts:48-85`). This does not certify every specialized Model/Voice/Memory child editor.
- Agent plugin schema boolean/enum/JSON/number/secret/string templates and Save. Settings values and secret replacements have different writers; sequential secret writes are not an atomic config transaction (`apps/desktop/src/app/settings/plugin-settings-form.tsx:112-151,155-279,281-422`; `apps/desktop/src/app/settings/plugin-settings.tsx:334-389`; `apps/desktop/src/store/agent-plugins.ts:483-552`). Only schema templates were read, never installed plugin/credential rows. #234's existing list/toggle scope does not silently acquire the whole schema port; the remainder stays under #73.
- Notifications master, all seven fixed kind identifiers, sound selection/preview and test delivery (`apps/desktop/src/app/settings/notifications-settings.tsx:36-137`; `apps/desktop/src/store/native-notifications.ts:28-36,89-105`). Android's two additional kinds remain Android-owned, not invented Desktop entries.
- Native Application/File/Edit/View/Window/Help branches, roles, callbacks and platform predicates (`apps/desktop/electron/main.ts:6487-6622`; `apps/desktop/electron/window-menu.ts:7-29`). Native menu omission is not automatically a permanent non-goal for every portable operation behind it.
- Foreign-session import: refresh/source/search/row selection/page/back/retries/continue-or-reopen, captured owner, abort-on-close, and exact RPC dispatch (`apps/desktop/src/app/session-import/index.tsx:28-111,114-318`; `apps/desktop/src/app/session-import/api.ts:35-49`). Search filters loaded rows; merely previewing does not import.
- Webhooks list/detail/menu/create/receipt/delete/receiver/restart and all creation inputs, including the different enable guard on empty-state creation versus the populated-list Add action (`apps/desktop/src/app/webhooks/index.tsx:84-368,389-620,624-685`; `apps/desktop/src/api/messaging.ts:149-200`). Dynamic rows are templates, not actual subscriptions or secrets.
- Bundled Radio's default-off lifecycle, statusbar/popover entrypoints, station search/play/pin, next/mute/volume, website and artist links, client playback authority and teardown (`apps/desktop/src/plugins/radio/plugin.js:94-139,157-366,455-552,558-678,684-697`). Compiled React factory calls are shipping controls here; a JSX-tag-only inventory loses them.
- Structured goal/criteria/loop/heartbeat menu and inline branches, disclosures, details/add/confirmation dialogs, copy/remove/clear, active/paused/waiting/done transitions and error dismissal (`apps/desktop/src/app/chat/composer/status-stack/session-control-goal.tsx:73-146,185-275,287-640`; `session-control-loop.tsx:68-85,106-242` and `session-control-heartbeat.tsx:59-76,94-201` in the same directory; `apps/desktop/src/store/session-control.ts:754-882`). Goal resume/unwait can dispatch a hidden continuation; a busy session queues it rather than pretending the backend mutation failed. Menu Add Criterion is hidden for done goals, while the expanded criteria-header Add remains present: those are not interchangeable predicates.

All upstream spans in this section use the Desktop SHA in Pin. Every control record separately carries that full SHA. Its Android spans use the baseline SHA in Pin. Seven upstream test-reading receipts are kept separately from shipping-control evidence; they were read, not executed.

## New baseline findings

1. Recent logs is more than the four file labels, four levels and a field. A nonempty search mounts previous/next match actions, disabled when there are no hits. The imported `LogTail` additionally mounts Copy and Jump to top/bottom, including loading/empty states, and `SearchField` adds Clear only for a nonempty, nonloading value (`apps/desktop/src/components/chat/log-tail.tsx:90-131`; `apps/desktop/src/components/ui/search-field.tsx:114-128`). Android `SystemScreen.kt:194-231` at the baseline has only the disabled block, tabs and placeholder; it does not mount those nested actions. Their omission belongs to #127. A future logs feature must not count painted labels as complete search navigation.
2. Command Center Maintenance includes curator and builtin-memory maintenance as well as the four common operations. Android's `SystemActions` at `app/src/main/kotlin/com/hermesagent/mobile/ui/system/SystemScreen.kt:34-42` exposes none of these. They remain host-capability omissions under #73, not Android filesystem operations.
3. The notifications baseline ledger and #99 disagree: `notifications.md:249-250` excludes plugin-kind/sound controls, while the final “Parity mandate” in #99 requires visible disabled markers for plugin kind and the completion-sound picker. Current `NotificationsScreen.kt:101-168` and `NotificationKind.kt:51-58` still omit them. The control records flag this as drift/policy conflict under #99; this audit does not resolve the conflict by approving a non-goal or inventing evidence.
4. Radio is one of the actual three bundled plugin roots, but `hello-runtime/plugin.runtime.js` is not matched by the shipping glob. Default-disabled is conditional shipping code, not example code. Android's `plugins/BundledPlugins.kt:13-22` includes Bots, Groups and Kanban, not Radio.
5. Settings Plugins again has a live schema-page role at this Desktop SHA. Historical consolidation prose in `settings-plugins.md` describes its earlier pin and must not be read as a claim about the current Settings rail. General plugin schema editing remains missing on Android even if separate Agent list/toggle work lands.

6. Native menu factories contain nonmac branches, but the shipping startup installs an application menu only on macOS, after the first window (`apps/desktop/electron/application-menu-startup.ts:28-34`; `apps/desktop/electron/main.ts:18121-18126`). The nonmac `window-menu.ts:21-28` Close factory branch is therefore not a currently mounted shipping menu control and is excluded from reviewed control records. The syntax ledger still records it as an uncertified source candidate. Merely finding a factory branch is not proof of reachability.

Android filenames in findings 1–3 resolve under `app/src/main/kotlin/com/hermesagent/mobile/`; the machine records contain full paths and immutable baseline citations. No separate candidate feature branch is reclassified as baseline implementation.

## Source and shipping boundary

The new syntax pass accounts for 3800 source paths: 2124 production-source candidates, 1669 test/fixture paths, six development-support paths and one runtime example. The older 2133-file source-ownership census is retained unchanged: it intentionally included support/example files. Neither file total is a shipping-control count.

Only production-source candidates enter the AST control ledger. Tests/specs, explicit fixture/test utility trees, billing dev/simulated support and the runtime example are separately classified. Shared primitives, API wrappers, stores, declarations and exports still appear as production-source candidates, never automatically as mounted controls. An AST node containing an event prop can be a reusable wrapper, a callback passthrough, a dormant component or a real mounted control. Handler bindings can be shadowed; same-name local declarations are deliberately not treated as typechecked resolution.

The registry join includes route/sidebar, Settings navigation/manifest/schema/subpages, statusbar, palette/keybind, native menu/window menu, core contribution, plugin host/discovery/settings and all three actual bundled roots. A root with no syntax entry still retains its immutable source hash; zero is not proof that it has no dynamic controls.

## Remaining semantic coverage debt

The machine inventory is not exhaustive yet. In particular the following still require complete mounted-tree and callback reading, not a signal scan:

- Session action subdialogs and tab/window conditional branches; full Sidebar filters/project menus/search/native branches.
- All Appearance rows and their actual store setters, provider/OAuth/key/custom-endpoint forms, host-local model subtrees, vault sources and billing/step-up states.
- Kanban's complete board/drawer menus/forms, Bots roster/identity/editor children, routines/group/Screen subtrees and plugin-specific contributions.
- Full Capabilities/catalog/MCP/connector panels; Messaging and pairing; Cron blueprint/delivery/history branches; Agents transcript controls; Starmap and Artifacts nested viewers.
- Composer completion/contribution/status children outside the structured-control tree, transcript renderers/required-input specialization, notifications outside Settings, and Files/Terminal/Review/browser/native onboarding subtrees.
- Generic primitives' conditional child affordances, factory aliases beyond the explicit React calls, data-driven callback registries, native OS-supplied role submenus, and runtime-loaded extension contents. Runtime/private schema rows cannot be enumerated by reading private installed data; their templates and authority seams need review instead.

Every syntactic candidate has a family review owner and every reviewed control has an existing issue/page, but that is weaker than resolving these semantic gaps. Existing #73 owns the broader remainder; #184 owns its six specific stances; #266's requested old-to-new drift engine/workflow remains separate and is not delivered by this ledger. Independent inventory review and parent exact-head compatibility acceptance are still outstanding.

## Reproduce

Use a disposable checkout at the exact Desktop SHA and an isolated tooling directory. Do not install dependencies into upstream or installed Hermes.

```sh
npm install --prefix "$AUDIT_TOOLS" --ignore-scripts --no-audit --no-fund typescript@5.9.3
export NODE_PATH="$AUDIT_TOOLS/node_modules"
python3 scripts/desktop_control_inventory.py --upstream "$UPSTREAM" --verify-ast docs/parity/desktop-control-candidates.json --verify-reconciliation docs/parity/desktop-registry-reconciliation.json
python3 -m unittest discover -s scripts/tests -p 'test_desktop*inventory.py' -v
python3 scripts/desktop_surface_inventory.py --upstream "$UPSTREAM" --verify-source-ledger docs/parity/desktop-source-ownership.json
```

`--snapshot` and `--reconcile` emit deterministic artifacts to stdout; regeneration is reviewed, not automatic acceptance. Control validation checks page/issue shape and immutable Desktop/Android span bounds. AST fixtures require Node and TypeScript 5.9.3; on machines without the parser those fixtures explicitly skip, while committed-record tests still run. The audited run supplies the parser so those skips are not needed. No upstream test execution, Gradle, emulator or physical-device run is claimed by these commands.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Log search previous/next match actions | omission | No matching controls in baseline System logs placeholder | pill-owed: #127; exact spans and mounted predicates above |
| Maintenance and general plugin/schema management | omission | No corresponding management destination/control trees | pill-owed: #73; reviewed records separate specific operations and authorities |
| Notification plugin-kind and completion-sound markers required by #99 | drift | Ledger calls them excluded but the baseline screen has no marker | #99; preserve the existing policy conflict until reconciled |
| Arbitrary runtime Desktop JavaScript loading | omission | Compiled Kotlin plugins only | non-goal: ADR 0003 and #166 explicitly reject runtime JavaScript loading; Agent management is a different capability |

## Visual report

No production UI was changed or captured. These are source findings, not a rendered parity approval. Existing captures keep their original pins. Pending rendering remains Concern under the existing owning pages/issues:

- pending: #99 — notification Settings/shade evidence, including the marker-policy conflict
- pending: #216 — existing Bots roster comparison, not certified by this audit
