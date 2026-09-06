# Settings ▸ Plugins: source and deviation ledger

Port of Hermes Desktop’s Settings ▸ Plugins inventory, **bundled plugins only**
per issue #169.

## Pin

| Source | Pin | Read via |
|---|---|---|
| Desktop UI + copy | `hermes-agent` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3` | `git -C ~/.hermes/hermes-agent show <sha>:<path>` |

Every `path:line` below is against that pin.

## Paths that settled the port

| Question | Path |
|---|---|
| Bundled plugin inventory row shape (name, description, pills, switch) | `apps/desktop/src/app/settings/plugins-settings.tsx:313-355` |
| Bundled plugin section header (title, count meta, blurb, empty) | `apps/desktop/src/app/settings/plugins-settings.tsx:357-408` |
| Copy keys for the surface | `apps/desktop/src/i18n/en.ts:408-421` |

Android implementation:

- `app/src/main/kotlin/com/hermesagent/mobile/ui/settings/PluginsScreen.kt`
- `app/src/main/kotlin/com/hermesagent/mobile/ui/settings/PluginsCopy.kt`
- `app/src/main/kotlin/com/hermesagent/mobile/ui/settings/SettingsScreen.kt`
- `app/src/main/kotlin/com/hermesagent/mobile/ui/HermesApp.kt`

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Page title: “Desktop plugins” (`i18n/en.ts:411`) | mobile-adaptation | “Plugins” | Desktop’s title scopes the surface to the Desktop disk door. Android ships bundled-only (no disk door), so “Desktop” would be untrue; the phone title matches the Settings row label and the supported inventory. |
| Blurb: “Bundled or dropped into the desktop-plugins folder…” (`i18n/en.ts:412`) | mobile-adaptation | Blurb states bundled-only | No disk door on Android; claiming folder-backed installs would be false. The omission is already a non-goal in #169, so the copy is adapted to the truth and stays as a single sentence. |
| “Open plugins folder” (`plugins-settings.tsx:380-385`, `i18n/en.ts:414`) | omission | Absent | non-goal: Android ships no disk door (no file manager / reveal path door in the plugin host), so there is no safe “open folder” affordance to offer. |
| “Rescan” (`plugins-settings.tsx:385-397`, `i18n/en.ts:415`) | omission | Absent | non-goal: runtime/disk discovery is Desktop-only; Android’s plugin roster is compiled-in (`BundledPlugins.ALL`). |
| “Reveal in file manager” per plugin row (`plugins-settings.tsx:321-327`, `i18n/en.ts:416`) | omission | Absent | non-goal: no disk door; bundled plugins have no file path to reveal, and Android offers no equivalent host-owned “revealPath” bridge. |
| Empty state: “No desktop plugins installed yet.” (`i18n/en.ts:420`) | mobile-adaptation | “No plugins installed yet.” | Same reason as the title: “desktop” names the disk door Android does not have. |
| Agent plugins section (`plugins-settings.tsx:410`) | omission | Absent | out-of-scope: #169 — this slice ports the bundled inventory only. |

## Visual report

- pending: #169

Android-only capture is acceptable for this slice; until an emulator/device
capture is attached, the Desktop half is evidenced from source + verbatim copy
citation above and the ordered-row shape is covered by the Robolectric journey.

