# Core Scheduled jobs parity

## Pin

| Authority | Revision |
|---|---|
| Hermes Desktop core Cron and Gateway REST | `5fc490e2b34123212d73ef316086f939567a725a` |
| Android starting point | `ed025e8148b30bfae62a229fe71912b750897510` |

This is the core **Scheduled jobs** surface, not the Bots plugin's separate
Routines pane. Historical [Bot Routines evidence](bot-routines.md) retains its
own source pins and delete/create behavior. Desktop has no dedicated Cron page
inside Settings. Job settings live in the Scheduled jobs editor.

## Source contract

All upstream paths in this section name the exact Desktop/Gateway pin above.

| Behavior | Source |
|---|---|
| List, search, selected detail, confirmed delete and create/edit | `apps/desktop/src/app/cron/index.tsx:310-440,482-809` |
| Detail actions, metadata, prompt/script and history | `apps/desktop/src/app/cron/index.tsx:854-1144` |
| Seven schedule presets in order, delivery and model/blueprint editor | `apps/desktop/src/app/cron/index.tsx:113-121,1147-1602` |
| Provider/model reset, multi-delivery, script-only prompt preservation | `apps/desktop/src/app/cron/cron-job-model.ts:7-15,25-41,60-105,134-157` |
| Blueprint defaults and field vocabulary | `apps/desktop/src/app/cron/blueprints.tsx:18-89` |
| Sidebar three-job preview, load-more, five runs and action order | `apps/desktop/src/app/chat/sidebar/cron-jobs-section.tsx:30-45,156-221,294-313,395-495` |
| Source-aware conversation admission and refreshed verdicts | `apps/desktop/src/app/cron/open-cron-run.ts:15-90,138-162` |
| Copy | `apps/desktop/src/i18n/en.ts:2948-3088` |
| REST paths and long synchronous trigger | `apps/desktop/src/api/cron.ts:19-168` |
| Request shapes and registration failure | `hermes_cli/web_models.py:315-342`; `hermes_cli/web_server_cron.py:236-287` |
| Profile hint fallback and aggregate dedupe | `hermes_cli/web_routers/cron.py:81-156` |
| Item mutations and one-shot completion | `hermes_cli/web_routers/cron.py:633-692` |

The REST record includes the full editable prompt and structured schedule. The
formatted `cron.manage` result only has a prompt preview and schedule label, so
it is not an edit authority. No edit/trigger/history RPC is invented.

## Ownership and outcomes

`GatewayCronApi` captures the exact authenticated HTTP transport and endpoint
lease before suspension. Closing a scope and admitting its actual OkHttp enqueue
share a monitor. Reconnects cannot reuse an older leg; endpoint/profile/selection
identity travels through the controller. Response and refusal byte buffers are
wiped after decoding. Jobs are transient backend snapshots, never a new disk
scheduler or a production fixture seed.

Every item call explicitly names its owner profile, preflights the scoped
inventory and validates the response owner. **Upstream's profile parameter is a
hint, not a strict isolation guarantee.** If a job disappears after preflight,
the server can fall back to a same-ID job in another profile. Response validation
can refuse publication, but cannot undo that server mutation. Delete receipts do
not name an owner. Atomic strict-owner isolation needs a backend contract change;
this client does not claim to supply one with a preflight read.

Mutation receipts require fresh named readback. Saved-but-unregistered HTTP 424
retains the saved ID and refuses automatic recreation. An admitted request with
a lost response stays unconfirmed, never an automatic retry. Script-only edits
preserve script/no-agent configuration and omit an unchanged structured schedule.
Default model reset clears model and provider together.

Modern `local-*` sessions with `source=cron` use the same admission checks as
historical cron execution IDs. Closed runs may resume; explicit scheduler
ownership overrides stale activity. A zombie or unknown run opens stored history
without acquiring a writable runtime. `cron_output` rows have no conversation
navigation. A run opener never receives Bot Chat privileges.

## Verification

- Latest-pin Desktop unit suites: eight files, 82 tests passed.
- [Desktop component packet](../media/cron-management-v1/desktop/report.md):
  40 images, 20 paired dark/light states, 340 real UI assertions. Real unchanged
  components and styles, synthetic API boundary, not native shell or live service.
- `tools/cron-smoke/` mounts the real untouched pinned cron APIRouter against
  scratch profile stores. Its Python acceptance covers 17 checks and four tests.
  This is router/storage/manual script-fire evidence, not full Gateway startup,
  production authentication, external delivery or recurring scheduler acceptance.
- `GatewayCronLiveTest` exercises production Kotlin transport/API against that
  actual router. CRUD, scoped inventory, configured-target and blueprint reads,
  readback and harmless script-only trigger passed without an agent/provider.
- Actual-socket regression reproduced a bodyless DELETE replay on HTTP 503 with
  Retry-After zero. A per-exchange guard blocks OkHttp's second send; mutation
  uncertainty remains explicit. Matching data/session/transport regression run
  passed 594 tests, one opt-in live test skipped in the non-opted run.

The integrated working-tree `check assembleDebug :app:assembleDebugAndroidTest`
gate passed with 3,816 debug and 2,865 release test cases, zero failures/errors,
and three opt-in skips across those variants. Lint and repo invariants passed.
The existing instrumented lane also passed eight tests on a dedicated local
API34 arm64 emulator. This is not the CI x86_64/KVM lane or physical acceptance.
Exact committed-head rendering and rolling distribution remain separate gates.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Wide list/detail overlay and compact pointer menus | mobile-adaptation | Phone list/detail navigation and scrollable form, same field/action order | Phone width and keyboard require reachable vertical content; icons retain 48 dp touch targets |
| Hover quick actions and right-click row menu | mobile-adaptation | Always reachable touch actions and long-press menu | A phone has no persistent hover or secondary pointer button |
| `This desktop` for local output | mobile-adaptation | Backend save-only destination, never a promise of Android delivery | The phone controls a host-owned scheduler; output is retained on that host |
| Failed initial list read also renders empty-account copy | mobile-adaptation | Explicit failed read with Retry, not a successful empty account | Mobile network loss is normal and cannot establish that no jobs exist; genuine Desktop failure captured in the component packet |
| Source/profile/connection fencing and uncertain-write outcomes | mobile-adaptation | Scope-owned requests, no replay after unknown creation/trigger outcome | Mobile disconnect/background transitions must not duplicate scheduled work |
| Pointer-hover sidebar actions and tiny unlabelled run peeks | mobile-adaptation | Persistent action buttons, collapsible section with count and a Run history heading | A touch drawer needs discoverable controls and a labelled nested list without hover |
| Pause on terminal or unrecognized job states | mobile-adaptation | Visible disabled Pause with WIP | The current backend may refuse a terminal transition; the phone does not label it Resume or promise an unsupported restart |
| Unconfirmed create retry | mobile-adaptation | Explicit Review previous create, fresh inventory, then Start a different cron | A lost mobile response can hide a successful create; acknowledgement clears the draft and never automatically matches or resubmits it |
| Unconfirmed existing-job mutation | mobile-adaptation | Review current job and explicit acknowledgement of fresh owner-scoped detail | Fresh inventory/detail plus user review releases the local lock without silently replaying a potentially completed mutation |
| Short native form fields without example placeholders | mobile-adaptation | Labels, help and seven preset hints retained; illustrative placeholders omitted | Narrow text fields reserve their viewport for the actual saved value, not a long example |
| Capitalized rounded state pill and compact horizontal run rows | mobile-adaptation | Lowercase wire-state text in the shared compact badge; run date wraps below title | Retains the state's exact vocabulary and readable run titles within a narrow phone column |
| General configuration values beyond the job editor | omission | No invented Cron Settings page | non-goal: this parity port follows Desktop's Scheduled jobs editor; Desktop exposes no dedicated cron configuration page at this pin |

## Visual report

- pending: #191

Desktop references are captured and verified. Android exploration includes all
nine non-editor states in dark/light, but is not immutable-commit evidence.
Final source-bound capture and paired review must be recorded before this page
claims rendered acceptance. The editor remains SecureOn and has no screenshot
bypass; its coverage is Compose journeys and source comparison, not device pixels.
