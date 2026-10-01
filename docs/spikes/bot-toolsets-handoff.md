# Existing Bot Toolsets — implementation handoff

## Base and integration ownership

Isolated branch `feat/bot-toolsets` starts at fetched `origin/main`
`fbdda921252df85b1f73f8a1342f21ce59a61128`. It is **not** stacked on the avatar branch.
The source worktree `hermes-mobile-bot-avatar` was inspected read-only.

The only shared prerequisite is `PluginHost.requestAtEndpointGuarded`. Its implementation
was reviewed against avatar `93443528`: unsupported hosts refuse; the caller predicate is
joined to endpoint/live-RPC ownership inside the existing immediate dispatch callback,
and checked again after the reply. This worktree's `PluginHost.kt` is identical to that
reviewed file (verified with `git diff 93443528 -- …/PluginHost.kt`). No avatar importer,
UI, transport asset support, dependency or capture changes were imported.

Planned merge: land the reviewed avatar seam first, then merge/rebase this branch and
retain one identical shared seam. Resolve BotManagementSheet/BotsManagementViewModel/
BotsPlugin additions additively (avatar + toolsets), never replace either editor with
the other branch's entire file. If toolsets lands first, the seam is independently
covered by its real-GatewayPluginHost dispatch tests; avatar should reuse it unchanged.

## Inspected contract

Read-only upstream `587e673e2a2fae0616d8b750bb189217080f621a`:

- `tui_gateway/methods_profiles.py:345-405`: describe name and boolean pin; independent section receipts.
- `tui_gateway/methods_profiles.py:567-588`: server order, labels, descriptions, counts,
  and default-off rows disappearing when disabled.
- `tui_gateway/methods_profiles.py:637-727`: no model keys on a toolset write; empty
  toolsets removes the CLI pin; nonempty writes an explicit CLI selection.
- `hermes_cli/tools_config.py:111`: pinned default-off disappearance allowlist;
  `:722-751`: platform filtering, preserved nonconfigurable entries, known-toolset
  recording, and disabled-toolset reconciliation. This is not a global runtime-tools editor.
- `apps/desktop/src/plugins/hermes-bots/profile-config.tsx:395-427,583-586`: Desktop
  ordered checkboxes, grouped save, and its all-selected/none-to-empty behavior.

Android deliberately does **not** copy Desktop's all-selected-to-empty shortcut:
all-selected remains an explicit pin, and no selection cannot Save. Only a separate
Restore defaults confirmation sends `enabled_toolsets: []`. No invented confirm RPC
or confirm field exists. The only mutation keys are `name` and `enabled_toolsets`.
Skills, MCP, model, identity and creation/duplication writes are excluded.

## Authority and failure behavior

Description requires exact name, required literal boolean `toolsets_pinned`, an array
of unique nonblank/unpadded names, and literal boolean `enabled` for every row.
Unrecognized optional presentation fields do not create write authority.

Save and restore reread their baseline first. A changed baseline refuses the write.
This is a known-staleness check, **not CAS**: another client can still race after
preflight. A literal `ok:true` and `applied.toolsets:true` is followed by another named
read. Save requires pin true, exact desired enabled names, and existing unchecked rows
disabled. Only pinned-upstream default-off names plus yuanbao may disappear when
unchecked; unknown missing rows and unexplained added enabled rows are unconfirmed.
Restore requires pin false and publishes the actual returned default rows.

Endpoint/profile/dialog-revision tickets reject stale callbacks. Accepted pending
operations remain keyed to endpoint/profile across close/reopen ABA. Reopening while
pending disables writes and performs a fresh read after completion; a separate read
sequence prevents the earlier reopening read winning. Uncertain writes are consumed,
never automatically replayed. The originating dialog predicate reaches the immediate
wire seam, not merely a pre-suspension check.

## UI and capture boundary

Existing Edit only, server-order checkboxes with 48dp minimum row targets, checkable
role/state, counts, descriptions, independent Save and default confirmation. Skills
and MCP servers remain visible WIP. New/Duplicate Toolsets remains WIP.

The `bot-toolsets` capture catalog was registered before the UI. The debug-only
`BotToolsetsParityActivity` uses the production repository, view-model and editor over
an allowlisted synthetic host; saved/restored/unconfirmed states execute actual
production actions. Loading holds the real request and preserves its real timeout.
The fixture owns no real Gateway or profile. Captures are a focused editor section,
not proof of the full sheet layout or recorded Save gestures.

Catalog states: loading, defaults, pinned, changed, all-selected, empty-selection,
reset-confirmation, saved, restored, error, empty, unconfirmed. Capture dark/light
Android pixels and platform accessibility XML; verify Checkbox checkable/checked/enabled
and 48dp hit regions, not only text presence. Browser Desktop capture is not Android
platform semantics evidence. Desktop screenshot/report and Android emulator captures
are **not produced yet**. No visual parity approval is claimed.

Deliberate mobile adaptations: independent section save/reset confirmation instead of
grouped advanced save; explicit pin for all-selected; descriptions/counts and native
checkbox touch rows. Unsupported skills/MCP configuration remains an omission with
visible WIP controls. Native per-toolset configuration is not implemented.

## Verification actually performed

No Gradle invocation, commit, push, APK install or emulator capture was authorized or run.
Standalone cached Kotlin/JUnit verification compiled the changed repository, view-model
and **current worktree PluginHost**, then ran repository/VM/real-host dispatch tests:
**16 tests passed**. Separate cached Kotlin Compose compilation included management
wiring, editor, fixture and tests; **5 tests passed** (three Compose journeys including
the registered production route, two deterministic fixture tests).

Reproduction scripts (scratch-only outputs):

- `$TMPDIR/toolsets-check.py`
- `$TMPDIR/toolsets-compose-check.py --run`

These checks read avatar's cached compiled collaborators/debug resources without
modifying that worktree. They are not a clean app build. JVM compiler 2.3.20; Compose
compiler 2.3.21. Robolectric ran API 34, with a harmless Java-17/API-36 availability
warning. Product-copy gate, existing parity structure gate and `git diff --check` passed.
The structure gate does not certify this new surface's missing pixels.

Parent-owned next gates: grant sequential Gradle; run focused and full unfiltered
checks/builds; capture actual paired images/accessibility evidence with exact APK/source
hashes; review remaining Desktop copy/layout divergence; then commit/push only on grant.
