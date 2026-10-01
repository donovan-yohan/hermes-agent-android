# Model capture contract v2

Status: **bounded model-editor visual acceptance completed** with 14 Android and
14 Desktop runtime receipts. [Paired report](../media/bot-model-v2/REPORT.md).
No existing PNG, receipt, fixture implementation, source hash or failed-validation
history is relabelled. Default dispatch still selects v1; explicit v2 dispatch now
uses actual runtime export and proof, not restamped fixture metadata.

Authoritative machine contract:
`visual-capture-surfaces.json` → `surfaces.bot-model-config.fixture_versions.bot-model-config-synthetic-v2`.
The target Desktop pin remains `587e673e2a2fae0616d8b750bb189217080f621a`.

Resolve before capture:

```sh
python3 scripts/visual_parity_contract.py describe \
  --surface bot-model-config --state bot-model-confirmation --theme dark \
  --fixture-id bot-model-config-synthetic-v2 --platform desktop
```

Use `platform_spec`, never the legacy surface selector. `selector` and
`capture_boundary` are semantic boundary identifiers; `locator` is the executable
structured locator. Role locators mean `getByRole(role, {name, exact:true})`.
Require exactly one visible match. Android locators name exact accessibility labels.
The future request intentionally does not inherit the historical `desktop_selector`.

## Inputs

Each receipt carries exact `capture_inputs`: skin `mono`, explicit receipt theme,
locale `en-US`, timezone `UTC`, clock `2026-09-17T16:00:00Z`, timers `live`.
Freeze wall-clock formatting only, not request timers.

Exact `synthetic_inputs`: name `synthetic-planner`, title `Synthetic Planner`,
description `Reviews release plans`, soul `Review synthetic release plans.`,
provider `synthetic-provider`, provider_name `Synthetic Provider`, initial_model
`synthetic-planner-v1`, requested_model `synthetic-planner-v2`, confirmation_message
`Synthetic model cost and data policy require confirmation.`

Inventory routing is explicit: Android `inventory-profile-parameter` with profile
`synthetic-planner`; Desktop `bot-scoped-websocket` with that profile. Neither is
permission to omit real routing assertions. Retain raw safe RPC evidence alongside
the normalized receipt.

## State/platform agreement

All Desktop states start with recorded roster Edit and Advanced gestures.

| State suffix | Desktop locator / presentation | Android presentation / origin |
|---|---|---|
| loaded | exact role dialog `Edit profile`; `editor`; describe/options loaded, zero writes | inline editor; original recorded scroll |
| inventory-loading | `Edit profile`; `editor-loading`; actual options pending, zero writes; no Android-label equivalence | inline loading; original scroll and 20-second production deadline |
| inventory-error | `Edit profile`; `manual-fallback`; actual options refusal, native manual fields, original pair, zero writes | inline explanatory banner; original scroll |
| manual | `Edit profile`; `manual-editor`; real provider menu → Enter manually, original pair, zero writes | actual Enter manually tap, then Model ID scroll |
| confirmation | exact role dialog `Switch to synthetic-planner-v2?`; `shared-dialog`, **not Edit profile**; real change/Save; guard message visible; one unconfirmed model-only warning; authoritative v1 | `inline-staged`; production update/Save staged by fixture, then recorded scroll; not recorded consent |
| saved | `Edit profile`; `saved-reopened`; real Save + shared-dialog confirm; two model-only writes; confirmed second write; close, roster reopen, named describe after apply; visible v2 | `inline-staged`; staged production update/Save/confirm/readback, recorded scroll to Model saved.; not Desktop reopened semantics |
| save-refused | **runtime-discovered**, not preselected; exactly one initial unconfirmed refusal, no warning/retry, host v1 unchanged | `inline-staged`; initial production configure refusal, original recorded scroll; no fabricated Save gesture |

For Desktop initial refusal, the worker must inspect native behavior. Primary
subartifact is `initial-refusal-notice` with its observed unique locator, labels,
and PNG hash. If the editor closes, also supply `refused-reopened`, independently
located at exact role dialog `Edit profile`, with its own PNG hash. Never crop the
full shell and label it a dialog. If native first-refusal notice cannot be captured,
fail capture; do not substitute a confirmed-retry refusal. The completed packet
observes the native initial-refusal alert and separately reopened editor; its
receipt retains the actual runtime-discovered boundary rather than assuming it.

## Receipt schema (additional to every unchanged v1 provenance check)

- `capture_mapping`: exact selected `platform_spec` object. It declares
  `capture_boundary`, structured `locator`, `selector`, `selector_kind`,
  `presentation`, `interaction_semantics`, `action_origin`, `ordered_actions`,
  `assertions`, `required_events`, `required_labels`, and optional `runtime_discovery`.
- `capture_inputs`, `synthetic_inputs`: exact registered inputs above.
- `screenshot_sha256`, `fixture_implementation_sha256`: 64 lowercase hex characters.
  Retain the actual reconstructible fixture implementation/patch and raw PNG.
- `state_proof.source`: `runtime-capture-worker`.
- `state_proof` repeats the exact mapping's `selector`, `locator`, `presentation`,
  `interaction_semantics`, `action_origin`, `ordered_actions`; `events` matches
  `required_events` in order. `nodes` retains actual visible text/content_description
  and includes every `required_labels` entry. `locator_matches` is integer 1.
- `action_evidence`: one object per ordered action, with `action`, matching `origin`,
  consecutive integer `sequence` starting at 1, and observed nonempty `nodes`.
  These sequence numbers order UI actions only. Retain actual gestures/trees;
  declaring the plan is not execution proof. Android additionally retains all
  existing `ordered_action_evidence`, pre-tap ancestor, and final accessibility checks.
- `model_calls`: ordered normalized objects with positive `sequence`, `profile`,
  `patch` containing **only** provider/model, boolean `confirmed`, `outcome`, `response`.
  RPC/read/reopen sequence numbers share one monotonic event ordering. First call
  unconfirmed, second confirmed. Warning response is exactly
  `{ok:true, confirm_required:true, confirm_message:<registered message>, applied:{model:false}}`;
  success is `{ok:true, applied:{model:true}}`; refused outcome has null response.
  Profile and pair must match registered inputs. No unrelated title/SOUL mutation.
- `authoritative_model`: v2 only after saved; otherwise v1.
- `fields`: visible provider/model pair for read/manual/saved editor captures.
  Explicit null for confirmation/refusal and **Desktop inventory-loading only**:
  the receipt must not infer hidden editor fields from a modal/notice crop or spinner.
  At Desktop pin `587e673e2a2fae0616d8b750bb189217080f621a`,
  `apps/desktop/src/plugins/hermes-bots/model-picker.tsx:148–153` returns only
  `GlyphSpinner` while `isLoading`, before either field-rendering branch.
  Loading still requires original `authoritative_model`, the named initial v1
  `describe_reads` RPC proof, zero writes, pending scoped inventory and the complete
  per-PNG request/deadline bracket below. Missing `fields` keys are never accepted;
  loaded/manual/error/saved editor states require their exact visible pair.
  Android loading field requirements are unchanged; inline content remains in nodes.
- `describe_reads`: nonempty ordered `{sequence, profile, model}` objects; initial
  named read must precede writes and contain v1. Saved requires named v2 read after
  apply. Desktop saved also requires `reopen_sequence` strictly between apply and
  that final read.
- `inventory`: `{method:"model.options", scope:<exact transport_mapping[platform]>,
  outcome:"loaded"|"pending"|"refused"}` matching the state. Manual/error additionally
  require `manual_fields_visible:true`.
- Loading `loading_bracket`: primary `screenshot_sha256`, nonempty `request_id`,
  `basis:"monotonic-since-interception"`, and `before` / `after` objects each with
  the same request_id, `pending:true`, explicit `response:null`, `error:null`, and
  numeric `elapsed_ms`. Require `0 <= before <= after < 20000`; NaN, bools and
  timed-out/error results fail. Each theme/PNG has its own bracket. Android's
  original screenshot/focus/accessibility deadline proof is still required too.
- Desktop refusal `discovery`: `phase:"initial-write-refused"`, boolean
  `editor_closed`, ordered `artifacts` as described above. Each artifact retains
  `kind`, observed structured role or CSS `locator`, `locator_matches:1`,
  `screenshot_sha256`, and nonempty `nodes`. The notice hash must equal the primary
  receipt PNG. A reopened artifact additionally needs original provider/model `fields`,
  `reopen_sequence`, and a named `describe_read` after that reopen and the refusal.
  Full-document CSS selectors are refused.

Unknown fixture versions, mismatched mappings, undeclared top-level selector /
boundary overrides, wrong phases, writes, action origins, fields, scopes, labels,
readback order and missing loading/refusal proof fail validation. Existing Android
APK, installed identity, activity, ordered accessibility and loading checks and
all secret/private-path rejection remain in force.

```sh
python3 scripts/visual_parity_contract.py check-receipt \
  --platform desktop --receipt path-to-new-receipt.json
```

The validator checks evidence structure and consistency, not the authenticity of
pixels. A worker must retain raw safe traces, inspect the genuine UI, hash the
actual files, and fail when observations differ. Unit-test receipts are synthetic
test inputs and must never be published as capture results.

## Visual report

- report: ../media/bot-model-v2/REPORT.md
- commit: fba060888b21a3ce0c456809f1f464fd0e5938ba
- Capture commit above is the dirty Android base, not an unchanged commit APK; see report provenance.
- Desktop packet `model-contract-v2-packet-final` has genuine captures for all
  seven states in both themes. Its original validation history remains **12 accepted,
  2 rejected**: loading truthfully reported `fields:null`. Source-backed correction
  of this validator requirement accepts all **14 unchanged receipts** on revalidation.
  Dark loading bracket: **1789→2350 ms**; light: **1957→2499 ms**; both stay pending
  with null response/error, strictly before 20000 ms. All 116 packet files remained
  byte-identical and all 115 original manifest entries verified. The new sibling
  `model-contract-v2-revalidation-loading-fix.json` records validator/receipt hashes
  and fresh CLI results; original `validation.json`, accepted/rejected directories,
  PNGs, receipts and capture history were not rewritten or moved. This is contract
  revalidation, not a new capture or visual-parity approval.
- Android now has 14 fresh v2 receipts with actual allowlisted runtime export,
  explicit version dispatch, normalized inputs and per-PNG pending brackets.
  Fixture source bytes and installed/local APK hashes match; original dirty
  base and runtime patch are retained in the paired report. No v1 restamping.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Shared confirmation dialog and reopened saved editor | mobile-adaptation | Inline fixture-staged production actions, not recorded Save/consent gestures | State/platform agreement above |
| Wide Advanced editor / native dialogs | mobile-adaptation | Scrollable phone controls and inline staged results preserve bounded model contract without claiming recorded consent gestures | [Paired report](../media/bot-model-v2/REPORT.md); explicit phone viewport and action-origin boundary |
