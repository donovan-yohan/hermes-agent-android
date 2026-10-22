# Settings ▸ Plugins: source and deviation ledger

Settings ▸ Plugins contains bundled Android app plugins and the selected profile's
agent plugins. App switches stay local; agent switches use the live Gateway.
The bounded agent capability is list + enable/disable, not install, removal,
updates, plugin settings or credentials. Related owner: [#234](https://github.com/donovan-yohan/hermes-agent-android/issues/234).

## Pin

| Source | Pin | Read via |
|---|---|---|
| Historical Desktop app-half UI + copy | `hermes-agent` @ `564aef2946c436500a5e80ee117b66b789b3f99a` | Retained historical ledger, not freshly re-audited here |

The app-half naming port and old capture retain that provenance. They are not
certified against a newer renderer by this change.

## Agent list and toggle source audit

All citations in this section are against `81a55437f3f31a9f12a30c53b6c5d49d420898aa`.
The Desktop component now lives under `app/capabilities/plugins`, not its old
`app/skills` location.

| Contract | Exact source |
|---|---|
| Row schema: canonical key, display metadata, status, portable and catalog provenance | `apps/desktop/src/store/agent-plugins.ts:36-69` |
| Curated inventory: user installs, manageable bundled lifecycle plugins, hidden provider/platform/auth prefixes | `apps/desktop/src/store/agent-plugins.ts:113-132`; `apps/desktop/src/store/agent-plugins.test.ts:81-88` |
| Profile-scoped list, stale-load generation, key-only toggle | `apps/desktop/src/store/agent-plugins.ts:140-249` |
| Agent switch, busy and keyless read-only treatment | `apps/desktop/src/app/capabilities/plugins/plugins-tab.tsx:452-480` |
| List failure and retry | `apps/desktop/src/app/capabilities/plugins/plugins-tab.tsx:725-734` |
| Key collisions and legacy behavior regression coverage | `apps/desktop/src/app/capabilities/plugins/plugins-tab.test.tsx:357-384,438-459` |
| Agent heading, scope, description, empty/error, legacy and portable copy | `apps/desktop/src/i18n/en.ts:2104-2137` |
| Backend row construction; list; toggle receipt; scoped action router | `tui_gateway/methods_tools.py:1656-1690,1725-1748,1854-1866` |
| Named profile resolution, missing-profile refusal, override reset | `tests/plugins/test_plugins_manage_profile_scope.py:13-74` |

Production wiring is `HermesApplication.agentPluginsHost` → `MainActivity` →
`HermesApp` → `PluginsScreen`. The concrete active profile comes from
`chatState.profileRail.scope.activeProfile`, including when browsing all profiles;
`default` is sent explicitly, never silently replaced by the backend launch profile.
`AgentPluginsStore` holds one visible owner's ephemeral backend inventory, scoped
to that profile, endpoint generation and opaque ready-leg identity. No plugin
inventory or preferences are persisted locally for the agent half.

Every list/toggle/readback goes through `PluginHost.requestAtEndpointOwned`.
The host's typed irrevocable owner lease serializes close with immediate wire
admission under one monitor, nested inside the existing endpoint fence. A close
that completes first forbids the frame; an admitted handoff makes close wait for
that handoff, never for its response. Predicates alone are not this boundary.
Profile/host/endpoint/ready-leg replacement closes the previous store before
opening its replacement; A → B → A and same-profile reopen cannot revive it.
Endpoint and ready-leg checks also run on return. Already-admitted mutations
cannot be retracted by closing the screen.

The writer sends only `action: toggle`, the exact `key`, Boolean `enable`, and
explicit `profile`. It never falls back to `name`; duplicate keyed inventories
are invalid, and keyless legacy rows remain visible and read-only. A successful
mutation receipt is not enough: a fresh scoped list must contain exactly the
same key with the requested status. An absent/wrong-key/opposite-status readback
or refused call remains unconfirmed and requires Refresh before another toggle.
No automatic mutation retries. Busy state disables all agent switches and Refresh.
Unsupported method is Unavailable, not an empty list. Malformed inventory is an
error, not success. Display metadata is redacted; wire identity stays separate.
Install paths, settings schemas and credential declarations are not retained.

## Historical app-half source

These citations remain at `564aef2946c436500a5e80ee117b66b789b3f99a`:

- Original page structure: `apps/desktop/src/app/skills/plugins-tab.tsx:241-355,478-585`.
- Original page naming: `apps/desktop/src/i18n/en.ts:1587-1630`.
- App-half copy: `apps/desktop/src/i18n/en.ts:445-457`.

The old `settings.sectionEntries.plugins` citation was invalid; the historical
key was `settings.nav.plugins`, removed during consolidation. The Plugins title
still uses Desktop's page word. Android bundled Kotlin modules do not load
Desktop JavaScript; [ADR 0003](../adr/0003-bundled-plugin-sdk.md) still applies.

## Candidate-source registry audit (2026-10-22)

The [nested-control reconciliation](desktop-control-reconciliation.md) inspects
Desktop `81a55437f3f31a9f12a30c53b6c5d49d420898aa` separately from this page's
historical Pin. At that source the Settings rail has a live Plugins entry with
schema/contributed settings children (`apps/desktop/src/app/settings/index.tsx:400-408`;
`apps/desktop/src/contrib/settings-pages.ts:192-256`). The consolidation prose
above describes its old inspected revision, not the current rail.

General Agent schema editing is not covered by a future list/toggle feature:
`apps/desktop/src/app/settings/plugin-settings-form.tsx:155-279,281-422` dispatches
six control templates and Save, while
`apps/desktop/src/store/agent-plugins.ts:483-552` separates settings values from
secret replacements. These remain #73 omissions; #234 retains its bounded
Agent inventory/toggle scope. No private plugin schemas or secret values were read.

The actual bundled discovery glob at the same candidate
(`apps/desktop/src/contrib/plugins.ts:21-87`) matches Bots, Kanban and Radio.
Radio is default-disabled shipping code (`apps/desktop/src/plugins/radio/plugin.js:684-697`),
not a runtime example. `hello-runtime/plugin.runtime.js` does not match that glob.
The new records enumerate Radio's conditional controls and preserve ADR 0003's
separate runtime-JavaScript non-goal. Android still bundles Bots, Groups and
Kanban at baseline `65080e8a37e8d546869528e43c162503015b94c7`; this is not a
new rendered parity claim or an implementation change.

## Divergences

### Later-source qualification (not a restamp of this page's Pin)

The separately pinned [continuation](desktop-audit-continuation.md) follows
the current catalog, install modal, Electron-local probe, host Agent install,
uncertain completion and detail-only uninstall. This historical table's
device-side JS-loader non-goal remains valid; its blanket catalog/install/deep-link
rationale must **not** exclude backend Agent functionality. Server install,
catalog, schema and update remain unimplemented under #73; #234 owns bounded
inventory/toggle, not every package operation. No screenshot is relabelled.
#227 is closed naming work, not an outstanding evidence owner; current Agent
rendered acceptance belongs to #234. The continuation carries all newer source
citations so this page's historical paths retain their actual Pin.

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Capabilities Plugins tab and paired package grouping | mobile-adaptation | Settings ▸ Plugins, with a separate flat section for each runtime | A narrow touch viewport stacks the app-level and remote-profile scopes instead of pairing an Android Kotlin module with unrelated remote JS packages. This preserves the existing Settings destination and 48dp switch targets; no package equivalence is inferred by name. |
| Agent scope selector within the package table | mobile-adaptation | Explicit “Agent in <profile>” label uses the app's selected profile; switch profiles in the existing rail | There is one concrete profile owner, not a second selection store on this short Settings destination. Browsing all profiles does not target an all-profile mutation. |
| App-half blurb and installed count | mobile-adaptation | Existing app-half description/count remain above the bundled list; agent heading/blurb/empty state below | Each section states its own runtime and authority rather than implying the app-level switch changes an agent. |
| Agent toggle patches the returned row or reloads | mobile-adaptation | Independent exact-key/status readback is mandatory; unconfirmed result requires Refresh | Remote mobile connections can be interrupted mid-write. A visible switch must not announce persistence from an echoed receipt alone. The requested status is shown only after backend readback. |
| Agent list failure uses Retry | mobile-adaptation | “Could not load agent plugins” and Refresh, with generic safe recovery for unavailable/disconnected/unconfirmed | Refresh matches the existing Android settings grammar; no raw backend exception is displayed on a phone. Loading and busy use static text, so state is readable with reduced motion. |
| Agent per-row version, source, portable and catalog provenance | mobile-adaptation | Wrapped compact pills in a flat settings row | Touch width requires wrapping instead of table columns. Agent kind is shown without claiming a remote Desktop half can run as an Android bundled module. |
| Plugin install, remove, update, settings and credential controls | omission | Not included in this bounded list/toggle capability | out-of-scope: #234 — this slice does not authorize those mutations or credential access. Agent plugin installation is not a platform non-goal; this row retains the owner rather than closing its wider scope. |
| Catalog browser/search, package-detail navigation and row deep links | omission | Only the installed agent inventory and its switches are included | out-of-scope: #234 — this bounded port does not claim the current Desktop catalog browser or detail pane. These are not hidden inside the location adaptation or declared permanent platform non-goals. |
| Detailed MCP server state and complete provenance/update metadata | omission | Only version, source, portable and catalog name/tier are displayed | deferred: #234 — this change does not claim server readiness or update/catalog parity. |
| Disk-folder, reveal, rescan and Desktop JavaScript loading | omission | No Android disk/plugin-loader bridge | non-goal: bundled Kotlin modules ship through an in-tree PR, not a mutable Desktop plugin folder. |

## Tests

- `AgentPluginsStoreTest`: key-only explicit-profile payloads; collision separation;
  exact readback; missing/wrong/opposite status; legacy keyless rows; curation;
  malformed/unsupported list; duplicate prevention; disposed/profile owner;
  endpoint switch and same-endpoint ready-leg reconnect at held wire dispatch;
  revoked late response.
- `AgentPluginsAdmissionTest`: actual `CorrelatedGatewayRpc` + `GatewayPluginHost`
  held final-predicate/close-first refusal and admitted-send/close-waits ordering;
  same-profile reopen, profile A → B → A, endpoint A → B → A with monotonic
  generation, and real virtual-time RPC timeout/cancellation uncertainty. No
  response wait holds the owner monitor and no uncertain mutation is retried.
- `AgentPluginsJourneyTest`: real Settings navigation with selected-profile
  propagation, enable/disable state, profile replacement, legacy read-only rows,
  failure + Refresh, and unsupported gateway state using production PluginHost.
- `PluginsSettingsJourneyTest`: bundled app toggles and restored state remain
  independent of the agent Gateway connection.

## Visual report

- pending: #234

Concern: no new Desktop/Android render is claimed. The original capture follow-up
[#227](https://github.com/donovan-yohan/hermes-agent-android/issues/227) is closed;
[#234](https://github.com/donovan-yohan/hermes-agent-android/issues/234) is the existing
open owner for the agent half and its remaining rendered evidence. Device and
rendered acceptance remain a separate review gate.

Historical Android-only packet:
`docs/parity/visual/settings-plugins/settings-plugins-light/android/reference.png`
and `contract.json`, from head `7022e2b`. It predates the naming change, marked
Agent row and removal of the plugin it showed. Preserve its pin; it does not show
or certify the new agent list. New evidence owes light/dark loading, empty,
loaded, busy, keyless, unavailable, failed/Refresh and unconfirmed states, including
profile/endpoint replacement. Unit/Compose green is not visual acceptance.
