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
| Mutation boundary | Issue #190's Phase B scope; #268 | `ChatViewModel.refuseBotChatMutation` is the one gate: it retires the capability on an endpoint change and refuses every door but the prompt send — read-aloud's speak and stop taps included, so voice is a refused door and not a second writable one. `ChatViewModelTest` sweeps the refusal set against a live speaker |

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
| Desktop's Bot Chat is the ordinary chat surface: queue, stop/redirect, approvals, attachments, voice, the composer's session controls and the session menu all work there | drift | Only the typed send path is enabled; every other door answers with `Only messages can be sent from a Bot Chat on mobile.` and the chat's session menu is hidden | #268. Each of these can cause a turn to run, and a turn in a Bot Chat is what arms live delivery, so they need their own consent story rather than an undocumented widening |
| Desktop owns a multi-pane bots workspace | mobile-adaptation | A successful row action returns to the single Android Chat destination | Phone navigation has one foreground chat destination; the explicit profile on resume preserves the bot owner |
| Desktop row/menu cluster has additional bot-management actions (`bot-row.tsx:307-443`) | omission | No bot-management controls are exposed anywhere in the roster | deferred: #189 — the roster's editing surface and its markers are that slice |

## Visual report

- pending: #190

No rendered Desktop/Android side-by-side was captured in this change. This is
explicitly pending evidence, not a pixel-parity claim. A parent may create a
separate visual-capture issue after this code change and replace `#190` before
reviewer sign-off.
