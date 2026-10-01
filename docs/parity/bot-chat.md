# Bot Chat parity

## Pin

| Authority | Revision | Read method |
|---|---|---|
| Hermes Desktop and Gateway | `564aef2946c436500a5e80ee117b66b789b3f99a` | read-only `git show <sha>:<path>` |

Every source location below is against that exact revision.

## Sources and action evidence

| Question | Desktop/Gateway source | Android evidence |
|---|---|---|
| Canonical identity and lookup | `apps/desktop/src/plugins/hermes-bots/canonical-chat.ts:151-205`; `apps/desktop/src/AGENTS.md:49-81` | `BotsPluginRepository.findCanonicalChat`: exact `session.list {profile, title:"Bot Chat", limit:200, include_hidden:true}`, one exact title row only, `resolved_id` before `id` |
| Row activation | `apps/desktop/src/plugins/hermes-bots/bot-row.tsx:210`; `canonical-chat.ts:485-519` (`openBotCanonicalChat`) | `BotsRosterScreen` row tap → `BotsViewModel.openBotChat`; `BotChatPhaseAViewModelTest` gates lookup/resume to prove the row key stays loading, exact lookup precedes navigation, duplicate taps coalesce, failures retry, and endpoint changes fence late callbacks |
| Open-or-create | `canonical-chat.ts:290-475` (`createCanonicalChat`): adopt-before-mint `:335-346`; `session.create {profile, title:"Bot Chat", hidden:true, follow_profile_config:true}` `:348-363`; eager `session.title` `:368-412`; adopt-on-conflict `:387-409`. Gateway durability receipt: `tui_gateway/methods_session.py:993-1011`, where `pending:true` means row creation did not take | `BotsPluginRepository.openCanonicalChat`: a second confirming read before any create, then create and eager title. Only a literal JSON `pending:false` with the exact title proves that row durable — an absent, string or numeric `pending` reconciles exactly like `true` — and every id this path adopts must be a JSON string, never a coerced number; any unproven receipt re-reads the registry and adopts the exact-title winner (`resolved_id` first), otherwise it fails closed with exactly one create. Every call uses `PluginHost.requestAtEndpoint`, whose dispatch is fenced to the roster's endpoint generation: validated at the call, re-validated at the wire dispatch itself and again on the answer. `PluginHostTest`, `BotsPluginRepositoryTest` and `BotChatPhaseBViewModelTest` cover the switch race at both ends, method sequence, exact request objects, malformed receipts and ids, and absence of `prompt.submit` |
| Kickoff | `canonical-chat.ts:242-257` (`kickoffText`), `:429-452` (`submitIntro`) | Absent by design: the creation path opens only after `pending:false` or an exact registry confirmation and submits no prompt, so opening stays inert; `BotChatPhaseBViewModelTest` |
| Resume profile | `tui_gateway/methods_session.py:484-493` (`_Resume` resolves `params["profile"]` into `profile_home`; handler `:825-856`) | `GatewaySessionRepository.openSession(durableId, profile)` is explicit; `GatewayProfileRoutingTest` asserts the exact `session.resume` object for an uncached hidden row. The Bot path uses `openSessionAtEndpoint(durableId, profile, expectedEndpointGeneration)` instead: a resume that waited behind another navigation is refused when the app has left the endpoint, and `GatewaySessionRepositoryTest` proves the replacement Gateway receives neither `session.resume` nor `session.activate` |
| Missing/refused result | `canonical-chat.ts:175-235` | No `session.create` after an ambiguous, malformed, refused, unavailable or failed read; the roster stays present and actionable with `Bot Chat could not be opened. Check the Gateway and try again.` |
| First-turn consequence | `docs/spikes/bot-mode-gateway-contracts-2026-09-12.md` (`_ensure_active_session_slot`, `tui_gateway/session_lifecycle.py:48-59` @ the pin: the lease is claimed on a turn, never on create or resume) | `ChatViewModel` enables exactly the typed `prompt.submit` for a verified canonical Bot Chat, through `GatewaySessionRepository.submitAtEndpoint`, which refuses to submit on a Gateway the app has left (including a switch that lands while the send waits to dispatch); `ChatViewModelTest` and `GatewaySessionRepositoryTest` prove the typed send is the chat's one accepted submission, that an open sends nothing, drains no stored queue, and adds no unread-flag write |
| Mutation boundary | Issue #190's Phase B scope; #268 | `ChatViewModel.refuseBotChatMutation` is the one gate: it retires the capability on an endpoint change and refuses unsupported doors except the prompt send and endpoint-fenced live correction — read-aloud's speak and stop taps included, so voice is a refused door and not a second writable one. `ChatViewModelTest` sweeps the refusal set against a live speaker |

## Two-client Gateway execution evidence

A synthetic loopback WebSocket run at
`e27448b231498e79ade668d68c0b6c6206951206` exercised production WebSocket
dispatch, session handlers, `AIAgent`, SQLite, and fan-out transports against
upstream's mock HTTP inference provider. Client A submitted one running turn;
client B resumed its stored session and profile, obtaining the same runtime and
agent. B's steer reached the original agent's next provider request, both clients
received the same completion, and the total `prompt.submit` count remained one.
The correction was also present in SQLite. An identical stored ID in a different
profile resolved to a separate runtime rather than attaching to A.

This is executed Gateway contract evidence, not Android UI or deployed-authentication
acceptance: the server and clients shared a Python process and the test route
bypassed deployment authentication. Completion's `persisted_turn.complete` flag
was false despite the verified stored correction; that flag is not certified by
this run. Android correctness is separately covered by repository and composer
tests, including endpoint/profile/turn fences.

## Current-target correction delta

Rechecked against `e27448b231498e79ade668d68c0b6c6206951206`:
`tui_gateway/methods_session.py:2330-2391` accepts `session.redirect` / `session.steer`
for the named live runtime; there is no requirement that this client started the turn.
The reported rejection was Android's `refuseBotChatMutation` gate before any RPC,
not a Gateway ownership refusal. A resumed `running:true` runtime already contributes
to the repository's live-runtime set, including externally started turns.

`ChatViewModel.redirectDraftFromUi` admits this explicit correction through
`redirectAtEndpoint`. A separate **Steer** composer button is wired through
`ChatActions.onSteer` → `ChatViewModel.steerDraftFromUi` → `steerAtEndpoint` /
`session.steer`; it injects at the next tool boundary rather than calling Redirect
or submitting another prompt. Attachments and slash commands are not steerable.

Live controls require an already-resumed binding (never an implicit resume under
the selected profile), the original endpoint and exact live RPC. Runtime bindings
carry profile provenance: a colliding durable id under profile B requires an
explicit B resume, never activation of A's cached runtime. A profile handoff
replaces the single cache slot instead of merging A's transcript/queue into B;
A's late runtime frames no longer resolve. A provisional Bot open cannot submit,
correct or stop until that profile's resume succeeds.

The VM captures the observable turn generation at the tap, before launching work.
The repository checks it on entry and again in the immediate wire-send callback;
terminal/new-start transitions and profile ABA invalidate old intents. Held
correction replies cannot append optimistic text into a newer turn. Stop installs
its attribution marker at dispatch and clears the queue on acknowledgement only
if the same observed turn and queue snapshot still own it; an old Stop cannot
clear B's newly observed queue or advance B's confirmed-interrupt bookkeeping.
Unknown outcomes keep the Bot correction in the editor; no local queue or fallback
prompt is armed. A changed editor, navigation or endpoint ignores late UI
acknowledgements. Ordinary session queue/fallback behavior is preserved.

Exact bare `/new` and `/reset` in the canonical Bot composer become `/compact`,
matching `apps/desktop/src/plugins/hermes-bots/plugin.tsx:765-799` at the new target.
This preserves the conversation and uses the same endpoint-bound submit path.
Definite failures restore the original editor command, not `/compact`; arguments,
other slash commands and ordinary Sessions are not rewritten.

`GatewayTurnOutcome.assistantMessagePreview: String? = null` carries an immutable
copy of `completed.markdown` from the exact `AssistantTurn` finalized inside
`completeMessage` (blank prose is null). Standalone errors retain the null default.
Notification consumers may sanitize/truncate that field but must not derive the
completed answer by reading a later session-cache snapshot.

Regression evidence: isolated Kotlin compilation against cached app collaborators
and direct JUnitCore execution passed **421 tests** across
`GatewaySessionRepositoryTest` and `ChatViewModelTest`. This includes pre-wire
endpoint revocation, observed-turn replacement, profile ABA, held correction and
Stop replies, explicit profile collision/unknown provenance, provisional-open
refusal, original alias drafts, negative aliases, distinct Steer dispatch and
immutable outcome prose. The changed production UI chain and
`ComposerSliceFourAccessibilityTest` also passed an isolated Compose-compiler
check. **No Gradle was run in this lane.** Parent-owned clean/full Gradle and
runtime Compose execution remain required; cached-collaborator checks are not a
clean application build or device proof.

### Remaining upstream race boundary

The RPCs name a runtime, not a server-issued expected-turn token. The local fence
can reject a transition mobile has observed, but cannot detect a server turn
change whose events have not reached mobile, or retract an already-sent frame
before the server handles it. Server-side turn-id compare-and-dispatch would be
needed to close that gap. A real two-client Gateway contract run has not been
performed by this worker; the deterministic fake uses separate event delivery,
pre-wire latches and held replies and is not presented as live two-client proof.

## Copy and navigation

The roster does not navigate during discovery, resumption or creation. The
tapped row keeps its loading indicator until the profile-aware open answers,
and a created chat's eager title write is what makes its row durable before
Chat opens it. On success Android goes to the normal Chat destination and
shows the transcript with a working composer; the first message the person
sends there is what arms live cron/teammate delivery through that Gateway
runtime — no client side call arms it, and opening does not. On failure it
stays on the roster and shows fixed product copy, never backend exceptions or
identifiers. A normal session selection or creation clears the Bot Chat
capability; an endpoint change clears it before any old durable id can be
reused.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| A newly created chat can be kicked off: Desktop submits its intro on New Agent creation, and on a gateway whose eager title write fails it submits the intro to persist the row (`canonical-chat.ts:242-257,429-452`) | mobile-adaptation | Creation titles the row and sends nothing; a title write that does not land and confirms no winner fails closed with a retry sentence | An opening that prompted would arm live cron and teammate delivery without the person sending anything, and this app ships no bot-creation flow for the intro to belong to — so mobile priority is that the person's own first message is what arms it, while the pin's gateway persists the row through the eager title write |
| Desktop's Bot Chat is the ordinary chat surface: queue, stop/redirect, approvals, attachments, voice, the composer's session controls and the session menu all work there | drift | Typed sends, Stop and explicit live corrections are enabled; other mutation doors answer with `Only messages can be sent from a Bot Chat on mobile.` and the chat's session menu is hidden | #268. Each of these can cause a turn to run, and a turn in a Bot Chat is what arms live delivery, so they need their own consent story rather than an undocumented widening |
| Desktop owns a multi-pane bots workspace | mobile-adaptation | A successful row action returns to the single Android Chat destination | Phone navigation has one foreground chat destination; the explicit profile on resume preserves the bot owner |
| Desktop row/menu cluster has additional bot-management actions (`bot-row.tsx:307-443`) | mobile-adaptation | Current-target create/edit/duplicate/delete, pin/hide and section actions are wired through phone forms; unsupported entries remain WIP | #189; [management contract audit](bot-management-contract-audit.md) records implemented contracts and remaining omissions, separately from historical chat captures |

## Visual report

- pending: #190

No rendered Desktop/Android side-by-side was captured in this change. This is
explicitly pending evidence, not a pixel-parity claim. A parent may create a
separate visual-capture issue after this code change and replace `#190` before
reviewer sign-off.
