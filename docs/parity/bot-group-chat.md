# Bot Mode — hosted Group Chats (read only)

## Pin

New wire and UI authority: `NousResearch/hermes-agent` at
`d177b119e9c56c9ddc0b7379ffce52341ec06584`, read from the disposable
`/tmp/hermes-mobile-upstream-20260918` snapshot. Historical ADR and global pins
remain unchanged. This is not a full Desktop parity claim.

## Sources and action evidence

All paths in this section are upstream-relative at the pin above.

- `tui_gateway/contracts/groups_bot_relay.py:148-178,197-225,260-277`:
  capability/list/state/log request and response shapes. Only these four methods
  are sent. No prompt submission, hidden member sessions, profile configuration,
  peer control, or Desktop blob-store reads.
- `tui_gateway/methods_groups.py:100-109,216-245`: omitted `profile` selects the
  process profile for RoomLink capabilities. The room store is install-wide;
  this reader deliberately omits `profile` on all four methods, and never
  manufactures an active-profile filter or sends undeclared keys.
- `gateway/hosted_rooms.py:494-511`: actual room/event serialization. Historical
  event epochs can serialize as null; current `append_event` requires an epoch
  (`gateway/hosted_rooms.py:951-955`). Nullable historical-read coverage does not
  claim modern user appends omit epochs.
- `gateway/hosted_room_discussion.py:60-75,380-415`: turn coordinates, terminal
  payloads and gateway-authored activity. Backend failure/reason prose is never
  rendered. Unknown kinds/actors receive a local generic row.
- `tui_gateway/hosted_room_driver.py:392-405`: approval run/request identities
  can be null. The read-only pending-action marker does not need to invent them.
- `apps/desktop/src/plugins/hermes-bots/bot-row.tsx:590-650`: list context menu
  order is Open Group Chat; separator; Pin to top; Move to section; separator;
  Delete. Mutations remain disabled with WIP, not silently removed.
- `apps/desktop/src/plugins/hermes-bots/roster-pane-groups.tsx:59-78`: room order
  arrows; retained disabled here because there is no hosted ordering mutation.
- `apps/desktop/src/plugins/hermes-bots/group-chat-view.tsx:671-735,798-845,1245-1309`:
  header settings, member management, disband; activity/stop; composer/thread
  controls. Settings precedes members, which precedes disband.
- `apps/desktop/src/plugins/hermes-bots/i18n.ts:351-358,438-505`: plugin-owned
  English copy. These strings are in the plugin, not the app-wide `en.ts`.

The ready-leg token has no transport reference or operations. Every read uses
`requestAtConnection(endpoint, token, ...)`, with the existing dispatch fence
and actual client identity checked both at wire send and after the response.
A reconnect retains same-endpoint cache but rechecks capabilities. A different
endpoint clears room identities, selected room and tombstones. A Boolean
readiness edge is not used as the reconnect identity.

Catch-up reads state then pages log on `has_more`, including byte-short pages.
The entire catch-up is committed atomically; a failed/malformed page keeps the
last known-good transcript stale. A fresh state cursor lower than the held
cursor is the only reset evidence; reasonless 4112 never resets. Recovery is
bounded by a page budget, and failures stop automatic polling until Retry,
resume, navigation or reconnect. Typed expired-history refusals tombstone the
room for the endpoint. Authority conflicts/lost events remain read-only.

Foreground is the production `LifecycleResumeEffect` on this destination, not
an invented PluginContext API. Working/blocked polling is 1.5 seconds and idle
polling 10 seconds; tests inject virtual-time intervals. Leaving/backgrounding
cancels polling; returning catches up. Local member names reuse the app's
already-loaded profile roster where a matching local profile exists; peer
profiles never borrow local identity.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Client-owned `hermes-bots-groups` store | drift | Gateway hosted-room protocol only | #193 — ADR 0004; new UI/wire pin above does not restamp historical ADR evidence |
| Desktop blob rooms in roster | drift | Not rendered | #192 — not read by decision (ADR 0004); may return as a read-only mirror |
| Groups integrated in Bot roster | drift | Separate Group Chats plugin destination using only active hosted rooms | #192 — hosted-room store boundary; no merged list |
| Client-side run/hold and transcript states | drift | Gateway settled/failed/cancelled/deferred/activity/authority projection | #192 — hosted log owns truth; generic safe unknown rows, no backend error prose |
| Desktop connection availability and member controls | drift | Gateway working/blocked, read-only pending-action and peer-route WIP markers | #192 — hosted driver state, no cross-Gateway control |
| Desktop writes (create, ordering, pin/file/delete, settings/members/disband, reply/attachment/new thread/stop) | drift | Visible disabled WIP controls; no mutation RPC | #192 / #193 — read-only slice; worker unavailability is not invented to explain missing Android mutations |
| Immediate local-store reads and sync | drift | Capability/unsupported/loading/empty/error+Retry and stale cache; bounded foreground polling | #192 — hosted read failure and lifecycle semantics |
| Desktop retained room history | drift | Explicit disbanded, authority and permanently expired-history states | #192 — hosted authority and retention rules |
| Threaded Desktop timeline, member holds and expanded activity detail | drift | Flat hosted event transcript with disabled Reply in thread; safe local status rows | #192 — hosted event projection, no client-side scheduling or fabricated backend prose |
| Room picture from Desktop blob | drift | No blob picture; hosted member list and room name only | #192 — the hosted room record has no image |
| Pointer context menu | mobile-adaptation | Explicit kebab opens the same menu order and separators | Touch has no right-click or hover; persistent 48dp target |
| Single-row header | mobile-adaptation | Header actions wrap in the same order on narrow viewports | Touch targets plus WIP markers must not overflow a phone |
| Desktop pane | mobile-adaptation | Full-screen back-navigable destination, larger readable text and 48dp controls | Phone viewport, touch and accessibility |

## Visual report

- pending: #192

No Desktop renderer was exercised. Desktop evidence is source-only, not
synthesized pixels. Android production-component debug fixtures are registered
as `bot-group-chat` in `visual-capture-surfaces.json` and the capture workflow:
`populated` opens Planning through the real list row, and `read-failure` shows
Retry. The debug manifest exposes `GroupsParityActivity`; no production demo
session or real host data is seeded. Parent CI owns actual image capture and
independent parity review. Unrendered UI remains Concern at best.

## Verification boundary

Author provenance: Hermes CLI / openai-codex / gpt-6-astra. No self-approval.
Repository, ready-leg race, virtual-time lifecycle/cache and Robolectric journey
tests cover the read surface; lint and assembled-manifest checks are separate.
The committed `app/src/test/resources/groups/hosted-store-d177.json` contains
actual frozen-upstream store outputs from a temporary synthetic database,
provided by the parent and reproduced locally. It is storage serializer
evidence, not JSON-RPC/live-driver integration. The actor and room-isolation
regression mutations must fail before restored code passes.
