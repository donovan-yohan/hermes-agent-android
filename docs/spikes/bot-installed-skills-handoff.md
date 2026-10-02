# Existing-Bot installed Skills — original contract gate audit

**Resumption:** the parent authorized the bounded core HTTP host foundation.
See [ADR 0005](../adr/0005-scoped-core-skills-http.md) for the implemented typed
capability, dispatch proof/limits, route availability, and remaining editor work.
The stopped-audit record below is historical; no Skills UI ships in this slice.

## Outcome

Stopped before Android implementation, as requested when a foundational host change is necessary. No Skills selection editor, replacement Toolsets feature, invented RPC, CLI/config escape hatch, production change, or capture state was added. The existing Skills WIP control remains unchanged.

Worktree: `../hermes-mobile-bot-skills`, branch `feat/bot-installed-skills`, based on fetched `origin/main` at `651d659bded4deb7b125e48ef746c899857837e0`. Original working-tree changes were not edited. Upstream was inspected in a disposable checkout at **`587e673e2a2fae0616d8b750bb189217080f621a`**; installed upstream was not checked out, fetched into, or modified.

## Why the existing guarded RPC route is unsafe

All upstream citations in this document refer to the full SHA above.

- `tui_gateway/methods_profiles.py:345-379`: `profiles.describe {name}` returns only installed `SKILL.md` directory basenames plus `enabled`. It does **not** return the complete disabled-name configuration. Comparison lowercases both disabled names and directory basenames.
- `tui_gateway/methods_profiles.py:382-405,701-722`: `profiles.configure {name, disabled_skills}` **replaces** the disabled set, with `ok` and `applied.skills` receipts. There is no Skills revision/CAS or merge-delta parameter here.
- `hermes_cli/skills_config.py:12-54`: unknown disabled names are valid configuration values. Missing/uninstalled names remain meaningful if installed later. Writing only the disabled installed rows drops those unknown values.

Counterexample: installed inventory contains `visible`; configuration A disables `absent-skill`, configuration B disables nothing. Both describe responses are exactly `[{name: visible, enabled: true}]`. Disabling `visible` via replacement loses `absent-skill` in A. Neither another preflight describe nor identical successful readback can distinguish these states or detect that loss. Endpoint/dialog fencing cannot fix missing server authority.

## Exact semantics and uncertainties

- Missing/empty global disabled set means enabled by default, not an allowlist pin. `disabled_skills: []` clears global disables; it is **not** Toolsets' restore-inheritance operation.
- Platform-specific disabled names are unioned with global disables when a platform is supplied. Describe calls `get_disabled_skills` without one, so it reports global selection, not availability on every messaging platform (`hermes_cli/skills_config.py:28-54`; runtime `agent/skill_utils.py:298-310`). Platform restrictions survive a global write.
- `hermes-agent` is essential (`agent/skill_utils.py:295`), and save silently removes it from the disabled set. A successful mutation receipt alone therefore cannot prove the requested enabled state (`hermes_cli/skills_config.py:43-54`).
- Do not normalize server-provided names on Android. The RPC read lowercases membership while the HTTP read below uses exact membership; these are distinct contracts.
- There is no Skills inheritance/pinned field in describe. Do not infer one from Toolsets or claim cross-profile inheritance controls. Inventory/resource resolution and platform applicability are not represented by its two-field rows.

## Current Desktop is not the fallback checkbox editor

- `apps/desktop/src/plugins/hermes-bots/profile-config.tsx:247-283`: current builds embed real `CapabilitiesView`, pinned to Bot profile and connection. The fallback staged checklist (`:197-213,578-580`) is not the current surface.
- `apps/desktop/src/app/capabilities/index.tsx:75-80,155-165,189-193`: Skills → Tools → Connectors → Plugins; scoped installed inventory feeds Skills.
- `apps/desktop/src/app/capabilities/skills/skills-tab.tsx:77-147`: individual and bulk switches autosave sequentially, optimistic cache state rolls back on failure, errors notify, and queries invalidate afterward. Busy/loading/error disables changes; empty inventory disables All. Bulk affects the whole profile, not filtered search results.
- `apps/desktop/src/app/capabilities/catalog/skill-catalog.tsx:98-203`: installed, official, and hub entries are combined; this is not just an installed checkbox list. Full detail, install/catalog, editing and archive are outside the requested bounded selection slice.
- `apps/desktop/src/app/capabilities/index.test.tsx:217-265`: source tests cover scoped switch dispatch and full skill detail.
- `apps/desktop/src/hermes-capability-scope.test.ts:163-175`: named connection/profile must accompany reads and writes.
- Historical `apps/desktop/src/i18n/bots-advanced.test.tsx:91-113` tests fallback staged replacement, not current HTTP switch safety.

These are source-observed states, **not rendered visual evidence**. No Desktop or Android capture was produced; no full parity claim is made. Future explicit Save/readback/uncertainty states would be documented mobile safety adaptations, not fabricated matching Desktop states.

## Real upstream alternative and foundational Android blocker

Current Desktop uses real scoped **HTTP**, not `profiles.configure`:

- `apps/desktop/src/api/skills.ts:14-43`: `GET /api/skills`, `PUT /api/skills/toggle` with `{name, enabled}` and capability scope.
- `hermes_cli/web_routers/skills.py:343-394`: GET uses the full skill finder, returns enabled state/provenance/usage; PUT reads the disabled set within `config_write_scope(body.profile or profile)`, adds/discards only the named skill, and preserves unrelated names. Its receipt echoes requested name/enabled, so essential-skill rejection still requires readback.
- `tests/hermes_cli/test_web_server_skills_profiles.py:67-93`: existing tests establish named-profile write isolation and restoration of scoped globals. These tests were read, not executed in this audit.

Android source below is at base `651d659bded4deb7b125e48ef746c899857837e0`:

- `app/src/main/kotlin/com/hermesagent/mobile/plugins/HermesPlugin.kt:10-43`: plugin context exposes namespaced REST and JSON-RPC host, not a typed core Skills HTTP capability.
- `app/src/main/kotlin/com/hermesagent/mobile/plugins/PluginRest.kt:55-77,91-107`: REST prefixes `/api/plugins/<id>` and forbids traversal. It cannot legitimately address `/api/skills`.
- `app/src/main/kotlin/com/hermesagent/mobile/plugins/PluginHost.kt:100-107`: guarded immediate dispatch exists for RPC and fails closed on unsupported hosts.
- `app/src/main/kotlin/com/hermesagent/mobile/data/gateway/GatewayHttp.kt:104-159`: app-owned HTTP supports PUT and `isCurrent` checks, but the plugin context does not expose this core route. It is not the shared immediate RPC dispatch-fence contract; a predicate checked before blocking HTTP execution must not be described as that atomic fence.

**Decision needed before coding:** authorize a bounded typed core Skills HTTP host capability, including endpoint/profile/dialog ownership at the actual HTTP dispatch boundary and explicit unavailable/refusal behavior, or pursue an upstream RPC contract that exposes complete mutation authority. No such new interface or API was implemented here. Do not broaden generic plugin REST to escape its namespace.

## Existing editor seams to preserve on resumption

Read Toolsets/model/avatar repositories and Toolsets lifecycle before stopping. Toolsets supplies the relevant pattern: exact named read, preflight known-stale refusal (not server CAS), guarded section-only write, exact receipt plus readback; accepted pending ownership is endpoint/profile-scoped, separate from dialog/read revisions. Close/reopen and A→B→A must retain pending ownership and reconcile after completion without replay. See `BotsToolsetsRepository.kt:23-83`, `BotsToolsetsViewModel.kt:29-117`, `BotsAvatarRepository.kt:23-65`, and `BotsModelRepository.kt:25-71` under the Bots package. Do not change these editors or overwrite other drafts when adding Skills.

## Verification actually executed

A stdlib-only Python AST probe compiled the **actual pinned** `get_disabled_skills`, `save_disabled_skills`, and describe's installed-list expression. It used synthetic configuration, an in-memory save sink, and a synthetic installed path; it did not import the running Gateway or write any profile. All four assertions passed:

1. Describe cannot distinguish hidden disabled names.
2. Replacement loses an absent disabled name.
3. Empty clears global disables while retaining platform restrictions.
4. Essential disable is silently dropped.

This is an isolated contract counterexample, not an upstream integration test or Android RED/GREEN result. No Android implementation was authorized past the failed gate, so no repository/ViewModel/Compose tests, debug capture fixture, Gradle build, emulator operation, push, or PR was performed. The exclusive Gradle lane was not consumed. On approved resumption use the requested serial `check assembleDebug --max-workers=1 --no-parallel -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx6g'` after real RED/GREEN, then review and visual evidence before any push/PR.

## Divergences and remaining work

- Omission: existing Skills control remains disabled/WIP. Nothing was silently substituted or newly hidden.
- No new UI/mobile adaptation shipped; explicit Save would diverge from current Desktop autosave and needs its own classification/evidence.
- Host-capability approval and implementation come first. Only then implement installed selection with unknown-name preservation, essential readback, unavailable/refusal, endpoint/dialog ABA, pending reopen, no replay and other-draft preservation tests.
- Full catalog/install/detail/edit/archive parity remains outside this bounded slice. Native pixels and deterministic debug capture states remain unimplemented, not passed.
