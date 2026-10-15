# Canonical inputs: compatibility addendum

## Pin

This addendum inspects backend-driven input/control behavior at
`379cf1220fe289b5ecfe8bcf61cd4d5126e140cd` (U). All Desktop path:line citations
below bind to U. The general UI/theme target stays
`36922ad064d65dcf25f8f48df81e1ccf9a55de67`; [vault prompts](vault-prompts.md)
and [composer status](composer-status-stack.md) keep their older inspected pins
and visual ceilings. They are not retroactively certified against U.

## Source, copy and interaction contract

| Changed control | U source / copy / tests | Android |
|---|---|---|
| Staged one-question and multiselect batch | `apps/desktop/src/components/assistant-ui/clarify/pending.tsx:152-207`, `core/confirm-bar.tsx:19`; `apps/desktop/src/i18n/en_clarify.ts:3-22`; `clarify/clarify-tool.test.tsx:751-833,1261-1379` | Pick per question and/or type answer, then Skip or Confirm and continue. Canonical single question uses same staged card. Target progress counts staged choices/nonblank drafts; no-choice fields use only their prompt/placeholder. Legacy immediate single-question fixture retains historical behavior. |
| Model confirmation | `apps/desktop/src/lib/guarded-model-switch.ts:36-99`; `apps/desktop/src/i18n/en.ts:5040-5047` | Keep current model then Switch anyway; back/dismiss declines; captured session/model/endpoint guards explicit consent. No automatic expensive-model approval. |
| Lost/queued admissions | `apps/desktop/src/app/chat/composer/queue-panel.tsx:130-222`, `queue-slot.tsx:47-81`; `apps/desktop/src/i18n/en.ts:3520-3541` | Lost row Discard uses original backend generation; queued Delete uses admission ID. No resubmission. |
| Reply branch continuation and cut | `apps/desktop/src/app/session/hooks/use-session-actions/index.ts:2689-2697`; `apps/desktop/src/app/session/hooks/use-session-actions/branch-boundary.test.ts:8-27`; `apps/desktop/src/api/canonical-protocol.ts:79-88`; `gateway/session_controls.py:530-539` | Resume actual child snapshot before adoption/first send. Carry selected authoritative saved row independently of visible count or cached page. Existing labels, action order, glyphs and error copy unchanged by this repair; no new render claimed. |

New staged-card copy is verbatim: Pick one; Select all that apply; Other (type
your answer); Type your answer…; Skip; Confirm and continue. Model title/fallback,
body/fallback, Keep current model / Switch anyway, and Selection changed — the
model switch was not applied. match U. Lost note,
Discard/tip, Delete and cancellation error match U. Historical immediate-card
Hermes has a question / Type an answer / Send are not claimed as verbatim U
strings. Staged headings use 1 question or answered-of-total progress. Choice
fields use Other (type your answer); no-choice fields use Type your answer….

PendingAnswerJourneyTest drives real ChatScreen/Actions/ViewModel/live-repository/
correlated-dispatch paths for choice/type/retry/batch/canonical restore/pre-ACK
prompts/native-root input and admission actions, then ordinary follow-up send.
Protocol tests separately enforce owner/generation/replay and receipt behavior.
Synthetic native peers prove client routing, not named-profile upstream server
settlement; [that boundary remains blocked](../upstream-compatibility-379cf122.md).

## Divergences

The rendered observations below remain bound to Android `6b42e62` (A).
Current source repair dispositions do not restamp those images or independently
approve changed bytes. New frozen-head CODE/product checks and affected capture
review remain required under #378.

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Clarify card body follows desktop transcript space | mobile-adaptation | Composer-adjacent body capped at 25% screen height; footer stays below it with 48dp targets | Original report shows final-question focus/body scroll and reachable confirmation at reduced available height. This is layout evidence, not physical IME proof. Coordinator disposition retains this adaptation. |
| 1 question or answered-of-total progress, with right question glyph | drift | A added Hermes has a question and fixed N questions. Source repair removes extra staged heading, counts staged picks/nonblank drafts and uses target Tabler MessageQuestion | #378; original selected captures document A finding. CanonicalInputParityJourneyTest exercises choice/draft/null progress; affected new-head capture remains owed. Historical immediate legacy heading stays separately pinned. |
| Start-aligned letter-badged choice rows and visible selection | drift | A used centered text buttons. Source repair gives start-aligned 48dp rows, conspicuous selected badges and target question-block treatment | #378; original selected pair records weak A feedback. Focused choice rows accept target letter/digit actions; text/composer keys retain ownership, with modifier/out-of-range/Other-focus checks. Android focus scope preserves editable input rather than registering window-wide keys. New-head visual review remains owed. |
| No-choice question has only prompt and placeholder | drift | A unconditionally showed Pick one and Other. Source repair conditions hints/Other badge on actual choices | #378; original reduced-height final-question capture proves A defect. Production zero-choice/mixed-batch journey covers corrected prompt, placeholder and null answer semantics; new-head capture remains owed. |
| Queue mixes lost/server rows in shared section | mobile-adaptation | Separate lost and queued groups; lost opens for required recovery, queued starts collapsed, explicit touch Discard/Delete actions | Coordinator retains phone placement/action adaptation. Source repair uses count-plus-Queued for new canonical admissions; unchanged legacy Queued next findings retain #245/#246 attribution. Original handler captures and production journey preserve exact admission ownership/no replay. |
| Model warning explicitly requests destructive confirmation | drift | A used ordinary accent fill. Source repair uses existing Destructive semantic variant and preserves Keep-before-Switch logical order | #378; original modal pair records A red/blue finding; actual modal pixel regression verifies destructive token. Native horizontal footer remains a coordinator-dispositioned adaptation to Desktop's narrow reversed vertical stack. New-head capture remains owed. |
| Confirming swaps label to submitting spinner | omission | A only disabled its text. Source repair adds visible progress with disabled controls and retained accessible Confirm and continue meaning | deferred: #378 — original submitting captures document missing feedback; new production test asserts progress/no duplicate submit. Changed-state render acceptance remains owed. |
| Stale confirmation reports Selection changed — the model switch was not applied. | omission | A silently returned. Source repair reports generic notice for same-endpoint stale owner/selection, while preserving no-RPC and prior-endpoint isolation | deferred: #378 — original stale scene was projection-only; real ViewModel tests now exercise owner/selection changes and endpoint rejection. New-head integrated effect/capture review remains owed. |
| Settled structured responses render question/answer and No answer/Skipped | omission | A's generic ToolView has no responses-array specialization; this bounded repair preserves that pre-existing feature gap | deferred: #380 — ToolView blob ce8024440b1ff0f71864bbef10d052671efa5d72 is identical at mission base d8626506df38aeea9d941c9058ef398d4ad999be and A. Original supplied-result captures prove scoped gap, not a regression. Separate renderer port tracked in [#380](https://github.com/donovan-yohan/hermes-agent-android/issues/380). |

## Visual report

- pending: #378

### Source-equivalent rendered packet for A

Original packet retained in the mission's single evidence bundle:
`missions/upstream-main-compatibility/evidence/visual378/REPORT.md`,
`report.html`, `copy-diff.md` and `manifest.json` (manifest SHA-256
`d70fa6ad6e2d2353aed8f02f1b090cfeada1a2dd077ed6d560008e87ad5f4101`).
Android authority is exactly `6b42e623345ea4d0847b87a58a91c26847087076`;
Desktop components come from a disposable exact-U export. All 124 originals
are retained: 58 Desktop, 42 main native, 12 supplemental native and 12 real
production-retry captures. Kani checked original hashes and representative
selection, free-text, submitting and model images; coordinator independently
reviewed the comparison. These observations are not evidence for later repair
bytes. #378 stays open for independent affected recapture/disposition.

Android images draw actual frozen production Activity/dialog windows through
native Robolectric Canvas at API34, 412dp, UTC/en/US; they are not installed-APK
or device screenshots. Desktop uses unmodified target components in an isolated
synthetic browser host. Main captures prove emitted surface actions only;
production-retry captures traverse real ChatScreen/Actions/ViewModel/repository/
RPC and show actual error/retry/settlement/follow-up effects. Synthetic peers
never certify named-profile upstream native settlement.

Original no-op sidebar inset diagnostics do not prove composer IME behavior.
Supplemental reduced-height captures prove focus/body-scroll/footer reachability
under a synthetic content bound. Projection-only stale scenes and arbitrary
surface-error diagnostics do not certify handler notices or producer error copy.
No physical IME, TalkBack, OS lifecycle, secure-dialog screenshot workaround or
full visual parity is claimed. Historical UI/theme/visual pins remain unchanged.
Secure dialogs retain FLAG_SECURE. The pre-existing settled detail is tracked
separately by #380; no omission was silently reclassified to clear this gate.
