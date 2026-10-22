# Latest-target audit continuation

## Pin

Source reading: Desktop `92663ae8054ae81f207f46d83512d3f879dc698d`.
Android comparison: `65080e8a37e8d546869528e43c162503015b94c7`.
Observed Android main: `5a2b666dc41471fd7a04d5a91534d8d9fe73d2ac`.
The latter does not replace the historical comparison baseline. Its PluginsScreen
is byte-identical to the baseline. No feature worktree was changed or credited
as merged from an open PR.

## Deliverable and limit

[Machine records](desktop-audit-continuation.json) contain fourteen additional
source-read control/authority seams and twelve finite residual-work categories
covering all forty preserved families exactly once. The original 418 control
objects, source census and registry reconciliation remain byte-for-byte historical.
Do not add the new seam count to the old template count and call it features.

This is **finite ownership reconciliation, not exhaustive control certification**.
`semantic_complete` remains false because whole mounted trees, transitive handlers
and comparative rendering remain unread/unexecuted. No flag or structural test
can close that gap. Existing issue ownership means acceptance resides in that
issue; it does not imply an assigned human (the inspected issues have no assignees)
or that a new issue/comment was published by this local candidate.

## Actual incremental source findings

The global contribution wiring mounts `PluginInstallModal`
(`apps/desktop/src/app/contrib/wiring.tsx:1333` at the source SHA above).
Capabilities dispatches its active Plugins tab with `scope.profile` and a keyed
remount (`apps/desktop/src/app/capabilities/index.tsx:130-150`). The scope selector
preserves connection/profile objects, but PluginsTab reduces ProfileScope to its
profile parameter before plugin requests. This increment does not certify
cross-connection installation from selector intent alone.

Catalog Add is now a **button**, not a reversible install switch. Installed rows
use the owning package switch; uninstall lives in detail behind confirmation.
The dialog separates local Electron repository inspection, profile-scoped host
Agent installation and local Desktop-half installation. A host install timeout
is uncertain, suppresses the Desktop phase and does not automatically retry.
The reviewed tier badge is display-only; the backend resolves the catalog pin.

This establishes a concrete documentation-policy gap: old blanket catalog,
Git-install and deep-link non-goals in the historical Plugins page cannot exclude
server-side Agent functionality. ADR 0003 excludes running arbitrary Desktop JS
on the device, not installing an Agent plugin on the host. Backend install,
schema and update flows remain #73 omissions; #234's independent list/toggle
slice does not implement them. No speculative Android endpoint is introduced.

Every machine record names its mount, callback, actual authority, immutable spans,
Android boundary and issue owner. Source tests named there were **read, not run**.
No host plugin install, external clone, credential read or provider migration was
performed. Repository probe code was read; it was not invoked.

## Classified new records

| Record | Classification | Issue owner | Operation |
|---|---|---|---|
| `capabilities.scope` | omission | #73 | Choose capability owner |
| `catalog.add` | omission | #73 | Install catalog entry |
| `catalog.tier` | omission | #73 | Display reviewed source tier |
| `catalog.repository` | omission | #73 | Review raw Git repository |
| `catalog.probe` | omission | #73 | Inspect repository components |
| `catalog.advanced` | omission | #73 | Expand install particulars |
| `catalog.profile` | omission | #73 | Set install profile |
| `catalog.pin` | omission | #73 | Pin custom Agent install |
| `catalog.force` | omission | #73 | Force custom reinstall |
| `catalog.install` | omission | #73 | Confirm install |
| `catalog.retry-cancel` | omission | #73 | Retry failure or cancel install dialog |
| `plugins.package-toggle` | omission | #234 | Toggle installed package |
| `plugins.uninstall` | omission | #73 | Uninstall Agent package |
| `plugins.desktop-install` | non-goal | #166 | Load Desktop JS half |

## Finite remaining work, with acceptance

The table is a residual-work partition, not a claim every nested control is known.
Its machine counterpart lists precise family membership, evidence and acceptance.
An unread tree is **review-debt**, not proof Android is missing every control.
`mixed` explicitly retains working subsets and known implementation omissions.

| Category | Classification | Issue owner | Remaining boundary |
|---|---|---|---|
| `discovery-and-session-owners` | mixed | #266 | Header/Bot menu suppliers; project menus/search/comparators; profile create/import; tab rehome, archive/pin lifecycle and native gestures. Existing row/tab actions are bounded implementation, not whole-family absence. |
| `settings-and-native-appearance` | mixed | #73 | Language/Pet/tray/contributed children, actual native effects and layout persistence; generic schema template does not certify specialized Voice/Memory/fallback/auxiliary/MoA. |
| `provider-vault-billing` | mixed | #73 | OAuth/onboarding completion, provider/native-opening, local downloads/progress, vault and billing step-up branches. Named-Bot model editor already exists; full host Settings does not. |
| `kanban-remaining` | mixed | #73 | Complete board/drawer/menu/form, multi-board/workspace/advanced creation and orchestration. Filters/Branch observed merged elsewhere are not audit-baseline features; independent create/move candidates not certified here. |
| `bots-groups-routines` | mixed | #186 | Roster/identity/editor children, canonical versus ordinary session menu variants, Screen, routines/groups nested controls and contributions. Production routines/avatar/editors are not absent just because umbrella issues remain open. |
| `capability-packages-and-connectors` | mixed | #73 | This increment resolves scope/install/probe/tier/uninstall seams; remaining public catalog query/discovery/detail links, toolset/provider forms, update consent/scan UI, hosted OAuth/accounts/directory/probe/per-tool dialogs remain unverified. |
| `messaging-cron-import` | mixed | #73 | Messaging/pairing all trees, Cron blueprint/delivery/history and import completion; webhook and foreign import templates already read but not general acceptance. |
| `composer-voice-queue` | mixed | #67 | Voice engine construction is missing despite nullable callbacks/dictation; server-owned queued edit/steer differs from local queue actions; loaded busy policy, completion folder/Tab/Escape, URL attachment cards, MoA and specialized model flows. |
| `transcript-agents-artifacts` | mixed | #73 | Specialized transcript/render/input branches, Agents controls, Starmap and Artifacts viewers. Existing clarify/approval/secret and bounded renderer coverage is not zero. |
| `workspace-native-bridges` | mixed | #73 | Files/PTY/Review/browser and onboarding children, connection transport branches, native OS roles. Backend cwd is not phone path; not every host operation is a permanent non-goal. |
| `system-notifications-status` | mixed | #127 | System maintenance/background/skew, logs Copy/jumps/match actions, notification plugin/sound policy conflict #99, statusbar resource children and Radio. Existing log feature candidate is not full maintenance parity. |
| `dynamic-boundaries-drift-engine` | mixed | #266 | Review debt: unread native role children, generic primitive affordances, callback registries/factory aliases and runtime-loaded extension templates. Missing implementation: old-to-new drift engine/report and artifact-only workflow. Installed private contents are not enumerated. |

The voice category deliberately reuses the specific open #67 construction owner,
not only umbrella #73. Its existing issue acceptance and the preserved composer
sample agree that dictation is implemented while the conversation engine remains
unconstructed. A historical epic comment saying voice wiring shipped does not
supersede production graph evidence. Local redirect is not misclassified as a
server-owned queue steer writer; #205/#266 retain that separate authority gap.

#266 is still an actual missing drift-engine/workflow deliverable: this source
ledger does not produce changed i18n/state/test/citation artifacts or its required
artifact-only workflow. That is implementation debt, not merely missing prose.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Catalog Add, repository review, scoped install, uncertain outcome, update/schema and detail uninstall | omission | No general host package-management flow; independent list/toggle is narrower | pill-owed: #73 — machine records trace actual host and Electron authorities, not only labels |
| Desktop JavaScript half loader | omission | Bundled Kotlin modules only | non-goal: ADR 0003 excludes device-side Desktop JS execution, not the Agent half |
| Blanket historical catalog/install non-goal | drift | Historical page could exclude portable host functionality | #73 — later-source qualification in settings-plugins.md preserves the old evidence without endorsing that inference |

## Visual report

- pending: #234

Source reading only. No comparative renderer/device captures or installed-host
acceptance were attempted. Verdict ceiling remains **Concern**. Source bounds,
family ownership and record regression tests are evidence-integrity checks, not
independent semantic review or rendered approval.
