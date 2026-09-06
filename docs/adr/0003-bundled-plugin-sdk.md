# ADR 0003 — Bundled-only plugin SDK and Desktop contribution contract

**Status:** implemented for issue #167, 2026-09-06  
**Authority:** `NousResearch/hermes-agent` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`  
**Related:** Issue #166 (Epic: Desktop-compatible plugin SDK), Issue #167 (SDK core), `docs/spikes/plugin-surface-relay.md`

## Context

Hermes Desktop ships a rich plugin architecture supporting two delivery modes:

1. **Bundled plugins** (`apps/desktop/src/contrib/plugins.ts:5-48` at the pin): compiled-in modules discovered at startup, published to the plugin inventory with `status: disabled | loaded | error`, and activated when `pluginActive(id, defaultEnabled)` is true.
2. **Runtime / disk plugins** (`<hermes home>/desktop-plugins/*`): arbitrary uncompiled ESM (`plugin.js`) dynamically evaluated in the Electron renderer.

Desktop's own documentation notes that its runtime loader provides error isolation only, not sandboxing. On Android, executing untyped runtime JavaScript or loading arbitrary code from disk/network violates platform security posture and offers no performance or security sandbox. A mobile app requires native Jetpack Compose UI, deterministic coroutine lifecycle management, and compile-time type safety.

However, full architectural compatibility with Desktop is essential so that any Desktop plugin surface can be ported to Android by direct translation rather than structural redesign.

## Decision

Adopt Desktop's **bundled delivery mode only**:

```text
BundledPlugins.ALL
       │
       ▼
PluginLoader (HermesApplication.onCreate)
       │
       ├── PluginStore (hermes.plugin.decisions.v1)
       │     └─ status: disabled | loaded | error, live activate/deactivate
       │
       └── PluginContext
             ├── ContributionRegistry (StateFlow-backed reactive areas)
             ├── PluginRest (GatewayHttp -> /api/plugins/<id>/..., path-guarded)
             ├── PluginSocket (path-guarded; no-op disposer on OAuth legs)
             ├── PluginStorage (hermes.plugin.<id>.<key> via DataStore)
             └── PluginOs (notify via NotificationSurface, openExternal, writeClipboard, share)
```

### 1. Compiled Kotlin modules (`HermesPlugin`)

Plugins on Android are compiled-in Kotlin classes implementing `HermesPlugin`:

```kotlin
interface HermesPlugin {
    val id: String
    val name: String? get() = null
    val description: String? get() = null
    val defaultEnabled: Boolean get() = true
    fun register(ctx: PluginContext)
}
```

Every plugin defines a stable `id` which namespaces its contributions (`<pluginId>:<contributionId>`), its source provenance (`plugin:<pluginId>`), its REST route namespace (`/api/plugins/<pluginId>/...`), and its persistent storage (`hermes.plugin.<pluginId>.<key>`).

### 2. Contract mapping (Desktop to Android)

Direct correspondence with Desktop's source contracts:

| Desktop contract (`3ca096de5f8183cb2e0ec23673f294d5978656a3`) | Android Kotlin SDK (`com.hermesagent.mobile.plugins`) |
|---|---|
| `contrib/plugin.ts:77-83` `HermesPlugin` | `interface HermesPlugin` |
| `contrib/plugin.ts:60-75` `PluginContext` | `interface PluginContext` |
| `contrib/types.ts:25-45` `Contribution` | `data class Contribution` (render is `@Composable () -> Unit`) |
| `contrib/registry.ts:31-155` `ContributionRegistry` | `class ContributionRegistry` exposing `StateFlow` streams |
| `contrib/plugins-store.ts:16-25` `PluginRecord` | `data class PluginRecord` (`disabled`, `loaded`, `error`) |
| `contrib/plugins-store.ts:30-48` decisions | `PluginDecisionStore` under `hermes.plugin.decisions.v1` |
| `api/plugins.ts:44-55` `pluginRest` | `PluginRest` over `GatewayHttp` (`/api/plugins/<id>/...`) |
| `api/plugins.ts:57-95` `pluginSocket` | `PluginSocket` (no-op on OAuth / unsupported legs) |
| `contrib/plugin.ts:9-13` `PluginStorage` | `PluginStorage` (`hermes.plugin.<id>.<key>`) |
| `contrib/plugin.ts:20-56` `PluginOs` | `PluginOs` (notify via `NotificationSurface`, openExternal, clipboard, share) |

### 3. Contribution areas and graceful degradation

The contribution registry maintains all registered entries regardless of whether the mobile client currently renders the targeted area. Unsupported or future areas (such as `PANES_AREA`, `THEMES_AREA`, or arbitrary data contributions) register cleanly and never throw:

- `ROUTES_AREA` & `SIDEBAR_NAV_AREA`: navigation targets.
- `TRANSCRIPT_DIRECTIVE_AREA`: inline assistant directives (`::name{key="value"}`).
- `COMPOSER_AREAS.*`: composer strips, actions, middleware, attachments, completions.
- `THEMES_AREA`, `PANES_AREA`, `STATUSBAR_AREAS.*`, `TITLEBAR_AREAS.*`, `CHAT_EMPTY_AREA`, `PALETTE_AREA`, `KEYBINDS_AREA`: accepted cleanly.

### 4. REST boundary and runtime gate mapping

Every plugin REST call via `ctx.rest(path)` is relative to `/api/plugins/<pluginId>`.
- Path traversal sequences (`..`) in path segments are strictly rejected before request emission.
- When the Gateway's runtime gate middleware returns HTTP 404 (for missing or disabled backend plugins), `PluginRest` maps this directly to `PluginRestResult.UnavailableOnGateway` so UI surfaces can distinguish an unconfigured/disabled backend from transport errors.

### 5. Error isolation

If a plugin throws an exception during `register()` or activation, `PluginLoader` captures the failure, marks the plugin's inventory record as `status: PluginStatus.Error` with the failure message, and continues discovering and activating subsequent plugins. The app never crashes on a faulty plugin.

## Non-goals

- **Runtime JavaScript loading:** `plugin.js` is never loaded or interpreted on the device.
- **Disk / network plugin loading:** Adding a plugin to Android is an in-tree PR adding a Kotlin module to `BundledPlugins.ALL`.
- **Live plugin sockets under OAuth:** Plugin sockets on OAuth-gated remotes return a no-op disposer and rely on polling (parity with Desktop).
- **Backend discovery probe:** Plugins do not dynamically scan unknown endpoints; capability is detected via deterministic route responses.
