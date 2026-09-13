# Bot Chat parity

## Pin

| Authority | Revision |
|---|---|
| Hermes Desktop and Gateway | `564aef2946c436500a5e80ee117b66b789b3f99a` |

## Sources

- `apps/desktop/src/plugins/hermes-bots/canonical-chat.ts:151-205` — exact hidden `session.list` registry lookup, title verification, and `resolved_id` preference.
- `apps/desktop/src/plugins/hermes-bots/bot-row.tsx:210` — activating a roster row resolves its canonical chat.
- `apps/desktop/src/AGENTS.md:49-81` — one canonical chat per profile, identified by exact `Bot Chat` title.
- Issue #190 — Phase A is read-only: no creation, kickoff, prompt submission, or delivery-target client behavior.

## Phase A contract

Android sends only `session.list {profile, title:"Bot Chat", limit:200, include_hidden:true}` for this action. It accepts only an exact-title row, opens `resolved_id` before `id`, and resumes it with the roster profile explicitly supplied. A missing or unsafe result never creates a session. The transcript opens read-only, with no composer or prompt-submit path.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Desktop can create/reconcile a missing canonical chat and may submit a first-turn kickoff | omission | Phase A says the chat is unavailable and creates nothing | out-of-scope: #190 Phase B |
| Desktop owns a multi-pane bots workspace | mobile-adaptation | A successful row action returns to the single Android Chat destination | Phone navigation has one foreground chat destination; profile-scoped resume preserves the bot owner |
| Desktop permits Bot Chat composition | omission | Composer is absent and the screen states it is read-only | out-of-scope: #190 Phase B |

## Visual report

- pending: #190

No rendered Desktop/Android evidence was captured in this change. The pending issue is intentional; this page does not claim pixel parity.
