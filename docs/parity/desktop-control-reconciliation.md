# Nested Desktop control reconciliation — bounded source audit

## Pin

Desktop source: `81a55437f3f31a9f12a30c53b6c5d49d420898aa`.
Android implementation baseline: `65080e8a37e8d546869528e43c162503015b94c7`.
These are analysis revisions, not replacements for historical visual/source pins.

## Evidence layers

The [family inventory](desktop-surface-audit.md) remains the ownership index.
This continuation adds three separate artifacts:

- [Reviewed control records](desktop-control-records.json): 295 source-read operation/entrypoint/control templates (83 added in the session/Skills/MCP continuation), with exact immutable UI and handler/state/transport spans, mount predicates, Android baseline citations, status, parity page and existing issue. Repeated entrypoints are recorded separately, not counted as separate features. A route entry proves access to a destination, not implementation of everything inside it.
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

All upstream spans in this section use the Desktop SHA in Pin. Every control record separately carries that full SHA. Its Android spans use the baseline SHA in Pin. Twelve upstream test-reading receipts are kept separately from shipping-control evidence; they were read, not executed.

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

## Session, Skills and owned-MCP source continuation

This increment appends 83 hand-read templates without rewriting the first 212
records. The baseline remains Desktop `81a55437f3f31a9f12a30c53b6c5d49d420898aa`
and Android `65080e8a37e8d546869528e43c162503015b94c7`. It does not audit or
repin a later main. All upstream citations in this section are at that Desktop
SHA; all Android citations are at that Android baseline.

### Mounted branches and authority followed

- The shared session menu is actually mounted by both the row kebab and context
  menu (`apps/desktop/src/app/chat/sidebar/session-row.tsx:329-370`), and by the
  tab wrapper (`apps/desktop/src/app/chat/session-tile.tsx:890-914`). New tab is
  row-only and disappears for an already-tabbed session. Window requires the
  Electron bridge; own-terminal handoff additionally excludes remote mode
  (`apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:298-360`). Tab
  Reload changes a remount epoch; close-family targets exclude uncloseable and
  hide-only siblings. They do not delete conversations
  (`apps/desktop/src/components/pane-shell/tree/store.ts:702-766`). Existing
  Android tab/window non-goals remain historical decisions under #73, not a
  newly invented reason to exclude Branch, Export or workspace moves.
- Rename's input, save/cancel, unchanged/blank title, focus restoration and
  busy/error branches were read, including the runtime lookup through selected
  main, tile and session-state map, owner-routed `session.title` and REST
  fallback. The result updates both recents and project caches
  (`apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:95-154,363-381,613-630,736-819`).
  Android has the corresponding RPC/REST ladder, but this is bounded behavioral
  coverage, not proof of identical multi-connection resolution or dialog focus
  (`app/src/main/kotlin/com/hermesagent/mobile/data/gateway/GatewaySessionRepository.kt:4542-4627`).
- Appearance mounts actual swatch and clear buttons only inside the open
  submenu; overrides use durable lineage ids and clear falls back to project
  color (`apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:192-210,558-566`;
  `apps/desktop/src/components/ui/color-swatches.tsx:24-47`;
  `apps/desktop/src/store/session-color.ts:9-48`). Move refreshes the project
  tree on open, excludes current/Home/folderless targets and dispatches
  `session.workspace.move`, including the busy refusal
  (`apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:219-258,579-587`;
  `apps/desktop/src/store/projects.ts:791-822`). Disabled parent rows do not
  certify either nested editor on Android.
- Stored Branch follows the owning connection/profile and a stable attempt key;
  missing-method fallbacks differ from arbitrary RPC failure. Export reads
  oldest-first pages including compacted messages, stops legacy unpaginated
  reads and refuses over its JSON-character limit
  (`apps/desktop/src/app/contrib/wiring.tsx:1099-1109`;
  `apps/desktop/src/app/session/hooks/use-session-actions/index.ts:2641-2750,3023-3066`;
  `apps/desktop/src/lib/session-export.ts:29-58`;
  `apps/desktop/src/api/sessions.ts:668-712`). These remain baseline WIP rows;
  independent overnight feature branches are not silently counted here.
- Every current filter-menu option template and its visibility predicate was
  read: scoped grouping, conditional cost/manual ordering, Advanced-only row
  metadata/rail, card-preview dependency, host-PR capability, project-only
  show-all, status/profile/project/PR narrowing, all-profile escape, archived
  pool, customized-only reset and level-specific fold actions
  (`apps/desktop/src/app/chat/sidebar/filter-menu.tsx:116-232,255-452`). Setters
  and the common narrowing predicate were followed
  (`apps/desktop/src/store/layout.ts:850-960`;
  `apps/desktop/src/app/chat/sidebar/index.tsx:610-650`). This does not certify
  every sort comparator, project membership fallback or profile-create dialog.
- Installed Skills follows `CapabilitiesView` through the actual unified
  catalog's installed-action/detail adapters, not the unused older facet-rail
  component. Individual/bulk changes serialize against one backend disabled
  list; bulk means the entire profile, not the search results. Detail reads
  the full body; editing/archive permits agent and external provenance, not
  bundled/hub; external origin is not a read-only policy. Editor requests use
  mount/request fences, save uses the captured scope, and archive optimistically
  removes/restores only its own row
  (`apps/desktop/src/app/capabilities/index.tsx:130-188`;
  `apps/desktop/src/app/capabilities/catalog/skill-catalog.tsx:269-322`;
  `apps/desktop/src/app/capabilities/skills/skills-tab.tsx:35-221,237-360`;
  `apps/desktop/src/app/capabilities/skills/skill-detail.tsx:24-64`;
  `apps/desktop/src/app/capabilities/skills/skill-provenance.ts:26-31`;
  `apps/desktop/src/app/learning/archive-skill-confirm-dialog.tsx:12-25,55-76`;
  `apps/desktop/src/api/skills.ts:14-43,62-89`). Android's bounded Bot list/toggle
  and WIP details/bulk/archive do not implement a general Capabilities page.
- The active catalog filter strip, overflow and expanded popovers, installed
  filter, category/source/tag templates, search, view mode, local More, retry
  and narrow-list Back were followed to their state setters
  (`apps/desktop/src/app/capabilities/catalog/catalog-browser.tsx:136-226,325-388,439-541`;
  `apps/desktop/src/app/capabilities/catalog/catalog-filter-bar.tsx:33-105`;
  `apps/desktop/src/app/capabilities/catalog/use-catalog-filters.ts:13-39`).
  `catalog-filters.tsx` was read but is not mounted by this browser and supplies
  no shipping control record. Discovery shelves, detail metadata links,
  comparator/query internals and install action completion remain unverified.
- Owned local MCP's Advanced subtree is supplied only for an installed,
  non-plugin local server. The native disclosure actually mounts JSON, log and
  Remove children. JSON adds the imported Format button; Save replaces the
  complete map, mirrors dirty drafts and attempts reload. This is not the
  bounded Android named-enabled toggle
  (`apps/desktop/src/app/capabilities/connectors/connectors-tab.tsx:206-219,259-266`;
  `apps/desktop/src/app/capabilities/connectors/local-dialog.tsx:45-83`;
  `apps/desktop/src/app/capabilities/connectors/connector-dialog.tsx:199-206,371-408`;
  `apps/desktop/src/app/capabilities/connectors/local-slots.tsx:17-35,45-69`;
  `apps/desktop/src/app/capabilities/mcp/mcp-editor.tsx:21-98`;
  `apps/desktop/src/app/capabilities/mcp/use-mcp-servers.ts:228-287,316-396`;
  `apps/desktop/src/api/mcp.ts:36-49`). No private config or actual server was read.

### Findings which a label-only inventory misses

1. Current Desktop swaps Archive to Unarchive and dispatches to the restore
   path. The historical Android menu ledger's “Desktop never swaps” sentence
   belongs to its older pin; it is not true of this source
   (`apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:515-531`;
   `apps/desktop/src/app/contrib/wiring.tsx:1085-1095`). The frozen native menu
   remains unconditional at
   `app/src/main/kotlin/com/hermesagent/mobile/ui/sessions/SessionActionsMenu.kt:250-277`.
   Class: drift, #266.
2. Desktop's menu confirmation calls a void callback; the shared dialog's done
   beat therefore proves dispatch, not completion of the async delete. The
   actual handler interrupts a live runtime, releases blocking input, closes it,
   then deletes on the owning route; it rolls back on refusal
   (`apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:632-641`;
   `apps/desktop/src/app/contrib/wiring.tsx:1102-1109`;
   `apps/desktop/src/components/ui/confirm-dialog.tsx:97-139`;
   `apps/desktop/src/app/session/hooks/use-session-actions/index.ts:3150-3255`).
   Android refuses live deletion instead of performing that interrupt sequence
   (`app/src/main/kotlin/com/hermesagent/mobile/data/gateway/GatewaySessionRepository.kt:4629-4658`).
   Class: drift, #266; source finding, not permission to change the policy here.
3. “Mark all as read” is not the same writer. Desktop only clears transient
   finished-unread paint
   (`apps/desktop/src/store/session.ts:1522-1539`); Android enumerates in-scope
   rows and sends per-row backend watermark updates
   (`app/src/main/kotlin/com/hermesagent/mobile/ui/chat/ChatViewModel.kt:2781-2797`;
   `app/src/main/kotlin/com/hermesagent/mobile/data/gateway/GatewaySessionRepository.kt:4766-4794`).
   Class: drift, #266. A matching item label is insufficient parity evidence.
4. Rename Cancel/input are disabled during submission in both, but Desktop's
   imported Close X remains enabled and the dialog passes an unguarded
   `onOpenChange`; Android blocks dismissal while submitting. The disabled
   input's Escape handler is not claimed reachable during that state
   (`apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:787-814`;
   `apps/desktop/src/components/ui/dialog.tsx:72-98,155-156`;
   `app/src/main/kotlin/com/hermesagent/mobile/ui/sessions/RenameSessionDialog.kt:108-110,179-189`).
   Class: drift, #266.
5. MCP config is capability-scoped, but its imported log hook reads
   `activeGatewayProfile` and calls `getLogs` without the selected capability
   scope. Thus a fixed remote-profile config editor is not by itself proof its
   logs show that owner's output
   (`apps/desktop/src/app/capabilities/mcp/mcp-logs.tsx:34-53`). This is an
   upstream authority concern requiring follow-up under #266/#73, not an invented
   Android endpoint. Log search also imports conditional Clear, hit navigation,
   Copy and top/bottom actions; the records include those actual child paths.

## Remaining semantic coverage debt

The machine inventory is not exhaustive yet. In particular the following still require complete mounted-tree and callback reading, not a signal scan:

- Session menu supplying owners outside row/tab wrappers (header/Bot canonical variants), full tab close/re-home consequences, pin-sync reconciliation beyond its read contract, profile-create/import completion and archive-pool failure lifecycle; full Sidebar project menus, search merge, native gestures and comparators.
- All Appearance rows and their actual store setters, provider/OAuth/key/custom-endpoint forms, host-local model subtrees, vault sources and billing/step-up states. These Settings children are still provisional unverified omissions under #73/#266, not implementation certified by the generic dispatcher.
- Kanban's complete board/drawer menus/forms, Bots roster/identity/editor children, routines/group/Screen subtrees and plugin-specific contributions.
- Capabilities scope-selector resolution, full public catalog query/comparator/discovery/detail-link/install/update completion, toolset/provider forms, Agent-plugin package/schema children, hosted connector accounts/OAuth/directory/probe/per-tool/Add dialogs. Only the installed Skills management and owned local-MCP Advanced paths described above were followed. The standalone `McpEditorPane` and older `CatalogFilters` exports are not counted merely because their definitions exist.
- Messaging/pairing; Cron blueprint/delivery/history branches; Agents transcript controls; Starmap and Artifacts nested viewers.
- Composer completion/contribution/status children outside the structured-control tree, transcript renderers/required-input specialization, notifications outside Settings, and Files/Terminal/Review/browser/native onboarding subtrees.
- Generic primitives' conditional child affordances outside the named imports, factory aliases beyond explicit React calls, data-driven callback registries, native OS-supplied role submenus, and runtime-loaded extension contents. Runtime/private schema rows cannot be enumerated by reading private installed data; their templates and authority seams need review instead.

### Registry coverage is still provisional

Each registry candidate now explicitly carries
`unverified-omission-requires-reachability-and-handler-review`, with its existing
family parity pages and issue owner. This is a conservative unverified-omission
classification under migration #73/drift #266, not a positive assertion that
Android lacks every syntactic node. An overlapping reviewed span does not
resolve callback forwarding or conditional mounting, so it cannot promote that
status. The original 3283-candidate snapshot is unchanged.

| Registry roots | Bounded reading already recorded | Remaining obligation |
|---|---|---|
| Routes / sidebar | Route dispatch; current filter templates; row and tab action kits | Destination children; sidebar project/search/native trees; owning action lifecycles |
| Settings navigation / manifest / schema / subpages (including appearance/config/other) | Rail/footer, generic dispatcher and earlier plugin-schema templates | Every specialized row/editor/setter, especially providers/models, connections, vault and billing; zero-entry subpage roots still require their imported children |
| Statusbar / palette / keybinds | Earlier ownership census only; structured stack is a different subtree | Trace each actual registration's enable gate and handler, including portable plugin contribution kernels |
| Native menu / window menu | macOS startup gate and common callbacks/roles | OS-supplied role children and portable-operation descendants not inspected above |
| Contribution root / plugin host / discovery / settings | Shipping glob, root ownership and earlier Agent settings templates | Each contribution's actual owner, mount and disposal; no dynamic runtime contents assumed |
| Bundled Bots / Kanban / Radio | Radio lifecycle and controls from prior increment | Bots/Kanban full nested trees, and Radio descendants beyond those explicit spans |

The structured records cover 22 families only in these bounded senses. Their
295 entries classify as 37 implemented-bounded, 15 WIP, 224 omission, 11
standing non-goal and eight drift. These are template/entrypoint records, not
feature totals, and not a claim that the remaining families are empty.

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

The continuation ran 36 focused inventory tests with no failures/skips. Three
new record-regression assertions protect the hand-read findings and provisional
registry status; they are scaffold, not independent semantic proof. AST equality,
21-root reconciliation, immutable source/native span bounds and the unchanged
2133-file family ledger pass. Parity-page and copy gates pass. The general
`verify-pin-citations.py --check-range` gate exits 2 on this increment: it binds
some mixed Android/Desktop prose and JSON identity strings to Android revisions
which the upstream clone cannot read (including the pre-existing approved-mobile
foundation in the MCP page). No fetch of those as upstream revisions, source
restamp or false success is used to hide that attribution limit. Structured
JSON citations are checked by their explicit per-record revision; the Markdown
range gate remains a separate Concern for parent review. Exhaustive-semantic
acceptance still deliberately exits 1.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Log search previous/next match actions | omission | No matching controls in baseline System logs placeholder | pill-owed: #127; exact spans and mounted predicates above |
| Maintenance and general plugin/schema management | omission | No corresponding management destination/control trees | pill-owed: #73; reviewed records separate specific operations and authorities |
| Notification plugin-kind and completion-sound markers required by #99 | drift | Ledger calls them excluded but the baseline screen has no marker | #99; preserve the existing policy conflict until reconciled |
| Current-source Archive/Unarchive, live deletion and busy rename dismissal | drift | Historical baseline differs from the later source's actual handlers | #266; later-source section and immutable structured records; no historical packet restamp |
| Current-source Mark all as read writer | drift | Per-row backend watermark writes rather than only transient-dot clearing | #266; exact Desktop/Android writer spans in the continuation |
| Nested Skills editing/archive and owned-MCP JSON/log controls | omission | Parent WIP labels or narrower selection only, no corresponding nested writers | pill-owed: #73; source-read child templates do not count as delivered features |
| Arbitrary runtime Desktop JavaScript loading | omission | Compiled Kotlin plugins only | non-goal: ADR 0003 and #166 explicitly reject runtime JavaScript loading; Agent management is a different capability |

## Visual report

No production UI was changed or captured. These are source findings, not a rendered parity approval. Existing captures keep their original pins. Pending rendering remains Concern under the existing owning pages/issues:

- pending: #99 — notification Settings/shade evidence, including the marker-policy conflict
- pending: #216 — existing Bots roster comparison, not certified by this audit
