# Spike: the four gateway contracts that gated Bot Mode

Status: contract read. No product code. Each section is what a slice needs in order to be built,
read at the pin rather than inferred.

| | |
|---|---|
| Upstream pin | `NousResearch/hermes-agent` @ `564aef2946c436500a5e80ee117b66b789b3f99a` |
| Read | 2026-09-12 |
| Method | `git show <sha>:<path>` against the read-only checkout; nothing written, fetched or checked out |

The Bot Mode epic recorded that "the real blockers aren't code, they're unread contracts" and named
three. A fourth — `cron.manage` — was read at the same time because #191 is the shortest path to a
usable surface and reading it first is what makes that slice cheap.

**All four came back UNBLOCKED, and every one of them corrected the slice it unblocks.** Those
corrections are the reason this document exists; the raw shapes are below them.

## The corrections, first

- **#190's gate dissolves.** The delivery-target "advertisement" is not a client claim. There is no
  RPC for it, no client can send it and none can withhold it — it is a metadata key the *gateway
  process* writes into a host-side registry when it claims a per-session lease. The read path claims
  no lease and therefore cannot capture, break or lose a delivery. What needs a decision is not the
  read path but the first prompt sent from Android into a Bot Chat, which silently makes that
  gateway runtime the live delivery target.
- **#191 asks for two things that cannot be built.** `cron.manage` has no `update` and no
  `run`/`trigger` action at this pin, and it is the only `cron.*` method the gateway registers.
  Full job editing and trigger-now are not descoped, they are unimplementable over this method, so
  both must ship visible-and-disabled behind the WIP chip.
- **#193's body is stale against this repo's own ADR.** It asks for CAS writes back to
  `hermes-bots-groups`; `docs/adr/0004-hosted-rooms-for-group-chats.md:25,28` says Android's group
  chats ride `groups.*` and never read or write that blob. The ADR's premise was re-verified at the
  new pin — no file under `apps/desktop/src/**` calls any `groups.*` method — so Desktop is still
  blob-only and the ADR still holds. The `ui_meta` contract below is what a *per-bot* write
  (hide / pin / rename) needs, and the fallback spec if the ADR is ever reversed.
- **#194's 15 MB cap is not the contract.** That is Desktop's file-picker cap. The server cap is
  **2,000,000 bytes** on decoded bytes.


---

# #190 — Bot Chat, one canonical chat per bot

**Verdict:** UNBLOCKED — and the gate itself dissolves. The "advertisement" is not a client claim at all: there is no RPC for it, no client can send it, and no client can withhold it. It is a metadata key the *gateway process* writes into a host-side file registry when it claims a per-session lease, and the only writer at the pin is `tui_gateway` — the exact dispatch surface this app already talks to over `/api/ws`. #190's read path claims no lease, so it cannot capture, break or lose a delivery. Ship it. The behaviour that needs a decision is not the read path but the *first prompt sent from Android into a Bot Chat*, which silently makes the gateway's runtime for that session the live delivery target.

## Methods

THE ADVERTISEMENT (no RPC; a host-side registry write)
- `try_acquire_active_session(*, session_id, surface, config, metadata=None, registry_home=None, track_liveness=False)` — hermes_cli/active_sessions.py:442-445 @564aef29; writes the entry at :467-470 / :530-531.
- Only caller that sets the flag: `_claim_active_session_slot` — tui_gateway/session_lifecycle.py:29-45, flag literal at :36 `metadata={"live_session_id": live_session_id, "bot_live_delivery_consumer": True}`; `track_liveness=str(surface or "").strip().lower() == "desktop"` at :37.
- Re-anchored across compression by `transfer_active_session(lease, session_id=…, metadata={"live_session_id": sid, "bot_live_delivery_consumer": True})` — tui_gateway/session_lifecycle.py:143-144; impl hermes_cli/active_sessions.py:554-590.
- Claimed ONLY on a turn: `_ensure_active_session_slot` — tui_gateway/session_lifecycle.py:48-59 ("session.create/resume deliberately do NOT claim"). Call sites: tui_gateway/methods_prompt.py:566, tui_gateway/prompt_turn.py:89, tui_gateway/session_auto_continue.py:101. Nowhere else.
- Registry file: `<profile_home>/runtime/active_sessions.json` — hermes_cli/active_sessions.py:167-172; entry fields at :421-439.

THE READER (who resolves the target)
- `find_canonical_live_owner(profile_home)` — tools/bot_live_delivery.py:27-54; title lookup + tip at :41-42; flag test at :50 `meta.get("bot_live_delivery_consumer") is True and meta.get("live_session_id")`; snapshot via `active_session_registry_snapshot` (hermes_cli/active_sessions.py:668-678).

THE MAILBOX
- `deliver_to_live_owner(profile_home, owner, message, *, delivery_id=None, author=None)` — tools/bot_live_delivery.py:122-151.
- `claim_pending_delivery(profile_home, owner)` — :169-193. `complete_delivery(profile_home, delivery_id, *, status, reply="", error="", reason="")` — :196-218. `read_delivery_result(profile_home, delivery_id)` — :221-223.

THE PRODUCERS
- Cron: `_deliver_to_bot_chat(job, content, profile)` — cron/scheduler_delivery.py:656-716 (mailbox) then :718-774 (CLI fallback); called at :1758. Target token `bot-chat[:<profile>]` — :798-799. Config-time target list `cron_delivery_targets()` — :496-530, served at `GET /api/cron/delivery-targets` (hermes_cli/web_routers/cron.py:231-242).
- Local teammate DM: `_admit_live_dm(profile_home, dm_file, author)` — tools/bot_mode_dm.py:408-441; routed by `_run_delivery` :471-511 and `_start_delivery` :533-555; waiter `_wait_live_dm` :444-459; CLI fallback `_run_local_turn` :364-405.

THE CONSUMER
- `_poll_bot_live_delivery_once(sid, session)` — tui_gateway/session_notifications.py:492-541; runs on `_notification_poller_loop` :544-565 (call at :563), started by `_start_notification_poller` :652-659 from `_start_session_services` (tui_gateway/server.py:936-941) for every live gateway session.

A SECOND, DIFFERENT DELIVERY PATH (no advertisement involved)
- `bot_relay.deliver {profile, message, from_profile?, from_handle?, from_connection?}` — tui_gateway/methods_bot_relay.py:57-112; live-session scan at :88-92 by `profile_home` + live title == `BOT_CHAT_TITLE`; lands via `prompt.submit {session_id, text, queued: true}` at :105-108.

WHAT A CLIENT CAN ACTUALLY SEE
- `gateway.capabilities` — tui_gateway/methods_voice.py:434-439; returns `{"per_session_exclusive_submit": true}` and nothing else. No delivery-target field exists on any RPC.

#190's READ PATH
- `session.list {title, limit?, include_hidden?}` — tui_gateway/methods_session.py:425-437; exact-title branch `_session_list_by_title` :398-422.
- `session.resume {session_id, profile?, cols?, lazy?, defer_history?, omit_messages?, eager_build?, close_on_disconnect?, source?}` — :825-856; ctx `_Resume.__init__` :484-493; `lease=None  # claimed lazily on turn 1` :503-510; live reuse `_resume_reuse_live` :664-686.
- `profiles.list {include_sessions=true}` → `canonical_session` — tui_gateway/methods_profiles.py:237-254, row builder `_canonical_session_row` :138-170.

## Request shape

ADVERTISEMENT (verbatim, tui_gateway/session_lifecycle.py:34-37 @564aef29) — a Python call, not a wire message:
    try_acquire_active_session(
        session_id=session_key, surface=surface, config=_load_cfg(), registry_home=profile_home,
        metadata={"live_session_id": live_session_id, "bot_live_delivery_consumer": True},
        track_liveness=str(surface or "").strip().lower() == "desktop")

REGISTRY ENTRY WRITTEN (hermes_cli/active_sessions.py:426-438):
    {"lease_id": str, "session_id": str, "surface": str, "pid": int,
     "process_start_time": float|None, "started_at": float, "updated_at": float,
     "track_liveness": true (only when tracked),
     "metadata": {"live_session_id": str, "bot_live_delivery_consumer": true}}

OWNER TUPLE the mailbox pins (tools/bot_live_delivery.py:23, :52-53):
    _OWNER_KEYS = ("profile_home", "session_id", "lease_id", "live_session_id")  — all four must be non-empty strings (:57-62)

MAILBOX ADMISSION (tools/bot_live_delivery.py:122-125):
    deliver_to_live_owner(profile_home, owner, message, *, delivery_id: str|None = None, author: dict|None = None)
    delivery_id must match r"[0-9a-f]{32,64}" (:66-69)

#190 READ PATH, verbatim as Desktop sends it (apps/desktop/src/plugins/hermes-bots/canonical-chat.ts:200-205):
    session.list { profile: <bot profile name>, title: "Bot Chat", limit: PROFILE_SESSION_LIST_LIMIT, include_hidden: true }
    then session.resume { session_id: <resolved_id || id>, profile: <bot profile name> }   (canonical-chat.ts:342, 405, 496-497 + host.openSession opts :115-139)

## Response shape

`find_canonical_live_owner` → `None` or (tools/bot_live_delivery.py:52-53):
    dict(profile_home=str, session_id=str, lease_id=str, live_session_id=str)

Delivery record on disk at `<profile_home>/runtime/bot_live_delivery/<delivery_id>.json` (tools/bot_live_delivery.py:22, :72-73, :147-149):
    {"delivery_id", "id", "owner": {4 keys}, "profile_home", "session_id", "lease_id", "live_session_id",
     "message", "status": "queued", "created_at": ns, "sequence": int, "author"?: {...}}
  → claimed adds `"status": "claimed", "claimed_at"` (:191)
  → terminal adds `"status" ∈ {settled, failed, cancelled, ambiguous}, "reply", "error", "reason", "completed_at"` (:24, :204, :216)

`session.list` row (`_session_row_summary`, tui_gateway/methods_session.py:124-130):
    {"id", "resolved_id"?, "title", "preview", "started_at", "message_count", "source"}
  On the title path `resolved_id` is ALWAYS present (:422) and equals the compression tip; on the plain list path it is absent. There is no `hidden`, no `root_title` and no `archived` field in this shape.

`profiles.list` → `{"profiles": [...], "bot_mode_protocol": true}` (methods_profiles.py:254); `canonical_session` (:165-170):
    {"id", "resolved_id", "root_title", "title", "preview", "started_at", "last_active", "message_count"}

`gateway.capabilities` → `{"per_session_exclusive_submit": true}` (methods_voice.py:439).

Cron receipt surfaced to the job (cron/scheduler_delivery.py:705-713):
    job["_bot_chat_delivery_receipts"]["bot-chat:<profile>"] = {"status", "delivery_id"}; a non-`settled` status returns the string
    f"{target} {status} (receipt {key}): completion unverified; do not resend".

Teammate-DM JSON printed to the sender (tools/bot_mode_dm.py:454-458, :549-550):
    {"status", "delivery_id", "reply"?, "error"?, "reason"?, "detail"?} / {"status","delivery_id","to","detail","process_id"?}
Refusal when a lease fences the CLI lane (:393-397): {"error": "Delivery failed: @<who>'s Bot Chat is open on another surface right now, so your message was NOT delivered. Try again later.", "reason": "target_busy"}

## Semantics

1. WHAT A CLIENT SENDS, WHEN. Nothing, ever. No `session.*` parameter, no `gateway.capabilities` field, no handshake carries it. The gateway writes `bot_live_delivery_consumer: True` itself, unconditionally, for every session that claims an active-session lease (tui_gateway/session_lifecycle.py:36) — regardless of the client's `surface` string. The docstring's "capability advertisement is mandatory; old Desktop/TUI processes must not receive work they cannot consume" (tools/bot_live_delivery.py:29-32) is a *backend-build* marker: an older gateway binary lacked the poller, so its leases lack the key and the mailbox skips them. website/docs/user-guide/bot-mode.md:116 says exactly this — "Older backends without live-delivery capability retain the existing ownership refusal; restart that backend after upgrading."

2. WHEN IT APPEARS. On the session's FIRST real turn, never on open. `_ensure_active_session_slot` is reached only from `prompt.submit` (methods_prompt.py:566), the turn runner (prompt_turn.py:89) and crash auto-continue (session_auto_continue.py:101). `session.create`/`session.resume` register the record with `lease=None  # claimed lazily on turn 1` (methods_session.py:503-510, and the comment at :345).

3. ELIGIBILITY — a conjunction of five facts, all host-side (tools/bot_live_delivery.py:34-53):
   (a) `<profile_home>/state.db` exists;
   (b) that DB has a row titled exactly `"Bot Chat"` (`get_session_by_title`);
   (c) `session_id = get_compression_tip(row["id"])` is non-empty;
   (d) a live registry entry in THAT profile's registry has `entry["session_id"] == session_id`;
   (e) that entry's metadata has `bot_live_delivery_consumer is True` AND a non-empty `live_session_id`.
   Scope, precisely: per (profile_home, stored session id) → exactly ONE gateway runtime. Not per connection, not per client. Two clients that resume the same Bot Chat on one gateway share one runtime and one lease (`_resume_reuse_live`, methods_session.py:664-686; `_reattach_refusal` :470-478 refuses only staleness and a settling disconnect interrupt, never a second viewer), so Android opening a Bot Chat beside Desktop does not steal it — they are the same target.
   INELIGIBLE holders that still hold the lease: `hermes chat` CLI passes `metadata={"live_session_id": …}` with NO flag (cli.py:2963-2970); the messaging gateway passes `{"platform","chat_id","user_id", …}` with no flag (gateway/run_busy.py:185-196). So a Bot Chat held live by the CLI is a live owner the mailbox cannot see — see failure mode 4b.

4. NO TARGET ADVERTISED — the actual code path, both producers:
   (a) CRON. `_deliver_to_bot_chat` reads any existing receipt first (`read BEFORE discovery`, cron/scheduler_delivery.py:693-695), then `find_canonical_live_owner`; `None` ⇒ no mailbox record ⇒ control falls past the `if receipt is not None` block to :718 and spawns `hermes [-p <profile>] chat --in ~ -c "Bot Chat" --create-if-missing -Q --query-file <tmp>` (:753-759) with a 600s default timeout (`cron.bot_chat_delivery_timeout_seconds`, :645-653). NOT dropped, NOT queued: delivered by a headless one-shot CLI turn writing into the same durable `Bot Chat` row, creating it if absent. Non-zero exit ⇒ `"bot-chat delivery to profile '<p>' failed (exit N): <tail>"` recorded as a delivery failure (:760-764).
   (b) LOCAL TEAMMATE DM. `_admit_live_dm` returns `None` (bot_mode_dm.py:420-422) ⇒ `_run_delivery` skips the mailbox and takes the per-profile turn lock + `_run_local_turn` (:499-505). If a *flag-less* lease (CLI, messaging gateway) holds that Bot Chat, the CLI turn is fenced by `SESSION_NOT_OWNED` (hermes_cli/active_sessions.py:103, :516-520) and the sender gets `{"reason":"target_busy"}` — the DM did NOT run (bot_mode_dm.py:386-398). That is the one genuine drop, and it is exactly the case the flag was invented to remove.
   (c) CROSS-CONNECTION RELAY is a third, independent rule: `bot_relay.deliver` never consults the advertisement. It scans the gateway's in-process `_sessions` for a record whose `profile_home` matches and whose *live* title is `Bot Chat`, and submits `prompt.submit {session_id, text, queued: true}` (methods_bot_relay.py:88-108); otherwise the same CLI subprocess (:119-140). A session that has been resumed but has never taken a turn IS eligible here (no lease required) while being invisible to the mailbox.

5. CAPABILITY OR REGISTRATION? Registration, recorded by the gateway. It PERSISTS on disk in `<profile_home>/runtime/active_sessions.json`. It does NOT expire and there is NO heartbeat: `updated_at` is stamped once at claim (hermes_cli/active_sessions.py:433) and refreshed only on a transfer (:582). Liveness is process liveness — `_prune_dead` keeps an entry iff `_pid_liveness(pid, process_start_time)` says the owning PID exists and its start time matches (:322-360). The PID is the GATEWAY's, not the client's, so the registration outlives an Android disconnect and dies with the gateway. Release is explicit: `_release_active_session_slot` on finalize (session_lifecycle.py:222-223, :253-254). "Unknowable" liveness raises rather than pruning, and every path fails CLOSED (:356-357, :414-418, session_lifecycle.py:38-45).

6. MAILBOX SEMANTICS the consumer must honour. FIFO by `(sequence, delivery_id)` where `sequence` is a lock-held high-water mark immune to wall-clock rollback (bot_live_delivery.py:144-146, :189-190). At-most-once: `queued → claimed → terminal`, and "Claims never expire: a crashed consumer leaves an inspectable unknown outcome, not permission to execute the same input again. Receipts are permanent." (:4-6). Same id + different payload is an error, never an overwrite (:139-140); duplicate identical completion is idempotent, a differing terminal raises (:210-213). Claim matching tolerates ONE kind of drift: `lease_id` and `live_session_id` must be identical, while a `session_id` mismatch is accepted only along the original row's compression chain (`_matches`, :154-166). The consumer admits only at a true idle boundary — not running, not closing/finalized, no queued prompt, no auto-continue scheduled, agent present, lease held and not released, and the resolved owner's `lease_id`/`live_session_id`/`session_id` must all equal this runtime's (session_notifications.py:497-509) — then runs the message as a normal turn id-stamped `__bot_dm__<delivery_id>` (:531-533) and writes the receipt from the terminal callback (:518-528).

7. DESKTOP. The renderer does not advertise anything and cannot: `git grep bot_live_delivery -- apps/ web/` at the pin returns nothing, and no renderer file touches `active_sessions` or `live_session_id`. Desktop becomes the target purely as a side effect of taking a turn in the Bot Chat it opened — including the birth kickoff prompt on creation (`kickoffText`, canonical-chat.ts:242-257; AGENTS.md:59 "kicked off with the bot's intro"). The only renderer symbol with a similar name, `deliveryTargetFromCommand` (components/assistant-ui/thread/agent-delivery.tsx:13-20), is an unrelated regex that pretty-prints a `hermes -p … chat … -q "Message from …"` terminal call as "Messaged X".

## Gotchas

- ASSUMING THERE IS A CLAIM TO MAKE. There is no RPC, no capability field, no opt-in. An implementer who adds `advertise_delivery_target` to a params object is inventing wire protocol. The only client-visible capability door, `gateway.capabilities`, returns one boolean (methods_voice.py:439).
- ASSUMING ANDROID CAN OPT OUT. It cannot. The gateway sets the flag for every lease it takes (session_lifecycle.py:36). The moment a user sends their first message in a Bot Chat from this app, that runtime is the live delivery target for the profile — cron fires and teammate DMs will execute as turns inside it. Withholding is only possible by not sending a prompt. The reasonable design is therefore "read path is inert; the composer is what arms it", and #190 must not describe the WIP chip as gating a claim the app never makes.
- TRUSTING THE AUDIT'S §3.3. At this pin the two delivery paths have DIFFERENT eligibility rules. `bot_relay.deliver` matches on live title with no lease and no flag (methods_bot_relay.py:88-92); cron and local DMs match on the flagged lease plus the compression tip (bot_live_delivery.py:34-53). A resumed-but-never-prompted Bot Chat is eligible for the first and invisible to the second. Any single sentence covering "delivery" is wrong about one of them.
- COPYING #190's `session.list {title, include_hidden: true}` AS A COMPOUND FILTER. On the title path, `include_hidden` and `limit` are dead: `_session_list_by_title` runs before either is consulted (methods_session.py:429-430) and resolves hidden rows unconditionally. Desktop still sends both (canonical-chat.ts:203-204); mirror them for older gateways, but do not build logic on them.
- RESUMING `id` INSTEAD OF `resolved_id`. A compacted Bot Chat's registry row is the lineage ROOT; the live tip is `resolved_id`. Desktop opens `resolved_id || id` (canonical-chat.ts:342, 405, 496-497), and the identity test accepts either (`isCanonicalChatOnScreen` :68-78). Resuming the root after compaction reaches the ended parent.
- OMITTING `profile` ON `session.resume`. This app derives it from `cache.session(durableId)?.remoteProfile` (GatewaySessionRepository.kt:1855-1857) — and a canonical Bot Chat is hidden, so it is NOT in `SessionCache` and the parameter comes out null, resuming against the launch profile's `state.db` instead of the bot's (`_Resume.profile` / `_profile_home`, methods_session.py:488-490). The bot's profile name must be threaded from the roster row, not looked up in the cache.
- READING A ZERO-ROW `session.list` AS "NO CHAT EXISTS". A profile backend mid-restart answers successfully with an empty list; Desktop fails CLOSED on that whenever the roster's `canonical_session.id` exists, and on any thrown error, precisely because minting there forks the forever-chat (canonical-chat.ts:186-234). This is the single most expensive bug in the surface's history.
- FILING A CRON FIRE WITH NO LIVE TARGET AS "DROPPED". It is delivered by a headless CLI turn into the same durable row (cron/scheduler_delivery.py:718-774). What is genuinely lost with no advertised target is the *live stream* and the durable receipt — plus, if a flag-less CLI lease holds the row, the DM is refused outright as `target_busy` (bot_mode_dm.py:386-398).
- TREATING `queued` AS DELIVERED. `deliver_to_live_owner` "Return[s] durable admission immediately, without waiting for the owner" (bot_live_delivery.py:126). Cron says so explicitly: "completion unverified; do not resend" (:711-712). A crashed consumer leaves `claimed` forever with no replay (:4-6). Any Android UI that renders delivery state must not promise completion from admission.
- CONFUSING `cron_delivery_targets()` WITH THIS. `GET /api/cron/delivery-targets` (hermes_cli/web_routers/cron.py:231-242, cron/scheduler_delivery.py:496-530) lists *configured* platforms plus one `bot-chat:<profile>` row per local profile, with `home_target_set: True` hardcoded. It is job configuration and says nothing about whether a live consumer exists.
- THE `source: "desktop"` THIS APP ALREADY SENDS. `session.create` hardcodes it (GatewaySessionRepository.kt:2092), which turns on `track_liveness` (session_lifecycle.py:37) and puts Android's created sessions inside the desktop-only automatic-cleanup lifecycle branch (`_AUTOMATIC_SESSION_END_REASONS`, :26, :219-223). `session.resume` sends no `source`, so resumed sessions fall back to the gateway host's env-resolved platform (`_resolve_session_source`, server.py:1401-1404) — usually `"tui"` on a headless `hermes serve`. Same app, two different lease lifecycles. Neither changes the delivery flag, but a slice that starts caring about lease lifetime must know this asymmetry exists.
- ASSUMING THIS IS ALL DESKTOP-ONLY MACHINERY. It is not: the app's `/api/ws` (GatewayRpc.kt:354, 480) is served by `tui_gateway.ws.handle_ws` (hermes_cli/web_routers/chat_ws.py:555-574) — the same dispatch, the same lease claim, the same per-session poller.

## What this app must build

SHIP #190's READ PATH AS SPECIFIED. Concretely, against the code that exists today:

1. `BotsPluginRepository` (app/src/main/kotlin/com/hermesagent/mobile/plugins/bots/BotsPluginRepository.kt) gains a second call beside `loadRoster()`: `host.request("session.list", {profile, title:"Bot Chat", limit, include_hidden:true})`. It must distinguish three outcomes, because `PluginHostResult` already models them: `Success` with a matching row → open; `Success` with zero rows → fail closed IF `row.canonicalSession?.id != null` (the parser already reads `canonical_session`, :80-84), else "no chat yet"; `Refused`/`UnavailableOnGateway` → fail closed, never mint. Parse `resolved_id` (already parsed into `BotSessionPreview.resolvedId`, :95-99) and `root_title`, which the current `parseSessionPreview` does NOT read and the title path does NOT return in `_session_row_summary` — the canonical test needs `title`, so add it to the row model rather than reusing `BotSessionPreview`.

2. The roster row is inert today: `BotRowItem` (BotsRosterScreen.kt:221-227) takes no `onClick` and the only `clickable` on the screen is `FilterPill` (:373-382). #190 adds the click, the "Open Bot Chat" item and Desktop's context-menu order (bot-row.tsx:307-443 per the audit) — all new UI, all subject to the rendered parity gate.

3. There is no door to open a chat from a plugin. `PluginNavigation` exposes `onBack`, `onOpenGateways`, `onNavigate(String)` (HermesApp.kt:65-69) and nothing that reaches `GatewaySessionRepository.openSession(durableId)` (:1988-2035). #190 must add one — an "open this durable session in Chat" contribution — and that is the real structural cost of the slice, not the RPC.

4. `openSession` needs a profile-aware entry. Today `owningProfileParam` (GatewaySessionRepository.kt:1855-1857) reads `remoteProfile` off `SessionCache`, and a hidden canonical row is not in the cache, so `session.resume` would omit `profile`. Either seed the cache from the resolved row before opening, or add an overload taking the profile explicitly. Per the backend-authoritative rule, seeding must be a layering upsert into `SessionCache`, never a clobber.

5. NOTHING to build for the delivery target, and nothing to disable behind a WIP chip on its account. The app cannot claim it and cannot refuse it. What #190 should record instead is the honest consequence: once the composer sends a turn in a Bot Chat, this app's gateway runtime becomes the profile's live delivery target and cron output / teammate DMs will land as turns in that chat — which is the desired product behaviour and needs no client code, only product copy and a `docs/parity/bot-chat.md` divergence row. If the epic wants a real gate, the only lever is whether the composer is enabled in a Bot Chat, not a capability claim.

6. Existing pin drift to fix while in here: `BotsRosterDerivation.kt:7-10` and `PluginHost.kt:25-26` still cite `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`; `BotsPluginRepository.kt:16-18` already cites 564aef29. Any file #190 touches should be re-cited at the new pin (#196/#231 remap work).

## What could not be established

1. Whether a lease can be held with the flag while the poller can never consume — the stuck-`queued` hole. `_poll_bot_live_delivery_once` returns early when `session["agent"] is None` (tui_gateway/session_notifications.py:498-501), while `find_canonical_live_owner` only reads the registry file and would still name that lease as the owner, so a producer would admit a record nothing ever claims. Every claim site I found is a turn path that implies an agent (methods_prompt.py:566, prompt_turn.py:89, session_auto_continue.py:101), so I believe the window is empty — but I did not exhaustively read the lazy-watch/`_child_run_active` records (methods_session.py:684-685, server.py:2412) to prove no such record can both hold a lease and have `agent is None`. The answer lives in tui_gateway/server.py's `_deferred_session_record` (server.py:2412 ff.) and tui_gateway/methods_session.py `_resume_lazy` (:711-731).

2. Whether the poller's turn actually streams to an attached Android client. `_run_prompt_submit("__bot_dm__<id>", …)` (session_notifications.py:531-533) emits through the session's normal turn machinery, and the app's foreground-isolation rules would then paint it or badge it unread — but I did not read `_run_prompt_submit` or the emit fan-out to confirm the frames carry the same `session_id` envelope this app's `GatewayGlobalEvents` pump keys on, nor whether a `__bot_dm__` prompt id is filtered anywhere client-side. The answer lives in tui_gateway/session_notifications.py's `_run_prompt_submit` definition and tui_gateway/prompt_turn.py.

3. What `hermes serve` resolves `_resolve_session_platform()` to in the two topologies this app ships (Remote Gateway on a host, Termux Local on the phone). It reads `HERMES_DESKTOP` / `HERMES_DESKTOP_TERMINAL` from the gateway process environment (tui_gateway/server.py:1395-1398), which I cannot determine from source — it depends on how the operator launched the service. This decides the `surface` on Android's *resumed* sessions and therefore `track_liveness`, not the delivery flag. Determinable only from a running gateway's environment, or from the launcher in hermes_cli/web_server.py / the systemd unit.

4. Whether the pinned Desktop ships any UI that *shows* delivery-target state. I proved the renderer never references `bot_live_delivery`, `live_session_id` or the lease registry under `apps/` and `web/`, so there is nothing to port — but I did not audit `apps/desktop/src/plugins/hermes-bots/relay.ts` or `bot-row.tsx`'s status derivation for an indirect signal (e.g. inferring "live" from `canonical_session.last_active`). If #190's parity table needs a "is this bot reachable live" affordance, that is where Desktop's answer would be.


---

# #191 — Routines, the bot-scoped cron tile

**Verdict:** UNBLOCKED — with one correction to the slice text: `cron.manage` has NO `update` and NO `run`/`trigger` action at this pin, and it is the ONLY `cron.*` JSON-RPC method the gateway registers. "Full job editing" and "trigger now" are not merely descoped, they are unimplementable over this method, so both MUST ship visible-and-disabled behind the WIP chip. Everything else the slice needs (list, create, pause, resume, delete, profile scoping, continuity, the two delivery targets, legacy auto-pause) is fully readable and specified below.

## Methods

ONE method. `cron.manage`, registered at `tui_gateway/methods_tools.py:1028` (`@_scoped_rpc("cron.manage", 5023)`), body `tui_gateway/methods_tools.py:1029-1052`. A repo-wide grep for `method("cron` / `_rpc("cron` over `tui_gateway/` returns exactly this one hit — there is no `cron.update`, `cron.run`, `cron.trigger`, `cron.list`.

Discriminator: `params["action"]`, default `"list"` (`methods_tools.py:1032`). The action vocabulary is FOUR names, not six:

| wire `action` | handler | maps to |
|---|---|---|
| `"list"` (default) | `methods_tools.py:1033-1041` | `cronjob(action="list", include_disabled=…)` → `_action_list` (`tools/cronjob_tools.py:597-605`) |
| `"add"` | `methods_tools.py:1042-1049` | `cronjob(action="create", …)` → `_action_create` (`tools/cronjob_tools.py:524-594`) — note the wire word is **`add`**, the tool word is `create` |
| `"remove"` | `methods_tools.py:1050-1051` | `cronjob(action="remove", job_id=…)` → `_action_remove` (`cronjob_tools.py:608-617`) |
| `"pause"` | `methods_tools.py:1050-1051` | `pause_job(job["id"], reason=None)` (`cronjob_tools.py:829`, `cron/jobs.py:2007-2017`) |
| `"resume"` | `methods_tools.py:1050-1051` | `resume_job(job["id"])` (`cronjob_tools.py:830`, `cron/jobs.py:2020-2037`) |
| anything else | `methods_tools.py:1052` | JSON-RPC error `{"code": 4016, "message": "unknown cron action: <action>"}` |

`update`, `run`, `run_now`, `trigger` exist in the underlying tool (`cronjob_tools.py:826-831`) but the gateway's `if action in {"remove", "pause", "resume"}` gate at `methods_tools.py:1050` never reaches them — they fall through to 4016. Desktop's *app-wide* Scheduled-jobs page gets editing/trigger over REST (`apps/desktop/src/api/cron.ts`), a different surface entirely; see `docs/spikes/bot-mode-audit-2026-09-09.md:692-700`.

Desktop's four call sites: list + inline legacy pause `apps/desktop/src/plugins/hermes-bots/cron.tsx:123-127,142-146`; row pause/resume/remove `cron.tsx:508-516`; create `cron.tsx:998-1026`.

## Request shape

Verbatim from `tui_gateway/methods_tools.py:1029-1052`.

COMMON, every action:
- `action: string` — see above. `methods_tools.py:1032`.
- `profile: string` (optional) — the HERMES_HOME scope. Applied by `_profile_scoped_rpc` at `methods_tools.py:42-51`: `_str_arg(params,"profile")` (trimmed; `methods_tools.py:79-80`), then `hermes_cli.profiles.get_profile_dir(profile)`; a missing dir is JSON-RPC `4064 "profile '<p>' not found"` (`methods_tools.py:46`); the override is ALWAYS reset in `finally` (`methods_tools.py:56-57`). Proven by `tests/test_cron_manage_profile_scope.py:41-58,61-76`.
- **The job reference param is `name`, for every job-bound action** (`methods_tools.py:1032`: `jid = params.get("name", "")`). There is no `job_id` param on the wire. Desktop passes the *id* in `name` (`cron.tsx:144,510`).

`action: "list"` — `methods_tools.py:1033-1041`
- `include_disabled: bool` (default `false`) — coerced by `utils.is_truthy_value` (`utils.py:24-30`, truthy strings `{"1","true","yes","on"}` at `utils.py:21`), so JSON `true`, `1` and `"true"` all work. Default EXCLUDES paused rows (`cron/jobs.py:1853-1854`), which reads as deletion in a toggle UI — Desktop always sends `true` (`cron.tsx:125`).

`action: "add"` — `methods_tools.py:1042-1049`, forwarded to `cronjob(action="create", …)`
- `name: string` — the job's display name (NOT an id). `methods_tools.py:1045`.
- `schedule: string` — required; `params.get("schedule","")`. `methods_tools.py:1045`.
- `prompt: string` — `params.get("prompt","")`. `methods_tools.py:1045`.
- `repeat: int|string` — `int(params["repeat"]) if str(params.get("repeat","")).strip().isdigit() else None` (`methods_tools.py:1046`). Digits-only; anything else (absent, `"forever"`, negative, a float) silently becomes `None` = the cronjob default.
- `continuity: bool` — `is_truthy_value(params.get("continuity")) if params.get("continuity") is not None else None` (`methods_tools.py:1047`). Explicit `false` is honoured; absent means "keep default".
- `deliver: string` — `_str_arg(params,"deliver") or None` (`methods_tools.py:1048`). Empty string == omitted.
- NOTHING ELSE reaches `cronjob()`. `skills`, `script`, `model`, `provider`, `workdir`, `monitor_*`, `no_agent`, `context_from`, `enabled_toolsets`, `attach_to_session`, `failure_deliver`, `paused`, `paused_reason`, `reasoning_effort` are all parameters of `cronjob()` (`cronjob_tools.py:854-883`) that the gateway never forwards. A created routine is therefore always active (never `paused`), always agent-mode, always default model.

`action: "remove" | "pause" | "resume"` — `methods_tools.py:1050-1051`
- `name: string` only. Forwarded as `cronjob(action=action, job_id=jid)`. Resolution is `resolve_job_ref` (`cron/jobs.py:1832-1847`): exact id first, then case-insensitive `name`; two same-named jobs raise `AmbiguousJobReference`.
- `pause` never receives a `reason` — the gateway passes none, so `paused_reason` is set to `None` on every pause (`cronjob_tools.py:829` → `cron/jobs.py:2016`).

## Response shape

Envelope: `{"jsonrpc":"2.0","id":rid,"result":{…}}` (`tui_gateway/server.py:714-715`) or `{"jsonrpc":"2.0","id":rid,"error":{"code",…,"message"}}` (`server.py:718-720`).

`list` result — `_action_list` (`tools/cronjob_tools.py:597-605`) plus `methods_tools.py:1039-1040`:
```
{ "success": true,
  "count": <int>,
  "jobs": [ <record>, … ],
  "gateway_running": true | false | null,      // only via _gateway_liveness_notice, added only when jobs is non-empty
  "warning": "<string>",                        // only when gateway_running === false
  "scoped": "<profile>" }                       // only when the request carried `profile`
```
`gateway_running` / `warning` come from `tools/cronjob_job_args.py:397-420`. `scoped` is the proof the profile scope was honoured; an older gateway that ignores `profile` omits it (`cron.tsx:80-85`).

THE ROUTINE RECORD, field by field — `_format_job`, `tools/cronjob_job_args.py:346-394`. Always present (may be JSON `null`):
- `job_id` — `str(job["id"]) or "unknown"`. `uuid.uuid4().hex[:12]`, 12 hex chars (`cron/jobs.py:1733`). Line 354.
- `name` — stored name, else first 50 chars of prompt, else first skill, else id, else `"cron job"`. Line 355.
- `skill` — first canonical skill or `null`. Line 356.
- `skills` — `string[]`, deduped, order kept. Line 357.
- `prompt_preview` — prompt truncated at 100 chars with a literal `"..."` suffix. Line 358. **This is the only prompt text the list carries; there is no full `prompt` field.**
- `model`, `provider`, `base_url` — nullable strings. Lines 359-361.
- `schedule` — **a plain string**, `job["schedule_display"]` or `"?"`. Line 362. Never the structured dict.
- `repeat` — **a display string**, not a number: `"forever"` | `"once"` | `"1/1"` | `"<completed>/<times>"` | `"<times> times"` (`_repeat_display`, `cronjob_job_args.py:136-143`). Line 363.
- `deliver` — string, default `"local"`. Line 364.
- `next_run_at` — ISO-8601, nullable. Line 365.
- `last_run_at` — ISO-8601, nullable. Line 366.
- `last_status` — closed set `"ok" | "error" | "delivery_failed" | "blocked_config"` or null (`cron/jobs.py:2220-2221`, `cron/scheduler.py:1444`). Line 367.
- `last_delivery_error` — string|null. Line 368.
- `last_delivery_unverified` — target acked without a message id. Line 369.
- `last_fire_error` — string|null. Line 370.
- `last_error` — string|null, passed through `agent.redact.redact_sensitive_text(force=True, redact_url_credentials=True)`. Lines 371-373.
- `enabled` — bool, default true. Line 374.
- `state` — `effective_job_state(job)` (`cron/jobs.py:488-501`): `"scheduled" | "paused" | "completed" | "error"`. `enabled: true` is authoritative and never renders as paused. Line 376.
- `paused_at` — ISO|null. Line 377.
- `paused_reason` — string|null. Line 378.

Present ONLY when truthy (`_FORMAT_JOB_OPTIONAL_KEYS`, `cronjob_job_args.py:341-343,380-382`): `script`, `reasoning_effort`, `monitor_script`, `monitor_url`, `monitor_state`, `no_agent` (coerced to literal `true`), `enabled_toolsets`, `workdir`.
Present conditionally: `continuity: true` when `context_from` contains `"self"` or the job's own id (lines 383-388) — **the key is absent, never `false`**; `context_from: string[]` of the non-self refs when any (389-391); `attach_to_session: bool` only when the stored value is a bool (392-393).
NOT present: `latest_execution` (attached by `list_jobs` at `cron/jobs.py:1861-1862` but dropped by `_format_job`), `prompt`, `origin`, `failure_streak`, `fire_claim`, `created_at`, `provider_snapshot`, `model_snapshot`.

`add` result — `cronjob_tools.py:588-594`:
```
{ "success": true, "job_id", "name", "skill", "skills", "schedule",
  "repeat", "deliver", "next_run_at",
  "job": <full record above>,
  "message": "Cron job '<name>' created." [+ local-delivery notice],
  "gateway_running": …, "warning": …,
  "guidance": [ "<string>", … ] }        // only when _mode_guidance_notes is non-empty
```
`pause` / `resume` result — `_job_state_result` (`cronjob_tools.py:620-622`): `{"success": true, "job": <full record>}`.
`remove` result — `cronjob_tools.py:613-617`: `{"success": true, "message": "Cron job '<name>' removed.", "removed_job": {"id","name","schedule"}}`.

Desktop's read of this is `RoutineJob` at `apps/desktop/src/plugins/hermes-bots/types.ts:250-269` — a deliberately narrower 18-field subset; it does not model `continuity`, `skills`, `count` or the liveness fields.

## Semantics

SCHEDULE REPRESENTATION. The client sends ONE opaque string; the server parses. `parse_schedule` (`cron/jobs.py:733-804`) produces one of three stored kinds, and only its `display` string ever comes back:
- `{"kind":"cron","expr":<5-6 field expr>,"display":<original>}` — from a 5+ field cron expression (`cron/jobs.py:757-760`, letters allowed so `MON-FRI`/`JAN` reach croniter) or from a natural day/time phrase (`_natural_every_to_cron`, `cron/jobs.py:680-713`; `"every monday 9am"`, `"weekdays at 9am"`, `"every day at 9am"`, comma/`and` weekday lists). Cron DOW numbering is 0=Sunday (`cron/jobs.py:636-645`). croniter is required; missing it raises (`cron/jobs.py:716-726`).
- `{"kind":"interval","minutes":N,"display":"every Nm"}` — `"every 2h"`, and **a bare duration `"30m"` is also a RECURRING interval, not a one-shot** (`cron/jobs.py:752-753,794-795`, and the comment at 783-784).
- `{"kind":"once","run_at":<ISO>,"display":"once at …"|"once in …"}` — an ISO timestamp (`cron/jobs.py:763-779`) or the explicit `"in 30m"` / `"in 2h"` form (`cron/jobs.py:785-793`).
An unparseable string raises with the five-form help text at `cron/jobs.py:797-804`.

TIMEZONE — resolved SERVER-SIDE, entirely. There is no timezone parameter anywhere on `cron.manage`. `hermes_time.now()` (`hermes_time.py:90-93`) resolves `HERMES_TIMEZONE` env → `timezone` in the profile's `config.yaml` → server-local, cached per HERMES_HOME identity (`hermes_time.py:29-31,64-81`). A naive ISO timestamp is made aware in that configured zone at parse time, deliberately not in the server's system zone (`cron/jobs.py:773-774` and the comment at 766-772). The client's own device timezone is never consulted and must never be sent. A returned `next_run_at` is an aware ISO string carrying a numeric UTC offset (`cron/jobs.py:1110,1122`), so it renders in the phone's zone only if the client converts.

DELIVERY TARGETS — the two the slice means (`cron.tsx:1077-1089`, the `Send results to` select):
1. `history` → **the `deliver` param is OMITTED entirely** (`cron.tsx:1021-1025`). Server side: `_str_arg(...) or None` → `create_job(deliver=None, origin=_origin_from_env())`; a gateway RPC session sets no `HERMES_SESSION_PLATFORM`/`CHAT_ID` so origin is `None` (`cronjob_job_args.py:13-18`) and `deliver` defaults to the literal `"local"` (`cron/jobs.py:1731-1732`). Output is saved to run history and injected into no chat. UI label `Run history only` (`i18n.ts:457`).
2. `deliver: "bot-chat"` — bare, no `:profile` suffix. The result is injected into that bot's canonical Bot Chat as a real message; the bot reads it, acts, and answers there, costing one agent turn per run (`cron.tsx:1018-1025`). Bare is deliberate: the job lives in the bot's own profile store, so the scheduler resolves the token locally and no cross-gateway name ambiguity exists. Validated at CREATE time, not fire time — `_validate_bot_chat_deliver` (`cronjob_job_args.py:183-208`) fails the create if the named profile is absent on the gateway's machine. UI label `<bot>'s chat (bot responds)` (`i18n.ts:458`).
The wider `deliver` grammar (`local`, `all`, `platform:chat_id:thread_id`, comma-combined) is reachable through the same param (`CRONJOB_SCHEMA` description, `cronjob_tools.py:942-945`) but Desktop's tile exposes only these two.

CONTINUITY. Wire: `continuity: true` on `add` (`methods_tools.py:1047`, `cron.tsx:1013-1017`). It is not a stored field — the server folds it into `context_from` by appending the literal ref `"self"` (`_apply_continuity`, `cronjob_job_args.py:313-323`; `cronjob_tools.py:559-561`). At run time `"self"` resolves to the job's own id, so each run is given its previous run's output. It is read back as a DERIVED boolean: `_format_job` sets `continuity: true` when `context_from` holds `"self"` or the job's own id, and drops non-self refs into a separate `context_from` array (`cronjob_job_args.py:383-391`). Copy: `Continuity: each run sees the previous run's output (dedupe, continue where it left off)` (`i18n.ts:459`).

BOT SCOPING, two mechanisms belt-and-braces. (a) `profile` scopes the store; `scoped` in the reply proves it landed. (b) The job name is prefixed `[bot:<name>] ` (`BOT_TAG_RE`, `cron.tsx:73`; written at `cron.tsx:1000`; stripped for display by `routineTitle`, `cron.tsx:93-95`). `selectRoutineJobs` (`cron.tsx:215-230`) shows everything when `scoped` matches the bot case-insensitively, and otherwise falls back to filtering on the `[bot:]` tag with untagged jobs attributed to `default`.

PROMPT WRAPPING. `routinePrompt` (`cron.tsx:270-286`): when the target bot IS the active profile the instruction is stored verbatim; otherwise the stored prompt is `'[bot-mode:routine:v2] ' + 'You are running the scheduled routine "<title>" for agent \'<bot>\'. …' + a `hermes -p <bot> chat -c … -q …` shell line, shell-quoted by `shellQuote` (`cron.tsx:254-256`). Note the server's own threat scanner runs on this prompt at create (`_scan_cron_prompt`, `tools/cronjob_prompt_scan.py:27-35`).

REFRESH. Desktop polls: `refetchInterval: 20000, staleTime: 8000` (`cron.tsx:186-188`). The `cron.changed` global event is NOT a substitute — the watcher stats only the watcher's own active home's `cron/jobs.json` (`tui_gateway/change_watcher.py:179` with `_home_mtime_ns`/`_watcher_home` at `:47-48,33-36`), so a non-launch bot profile's store never fires it. Its payload is `{}`.

## Gotchas

1. **A rejected create is a JSON-RPC SUCCESS.** `_action_create`'s failures come back through `tool_error` (`tools/registry.py:935-938`) as a JSON string that the gateway parses and returns via `_ok` (`methods_tools.py:1049`). The result body is `{"error": "<bounded message>", "success": false}` — HTTP 200, `result` member, no `error` member. Same for a not-found pause/resume/remove (`cronjob_tools.py:847-850`) and an ambiguous name (`cronjob_tools.py:838-846`, which adds `matches: [{id,name,schedule,next_run_at}]`). **`PluginHostResult.Success` is not proof the mutation landed; you MUST read `result.success`.** Desktop gets this wrong — `requestForBot` (`routing.ts:200-227`) resolves on any `result` and never inspects `success`, so its create dialog toasts `Cron created` over a rejection and its legacy overlay marks a job paused that the gateway refused. Decide deliberately and ledger it.
2. Real JSON-RPC `error` members are only: `4016` unknown cron action (`methods_tools.py:1052`), `4064` profile not found (`methods_tools.py:46`), `5023` a body exception (`methods_tools.py:55`), `-32601` method absent. So `PluginHostResult.Refused(4016)` means you sent `update`/`trigger` — a client bug, not a gateway generation difference.
3. **There is no `update` and no `trigger`.** `cronjob_tools.py:826-831` has them; `methods_tools.py:1050` gates them out. Do not build an edit path.
4. **The wire word is `add`, not `create`** (`methods_tools.py:1042`). And **the job reference param is `name`, not `job_id`**, for pause/resume/remove too (`methods_tools.py:1032`) — while the record's field is `job_id` (`cronjob_job_args.py:354`). Getting these crossed is the cheapest way to fail this slice.
5. `repeat` is digits-only on the wire: `str(...).strip().isdigit()` (`methods_tools.py:1046`). `"forever"`, `-1`, `2.0`, `" 2 "`-with-sign all silently become the default instead of erroring. And `repeat` comes BACK as a display string (`"forever"`, `"3 times"`, `"1/3"`), never a number — `RoutineJob.repeat` is typed `number | string` (`types.ts:265`) for exactly that reason. Do not round-trip it.
6. `"30m"` is a RECURRING interval. Desktop's `once` frequency composes exactly that bare form (`composeSchedule`, `cron.tsx:658-662`) and then `scheduleLabel` re-labels `/^(\d+)([mhd])$/` as `Once (…)` (`cron.tsx:296-300`). **The picker's "Once, in…" therefore emits a string the server parses as a forever-repeating interval.** Only `"in 30m"` is a real one-shot (`cron/jobs.py:785-793`). Port `composeSchedule` byte-for-byte to preserve parity, and record the mismatch as a divergence rather than "fixing" it silently.
7. `next_run_at` is ISO-8601 with a **numeric offset**, not `Z` — `Instant.parse` throws; use `OffsetDateTime.parse`. Worse, a one-shot's `next_run_at` is the stored `run_at` returned verbatim (`cron/jobs.py:841-852`), and a legacy hand-edited record can hold a NAIVE timestamp with no offset at all (`_ensure_aware` at `cron/jobs.py:807-814` normalises for comparison but never rewrites storage). Parse defensively; a bad timestamp must drop the row, as Desktop does (`routineTimestamp`, `cron.tsx:328-332`).
8. `continuity` and `no_agent` come back only when TRUE (`cronjob_job_args.py:380-382,387-388`). Absent ≠ false-with-a-value; a Kotlin `Boolean` default of `false` is right, but do not send `continuity: false` back expecting a no-op — it actively STRIPS `"self"` from `context_from` (`_apply_continuity`, `cronjob_job_args.py:321-322`), and there is no update action to send it through anyway.
9. `include_disabled` defaults FALSE and the default hides every paused job (`cron/jobs.py:1853-1854`) — a pause would look like a delete. Always send `true` (`cron.tsx:125`).
10. **pause/resume are idempotent in outcome but NOT side-effect-free.** A redundant `pause` rewrites `paused_at` to now and clobbers `paused_reason` to `null` (`cron/jobs.py:2012-2017`, and the gateway never sends a reason). A redundant `resume` recomputes `next_run_at` from now (`cron/jobs.py:2025,2036`) — **so a stray resume on an already-active interval job pushes its next fire out by a full period.** Only send the transition you actually need; the optimistic overlay must not re-fire on a poll that already agrees.
11. Two resume paths return an in-band error, never an exception the transport shows: a one-shot whose `run_at` is past its grace window (`cron/jobs.py:2026-2030`), and a terminal `completed`/`error` job — `_reject_terminal_activation` raises `Cannot activate terminal cron job '<name>' through update_job; use cron resume --run-now or --at.` (`cron/jobs.py:1866-1879`), CLI-only advice this app cannot offer. Your rollback must cover both, and your copy must not repeat that sentence.
12. Partial-failure create: `CronSchedulerRegistrationError` (`cron/scheduler.py:3297-3325`) returns `{"error", "job_id", "job_saved": true, "scheduler_registered": false, "retry_create": false, "success": false}` (`cronjob_tools.py:580-582`). **The job IS saved.** Retrying the create makes a duplicate. Surface "saved but not scheduled yet", never "failed".
13. `cron.changed` is scoped to the gateway's own home, not to a bot profile (gotcha 3 in "semantics"). The tile must poll.
14. Every string the gateway returns (`error`, `warning`, `message`, `guidance`, `paused_reason`, `last_*_error`) is backend prose — this repo's copy policy and `PluginHostResult.Refused`'s own contract (`PluginHost.kt:99-105`: "[safeMessage] is this app's own sentence — never text the backend wrote") mean none of it may be painted raw. `routineDetailIssue` (`cron.tsx:402-407`) shows `last_fire_error || last_delivery_error || paused_reason` verbatim; matching that literally would violate the invariant. Ledger it as a mobile adaptation and map to this app's own sentences.
15. `_format_job` redacts only `last_error` (`cronjob_job_args.py:371-373`). `last_fire_error`, `last_delivery_error` and `prompt_preview` are UNREDACTED — `prompt_preview` on a delegated routine literally contains a `hermes -p <bot> chat -q …` shell line. Treat these as untrusted content, and keep them out of screenshots and parity captures.
16. `routineInputError` (`cron.tsx:258-268`) rejects a NUL in the name or instruction before any RPC — matches this repo's own no-NUL rule, keep it.
17. **The pin moved and the slice's own line numbers are stale.** Issue #191 and the audit cite core `en.ts:2172-2262` and pin `72a3277cd7`; at `564aef2946` the core `cron` block is `apps/desktop/src/i18n/en.ts:2331-2476` and the plugin block is `apps/desktop/src/plugins/hermes-bots/i18n.ts:446-484`. `methods_tools.py:1027` is now `:1028`. Re-read, do not copy.

## What this app must build

The Android app must build the Routines surface from scratch — there is no cron code in `app/src/main/kotlin/` at all (the only hits are `RestTranscriptProjection.kt:222` and `ToolView.kt:318`, which merely map the `cronjob` TOOL name for transcript rendering, and `ConnectionsCopy.kt:15,53`, where product copy currently *admits* cron is something this app does not ship — that copy has to change).

What exists and is reusable, unchanged:
- `app/src/main/kotlin/com/hermesagent/mobile/plugins/PluginHost.kt:29-43,84-106,153-196` — the generic JSON-RPC door. `cron.manage` passes `normalizePluginHostMethod` (`:117-127`) as-is. `-32601` → `UnavailableOnGateway`, any other code → `Refused(code, REFUSED_MESSAGE)`. No allow-list to extend.
- `app/src/main/kotlin/com/hermesagent/mobile/plugins/bots/BotsPlugin.kt:69-111` — the contribution site. The tile is a third `PluginContribution`, or (better on a phone) a destination reached from a roster row: `BotsRosterScreen.kt` currently has NO per-bot click target, so slice #191 also owes the roster row's `onClick` and a bot-detail destination.
- `app/src/main/kotlin/com/hermesagent/mobile/plugins/bots/BotsPluginRepository.kt:32-56` — the repository shape to copy: `host.request(...)` → `when (result) { Success -> parse…, UnavailableOnGateway -> …, Refused -> … }`, with a parse failure answering a sealed `Refused(<this app's sentence>)` rather than throwing. Extend this class (or add `RoutinesRepository` beside it) with `list/add/pause/resume/remove`.
- `app/src/main/kotlin/com/hermesagent/mobile/plugins/bots/BotsRosterModel.kt:43-64,126-155` — the `BotRosterRow` that supplies the `profile` param (`row.name`), and the `BotsRosterCopy` object pattern for product copy with `path:line` provenance comments.
- `app/src/main/kotlin/com/hermesagent/mobile/data/gateway/GatewayGlobalEvents.kt:69,162-163` — `cron.changed` is ALREADY in the global allow-list and already publishes `GatewayChangeHintKind.Cron`. Nothing subscribes. Wire the tile to it as a nudge, but keep a 20s poll: the event only fires for the gateway's launch profile.
- `app/src/main/kotlin/com/hermesagent/mobile/ui/common/SettingsPrimitives.kt:93,103,120` — `WIP_PILL` / `WIP_SPOKEN` / `WipPill`, which is what "full editing" and "trigger now" ship behind.

What must be newly built:
1. A `RoutineJob` model over the 18 fields Desktop reads (`types.ts:250-269`), plus a tolerant parser: nullable everything except `job_id`, `repeat` as a String (never Int), `next_run_at`/`last_run_at` via `OffsetDateTime.parse` with a naive-ISO fallback.
2. A `success`-aware result type. Because a rejection arrives inside `PluginHostResult.Success`, the repository needs a three-way `Loaded / Rejected(reason) / Refused(safeMessage)` split, mapping the backend's `error` string to this app's own sentence.
3. `composeSchedule` + `scheduleLabel` + `scheduleSummary` (`cron.tsx:654-687,288-324,689-727`) as pure Kotlin — the slice's acceptance evidence is a fixed-clock/fixed-timezone/fixed-locale round-trip over all eight frequencies, so they must be free of `Context` and of `Locale.getDefault()`.
4. The client-side legacy sweep (`BOT_TAG_RE`, the v2 marker, `isLegacyDelegatedRoutine`, the swallow-per-pause `Promise.all` and the paused-id overlay) as a coroutine `awaitAll` with per-item `runCatching`, testable on virtual time.
5. Optimistic pause/resume with the `pendingActive` overlay and its "server caught up" reconciliation (`cron.tsx:487-494,496-524`), plus rollback on both `Refused` and in-band `success: false`.
6. Product copy: reuse the Desktop strings verbatim (see the i18n quotes), but replace the four backend-prose surfaces (issue banner, stale notice on error, create error, legacy notice) with this app's sentences per the secrets/copy invariant.
7. `docs/parity/bot-routines.md` with a `## Visual report` and `## Divergences`, classifying at minimum: the tile→sheet adaptation, the read-only inspector, the disabled edit/trigger controls behind `WIP`, the `success: false` handling divergence (gotcha 1), the backend-prose substitution (gotcha 14), and the `Once` frequency emitting a recurring interval (gotcha 6).

VERBATIM COPY, at the pin.
Plugin block `apps/desktop/src/plugins/hermes-bots/i18n.ts:446-484`:
- `:447-448` `filterHint`: `'Scheduled jobs exist in this profile but none are tagged for this bot. Name a job "[bot:<name>] …" to show it here, or see them in Cron below.'`
- `:449` `needsRosterFirst`: `'This bot has to appear in the roster first.'`
- `:450` `staleNotice`: `'Could not refresh scheduled jobs. Showing the last list we had.'`
- `:451` `readFailure`: `'The list may still be there — this was a read failure, not a delete.'`
- `:452` `createDesc`: `` bot => `A recurring task ${bot} runs on a schedule. Runs land in its own chat history.` ``
- `:453` `instruction`: `'Instruction'` · `:454` `whenToRun`: `'When to run'` · `:455` `dayOfMonth`: `'Day of month'` · `:456` `sendResultsTo`: `'Send results to'`
- `:457` `runHistoryOnly`: `'Run history only'` · `:458` `botChatTarget`: `` bot => `${bot}’s chat (bot responds)` ``
- `:459` `continuity`: `'Continuity: each run sees the previous run’s output (dedupe, continue where it left off)'`
- `:460` `onceIn`: `` when => `Once (${when})` `` · `:461` `everyNDays` · `:462` `everyNHours` · `:463` `everyNMinutes`
- `:464-471` `freqOnce: 'Once, in…'`, `freqHourly: 'Every hour'`, `freqDaily: 'Every day'`, `freqWeekdays: 'Weekdays'`, `freqWeekly: 'Every week'`, `freqMonthly: 'Every month'`, `freqInterval: 'Interval'`, `freqAdvanced: 'Advanced…'` — **this is the picker's menu ORDER**
- `:472-474` `unitMinutes: 'minute(s)'`, `unitHours: 'hour(s)'`, `unitDays: 'day(s)'`
- `:475-483` `runsOnce: (count, unit) => 'Runs once, ${count} ${unit} from now'`, `runsHourly: 'Runs at the top of every hour'`, `runsDaily: time => 'Runs every day at ${time}'`, `runsWeekdays: time => 'Runs Monday–Friday at ${time}'`, `runsWeekly: (day, time) => 'Runs every ${day} at ${time}'`, `runsMonthly: (day, time) => 'Runs on day ${day} of each month at ${time}'`, `runsInterval: (count, unit) => 'Runs every ${count} ${unit}'`, `runsRaw: 'Raw schedule — every Nm/Nh/Nd or 5-field cron'`, `timesTotal: count => ', ${count} time(s) total'`

Core block `apps/desktop/src/i18n/en.ts` — `:2333` `title: 'Scheduled jobs'`; `:2353` `states.paused: 'paused'` (lowercase, used as the row's right-hand text); `:2366` `scheduleLabels.daily: 'Daily'`; `:2370` `scheduleLabels.hourly: 'Hourly'`; `:2383-2392` `days['0'…'7']` = `'Sunday'…'Sunday'`; `:2400` `newCron: 'New cron'`; `:2401-2402` `emptyDescNew: 'Schedule a prompt to run on a cron expression. Hermes will run it and deliver results to the destination you pick.'`; `:2404` `emptyTitleNew: 'No scheduled jobs yet'`; `:2407` `next: 'Next:'`; `:2409` `manage: 'Manage'`; `:2428` `created: 'Cron created'`; `:2430` `failedLoad: 'Failed to load cron jobs'`; `:2431` `failedUpdate: 'Failed to update cron job'`; `:2436` `createTitle: 'New cron job'`; `:2439` `nameLabel: 'Name'`; `:2440` `namePlaceholder: 'Morning briefing'`; `:2441` `promptLabel: 'Prompt'`; `:2442` `promptPlaceholder: 'Summarize my unread Slack threads and email me the top 5...'`; `:2457` `createAction: 'Create cron'`. Shared: `:44` `saving: 'Saving…'`, `:45` `cancel: 'Cancel'`, `:49` `close: 'Close'`, `:58` `delete: 'Delete'`, `:71` `retry: 'Retry'`.

HARDCODED English in `cron.tsx` (NOT in any i18n table — a port must copy these literally or ledger the change): `:94` `'Untitled job'`; `:260` `'Job name cannot contain NUL (U+0000).'`; `:264` `'Job instruction cannot contain NUL (U+0000).'`; `:348,351,354,357` `'Succeeded'` / `'Failed'` / `'Ran, but delivery failed'` / `'Blocked by configuration (not run)'`; `:378-389` detail-row labels in ORDER — `Status` (`Paused`|`Active`), `Schedule`, `Schedule (raw)`, `Repeat`, `Next run`, `Last run`, `Last result`, `Delivers to`, `Model`, `Working directory`, each row dropped when its value is absent; `:437` `'What this job runs, and when it runs next.'`; `:584` `'Paused for security: delete and recreate this legacy job before running it again.'`; `:809,813,817` `'minutes from now'`/`'hours from now'`/`'days from now'`; `:868,873,878` `'minutes'`/`'hours'`/`'days'`; `:891` placeholder `'every 1d · every 2h · 0 9 * * * (cron)'`; `:897,908` `'Stop after'` … `'runs (blank = forever)'` with placeholder `'∞'` (`:905`).

The six pane states the Robolectric journey must cover (`cron.tsx:1224-1309`): no-owner (`PanelEmpty` icon `hubot`), loading spinner, read-failure + Retry (icon `warning`), empty with New-cron action (icon `watch`), the filter-hint variant of empty, and the populated list — plus the `staleNotice` banner (`:1268-1272`) which overlays the populated state.

## What could not be established

1. **Live behaviour of a bot-chat delivery.** I read `_validate_bot_chat_deliver` (`cronjob_job_args.py:183-208`) and the create path, but not `cron/scheduler_delivery.py::parse_bot_chat_deliver_token` or `cron/scheduler.py::_resolve_delivery_targets`. So I cannot state what a `bot-chat` delivery *looks like* to a client watching the target session — whether it arrives as an ordinary user message on the session event stream, or as something the Android transcript would need a new branch for. That answer lives in `cron/scheduler_delivery.py` and `gateway/relay.py` @ the pin. It does not block #191 (the tile only *creates* such a job) but it does block any claim that Android renders the result correctly.

2. **`skills.reload`-style event after a mutation.** I confirmed `cron.changed` fires off `cron/jobs.json` mtime for the watcher's own home only. I did NOT trace whether a profile-scoped `cron.manage` mutation temporarily moves the watcher's `_watcher_home()` (the override is set and reset inside the handler at `methods_tools.py:47,57`, and the watcher runs on its own tick), so there is a small chance a scoped mutation racing a watcher tick emits a spurious or a suppressed `cron.changed`. Determining that needs the watcher's thread/tick wiring in `tui_gateway/change_watcher.py:196-230` and its call site — unread.

3. **Whether Desktop's `once`-emits-an-interval mismatch (gotcha 6) is intended.** `composeSchedule` (`cron.tsx:658-662`) emits `"30m"` and `scheduleLabel` (`cron.tsx:296-300`) re-labels it `Once (30m)`, while `parse_schedule` (`cron/jobs.py:794-795`) stores it as a recurring interval. I found no test or comment reconciling the two; `cron-schedule*.test.ts` was not read. If one exists it would be under `apps/desktop/src/plugins/hermes-bots/` @ the pin. I am reporting it as a factual mismatch, not as a settled bug.

4. **The full non-English locales.** I quoted only `en`. `i18n.ts:663,876,1089` hold `ja`/`zh-CN`/`zh-TW` cron blocks. Android ships English only today, so this is noted rather than a gap.

5. **`hermes_cli.cron._builtin_gateway_liveness`.** I read its adapter (`cronjob_job_args.py:397-420`) and so know the tri-state and the two warning strings, but not what makes the probe return `None` versus `False`. If the tile wants to render "the scheduler is not running", the exact liveness definition is in `hermes_cli/cron.py` @ the pin — unread.


---

# #193 — Group chats, participating

**Verdict:** UNBLOCKED — with a scope correction. The `ui_meta` write contract is fully established from the applying code path (not docstrings), including CAS enforcement, conflict shape, key preservation and atomicity. Separately: #193's own body ("CAS writes back to `hermes-bots-groups`") is STALE against this repo's own accepted `docs/adr/0004-hosted-rooms-for-group-chats.md:25,28`, which says Android's group chats use `groups.*` and "never read or write `hermes-bots-groups`". I re-verified the ADR's premise at the NEW pin: no file under `apps/desktop/src/**` calls any `groups.*` method at 564aef2946 (grep-negative), so Desktop is still blob-only and ADR 0004 still holds. The contract below is therefore what #193 needs for any *per-bot* `ui_meta['hermes-bots']` write (hide / pin / rename), and is the fallback spec if the ADR is ever reversed.

## Methods

All upstream paths @ `564aef2946c436500a5e80ee117b66b789b3f99a`.

WRITE
- `profiles.configure` handler + error code 5064 — `tui_gateway/methods_profiles.py:563-586`
- the ui_meta gate (only fires when `params["ui_meta"]` is a JSON object) — `:572-573`
- the applying code path — `_configure_ui_meta`, `tui_gateway/methods_profiles.py:436-479`
- profile resolution / errors 4063 (name required), 4064 (not found) — `:69-78`
- the serializing lock — `tui_gateway/server.py:96-98` (`_profile_ui_meta_lock = threading.Lock()`)
- the durable write — `utils.py:262-275` (`atomic_yaml_write`) → `utils.py:177-199` (`_atomic_write`: mkstemp + fsync + atomic_replace)

READ (the only read path; there is no `ui_meta` in `profiles.describe`)
- `profiles.list` handler + code 5061 — `tui_gateway/methods_profiles.py:237-254`
- `_profile_ui_meta_fields` — `:224-234`
- `_clean_revisions` — `:91-93`
- `_read_profile_yaml` — `:81-88`

ENVELOPE / DISPATCH
- `_ok` / `_err` — `tui_gateway/server.py:714-720`; unknown method `-32601` — `tui_gateway/server.py:749`
- `profiles.configure` and `profiles.list` are both in `_LONG_HANDLERS` → dispatched on an 8-worker thread pool, i.e. genuinely concurrent — `tui_gateway/server.py:162-177,179-180`
- control-plane note — `docs/design/multiplexing-gateway.md:158-165`

REFERENCE CLIENT (Desktop, the only CAS participant at the pin)
- group projection key + budgets — `apps/desktop/src/plugins/hermes-bots/group-chat.ts:47,50-53`
- the read/feature-detect — `:839-853`
- the pull-merge-write flush with CAS — `:938-1080` (params built `:995-1010`, success assertions `:1016-1031`)
- the per-bot `hermes-bots` write, which uses NO CAS — `apps/desktop/src/plugins/hermes-bots/data.ts:305-364`

TEST THAT PINS THE BEHAVIOUR
- `tests/tui_gateway/test_profiles_ui_meta_cas.py:40-63` (advance + stale reject), `:66-81` (revision survives deletion), `:84-98` (two concurrent writers, same expected revision)

## Request shape

`profiles.configure` params, verbatim from `tui_gateway/methods_profiles.py:563-586`:

```
{
  "name": <str>,                            // required; :568 -> :69-78
  "ui_meta": <object>,                      // :572 — must be a JSON object or the whole ui_meta section is skipped silently
  "ui_meta_expected_revisions": <object>,   // :445-447 — optional; per-key int map; non-object raises (and is swallowed)
  "soul": <str>, "description": <str>,
  "model": <str>, "provider": <str>, "confirm_expensive_model": <truthy>,
  "disabled_skills": [<str>], "enabled_toolsets": [<str>], "enabled_mcp_servers": [<str>]
}
```

`ui_meta` value semantics (`:463-467`):
- `{"<key>": <any JSON value>}` — set/replace that top-level key.
- `{"<key>": null}` — DELETE that key (`if value is None: current.pop(key, None)`).

`ui_meta_expected_revisions` (`:453-456`): `{"<key>": <non-negative, non-bool int>}`. The check iterates the keys of `incoming`, not of `expected` — so an expected entry for a key you did not send is ignored, and a key you DID send with no expected entry is a guaranteed conflict (`wanted` is `None`, fails `isinstance(wanted, int)`).

Size cap (`:443-444`): `len(json.dumps(incoming)) > 65536` → return, nothing written. `json.dumps` defaults to `ensure_ascii=True` and separators `', '`/`': '`, so non-ASCII inflates to `\uXXXX` (6 bytes; 12 for astral pairs) and every `,`/`:` costs one extra byte versus JS `JSON.stringify`. Desktop's estimator encodes exactly this — `group-chat.ts:92-115` — and budgets 48000 (`:50`).

READ side (`profiles.list`), params: `{"include_sessions": <bool, default true>}` — `:239-242`. Desktop sends `false` for sync reads (`group-chat.ts:840-842`) because `true` walks each profile's skill tree and opens each `state.db` (`:208-221`).

## Response shape

SUCCESS ENVELOPE always — a CAS conflict is NOT a JSON-RPC error. `_ok` at `tui_gateway/server.py:714-715`.

`profiles.configure` result (`tui_gateway/methods_profiles.py:584-586`):
```
{"ok": <bool>, "applied": {...}
 [, "confirm_required": true, "confirm_message": <str>]}      // model guard only, :580,585-586
```
`ok` = `all(applied.values())` when `applied` is non-empty, else `true`. It is section-agnostic: a `model`/`skills` failure also drives it false, so never use `ok` to detect a ui_meta conflict.

`applied` on ui_meta SUCCESS (`:476-477`):
```
{"ui_meta": true,
 "ui_meta_revisions": {"<key>": <new int>, ...}}   // ONLY the keys you sent; each = old+1
```
`applied` on ui_meta CAS CONFLICT (`:457-460`) — nothing was written, for ANY key:
```
{"ui_meta": false,
 "ui_meta_conflicts": {"<key>": {"expected": <what you sent, may be null>, "actual": <int>}, ...},
 "ui_meta_revisions": {"<key>": <current int>, ...}}   // every key you sent, at its current value
```
`applied` on ui_meta NON-CAS FAILURE (`:440,443-444,478-479`) — size cap, bad `ui_meta_expected_revisions` type, YAML write error:
```
{"ui_meta": false}      // no "ui_meta_conflicts", no "ui_meta_revisions"
```
That absence-of-`ui_meta_conflicts` is the ONLY discriminator between "retry with a fresh revision" and "your payload/host is broken; retrying will not help".

If `params["ui_meta"]` is not a dict, `applied` carries no `ui_meta` key at all (`:572-573`) and `ok` can be `true` — a silently ignored write.

`profiles.list` row (`:224-234,245-250`):
```
{"name","path","is_default","model","provider","description","display_name","skill_count",
 "ui_meta_revisions": {<key>: <int>},   // ALWAYS present (even {}) — :230; this is the CAS feature-detect
 "ui_meta": {<key>: <value>},           // present ONLY when the stored map is a non-empty dict — :231-232
 "has_avatar": <bool>}                  // :234
```
plus `last_session`/`worker_session`/`canonical_session` when `include_sessions`. Envelope also carries `"bot_mode_protocol": true` (`:254`). Note the asymmetry: the server key on disk is `_ui_meta_revisions`; the wire key is `ui_meta_revisions`.

## Semantics

MERGE, at exactly ONE level. `tui_gateway/methods_profiles.py:461-467`: the handler loads the existing `ui_meta` dict and does `current[key] = value` per incoming top-level key. There is no recursion, no `dict.update` into a nested map, no schema. So:
- keys of `ui_meta` you did not send: PRESERVED byte-for-byte (they are never touched).
- the VALUE of a key you did send: REPLACED WHOLESALE. Anything nested inside it that you did not resend is GONE. Deep merge does not exist anywhere in this path.
- `null` value = delete the key (`:464-465`). If `current` ends up empty the whole `ui_meta` mapping is popped from profile.yaml (`:469-472`).

CAS: SERVER-SIDE ENFORCED, but OPT-IN PER REQUEST. `expected = params.get("ui_meta_expected_revisions")` (`:445`); the comparison loop at `:453` iterates `incoming if isinstance(expected, dict) else ()` — omit the param and the loop body never runs, the write lands unconditionally, and the revision still increments. So: enforced when you participate, silent last-writer-wins when you don't. It is not advisory when present: `:457-460` returns before any mutation.

ALL-OR-NOTHING ACROSS KEYS. One mismatched key in a multi-key `ui_meta` rejects the entire request (`:457` `if conflicts: ... return`). Corollary: never batch an independent key (`hermes-bots`, `color`) into a CAS'd group write.

REVISIONS ARE PER-KEY AND MONOTONIC, AND SURVIVE DELETION. `revisions[key] = revisions.get(key, 0) + 1` (`:468`) runs for every incoming key including deletes; the map is written back as `_ui_meta_revisions` (`:473`) even when `ui_meta` itself was popped. A stale client cannot resurrect a deleted key — proven at `tests/tui_gateway/test_profiles_ui_meta_cas.py:66-81`. A key never written has revision 0; a first write must send `{"<key>": 0}` to participate.

CONFLICT DELIVERY. Not an error code, not a rejected transport frame, not silent LWW: a `result` with `applied.ui_meta === false` plus `applied.ui_meta_conflicts` naming each key's `expected` vs `actual`, and `applied.ui_meta_revisions` handing you the current revisions so the retry needs no extra read (though Desktop re-reads anyway).

ATOMICITY. Read → compare → merge → write is one critical section under `_profile_ui_meta_lock` (`:448`; `tui_gateway/server.py:96-98`), and the write itself is `atomic_yaml_write` (temp file + fchmod + fsync + `atomic_replace`, `utils.py:262-275,177-199`), so no torn profile.yaml. Since `profiles.configure` is a `_LONG_HANDLERS` member dispatched onto an 8-worker pool (`server.py:162-177,179-180`), the lock is doing real work, not theatre.

TWO CLIENTS AT THE SAME REVISION. Exactly one wins. Winner: `applied.ui_meta true`, `ui_meta_revisions {key: N+1}`. Loser: `applied.ui_meta false`, `ui_meta_conflicts {key: {expected: N, actual: N+1}}`, and NOTHING of its payload is persisted. Pinned by `tests/tui_gateway/test_profiles_ui_meta_cas.py:84-98` with a real `ThreadPoolExecutor`.

THE `hermes-bots-groups` BLOB. Lives at `ui_meta["hermes-bots-groups"]` on the `default` profile ONLY — Desktop hardcodes `name: 'default'` (`apps/desktop/src/plugins/hermes-bots/group-chat.ts:1000-1003`) and reads back the row where `row.name === 'default'` (`:844`). Shape is the v3 envelope `GroupChatSyncSnapshot` (`:67-74`):
```
{version: 3, updatedAt: <ms>, rooms: {<roomKey>: GroupChatSyncRoom}, deleted?: {<roomKey>: <revision int>}}
```
`roomKey` is `id:<roomId>` when the room has one, else legacy `name:<displayName>` (`:117-125`). `GroupChatSyncRoom` (`:56-65`, built at `:241-283`): `{name<=64, roomId?<=128, log: GroupMessage[], revision: int, members: GroupMember[], image?}`; each log entry `{id?<=160, from:{kind:'user'|'member', name<=128, source?}, text<=1200, at:<ms>, thread?<=128}`, last 16 messages (`:51`), members capped at `GROUP_CHAT_MAX_MEMBERS`, `image` dropped over 24000 chars (`:53`). `deleted` is bounded to 64 tombstones (`:199-203`). Room `revision` is the gateway CAS revision the room was last written at, and it orders identity/membership/picture (`:418-454`); `id:` tombstones are FINAL (`:482-489`).

NOTHING SERVER-SIDE VALIDATES OR REWRITES IT. Grep at the pin: `hermes-bots-groups` appears in ZERO Python files. `git grep ui_meta -- hermes_cli/ gateway/ agent/` is empty — `tui_gateway/methods_profiles.py` is the sole server-side reader and writer. The gateway treats the value as opaque JSON, applies only the 64KB `json.dumps` cap, and round-trips it through `yaml.safe_load`/`yaml.dump`. The one server-side transform is the YAML round trip; the one server-side normalisation is `_clean_revisions` on the *revision map* (`:91-93`: `str` keys, non-bool ints, `max(0, ...)`), never on the blob.

NO PUSH. Nothing emits a `profiles.changed`-style event; the read side is polling `profiles.list`. Desktop pulls on gateway transition (`group-chat.ts:1172-1193`) and debounces publishes by 350 ms (`:1157-1169`) with a 1s→30s backoff ladder that gives up after 8 retries (`:879-883,1046-1058`).

## Gotchas

1. A CAS CONFLICT IS AN RPC SUCCESS. `_ok` at `tui_gateway/server.py:714-715`. On Android it arrives as `PluginHostResult.Success` (`app/src/main/kotlin/com/hermesagent/mobile/plugins/PluginHost.kt:180-196`), never `Refused`. A careless implementer treats the 200-equivalent as "saved" and drops the user's edit.

2. `ok` IS NOT THE UI_META VERDICT. `ok = all(applied.values())` (`methods_profiles.py:584`) spans every section. Branch on `applied.ui_meta` and the presence of `applied.ui_meta_conflicts`, nothing else.

3. `applied.ui_meta == false` WITH NO `ui_meta_conflicts` MEANS DO NOT RETRY. That is the 64KB cap (`:443-444`), a malformed `ui_meta_expected_revisions` (`:446-447`, raised then swallowed at `:478-479`), or a YAML write failure. All three look identical to a conflict if you only check `ui_meta == false`, and a retry loop on them spins forever.

4. THE MERGE IS ONE LEVEL DEEP. Sibling top-level keys are safe automatically; everything INSIDE the key you write is replaced. The clobber risk for #193 is not `color` or `hermes-bots` — it is a field Desktop added inside the v3 envelope or inside a room object. Desktop's own `mergeGroupChatSyncSnapshots` (`group-chat.ts:456-479`) rebuilds each room from a fixed field list and therefore drops unknown per-room fields itself; an Android client must be strictly better than Desktop here, not merely equal to it.

5. YOU MUST SEND AN EXPECTED REVISION FOR EVERY KEY IN THE REQUEST, INCLUDING 0 FOR A KEY THAT DOES NOT EXIST YET. `:453-456` — a missing entry yields `wanted = None`, which fails `isinstance(wanted, int)` and conflicts. Booleans are rejected too (`isinstance(wanted, bool)`), so a JSON `true` for a revision is a conflict, not a `1`.

6. ONE KEY PER CAS'D CALL. Any mismatch rejects all keys (`:457`), and every key you send burns a revision (`:468`) even when its value is unchanged — which will conflict a concurrent writer of that other key for no reason.

7. OMITTING `ui_meta_expected_revisions` IS SILENT LAST-WRITER-WINS, and this is exactly what Desktop does for the per-bot `hermes-bots` key (`apps/desktop/src/plugins/hermes-bots/data.ts:347-359` sends `{name, ui_meta:{'hermes-bots': rest}}` with no expected revisions, where `rest` is a *client-cache* spread at `:309-315`). So Desktop itself will happily overwrite a concurrent per-bot edit. Do not copy that; Android should CAS the `hermes-bots` key even though Desktop does not.

8. FEATURE-DETECT ON KEY PRESENCE, NOT TRUTHINESS. `ui_meta_revisions` is always emitted, `{}` included (`:230`); `ui_meta` is omitted entirely when the stored map is empty (`:231-232`). Desktop uses `Object.prototype.hasOwnProperty.call(profile, 'ui_meta_revisions')` (`group-chat.ts:846`). In Kotlin that is `row.containsKey("ui_meta_revisions")`, NOT `row["ui_meta_revisions"] != null` — and definitely not "did I get a non-empty map back".

9. A SECOND, UNLOCKED WRITER OF THE SAME FILE EXISTS. `profiles.configure`'s `description` branch calls `write_profile_meta` (`methods_profiles.py:577-579`), which does its own read-modify-write of profile.yaml at `hermes_cli/profiles.py:634-649` — OUTSIDE `_profile_ui_meta_lock`. A `description` save concurrent with a `ui_meta` save can lose one of the two whole-file rewrites. Do not send `description` and `ui_meta` in the same call assuming the lock covers both, and do not assume some other client's profile rename cannot eat your just-CAS'd blob.

10. THE LOCK IS PROCESS-LOCAL. `threading.Lock` (`server.py:98`); `_atomic_write` (`utils.py:177-199`) takes no `flock`/`fcntl`. Two gateway processes over the same HERMES_HOME serialize on nothing, and CAS degrades to a narrow-window race rather than a guarantee.

11. A CORRUPT profile.yaml SILENTLY BECOMES A NEW ONE. `_read_profile_yaml` swallows every parse error and returns `{}` (`:81-88`), and the write dumps that `{}`-derived dict back (`:474-475`). An unparseable profile.yaml is therefore REPLACED with just `ui_meta` + `_ui_meta_revisions`, dropping `description` / `display_name` / anything else. `yaml.dump` also discards comments.

12. SIZE IS MEASURED PYTHON-SIDE, ASCII-ESCAPED, ON THE INCOMING PAYLOAD ONLY. Not on the merged result. Emoji and CJK in a room name or message inflate ~6x. Port `groupChatGatewayJsonSize` (`group-chat.ts:92-115`) rather than using `Json.encodeToString(...).length`, and budget 48000 like Desktop (`:50`).

13. `profiles.list` IS THE ONLY READ DOOR. `profiles.describe` (`:401-433`) returns no `ui_meta`. So every CAS read costs a full roster walk; send `include_sessions: false` (`:242`, and Desktop does at `group-chat.ts:840-842`) or you also pay a `state.db` open per profile (`:208-221`).

14. `_clean_revisions` CAN RESET A REVISION TO 0. `:93` drops any non-int / bool value from `_ui_meta_revisions`. A hand-edited profile.yaml with a quoted revision silently re-opens the door for a stale writer.

15. #193's OWN TEXT IS STALE. It predates `docs/adr/0004-hosted-rooms-for-group-chats.md` (accepted 2026-09-09), which forbids reading or writing `hermes-bots-groups` from Android (`:25`, `:28`). Implementing "CAS writes back to `hermes-bots-groups`" as written would violate an accepted ADR. The ADR needs a one-line re-verification note at the new pin (its citations are all at `72a3277cd7`), not a reversal — its premise still holds.

## What this app must build

THE EXACT ALGORITHM #193 (or any ui_meta writer) MUST RUN — a read-merge-CAS-verify loop, unknown-field-preserving:

1. READ. `profiles.list {include_sessions: false}`. Pick the row with `name == "default"` (or the target bot's profile name for a `hermes-bots` write).
2. FEATURE-DETECT. `supportsCas = row.containsKey("ui_meta_revisions")`. If false, the gateway predates CAS: either refuse the write or accept documented LWW — do not silently proceed as if guarded.
3. CAPTURE. `remoteValue = (row["ui_meta"] as? JsonObject)?.get(KEY)`; `remoteRev = max(0, (row["ui_meta_revisions"] as? JsonObject)?.get(KEY)?.intOrNull ?: 0)`; `writeRevision = remoteRev + 1`.
4. MERGE FORWARD FROM THE SERVER OBJECT, NOT FROM LOCAL STATE. Start from `remoteValue` as a raw `JsonObject`. Copy it, then overwrite ONLY the fields this app models, and carry every unrecognised key through verbatim — at the envelope level AND inside each room object. Concretely: `buildJsonObject { remoteRoom.forEach { (k, v) -> put(k, v) }; put("log", mergedLog); put("revision", ...) }`, never `buildJsonObject { put("name", ...); put("log", ...); put("members", ...) }`. This is the single line that separates "does not clobber a field Desktop wrote" from "does". Because the server merge is one level deep (`methods_profiles.py:463-467`), sibling top-level keys (`hermes-bots`, `color`) need no work — the danger is entirely inside the value.
5. WRITE ONE KEY. `profiles.configure {name: <profile>, ui_meta: {KEY: merged}, ui_meta_expected_revisions: {KEY: remoteRev}}`. Never batch a second `ui_meta` key, never co-send `description`.
6. VERIFY. Success iff `result.applied.ui_meta == true` AND `result.applied.ui_meta_revisions[KEY] == writeRevision`. Desktop additionally re-reads and requires `confirmed.revision >= writeRevision` (`group-chat.ts:1016-1031`).
7. CONFLICT. `applied.ui_meta == false` AND `applied.ui_meta_conflicts` present → go to step 1 with the `actual` revision, re-merge, retry. Bounded backoff (Desktop: 1s→30s, give up after 8, `group-chat.ts:879-883,1046-1058`). NEVER re-send the same payload with the same expected revision.
8. HARD FAIL. `applied.ui_meta == false` AND no `ui_meta_conflicts` → payload or host problem. Surface a product-copy limitation line, do not loop.
9. DELETE. Send `{KEY: null}`; the revision still advances and persists, so the delete is not resurrectable by a stale peer.

WHAT THIS APP MUST BUILD OR CHANGE (all paths under `/home/donovanyohan/Documents/Programs/personal/hermes-mobile`):

- `app/src/main/kotlin/com/hermesagent/mobile/plugins/bots/BotsPluginRepository.kt` — today it is read-only and DISCARDS both fields: `loadRoster()` (`:34-48`) sends `include_sessions = true`, and `parseBotsRoster` (`:70-87`) builds `BotRosterRow` from `name/description/display_name/canonical_session/last_session/has_avatar` only. Neither `ui_meta` nor `ui_meta_revisions` is parsed anywhere in the bots plugin. Needs: an `include_sessions=false` read variant for the CAS lane, `uiMeta: JsonObject?` + `uiMetaRevisions: Map<String,Int>` + `supportsCas: Boolean` on the row, and a new `configureUiMeta(...)` returning a typed three-way result (`Applied(revisions)` / `Conflict(conflicts, revisions)` / `Failed`).
- `app/src/main/kotlin/com/hermesagent/mobile/plugins/bots/BotsRosterModel.kt` — `BotRosterRow` gains the fields above.
- `app/src/main/kotlin/com/hermesagent/mobile/plugins/PluginHost.kt` — no change needed to send it: `normalizePluginHostMethod` (`:117-127`) is a shape check, not an allowlist, so `profiles.configure` is already callable. BUT the three-way `PluginHostResult` (`:84-106`) cannot express a CAS conflict — it arrives as `Success` (`:180-196`). The conflict discrimination must live in the bots repository's own parse layer, not in the host door.
- `app/src/main/kotlin/com/hermesagent/mobile/data/gateway/GatewayRpc.kt:498-508` — `gatewayRpcTimeoutMillis` special-cases only `prompt.submit` and `profiles.list`; `profiles.configure` would get the 15s default despite being a `_LONG_HANDLERS` member upstream (`tui_gateway/server.py:169`) that opens and rewrites profile.yaml under a contended lock. Add an entry (60s, matching `profiles.list`) or the CAS loop will time out into a bogus `Refused` and retry a write that may already have landed.
- `app/src/main/kotlin/com/hermesagent/mobile/data/profiles/GatewayProfileRepository.kt:113` and `.../ProfileModel.kt:49-56` — the app's other `profiles.list` parser reads exactly one ui_meta key (`uiMetaColor`) and drops the rest. If a colour write is ever added it must join the same CAS lane; today it is read-only, which is safe.
- Nothing in the app calls `groups.*` yet, so ADR 0004's chosen surface is entirely unbuilt.
- Sizing helper: port `groupChatGatewayJsonSize` (`group-chat.ts:92-115`) as a Kotlin function with a unit test that a 3-emoji room name is measured ~6x its UTF-16 length. `Json.encodeToString(...).length` is wrong.
- Tests, per #193's acceptance: a virtual-time CAS-conflict test proving a losing write RE-READS and re-merges rather than clobbering, plus a test proving an unknown key inside the remote value survives an Android write round trip. Both are pure JVM (`app/src/test/kotlin/`), no Robolectric.

CITATION DRIFT THE PIN MOVE INTRODUCED (read-only finding; I changed nothing):
- `.../data/profiles/ProfileModel.kt:52` cites `methods_profiles.py:221-236`; the block is `224-234` at 564aef2946.
- `.../data/profiles/GatewayProfileRepository.kt:92` cites `methods_profiles.py:205-249`; `profiles.list` is `237-254` at 564aef2946.
- `.../plugins/PluginHost.kt:91` cites `server.py:734` @ `72a3277cd7` for `-32601`; it is `server.py:749` at 564aef2946.
- `.../plugins/PluginHost.kt:23,57` and `.../data/gateway/GatewayRpc.kt:495` still cite `72a3277cd7` / `3ca096de` explicitly.
- `docs/adr/0004-hosted-rooms-for-group-chats.md` is entirely cited at `72a3277cd7`.
`.../plugins/bots/BotsPluginRepository.kt:16-17,65` is already correct at 564aef2946 (`237-254`, `245-250`).

## What could not be established

1. CROSS-PROCESS SERIALIZATION — established as ABSENT, but only by absence of evidence. I confirmed `_profile_ui_meta_lock` is a plain in-process `threading.Lock` (`tui_gateway/server.py:98`) and that `_atomic_write` (`utils.py:177-199`) uses no `flock`/`fcntl`. I did NOT establish whether two gateway processes can legitimately serve the same HERMES_HOME concurrently (e.g. `hermes serve` plus a `hermes tui` gateway). The answer lives in `hermes_cli/web_server.py` and the multiplexer bootstrap in `gateway/run_startup.py` — whether a single-instance port/pidfile guard makes the multi-process race unreachable in practice. I read neither.

2. WHETHER `profiles.configure` IS ROUTABLE TO A NON-LOCAL PROFILE FROM THIS APP'S SINGLE CONNECTION. `_resolve_profile` (`methods_profiles.py:69-78`) resolves by `get_profile_dir(name)` under the serving process's HERMES_HOME, and Desktop routes group writes through `host.requestProfile(route, ...)` when a route exists (`group-chat.ts:816-828`). Android has `ProfileScope.sessionProfileParam` for *session* RPCs (`app/src/main/kotlin/com/hermesagent/mobile/data/profiles/ProfileScope.kt:37-48`) but no equivalent of Desktop's `host.profileRoutes()`/`requestProfile`. I did not establish whether `profiles.configure {name: "default"}` on a multiplexed gateway always reaches the same profile.yaml the app's `profiles.list` read from. That would be settled by reading `hermes_cli/profiles.py:get_profile_dir` against `tui_gateway/server.py`'s `set_hermes_home_override` scoping (`methods_profiles.py:59-66`).

3. THE YAML ROUND-TRIP'S FIDELITY FOR THE BLOB. `yaml.safe_load` → `yaml.dump` is the one server-side transform on `hermes-bots-groups`. I reasoned that PyYAML quotes ambiguous scalars and that the blob's value types (str/int/float/bool/null/list/dict) survive, but I did not empirically round-trip a real v3 envelope containing an `image` data URL, a message ending in a colon, or a room name of `"yes"`/`"null"`/`"1.0"`. If #193 ever does write the blob, that is a ten-line pytest against `utils.atomic_yaml_write` + `_read_profile_yaml`, and it is the one place a "server-side rewrite" could still bite despite no code validating the blob.

4. UNBOUNDED `_ui_meta_revisions` GROWTH. Nothing at `methods_profiles.py:436-479` ever prunes the revision map, and it is emitted in full on every `profiles.list` row (`:230`) — i.e. on every roster paint. I found no pruning anywhere and no cap. I did not quantify whether a long-lived install accumulates enough keys for this to matter; it would show up as `profiles.list` payload growth, not as a correctness bug.

5. WHETHER ADR 0004'S CITATIONS SURVIVED THE RE-PIN. I verified its load-bearing premise at the new pin — no `groups.*` caller under `apps/desktop/src/**` at 564aef2946 — but I did NOT re-read its ~60 `path:line` citations into `gateway/hosted_rooms.py`, `tui_gateway/hosted_room_service.py`, `gateway/hosted_room_discussion.py` and `tui_gateway/methods_groups.py`, all of which were taken at `72a3277cd7`. `tui_gateway/methods_groups.py:18-23` still registers the eighteen methods at the new pin (verified), but every line number in the ADR's method, event and error tables is unverified at 564aef2946. That re-read is a prerequisite for the hosted-room slices, not for this ui_meta contract.


---

# #194 — Avatars, pets and bot identity

**Verdict:** UNBLOCKED — both handlers are short, self-contained and fully readable at the pin; the only material correction is that the audit's "15 MB cap" is a Desktop file-picker cap, not the contract. The server cap is 2,000,000 bytes on decoded bytes.

## Methods

- `profiles.set_asset` — handler `tui_gateway/methods_profiles.py:595-630` (registered `@_profile_handler("profiles.set_asset", 5065)` at `:595`).
- `profiles.get_asset` — handler `tui_gateway/methods_profiles.py:633-647` (`@_profile_handler("profiles.get_asset", 5066)` at `:633`).
- Shared helpers: `_ASSET_EXTS` / `_ASSET_MAGIC` `methods_profiles.py:14-18`; `_unlink_asset_files` `:589-592`; `_resolve_profile` `:69-78`; `_profile_handler` catch-all wrapper `:21-30`.
- `has_avatar` producer: `_profile_ui_meta_fields` `methods_profiles.py:224-234` (the flag itself at `:234`), called unconditionally from `profiles.list` `:237-254` (call site `:250`).
- Envelope: `_ok` / `_err` `tui_gateway/server.py:714-720`. Both methods are in the slow-lane thread pool `tui_gateway/server.py:169-170`.
- Desktop call sites: `apps/desktop/src/plugins/hermes-bots/data.ts:372-402` (change-gated push/clear), `profile-ops.ts:43-115` (backfill push), `profile-ops.ts:160-210` (pull), `create-dialog.tsx:464-470` (remote target), `components/assistant-ui/thread/user-message.tsx:130-177` (inter-agent pfp).
- Adjacent methods the slice needs: `image.generate` `tui_gateway/methods_images.py:43-82`; `pet.gallery` `tui_gateway/methods_session.py:1281-1309`.

## Request shape

**set_asset** (`methods_profiles.py:595-630`), all read off a flat params object:
- `name`: string, required. `str(params.get("name") or "").strip()`; empty → `_err(rid, 4063, "name required")` (`:600-601`). It is the *profile name*, resolved to a directory by `get_profile_dir(name)` (`:74-77`).
- `asset`: string, optional, defaults to `"avatar"`; `str(params.get("asset") or "avatar").strip().lower()` (`:599`). Anything other than `"avatar"` → `_err(rid, 4066, f"unknown asset '{asset}' (supported: avatar)")` (`:602-603`).
- `clear`: bool-ish, optional. Passed through `is_truthy_value(params.get("clear", False))` (`:610`), so `true`, `"true"`, `1` all count (`utils.py:24-30`). When truthy the handler returns immediately and `data` is never read.
- `data`: string, required unless `clear` (`:612-614`, `_err(rid, 4067, "data required (data URL or base64)")`). **Binary is carried inline as base64 text in the JSON-RPC params.** Two accepted forms, one regex: `^data:(image/(?:png|jpeg|webp));base64,(.*)$` with `re.DOTALL` (`:615`) — a data URL with exactly that mime set, or a bare base64 payload with no prefix at all. If the regex misses, the whole string is fed to `base64.b64decode(..., validate=True)` (`:617`). There is **no multipart route, no file path, no chunking, no upload ticket**.

**get_asset** (`methods_profiles.py:633-647`):
- `name`: string, required — same `_resolve_profile` gate, 4063 / 4064 (`:638-640`).
- `asset`: string, optional, defaults to `"avatar"`, lowercased (`:636`). **Not validated here** — unlike set_asset there is no `!= "avatar"` refusal.
- No other params. No `mime`, `max_bytes`, `size` or `since` knob exists.

## Response shape

All replies are JSON-RPC `{"jsonrpc":"2.0","id":rid,"result":{...}}` (`server.py:714-716`); errors are `{"jsonrpc":"2.0","id":rid,"error":{code,message}}` (`server.py:718-720`).

**set_asset, write** (`:630`): `{"ok": true, "asset": "avatar", "size": <int decoded bytes>}`.
**set_asset, clear** (`:611`): `{"ok": true, "asset": "avatar", "size": 0, "removed": <int files that existed>}` — `removed` is 0 when nothing was there, and that is still `ok: true`, not an error (`_unlink_asset_files` `:589-592`).

**get_asset, present** (`:645-646`): `{"found": true, "mime": "image/png"|"image/jpeg"|"image/webp", "size": <int raw bytes>, "data": "data:<mime>;base64,<...>"}` — always a data URL with the mime prefix, never bare base64, never a URL or path.
**get_asset, absent** (`:647`): `{"found": false}` — that is the entire object. No `data`, no `mime`, no `size` key at all, **no 404, no error, no generated fallback**. Desktop models it as `{ data?: string; found?: boolean }` (`profile-ops.ts:153-158`).

**Error codes** (all JSON-RPC `error.code`, message is backend prose):
- 4063 `name required` (`:601`, and `_resolve_profile:73`)
- 4064 `profile '<name>' not found` (`_resolve_profile:77`) — both methods
- 4066 `unknown asset '<x>' (supported: avatar)` — set_asset only (`:603`)
- 4067 `data required (data URL or base64)` (`:614`)
- 4068 `data is not valid base64` (`:619`)
- 4069 `asset too large (<n> bytes; max 2MB)` (`:621`)
- 4070 `unsupported image format (PNG/JPEG/WebP only)` (`:624`)
- 5065 / 5066 — the `_profile_handler` catch-all (`:21-30`): any uncaught exception (disk full, permissions, `mkdir` failure) becomes `_err(rid, 5065, str(e))` for set_asset / `5066` for get_asset, with the raw Python exception text as the message.
- `-32601` if the gateway build predates the methods (`server.py`, mapped to `PluginHostResult.UnavailableOnGateway` in this app).

## Semantics

**Asset kinds: exactly one, `avatar`.** There is no enum, no registry, no list endpoint. The set is a literal string comparison at `methods_profiles.py:602-603`. `_ASSET_EXTS` (`:15`) is *extensions*, not kinds: `{"png": "image/png", "jpg": "image/jpeg", "webp": "image/webp"}`. There is no `pet` asset, no `sprite` asset, no banner. Pets are not a server asset kind at all — a chosen pet becomes an ordinary `avatar` PNG (see sprite section), and the pet *slug* stays client-local (`data.ts:159-166`: `StoredBotMeta.pet` is "the extracted pet icon that stays local and is never sent to the server", stripped at `data.ts:345`, asserted by `data.bot-meta.test.ts:154-165`).

**Storage layout.** `<profile_dir>/assets/avatar.<ext>`, one canonical file per asset: every existing `avatar.{png,jpg,webp}` is unlinked before the write (`:626`), so the extension can change between writes. The write is atomic: `avatar.<ext>.tmp` then `Path.replace` (`:627-629`). `assets/` is created lazily (`:625`).

**Format is sniffed, the declared mime is never trusted** (`:16-18`, `:622`). Magic checks: PNG `\x89PNG\r\n\x1a\n` at 0..8; JPEG `\xff\xd8\xff` at 0..3; WebP `RIFF` at 0..4 **and** `WEBP` at 8..12. A data URL that says `image/png` but carries GIF bytes is rejected 4070. Conversely a bare base64 GIF is also 4070 — GIF is accepted by Desktop's file picker `accept` attribute (`avatar-image.ts:41`) but refused by the server.

**Size cap: 2,000,000 bytes on the DECODED blob, server-side** (`:620-621`). Checked after base64 decode, before sniffing, before any disk touch. The 15 MB the audit cites is `apps/desktop/src/plugins/hermes-bots/avatar-image.ts:50` — a client-side `file.size > 15_000_000` guard in the browser file picker, whose only effect is the toast `avatar.imageTooLarge` = "Image too large (max 15MB)." (`i18n.ts:369`). It is not a contract and the server has never heard of it. Desktop stays under 2 MB because *every* image entering the picker is re-encoded to a 256×256 PNG data URL first: `normalizeAvatarImage(dataUrl, edge = 256)` (`avatar-image.ts:14-35`) is applied on the upload path (`avatar-picker.tsx:79-85`) and on the generate path (`avatar-picker.tsx:87-120`, call at `:113`).

**Read order is `_ASSET_EXTS` iteration order: png, then jpg, then webp** (`:641`, comment at `:14` says so explicitly). First hit wins. Since set_asset unlinks the others, collisions do not occur in practice.

**get_asset does not consult `has_avatar`, and `has_avatar` is not authoritative in the same call.** `has_avatar` is computed per row inside `profiles.list` as `any((profile_dir / "assets" / f"avatar.{e}").is_file() for e in _ASSET_EXTS)`, wrapped in `_try(..., False)` so any error reads as false (`:234`). Its stated purpose is "Cheap existence flag so rosters skip a get_asset probe per paint" (`:233`). It is TOCTOU by nature — the file can vanish between list and get, and get answers `{"found": false}` rather than erroring.

**`has_avatar` rides `profiles.list` unconditionally, NOT gated on `include_sessions`.** `_profile_ui_meta_fields` is called at `:250`, outside the `if include_sessions:` branch at `:248-249`. Only `last_session` / `worker_session` / `canonical_session` are session-gated.

**`profiles.describe` does NOT carry `has_avatar`** — the returned dict at `methods_profiles.py:428-433` is `{name, description, soul, model, skills, toolsets, toolsets_pinned, mcp_servers}`. `profiles.list` is the only source of the flag.

**Write semantics are last-writer-wins with no CAS.** `ui_meta` has compare-and-swap revisions (`ui_meta_revisions`, `methods_profiles.py:437-479`, 64 KB cap at `:443`); the asset store has **none**. No etag, no revision, no conditional write. That is exactly why Desktop change-gates the call rather than sending it on every save: `data.ts:372` fires `set_asset` only when `'image' in patch && patch.image !== (prevMeta.image ?? null)`, because "a no-op `clear` from one machine can race another machine's just-pushed avatar and wipe it server-side" (`data.ts:366-371`, test `data.bot-meta.test.ts:117-138`).

**Profile scoping.** `name` alone selects the profile; the handler resolves the directory directly and does not enter a `_hermes_home_scope` (unlike `profiles.describe`, `:407`). Cross-gateway targeting is a *transport* concern in Desktop (`requestForBot` / `requestForTarget` pick the connection), not a param. `docs/design/multiplexing-gateway.md:158-165` names these two among the control-plane methods and states "Asset writes are atomic, type- and size-capped."

**Merge vs replace:** replace. A write clobbers whatever was there; `clear:true` deletes. There is no partial/patch mode.

**Frame budget is not a constraint:** the gateway's ws door runs `ws_max_size = 384 MiB` (`hermes_cli/web_server.py:361`, applied `:1173`), so a ~2.7 MB base64 body is nowhere near the limit.

## Gotchas

1. **The 15 MB cap is a Desktop UI toast, not the contract.** A careless port applies `15_000_000` to the picked file, ships the raw bytes, and gets `4069 asset too large (… ; max 2MB)` for anything above 2 MB decoded. The Desktop behaviour that actually keeps it legal is the unconditional 256×256 PNG re-encode (`avatar-image.ts:14-35`) applied at `avatar-picker.tsx:83` and `:113`. Port the *downscale*, then keep the 15 MB guard only as the pre-decode rejection it is (verbatim copy "Image too large (max 15MB)." `i18n.ts:369`).

2. **`size` in the reply is decoded bytes, `data` is a base64 data URL ≈ 1.37× that.** Do not size a buffer off `size`.

3. **Absent asset is `{"found": false}` — success, not failure.** Treating a missing avatar as an error produces a retry storm on every roster paint. Desktop caches the miss with a timestamp (`user-message.tsx:121,173-175`).

4. **`get_asset` does not validate `asset`, `set_asset` does.** `get_asset` interpolates the lowercased, unsanitised string into `profile_dir / "assets" / f"{asset}.{ext}"` (`:642`) — a `../` segment is not stripped. Never let a user-supplied or plugin-supplied string reach that param; hard-code `"avatar"` on the Android side. (Upstream observation, not this repo's bug — but it is why the app must not build a generic `getAsset(kind)` API.)

5. **Format sniffing beats the declared mime.** A data URL prefix of `image/png` over WebP bytes is stored as `avatar.webp` (`:622`), and non-PNG/JPEG/WebP is 4070 even if the data URL claims otherwise. GIF passes Desktop's picker `accept` list (`avatar-image.ts:41`) and is refused server-side — so the Android picker must not offer GIF unless it transcodes.

6. **`clear:true` and `data` are mutually exclusive by precedence, not validation.** `clear` is checked first (`:610`); sending both silently deletes and ignores the data.

7. **No CAS on assets.** Fire `set_asset` only on a real change; an unconditional `clear` on every save races another client and wipes a just-pushed avatar (`data.ts:366-371`). This must be ported, not just the happy path.

8. **`5065` / `5066` leak raw Python exception text** including filesystem paths (`_profile_handler:28`). This repo's copy rule forbids surfacing backend prose. Map on `PluginHostResult.Refused.code` — that sealed class already carries the numeric code (`PluginHost.kt:98-104`) — and print this app's own sentence.

9. **`has_avatar` is not gated on `include_sessions`.** `BotsPluginRepository.kt:65-68` currently documents "plus `last_session` / `canonical_session` / `ui_meta` / `has_avatar` when `include_sessions` is on" — wrong at this pin (`methods_profiles.py:248-250`). `GatewayProfileRepository.kt:80` sends `include_sessions=false` and still gets a correct `has_avatar` at `:114`; the docstring is what needs fixing, not the call.

10. **`image.generate` can return a bare URL/path instead of a data URL.** `image_data` is omitted when the gateway's own download fails, and callers fall back to `image`, "the backend's URL/path" (`methods_images.py:45-49,79-82`). Desktop just hands that to an `<img>` src inside `normalizeAvatarImage`. On Android that string may be a **filesystem path on the gateway host**, which this app cannot and must not fetch. Treat a missing `image_data` as a generation failure. Also: the default `max_bytes` there is 8 MB, four times what `set_asset` will accept — another reason the downscale is mandatory, not cosmetic.

11. **Both methods are in the slow-lane pool** (`server.py:169-170`), so they need the generous RPC budget the app already gives `profiles.list`, not the default.

12. **Do not put the image in `ui_meta`.** It rides every `profiles.list` and is 64 KB-capped server-side (`methods_profiles.py:437-443`); Desktop strips `image` and `pet` before `profiles.configure` (`data.ts:345`, test `data.bot-meta.test.ts:154-165`).

## What this app must build

**What exists today.** `BotRosterRow.hasAvatar` (`app/src/main/kotlin/com/hermesagent/mobile/plugins/bots/BotsRosterModel.kt:51`) and `HermesProfile.hasAvatar` (`app/src/main/kotlin/com/hermesagent/mobile/data/profiles/ProfileModel.kt:56`) are both parsed — `BotsPluginRepository.kt:84`, `GatewayProfileRepository.kt:114` — and **read by nothing**: a repo-wide grep over `app/src/main/kotlin/` finds only the two declarations and the two parse sites. `ProfileGlyph` (`app/src/main/kotlin/com/hermesagent/mobile/ui/common/ProfileGlyph.kt:40-53`) always paints the initial-on-tint mark or the `home` codicon, with no image branch. The Bots roster (`BotsRosterScreen.kt`) draws no avatar at all.

**Concretely, slice #194 must build:**

1. **An asset door on the plugin host.** `PluginHost.request(method, params)` (`PluginHost.kt:29-42`) already takes any dotted method — `normalizePluginHostMethod` (`:117-127`) only rejects blank/spaced/slashed/`..` names, so `profiles.get_asset` and `profiles.set_asset` pass with no allowlist edit. A thin `BotsAssetRepository` beside `BotsPluginRepository.kt` should own both calls, hard-coding `asset = "avatar"`, and map the three-way `PluginHostResult` plus the numeric codes 4064/4067/4068/4069/4070/5065 onto this app's own sentences (the `Refused(code, safeMessage)` shape at `PluginHost.kt:98-104` makes that a `when` on `code`).

2. **A read path gated on `hasAvatar`.** Only call `get_asset` for rows where `hasAvatar` is true (that is the flag's stated purpose, `methods_profiles.py:233`), dedupe in-flight per profile name, cache the decoded bitmap, and cache the miss so a `{"found": false}` does not re-fire on every recomposition — Desktop's `avatarFetchInflight` / `agentAvatarMissAt` (`profile-ops.ts:168`, `user-message.tsx:121,173-175`).

3. **Base64 decode + bounded bitmap decode.** The reply's `data` is `data:<mime>;base64,<...>`: strip the prefix, `android.util.Base64.decode`, then reuse `AttachmentThumbnails`-style bounded decoding (`app/src/main/kotlin/com/hermesagent/mobile/ui/common/AttachmentThumbnails.kt:16-28`) — in-memory bytes only, `inJustDecodeBounds` then `inSampleSize`, no file, no URI, no persistence. A new `AVATAR_MAX_DIM` (256 matches Desktop's own edge) belongs next to `COMPOSER_MAX_DIM`/`TRANSCRIPT_MAX_DIM`.

4. **A write path that downscales before it encodes.** Read the picked image with the existing bounded read used by the composer attachment path, decode to a Bitmap, centre-crop to a square and scale to 256, `Bitmap.compress(PNG)`, base64 the compressed bytes, and send `data:image/png;base64,…`. The 2 MB server cap should be re-checked locally on the *compressed* bytes so the failure is a local, worded refusal rather than a 4069 round-trip. No `content://` URI, no device path, no filename goes on the wire — the whole payload is one base64 string, so the existing `file.attach` staging (`GatewaySessionRepository.kt:2653-2659`) is **not** the right seam; `set_asset` is a single inline RPC.

5. **Change-gating and clear.** Mirror `data.ts:372`: fire `set_asset` only when the image actually differs from the previous value, `clear: true` on removal, nothing when the key is absent. This is a correctness requirement (multi-client wipe race), not an optimisation.

6. **ProfileGlyph gains an image branch.** `ProfileGlyph(profile, …)` should prefer a resolved avatar bitmap when `profile.hasAvatar` and the cache has one, falling back to today's `home` codicon / initial-on-tint. That change lands under `ui/`, so it triggers the `review-desktop-parity` gate and needs a rendered side-by-side plus a row in the parity ledger.

7. **Pet tab = ordinary avatar upload.** No new gateway method: `pet.gallery` for the list, an HTTP GET of `spritesheetUrl`, a 192×208 crop at origin, scale to 96×104, PNG-encode, then the same `set_asset` path. Note the app has no HTTP image fetcher for arbitrary external URLs today, and the cleartext policy in this repo (loopback-only) applies to that fetch.

8. **Docstring fix.** `BotsPluginRepository.kt:65-68` misstates the `has_avatar` gating; correct it to cite `methods_profiles.py:250` @ the new pin. `BotsRosterModel.kt:8-13` and `ProfileModel.kt:33` still cite `3ca096de…` / `72a3277cd7…` and need re-pinning to `564aef2946c436500a5e80ee117b66b789b3f99a` as part of the slice.

## What could not be established

1. **No upstream server-side test exists for either handler.** `git grep -l "set_asset\|get_asset"` across the whole tree at the pin returns exactly one test file, and it is Desktop's `apps/desktop/src/plugins/hermes-bots/data.bot-meta.test.ts`. There is no Python test asserting 4066/4069/4070, the magic sniffing, the atomic replace, or the png→jpg→webp read order. Every claim above about server behaviour is read from the handler body, not from a passing test. If you want executable proof, it would have to be written against `tui_gateway/methods_profiles.py` in the upstream repo — which is read-only here.

2. **I did not read `avatar-picker.tsx` in full** (only `:70-120`, the upload and generate handlers, plus the `normalizeAvatarImage` call sites). The four-tab layout, the blob-face Lock/Unlock and Randomize controls named in issue #194, and their exact menu order live in `apps/desktop/src/plugins/hermes-bots/avatar-picker.tsx:56-295` and `avatar.tsx`, and the slice's parity work will need those read verbatim. They are UI, not payload shape, so out of this brief's scope.

3. **`pet.generate` / `pet.hatch` and their progress events are unread.** They are at `tui_gateway/methods_session.py:1404-1458+` and `apps/desktop/src/store/pet-generate.ts`, listed as *optional* on #194. I read only `pet.gallery` (`methods_session.py:1281-1309`) because that is what the frame-0 avatar path needs.

4. **Where `spritesheetUrl` actually points is unresolved.** `pet.gallery` copies `entry.spritesheet_url` straight from `agent/pet/manifest.py`'s `fetch_manifest()` (`methods_session.py:1294-1300`), and locally-installed-but-unlisted pets get `""` (`:1305`). I did not open `agent/pet/manifest.py`, so I cannot say whether that URL is an absolute remote CDN URL, a gateway-relative path, or something the phone can reach at all over a Remote Gateway. The answer is in `agent/pet/manifest.py` at the pin, and the slice needs it before the Pet tab is buildable — the Desktop client fetches it directly from the renderer (`pet.tsx:52`), which tells you nothing about reachability from a phone on a different network.

5. **`hermes_cli/web_server.py:14498`** — the REST twin of `profiles.list` referenced by `ProfileModel.kt:33-37` for `has_env` — I did not re-read at the new pin, so I cannot confirm that line number survived the re-pin, nor whether the REST side exposes an avatar route. My grep for "avatar" across `hermes_cli/web_routers/` and `hermes_cli/web_app.py` returned nothing, which is evidence there is no HTTP avatar endpoint, but I did not enumerate `web_server.py` itself for one.

6. **Concurrency between two simultaneous `set_asset` calls for the same profile** is not established. The write is atomic per file (`tmp` + `replace`, `:627-629`), but `_unlink_asset_files` at `:626` runs first and is not, so two racing writes with different extensions have an interleaving I did not reason through to a conclusion. No lock is taken anywhere in the handler.
