# ADR 0003 — Bundled-only plugin SDK and Desktop contribution contract

**Status:** implemented for issue #167, 2026-09-06; host and i18n doors added for issue #187, 2026-09-11  
**Authority:** `NousResearch/hermes-agent` @ `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`  
**Related:** Issue #166 (Epic: Desktop-compatible plugin SDK), Issue #167 (SDK core), Issue #187 (gateway JSON-RPC, event and i18n doors), `docs/spikes/plugin-surface-relay.md`

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
             ├── PluginOs (notify via NotificationSurface, openExternal, writeClipboard, share)
             ├── PluginHost (gateway JSON-RPC over the live socket, method-guarded,
             │     gateway event tap; cancelled with the plugin)
             └── PluginI18n (plugin-owned locale bundles; resolved against the app locale)
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

| Desktop contract (`72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`) | Android Kotlin SDK (`com.hermesagent.mobile.plugins`) |
|---|---|
| `contrib/plugin.ts:107-120` `HermesPlugin` | `interface HermesPlugin` |
| `contrib/plugin.ts:75-105` `PluginContext` | `interface PluginContext` |
| `contrib/types.ts:25-45` `Contribution` | `data class Contribution` (render is `@Composable () -> Unit`) |
| `contrib/registry.ts:31-155` `ContributionRegistry` | `class ContributionRegistry` exposing `StateFlow` streams |
| `contrib/plugins-store.ts:16-25` `PluginRecord` | `data class PluginRecord` (`disabled`, `loaded`, `error`) |
| `contrib/plugins-store.ts:30-48` decisions | `PluginDecisionStore` under `hermes.plugin.decisions.v1` |
| `api/plugins.ts:44-55` `pluginRest` | `PluginRest` over `GatewayHttp` (`/api/plugins/<id>/...`) |
| `api/plugins.ts:57-95` `pluginSocket` | `PluginSocket` (no-op on OAuth / unsupported legs) |
| `contrib/plugin.ts:9-13` `PluginStorage` | `PluginStorage` (`hermes.plugin.<id>.<key>`) |
| `contrib/plugin.ts:20-56` `PluginOs` | `PluginOs` (notify via `NotificationSurface`, openExternal, clipboard, share) |
| `sdk/index.ts:582,1267,1407-1414` module-global `host.request` / `host.onEvent` | `PluginContext.host` (`PluginHost` over the live `GatewayRpcClient`) |
| `i18n/plugin-i18n.ts:39-46,96-101` `PluginI18n` / `createPluginI18n` | `PluginI18n` (`PluginLocaleRegistry`) |

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

### 5. Host door, plugin i18n and disposal

`PluginContext.host` exposes the live connection's JSON-RPC surface to a plugin; `PluginContext.i18n` carries its own locale bundles. Both are scoped to the plugin and torn down with it.

- `host.request(method, params)` rides the connection-owned `GatewayRpcClient` (`data/gateway/GatewayRpc.kt:126-302`): JSON-RPC 2.0 over the one authenticated WebSocket, the same transport this app itself uses. The client is re-resolved per call, so the door follows a reconnect the way Desktop's lazy `host.request` follows a profile swap.
- The `method` is validated before anything is sent. A blank, spaced, slashed or `..`-bearing method is refused locally — the host door's form of the traversal guard `PluginRest` applies to a route: a malformed name must cost no request.
- Desktop's result-shaped refusal is kept. A Gateway `-32601` (`tui_gateway/server.py:734` @ the pin, `unknown method: {method}`) maps to `PluginHostResult.UnavailableOnGateway` — the twin of `PluginRestResult.UnavailableOnGateway`; any other RPC error maps to `Refused` carrying this app's own sentence, never text the backend wrote; no live connection maps to `Refused(0, RECONNECT_MESSAGE)`, as `PluginRest` does with no transport.
- A call the Gateway never answers is a value too. `withTimeout` (`data/gateway/GatewayRpc.kt`) raises `TimeoutCancellationException`, which is caught at the door and returned as `Refused(0, TIMED_OUT_MESSAGE)`: only the exchange ends, and the plugin's own coroutine is never silently cancelled. A plugin's scope going away is still a cancellation, because that is the plugin being disposed rather than a call failing.
- `host.onEvent(type, listener)` is Desktop's `onGatewayEvent` (`contrib/events.ts:16-28`): subscribe by type or `*`, listeners isolated so one throwing plugin cannot break the pump or another subscriber, disposer returned. Desktop fans every inbound event through `emitGatewayEvent` (`contrib/events.ts:31`) before its own dispatch; here the client's event stream is a broadcast — the bounded channel is the ingest buffer and one pump drains it to every subscriber, so the app's transcript pump and a plugin tap each receive every event rather than competing for one queue.
- `i18n.register(bundles)` merges locale bundles keyed by plugin, resolved active locale → the plugin's `en` → the key itself (Desktop's `ctx.i18n`, `i18n/plugin-i18n.ts:39-46,96-101`). The returned disposer joins the plugin's dispose list.
- `PluginLoader` gives each activation one `CoroutineScope`. Disabling the plugin cancels it, so a request still in flight stops rather than outliving `onDispose`.

**Mobile adaptation.** Desktop's module-global `host` also carries navigation and a multi-connection registry. Navigation waits for slice 3 — there is no screen to navigate to yet — and the connection registry is out of the epic; this door ships only the request, event and i18n doors.

### 6. Error isolation

If a plugin throws an exception during `register()` or activation, `PluginLoader` captures the failure, marks the plugin's inventory record as `status: PluginStatus.Error` with the failure message, and continues discovering and activating subsequent plugins. The app never crashes on a faulty plugin.

## Divergences

The narrowed set of differences from Desktop at the pin. Each is a decision this app owns, not an unmade one; classification follows the parity vocabulary (`mobile-adaptation`, `drift`, `omission`).

| Desktop (`72a3277cd7`) | Class | Android | Evidence |
|---|---|---|---|
| `PluginI18n.register` takes `PluginLocaleBundles`: locale → arbitrarily nested message trees addressed by dot-path, and a leaf may be a literal or an interpolator (`i18n/plugin-i18n.ts:22-37`); `t` is `(key, ...args) => string` (`:36`) | drift | `register(Map<String, Map<String, String>>)` with flat keys and `t(key)` | `app/src/main/kotlin/com/hermesagent/mobile/plugins/PluginI18n.kt:21-27`. A Kotlin plugin cannot express Desktop's nested trees, dot-path keys or `string \| ((...args) => string)` leaves, and no bundled plugin needs an interpolated or nested string yet. Widening this contract — nested bundles plus `t(key, vararg args)` — is the change to make when one does; until then the narrowing is recorded here rather than silently shipped. |

## Non-goals

- **Runtime JavaScript loading:** `plugin.js` is never loaded or interpreted on the device.
- **Disk / network plugin loading:** Adding a plugin to Android is an in-tree PR adding a Kotlin module to `BundledPlugins.ALL`.
- **Live plugin sockets under OAuth:** Plugin sockets on OAuth-gated remotes return a no-op disposer and rely on polling (parity with Desktop).
- **Backend discovery probe:** Plugins do not dynamically scan unknown endpoints; capability is detected via deterministic route responses.
