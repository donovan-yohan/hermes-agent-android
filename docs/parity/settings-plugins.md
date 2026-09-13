# Settings ▸ Plugins: source and deviation ledger

Port of Hermes Desktop’s plugins surface, **bundled plugins only** per issue
#169 and `docs/adr/0003-bundled-plugin-sdk.md`.

## Pin

| Source | Pin | Read via |
|---|---|---|
| Desktop UI + copy | `hermes-agent` @ `564aef2946c436500a5e80ee117b66b789b3f99a` | `git -C ~/.hermes/hermes-agent show <sha>:<path>` |

Every `path:line` below is against that pin.

## What upstream changed, and how much of it ports

Upstream `61afcde8f9` folded two half-pages into one. Settings ▸ Plugins listed
desktop plugins plus *Install from Git*; Capabilities ▸ Plugins listed agent
plugins plus the catalog; neither showed the whole set. Capabilities ▸ Plugins
is now THE plugins page — one row per *package*, a switch per half — and
Settings ▸ Plugins was deleted along with the `settings.nav.plugins` key.

This page previously claimed its Settings row was verbatim
`settings.sectionEntries.plugins` at `i18n/en.ts:408` @ `3ca096de`. That was
wrong twice over: `:408` was `settings.nav.plugins`, not a `sectionEntries` key,
and `61afcde8f9` deleted it. Both the code comment and this ledger cited a key
that no longer exists.

**The consolidation itself does not port.** It exists to put two halves in one
place; this app has one half. There is no agent-plugin list, no install path and
no catalog to unify with the bundled roster, so folding two pages into one would
be folding one page into itself.

**The naming ports, and it is the whole point of this slice.** Desktop’s page is
called **Plugins** (`skills.tabPlugins`, `i18n/en.ts:1587`) — not “Desktop
plugins”, which is now only the name of one half’s section inside it. This app’s
page title, Settings row label and empty state are therefore *verbatim* Desktop
where they used to be adaptations of a narrower key.

**Where the surface lives does not port**, because there is nowhere to move it
to: Capabilities is a pane holding Skills, Tools, MCP and Plugins, and this app
ships none of the other three. Row 1 below.

## Paths that settled the port

| Question | Path |
|---|---|
| The plugins page: header, blurb, the two half columns, per-row controls | `apps/desktop/src/app/skills/plugins-tab.tsx:241-355,478-585` |
| Where that page is mounted, and the tab’s label | `apps/desktop/src/app/skills/index.tsx:873,891` |
| The package row model — one row per package, two optional halves | `apps/desktop/src/app/skills/plugin-packages.ts:4-30,49-50` |
| Copy keys for the page | `apps/desktop/src/i18n/en.ts:1587-1630` |
| Copy keys for the app-plugin (“Desktop”) half | `apps/desktop/src/i18n/en.ts:445-457` |

Android implementation:

- `app/src/main/kotlin/com/hermesagent/mobile/ui/settings/PluginsScreen.kt`
- `app/src/main/kotlin/com/hermesagent/mobile/ui/settings/PluginsCopy.kt`
- `app/src/main/kotlin/com/hermesagent/mobile/ui/settings/SettingsScreen.kt`
- `app/src/main/kotlin/com/hermesagent/mobile/ui/HermesApp.kt`

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| The page is a tab of the Capabilities pane (`skills/index.tsx:873,891`), reachable from the pane, the command palette and `/skills?tab=plugins` | mobile-adaptation | Settings ▸ Plugins, labelled with Desktop’s own word for the page (`skills.tabPlugins`, `en.ts:1587`) | Capabilities groups Skills, Tools, MCP and Plugins; this app ships none of the other three, so the destination would hold one tab and cost a tap and a screen to group nothing. A phone has no command palette and no address bar, and Settings is this app’s only destination list. The row’s description carries the surface’s purpose instead of its location. |
| Page blurb: “One row per plugin. A plugin can extend this app, the agent, or both — each half has its own switch.” (`skills.plugins.pageBlurb`, `en.ts:1592`) | mobile-adaptation | Desktop’s app-half blurb instead: “Extend this app, not an agent — the same for every profile, gateway or machine you connect to. Bundled with the app; toggles apply live.” (adapted from `settings.plugins.blurb`, `en.ts:447-448`) | Desktop’s sentence teaches a two-switch row this app does not draw, so it would describe a control that is not on screen. The kept sentence is the one fact a reader needs on a small viewport — these extend the app and not the agent — which is also what `skills.plugins.halfDesktopHint` (`:1594`) says in the column header this app has no room for. Its second clause names the `desktop-plugins` folder, which Android has no door to. |
| Two control columns with their own headers — `Desktop` / `Agent in <profile>` (`plugins-tab.tsx:544-559`) | mobile-adaptation | One switch per row, and the column meaning stated once in the page blurb | A two-column table does not survive 320dp beside a wrapping plugin name and its pills; the app-half switch already sits in the row’s action slot at the 48dp floor. With only one half backed there is also nothing for the second column to align against. |
| The Agent half: a per-package switch scoped to the profile selector, `Install here` for a missing half, and the legacy-backend tip (`plugins-tab.tsx:305-352`) | omission | One disabled `Agent plugins` row behind the `WIP` chip, below the list (`PluginsScreen.kt`, `PLUGINS_AGENT_HALF_TAG`) | coming soon — the pill ships today. The half itself is #234: it rides `plugins.manage` over the gateway, a backend call this app already has a door for (`PluginContext.host`), so it is a *yet*, not a never, and is marked rather than hidden. One row stands for the half because per-package agent switches would need rows this app cannot populate. |
| Kind badge `Agent` / `Desktop` / `Agent + Desktop`, the provenance and `portable` pills, and the version (`plugins-tab.tsx:144-193,252-255`) | omission | Only the `bundled` kind pill (`settings.plugins.kinds.*`, `en.ts:457`), verbatim | deferred: #234 — every one of these reads from the agent row. With one half, a kind badge would say “Desktop” on every row and a provenance pill would have nothing to attest: an Android plugin’s provenance is this repository. They arrive with the half they describe. |
| Agent-list failure panel and its `Refresh` (`plugins-tab.tsx:524-534`) | omission | Absent | deferred: #234 — this list is `BundledPlugins.ALL`, compiled in; it cannot fail to load, so there is no state for the panel to report. The failure it reports is the agent RPC’s. |
| `Install from Git` (`plugins-tab.tsx:488-495`, `en.ts:462`) | omission | Absent | non-goal: adding a plugin to Android is an in-tree PR adding a Kotlin module to `BundledPlugins.ALL` (`docs/adr/0003-bundled-plugin-sdk.md`, “Disk / network plugin loading”). There is no device-side install for a button to start. |
| `Open plugins folder` (`plugins-tab.tsx:496-506`, `en.ts:450`) | omission | Absent | non-goal: Android ships no disk door — no `desktop-plugins` root exists on the device, so there is no folder to open. |
| `Rescan` (`plugins-tab.tsx:507-520`, `en.ts:451`) | omission | Absent | non-goal: runtime and disk discovery are Desktop-only; this app’s roster is compiled in and cannot change between launches of the same build. |
| `Reveal in file manager` per row (`plugins-tab.tsx:271-279`, `en.ts:452`) | omission | Absent | non-goal: a bundled Kotlin module has no file path to reveal, and the plugin host exposes no `revealPath` bridge. |
| The catalog picker — embedded `hermes-agent.nousresearch.com` iframe, its sash, `Browse`/`Hide`, and the `emptyHint` that points at it (`plugins-tab.tsx:588-646`, `en.ts:1611,1616-1620`) | omission | Absent; the empty state is `skills.plugins.emptyAll` (`en.ts:1609`) alone, verbatim | non-goal: the catalog exists to install with one click, and this platform has no install path at all, so it would be a web view that can only browse. The hint is dropped with it rather than left pointing at a catalog that is not below. |
| `?plugin=<key>` deep-link highlight into a row (`plugins-tab.tsx:396`) | omission | Absent | non-goal: the link’s two senders are the command palette and the address bar, neither of which exists on a phone; nothing in this app can address a plugin row. |

## Visual report

- pending: #227

Android half only, owed its Desktop side by #227:
`docs/parity/visual/settings-plugins/settings-plugins-light/android/reference.png` with its
`contract.json` — Settings ▸ Plugins listing the bundled Relay plugin with its `bundled` pill and
enabled switch, light, on `emulator-5554` from this branch's debug build (head 7022e2b).
That capture predates the naming change and the marked Agent row, so it no
longer shows the surface: the pending report owes both halves at the new pin.
