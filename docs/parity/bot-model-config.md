# Existing Bot model configuration — bounded partial parity

## Pin

Inspected read-only checkout `scratch/hermes-agent-reference-587e` at
`587e673e2a2fae0616d8b750bb189217080f621a`:

- `tui_gateway/methods_profiles.py:345-405`: named description and independent
  configure receipts; `ok:true` alone does not mean a model was written.
- `tui_gateway/methods_profiles.py:637-655`: expensive/data-policy guard and
  consent-only `confirm_expensive_model` resend.
- `apps/desktop/src/plugins/hermes-bots/profile-config.tsx:523-638`: dirty model
  pair, model-only confirmation, and Desktop's separate CLI reset fallback.
- `apps/desktop/src/plugins/hermes-bots/model-picker.tsx:30-100,123-250`:
  bounded inventory, custom input, provider aliases and legacy model objects.

## Delivered scope

Existing Bots → Edit… → Edit profile includes independent **Save model**.
`profiles.describe {name}` must echo the selected Bot's name before its
`model.provider` and `model.default` seed the editor. Catalog loading uses
`model.options {profile, include_unconfigured:true, explicit_only:false}`.
Each read has a 20-second deadline. An empty, unavailable or timed-out inventory
leaves manual entry available; it never replaces a typed or unknown selection.

Writes contain only `{name, provider, model}`. A warning is not success, even
with `ok:true` or an optimistic `applied.model`. Only explicit consent resends
that same pair and name with `confirm_expensive_model:true`. A literal
`ok:true` and `applied.model:true`, followed by a matching named description
and exact pair readback, are required to show **Model saved.**

Callbacks carry the endpoint generation, Bot and dialog revision through a
rendered snapshot. The production `requestAtEndpoint` door fences immediate
wire dispatch. Accepted writes stay pending across dialog A→B→A; stale results
cannot paint a replacement dialog. Uncertain writes are consumed, not retried.
Reopening performs a new read and requires a new explicit user save.

No credentials, `model.save_key`, CLI resets, session model changes or hot-turn
mutations are part of this feature. Half pairs, blank reset and padded values
are refused rather than normalized into another identifier. Avatar editing,
skills/toolsets, model selection during creation/duplication and reset remain
visible WIP controls. This is not full Desktop profile-editor parity.

## Verification boundary

- `BotsModelRepositoryTest`: exact read/write payloads, guard receipts,
  model-only confirmation, false/malformed/missing application receipts,
  foreign/mismatched readback, invalid pairs, aliases and legacy inventories.
- `BotsModelDispatchTest`: production GatewayPluginHost plus a contract-faithful
  transport latch before wire dispatch, across describe/options/save/confirmation.
- `BotsModelViewModelTest`: endpoint ABA, dialog ABA, stale reads and callbacks,
  pending write duplicate suppression, consent cancellation and edit revocation,
  uncertain-write consumption, inventory failure/timeout with draft preservation.
- `BotsModelJourneyTest`: the registered plugin route opens the production editor,
  preserves a custom model, selects real catalog rows, requests explicit consent,
  saves the model-only pair and displays readback success; a captured warning
  callback refuses an endpoint switch before recomposition.

After integrating main `161267d65ed023478a64cf7b935172b38daacdc8` (routine
inspector and PEM redaction fix), full local `check assembleDebug` passed using
`--max-workers=1 --no-parallel -Pkotlin.compiler.execution.strategy=in-process`
and `-Dorg.gradle.jvmargs="-Xmx6g -XX:MaxMetaspaceSize=2g -Dfile.encoding=UTF-8"`.
JUnit XML reports 3,262 debug and 2,594 release tests, zero failures/errors and
one skipped test in each variant. This was an incremental Gradle build, not a
clean build, live Gateway exercise or physical-device acceptance.

The historical debug management fixture still refuses every write. Dedicated
`bot-model-config-synthetic-v1` states now bind real model reads/actions for loaded,
inventory-loading/error, manual, confirmation, saved and save-refused captures.
Confirmation/saved/refusal are explicitly **fixture-staged**, not recorded Save
or consent gestures. Manual requires the real Compose control. Synthetic writes
are allowlisted to one model-only pair and require warning then exact consent;
success remains pending until named describe readback completes. Regression tests
hold that readback and inspect method-plus-params traces. The ordered accessibility
driver and executable instructions are in `docs/workflows/visual-parity-capture.md`.
New aligned model-editor pixel evidence remains outstanding; this is neither a
live backend nor a physical/emulator visual acceptance report.

Fixture follow-up verification (uncommitted working tree based on `65c2bb7`):
9 focused debug tests passed. Full unfiltered `check assembleDebug --rerun-tasks`
passed in the exclusive sequential lane with the same Xmx6g/in-process options
above: 3,268 debug and 2,594 release tests, zero failures/errors, one skipped in
each variant; repository Python gate: 133 tests passed. No emulator capture was
run, and these counts do not promote the pending visual report to acceptance.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Advanced editor groups dirty sections in one save | mobile-adaptation | Existing Bot model has a separate explicit Save model action, isolating model warnings from identity writes on the scrollable phone form | `BotModelEditor`, `BotsModelRepositoryTest`; bounded per-section mutation scope |
| Provider/model selects and custom form | mobile-adaptation | Touch menus with bounded height and manual fields; unmatched current values remain visible and unchanged | `BotsModelJourneyTest`; phone touch/viewport budget |
| Clear model pin via CLI and configure on creation | omission | Reset and new/duplicate model controls remain disabled WIP | coming soon — no bounded reset contract or creation model write is delivered |
| Skills, toolsets, MCP and avatar authoring | omission | Existing unsupported controls remain WIP; this change does not implement them | coming soon — `BotManagementSheet` |
| Pinned aligned rendered comparison | omission | Registered Compose interaction evidence, but no new Desktop/Android pixel comparison | deferred: #194 — no full visual parity approval |

## Visual report

- pending: #194
- No new aligned rendered report. Historical Bot identity screenshots do not
  certify this editor's inventory, warning, error or saved states.
