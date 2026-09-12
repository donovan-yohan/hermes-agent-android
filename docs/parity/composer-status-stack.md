# Composer status stack: Desktop-to-Android parity

Desktop authority for this page is the exact UI pin
`564aef2946c436500a5e80ee117b66b789b3f99a`. Every citation was read from that
Git object in the read-only upstream reference checkout; this page does not
claim the local worktree HEAD is at the pin, and the upstream checkout was not
modified or fetched.

## Pin and source contract

| Contract | Desktop source | Android |
|---|---|---|
| The stack above the composer | `apps/desktop/src/app/chat/composer/status-stack/index.tsx:94-262` | `ComposerStatusStack.kt` |
| One collapsible group | `apps/desktop/src/components/chat/status-section.tsx:20-53` | `StatusGroup`, in the same file |
| Collapse default | `.../status-stack/index.tsx:236` — `defaultCollapsed={group.type !== 'todo'}`; the shared default is `defaultCollapsed = true` (`components/chat/status-section.tsx:30`) | `defaultExpanded`, per group |
| Group label | `.../status-stack/index.tsx:59-77`, strings at `apps/desktop/src/i18n/en.ts:2891-2900` | the literal titles, and `ComposerGoalState.groupLabel()` |
| The queue panel | `apps/desktop/src/app/chat/composer/queue-panel.tsx:49-67` | `ComposerQueueSection.kt` |

## Collapse defaults

Upstream `5b181e511a` ("keep composer status groups collapsed except todos")
narrowed `defaultCollapsed={group.type !== 'todo' && group.type !== 'goal'}` to
`defaultCollapsed={group.type !== 'todo'}`, deleted `defaultCollapsed={false}`
from the structured goal section and the subagent section, and deleted the
queue panel's `defaultCollapsed={!parked}`, its
`key={parked ? 'parked' : 'flowing'}` remount and the comment arguing that a
Stop has to open the panel. Only the task list opens itself; everything else
starts shut, and a park neither opens the queue nor throws away an expansion
the reader chose.

Three of this app's groups already matched (todos open, subagents and
background shut). Two did not, because both were faithful ports of the rule
upstream has now reversed: the goal group passed `defaultExpanded = true`, and
the queue started expanded when parked and was force-expanded on every park.
Both now follow the pinned rule, and the queue's saveable key never included
`parked`, which is the other half of the deleted remount — a manual expansion
survives the park that follows it.

Collapsing the goal group is only honest once its header can stand alone.
Desktop's label *is* the goal's state — `Goal active` / `Goal waiting` /
`Goal paused` / `Goal done` (`.../status-stack/index.tsx:59-70`, strings at
`apps/desktop/src/i18n/en.ts:2894,2896-2898`) — while this app's header was the
bare word `Goal` with the state implied by a body that is now hidden. The
header carries the state as of #232, so the collapsed group says exactly what
Desktop's collapsed group says.

`ComposerStatusCollapseDefaultsTest` is the Android side of Desktop's own
contract test
(`apps/desktop/src/app/chat/composer/status-stack/default-collapse.test.tsx:45,73`).
When a parsed goal crosses the known/unknown boundary, the Android group reacts
without remounting: an automatically opened unknown closes once the state is
known again, while an expansion the reader chose survives the round trip.

## Silent exits

A background process started without `notify_on_complete` emits no event when
it dies, so nothing retires its row. Desktop's answer is a safety-net poll:
`process.list` every 5 seconds, armed only while a running row is on screen and
disarmed with the pane
(`.../status-stack/index.tsx:41-43,138,149,151-163`). The poll is silent —
the interval discards the result (`:156-160`) and the store swallows a
transient failure ("the next trigger (event or poll) retries",
`apps/desktop/src/store/composer-status.ts:405-424`).

This app has no timer on this path and #233 rates that as the property worth
keeping: its refreshes are a seed when the open session changes
(`ChatScreen.kt:803`), the repository's coalesced event-driven refresh
(`GatewaySessionRepository.kt:5184-5200`), and the Background group's manual
Refresh. The cost was that a silently exited process kept reading
`Running · <title>`, and kept offering a Stop for something already gone, for
as long as the reader stayed in that session.

The stand-in is bounded rather than periodic (`ReconcileSilentExits`): it
exists only while a row claims Running in the session on screen, it runs only
while the host is RESUMED — the mobile shape of Desktop's `paneVisible` gate —
and it walks three widening rungs (10s, 30s, 90s) and then stops. A change in
which ids claim Running starts a fresh ladder, so the work is bounded by real
events; returning to the foreground re-arms it, which is the edge a silent exit
most often hides behind. `ComposerSilentExitReconcileTest` drives all of that
on the Compose test clock.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| The goal group's label comes from a typed `goalStatus`, and a status it cannot name falls through to `Goal active` (`.../status-stack/index.tsx:59-70`) | mobile-adaptation | A goal state parsed out of the Gateway's text status line; `Unknown` keeps the bare word `Goal` and is the one group left open by default | The parser is deliberately neutral rather than fabricating an active goal (`GatewaySessionRepository.kt:5287-5291`). On a phone the collapsed header is all that is left of the group, so a header that cannot name the state cannot replace the body: the unrecognised line stays on screen instead of being hidden behind a word that says nothing. Every state the parser *can* name collapses exactly as Desktop does |
| Group labels read `${count} Subagent${count === 1 ? '' : 's'}`, `${count} Background`, `${count} Queued`, `${count} Queued — paused` (`apps/desktop/src/i18n/en.ts:2793-2795,2891-2900`) | drift | `Subagents · 1`, `Background · 1`, `Queue · 1`, `Queue · 1 · parked` | Count-last and not Desktop's wording; `Tasks 0/1` and the four goal labels are verbatim. #245 |
| Preview rows are a bare always-visible block in the stack, with no `StatusSection` and no collapse (`.../status-stack/index.tsx:260-262`) | mobile-adaptation | A `Previews` group with a count, collapsed by default | The Android stack is a bounded 240dp scroll region that must not push the editor below the IME (`ComposerStatusStack.kt`), so a status kind that is not a disclosure spends that budget on itself whether or not it is being read. #232 deliberately did not change this group's default, because `5b181e511a` says nothing about a group Desktop has no section for |
| No `StatusSection` for prompts the Gateway itself is holding; Desktop's stack renders only the client-side queue (`apps/desktop/src/app/chat/composer/queue-panel.tsx:49-67`) | drift | A `Queued next` group, expanded by default | Android-only group with no upstream default to copy and no verdict yet; #246 |
| No session-compaction copy in the composer stack, and none in `apps/desktop/src/i18n/en.ts` at the pin | drift | The bare line `Hermes is compacting this session.` | Android-only string with no Desktop wording behind it; #246 |
| A 5 second `process.list` interval while any running row is on screen (`.../status-stack/index.tsx:41-43,151-163`) | mobile-adaptation | No interval: three widening checks (10s, 30s, 90s), armed only by a Running claim and only while the app is resumed, restarted by new evidence or by a return to the foreground, then silent | Every tick of a 5 second gateway round trip is a radio wake, and on a phone that is battery and data spent on a row nobody may be looking at; Desktop's own gate is pane visibility, and the lifecycle is its mobile equivalent. What it costs: a process that dies silently while the reader keeps the session in front of them for longer than the ladder still reads Running until the next foreground return, the next process event, or Refresh — Desktop would catch it within five seconds |
| No manual refresh control in the background group; the poll is the only recovery (`.../status-stack/index.tsx:151-163`) | mobile-adaptation | A `Refresh` text button inside the Background group (`ComposerStatusStack.kt`) | It is the explicit escape that a bounded ladder needs and an unbounded interval does not: the one press that resolves a row the ladder has already given up on, rather than asking the radio to keep checking on the reader's behalf |
| The safety-net refresh is silent: the interval discards its result (`:156-160`) and a transient failure is swallowed (`apps/desktop/src/store/composer-status.ts:405-424`) | mobile-adaptation | The automatic ladder uses `reconcileProcesses`, while the visible Refresh uses `refreshProcesses` | Both paths read the same authoritative process list, but only the reader's Refresh can post `Background work could not be refreshed. Try again.`; `ChatViewModelTest` proves the failed automatic and manual paths separately. #249 |

## Visual report

- pending: #244

Nothing here is rendered. The rows above were read from the pinned upstream
Git objects and from this repo's sources; the Desktop-versus-Android
side-by-side that would judge the labels, the caret treatment and the collapsed
headers is owed by #244.
