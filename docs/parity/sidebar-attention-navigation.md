# Sidebar input attention and chat switching

## Pin

Desktop structure and copy were inspected at
`41dfcf2c1bd7146d4ad46aea08b7dd007900602e` in a disposable source export.
The mobile baseline is `9ccd0108e8dfd5acd01c5799a12705085453d7d1`.
This continuation does not restamp the older sidebar or recovery reports.

| Contract | Desktop source at the pin |
| --- | --- |
| A blocking prompt outranks working, background work and unread | `apps/desktop/src/store/session-dot-state.ts:125-180` |
| Filled amber attention dot, accessible label and hover title | `apps/desktop/src/app/chat/session-status-dot.tsx:29-37` |
| `Needs your input` and `Waiting for your answer` | `apps/desktop/src/i18n/en.ts:3246-3247` |
| Routed intent can be loading before active-session identity agrees | `apps/desktop/src/app/chat/thread-loading.ts:65-96` |
| Hide the previous route's composer while switching; keep it during same-route refresh | `apps/desktop/src/app/chat/thread-loading.ts:33-63` |
| Desktop's centered conversation loader | `apps/desktop/src/components/assistant-ui/thread/status.tsx:228-245` |

## Scope

The session row already had `NeedsInput` and its semantic amber dot. The new
visible second line uses Desktop's `Needs your input` wording instead of stale
preview text. It carries no question, command or secret contents. Archived rows
do not claim attention, and working or unread alone does not claim it either.
This status describes a real outstanding input request, not every assistant
reply that happens to contain a question.

The reported stale-pane delay is reproducible in project-row selection. That
path waits for owner-aware open and history before changing authoritative active
identity. An ordinary row selection already rehomes synchronously; its cold
loading state was not rendered. Owner validation is retained. Navigation
presentation must acknowledge the selected destination before its response
arrives, without painting a different profile's raw-ID cache slot.

## Evidence

`ChatViewModelTest` adds a held owner-aware resume. Before the change, its
loading state is `Idle` immediately and after 2,000 milliseconds of virtual time,
and the assertion for immediate feedback fails. Separate Compose regressions
fail on the missing skeleton and visible attention text. The accepted candidate
passed `check` with 4,098 debug tests and 3,220 release tests, no failures or
errors, and two skips per variant. Independent review and its bounded rereview
accepted the transport-cleanup, actual Stop/Send Next, same-owner reselect and
light-mode contrast repairs. The publication branch integrates newer main's
notification presence; its exact-head verification is recorded on the PR.

The repository currently serializes navigation through resume/activate,
transcript hydration and reconciliation. The history path can request another
canonical resume. These are inspected critical-path mechanisms, not measured
physical-device timings. This slice does not claim faster backend completion.

## Visual report

- pending: #72

The [Android observation gallery](../media/sidebar-attention-navigation/README.md)
retains actual light/dark attention and selected-loading screenshots. It records
the captured candidate and APK identity without claiming newer-base or physical
device acceptance.

The existing device/paired-sidebar acceptance issue remains open. New synthetic
capture evidence must identify its exact candidate bytes and distinguish
component references, emulator rendering and physical-device acceptance.

The bounded Desktop component attempt produced no screenshot. Navigation timed
out before rendered assertions; dependency optimization was visible in the Vite
log, but the cause was not established. Its failed-attempt/source packet is
retained outside the repository. No screenshot or paired parity is inferred from
that fixture. The inspected native `title` attribute remains source evidence,
not proof of a painted tooltip.

## Divergences

| Desktop | Class | Android | Evidence |
| --- | --- | --- | --- |
| Attention is a filled amber dot with an accessible label and hover title | mobile-adaptation | Same dot plus visible `Needs your input` in the row's preview slot | Touch has no hover. `SessionListSectionsJourneyTest` asserts visible text, stale-preview replacement and non-attention controls. |
| New-route loading uses a centered rose-curve loader | mobile-adaptation | Static transcript skeleton while the selected conversation loads | User-requested loading treatment. Semantic theme colors, one loading announcement, no decorative accessibility nodes or perpetual animation. |
| Route and accepted runtime identity can differ while loading | mobile-adaptation | Keep owner-aware active identity unchanged until admission, but replace previous-pane presentation during the wait | Raw IDs may collide across profiles. The held owner-collision journey must retain the original cache and draft until owner-aware open succeeds. |

## Acceptance boundary

Virtual-time feedback proof is not a phone latency benchmark. A held synthetic
response proves that feedback does not depend on network completion, not that a
real gateway's round-trip time or Android frame time improved. No live personal
conversation is used for capture, and no running turn is cancelled by selection.
