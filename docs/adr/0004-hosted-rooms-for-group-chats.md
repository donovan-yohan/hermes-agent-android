# ADR 0004 — Hosted rooms for Bot Mode group chats

**Status:** Accepted, 2026-09-09
**Authority:** `NousResearch/hermes-agent` @ `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`
**Related:** Issue #186 (Epic: Bot Mode), #192 (slice 6, read-only), #193 (slice 7, participating), #199 (audit corrections), `docs/spikes/bot-mode-audit-2026-09-09.md` sections 1.6, 3.2, 3.4

Every `path:line` below is upstream-relative and was read at `72a3277cd7`. Android paths are prefixed `android:`.

## Context

At the pin there are two group-chat engines, and they do not share rooms.

**Desktop's engine is client-side.** Hermes Desktop's `hermes-bots` plugin schedules every round in the renderer (`apps/desktop/src/plugins/hermes-bots/group-rounds.ts`), drives each member through its own hidden `Group: <roomId>` session with `prompt.submit`, and persists the room as a client-authored blob under `ui_meta['hermes-bots-groups']` on the `default` profile, CAS-guarded by `ui_meta_revisions` (audit section 3.2). No file under `apps/desktop/src` calls any `groups.*` method (audit section 3.4).

**The gateway's engine is server-side.** `tui_gateway/methods_groups.py:18-23` registers eighteen `groups.*` methods over the same `/api/ws` JSON-RPC dispatcher every route already uses. Rooms and their append-only log live in the install-wide root `state.db` (`gateway/hosted_rooms.py:398-402`), never in `hermes-bots-groups`. An in-process worker, started by `hermes serve` (`hermes_cli/web_server.py:165-187`) and by the messaging gateway (`gateway/run_startup.py:656-683`), plans turns deterministically from the log with the same caps Desktop uses: 2 to 6 members, 3 rounds, 10 member messages, a 24-line delta (`gateway/hosted_room_discussion.py:23-28`). It runs "independently of Desktop connections" (`tui_gateway/hosted_room_driver.py:92-96`).

**Upstream's stated direction is the hosted engine.** The user guide says rooms "keep running when you close the Desktop" and that "the Desktop simply catches up from the room's log when it reconnects" (`website/docs/user-guide/bot-mode.md:105`). The protocol stamps the user actor as `{"kind":"user","id":"desktop"}` (`tui_gateway/hosted_room_service.py:449-452`), defaults `cancel_id` to `desktop-stop` (`tui_gateway/methods_groups.py:435`), and its fence text reads "Update Hermes Desktop to continue it." (`tui_gateway/methods_prompt.py:235-238`). Both engines reuse the `Group: <room_id>` session title on purpose "so a local-to-hosted migration keeps one transcript" (`tui_gateway/hosted_room_driver.py:6-7`; `gateway/platforms/api_server_room_dispatch.py:17-19`).

**What the pinned Desktop does.** It never reads hosted rooms, and the gateway already treats it as an older build: any non-internal `prompt.submit` into a session titled `Group: <room_id>` whose id is a hosted room returns 4122 (`tui_gateway/methods_prompt.py:206-239`; `tests/tui_gateway/test_hosted_room_prompt_fence.py:1`). Nothing under `apps/` handles 4122 or 5122 (grep-negative for both codes under `apps/` at the pin).

Reimplementing Desktop's engine on a phone means a round driver that must survive process death, be a second writer to a blob whose schema is Desktop TypeScript, and depend on `ui_meta_revisions` CAS semantics this repo has not read (audit "Not covered"). The hosted engine already solves all three, at the cost of not being the pinned Desktop's integration surface.

## Decision

1. **Android's Bot Mode group chats use the gateway's hosted-room protocol.** Slices 6 and 7 call `groups.*` and never read or write `hermes-bots-groups`.
2. **Desktop's UI treatment is kept.** Layout, menu order, copy, glyphs and every visible state come from the pinned Desktop under the normal parity workflow. Only the API integration surface differs.
3. **The difference is ledgered as drift.** Every Desktop control, state or copy string the hosted choice removes or changes, and every hosted-only state Android adds, is a `drift` row in `docs/parity/bot-group-chat.md` with an issue number. Nothing hosted-caused is classified `mobile-adaptation`.
4. **Desktop's blob rooms are not rendered.** A room the pinned Desktop created under `hermes-bots-groups` does not appear on Android. The ledger records it as a `drift` row with evidence `#192 — not read by decision (ADR 0004); may return as a read-only mirror`.
5. **Hosted-only room kinds.** Android's room list is exactly `groups.list` on the active connection. There is no merged list and no second room store.
6. **Single-gateway rooms only.** Android creates rooms whose members are all local profiles of the active gateway. The seven replication and peer methods (`groups.replicate`, `groups.replica_state`, `groups.promote`, `groups.demote`, `groups.peer.invite`, `groups.peer.revoke`, `groups.peer.register`) are **non-goals**: each needs a second gateway, that gateway's API key, or an out-of-band process fence the phone cannot provide (`gateway/hosted_room_peer.py:3-4`; `website/docs/user-guide/bot-mode.md:186-200`). Rendering a mixed room that already exists on the authority gateway, with its peer route status, is **WIP** behind the marker chip, not a non-goal.

## Contract

### Methods Android calls

Eleven of the eighteen. All run on the RPC thread pool (`tui_gateway/methods_groups.py:23`; `tui_gateway/server.py:762-785`). Success is `{"jsonrpc":"2.0","id","result"}`; failure is `{"jsonrpc":"2.0","id","error":{"code","message"[,"data"]}}`. `data.reason` appears only when a `HostedRoomError` subclass with a `reason` attribute is mapped through a method's `room_code` (`tui_gateway/methods_groups.py:209-212`; envelope `_ok`/`_err` at `tui_gateway/server.py:699-705`). The only two reasons are `room_history_expired` and `authority_conflict` (`gateway/hosted_rooms.py:185-198`).

| Method | Params | Result | Needs worker | Citation |
|---|---|---|---|---|
| `groups.capabilities` | `profile?` | `{protocol_version: 2, driver, persistent_process, authority_gateway_id, room_link, features[], methods[18], max_log_limit: 500}` | no (`driver` reports it) | `tui_gateway/methods_groups.py:218-247` |
| `groups.list` | `limit? (1..500, default 500)`, `offset? (>=0)`, `include_disbanded? (literal true only)` | `{rooms[], next_offset: int|null}`; ordered `updated_at DESC, room_id ASC`; `next_offset` set only when the page is exactly full | no | `:346-356`; `gateway/hosted_rooms.py:870-881` |
| `groups.state` | `room_id`, `include_disbanded?` | `{room, driver_status?}`; `room` adds `latest_seq` and optional `authority_claim`; `driver_status` present only while the worker is live and the room is not disbanded | no | `:369-380`; `gateway/hosted_rooms.py:985-997` |
| `groups.log` | `room_id`, `since_seq? (>=0, default 0)`, `limit? (1..500, default 100)`, `include_disbanded?` | `{events[], cursor, latest_seq, has_more, authority:{gateway_id, epoch}}`; page serialised size capped at 2 MiB so a page may be shorter than `limit`; `since_seq > latest_seq` is an error | no | `:489-495`, `:172-173`; `gateway/hosted_rooms.py:33-34, 1111-1147` |
| `groups.create` | `room_id` (required identifier, `^[A-Za-z0-9][A-Za-z0-9._:-]*$`, <=128; the gateway never mints one: `gateway/hosted_rooms.py:206-207` and `identifier()` at `gateway/hosted_rooms_common.py:23-32` raise `room_id must be a string` on a missing value, surfaced as 4110), `name` (non-blank, <=200, no pattern: `gateway/hosted_rooms.py:26, 212-213`), `members[]` (2..6) | `{room}`; a fresh create returns the room **without** `latest_seq` (the reload SELECT at `gateway/hosted_rooms.py:862-866` omits `next_seq`, and `_room_from_row` at `:474` adds `latest_seq` only when that column is present); only the idempotent replay (`idempotent: true`, read through `_SELECT_ROOM_WITH_BYTES`, `:840, :855`) carries it. Android reads `latest_seq` from `groups.state` or `groups.list`, never from the create result | yes (4123) | `:359-366`; `gateway/hosted_rooms.py:828-867` |
| `groups.send` | `room_id`, `event_id` (client retry key, identifier <=128), `payload: {text (<=64 KiB, non-blank), thread_id (identifier)}` exactly; a top-level `actor` param is ignored (the handler reads only `room_id`, `event_id`, `payload`, `tui_gateway/methods_groups.py:387-392`); an extra key inside `payload` is a 5112 `user payload has unknown fields` error (`gateway/hosted_rooms_common.py:42-55`; `gateway/hosted_room_discussion.py:189`) | `{event, client_event_id, accepted: true, driver_started: true}`; `driver_started` is a literal `True` and carries no information | yes (4123) | `:383-395`; `tui_gateway/hosted_room_service.py:446-458`; `gateway/hosted_room_discussion.py:49, 187-192` |
| `groups.stop` | `room_id`, `cancel_id? (default "desktop-stop")` | `{cancelled: <count of stoppable tasks>}`; appends one `room.stop_requested` | yes (4115) | `:431-436`; `tui_gateway/hosted_room_service.py:460-480`; `gateway/hosted_rooms.py:1000-1007` |
| `groups.rename` | `room_id`, `event_id`, `name` | `{room: {..., event}}`; appends `room.renamed {name}` | no | `:485-488`; `gateway/hosted_rooms.py:884-910` |
| `groups.disband` | `room_id`, `cancel_id? (default "room-disbanded")` | `{tombstone: {room_id, disbanded_at, idempotent, event?}}`; stops with `require_acknowledged`, so a room still stopping returns 5114 and must be retried; the id is retired forever | yes (4123) | `:398-428`; `gateway/hosted_rooms.py:1079-1108` |
| `groups.retry` | `room_id`, `task_id` | `{retried: true, task: {room_id, task_id, thread_id, turn_id, status, execution_generation, cancel_generation}}` | yes (4115) | `:450-464` |
| `groups.approve` | `room_id`, `member_id`, `task_id`, `execution_generation`, `choice ∈ once|deny`, `request_id`; all must match the pending action exactly | `{approved: true, result}` | yes (4115) | `:439-447`; `tui_gateway/hosted_room_service.py:490-526` |

**Member record** (`{member_id, profile, handle, target, display_name?}`): `target` is `{kind:"local", profile}` or `{kind:"peer", peer_id, installation_id, profile, capability_digest}`; handles are unique and `all`/`everyone` are reserved; Desktop's cross-connection fields (`connectionId`, `route`, `remoteSource`, ...) are refused (`gateway/hosted_room_discussion.py:43-48`; `tui_gateway/hosted_room_service.py:432-444`). A local profile is `default` plus every directory under the install's `profiles/` (`tui_gateway/hosted_room_service.py:126-130`), so Android maps a local member to the roster by `member.profile == HermesProfile.name`.

**Room record** (`{room_id, name, members[], authority_gateway_id, authority_epoch, revision, created_at, updated_at, idempotent, disbanded_at?, latest_seq?}`, `gateway/hosted_rooms.py:465-474`): `disbanded_at` is present only when set, and `latest_seq` only when the query selected `next_seq` (see `groups.create`). A gateway holds at most `MAX_ACTIVE_ROOMS = 256` live rooms (`:36`); the next create fails with `This host has too many active Group Chats. Delete one and try again.` (`:857-858`, a 4110).

**`driver_status`** (`tui_gateway/hosted_room_service.py:528-548`): `{running, working, blocked, counts{status: n}, pending_actions[], peer_routes[]}`. Task statuses are `queued, running, settled, failed, cancelled, indeterminate, deferred, stopping` (`gateway/hosted_room_driver.py:25`). `working` is any `queued|running|stopping`; `blocked` is the room in the runtime's blocked set or any `indeterminate|stopping`. `pending_actions` holds `{kind:"retry", task_id}` for every `indeterminate|deferred` task (`tui_gateway/hosted_room_service.py:35-37`) and `{kind:"approval", task_id, execution_generation, run_id, session_id, request_id, approval{choices ⊆ [once, deny]}, member_id}` for a member waiting on approval (`tui_gateway/hosted_room_driver.py:392-405`; `tui_gateway/hosted_room_service.py:297-303`).

### Log event kinds Android renders

Every event is `{room_id, seq, event_id, kind, actor{kind, id, display_name?, profile?, connection_id?}, authority_epoch, payload, created_at, idempotent}` (`gateway/hosted_rooms.py:58-60, 477-482`). `seq` is contiguous per room from 1 (`:941-947`; from 1 at `gateway/hosted_rooms.py:859-861`). The admissible kinds per actor are at `gateway/hosted_rooms.py:50-57`; the exact payload field sets at `gateway/hosted_room_discussion.py:49-64`.

| kind | Producer | Payload | Android renders | Citation |
|---|---|---|---|---|
| `message.user` | `groups.send` | `{text, thread_id}` | user bubble in its thread; its `event_id` is the `discussion_event_id` later events cite | `tui_gateway/hosted_room_service.py:446-452` |
| `message.member` | a settled non-pass turn | coordinates + `text` | assistant bubble attributed to `payload.member_id`, labelled by roster `display_name` else `@handle` | `gateway/hosted_room_discussion.py:52, 694-699`; appended at `tui_gateway/hosted_room_service.py:381-382` |
| `turn.settled` | end of a turn | coordinates + `seen_through_seq, message_event_id, passed` | status only; `passed: true` is a silent member | `gateway/hosted_room_discussion.py:53-55` |
| `turn.failed` | failed or timed-out turn | + `error`, `reason_code?` | error row for that member, showing `error` | `:53-57`; timeout `tui_gateway/hosted_room_service.py:40-46` |
| `turn.cancelled` | stop, or a newer user message on the same thread | + `reason` | "stopped" marker, no bubble | `:53-56, 769-772` |
| `turn.deferred` | member unavailable | + `execution_generation, reason` | member-unavailable state with the `groups.retry` action from `pending_actions` | `:53-56` |
| `room.activity` | discussion ended | `{status: settled|bounded, reason_code: silent_round|max_messages|max_rounds, thread_id, discussion_event_id}` | discussion-complete divider; `bounded` says a cap was hit | `tui_gateway/hosted_room_service.py:386-399`; `gateway/hosted_room_discussion.py:60-64, 618-619, 646-649` |
| `room.stop_requested` | `groups.stop` / `groups.disband` | `{cancel_id}` | "stopped" divider; user messages at or before its seq are fenced | `gateway/hosted_rooms.py:1000-1007`; `gateway/hosted_room_discussion.py:559-569` |
| `room.renamed` | `groups.rename` | `{name}` | system line; header updates | `gateway/hosted_rooms.py:884-910` |
| `room.disbanded` | `groups.disband` | `{room_id}` | terminal; only readable with `include_disbanded: true` | `gateway/hosted_rooms.py:1079-1108` |
| `authority.claimed`, `authority.lost` | authority moves between gateways | `{previous_gateway_id, authority_gateway_id, authority_epoch}` | system line; the room becomes read-only on this connection after `authority.lost` | `gateway/hosted_rooms.py:1010-1058`; `authority.lost` producer `gateway/hosted_room_replicas.py:253` (`demote_room`, appended at `:276-279`) |
| any other kind | none at the pin: `room.created`, `room.members_changed`, `member.unavailable`, `turn.started`, `turn.reassigned` are admissible but have no hosted-room log producer under `gateway/` or `tui_gateway/` (the only `turn.started` emitter, `tui_gateway/compute_host.py:228`, is a session RPC reply, not a room-log append) | any object | generic system line; never crash on an unknown kind | `gateway/hosted_rooms.py:53-57` |

Do not filter events by `authority_epoch` when rendering; each event carries the epoch it was written under and the page-level `authority` is the current one (`gateway/hosted_rooms.py:481, 1123`). Render from the log only: member sessions titled `Group: <room_id>` hold the driver's synthetic prompt and raw replies and are fenced against `prompt.submit` (`tui_gateway/methods_prompt.py:206-239`).

### Polling

There are no push events for hosted rooms; nothing in `tui_gateway/methods_groups.py`, `tui_gateway/hosted_room_service.py` or `gateway/hosted_rooms.py` emits one. Android keeps a per-room `cursor` (the last `seq` rendered) and:

- On open and on reconnect: `groups.state`, then `groups.log {since_seq: cursor}` looping while `has_more` is true (loop on `has_more`, not on `len(events) == limit`, because the byte cap can shorten a page: `gateway/hosted_rooms.py:1141-1147`).
- While `driver_status.working` or `blocked` is true, or within a few seconds of a send: poll `groups.state` every 1 to 2 seconds and page the log whenever `room.latest_seq > cursor`. The worker itself observes at 0.25 s active and 5 s idle (`tui_gateway/hosted_room_service.py:30-31`), so a tighter client cadence buys nothing.
- Idle and in the foreground: every 10 seconds or on pull-to-refresh. In the background: no polling; the room catches up on return, which is the whole point of the hosted choice.
- A `groups.log` error "since_seq is ahead of the hosted room log" (`gateway/hosted_rooms.py:1124-1125`) means the cursor is stale for this room id: reset the cursor to 0 and re-page.

### Idempotent `event_id`

Android mints one identifier per outgoing message, stores it with the draft before calling `groups.send`, and reuses it on every retry of that message. The gateway namespaces the key server-side (`user_event_id`, `tui_gateway/methods_groups.py:389-390`) and returns both the stored `event.event_id` and the raw `client_event_id` (`:393-395`). A replay with identical content returns the original event with `idempotent: true` and does not advance `seq`; a replay with different content is a 4111 "event_id already exists with different content" (`gateway/hosted_rooms.py:932-936`). That error means the key was reused for a different text: mint a new key, never overwrite. `thread_id` is client-chosen (`gateway/hosted_room_discussion.py:49, 187-192`); Android mints one per thread and a fixed one for the main thread.

### Errors

| Code | Method | Meaning | Android action | Citation |
|---|---|---|---|---|
| 4110 / 4111 / 4112 / 4113 / 4114 / 4117 | create / send / log / disband / state / rename | `HostedRoomError` mapped through `room_code` | inspect `data.reason` below; otherwise show `message` verbatim (upstream writes product-facing text such as "This Group Chat reached its history limit. Start a new Group Chat to continue.") | `tui_gateway/methods_groups.py:346-395, 398-428, 485-488` |
| 4111 / 4113 with `data.reason: authority_conflict` | send / disband | this gateway no longer owns the room | mark the room read-only, refresh `groups.state`, disable every write control | `tui_gateway/hosted_room_service.py:142-148`; `tui_gateway/methods_groups.py:409-412` |
| 4111 / 4112 / 4114 with `data.reason: room_history_expired` | send / log / state | the room was pruned; the id is retired forever | drop the room from the cache with a tombstone; never recreate under that id | `gateway/hosted_rooms.py:440-449` |
| 4115 | stop / approve / retry | "hosted room driver is unavailable" | show the message; keep the action enabled for retry | `tui_gateway/methods_groups.py:31, 431-464` |
| 4118 | promote | requires `confirm: true` | never sent (non-goal) | `:508-517` |
| 4119 / 4120 / 4121 | demote / peer.invite / peer.register | replica or peer errors | never sent (non-goal) | `:250-279, 297-343, 520-524` |
| 4122 | peer.revoke; **and** `prompt.submit` into a hosted `Group: <room_id>` session | the fence | must never occur: Android never calls `prompt.submit` on a `Group:` session (see Consequences) | `:282-294`; `tui_gateway/methods_prompt.py:206-239` |
| 4123 | create / send / disband | "Group Chat worker is unavailable. Restart the Hermes gateway and try again." | show the message beside the composer; keep the draft and its `event_id`; the read path still works | `tui_gateway/methods_groups.py:30, 359-366, 383-395, 398-428` |
| 5110 to 5120 | any | anything that is not a `HostedRoomError`, including roster and payload validation (`DiscussionValidationError`) | show `message`; treat 5114 after disband as "still stopping, retry" | `tui_gateway/methods_groups.py:209-212`; `tui_gateway/hosted_room_service.py:477-478` |
| 5116 with message "This Group Chat is managed by another gateway." | stop | `groups.stop` has no `room_code`, so the authority conflict arrives without `data.reason` | same as `authority_conflict` | `tui_gateway/methods_groups.py:431-436`; `tui_gateway/hosted_room_service.py:142-148, 462` |
| -32000 / -32601 / -32603 | any | pool handler crash / unknown method / WS dispatch crash | a gateway without `groups.*` answers -32601: treat as "hosted rooms unsupported" | `tui_gateway/server.py:734, 762-785`; `tui_gateway/ws.py:326-342` |

### Capability gating

`groups.capabilities` is read once per connection and again on reconnect.

- `-32601` or `protocol_version != 2`: the Group chats section renders empty with a "needs a newer gateway" state; nothing else is called.
- `driver: false` (worker thread not alive, `tui_gateway/methods_groups.py:222-223`): read-only mode. `groups.list`, `groups.state`, `groups.log` and `groups.rename` still answer (`:184-215`, no `service_code`); create, send and disband would return 4123 and stop, approve and retry 4115 (`tests/tui_gateway/test_groups_methods.py:824-831`), so every write control renders disabled with the 4123 text as its limitation line, and the app re-reads capabilities on the next poll.
- `room_link.enabled: false`: irrelevant to single-gateway rooms; `room_link` only gates cross-gateway execution. Its `reason` is `durable_run_storage_required` or, for every other failure including approvals `off`, `gateway_roomlink_secret_unavailable` (`:235-238`; `gateway/hosted_room_peer.py:255-258`). Android does not surface it.
- `persistent_process: false`: the discussion does not keep running beyond the gateway process; the room and its log still persist in `state.db` and are recovered on the next start (`hermes_cli/web_server.py:165-177`; `gateway/hosted_rooms.py:398-402`). Android shows Desktop's "rooms keep running" promise only when this is true (`:241`; `gateway/hosted_room_peer.py:250-251`).

## Reachability per route

All eighteen methods are plain handlers on the `/api/ws` dispatcher. The pre-accept gates are chat enabled, WS-upgrade auth and the Host/Origin/peer check (`hermes_cli/web_routers/chat_ws.py:137-149`); after that `handle_ws` hands every method to `server.dispatch` with no allowlist (`tui_gateway/ws.py:326-342`), and `dispatch` submits long handlers to the pool without an identity check (`tui_gateway/server.py:762-785`).

| Route | Credential at upgrade | Worker | `driver` | `persistent_process` | Note |
|---|---|---|---|---|---|
| Remote Gateway | single-use ticket in gated mode (`hermes_cli/web_server_chat.py:220-232`) | started by `hermes serve` lifespan (`hermes_cli/web_server.py:165-187`) | true once the startup thread finishes | true unless `room_link` fails (`HERMES_DESKTOP=1`, approvals `off`, or non-durable run storage; `tui_gateway/methods_groups.py:227-241`; `gateway/hosted_room_peer.py:250-258`) | the preferred route; rooms outlive the phone |
| Managed SSH | legacy `?token=` on loopback | same `hermes serve` process | true | **false**: the app spawns serve with `HERMES_DESKTOP=1` (`android:docs/adr/0001-ssh-probe-to-tunnel.md:74`; `android:app/src/main/kotlin/.../data/gateway/RemoteLifecycle.kt:412, 436`), which the loopback auth exemption requires (`hermes_cli/web_server.py:468-487`) and which forces the flag false | rooms run only while the app-owned process lives; the UI must not promise they keep running |
| Local (Termux) | legacy `?token=` on loopback | Termux `hermes serve` | true | true on paper | upstream calls Termux persistence best-effort (`website/docs/getting-started/termux.md:43`); neither upstream nor `android:docs/guides/termux-local-gateway.md` mentions hosted rooms on a phone |

**Approvals.** A member turn waiting on approval surfaces as `driver_status.pending_actions[{kind:"approval"}]` (`tui_gateway/hosted_room_driver.py:392-405`), and `groups.approve` forwards a local member's choice as an `approval.respond` on the hidden session (`tui_gateway/hosted_room_service.py:517-520`; `tui_gateway/hosted_room_server_rpc.py:119`). The choices are `once|deny` only (`:507-508`). When `approvals.mode` is `off`, the hosted catalog refuses cross-gateway execution (`gateway/hosted_room_peer.py:255-258`); whether a local member turn then runs unapproved was not read and is an open question.

## Consequences

**Positive.**
- Rooms are durable on the gateway. A phone that sleeps mid-discussion catches up from `groups.log`; no round driver has to survive process death.
- One writer per room. Android is never a second writer to Desktop's blob, so it cannot corrupt a Desktop client (audit section 3.2's risk disappears).
- Idempotent send and a contiguous `seq` make retry and resume mechanical.
- The engine's caps and prompts are upstream's, so behaviour matches the documented product rather than a port's reimplementation.

**Negative.**
- **Android rooms are invisible to the pinned Desktop.** Hosted rooms live only in `state.db` and never reach `hermes-bots-groups`; the pinned Desktop never calls `groups.*`. A person with both clients sees two disjoint room lists until Desktop moves onto the hosted protocol.
- **Desktop controls with no hosted source ship WIP-disabled or are ledgered:** per-member holds and the "Paused: a, b" banner, the clarify card, approval choices beyond `once|deny`, the "N of M available" badge, room pictures, attachments (`catalog.attachments` is false and the payload is `{text, thread_id}`), membership edits and Manage groups (members are frozen at create), cross-connection members, and mention-by-display-name (the hosted engine resolves handles only: `gateway/hosted_room_discussion.py:37, 291-303`).
- **Desktop copy whose meaning changed under hosted is drift, string by string:** `holdReleaseHint`, `heldMembersStatus`, `allHeldStatus`, `stopped()`, `stopHint` (`apps/desktop/src/plugins/hermes-bots/i18n.ts:403-406, 422`) and the create-dialog description each describe per-member holds or a client-driven stop that the hosted engine does not have. Each is a drift row with an issue number.
- **Hosted states with no Desktop UI need new Android UI:** 4123 and 4115 unavailability, `authority_conflict` and the `authority.*` events, `blocked` with a retry action, `turn.deferred`, `room_history_expired`, storage and room caps, log paging. Each is a drift row with an issue number: it is a difference from the pinned Desktop that the hosted choice caused, and the owner's decision is that every such difference is drift.
- There are no push events. Polling costs battery and adds latency the Desktop transcript does not have.
- On Managed SSH the "rooms keep running" promise is false and the UI must say so.

**What the parity page must say.** `docs/parity/bot-group-chat.md` opens its `## Divergences` section with a row stating that Android's group chats use the hosted-room protocol and the pinned Desktop uses `hermes-bots-groups`, classified `drift`, evidence `#193`. The blob-rooms row is `drift` with evidence `#192 — not read by decision (ADR 0004); may return as a read-only mirror`. Every removed or changed Desktop control, state or copy string, and every hosted-only addition, is a `drift` row with an issue number. Nothing hosted-caused is classified `mobile-adaptation`; that class stays reserved for touch, viewport, accessibility and explicit mobile priorities per `docs/workflows/review-desktop-parity.md`. Every drift row is at least a Concern on review, and that is the intended cost of this decision.

**The 4122 fence.**
- Android must never call `prompt.submit` on a session titled `Group: <room_id>`. Member sessions are the driver's plumbing (`tui_gateway/hosted_room_driver.py:6-7`), and a direct prompt into a hosted one is refused with 4122 (`tui_gateway/methods_prompt.py:206-239`). The transcript is rendered from the log only.
- Android must never share a `room_id` namespace with Desktop blob rooms. Android mints its own identifiers and never reuses a Desktop `roomId`, because the fence keys on the shared `Group: <room_id>` title and a collision would lock the Desktop room out of its own session.

## Alternatives considered

- **Reimplement Desktop's client-side engine.** Keeps rooms shared with the pinned Desktop. Rejected: a phone-hosted round driver must be cancellable, resumable and death-safe; it makes Android a second writer to a Desktop-typed blob; it depends on `profiles.configure` `ui_meta` write and `ui_meta_revisions` CAS semantics this repo has not read; and upstream's stated direction is away from it.
- **Read-only mirror of the blob.** Render Desktop's rooms from `profiles.list` `ui_meta` beside hosted rooms. Not rejected forever; deferred. It would give a person with both clients one list, but the schema is Desktop TypeScript with no recorded fixture, and a merged list needs a rule for name and id collisions. The ledger's blob-rooms row (`drift`, `#192`) is where it returns.
- **Hybrid: hosted engine, blob written for Desktop's benefit.** Write a `hermes-bots-groups` entry mirroring each hosted room so the pinned Desktop lists it. Rejected: the pinned Desktop would then `prompt.submit` into the hosted member session and receive 4122 with no handler (#199), which is worse than not seeing the room.

## Open questions

1. **Approvals `off` and local member turns.** Whether a local hosted turn under `approvals.mode: off` bypasses approval entirely, so `pending_actions` never carries an approval, was not read. It decides whether Android's approval card is reachable on a profile using the Off control (`docs/parity/approval-mode.md`).
2. **Worker liveness on the Remote route in practice.** The startup thread runs on every `hermes serve`, but `driver` is per process; a deployment running only the messaging gateway's worker on the same `state.db` is untested from Android.
3. **Kinds without a producer.** `room.created`, `room.members_changed`, `member.unavailable`, `turn.started` and `turn.reassigned` are admissible but have no hosted-room log producer at the pin under `gateway/` or `tui_gateway/` (the only `turn.started` emitter, `tui_gateway/compute_host.py:228`, is a session RPC reply, not a room-log append). A later upstream may start emitting them; Android renders them as generic system lines until then.
4. **Peer members in rooms created elsewhere.** Rendering `target.kind: "peer"` members and `peer_routes` status (`ready|unavailable|needs_reauthorization`) is WIP and needs a captured fixture.
5. **Threading UI.** The hosted `thread_id` is enough for Desktop's threads, but the Desktop threading UI itself was not read in the audit.
6. **Desktop's own move onto the protocol.** When a later Desktop pin calls `groups.*`, the drift rows above collapse; the parity page must be re-classified against that pin, not this one.
