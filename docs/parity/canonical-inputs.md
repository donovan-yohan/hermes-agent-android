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
| Staged one-question and multiselect batch | `apps/desktop/src/components/assistant-ui/clarify/core/pending.tsx:152-207`, `core/confirm-bar.tsx:19`; `apps/desktop/src/i18n/en_clarify.ts:3-22`; `clarify/clarify-tool.test.tsx:751-833,1261-1379` | Pick per question and/or type answer, then Skip or Confirm and continue. Canonical single question uses same staged card. Legacy immediate single-question fixture retains historical behavior. |
| Model confirmation | `apps/desktop/src/lib/guarded-model-switch.ts:36-99`; `apps/desktop/src/i18n/en.ts:5040-5047` | Keep current model then Switch anyway; back/dismiss declines; captured session/model/endpoint guards explicit consent. No automatic expensive-model approval. |
| Lost/queued admissions | `apps/desktop/src/app/chat/composer/queue-panel.tsx:130-222`, `queue-slot.tsx:47-81`; `apps/desktop/src/i18n/en.ts:3520-3541` | Lost row Discard uses original backend generation; queued Delete uses admission ID. No resubmission. |

New staged-card copy is verbatim: Pick one; Select all that apply; Other (type
your answer); Type your answer…; Skip; Confirm and continue. Model title/fallback,
body/fallback and Keep current model / Switch anyway match U. Lost note,
Discard/tip, Delete and cancellation error match U. Historical immediate-card
Hermes has a question / Type an answer / Send and the staged-card question-count
heading are not claimed as verbatim U strings.

PendingAnswerJourneyTest drives real ChatScreen/Actions/ViewModel/live-repository/
correlated-dispatch paths for choice/type/retry/batch/canonical restore/pre-ACK
prompts/native-root input and admission actions, then ordinary follow-up send.
Protocol tests separately enforce owner/generation/replay and receipt behavior.
Synthetic native peers prove client routing, not named-profile upstream server
settlement; [that boundary remains blocked](../upstream-compatibility-379cf122.md).

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Clarify card body follows desktop transcript space | mobile-adaptation | Body scroll capped at 25% screen height; confirmation stays below body with 48dp actions | Prevents a long/multi-question card consuming phone composer/IME space; selected semantics and disabled submitting controls remain. Render acceptance pending #378. |
| Inline clarify uses its question headings/progress copy | drift | Additional Hermes has a question heading and simple N question(s) count; historical immediate card keeps Type an answer / Send | #378 tracks exact rendered/copy review; no verbatim claim for these existing/new headings. |
| Clarify selection uses Desktop card/radio/check treatment | drift | Shared text buttons with selected semantics and theme highlight | #378 owes glyph/selection/body/menu-order side-by-side. Selection semantics tests alone do not certify matching pixels. |
| Queue mixes lost/server rows in its shared queue section | mobile-adaptation | Separate lost and queued groups; lost opens to expose required recovery, queued starts collapsed | Bounded phone status region gives blocked recovery a visible action. Original admission identity retained; copy uses U. Visual acceptance pending #378; historical group-label drift #245/#246 remains. |
| Model warning uses shared destructive confirmation | drift | Shared Android modal uses current PrimaryButton treatment | #378 owes warning emphasis and modal spacing/focus/disabled-state review; action order/copy remain Keep current model then Switch anyway. |

## Visual report

- pending: #378

No new Desktop-versus-Android renders are claimed. Review ceiling is **Concern**;
interaction checks do not close visual parity or physical acceptance. Capture
normal/submitting/retry/settled clarify, multi-question selection, model keep/
switch/stale, unknown discard and queued cancel in light/dark on the frozen
candidate. Secure dialogs retain FLAG_SECURE; no screenshot workaround is added.
