# Desktop surface and control audit — 2026-10-22

## Pin

Inspected Desktop source: `81a55437f3f31a9f12a30c53b6c5d49d420898aa` (NousResearch/hermes-agent).
Android reconciliation baseline: `65080e8a37e8d546869528e43c162503015b94c7`.

This is a source-derived family/control-group inventory, not a rendered parity approval, a claim that every nested control has been independently reviewed, or a feature count. The machine record deliberately says `semantic_complete: false`. Independent inventory acceptance is outstanding. No old source/capture pin is changed by this page.

## Reproducible scope

The curated inventory contains 40 records: 39 surface/capability families and one shared-source infrastructure record. Classifications: 1 drift, 20 mixed, 1 non-goal, 18 omission. `mixed` means the prose distinguishes working subsets from WIP/omissions; it never means full parity.

The companion `desktop-source-ownership.json` accounts for 2133 production/support JavaScript/TypeScript source paths under `apps/desktop/src/` and `apps/desktop/electron/`, with content hashes, line counts, family ownership and candidate-signal counts. Tests/specs and CSS/assets are not counted as production controls. Fixtures and support code can still appear in the path census. Those are files, not features. Every candidate is owned structurally; a prefix match is not a semantic classification of its contents.

Discovery starts from the route table, contribution controller, palette callbacks, keybind action table, Settings navigation, Settings manifest, backend-curated config sections, plugin delivery glob, and bundled plugin entrypoints. It then follows selected handler/store/API paths and tests. This catches un-routed controls: native menus, statusbar items, pane controls, composer contributions, and plugin-specific settings. Plain `.js` is included, so Radio is not silently lost. The historical composer manifests are retained rather than regenerated on a new pin independently of their capture matrix (#196).

Runtime-installed Desktop plugins and backend-generated schema/credential/plugin rows are open-ended. This source audit inventories their registration and authority seams, not private installed plugins, credentials, or every possible server-supplied row. It reads no live account, credential file, provider state, or user session.

Run from the repository root (supply a disposable upstream checkout at the exact pin):

```sh
python3 scripts/desktop_surface_inventory.py --upstream "$UPSTREAM"
python3 scripts/desktop_surface_inventory.py --upstream "$UPSTREAM" --verify-source-ledger docs/parity/desktop-source-ownership.json
python3 -m unittest discover -s scripts/tests -p test_desktop_surface_inventory.py -v
# Intentionally exits 1 until semantic acceptance is actually earned:
python3 scripts/desktop_surface_inventory.py --require-semantic-complete
```

`--discover` emits the reproducible source candidates; `--snapshot` emits the compact ledger. Neither writes to upstream. Regeneration is a reviewed action, not an automatic pin bump. The verifier rejects wrong HEAD, duplicate families, missing owners/pages, bad source bounds, unowned source paths, ambiguous ownership and ledger mismatches. It does not prove pixels, callback reachability, or complete test coverage. Broad support prefixes intentionally make no stronger claim.

## Nested-control continuation

The [nested-control reconciliation](desktop-control-reconciliation.md) adds
`desktop-control-records.json` (418 source-read entrypoint/control templates),
`desktop-control-candidates.json` (3283 uncertified syntax candidates), and
`desktop-registry-reconciliation.json` (21 actual root registries). These are
three different evidence layers, not a feature count or a completeness percentage.
The [latest-target continuation](desktop-audit-continuation.md) retains this
baseline and assigns every family to a finite residual category with acceptance
and issue ownership; it does not convert source-review debt into implementation
omission or declare the inventory exhaustive.
The continuation follows imported primitives, handler/state/transport authority,
conditional menus and actual plugin delivery roots. It records additional logs
Copy/jump/match/Clear omissions, Maintenance operations, a notification-policy
conflict, and the macOS-only native-menu startup gate. Nonmac menu-factory
branches are not claimed as currently mounted controls. The old file census and
all visual pins remain unchanged. Exhaustive semantic acceptance remains withheld;
the companion page names the remaining trees that still need read-through.
The earlier 83-template increment preserves the first 212 records and follows
session row/tab dialogs and callbacks, current filter-menu predicates, installed
Skills management and the actually mounted owned-MCP Advanced subtree. It finds
live-delete, unread-bulk and busy-dismiss authority differences; explicit
unverified-omission statuses remain on every unresolved registry entry. The
approved Android named-MCP-toggle adaptation is not relabelled accidental drift.
The next 80-template increment preserves all first 295 serialized records and
follows 41 Appearance, 13 Accounts/Keys, 15 custom-endpoint and 11 Settings
main-model templates, including named setters, specialized writers and actual
mount/busy/owner/error limits. Current skin/mode scope differences are #266
findings; absent controls remain provisional #157/#73 omissions. A native
named-Bot model editor is not misreported as absent or mistaken for full Settings.
Remaining Appearance children, OAuth completion, local models, auxiliary/MoA,
connections, composer/transcript and other named residuals remain unverified.
The companion page separately reads the expanded-path API-client delta to the
later source without restamping this inventory or the parent's earlier 81-path
observation. No exhaustive semantic acceptance is asserted.

## Baseline findings that must survive the repin

- Session action drift: Desktop chooses Archive/Unarchive from archived state (`apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:520-535` at the inspected Desktop SHA); Android unconditionally appends Archive (`app/src/main/kotlin/com/hermesagent/mobile/ui/sessions/SessionActionsMenu.kt:250-277` at the Android baseline). Existing #266 owns repin-impact triage; this is not repaired by relabelling the historical capture.
- Goal support is not zero: Android reads `session.control.read` for bounded goal display (`GatewaySessionRepository.kt:4471-4501`, full package path below), but does not expose the goal/criteria/loop/heartbeat action stack. #184 remains its control-surface owner.
- Hosted groups: `GroupSendCoordinator`, durable intent and wire code are not production send UI. `GroupsScreen.kt:100-161` still carries disabled room/member/thread/attachment controls at the baseline. #193 remains open. New Desktop canonical-room code does not retroactively remove the historical ADR drift.
- Routines, model/toolset/skills/MCP editors and avatar upload/clear exist in the baseline. Older issue bodies and the historical bot contract audit contain superseded remainder lists. Current code and each newer per-surface page win; issue-open is not equivalent to feature-unimplemented. In particular #191 has a later status correction for production creation/Once/inspector.
- System Recent logs is WIP even though chat error recovery has its own working log dialog. Agent plugin management is WIP even though bundled Kotlin plugin toggles work. Those pairs are distinct entrypoints and outcomes.
- Parent-selected Branch/Export/Move to project, Kanban Filters/New task/Move to, System Recent logs, Agent plugin list/toggle and Bot new/recent actions remain baseline WIP here. No independent branch is counted as implemented until its exact integration is verified elsewhere.
- New route/settings/control families without dedicated parity pages are recorded as omissions under existing #73/#184, not mobile adaptations. A bounded issue-body handoff is needed before replacing that umbrella with a newly published issue; no issue number is invented here.

## Reading the records

Each family lists the actual user operations, frontend/handler source ranges, state and transport authority, Android implementation boundary, parity-page owners and existing issue owners. Upstream citations in the following records all use the Desktop SHA in Pin; Android citations use the baseline SHA in Pin. Bare Android filenames resolve beneath `app/src/main/kotlin/com/hermesagent/mobile/`; explicit evidence records give full paths.

Backend evidence and test citations are additional evidence, not endpoints invented from labels. Tests marked read were inspected, not executed in the upstream checkout. A missing test citation means this audit has no test-reading receipt for that family; it does not mean upstream has no tests. This is one reason semantic completeness remains uncertified.

### navigation-and-discovery

Controls: New chat; Settings; System; Usage; Capabilities; Messaging; Artifacts; Cron; Profiles; Agents; Starmap; Session import; plugin routes; command palette; setting search; direct session and folder lookup.

Classification: `omission`. Owner: migration #73; discovery/drift #266.

Desktop: `apps/desktop/src/app/routes.ts:11-77`, `apps/desktop/src/app/command-palette/index.tsx:759-1185`.

Authority/transport: Renderer route state; palette invokes existing feature actions rather than a universal backend endpoint. Contributions add run callbacks.

Android baseline: HermesApp.kt and SettingsScreen.kt: native destination routing, no universal command palette. Settings has Appearance, Gateways, System, Plugins, Notifications; contributed feature launchers are distinct. Missing destinations are not implicitly non-goals.

Parity owners: [desktop-sidebar.md](desktop-sidebar.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### session-actions

Controls: New tab/window/terminal; Rename; Pin/unpin; Read/unread; Appearance; Copy ID; Branch; Export; Move to project; Reload/close tab family; Archive/unarchive; Delete.

Classification: `mixed`. Owner: drift #266; migration #73.

Desktop: `apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:119-149`, `apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:300-610`.

Authority/transport: Session identity and mutations backend-owned; window/tab state renderer/Electron-owned. Rename prefers live RPC.

Android baseline: ui/sessions/SessionActionsMenu.kt: rename, pin/read, copy, archive/delete enabled; appearance, Branch, Export, Move to project unavailable. Android archive label is unconditional, unlike current Desktop toggle. No tab/window surface. Parent work does not alter this baseline.

Android evidence: `app/src/main/kotlin/com/hermesagent/mobile/ui/sessions/SessionActionsMenu.kt:250-318`.

Source test read, not executed: `apps/desktop/src/app/chat/sidebar/session-actions-menu.test.ts:62-161`. Rename prefers owned runtime RPC; persisted-only rows use REST, including empty-title handling.

Source test read, not executed: `apps/desktop/src/app/chat/sidebar/session-actions-menu.test.tsx:130-146`. Real dropdown trigger composition opens the action menu; labels in mocks are not copy authority.

Parity owners: [session-actions-menu.md](session-actions-menu.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### settings-navigation

Controls: Config section navigation; Passwords & Logins; Notifications; Billing overview/plans; Providers accounts/API keys/custom endpoints/local models; Gateway; Keyboard shortcuts; Tool keys; Archived chats; Plugins; About; Config export/import.

Classification: `omission`. Owner: migration #73; vault #238; surface gaps #184.

Desktop: `apps/desktop/src/app/settings/index.tsx:240-435`, `apps/desktop/src/app/settings/index.tsx:489-536`, `apps/desktop/src/app/settings/types.ts:7-19`.

Authority/transport: Config schema/backend owns host configuration; renderer selects/deep-links subpage. Billing is separate account authority.

Android baseline: ui/settings/SettingsScreen.kt: only five built-in destinations. No evidence of equivalent general config, provider, billing, keys, vault, keybind or About destinations. Bounded bot editors do not equal full Settings.

Android evidence: `app/src/main/kotlin/com/hermesagent/mobile/ui/settings/SettingsScreen.kt:27-100`.

Parity owners: [settings-plugins.md](settings-plugins.md), [gateway-connections.md](gateway-connections.md), [vault-prompts.md](vault-prompts.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### appearance-manifest

Controls: Language; Intro; Model pricing; Resume last chat; Tips/tours; Theme/mode; UI/chat/terminal fonts and scale; Simple mode; Density; Tab strip; App actions; Tray; Translucency/backdrop; Files; Composer popout; Bubble opacity; Direction; Timeline; Reactions; Hearts; Tool view/diffs/reasoning; Embeds; Pet.

Classification: `mixed`. Owner: appearance #157; six-surface stance #184; visual #292.

Desktop: `apps/desktop/src/app/settings/settings-manifest.ts:54-146`.

Authority/transport: Local renderer preferences and theme authority, not Gateway session truth. Platform predicates hide native-only rows.

Android baseline: ui/appearance/AppearanceScreen.kt has mode, presets/custom import and selected chat chrome. Full manifest must not be inferred from theme registry parity; absent rows remain omissions pending explicit platform decisions.

Parity owners: [appearance-themes.md](appearance-themes.md), [empty-states.md](empty-states.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### core-config-schema

Controls: Model/context/fallback/delegation; Chat/personality/timezone; Workspace/path/scan; Safety/approval/checkpoints; Browser/profile; Memory/provider/compression; Voice/STT/TTS; Advanced tools/execution/output/updates.

Classification: `omission`. Owner: migration #73; discovery #266.

Desktop: `apps/desktop/src/app/settings/constants.ts:709-870`.

Authority/transport: SECTIONS curates backend config keys; schema fields, credentials and plugins dynamically extend search independently of SETTINGS_MANIFEST.

Android baseline: No generic Android config-schema editor. Composer model/reasoning and approval mode plus bot model/toolset editors are bounded subsets, not full section coverage. Browser host preferences are not phone browser preferences.

Parity owners: [approval-mode.md](approval-mode.md), [bot-model-config.md](bot-model-config.md), [bot-toolsets.md](bot-toolsets.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### keyboard-and-pane-layout

Controls: Composer focus/model/voice/reasoning; Profile slots/switch/create; Session/tab slots; New worktree/folder; Navigation; Pane visibility; Tabs; HUD; Terminal; Find; Theme mode; Keybind editor; Layout reset/edit; Profile export/import; Reload desktop plugins.

Classification: `omission`. Owner: migration #73; gap classification #184.

Desktop: `apps/desktop/src/lib/keybinds/actions.ts:81-225`, `apps/desktop/src/lib/keybinds/actions.ts:348-377`, `apps/desktop/src/app/contrib/controller.tsx:192-440`.

Authority/transport: Renderer pane tree and scoped preferences; Electron supplies native windows/terminal/files. Keys are triggers, not separate features.

Android baseline: HermesApp.kt compact drawer and native destinations adapt navigation. No arbitrary pane tree, tab strip, HUD or rebind editor. Do not classify all absent keyboard-triggered backend actions as Electron-only.

Parity owners: [desktop-sidebar.md](desktop-sidebar.md), [session-actions-menu.md](session-actions-menu.md), [profile-switcher.md](profile-switcher.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### kanban

Controls: Board/lane display; Board switch/create/rename/delete/import/export; Search/filter assignee/tenant/archived/lanes; New task; Detail; Move status; Select/bulk move/reassign/archive/delete; Comments/requeue; Worker controls; Attachments; Estimate; Orchestration; Profile descriptions.

Classification: `mixed`. Owner: plugin migration #166; full coverage #73.

Desktop: `apps/desktop/src/plugins/kanban/plugin.tsx:81-170`, `apps/desktop/src/plugins/kanban/api.ts:388-517`, `apps/desktop/src/plugins/kanban/board.tsx:900-1088`, `apps/desktop/src/plugins/kanban/drawer.tsx:985-1030`.

Authority/transport: Plugin namespace REST via ctx.rest, socket-invalidated scoped query cache. Endpoint family is /api/plugins/kanban; not core RPC.

Android baseline: plugins/kanban/KanbanScreen.kt and KanbanPluginRepository.kt: board/task read snapshot. Filters, New task, Move to are visible WIP at baseline. Rich task/board mutations beyond those are not implemented by showing a label.

Android evidence: `app/src/main/kotlin/com/hermesagent/mobile/plugins/kanban/KanbanScreen.kt:86-129`.

Source test read, not executed: `apps/desktop/src/plugins/kanban/api.connection-scope.test.ts:28-129`. Query keys and board slugs follow connection; outgoing observer cannot refetch on incoming host.

Parity owners: [kanban-task-snapshot.md](kanban-task-snapshot.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### system-actions

Controls: Backend status; Gateway restart; Hermes update; Update check/changelog/action tail; Recent logs; Maintenance doctor/security audit/backup/debug share; Usage.

Classification: `mixed`. Owner: logs/background/skew #127; broad omissions #73.

Desktop: `apps/desktop/src/api/system.ts:148-179`, `apps/desktop/src/api/system.ts:270-313`, `apps/desktop/src/app/command-palette/index.tsx:956-995`.

Authority/transport: Authenticated host REST, explicit profile/owner scoping; Electron client update differs from backend update.

Android baseline: ui/system/SystemScreen.kt, SystemViewModel.kt and data/updates: status/restart/update implemented; System Recent logs WIP. Existing chat GatewayLogsDialog is error recovery, not System feature parity. No general maintenance/usage destination.

Android evidence: `app/src/main/kotlin/com/hermesagent/mobile/ui/system/SystemScreen.kt:179-200`.

Parity owners: [system-panel.md](system-panel.md), [turn-error-recovery.md](turn-error-recovery.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### agent-and-desktop-plugins

Controls: Bundled Desktop enable/disable; Runtime Desktop discovery/reload/install; Agent package list/toggle; Provenance/version/kind; Install here; Failure/refresh; Plugin settings; Contribution areas.

Classification: `mixed`. Owner: Agent #234; SDK #166.

Desktop: `apps/desktop/src/app/contrib/controller.tsx:325-337`, `apps/desktop/src/app/settings/types.ts:7-19`, `apps/desktop/src/plugins/kanban/plugin.tsx:81-170`.

Authority/transport: Desktop plugin loader is local JS/Electron authority; Agent half is profile-scoped backend authority. They are not interchangeable.

Android baseline: ui/settings/PluginsScreen.kt: bundled Kotlin module toggles plus disabled Agent plugins row. ADR 0003 rules out loading Desktop JS, not managing backend plugins.

Backend evidence at the same Desktop SHA: `tui_gateway/methods_tools.py:1859-1866`.

Source test read, not executed: `apps/desktop/src/store/agent-plugins.test.ts:21-60`. Slow install has explicit timeout budget; timeout is unknown outcome, not retry-safe failure.

Source test read, not executed: `apps/desktop/src/store/agent-plugins.test.ts:90-161`. Secret writer separate from settings RPC; stale profile response cannot replace another list.

Parity owners: [settings-plugins.md](settings-plugins.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### bots-roster-and-chat

Controls: Roster select/refresh; New/edit/duplicate/delete bot; Pin/hide/sections/order; Canonical Bot Chat; New side chat; Recent session; Screen open/auto-open; Groups; Activity notices; Routines pane.

Classification: `mixed`. Owner: Bot epic #186; captures #216; doors #268.

Desktop: `apps/desktop/src/plugins/hermes-bots/plugin.tsx:438-505`, `apps/desktop/src/plugins/hermes-bots/plugin.tsx:728-753`, `apps/desktop/src/plugins/hermes-bots/roster-actions.ts:215-381`.

Authority/transport: profiles.list and canonical session registry own identity; Server ui_meta CAS owns shared roster metadata; ctx.storage owns scoped local section/preferences state. Bot Chat is not an arbitrary latest session; source/profile-qualified open fenced by generation.

Android baseline: plugins/bots/BotsRosterScreen.kt and management models implement roster/canonical chat/selected mutations. New chat/recent and Screen actions visibly WIP; Group/identity/routine coverage is separately bounded.

Android evidence: `app/src/main/kotlin/com/hermesagent/mobile/plugins/bots/BotsRosterScreen.kt:539-560`.

Parity owners: [bots-roster.md](bots-roster.md), [bot-chat.md](bot-chat.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### sidebar-catalog-and-projects

Controls: Search; Group Updated/Profile/Project/Status; Ordering updated/created/status/tokens/cost/manual; Show metadata; Inbox; Profile rail; Status/profile/project/PR filters; Archived; Reset; Collapse/expand; Mark read; New project; Rename/add folder/set active; Appearance; Copy/reveal path; Remove/hide/undo.

Classification: `mixed`. Owner: filter #142; gateway groups #182; search drift #367.

Desktop: `apps/desktop/src/app/chat/sidebar/filter-menu.tsx:116-160`, `apps/desktop/src/app/chat/sidebar/filter-menu.tsx:280-453`, `apps/desktop/src/app/chat/sidebar/projects/project-menu.tsx:40-158`.

Authority/transport: Backend project/session catalog, renderer grouping/filter/order preferences. Auto project hide is local, explicit deletion is catalog mutation.

Android baseline: ui/sessions/SessionList.kt and ChatViewModel.kt: backend project tree, project selection and search; Updated/Project grouping. Other filter controls owed. Rich project management not equivalent to displaying a project.

Parity owners: [desktop-sidebar.md](desktop-sidebar.md), [session-list-sections.md](session-list-sections.md), [session-search.md](session-search.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### profiles

Controls: Rail/picker; All profiles; Profile list/detail; Create/rename/delete; SOUL; Export/import archive; Setup command; Default profile.

Classification: `mixed`. Owner: migration #73; Bot epic #186; scope #180.

Desktop: `apps/desktop/src/api/profiles.ts:11-116`, `apps/desktop/src/app/routes.ts:74-75`.

Authority/transport: Host profile lifecycle + authenticated profile-scoped REST; renderer selection is not host launch profile.

Android baseline: ui/profiles/ProfilesScreen.kt is read-only roster; data/profiles and session RPC route selected scope. Bot New/edit/duplicate is a separate bounded mutation path, not full lifecycle parity.

Parity owners: [profile-switcher.md](profile-switcher.md), [bot-management-contract-audit.md](bot-management-contract-audit.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### provider-accounts-and-keys

Controls: Provider OAuth sign in/poll/code/cancel/disconnect; API key save/clear/reveal/validate; Tool credentials; Custom endpoint add/validate/activate/delete; Delegation/fallback models.

Classification: `omission`. Owner: migration #73.

Desktop: `apps/desktop/src/api/config.ts:204-371`, `apps/desktop/src/app/settings/index.tsx:315-356`.

Authority/transport: Host config/env/provider account authority, profile-bound reads and writes. Connection OAuth is a different authority from provider OAuth.

Android baseline: No Android provider account/key/custom endpoint settings. Composer provider/model choice consumes a catalog; it does not configure credentials. Bot model choice is bounded.

Parity owners: [model-visibility.md](model-visibility.md), [bot-model-config.md](bot-model-config.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### local-model-management

Controls: Hardware/status; Catalog; Install runtime; Quickstart; Download/pause/resume; Activate/eject/delete; Server start/stop; HF search/files/download; Sideload.

Classification: `omission`. Owner: stance #184; picker #183.

Desktop: `apps/desktop/src/api/local-models.ts:13-146`, `apps/desktop/src/api/local-models.ts:167-207`, `apps/desktop/src/app/settings/local-models-owner.ts:1-20`.

Authority/transport: The connected HOST owns model files/hardware/jobs. Pane pins owner on mount; local means backend-local, not Android inference.

Android baseline: No local-model management destination. Model picker local/download headings and loading details remain independently owed; never turn these into a claim that Android runs a model.

Parity owners: [model-visibility.md](model-visibility.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### vault-management

Controls: List saved login/payment/address; Add/remove; Local/Bitwarden/1Password sources; Unlock/lock; Authenticator data.

Classification: `omission`. Owner: vault #238; prompt state #237.

Desktop: `apps/desktop/src/app/settings/vault-settings.tsx:158-190`, `apps/desktop/src/app/settings/vault-settings.tsx:320-378`, `apps/desktop/src/app/settings/vault-settings.tsx:450-503`.

Authority/transport: Profile/connection-qualified vault RPC. Secret entry remains sensitive state, list metadata is not a credential read. No live vault was accessed in this audit.

Android baseline: PendingInputSurface.kt can answer vault prompts; no Passwords & Logins management destination. Saving via a prompt cannot be undone on phone through a nonexistent screen.

Backend evidence at the same Desktop SHA: `tui_gateway/methods_vault.py:1-22`, `tui_gateway/methods_vault.py:42-121`.

Source test read, not executed: `apps/desktop/src/app/settings/vault-settings.owner.test.tsx:86-168`. Owner change wipes secret draft; late list cannot repaint next profile; secret absent from mutation cache.

Parity owners: [vault-prompts.md](vault-prompts.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### capabilities-skills-and-catalog

Controls: Installed list/filter; Enable/disable; Detail/edit/delete/archive; Official/Hub catalog search/filter/preview/scan/install/uninstall/update; Plugin package catalog; Toolsets detail/config.

Classification: `mixed`. Owner: migration #73; plugins #234.

Desktop: `apps/desktop/src/app/capabilities/index.tsx:61-180`, `apps/desktop/src/api/skills.ts:14-43`, `apps/desktop/src/api/skills.ts:102-173`.

Authority/transport: Profile-scoped host REST, bounded installer action status, backend enabled state. Catalog metadata and installed state are separate.

Android baseline: BotSkillsEditor.kt lists/toggles installed skills with guarded core HTTP; Browse/install/detail/bulk/archive WIP. BotToolsetsEditor.kt is a bounded enabled-toolsets editor, not full Capabilities UI.

Parity owners: [bot-installed-skills.md](bot-installed-skills.md), [bot-toolsets.md](bot-toolsets.md), [settings-plugins.md](settings-plugins.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### connectors-and-mcp

Controls: Hosted accounts/connect/disconnect; Directory/search/categories; Local MCP add/edit/remove; Catalog install; Probe/logs; OAuth; Tool controls; Alias/config persistence.

Classification: `mixed`. Owner: migration #73; in-chat consent #63.

Desktop: `apps/desktop/src/app/capabilities/index.tsx:175-180`, `apps/desktop/src/api/mcp.ts:27-103`.

Authority/transport: Backend MCP config and account grants, typed profile-scoped HTTP/RPC. Whole-map save/reload differs from named enabled toggle.

Android baseline: BotMcpEditor.kt and PluginMcp.kt list/named toggle; catalog/add/OAuth/remove/probe/per-tool controls WIP. In-turn MCP consent is distinct.

Parity owners: [bot-configured-mcp.md](bot-configured-mcp.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### messaging-and-pairing

Controls: Platforms list/configure/test; Telegram QR start/status/apply/cancel; Pairing approve/revoke; Allowlist; Gateway restart.

Classification: `omission`. Owner: migration #73.

Desktop: `apps/desktop/src/api/messaging.ts:18-145`.

Authority/transport: Profile-scoped host messaging router, not phone messaging-account APIs.

Android baseline: No messaging management destination in HermesApp/SettingsScreen; sessions from messaging sources can still be read. Reading conversations does not configure their channels.

Parity owners: [desktop-sidebar.md](desktop-sidebar.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### webhooks

Controls: List/detail; Enable service/restart; New subscription; Event/skills/delivery fields; Enable/disable/delete.

Classification: `omission`. Owner: migration #73.

Desktop: `apps/desktop/src/app/webhooks/index.tsx:397-507`, `apps/desktop/src/api/messaging.ts:157-200`.

Authority/transport: Host webhook configuration and service authority over authenticated HTTP, scoped to explicit profile.

Android baseline: No Android Webhooks destination or API consumer found. Not a platform non-goal: these act on the host.

Parity owners: [desktop-sidebar.md](desktop-sidebar.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### cron-and-blueprints

Controls: List/search/detail; Create/edit; Pause/resume/trigger/delete; Run history/open; Delivery target picker; Blueprints list/instantiate.

Classification: `mixed`. Owner: migration #73; routine #191.

Desktop: `apps/desktop/src/api/cron.ts:30-134`, `apps/desktop/src/api/cron.ts:148-168`.

Authority/transport: Core host REST owns full job/run records. List scoping is not an atomic item owner precondition; ordinary job IDs cannot prove ownership alone.

Android baseline: ui/cron and data/cron implement core management through GatewayCronApi. Runtime schema fields outside supported editors remain WIP. Blueprint actions require separate review; bot routines are not core Cron parity.

Parity owners: [cron-management.md](cron-management.md), [cron-session-admission.md](cron-session-admission.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### bot-routines

Controls: Bot-owned list; Title/detail inspector; Pause/resume/delete; New routine frequencies/once; Delivery target; Legacy delegated policy.

Classification: `mixed`. Owner: routines #191.

Desktop: `apps/desktop/src/plugins/hermes-bots/cron.tsx:99-145`, `apps/desktop/src/plugins/hermes-bots/cron.tsx:490-586`, `apps/desktop/src/plugins/hermes-bots/cron.tsx:632-739`.

Authority/transport: Profile-qualified cron.manage list/add/remove/pause/resume; richer core REST is a separate host door.

Android baseline: BotsRoutinesScreen.kt and models: list/read inspector, supported pause/resume/remove and creation including explicit one-shot syntax. Legacy/unknown jobs retain WIP. Do not repeat old contract-audit claims that all management remains missing.

Parity owners: [bot-routines.md](bot-routines.md), [bot-management-contract-audit.md](bot-management-contract-audit.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### bot-identity

Controls: Avatar upload/clear; Blobatar appearance; Generate/Bot/Pet choices; Model/provider/reasoning/confirmation; Toolsets; Skills; MCP; Reset defaults; SOUL/title/description.

Classification: `mixed`. Owner: identity #194; Bot epic #186.

Desktop: `apps/desktop/src/plugins/hermes-bots/profile-config.tsx:1-120`, `apps/desktop/src/plugins/hermes-bots/avatar-picker.tsx:1-100`.

Authority/transport: profiles.describe/configure plus host asset and guarded core capabilities; UI metadata is server-synchronized, not all local storage.

Android baseline: BotAvatarEditor.kt has upload/clear bounded path, Bot/Generate/Pet WIP; BotModelEditor reset WIP. Model/toolset/skills/MCP editors exist at baseline, so historical finite-remainder prose is stale in places.

Android evidence: `app/src/main/kotlin/com/hermesagent/mobile/plugins/bots/BotAvatarEditor.kt:69-90`, `app/src/main/kotlin/com/hermesagent/mobile/plugins/bots/BotMcpEditor.kt:52-65`.

Parity owners: [bot-identity.md](bot-identity.md), [bot-avatar-editor.md](bot-avatar-editor.md), [bot-model-config.md](bot-model-config.md), [bot-toolsets.md](bot-toolsets.md), [bot-installed-skills.md](bot-installed-skills.md), [bot-configured-mcp.md](bot-configured-mcp.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### groups

Controls: Create; Room select/history; Send; Stop/retry/approval; Rename/disband; Members/holds/peers; Attachments; Threads; Pin/sections/order; Room picture.

Classification: `drift`. Owner: hosted writes #193; Bot epic #186.

Desktop: `apps/desktop/src/plugins/hermes-bots/canonical-group-registry.tsx:1-110`, `apps/desktop/src/plugins/hermes-bots/group-chat-view.tsx:1-110`.

Authority/transport: Desktop legacy UI-meta and canonical hosted-room paths coexist. Android deliberately uses hosted groups.* and cannot claim legacy blob write parity.

Android baseline: GroupsScreen.kt read surface; send coordinator/store/wire exists but is not wired to production composer. New/Stop/disband/member/attachment/thread and room menu controls WIP. Existing-room send core alone is not user-facing send capability.

Android evidence: `app/src/main/kotlin/com/hermesagent/mobile/plugins/groups/GroupsScreen.kt:100-161`.

Parity owners: [bot-group-chat.md](bot-group-chat.md), [group-message-links.md](group-message-links.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### bot-screen

Controls: Open Screen; Auto-open tool activity; Route-owned screen/control/stream; Install; Image switching; Browser comment drafts.

Classification: `omission`. Owner: Bot epic #186; identity #194.

Desktop: `apps/desktop/src/plugins/hermes-bots/screen-open.tsx:1-49`, `apps/desktop/src/plugins/hermes-bots/screen-connection.ts:1-90`.

Authority/transport: Desktop workspace/Screen host capabilities bind connection + profile; not an unrestricted Android WebView.

Android baseline: BotsRosterScreen.kt Open Screen/auto-open WIP; no matching screen transport or workspace. Presence of a browser tool transcript is not an interactive Screen.

Parity owners: [bots-roster.md](bots-roster.md), [bot-management-contract-audit.md](bot-management-contract-audit.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### session-import

Controls: Source browse Claude/Codex; Search/page; Preview/truncation; Import/already imported; Open imported chat.

Classification: `omission`. Owner: six-surface stance #184.

Desktop: `apps/desktop/src/app/session-import/api.ts:1-49`.

Authority/transport: session.foreign.list/preview/import routed through exact owner connection/profile; host reads its own foreign histories.

Android baseline: No Android foreign-session import RPC consumer or route; owes visible entrypoint stance, not a phone file picker substitute.

Backend evidence at the same Desktop SHA: `tui_gateway/methods_session_foreign.py:1-46`.

Parity owners: [desktop-sidebar.md](desktop-sidebar.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### subagents

Controls: Global agent tree; Per-session activity; Expand transcript; Steer; Interrupt; Snapshot hydration; Terminal/error states.

Classification: `omission`. Owner: six-surface stance #184.

Desktop: `apps/desktop/src/app/agents/index.tsx:84-130`, `apps/desktop/src/app/chat/composer/status-stack/subagent-controls.tsx:15-88`, `apps/desktop/src/store/subagents.ts:250-319`.

Authority/transport: Parent owned session routes subagent.list/tail/steer/interrupt; child transcript ID is not steering authority. Unknown owner refuses rather than retargeting.

Android baseline: No equivalent subagent transcript/steer consumer found. Tool rows may mention delegations but are not this interactive surface.

Backend evidence at the same Desktop SHA: `tui_gateway/methods_subagents.py:19-63`, `tui_gateway/methods_subagents.py:81-128`.

Source test read, not executed: `apps/desktop/src/app/chat/composer/status-stack/subagent-controls.test.tsx:26-64`. Steer/stop target parent owner and preserve rejected text; unknown owner never falls back.

Parity owners: [composer-status-stack.md](composer-status-stack.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### goal-loop-heartbeat

Controls: Goal details/pause/resume/unwait/clear; Acceptance criteria add/remove/clear/copy; Loop pause/resume/stop; Heartbeat pause/resume/clear; Error dismissal.

Classification: `omission`. Owner: six-surface stance #184.

Desktop: `apps/desktop/src/app/chat/composer/status-stack/session-control.tsx:19-109`, `apps/desktop/src/app/chat/composer/status-stack/session-control-goal.tsx:230-273`, `apps/desktop/src/app/chat/composer/status-stack/session-control-loop.tsx:126-153`, `apps/desktop/src/app/chat/composer/status-stack/session-control-heartbeat.tsx:108-130`, `apps/desktop/src/store/session-control.ts:754-862`.

Authority/transport: Backend session.control.read snapshots + session.control actions; visible status does not authorize a write.

Android baseline: GatewaySessionRepository.kt reads session.control.read and GatewayGoalStatus.kt bounds display state. Full action/criteria/loop/heartbeat control stack absent; do not classify this as zero backend support.

Android evidence: `app/src/main/kotlin/com/hermesagent/mobile/data/gateway/GatewaySessionRepository.kt:4471-4501`.

Backend evidence at the same Desktop SHA: `tui_gateway/methods_session_control.py:255-323`.

Source test read, not executed: `apps/desktop/src/app/chat/composer/status-stack/session-control.test.tsx:139-189`. Hydrates structured control while retaining legacy goal until capability is supported; no duplicate goal.

Parity owners: [composer-status-stack.md](composer-status-stack.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### composer-edit-send-and-queue

Controls: Draft/edit history; Send/stop/redirect/steer; Queue/send next/edit/reorder; Attach files/images/folder/clipboard; URL/snippet/path/session references; Slash/at/emoji/skills completions; Model/provider/reasoning/fast; Popout.

Classification: `mixed`. Owner: acquisition #278; references #205; Bot doors #268.

Desktop: `apps/desktop/src/app/chat/composer/controls.tsx:38-188`, `apps/desktop/src/app/chat/composer/controls.tsx:290-350`.

Authority/transport: Canonical text/draft/queue local to owner; acknowledged prompt transport and session/model state backend-owned. Capture stages bytes; completion paths are host paths, not Android paths.

Android baseline: Composer.kt, ChatViewModel.kt, data/draft, attachments and GatewaySessionRepository implement main send/stop/corrections/queue/acquisition/model/completion pipeline. Folder and clipboard-image WIP; reference Open and queue-editor/details gaps stay owned. Popout is not silently declared a non-goal.

Parity owners: [composer-references.md](composer-references.md), [composer-recent-images.md](composer-recent-images.md), [composer-status-stack.md](composer-status-stack.md), [model-visibility.md](model-visibility.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### slash-execution

Controls: Desktop command interception; Session command dispatch; Curated palette visibility; Skill invocation; Terminal-only commands; Command output rendering.

Classification: `mixed`. Owner: slash #70; historical manifest #196.

Desktop: `apps/desktop/src/lib/desktop-slash-registry.json:1-132`.

Authority/transport: Registry categories curate visibility, not execution availability. Per-command handlers and backend contracts decide execution.

Android baseline: Completion is not command execution. GatewaySessionRepository has session commands and established execution paths; #70 tracks remaining execution/output. Do not count registry keys as implemented features.

Parity owners: [composer-references.md](composer-references.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### transcript-and-required-input

Controls: Backfill/tail/jump/timeline/sticky prompt; Copy/select/edit/regenerate/reactions; Markdown/code/diff/image/audio/video/artifacts/embeds; Tool details; Clarify/approval/sudo/secret/vault/MCP; Model confirmation/admission; Error/log/support recovery.

Classification: `mixed`. Owner: settled #380; refs #204; density #265; below count #224.

Desktop: `apps/desktop/src/components/assistant-ui/clarify/settled.tsx:1-57`, `apps/desktop/src/app/chat/scroll-to-bottom-button.tsx:1-70`.

Authority/transport: Backend transcript/canonical owner controls; renderer scroll, selections and disclosure. Secure replies route pending owner; generic tool output is not a specialized settled renderer.

Android baseline: Transcript.kt/ToolView.kt/PendingInputSurface.kt and canonical repository provide substantial bounded implementations. Settled responses-array clarification gap remains #380; in-chat refs #204; density #265; message-below count #224; no all-renderer parity claim.

Parity owners: [transcript-rendering.md](transcript-rendering.md), [tool-output-fidelity.md](tool-output-fidelity.md), [canonical-inputs.md](canonical-inputs.md), [transcript-backfill.md](transcript-backfill.md), [sticky-user-prompt-port.md](sticky-user-prompt-port.md), [transcript-selection-copy.md](transcript-selection-copy.md), [turn-error-recovery.md](turn-error-recovery.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### statusbar-and-notices

Controls: Gateway/profile/model/reasoning/approval controls; Backend/client versions; Context breakdown; Free tier; Workspace path/reveal; Agents/Cron/Webhooks; Running/session timer; Cache hit/tokens per second; System resources; Item visibility; Toast/notification/butterbar/terms.

Classification: `mixed`. Owner: surface #184; notifications #99; background/skew #127.

Desktop: `apps/desktop/src/app/shell/hooks/use-statusbar-items.tsx:475-627`, `apps/desktop/src/app/shell/hooks/use-statusbar-items.tsx:669-730`, `apps/desktop/src/app/shell/system-resources-statusbar.tsx:51-160`, `apps/desktop/src/app/settings/settings-manifest.ts:192-218`.

Authority/transport: Backend usage/model/effective state; renderer visibility; native OS notification capabilities. Hardware is host-authoritative.

Android baseline: ChatScreen.kt context/model/approval and status; NotificationsScreen.kt/SessionNotifier OS notifications. No general Desktop toast center or full resources/rate/visibility surface. #184 comment explicitly disputes blanket statusbar order parity.

Parity owners: [context-usage.md](context-usage.md), [approval-mode.md](approval-mode.md), [notifications.md](notifications.md), [model-visibility.md](model-visibility.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### artifacts

Controls: All/images/files/links tabs; Search/sort/page/view; Preview/open/download; Reveal location; Open source chat.

Classification: `omission`. Owner: migration #73.

Desktop: `apps/desktop/src/app/artifacts/index.tsx:114-170`, `apps/desktop/src/app/artifacts/index.tsx:336-349`, `apps/desktop/src/app/artifacts/index.tsx:528-549`.

Authority/transport: Artifact aggregation uses host/session data; file opening goes through bounded Desktop host capabilities. Inline transcript attachment rendering is a separate entrypoint.

Android baseline: No Android Artifacts library route. Transcript links/images do not equal cross-session artifact browsing/download management.

Parity owners: [transcript-rendering.md](transcript-rendering.md), [desktop-sidebar.md](desktop-sidebar.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### learning-starmap

Controls: Graph pan/zoom/select; Timeline/playback; Node detail/edit/delete; Skill archive confirmation; Share/import/reset map.

Classification: `omission`. Owner: migration #73.

Desktop: `apps/desktop/src/api/skills.ts:46-90`, `apps/desktop/src/app/starmap/share-controls.tsx:30-130`.

Authority/transport: Host learning graph/node API; renderer simulation/share state. No new Android endpoint inferred.

Android baseline: No Android Starmap/learning route or graph/node API consumer. A skill list is not a learning graph.

Parity owners: [desktop-sidebar.md](desktop-sidebar.md), [bot-installed-skills.md](bot-installed-skills.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### files-terminal-review-browser

Controls: File tree/browse/create/rename/delete/copy/path/open/drag; Terminal tabs/selection/paste/revive; Review list/diff/stage/unstage/revert/commit/push/PR; Worktree/branch operations; Preview/browser navigation/popout.

Classification: `omission`. Owner: migration #73.

Desktop: `apps/desktop/src/app/right-sidebar/files/ipc.ts:190-218`, `apps/desktop/src/lib/desktop-git.ts:22-100`.

Authority/transport: Desktop host filesystem/PTY/browser bridge and authenticated git facade. Remote backend workspace path must never become a phone path.

Android baseline: CodingContext/CodingStatusRow expose bounded git status/PR links via Gateway HTTP; no full Files/Terminal/Review/browser workspace. These host functions are not all Electron-only non-goals: each needs a deliberate transport/safety decision.

Parity owners: [composer-status-stack.md](composer-status-stack.md), [desktop-sidebar.md](desktop-sidebar.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### radio

Controls: Choose/search station; Play/pause/retry/next; Volume/mute; Favorite/pin; Visit station/artist; Waveform; Cross-window playback arbitration.

Classification: `omission`. Owner: migration #73.

Desktop: `apps/desktop/src/plugins/radio/plugin.js:94-139`, `apps/desktop/src/plugins/radio/plugin.js:157-240`.

Authority/transport: Client audio + external public directory/media, local plugin preferences. No Gateway RPC required; not covered by backend plugin toggles.

Android baseline: No Android Radio bundled module in BundledPlugins. Missing surface, not an inherited platform non-goal. New privacy/media policy needed before port.

Parity owners: [settings-plugins.md](settings-plugins.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### runtime-example-and-extension-boundary

Controls: Hello Runtime chip toggle/style/about; Arbitrary runtime plugin routes/panes/palette/settings/transcript/composer contributions.

Classification: `non-goal`. Owner: SDK #166.

Desktop: `apps/desktop/src/contrib/plugins.ts:1-21`, `apps/desktop/src/plugins/hello-runtime/plugin.runtime.js:39-119`, `apps/desktop/src/contrib/settings-pages.ts:138-173`.

Authority/transport: Bundled glob and runtime disk loader invoke plugin register; runtime content is open-ended and cannot be exhaustively enumerated from repository labels.

Android baseline: Android bundled Kotlin SDK/contribution registry only. Loading arbitrary Desktop JS is a documented ADR 0003 non-goal; future bundled ports need their own surface audit. Hello Runtime is a runtime example, not automatically a shipping bundled destination.

Parity owners: [settings-plugins.md](settings-plugins.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### native-shell-and-onboarding

Controls: Application/Window menu; Window controls/tray/quit/find/devtools; Install/bootstrap/update/uninstall; Quick entry/HUD/wake/pet overlays; Onboarding/provider/free-tier; Shared metrics consent; Support diagnostics consent; About / Check for Updates; Services / Hide / Hide Others / Unhide / Quit; New Window / Open Folder / Close; Undo / Redo / Cut / Copy / Paste / Paste and Match Style / Delete / Select All; Substitutions / Text Replacement; Reload focused preview / Force Reload / Developer Tools; Actual Size / Zoom In / Zoom Out / Fullscreen; Window Minimize / Zoom / Bring All to Front.

Classification: `mixed`. Owner: migration #73; release/physical #72.

Desktop: `apps/desktop/src/app/contrib/controller.tsx:780-845`, `apps/desktop/src/app/settings/settings-manifest.ts:154-190`, `apps/desktop/src/components/onboarding/flow.tsx:1-100`, `apps/desktop/electron/main.ts:6487-6622`, `apps/desktop/electron/window-menu.ts:7-29`, `apps/desktop/electron/application-menu-startup.ts:1-33`.

Authority/transport: Electron owns OS windows/process/install; renderer consent/flow; host provider/free-tier authority remains separate. Window role behavior is not an Android backend feature.

Android baseline: Native Android activity/back/permissions/connection setup and diagnostic consent paths adapt part of flow. No Electron installer/HUD/tray or full Desktop onboarding. Do not silently omit portable provider/account/consent capabilities as OS-only.

Parity owners: [gateway-connections.md](gateway-connections.md), [empty-states.md](empty-states.md), [turn-error-recovery.md](turn-error-recovery.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### gateways-and-connection-lifecycle

Controls: Saved registry/add/edit/remove/duplicate detection; Connect/disconnect/switch; Local/Remote/SSH/Cloud; Sign in/out; Test; Make primary; Headers; Diagnostics; Ownership/restart/reconnect.

Classification: `mixed`. Owner: Cloud #151; polish #85; switch #110.

Desktop: `apps/desktop/src/app/settings/connections-registry.tsx:88-173`, `apps/desktop/src/app/settings/connections-registry.tsx:630-672`, `apps/desktop/src/app/chat/sidebar/connection-switcher.tsx:35-115`.

Authority/transport: Saved route preferences local; transport/auth/lifecycle machine facts; sessions backend-owned. Source switch wipes connection-bound stores, never treats a URL label as owner identity.

Android baseline: ConnectionsSection.kt and connection registry implement saved rows/switch/edit/remove. Test connection and Make primary WIP; Cloud not shipped. Android Local targets Termux without owning process, SSH owned lifecycle separate from shared Remote.

Android evidence: `app/src/main/kotlin/com/hermesagent/mobile/ui/gateway/ConnectionsSection.kt:399-406`.

Parity owners: [gateway-connections.md](gateway-connections.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### billing-and-subscriptions

Controls: Account overview/usage; Buy credits; Charge status/retry; Auto reload; Plan preview/change/resume; Step-up; Portal links.

Classification: `omission`. Owner: migration #73.

Desktop: `apps/desktop/src/app/settings/billing/api.ts:146-178`, `apps/desktop/src/app/settings/billing/plans-view.tsx:85-113`.

Authority/transport: Gateway billing/subscription RPC backed by account authority; idempotent charge key and explicit step-up are security boundaries, not UI decoration.

Android baseline: No Android Billing destination or billing RPC consumer. This audit performs no purchase, account mutation or credential access. Portable account management is not automatically an Electron non-goal.

Parity owners: [desktop-sidebar.md](desktop-sidebar.md).

Evidence limit: Source reconciliation only; rendered and independent review remain owed.

### shared-source-infrastructure

Controls: Shared primitives and SDK; Types; Localization; Theme registry/tokens; API/state helpers; Debug/development fixtures.

Classification: `mixed`. Owner: source-impact triage #266.

Desktop: `apps/desktop/src/contrib/types.ts:1-40`, `apps/desktop/src/app/contrib/index.ts:1-7`.

Authority/transport: Support code is not a user-facing capability. Source ownership routes review; it does not classify every exported helper as an implemented feature.

Android baseline: Android counterpart depends on consumer; this row records structural support only. Newly discovered UI under a support root still requires semantic inspection and a dedicated family as needed.

Parity owners: [desktop-sidebar.md](desktop-sidebar.md).

Evidence limit: Structural ownership only; not a semantic completeness claim.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Archive/Unarchive menu state | drift | Baseline menu always appends Archive; historical report unchanged | #266; exact source and baseline ranges above |
| Missing route/config/control families | omission | Family owners recorded; no hidden implementation claim | pill-owed: #73; #184 owns the six-surface stance |
| Arbitrary runtime Desktop JS plugins | omission | Kotlin bundled-only SDK; runtime loading not promised | non-goal: ADR 0003 chooses compiled-in Android plugins rather than a Desktop JS loader |

## Visual report

- No UI was changed or captured in this source-audit lane. This page does not establish a new visual baseline.
- pending: #216 — roster comparison; other existing per-surface owners include #244 (composer stack), #272 (profiles), #292 (Appearance), and the linked parity pages. None is claimed complete by this inventory.

## Acceptance still withheld

Independent final inventory review must check nested controls, conditional/native branches and all remaining source/test families, replacing coarse groups where needed. Parent exact-head integration, compatibility repair, feature acceptance and device/rendered QA are separate. Source-ledger success alone must never close #266, #184 or #73. This audit has not implemented the full old-to-new drift engine or artifact workflow requested by #266.
