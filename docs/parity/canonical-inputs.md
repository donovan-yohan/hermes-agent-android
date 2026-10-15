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
| Staged one-question and multiselect batch | `apps/desktop/src/components/assistant-ui/clarify/pending.tsx:152-207`, `core/confirm-bar.tsx:19`; `apps/desktop/src/i18n/en_clarify.ts:3-20`; `clarify/clarify-tool.test.tsx:751-833,1261-1379` | Pick per question and/or type answer, then Skip or Confirm and continue. Canonical single question uses same staged card. Target progress counts staged choices/nonblank drafts; no-choice fields use only their prompt/placeholder. Legacy immediate single-question fixture retains historical behavior. |
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

## Historical divergences at A

The following observations and repair notes remain bound to Android `6b42e62`
(A). Their then-pending review/capture notes are retained as history; the current
C comparison and classifications follow separately. No later result restamps
these images or silently reclassifies their findings.

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Clarify card body follows desktop transcript space | mobile-adaptation | Composer-adjacent body capped at 25% screen height; footer stays below it with 48dp targets | Original report shows final-question focus/body scroll and reachable confirmation at reduced available height. This is layout evidence, not physical IME proof. Coordinator disposition retains this adaptation. |
| 1 question or answered-of-total progress, with right question glyph | drift | A added Hermes has a question and fixed N questions. Source repair removes extra staged heading, counts staged picks/nonblank drafts and uses target Tabler MessageQuestion | #378; original selected captures document A finding. CanonicalInputParityJourneyTest exercises choice/draft/null progress; affected new-head capture remains owed. Historical immediate legacy heading stays separately pinned. |
| Start-aligned letter-badged choice rows and visible selection | drift | A used centered text buttons. Source repair gives start-aligned 48dp rows, conspicuous selected badges and target question-block treatment | #378; original selected pair records weak A feedback. Focused choice rows accept target letter/digit actions; text/composer keys retain ownership, with modifier/out-of-range/Other-focus checks. Android focus scope preserves editable input rather than registering window-wide keys. New-head visual review remains owed. |
| No-choice question has only prompt and placeholder | drift | A unconditionally showed Pick one and Other. Source repair conditions hints/Other badge on actual choices | #378; original reduced-height final-question capture proves A defect. Production zero-choice/mixed-batch journey covers corrected prompt, placeholder and null answer semantics; new-head capture remains owed. |
| Queue mixes lost/server rows in shared section | mobile-adaptation | Separate lost and queued groups; lost opens for required recovery, queued starts collapsed, explicit touch Discard/Delete actions | Coordinator retains phone placement/action adaptation. Source repair uses count-plus-Queued for new canonical admissions; unchanged legacy Queued next findings retain #245/#246 attribution. Original handler captures and production journey preserve exact admission ownership/no replay. |
| Model warning explicitly requests destructive confirmation | drift | A used ordinary accent fill. Source repair uses existing Destructive semantic variant and preserves Keep-before-Switch logical order | #378; original modal pair records A red/blue finding; actual modal pixel regression verifies destructive token. Native horizontal footer remains a coordinator-dispositioned adaptation to Desktop's narrow reversed vertical stack. New-head capture remains owed. |
| Confirming swaps label to submitting spinner | omission | A only disabled its text. Source repair adds progress with disabled controls and retained accessible Confirm and continue meaning; narrow F3 repair keeps the semantic fill/ink pair opaque during loading | deferred: #378 — independent cf86de6 native paint found faded loading fill made its paired ink hard to see. PrimaryButtonLoadingPaintTest reproduces the light/dark defect and verifies the paired painted fill/ink plus focused keyboard/pointer rejection. Default and destructive controls retain their tests. New-head independent changed-state acceptance remains owed. |
| Stale confirmation reports Selection changed — the model switch was not applied. | omission | A silently returned. Source repair reports generic notice for same-endpoint stale owner/selection, while preserving no-RPC and prior-endpoint isolation | deferred: #378 — original stale scene was projection-only; real ViewModel tests now exercise owner/selection changes and endpoint rejection. New-head integrated effect/capture review remains owed. |
| Settled structured responses render question/answer and No answer/Skipped | omission | A's generic ToolView has no responses-array specialization; this bounded repair preserves that pre-existing feature gap | deferred: #380 — ToolView blob ce8024440b1ff0f71864bbef10d052671efa5d72 is identical at mission base d8626506df38aeea9d941c9058ef398d4ad999be and A. Original supplied-result captures prove scoped gap, not a regression. Separate renderer port tracked in [#380](https://github.com/donovan-yohan/hermes-agent-android/issues/380). |

## Divergences

### Current observed head C

Current observations bind exactly to Android
`0e641823865dc2d685a4bce500eb3cd1c8031988` (C), tree
`8fea8870e7d026c5fb8b5114b041900dbffbf487`, against U. Independent client CODE
is clear with F3 closed; affected synthetic product QA passes 34 unique cases.
Hotate's completed comparison observes all seven requested functional/surface
repairs, including paired loading paint and actual stale-handler effects.
Its overall verdict remains **Concern**. Coordinator disposition retains the
selection-paint drift and decorative Enter omission under #378 for requester
visual ruling/follow-up; this is tracking, not a parity waiver or release
acceptance. #378 stays open. The pre-existing settled renderer remains #380.

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Compact transcript card and inline question body | mobile-adaptation | Composer-adjacent capped scroll body, 48dp rows/actions, larger native editor and resized footer | Current C report; coordinator retains touch/reachability adaptation. Actual 892/592dp draws reach the final editor and preserve nullable staging; they do not prove real IME behavior. |
| Shared collapsed queue, hover X and shared total count | mobile-adaptation | Lost autoexpands separately from queued; explicit Discard/Delete; count per split group | Current C report and production action checks preserve exact admission ownership/no replay. Per-group count-plus-Queued wording matches; old legacy #245/#246 findings remain separately pinned. |
| Toast, Desktop modal chrome and narrow reversed vertical footer | mobile-adaptation | Inline error/stale notice, native modal, existing decline treatment and horizontal Keep-before-Switch footer with touch targets | Current C report; coordinator retains phone feedback/chrome/footer adaptation. Product words and logical order match. No new Back/scrim, accessibility or precise theme-color equality claim. |
| Selected badge uses fixed white ink; row highlight tracks cursor/focus | drift | Badge uses existing paired filledActionInk, dark in Nous dark; selected row retains accent tint | #378 — current C selected pair and source inspection establish scoped paint difference. Coordinator retains explicit drift pending requester visual ruling/follow-up. No forced raw-white change or theme-token waiver; loading contrast measurements are not badge-specific accessibility proof. |
| Confirm includes decorative aria-hidden Enter glyph | omission | Functional confirmation, correct words and submitting feedback present; decorative glyph absent | deferred: #378 — current C report. Coordinator retains omitted decoration explicitly; no missing confirm control or physical-keyboard behavior is inferred. |
| Structured settled question/answer, No answer and Skipped rows | omission | Generic ToolView remains unchanged from mission base | deferred: #380 — original supplied-result evidence and unchanged blob remain authoritative. Current pending-retirement pairs prove card removal, not structured result rendering. |

Current table: three mobile adaptations, one drift, two omissions. This does not
replace the historical A table. Legacy immediate words and legacy Queued next
keep their own attribution. No full Desktop parity or public-native server
settlement is accepted.

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

### Completed independent comparison for C

The single mission evidence bundle retains
`missions/upstream-main-compatibility/evidence/visual378-0e64182/REPORT.md`,
`report.html`, `copy-diff.md`, `manifest.json` and `receipt.json`.
Manifest SHA-256:
`7cc711f8e5d0c360ccbe62672fbfb0cd634f5db6cb5d8a36a7b1e88c7a566e33`.
Report SHA-256:
`45cb21c063b1bcfbf173489b2e8e4250404aadb440c78cba846d2b6bae8d5d24`.
Observed Android is exactly C above; Desktop imports unchanged exact-U production
components from the owned disposable export. The report contains 102 comparison
pairs and 226 attributed original image files; these are inventories, not test
totals or additional product progress. Kani verified all image hashes, 34 capture
artifact hashes and 21 source hashes, read the full report/copy/HTML inventory,
and inspected the original dark selected pair. Coordinator independently
verified receipt/image joins and dispositioned the residuals for tracking.

Hotate captures actual production surfaces using external Kotlin/Compose and
direct Java over frozen outputs, native Robolectric Canvas at API34, Nous
light/dark, mdpi, UTC/en-US and a fixed synthetic clock. Activity windows remain
892dp; reduced captures constrain actual ChatScreen content to 592dp and clip
the direct Canvas draw to that boundary. Native dialogs draw their own window.
Projection fixtures prove surface action/answer/admission identity, not repository
settlement. Kame's 40 full-height originals retain their paths/authorship;
Hotate's attributed reduced harness copies traverse real ChatActions, ViewModel,
repository and correlated RPC with synthetic peers. Their 13 capture cases
overlap existing QA and are not additive coverage.

Integrated evidence shows actual failure/retry, held successful ACK, disabled
duplicate rejection, exact-ID release, sensitive wipe and one ordinary follow-up.
Same-endpoint stale owner/selection callbacks produce the current generic notice
without another RPC and preserve newer selection; a foreign-endpoint callback
paints no prior notice/model/modal and sends on neither wire. Pending retirement
proves removal of the pending card, not ToolView responses-array specialization.
Original no-op insets, arbitrary projection retry and projection-only stale
scenes are excluded. Intro Splash and synthetic producer text are excluded from
verbatim copy acceptance.

Desktop uses target components in an isolated synthetic host with fresh headless
Chrome contexts and external network refused. Existing originals are reused only
with exact image/source joins; matched reduced/batch captures use the same U
components and locked dependencies. No installed APK, physical density, real
IME/binding, TalkBack, OS lifecycle, animation liveness, radio, live provider or
public-native responder settlement is claimed. F3's independent paired loading
paint measurements retain their static-arc limit. Historical ordinary UI/theme
and report pins remain unchanged. Any documentation-only child carries these
observations only through exact source/test/configuration equivalence; the
rendered report remains pinned to C.

## Stock046 native limitation recovery

Unmodified `046094df28b712f62a5e3c728a9e59da05dcbf1e` authorizes public native
id-only replies against the launch authority (`gateway/session_controls.py:581-592`).
Other-owner native requests therefore have an explicit limitation; canonical named
approval/clarification and launch-owner native replies remain supported.

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| No equivalent safe public native reply for another authority | mobile-adaptation | Reply unavailable; This request can’t be answered here. Stop this turn to continue.; Stop. Captured owner/generation control retains card until backend withdrawal | [Actual stock limitation/Stop report](visual/stock-native-input/report.html), original PNG and source receipt. Actual handler cancels beta while equal-ID alpha stays parked, then next beta send completes. This scoped card evidence does not restamp historical Desktop reports or certify the aggregate network run. |

The existing visual report above remains pinned to C. This minimal additional
card reuses existing native tokens/control and records its own original capture;
no new global visual gate, Desktop parity, physical IME or accessibility claim.
