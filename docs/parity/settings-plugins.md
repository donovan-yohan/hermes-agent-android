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

Every list/toggle/readback goes through `PluginHost.requestAtEndpointGuarded`.
The guard rechecks owner liveness, concrete profile, endpoint and ready leg at
immediate wire dispatch and on return. Closing or replacing the screen revokes
the old store. A reconnect creates a new owner even at the same endpoint.

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

## Divergences

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
