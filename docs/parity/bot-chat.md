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
| Row activation | `apps/desktop/src/plugins/hermes-bots/bot-row.tsx:210` | `BotsRosterScreen` row tap → `BotsViewModel.openBotChat`; duplicate taps are held by the row loading key |
| Resume profile | `tui_gateway/methods_session.py:324-330` | `GatewaySessionRepository.openSession(durableId, profile)` is explicit; `GatewayProfileRoutingTest` asserts the exact `session.resume` object for an uncached hidden row |
| Missing/refused result | `canonical-chat.ts:151-205` | no `session.create`; roster stays present with `No Bot Chat is available for this bot yet.` or `Bot Chat could not be opened. Check the Gateway and try again.` The row remains actionable for retry |
| Phase-A composition boundary | Issue #190 | `ChatViewModel` centrally refuses submit, queue, redirect, send-next, regenerate and branch while the opened canonical session is read-only; the explicit New Chat escape clears that marker before `session.create` |

## Copy and navigation

The roster does not navigate during discovery or resume. The tapped row keeps its
loading indicator until the profile-aware resume answers. On success Android goes
to the normal Chat destination and shows the transcript with the fixed sentence
`Bot Chat is read-only. Open a regular chat to send a message.` On failure it
stays on the roster and shows fixed product copy, never backend exceptions or
identifiers. A normal session selection or creation clears the read-only marker;
an endpoint change clears it before any old durable id can be reused.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Desktop can create/reconcile a missing canonical chat and may submit a first-turn kickoff | omission | Phase A reports the chat unavailable and creates nothing | out-of-scope: #190 Phase B |
| Desktop owns a multi-pane bots workspace | mobile-adaptation | A successful row action returns to the single Android Chat destination | Phone navigation has one foreground chat destination; the explicit profile on resume preserves the bot owner |
| Desktop permits Bot Chat composition | omission | Transcript-only Chat; every mutation door is centrally refused | out-of-scope: #190 Phase B |
| Desktop row/menu cluster has additional bot-management actions | omission | No management controls are exposed in Phase A | out-of-scope: #190 Phase B; this slice only ports the canonical-chat row action and does not imply unsupported actions work |

## Visual report

- pending: #190

No rendered Desktop/Android side-by-side was captured in this change. This is
explicitly pending evidence, not a pixel-parity claim. A parent may create a
separate visual-capture issue after this code change and replace `#190` before
reviewer sign-off.
