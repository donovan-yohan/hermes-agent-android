# Bot management: implemented contracts and finite remainder

## Pin

Upstream source and tests were read at
`e27448b231498e79ade668d68c0b6c6206951206` in the disposable read-only checkout.
Every upstream citation on this page names that revision. This is a functional
contract audit, **not** a claim of all-feature or visual parity.

## Implemented production paths

- **New bot:** `profiles.create`, explicit `mirror_credentials:true`, `share_auth:false`,
  `clone_channels:false`, `clone_all:false`, `no_alias:true`. The identifier is validated
  as given; the server remains authoritative for reserved names/collisions. Provider
  credentials stay on the host; no credential values travel through this form or storage.
  The display title and millisecond creation stamp are then stored in `ui_meta.hermes-bots`.
  Source: `tui_gateway/methods_profiles.py:302-342`;
  `hermes_cli/profiles.py:309-326`. Creation is never automatically retried.
- **Edit profile:** name is immutable; display title, description and SOUL.md are editable.
  `profiles.describe` supplies the actual snapshot, `profiles.configure` writes it.
  Every named applied field must be literally true; an exact describe/metadata readback
  precedes success. Source: `methods_profiles.py:345-405`.
- **Duplicate:** proposes a collision-free `-2`…`-99` suffix without truncating the suffix,
  allows a different identifier, calls `profiles.create {clone_from:<source>}` and copies
  scalar appearance fields/title, never source chat ids, placement or creation age.
  Host-side clone-config includes SOUL.md, skills and identity memories, not session history
  (`hermes_cli/profiles.py:36-39,1269-1292`). Avatar asset copying is still missing.
  Desktop source: `apps/desktop/src/plugins/hermes-bots/profile-ops.ts:305-367`.
- **Delete: unavailable, visible and disabled.** The CLI argv is real, but a
  successful subprocess does not retire the host's pooled backend handles. Desktop
  prefers `host.deleteProfile` and refuses its source-profile fallback
  (`profile-ops.ts:381-409`). Android's PluginHost has no lifecycle-aware deletion
  door, and namespaced PluginRest cannot call core `/api/profiles`. The repository
  returns Unsupported without sending any RPC (default/invalid ids are Rejected).
  There is no universal CLI fallback and no deletion-parity claim.
- **Pin/hide/section membership:** fresh roster read, preserve the complete existing
  `hermes-bots` namespace, CAS against its current revision, literal applied receipt,
  exact patch readback. Older Gateways without `ui_meta_revisions` fail closed rather
  than overwrite another client's namespace. Source: `methods_profiles.py:255-275,591-634`.
  Current Desktop metadata is **server-synchronized**, not purely local
  (`apps/desktop/src/plugins/hermes-bots/data.ts:314-369`); older read-slice comments
  describing all BotMeta as local are superseded by this implementation.
- **Sections:** create, rename, move up/down, delete and explicit Move to section/Unassigned.
  Empty section records and ordering require an application-supplied
  `BotStorageEndpoint(stableId, generation)`. Keys encode only the stable identity,
  not the generation; generation fences admission, queued work, reads and publication.
  Set is read back before success. Endpoint switches clear the held section list.
  The old unowned `bot-sections-v1` key is deliberately not imported into any endpoint.
  **Production integration:** `HermesApplication` injects
  `connectionSwitch.botStorageEndpoint` into `BotsPlugin(storageEndpoint=...)`.
  `ConnectionSwitchController` publishes an atomic saved-endpoint identity/address-revision
  plus generation snapshot; `BotStorageIdentity.kt` derives the stable storage identity.
  Unknown identity still disables local section CRUD rather than falling back to global storage.
  Stored section receipts are validated in full before enabling writes: non-array JSON,
  malformed entries, blank ids/names and duplicate ids (including trimmed collisions)
  fail closed. No partial list is adopted and the raw record is left untouched. The
  disabled section dialog asks for a valid restored record and reconnect; reopening or
  reconnecting with the same invalid record cannot unlock writes. Missing storage and
  an explicit empty array remain valid, distinct from malformed data.
  Members carry sectionId and sectionName server-side;
  section records are reconstructed from those fields on another device. Section
  rename/delete walks the admitted roster members, reports partial failure without
  replay, and refreshes. Concurrent/new members can therefore make a section reappear;
  no transaction across multiple profiles is claimed. Empty section controls are reachable
  under New → Sections. Source: `user-sections.ts:38-46,92-109,118-175`.
- **Once routine:** the form enables delay amount/unit and dispatches the documented
  `schedule:"in 30m"` one-shot syntax, not Desktop's recurring bare `"30m"` bug.
  Source: `cron/jobs.py:823-832`; Desktop `cron.tsx:672-675`.
  New and Create require a fresh matching scoped list receipt (Ready or confirmed
  Empty), a live connection and the original endpoint/selection. Unscoped, mismatched,
  loading, unavailable, rejected and failed refreshes cannot authorize add. Admission
  is rechecked at submit and after the pre-dispatch yield. Existing uncertainty locks
  remain. Roster and embedded-sidebar callbacks carry their rendered roster snapshot;
  a departed endpoint is rejected before navigation or routine reads.

New management state is plugin-scoped. Every admitted dialog captures the roster's
endpoint, every RPC uses `requestAtEndpoint`, and late results cannot publish into a
replacement endpoint or dialog. All backend exceptions/CLI output are discarded;
only product-owned result sentences are rendered. No Gateway session repository,
ChatViewModel, notifications or global pins were changed by this management lane.

## Finite remaining feature list

These are **omissions**, not invented backend limitations:

1. **Advanced identity configuration:** model/provider selection and expensive-model
   confirmation, installed skills/toolsets/MCP toggles, and the richer inheritance/
   onboarding flow. Existing JSON-RPC already supports the first group:
   `methods_profiles.py:345-405,637-730`. Android's form currently only edits
   title/description/SOUL.md; its Advanced marker is not that feature.
2. **Avatar authoring and complete duplicate appearance:** upload/clear/generation,
   Blobatar shape/mood/lock, pet selection and copied image asset. Gateway read/write
   asset contracts exist at `methods_profiles.py:408-460`; Android still only has its
   bounded read/display path. This is UI/asset-production work, not absent backend support.
3. **Canonical profile-id rename and target selection:** the editable display title is
   not a directory rename. Core REST has `PATCH /api/profiles/{name}`
   (`hermes_cli/web_routers/profiles.py:951-968`). The bundled Android PluginRest is
   restricted to `api/plugins/<id>` (`plugins/PluginRest.kt:65-101`), and PluginHost
   exposes no `deleteProfile`/rename lifecycle door. Multi-connection target creation,
   pooled-backend teardown and alias routing remain outside the single-live-endpoint seam.
   Desktop's richer delete entry is `apps/desktop/src/sdk/index.ts:798-858`.
4. **Roster extras:** New group chat/Manage groups/integrated group rows; recent-session
   and new scratch-chat actions; section drag/multiselect/Undo, unanimous remote-rename
   adoption and legacy section-name backfill; activity-toast preference;
   gateway filter/live-turn liveness/accessibility live-region deltas. Existing row
   placement/reordering is implemented, but those controls are not. Source owners remain
   `bot-row.tsx`, `user-sections.ts`, `user-sections-ui.tsx`, `roster-pane-toolbar.tsx`;
   the specific existing divergence rows remain in `bots-roster.md` and `bot-group-chat.md`.
5. **Screen surface:** streaming/control/install/image switching and auto-open are
   missing Android UI/transport adaptations, not a promise hidden behind Open Screen.
   Desktop opens a workspace through `screen-open.tsx:37`; that workspace host facility
   is not an Android plugin SDK door. `screen-autoraise.ts:34` identifies browser/computer
   tool activity; the current disabled controls do not implement its event/lifecycle policy.
6. **Routine management:** full inspector, edit, run-now, run history and legacy
   auto-pause/delete policy. `cron.manage` explicitly accepts only list/add/remove/pause/
   resume and rejects other actions (`methods_tools.py:1354-1378`). Core REST supports
   detail/history/update/trigger at `hermes_cli/web_routers/cron.py:616-624,657-674`,
   but the Android plugin's namespaced REST door cannot access `/api/cron`. The list
   already carries useful inspector fields; presenting those is still Android work,
   not a backend blocker. Legacy pause/remove can use existing RPC; policy/UX is missing.
7. **Durable uncertain-write recovery:** forms prevent automatic retry during their
   admitted operation; routine creation additionally retains its uncertain owner lock
   for the model lifetime. There is no cross-process receipt journal or create idempotency
   contract here (`methods_profiles.py:302-342`, `methods_tools.py:1368-1375`). Refresh
   and explicit reconciliation are required; disabled submit is not durable recovery.
8. **Rendered acceptance:** the new dialogs, menus, section actions and Once form still
   need aligned Desktop/Android captures and Compose execution in the parent build lane.
   Existing avatar/roster screenshots do not certify these changes.

## Verification

Malformed-storage follow-up: isolated compilation of the changed management model
and its actual JVM tests reproduced the `{}` admission bug, then passed **11 tests**
after the fix. Cases cover malformed/mixed entries, duplicate ids, raw-record
preservation, create/rename/reorder/delete refusal, invalid reconnects, and valid
reload recovery. Logs: `bot-storage-validation-red.log` and
`bot-storage-validation-green.log` in the active profile scratch directory.
No Gradle invocation was made for this follow-up; parent acceptance remains separate.

No Gradle invocation or commit was made in this lane. The isolated compiler rebuilds
changed production sources (no fabricated PluginHost/Compose stubs), then uses the
application's actual compiled collaborators and Gradle-resolved Robolectric runtime
classpath/resources. Final runs: **314 JVM tests passed; 20 registered Compose
journey tests passed on Robolectric SDK 34**. `git diff --check` also passed.
This remains distinct from the parent's clean Gradle acceptance gate.

Deterministic regressions cover stable A/B persistence and A→B→A restoration;
switches before a mutation coroutine, inside storage set, inside readback and inside
an uncancellable initial load; refusal to import the unowned legacy key; unknown
identity refusal; scope admission and disconnect before submit/dispatch; malformed
creation ids at both parser and repository seams; and actual registered roster and
sidebar semantics callbacks invoked after the endpoint changes but before collection.
Removing the callback fence in a scratch mutation made both registered-callback tests
fail (19 tests, two failures); production wiring passes those same tests. The null-id
regression already passed with the cached serialization version's `isString` behavior;
`JsonNull` is now also excluded explicitly, not reported as a reproduced failure.

Evidence files in the active profile scratch directory:
`bot-review-red-storage.log`, `bot-review-red-scope.log`,
`bot-review-mutant-callback.log`, `bot-review-jvm.log`, `bot-review-compose.log`.
The compiler emits existing coroutine opt-in warnings. Robolectric executes SDK 34;
its SDK-36/Java-21 notice does not mean these SDK-34 tests were skipped.

## Target refresh follow-up

The [587e673e source audit](../spikes/upstream-bot-refresh-587e673e-2026-10-01.md)
compares the previous target with `587e673e2a2fae0616d8b750bb189217080f621a`.
It records route-owned Screen profile/RFB targeting, ticket-only credentials,
reviewable scoped browser-comment drafts, group code wrapping, restricted Hub
capabilities and stacked slash-skill dispatch. These missing Android capabilities
are omissions, not new backend blockers. This addendum does not move the historical
contract citations, test results or rendered evidence above.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Profile dialogs and context menus | mobile-adaptation | Scrollable bottom-sheet forms and accessible row actions with the same named operations | Phone keyboard/viewport and touch targets require a single reachable form; exact Gateway contracts above |
| Local metadata fallback on old Gateways | drift | Writes require server CAS and readback; no unscoped local preference fallback | #189; failing closed prevents silently replacing another client's whole metadata namespace |
| Rich identity/group/screen/routine controls | omission | Explicit WIP controls and finite remaining list above, not full-feature claims | coming soon — #189/#191/#192/#194; exact available and unavailable SDK contracts above |
| Screen, browser-comment group drafts/code wrapping, Skills Hub, stacked slash-skill dispatch at 587e673e | omission | Native transport, rich drafts/picker and command-dispatch pipeline are not implemented; completion/display helpers alone are not execution parity | coming soon — #189/#190/#192; [exact target audit](../spikes/upstream-bot-refresh-587e673e-2026-10-01.md), not a backend blocker |
| Once sends a recurring duration | mobile-adaptation | Sends explicit one-shot `in <duration>` | A one-time control must not authorize recurring work; actual Gateway contract and failing-then-passing model regression |
| New menu lacks an empty-section management submenu | mobile-adaptation | New → Sections keeps empty folders editable without a drag target | A touch list drops empty drag slots but must retain rename/delete/order access |

## Visual report

- pending: #194 — new management forms/actions are not represented by historical captures.
