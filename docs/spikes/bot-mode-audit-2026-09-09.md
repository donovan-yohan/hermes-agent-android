# Spike: Desktop Bot Mode, audited for porting

Status: audit / porting decomposition. No product code, no parity page yet.

| | |
|---|---|
| Upstream pin | `NousResearch/hermes-agent` @ `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd` |
| Previous pin | `3ca096de5f8183cb2e0ec23673f294d5978656a3` |
| Audited | 2026-09-09 |
| Android head | this worktree, branch `docs/bot-mode-audit` |

**Scope.** Desktop's `apps/desktop/src/plugins/hermes-bots/` plugin (Bot Mode:
roster, Bot Chat, group chats, avatars/pets, routines/cron, canonical-chat
registry, cross-connection bots), the sidebar's `gateway-group*` files, the
`pet-generate` / `pet-overlay` surfaces, and the gateway modules that serve
them (`tui_gateway/methods_bot_relay.py`, `tui_gateway/methods_groups.py`,
`gateway/hosted_room*`, `event_publisher.py`, `event_replay.py`, `tools/bot_*`).

Every `path:line` below is upstream-relative and read at `72a3277cd7`. The
checkout is read-only; nothing here was fetched, checked out, or written.

**Companion work in flight.** A separate agent re-pinned the reference
checkout; a third is verifying this app's *existing* wire surface in depth.
Section 4 is deliberately short because that verifier owns it.

**Decision (2026-09-09).** The epic has decided the question sections 1.6 and
3.4 leave open: Android's Bot Mode group chats use the gateway's hosted-room
protocol (`groups.*`), keep Desktop's UI treatment, and record the difference
from the pinned Desktop as drift on the parity ledger. The durable record is
[ADR 0004](../adr/0004-hosted-rooms-for-group-chats.md); the corrections from
#199 are applied to sections 1.6 and 3.4 and to slices 6 and 7 below. Where an
older sentence in this document still says a slice "reimplements" Desktop's
engine or writes `hermes-bots-groups`, the ADR wins.

---

## 1. What Bot Mode is at `72a3277cd7`

Bot Mode is a **bundled Desktop plugin**, not a core surface. Its own header
states the product: "a 'one chat per agent' roster for the Hermes desktop"
(`apps/desktop/src/plugins/hermes-bots/plugin.tsx:1-16`). It registers as
`id: 'bots'`, `name: 'Bots'`, described in Settings as "Bot Mode — a
one-chat-per-agent roster with avatars, routines, group chats, and bot-to-bot
messaging. Ships with the app; disable here if unwanted." (`plugin.tsx:93-97`).

Paths in this section are relative to
`apps/desktop/src/plugins/hermes-bots/` unless stated otherwise.

### 1.1 Concepts

| Concept | What it is | Evidence |
|---|---|---|
| **Bot** | One Hermes *profile*, presented as a roster row. Not a new backend object — a profile plus client-held presentation metadata. | `types.ts:80-107`; `plugin.tsx:2-16` |
| **Bot Chat / canonical chat** | The profile's one forever-conversation: a session titled exactly `Bot Chat`. Identity is **by name, never by pointer** — resolved fresh on every open via `session.list {title:'Bot Chat', include_hidden:true}`. | `canonical-chat.ts:48,184-235`; `tools/bot_mode_probe.py:31` |
| **Canonical-chat registry** | The core's per-profile `UNIQUE(title)` index makes `(profile, "Bot Chat")` an exact registry. The client does *adopt-before-mint*: on a "title already in use" collision it re-consults the registry and adopts the winner rather than forking. A failed lookup **fails closed** and is never read as "no chat exists". | `canonical-chat.ts:388-412`; `canonical-chat-registry.test.ts:1-27,248-308` |
| **Group chat / room** | A `GroupChat` with one ordered `log`, `members`, `holds`, per-member hidden plumbing `sessions`, per-thread-per-member `watermarks`, an immutable `roomId` (a rename cannot fork the log), an `epoch` (bumped to abandon stale in-flight turns) and a `tombstone`. Capped at `GROUP_CHAT_MAX_MEMBERS = 6`. | `types.ts:160-188`; `group-chat.ts:1210-1216` |
| **Hosted room** | The gateway *also* ships a hosted-room protocol (`groups.*`, `gateway/hosted_room*`) with authority epochs, coordinator fencing and log replication. **Desktop's Bot Mode does not use it** — no file under `apps/desktop/src` calls any `groups.*` method. Desktop drives rooms client-side. | `tui_gateway/methods_groups.py:19-22,218-246`; absence verified by grep across `apps/desktop/src` |
| **Pet** | A "petdex" companion: an animated spritesheet (1536x1872, an 8x9 grid of 192x208 frames) with named animation rows (`idle, wave, run, failed, review, jump, waiting`). In Bot Mode, frame 0 is cropped client-side and used as a bot's static profile picture. | `apps/desktop/src/store/pet.ts:10-40`; `pet.tsx:12-76,142-278` |
| **Retained tile** | Two senses. (a) The "Bots" and "Routines" panes are **dock-enforced** into fixed positions on every boot. (b) Relay socket retention: `host.retainProfileSocket` pins each registered connection's socket open while the relay runs; `host.retainProfile` pins a group member's route for a whole turn. | `plugin.tsx:366-441`; `relay.ts:96-174`; `group-turns.ts:357-382` |
| **Attention / "needs you"** | Two distinct signals. Bot-level `$botAttention` classifies into `agent_blocked`, `missing_config`, `provider_auth_or_access`, `provider_quota_limit`; transient errors (rate-limit, 5xx, timeout) deliberately never badge. Group-level `$groupNeedsYou` is set when a room needs user input and cleared on send; clarify/approval attention is derived from `$groupClarify` rather than duplicated into it. | `data.ts:52-157`; `types.ts:303`; `bot-row.tsx:449-537`; `group-rounds.ts:672-675` |
| **Handoff** | At bot level, `@<agent>` or "ask X to…" is a protocol written into the bot's own SOUL: message the agent, wait for the reply, report back. At room level an "unresolved handoff" is a member @-mentioned mid-thread who has not answered, driven through a bounded continuation round. | `soul.ts:56-58`; `group-rounds.ts:263-331,521-560` |
| **Hidden bots** | Display-only. A hidden bot keeps working, stays mentionable and keeps its group memberships; `$showHiddenBots` is a session-only reveal toggle. | `hidden-bots.ts:23-31` |
| **Cross-connection bot** | A roster row whose owner is a *different* registered connection. It carries an **immutable** `ProfileRoute`, and every RPC for it goes through `host.requestProfile`, never the active gateway. | `routing.ts:37-227`; `cross-connection-bots.test.ts:66-131` |

### 1.2 The user journeys

- **Create a bot.** Name / Title / Description plus an avatar picker, with an
  "Advanced" disclosure (General, then Capabilities or Skills/Toolsets/MCP).
  The profile is created **lazily** and single-flighted: it materialises on
  Create *or* on first open of the MCP-setup or Capabilities tab, whichever
  comes first; cancelling discards the draft profile. Only the "New Bot" path
  requests the intro kickoff (`createCanonicalChat(slug, {kickoff:true})`) — a
  click-path open mints a hidden Bot Chat *without* one.
  (`create-dialog.tsx:79-100,117-1016,232-258,549-560`;
  `canonical-chat-registry.test.ts:176-203`)
- **Create a group chat.** A search-and-checkbox picker, 2 to 6 members, a name
  field defaulting to the member names, and an optional room image. Creating is
  always a *fresh* room: the name is uniquified against live rooms and every
  bot's current groupings, and a fresh `roomId` is minted so a same-named
  recreate never resumes the old log. (`create-dialog.tsx:1116-1352,1159-1183`)
- **Open a bot chat.** A roster click fronts an already-open tab — restricted to
  the canonical id and its compression-lineage tip, so side threads are excluded
  — or resolves the registry. A focused but busy Bot Chat still forces a
  registry re-open so `forceResume` repaints fresh rows.
  (`bot-row.tsx:211,235`; `bot-row-opens-canonical-chat.test.ts:45-116`)
- **Edit a bot profile.** Avatar, Title, Description, plus Advanced. Save always
  writes local and server meta, pushes a description RPC only if it changed, and
  applies advanced config separately; three independent failure paths each toast
  distinctly. (`edit-profile-dialog.tsx:47-259,97-176`)
- **Pick an avatar.** Four tabs in order: **Bot** (shape grid plus colour
  swatches, or a blob-face silhouette with Lock/Unlock-to-name and Randomize),
  **Generate** (`image.generate`, gated on a live probe), **Upload** (a plain
  `<input type=file>`, 15 MB cap), **Pet** (the petdex gallery).
  (`avatar-picker.tsx:56-295`)
- **Skills hub and MCP.** The hub is an embedded iframe; picks arrive by
  `window.postMessage({type:'hermes-skill-pick', …})`, hardened to accept only
  messages whose `source === iframe.contentWindow`, and install via
  `skills.manage {action:'install'}`. MCP setup is an inline per-entry flow with
  states `idle | keys | oauth | busy | done | error`, plus an "unsupported"
  state hinting a gateway restart.
  (`skills-hub-picker.test.tsx:1-62`; `mcp-setup.tsx:108-369,297-368`)
- **Hidden plumbing.** Bot Mode sessions are **unconditionally hidden** (no user
  preference): `session.create` always passes `hidden:true`, and a
  reconciliation sweep on load and reconnect hides group-room member sessions by
  id and each bot's plumbing sessions by exact title (`Bot Chat`, `Agent Inbox`,
  `Group: *`), failing closed on malformed remote-owner rows and giving new
  drafts a grace window. (`hide-bot-chats.test.ts:1-268`; `plugin.tsx:365`)
- **Routines (cron).** A right-hand tile scoped to the focused bot. Jobs are
  namespaced `[bot:<name>] <title>` (`BOT_TAG_RE`), with a structured schedule
  picker, a "Send results to" choice and a continuity checkbox. Legacy
  "delegated" routines are auto-paused on load and their toggle disabled behind
  a security notice. (`cron.tsx:73,87-101,131-170,1188-1327`)

### 1.3 Menus, in order

Desktop is the spec, so menu **order** is part of the contract.

**Bot row context menu** (`bot-row.tsx:307-443`): Open Bot Chat → *separator* →
Pin to top / Unpin → Hide / Unhide → *separator* → Edit… → Manage groups… →
Duplicate → *separator* → New chat with… → *separator* → Move to (submenu: each
section, *separator*, New section…, then Remove from section when assigned) →
*separator, only when not the default bot* → Delete (destructive; omitted
entirely for the default bot).

**Group row context menu** (`bot-row.tsx:550-567`): Open Group Chat →
*separator* → Delete group (destructive).

**Roster toolbar "New…" dropdown** (`roster-pane-toolbar.tsx:85-113`): New Bot
(`hubot`) → New Group (`organization`, disabled below two bots) → *separator* →
New section (`new-folder`).

**Roster toolbar filter dropdown** (`roster-pane-toolbar.tsx:132-220`): kind
filters (All bots and groups / Bots only / Groups only) → *separator* →
activity filters (Any activity / Active now / Recently active / Older) →
*separator, only when multi-gateway* → gateway filters (All gateways, then each
connection with its kind glyph and count) → *separator, only when a filter is
active* → Clear filters.

**Gateway-and-profile sidebar group menu** (`apps/desktop/src/app/chat/sidebar/gateway-groups.tsx:224-242`):
Rename group → Reset name (disabled with no alias) → Move up (disabled first) →
Move down (disabled last).

### 1.4 Copy, verbatim

Bot Mode carries its **own locale bundle**, registered by the plugin
(`ctx.i18n.register(BOTS_LOCALES)`, `plugin.tsx:103`) and living at
`apps/desktop/src/plugins/hermes-bots/i18n.ts` — *not* in
`apps/desktop/src/i18n/en.ts`. That is a porting trap: a Bot Mode string looked
up in the core `en.ts` will not be found. The English block starts at
`i18n.ts:269`; `ja`, `zh` and `zh-hant` follow at `:487`, `:704` and `:917`.

Roster (`i18n.ts:270-306`), verbatim:

```
search               'Search bots and group chats'
searchPlaceholder    'Search bots and group chats…'
newBotOrGroup        'New bot or group chat'
groupChats           'Group chats'
emptyTitle           'No bots yet'
emptyDesc            'Create your first bot.'
noMatchFilters       'No bots or group chats match these filters.'
clearFilters         'Clear filters'
allHidden            'All bots are hidden'
allHiddenDesc        'They keep working and retain their history.'
showHidden           'Show hidden bots'
noHiddenMatch        'No hidden bots match these filters.'
hiddenFromRoster     'Hidden from the roster'
pinned               'Pinned'
needsAttention       'needs attention'
needsInput           'Needs your input'
botsAndGroups        'Bots and group chats'
botsOnly             'Bots only'
groupsOnly           'Group chats only'
anyActivity          'Any activity'
activeNow            'Active now'
recentlyActive       'Recently active'
older                'Older'
gatewayRemoved       'Gateway removed'
onDemand             'On demand'
ready                'Ready'
statusUnknown        'Status unknown'
unavailable          'Unavailable'
retryNow             'Retry now'
waitingForGateway    'Waiting for the gateway connection… (remote gateways can take a few seconds; retries automatically)'
```

Four of the roster strings are interpolated and must keep their placeholders:
`noMatchQuery` renders `No bots or group chats match "<query>"`; `noMatchQueryOn`
appends ` on <gateway>`; `noMatchFiltersOn` renders `No bots or group chats match
these filters on <gateway>`; `rosterUnavailable` renders `Roster unavailable:
<reason>. If your gateway predates profiles.list, update Hermes and restart the
gateway.`

Bot (`i18n.ts:331-351`), verbatim:

```
newTitle                     'New bot'
editTitle                    'Edit profile'
editMenu                     'Edit…'
helpPromptPlaceholder        'What should this bot help with?'
descriptionHint              'Leave blank to generate from the bot's name and description.'
newChatWith                  'New chat with this bot'
openBotChat                  'Open Bot Chat'
duplicate                    'Duplicate'
duplicateFailed              'Duplicate failed'
deleteTitle                  'Delete bot and profile?'
removeFromAllGroups          'Remove from all groups'
createFirstHint              'Open the Bots pane and hit "New Bot".'
createFailed                 'Could not create the profile yet'
advanced                     'Advanced'
advancedHint                 'Advanced — model, skills, toolsets, SOUL.md'
advancedFailed               'Advanced configuration failed'
openAnotherChatUnsupported   'Update Hermes Desktop to open another Bot chat.'
remoteConnectionsUnsupported 'Update Hermes Desktop to chat with bots on other connections.'
chatEmpty                    'Say something to get started.'
kickoff                      'Hey, tell me about yourself!'
```

Group (`i18n.ts:376-438`) — the load-bearing subset, verbatim:

```
newTitle             'New group chat'
manageDesc           'A bot can join multiple group chats. Memberships sync to every machine.'
manageTitle          'Manage groups'
settingsTitle        'Group settings'
settingsDesc         'Rename the group or set a room picture. Members and history are kept.'
nameLabel            'Group name'
searchToAdd          'Search bots to add'
composerPlaceholder  'Say something — every bot in this group hears the room.'
attachHint           'Attach files — every responding bot sees them'
newThread            'New Thread'
reply                'Reply'
replyInThread        'Reply in thread'
activity             'Activity'
noActivityYet        'No activity in this turn yet.'
showActivity         'Show room activity'
hideActivity         'Hide room activity'
stop                 'Stop'
stopHint             'Stop this run — interrupts the member on turn and holds the rest'
holdReleaseHint      'Mention a paused bot or send @all resume to release them.'
needsYourInput       'A bot in this group chat needs your input'
disbandAction        'Disband'
disbanding           'Disbanding…'
disbandDone          'Disbanded'
disbandDescPrefix    'This removes the '
waitingForAnswer     'Waiting for your answer…'
roomWorking          'The room is working…'
everyoneMeta         'Every bot in the room'
commandApproval      'command approval'
removeAttachment     'Remove attachment'
dropToThread         'Drop to attach to this thread reply'
dropToRoom           'Drop to attach — every responding bot sees it'
```

Interpolated group strings: `allHeldStatus` renders `All <count> bots are
paused`; `heldMembersStatus` renders `Paused: <members>`; `memberCount` renders
`<count> bots`; `memberThinking` renders `<name> is thinking…`; `messageRoom`
renders `Message <group>`; `newThreadPlaceholder` renders `New thread in
<group>… (@name to direct, @everyone for all)`; `wantsToRunCommand` renders
`@<handle> wants to run a command:`; `asks` renders `@<handle> asks:`; and
`disbandDescSuffix` completes the disband body with ` grouping from its <count>
bots and clears the shared room log. The bots themselves and their per-group
sessions are kept.`

Sections (`i18n.ts:308-329`), avatar (`:353-374`), tools (`:440-444`) and cron
(`:446-483`) are equally complete in the source. Two cron strings a port must
not paraphrase: `botChatTarget` renders `<bot>'s chat (bot responds)` and
`continuity` reads `Continuity: each run sees the previous run's output (dedupe,
continue where it left off)`.

The **sidebar** group strings live in core `en.ts` instead, under
`sidebar.gatewayGroups` (`apps/desktop/src/i18n/en.ts:2375-2385`): `grouping` =
`'Gateway & profile'`, plus `rename`, `aliasLabel`, `aliasHint`, `resetName`,
`moveUp`, `moveDown`, `reorder`, `actions`.

### 1.5 States per surface

- **Roster pane** (`roster-pane-content.tsx:43-178`; `roster-pane.tsx:396-401`):
  loading (gated so a data-less, error-less transition cannot flash empty),
  error-with-no-roster (message plus Retry), true-empty, all-hidden (its own
  explainer plus "Show hidden bots"), filtered-to-nothing (four distinct copies:
  query, query-on-gateway, filters, filters-on-gateway),
  stale-but-showing-last-good (a `staleNotice` banner), and the normal sectioned
  list.
- **Bot row** (`bot-row.tsx:100-444`): pinned icon, hidden icon (dimmed
  eye-closed), attention badge (amber, with a hint per `AttentionClass`), age
  label, preview line (italic when the last speaker was the bot), grayscale and
  dimmed when the source is unavailable, drag opacity, active highlight, and a
  mood-driven face animation.
- **Group row** (`bot-row.tsx:483-528`): an availability badge reading "N of M
  available" with a disconnect glyph when a member's source is down.
- **Group timeline** (`group-chat-view.tsx`; `group-hold-status.tsx:16-60`;
  `group-activity.ts:87-129`): a hold-status banner (`allHeld` versus a
  per-member held list, unreachable members rendered `name (source)`), clarify
  and approval cards mirrored from a member's blocked session, and a per-message
  activity feed with twelve kinds — `queued, working, replied, passed,
  timed-out, failed, cancelled, settled, capped, delivered, held, stopped` —
  each with its own glyph and tone.
- **Routines pane** (`cron.tsx:1188-1327`): no-owner ("This bot has to appear in
  the roster first."), loading, read-failure card with Retry, stale banner,
  empty-with-filter-hint versus genuinely empty, per-row optimistic pause/resume
  with rollback, legacy-unsafe disabled switch plus warning strip, busy-disabled
  delete.
- **Avatar picker and pets** (`avatar-picker.tsx:278-284`;
  `pet.tsx:161-176,226-273`): image model unavailable / checking / ready,
  generating spinner, gallery loading / empty / no-match / scroll-to-load-more,
  and a failed frame extraction that toasts `petLoadFailed` and reverts the
  selection.
- **Create dialog** (`create-dialog.tsx`): name-taken inline error (distinct
  remote-target and local phrasings), a pre-name Capabilities state, a
  draft-materialising spinner, capability-catalog-failed ("needs a newer
  gateway"), skills-unsupported-build, MCP `needsSetup` disabled checkbox, the
  five per-server setup phases, submit-busy and a generic error banner.
- **Create-group dialog** (`create-dialog.tsx:1268,1310-1346`): at-cap disabled
  checkboxes once six are selected, empty-roster and no-match states, and a
  disabled Create with a "Pick at least 2 bots" tooltip.

### 1.6 Group rounds, because the room engine is the hard part

`group-rounds.ts` is the room coordinator. `parseGroupChatMentions` resolves
`@name`, `@handle`, quoted `@"title word"`, `@everyone` and `@all` against member
names, handles, titles and friendly forms (`:42-104`). `resolveGroupResponders`
picks who speaks this round: everyone, unless someone was @-mentioned since the
last user message, in which case only those, recomputed every round
(`:106-141`). `rotateGroupSpeakers` round-robins the leader by
`round % members.length` (`:143-152`). Holds implement stop and resume from raw
user text — an "all stop" directive holds everyone, and a direct non-stop
mention releases a hold (`:154-259`). `stopGroupThread` bumps the room epoch,
holds every member and sends `session.interrupt` to whoever is mid-turn
(`:352-414`). The driver `runGroupChatRounds` runs at most
`GROUP_CHAT_MAX_ROUNDS = 3` serial rounds, capped at
`GROUP_CHAT_MAX_MESSAGES = 10` and `GROUP_CHAT_MAX_CONTINUATIONS = 2`, harvests
stranded replies at each round boundary, and exits `settled` or `capped`
(`:421-594`; constants `group-chat.ts:1210-1216`).

`group-round-prompt.ts` builds each member's per-turn text: `formatGroupChatLine`
renders one room-log line, and `buildGroupChatTurnPrompt` assembles the peer
roster, the room delta since that member's watermark, and the participation
rules (reply only if you add something new, pass to stay quiet, mention to pull
someone in, never leak your private one-to-one chats).

`group-turns.ts` runs one member's turn against its own hidden per-group session
titled `Group: <roomId>`, fails closed on ambiguous resume errors (only a genuine
JSON-RPC 4007 falls through to `session.create`), holds the member's route
socket for the whole turn, retries once on a 4001 "session reaped mid-turn", and
polls with a base `GROUP_TURN_TIMEOUT_MS = 180000` extended while the session
reports `inflight` or `running` up to `GROUP_TURN_HARD_CAP_MS = 20 * 60000`. A
turn that times out anyway is recorded as `stranded` and harvested later rather
than lost.

**This engine is the Desktop client's; the gateway carries its own.** Every
scheduling decision above runs in the Desktop renderer. But "reimplement it or
ship read-only" is a false dichotomy: the gateway ships a second, bounded round
engine with the same caps (2 to 6 members, 3 rounds, 10 member messages, a
24-line delta: `gateway/hosted_room_discussion.py:23-28`), driven by an
in-process worker that runs "independently of Desktop connections"
(`tui_gateway/hosted_room_driver.py:92-96`). Its mention rule resolves handles
only (`hosted_room_discussion.py:37, 291-303`), it has no per-member holds (a stop is a
room-wide seq fence, `:559-569`), and it exposes the room as a typed log
(section 3.4). The two engines do not share rooms: Desktop's live in
`hermes-bots-groups`, the gateway's in `state.db`.

**The `Group: <roomId>` title namespace is shared on purpose.** The hosted
driver reuses Desktop's member-session title "so a local-to-hosted migration
keeps one transcript" (`tui_gateway/hosted_room_driver.py:6-7`;
`gateway/platforms/api_server_room_dispatch.py:17-19`), which is exactly why the
4122 fence in section 3.4 keys on it. An Android port on the hosted engine
therefore renders from the log and never prompts into those sessions. The epic
has chosen the hosted engine: [ADR 0004](../adr/0004-hosted-rooms-for-group-chats.md).

---

## 2. What is new since `3ca096de`

Bot Mode is **not** new. The plugin existed at the old pin; 32 of its files were
modified, 17 added and 1 deleted in the range
(`git diff --name-status 3ca096de..72a3277cd7 -- apps/desktop/src/plugins/hermes-bots/`).
31 commits touch the plugin directory. Three of those are `fmt(js): npm run fix
on merge` sweeps (`c076d653a2`, `e9bb6e86fb`, `bcef556b00`) and carry no
behaviour.

Grouped by capability:

**Roster sections (new capability).** User-made sections with drag-and-drop
filing landed as `3d0ac691af`, then `bd9955d529` (Escape cancels a rename, Enter
commits once) and `e9dd0bf5d5` (rename dialog, Undo delete, Esc-cancels-drag,
sections nested under gateways). New files: `user-sections.ts`,
`user-sections-ui.tsx`, `user-sections.test.ts`.

**Roster pane split (refactor).** `roster-pane.tsx` was decomposed into
`roster-pane-content.tsx`, `roster-pane-derivation.ts`, `roster-pane-dialogs.tsx`,
`roster-pane-groups.tsx`, `roster-pane-lifecycle.ts`, `roster-pane-sections.tsx`
and `roster-pane-toolbar.tsx` (`f9993d5be2`, "extract group member phases and
roster presentation"). A port reads the split files, not the old monolith.

**Group chat rooms.** Ordering became user-controlled (`9d66e76c5d`, new
`group-order.ts` / `group-order.test.ts`). Turn scheduling was reworked twice:
`fb5023950e` made a room answer "in the time of one bot, not the sum of all",
then `5d4aa4fcb2` reverted rooms to serial and kept only the push-woken turn
poll. Reply ordering was fixed to arrival order (`3a7bf7455f`). New
`group-round-members.ts` and `group-round-prompt.ts` carry the round model;
`group-chat-timeline.test.tsx` is new. Group tabs can now be handed off to
remote bots (`2599793271`). The create-group picker's rows were fixed to
truncate rather than scroll names out of view (`c65c79a4a8`, `d1f8c2ea11`).

**Attention / "needs you".** `ad08688bc6` derives group clarify/approval
attention from `$groupClarify` instead of duplicating it into `$groupNeedsYou`;
`613ec20304` binds pending group attention to its live room; `bc952a78ce`
integrates group attention with room ordering and working avatars; `e1c8764d18`
fences failure cues for retired members.

**Canonical Bot Chat resolution.** A cluster of fixes hardened the "one chat per
bot" rule: a row click always lands on the Bot Chat the row previews
(`6e7c7c7da9`, new `bot-row-opens-canonical-chat.test.ts`, replacing the deleted
`bot-row-keeps-closed-chat.test.ts`); lookup fails closed on zero rows
(`87d5e40f53`); an explicit open refreshes a busy chat (`9a017b9691`) and a
roster click that fronts an already-open tab refreshes its transcript
(`3ea71a47b3`); tabs caption a Bot Chat with the bot's name rather than the
literal "Bot Chat" (`209de12d5f`).

**Cross-connection / relay.** `2e542c92e6` and `bd6cc48b94` annotate the
resolvable canonical relay target for remote and aliased LOCAL `@mentions`.
`f43b976209` named the `bot_relay` per-attempt turn timeout and attempt ceiling
as constants so the Desktop mirror test reads them
(`tui_gateway/methods_bot_relay.py:26-31`). `e3ba651b6d` routes MCP OAuth through
client-local callbacks.

**Avatars and pets.** `ab6f4c5408` shows the focused bot's working "think" pose;
`acbe72ed1f` keeps pet selection rings inside the gallery. The standalone pet
surfaces — `apps/desktop/src/app/pet-generate/`, `apps/desktop/src/app/pet-overlay/`,
`apps/desktop/electron/pet-overlay-ipc.ts`, `apps/desktop/src/store/pet-*.ts` —
have **no commits in the range**. Pets predate the old pin and are unchanged.

**Cron.** `df4b3733ba` makes every `last_status` consumer render
`delivery_failed` explicitly (dashboard badge, Desktop inspector, `/cron list`).

**Sidebar `gateway-group*` (new files, new capability).** All five files are
added in the range: `77a5457343` organises Desktop sessions by gateway and
profile, `a529ecfeb5` nests profile sessions under gateway sections. This is the
sidebar-side counterpart to the roster's "nested under gateways" treatment and
is what makes multi-connection rosters legible.

### 2.1 Cross-checked against the contract verifier

A second agent verified the wire surface independently. Its findings, confirmed
here before use:

- `gateway/hosted_room*`, `tui_gateway/methods_bot_relay.py` and
  `tui_gateway/methods_groups.py` all existed at `3ca096de`. They are **not new
  surfaces**; the hosted-room protocol did not arrive in this range.
- No protocol bump: `gateway/hosted_rooms.py:23` and
  `gateway/hosted_room_peer.py:30` both still read `PROTOCOL_VERSION = 2`.
- `groups.capabilities` response keys and the `_METHODS` wire order are
  unchanged. What changed is internal: the inline list became a `_room_method()`
  decorator factory (`tui_gateway/methods_groups.py:184-216`).
- The `prompt.submit` hosted-room fence codes 4122 and 5122 are byte-identical
  at both pins.
- **`bot_relay.deliver` gained its live-session fast path in this range.** At
  `3ca096de` the handler had no `prompt.submit` path at all — delivery was always
  the CLI subprocess. Verified by reading the old file: it contains zero
  occurrences of `prompt.submit`. The new path returns a **placeholder** reply
  ("Delivered into … the reply will appear there"), *not* the agent's real reply
  text (`tui_gateway/methods_bot_relay.py:97-104`). Any Android caller of
  `bot_relay.*` must not render that placeholder as an answer.
- Adjacent and new in the range, though outside Bot Mode: `subagent.list`,
  `subagent.interrupt` and `subagent.tail`
  (`tui_gateway/methods_subagents.py:29,42,66`) and an `agents` en.ts namespace
  (`apps/desktop/src/i18n/en.ts:1592`). Worth an epic of their own; not this one.

---

## 3. The wire contract a client needs

### 3.1 The headline: Bot Mode has almost no private wire surface

Every backend call the plugin makes goes through one choke point,
`requestForBot(bot, method, params)` (`routing.ts:200-227`), which dispatches on
the bot's immutable owner: `host.requestProfile(route, method, scopedParams)`
for a source-scoped or remote row, otherwise the ambient
`host.request(method, params)`. Both are plain gateway JSON-RPC
(`apps/desktop/src/sdk/index.ts:1306,1407`).

The complete set of JSON-RPC method strings the plugin uses, all
**[GATEWAY]** — reachable by any authenticated JSON-RPC client:

| Namespace | Methods |
|---|---|
| Profiles | `profiles.list`, `profiles.create`, `profiles.describe`, `profiles.configure`, `profiles.set_asset`, `profiles.get_asset` |
| Sessions | `session.create`, `session.list`, `session.title`, `session.resume`, `session.interrupt`, `session.set_hidden` |
| Turns | `prompt.submit`, `clarify.respond`, `approval.respond` |
| Cron | `cron.manage` (action-dispatched: `list`, `add`, `pause`, `resume`, `remove`) |
| MCP | `mcp.catalog`, `mcp.servers.list`, `mcp.servers.add`, `mcp.servers.set_api_key`, `mcp.servers.test` |
| Media | `image.generate`, `image.attach_bytes`, `file.attach`, `pdf.attach`, `pet.gallery` |
| Escape hatch | `cli.exec`, `skills.manage` |
| Bot relay | `bot_relay.roster.sync`, `bot_relay.outbox.drain`, `bot_relay.deliver`, `bot_relay.reply` |

Event type strings the plugin subscribes to, all **[GATEWAY]**:
`session.reclaimed` (`plugin.tsx:546`), `bot_relay.outbox.pending`
(`relay.ts:480-482`), and `message.complete` / `error` as the turn-completion
signal (`group-turns.ts:268-320`).

**There is no `bots.*` namespace and no bots REST route.** A bot is a profile; a
Bot Chat is a session; a routine is a cron job. All of these are methods this
app's Gateway connection can already reach in principle.

### 3.2 The one contract that is neither RPC nor local: group-chat sync

Group rooms are **not** stored by a groups API. They are a client-authored blob
inside the `default` profile's `ui_meta`, under the key `hermes-bots-groups`
(`group-chat.ts:47`). The read is a plain `profiles.list {include_sessions:
false}`, then `profile.ui_meta['hermes-bots-groups']`, with an optimistic
concurrency check against `profile.ui_meta_revisions[...]` when the gateway
publishes that field (`group-chat.ts:839-852`). The write is
`profiles.configure`. The source comment calls this "the receive half of the
**client-only sync contract**" (`group-chat.ts:855-856`).

Consequences for Android:

- Group chats are portable — the transport is two methods this app can already
  call. **[GATEWAY]**
- But Android would be a **second writer** to a shared, CAS-guarded blob whose
  schema is defined only by Desktop's TypeScript types (`types.ts:160-188`). A
  malformed write corrupts every Desktop client on that gateway. Any port must
  treat `hermes-bots-groups` as a versioned wire contract, round-trip unknown
  fields untouched, and refuse to write when `ui_meta_revisions` is absent.

### 3.3 Bot-to-bot delivery, and whether Android can be a delivery target

`tui_gateway/methods_bot_relay.py` registers four methods, all **[GATEWAY]**:
`bot_relay.roster.sync` (replace this gateway's view of agents on other
connections; returns `{count}`), `bot_relay.outbox.drain`, `bot_relay.deliver`
(`{profile, message}` to `{reply}`) and `bot_relay.reply`. Per-attempt turn
timeout and attempt ceiling are named constants the Desktop client mirrors:
`TURN_ATTEMPT_TIMEOUT_SECONDS = 600`, `TURN_MAX_ATTEMPTS = 2`
(`methods_bot_relay.py:26-31`), with a message cap of
`MESSAGE_MAX_CHARS = 16000` plus attribution headroom
(`tools/bot_mode_dm.py:42`).

The critical question — what "cron and local DMs reach an open Desktop Bot Chat"
actually means — is answered at `methods_bot_relay.py:82-101`.
`bot_relay.deliver` looks through the **gateway's own live session table** for a
session whose `profile_home` matches the target profile and whose live title
equals `BOT_CHAT_TITLE` (`"Bot Chat"`, `tools/bot_mode_probe.py:31`). If it finds
one it lands the DM with `prompt.submit {session_id, text, queued: true}` —
queued, so a teammate's DM runs as the *next* turn and never interrupts or steers
a turn in flight. If it does not, it falls back to a one-shot subprocess
transport, `hermes -p <profile> chat -c "Bot Chat"`, run **by the gateway
process**.

**So "an open Desktop Bot Chat" is a misnomer in the client's favour.** The
delivery target is a *live gateway session titled `Bot Chat` for that profile*.
There is nothing Electron about it. An Android client that resumes the canonical
Bot Chat and keeps it live is, by this code, the delivery target. This is the
single most load-bearing finding for the port.

What *is* Desktop-owned is the **router**. `relay.ts` runs two loops inside the
renderer: a 60-second roster loop pushing each gateway the union of agents on
every other connection via `bot_relay.roster.sync`, and a 30-second drain loop
that calls `bot_relay.outbox.drain` on each sender, delivers via
`bot_relay.deliver` on the *target's own socket*, and posts the outcome back with
`bot_relay.reply`. A `bot_relay.outbox.pending` push event debounce-triggers an
immediate drain (250 ms) instead of waiting the poll out. Because the router
needs a socket per connection, cross-connection relay is blocked on a
multi-connection Android client, not on any missing method.

### 3.4 The hosted-room protocol, which the pinned Desktop does not use

`tui_gateway/methods_groups.py:18-22` registers **eighteen** methods, in wire
order: `groups.capabilities`, `groups.list`, `groups.create`, `groups.state`,
`groups.send`, `groups.rename`, `groups.log`, `groups.disband`,
`groups.replicate`, `groups.replica_state`, `groups.promote`, `groups.demote`,
`groups.stop`, `groups.retry`, `groups.approve`, `groups.peer.invite`,
`groups.peer.revoke`, `groups.peer.register`. Every one runs on the RPC thread
pool (`:23`; `tui_gateway/server.py:762-785`), and all share one error envelope
(`:184-215`): a `HostedRoomError` maps to the method's `room_code` with
`data.reason` only for `room_history_expired` and `authority_conflict`
(`gateway/hosted_rooms.py:185-198`); anything else maps to the method's 5xxx
`code`.

`groups.capabilities` (`:218-247`) is a feature-detect with narrow semantics.
`driver` is true only while the worker thread is alive (`:222-223`).
`persistent_process` is forced false under `HERMES_DESKTOP=1`
(`gateway/hosted_room_peer.py:250-251`), which is how this app spawns its
Managed SSH `hermes serve`. `room_link` has exactly two failure reasons, and
every non-storage failure (foreign profile unavailable, execution policy
unresolvable, approvals mode `off`, malformed secret) collapses into the second:
`durable_run_storage_required` or `gateway_roomlink_secret_unavailable`
(`:224-238`; the approvals-`off` refusal is `hosted_room_peer.py:255-258`).
The rest is `protocol_version` (2), `authority_gateway_id`, a `features` list
(`authority_epoch`, `coordinator_fencing`, `room_identity`, `monotonic_log`,
`idempotent_send`, `replayable_disband`, `typed_events`, `actor_identity`,
`log_replication`, `authority_takeover`), the eighteen `methods` and
`max_log_limit` (500).

**Capability gates.** Three coordinator writes need the live worker or return
4123 "Group Chat worker is unavailable. Restart the Hermes gateway and try
again.": `groups.create`, `groups.send`, `groups.disband` (`:30, 359-366,
383-395, 398-428`). `groups.stop`, `groups.retry` and `groups.approve` need it or
return 4115 (`:31, 431-464`). The read methods and `groups.rename` are db-only
and answer without a worker (`:346-356, 369-380, 485-495`).

**A better substrate for a phone, with qualifiers.** A monotonic replicated log
with idempotent send is what an app that loses the network wants, but:
`groups.send` accepts only an inert `message.user` `{text, thread_id}` and
stamps a server-owned actor `{"kind":"user","id":"desktop"}` (`:383-395`;
`tui_gateway/hosted_room_service.py:446-452`); there are **no push events**, so a
client polls `groups.state` and `groups.log`; `groups.send` always returns
`driver_started: true` and the truthful signal is `groups.state.driver_status`
(`:393-395`; `hosted_room_service.py:528-548`); coordinator writes need the
live worker; members are local profiles or peer targets on the authority
gateway (`gateway/hosted_room_discussion.py:24-27, 43-48`); and hosted rooms are
invisible to the pinned Desktop, because the gateway never reads
`hermes-bots-groups` and Desktop never calls `groups.*`.

**The 4122 fence.** The gateway already treats the pinned Desktop as an older
build. Any non-internal `prompt.submit` into a session titled
`Group: <room_id>` whose id is a hosted room returns 4122 "This room is managed
by its gateway. Update Hermes Desktop to continue it."; a probe failure returns
5122 (`tui_gateway/methods_prompt.py:206-243`;
`tests/tui_gateway/test_hosted_room_prompt_fence.py:1`). Nothing under `apps/`
handles 4122 or 5122. An Android hosted room and a Desktop blob room must never
share a `room_id`, because the fence keys on the shared `Group: <room_id>`
title.

**Divergence from the pinned Desktop code, not from the product.** Shipping on
this protocol diverges from the pinned Desktop *code*: no file under
`apps/desktop/src` calls `groups.*`. It does not diverge from upstream's stated
direction. Upstream names Desktop as the protocol's intended client (the
`desktop` actor id; `cancel_id` default `desktop-stop`, `:431-436`; the fence
text above), and `website/docs/user-guide/bot-mode.md:105` already claims
Desktop "catches up from the room's log". The epic has taken this decision:
see the Decision note at the top of this document and
[ADR 0004](../adr/0004-hosted-rooms-for-group-chats.md). It is ledgered as
drift, not mobile-adaptation.

### 3.5 What is genuinely Desktop-only

The plugin contains **no** `window.electron`, `ipcRenderer` or node `fs` use.
Everything goes through the SDK `host` door, so the Desktop-only set is exactly
the set of `host` capabilities with no gateway equivalent. Call counts are from a
grep across the plugin directory.

| `host` call | Uses | Verdict |
|---|---|---|
| `host.request`, `host.requestProfile` | 52 | **[GATEWAY]** — plain JSON-RPC, on the ambient socket or a routed one |
| `host.onEvent` | 11 | **[GATEWAY]** — the gateway event stream |
| `host.state` | 49 | Renderer store. Not a wire surface; Android needs its own state layer, not a translation |
| `host.notify`, `host.notifyError` | 45 | Toasts. Android has `PluginOs.notify` |
| `host.openSession`, `host.newChat`, `host.openWorkspace`, `host.focusOpenWorkspaceSession`, `host.setWorkspaceScope`, `host.setWorkspaceOwnerLabel`, `host.paneVisibility`, `host.activeSessionId`, `host.activeConnectionId` | 60 | App navigation and the workspace/tab model. Portable in concept; Android has no tabbed workspace, so these need mobile equivalents rather than translation |
| `host.profileRoutes`, `host.connections`, `host.getGateway`, `host.agents` | 28 | The **multi-connection registry**. Desktop holds many gateway sockets at once; this app's active row is its one connection. **This is the cross-connection blocker** |
| `host.retainProfileSocket`, `host.retainProfile`, `host.warmProfile`, `host.warmAgent` | 8 | Pooled-socket retention and hover pre-dial. Already feature-detected on Desktop and degrading gracefully, so safely omitted on Android |
| `host.deleteProfile` | 8 | **[DESKTOP-ONLY]** — an Electron-intercepted DELETE that tears the profile's pool and primary backend down *before* deleting, so a live backend cannot hold the directory open or respawn mid-delete. The SDK explicitly warns plugins to prefer it over `cli.exec ['profile','delete',…]`, which bypasses that interception (`apps/desktop/src/sdk/index.ts:685-695`) |
| `host.hidden`, `host.setPersistedSessionHidden`, `host.listPersistedSessions` | 6 | Thin wrappers over `session.set_hidden` / `session.list`. **[GATEWAY]** |
| `host.completeMcpOAuth` | 1 | A client-local OAuth callback; needs an Android equivalent |
| `window.hermesDesktop?.connections?.list` / `.onChanged` | 2 | **[DESKTOP-ONLY]** connection-registry push, used only to annotate orphaned group members after a connection is deleted. Already feature-detected and optional (`plugin.tsx:269-276,330-337`) |

Also Desktop-only: the **pet overlay**, a transparent always-on-top
click-through `BrowserWindow` driven over IPC channels `hermes:pet-overlay:*`
(`apps/desktop/electron/pet-overlay-ipc.ts:3,23-150`). The overlay window
"carries NO gateway connection" (`apps/desktop/src/store/pet-overlay.ts:13`).
That is a **non-goal** on Android, not a WIP-disabled control.

Pet *generation*, by contrast, is fully **[GATEWAY]**: `pet.generate`,
`pet.hatch`, `pet.select`, `pet.rename`, `pet.remove`, `pet.cancel`,
`pet.generate.status`, `pet.gallery` and `pet.info`, with live progress over the
`pet.generate.progress` and `pet.hatch.progress` events
(`apps/desktop/src/store/pet-generate.ts:136,344,364,401,483,495,517,601,611`).
Its client state machine is `idle | generating | ready | hatching | preview |
adopting | error | stale`, where `stale` means the backend predates the RPCs.

Two more that read as Electron but are not: the avatar **Upload** tab is a plain
`<input type=file>` with a 15 MB cap (`avatar-image.ts:37-57`), and the Skills
Hub picker is a same-origin-checked iframe plus `window.postMessage`
(`skills-hub-picker.test.tsx:1-62`). Neither needs an IPC bridge, though neither
translates directly to a phone either.

### 3.6 The sidebar's gateway groups need no wire surface at all

`gateway-group-model.ts:12-47` buckets the already-loaded sessions array by
`(connection_id, profile)`; `gateway-groups.tsx:64-96` re-nests groups that carry
a `connectionId` under a synthetic gateway parent, keeping same-named profiles on
different gateways strictly separate (`gateway-groups.test.tsx:41-119`). Aliases,
order and collapsed state persist client-side
(`gateway-group-preferences.ts`). There is no RPC of its own and no loading or
error state of its own — the parent `SidebarSessionsSection` supplies those
(`sessions-section.tsx:105-610`).

### 3.7 Cron, precisely

The Bot Mode Routines pane uses exactly one method, `cron.manage`, action-dispatched
over `list | add | pause | resume | remove` with params `name`, `schedule`,
`prompt`, `profile`, `repeat`, `continuity`, `deliver`, `include_disabled`
(`cron.tsx`). Desktop's *separate*, app-wide "Scheduled jobs" page instead uses
REST — `GET/POST/PUT/DELETE /api/cron/jobs[...]`,
`/api/cron/jobs/{id}/{pause,resume,trigger}`, `/api/cron/delivery-targets`,
`/api/cron/blueprints[/instantiate]` (`apps/desktop/src/api/cron.ts`). Both are
**[GATEWAY]**. A port that needs only the bot-scoped tile needs only
`cron.manage`.

Delivery has two targets (`cron.tsx:1077-1089`): `history` (default; the run
lands in its own run history and is never injected into a chat) and
`deliver: 'bot-chat'` (the result is injected into the bot's canonical Bot Chat
as a real message, costing the bot one agent turn per run). The `[bot:<name>]`
name prefix is how a job is scoped back to its bot on gateways that ignore the
`profile` parameter (`cron.tsx:73,87-91,103-170,215-230`). The fire-claim TTL is
**server-side only** and invisible to the client; no claim, lease or TTL field
appears in `RoutineJob` (`types.ts:250-269`).

---

## 4. Did the recent core refactors reach Desktop?

Short answer: **no**. The range is large — 6016 commits — and the core churn in
it is almost entirely internal. The contract verifier working in a separate
worktree owns this app's existing surface in depth; this section only records
what the Bot Mode epic must *not* worry about.

| Refactor | Commits | Client contract |
|---|---|---|
| `tui_gateway/server.py` module split | `0a1057c709`, `697f6fdc2f`, `f7c5b16fcc`, `d3b4d1cbe3`, `2e9a5e9f32`, `89fe549af4`, `4876472bbd`, `955d362176`, `f997235f1b`, `8c62bc058f`, `b0f87d7a7a` and dozens more | **Internal only.** `server.py` fell from 18,097 to 3,215 lines and about 25 topical modules were extracted. Dispatch is still the same shared `_methods` dict populated by `@method(name)`, now also via thin factories (`_rpc`, `_scoped_rpc`, `_pet_method`, `_billing_view`, `_session_method`) each module wires in through its own `register()`. Every spot-checked name still dispatches under an identical string |
| An "`event.py` split" | `ab2f4602de` | **Does not exist as posed, and internal.** The only new `event.py` is `gateway/platforms/event.py`, a leaf pulled verbatim out of `gateway/platforms/base.py` to break two import cycles. Its `MessageEvent` is the platform-adapter ingestion type (Discord, Slack), not the client event envelope. `tui_gateway/event_publisher.py` and `event_replay.py` are unchanged in identity at both pins |
| A `web_server.py` split | — | **Not found in this range** |
| Delegation normaliser | `1e24a8de39` | **Internal only.** `_resolve_child_fallback_chain` now routes through the canonical `get_fallback_chain()` instead of reimplementing validation. Governs the `delegate_task` agent tool's child fallback chain; not an RPC, event or route |
| Compression gate | `0f4587e336`, `c0aaa238f6`, `be58c276ee`, `40da0fd52f`, `ec4c1e0c98`, `dcdbc0093d` | **Internal only, and misnamed for our purposes.** This is LLM *context compaction*, not payload compression — no gzip or `Content-Encoding` work in range. Unifies anchored/real/rough token-usage authority and adds `delegation.compression_threshold_tokens` |
| Cron claim TTL | `193f05dec5`, `eb40bf060f` | **Internal only.** `cron/jobs.py` collapsed three duplicated 300-second literals into `FIRE_CLAIM_TTL_SECONDS` for the at-most-once cross-process fire-claim lock. No `cron.manage` contract change |
| Dispatch table / registration / event envelope | `8b01df963d`, `8b2ef359d1`, `b0f87d7a7a`, `9a5a78187a` plus the split | **Additive only** |

**Added JSON-RPC methods in range:** `session.control`, `session.control.read`,
`session.foreign.import`, `session.foreign.list`, `session.foreign.preview`,
`subagent.list`, `subagent.tail`.

**Added event type:** `session.control.update`.

**Removed or renamed:** none confirmed. A naive `@method("…")` string diff
suggests roughly a hundred removals, but every one hand-checked is still live in
`_methods` under the same name, registered through one of the new factories or
built dynamically — an artefact of the split, not a real removal.

So: nothing in the range breaks a Bot Mode port, and nothing in the range
delivers one either. The only Desktop-facing additions
(`session.control*`, `subagent.*`, `session.foreign.*`) are unrelated to bots.

### 3.8 The transport underneath, and the two shapes of event

One JSON-RPC 2.0 object per WebSocket text frame at `/api/ws`
(`hermes_cli/web_routers/chat_ws.py:541-554`; `tui_gateway/ws.py:239-381,316`).
There is no SSE and no long-poll for this surface. Authentication is a pre-accept
gate that closes with code 4401 before `ws.accept()` on a bad credential
(`chat_ws.py:137-149`) using the same bearer-or-cookie machinery as REST
(`hermes_cli/dashboard_auth/middleware.py:1-27`) — **nothing about it is
Electron-specific**. Immediately after accept the server pushes a
`gateway.ready` event whose payload carries `skin`, `change_events`,
`heartbeat` and `replay_epoch` (`tui_gateway/ws.py:272-277`).

Event envelope (`tui_gateway/server.py:573-579`):

```
{"jsonrpc":"2.0","method":"event","params":{"type":<name>,"session_id":<sid or "">,"payload":{…}}}
```

Two shapes matter, and the difference is the port's biggest structural risk:

- **Session-scoped** events carry a non-empty `session_id` and are stamped with
  a `seq` (`tui_gateway/event_replay.py:39-60`). Replay is
  `session.events.since {session_id, last_seen}` returning
  `{events, latest_seq, truncated, count, epoch}`
  (`tui_gateway/methods_session.py:2131-2144`); the ring holds 512 events across
  64 sessions and `truncated: true` means refetch history rather than trust
  replay (`event_replay.py:24-25,74-79`). `epoch` is regenerated per process
  start so a gateway restart is detectable, and is also carried in
  `gateway.ready`.
- **Session-less global** events carry `session_id == ""`, are deliberately
  *not* stamped or buffered, and are re-fetchable through their own RPCs
  (`event_replay.py:46-49`). The bot-relevant ones are polled and broadcast by
  `tui_gateway/change_watcher.py:177-184`: `pet.changed`, `cron.changed`,
  `sessions.changed`, `platforms.changed`, `pairing.changed`, and
  `bot_relay.outbox.pending` — the last polled every second specifically so a
  queued DM envelope reaches a push-triggered drain quickly.

**There are no `groups.*` push events.** A hosted-room client polls
`groups.state` / `groups.log {since_seq}`.

`cron.manage` is registered at `tui_gateway/methods_tools.py:1027` via the
`_scoped_rpc` factory, which is why a naive `@method("…")` grep misses it.

### 3.9 What actually decides the delivery target

`_claim_active_session_slot` writes
`metadata = {"live_session_id": …, "bot_live_delivery_consumer": True}`
**unconditionally**, for any session, regardless of the client's declared
`surface` (`tui_gateway/session_lifecycle.py:27-35`). `surface` is a
client-supplied string echoed back from `session.create` / `session.resume`'s
`source` parameter (`tui_gateway/session_workdir.py:190-192`;
`tui_gateway/server.py:1385`); the only thing gated on `surface == "desktop"` is
`track_liveness`, not the delivery flag. `find_canonical_live_owner`
(`tools/bot_live_delivery.py:27-54`) then looks for exactly that flag on the
session titled `Bot Chat`.

So the mechanism is: *whichever authenticated client currently holds a live
session titled `"Bot Chat"` for that profile, and has taken a real turn on it,
is the delivery target.* Desktop wins today only because Desktop is the client
people leave open.

Two guard rails worth writing into the epic:

- When **no** client holds it, delivery does not fail: it falls back to a
  headless one-shot CLI turn inside the gateway
  (`tools/bot_mode_dm.py:358-399`). Android's absence never blocks a DM or a
  cron fire; Android's presence can *capture* one.
- `tools/bot_live_delivery.py:29-32` states capability advertisement is
  mandatory so that old clients "must not receive work they cannot consume".
  We did **not** trace what constitutes that advertisement beyond the metadata
  flag. A slice that makes Android the delivery target must settle this first,
  or it risks silently swallowing a teammate's DM.

### 3.10 The behavioural contract is generated, not stored

The legacy `## Messaging other agents` block was deleted from every profile's
`SOUL.md` by config migration 41
(`tests/agent/test_soul_legacy_bot_protocol.py:1-3,29-44`). The contract is now
built per turn by `_build_section()`
(`tools/bot_mode_probe.py:193-228`), injected only into a session titled exactly
`Bot Chat` (`:29-31`), gated on a profile carrying `ui_meta['hermes-bots']` in
`profile.yaml` (`:102-114`), and stamped with a `capability_fingerprint` epoch so
it rebuilds when skills, toolsets, MCP, SOUL or the roster change (`:252-333`).

Its load-bearing clauses, for anyone designing the mobile copy: `message_agent`
is **fire-and-forget** — it delivers with attribution prefixed, returns an
acknowledgement immediately and never returns the reply; the reply arrives later
as a background-process completion notification. A bot must compose each message
itself, never forward the user's words verbatim, and never reveal private
one-to-one chat content. "Ask <name>" is a handoff to exactly one clearly
relevant teammate. An incoming `Message from <name> (@<handle>):` is a teammate
talking, not the user, and a pure FYI may be left unanswered rather than
ping-ponged.

---

## 5. What Android already has

Paths in this section are relative to this repository's worktree root.

### 5.1 The plugin SDK is the right shape but is missing the one door Bot Mode lives on

`app/src/main/kotlin/com/hermesagent/mobile/plugins/PluginAreas.kt:8-39` already
declares every Desktop area constant Bot Mode uses, including `PANES_AREA`,
`CHAT_EMPTY_AREA`, `PALETTE_AREA`, `Composer.MIDDLEWARE` and
`Composer.AT_COMPLETIONS`. `ContributionRegistry.kt:16-119` is
`StateFlow`-backed, `PluginLoader.kt:22-89` isolates a failing plugin as
`PluginStatus.Error` without crashing, and `PluginsScreen.kt:49-90` already ships
live enable/disable through `PluginStore.setPluginEnabled`.

But `PluginContext` (`HermesPlugin.kt`) exposes only `register`, `registerMany`,
`onDispose`, `rest`, `socket`, `os` and `storage`. Desktop's Bot Mode makes
**zero** `ctx.rest` calls and about 260 `host.*` calls. There is no
`host.request` (gateway JSON-RPC), no `host.onEvent`, no navigation door and no
`ctx.i18n.register`. **Bot Mode cannot be expressed against today's Android
PluginContext at all.** That is slice 1, and everything else depends on it.

Two further gaps of the same kind: `PANES_AREA` is accepted but has **no
renderer** (ADR 0003 §3), so Bot Mode's primary surface — a docked "Bots" pane —
has nowhere to draw; and `PluginSocket.kt:27-46` returns a no-op disposer
(deferred under issue #73), so a plugin cannot stream today.

The Relay plugin (`plugins/relay/`) is a good template for the *shape* — a
`ROUTES_AREA` full-screen destination plus a `SIDEBAR_NAV_AREA` entry row
(`RelayPlugin.kt:78-113`) — but it speaks REST under
`/api/plugins/hermes-plugin-relay/…` only (`RelayPluginRepository.kt`), never the
gateway JSON-RPC layer. Its `RelayChannelRow` / `RelayTranscriptRow` /
`RelaySenderKind` (Human, Agent, System) at `RelayViewState.kt:16-60` are
nonetheless the closest existing multi-speaker transcript shape in the app.

### 5.2 The grep result: there is no bot concept here today

- **bot** — zero genuine whole-word hits anywhere under `app/src`.
- **room** — zero; every hit is prose ("room for X").
- **group** — 54 files, all false positives: calendar and session grouping
  (`SessionGrouping.kt`, `SidebarGrouping`, `ProjectGrouping`), model-family
  grouping (`ModelVisibility.kt`), `NotificationCompat` groups, `RadioGroup`.
- **pet**, **avatar** — zero. `HermesProfile.hasAvatar` (`ProfileModel.kt:56`) is
  parsed from `profiles.list`'s `has_avatar` and has **no consumer**; no image is
  ever fetched or drawn from it.
- **roster** — only the *profile* roster (`ProfileRoster.kt`) and a comment
  calling `BundledPlugins.ALL` a roster.
- **cron** — two hits, both in `ConnectionsCopy.kt:15,53`, where product copy
  already names cron jobs as something this app does not ship.
- **handoff**, **canonical** — all about turn/session lifecycle and durable
  identity; unrelated.

### 5.3 The gateway client can reach it, with one structural caveat

`CorrelatedGatewayRpc` (`data/gateway/GatewayRpc.kt:106-240`) is JSON-RPC 2.0
over one authenticated WebSocket — the same transport Desktop uses. Adding a new
method call is a one-line change at any call site
(`connection.client.request("new.method", params)`).

Adding a new **event type** is two edits: the type string must join the fixed
allow-list `SUPPORTED_EVENTS` (`GatewayRpc.kt:219-238`) or the frame is silently
dropped, and a branch must join `GatewaySessionRepository.applyEvent`
(`GatewaySessionRepository.kt:3889-4108`).

The caveat is exactly the fault line section 3.8 describes. `applyEvent` begins
`event.runtimeSessionId ?: unscopedRuntimeId ?: return false`
(`GatewaySessionRepository.kt:3907`) — **every event is routed by an active
runtime session id.** Every bot-relevant global event
(`bot_relay.outbox.pending`, `cron.changed`, `pet.changed`, `sessions.changed`)
is session-less by design. They cannot flow through `applyEvent` and need a
parallel dispatch path. This is the one place a Bot Mode port is structural
rather than additive, and it is worth doing once, properly, in its own slice.

### 5.4 Reuse verdicts

| Desktop piece | Verdict | Android anchor |
|---|---|---|
| Bot roster list | **EXTEND** | `data/profiles/ProfileRosterCache.kt`, `ProfileModel.kt` — epoch-guarded single-authoritative-answer caching is already the right pattern; missing the bot presentation model and a rail |
| Bot Chat (a chat bound to a bot) | **EXTEND** | `data/gateway/GatewaySessionRepository.kt` + `SessionCache`. Concurrent per-runtime turns and foreground isolation already exist. Missing: a bot identity discriminator on `SessionSummary` (`source` is a client tag, not a chat-type) |
| Group chat timeline | **EXTEND (UI only)** | `plugins/relay/RelayViewState.kt:16-60` for the multi-speaker row shape. Reuse the rows, not the REST-polled transport |
| Avatars / identity colour | **REUSE + NEW** | `data/profiles/ProfileColor.kt` gives deterministic colour and initials today. An avatar *image* pipeline is NEW — `hasAvatar` is parsed and unconsumed, and `GatewayImageLoader.kt` is not wired to profiles |
| Unread / attention badge | **REUSE** | `SessionStatus` / `displayStatus()` / `isUnread()` (`SessionModel.kt:17-70`) plus the `StatusDot` composable already resolve dot priority correctly |
| Cron list | **NEW** | Nothing exists; product copy already admits the gap |
| Plugin-contributed pane | **REUSE for a route, NEW for a pane** | `ROUTES_AREA` + `SIDEBAR_NAV_AREA` work end to end today (`RelayPlugin.kt:78-113`). `PANES_AREA` has no renderer |

---

## 6. Porting decomposition

**Epic: Port Hermes Desktop Bot Mode to Android (roster, Bot Chat, group chats,
routines)**

The epic's shape is dictated by three findings. Bot Mode has no private wire
namespace, so almost nothing is blocked on the backend. Bot Mode is a *plugin*,
and this app's plugin SDK cannot yet express it, so the first slice is SDK work
with no user-visible surface. And Bot Mode's group engine lives in the Desktop
renderer, so participating in a room is a reimplementation rather than a
translation — which is why reading a room and joining a room are two slices.

Two things are **non-goals**, omitted rather than shipped disabled: the pet
overlay window (an Electron `BrowserWindow` this platform will never have) and
`host.deleteProfile`'s teardown-routed delete (Android holds no process
authority over a remote profile's backend). Everything else Desktop offers that
a slice does not implement ships **visible and disabled behind the WIP marker
chip**.

Every slice's acceptance evidence assumes the house baseline: fixed clock,
timezone and locale for anything calendar-shaped; coroutines on virtual time with
injected timing; Compose journeys in `src/testDebug/` under Robolectric with the
v2 `createComposeRule`; and, for any slice touching `ui/`, a
`docs/parity/<surface>.md` carrying `## Visual report` and `## Divergences`
sections plus a rendered Desktop-versus-Android side-by-side per
`docs/workflows/review-desktop-parity.md`.

### Slice 1 — Plugin SDK: the gateway JSON-RPC, event and i18n doors

**Blocked?** No. Pure Android work, and everything else depends on it.

**Desktop source.** `apps/desktop/src/contrib/plugin.ts:60-101`;
`apps/desktop/src/sdk/index.ts:582,1306,1407`;
`apps/desktop/src/plugins/README.md`.
**en.ts keys.** None — no user-visible surface.
**Gateway methods.** None new; this slice *exposes* the existing connection to a
plugin.

**What it builds.** `PluginContext` gains a `host` door: a gateway JSON-RPC
`request(method, params)`, an event subscription, and `i18n.register` for a
plugin-owned locale bundle. The path-guarding and `UnavailableOnGateway`
mapping `PluginRest` already has must extend to it, and a disabled plugin's
in-flight requests must stop at `onDispose` rather than outliving it.

**Mobile adaptation.** Desktop's `host` also carries navigation and a
multi-connection registry. This slice ships only the request, event and i18n
doors; navigation waits for slice 3, where there is a screen to navigate to, and
the connection registry is out of the epic.

**Ships WIP-disabled.** Nothing — no surface.

**Acceptance evidence.** Unit tests for the request door on both connection legs,
for namespace guarding, and for disposal cutting a request off mid-flight on
virtual time. An ADR 0003 amendment recording the added contract rows. No parity
page.

### Slice 2 — Session-less gateway events reach the app

**Blocked?** No, but it is the slice most likely to surface an upstream surprise;
land it before anything depends on live bot data.

**Desktop source.** `tui_gateway/server.py:573-579`;
`tui_gateway/event_replay.py:24-25,39-60,74-79`;
`tui_gateway/change_watcher.py:177-184`; `tui_gateway/ws.py:272-277`;
`tui_gateway/methods_session.py:2131-2144`.
**en.ts keys.** None.
**Gateway methods.** `session.events.since`, plus the global event types
`cron.changed`, `pet.changed`, `sessions.changed`, `bot_relay.outbox.pending`.

**What it builds.** A dispatch lane for events whose `session_id` is empty,
parallel to `GatewaySessionRepository.applyEvent`, with the matching
`SUPPORTED_EVENTS` additions, and handling for `gateway.ready`'s `replay_epoch`
so a gateway restart invalidates cached watermarks instead of silently resuming.

**Mobile adaptation.** Global events are *hints*, never data — each triggers a
refetch through its own RPC, exactly as upstream intends
(`event_replay.py:46-49`). Refetches are lifecycle-scoped
(`LifecycleResumeEffect`), never a bare timer.

**Ships WIP-disabled.** Nothing.

**Acceptance evidence.** Unit tests that a session-less event does not reach
`applyEvent` and does reach the global lane; that an unknown type is dropped
without throwing; that a changed `replay_epoch` clears watermarks; and a
virtual-time test that a resumed surface refetches once per hint, not per frame.

### Slice 3 — The Bots roster, read-only

**Blocked?** No.

**Desktop source.** `roster-pane-content.tsx:43-178`, `roster-pane-derivation.ts`,
`roster-pane-sections.tsx`, `roster-pane-toolbar.tsx:85-220`,
`bot-row.tsx:100-444`, `data.ts:52-157,634-710`, `hidden-bots.ts:23-31`,
`user-sections.ts`.
**en.ts keys.** `apps/desktop/src/plugins/hermes-bots/i18n.ts:270-306` (roster)
and `:308-329` (sections), verbatim, from the *plugin* bundle — not core `en.ts`.
**Gateway methods.** `profiles.list`.

**What it builds.** A bundled `bots` plugin contributing a full-screen route and
a sidebar nav entry, rendering the roster: search, kind and activity filters,
user sections, pinned and hidden treatment, the four attention classes, the age
label and preview line, and all seven empty, error and stale states from 1.5.

**Mobile adaptation.** Desktop's docked pane becomes a full-screen destination
with a back affordance, because `PANES_AREA` has no renderer. Right-click becomes
long-press. Drag-and-drop section filing becomes an explicit "Move to" sheet — a
drag inside a scrolling list is a known phone failure. 48dp targets and a
`contentDescription` on every icon-only control.

**Ships WIP-disabled.** The gateway filter group (single connection), and the
whole "New…" dropdown — New Bot, New Group, New section.

**Acceptance evidence.** Unit tests for section derivation, filter composition,
and attention classification, including that rate-limit, 5xx and timeout never
badge. A Robolectric journey over the seven states.
`docs/parity/bots-roster.md` with a rendered side-by-side and a verbatim copy
diff against `i18n.ts` at the pin.

### Slice 4 — Bot Chat: one canonical chat per bot

**Blocked?** **Partly.** The open and resume path is plain `session.list` /
`session.resume`. Making Android the *delivery target* for cron fires and
teammate DMs depends on the capability advertisement at
`tools/bot_live_delivery.py:29-32`, which this audit did not trace. Land the read
path first and gate delivery-target behaviour on that answer.

**Desktop source.** `canonical-chat.ts:48,68-79,184-235,388-412`;
`canonical-chat-registry.test.ts:1-27,176-203,248-308`;
`bot-row-opens-canonical-chat.test.ts:45-116`; `hide-bot-chats.test.ts:1-268`.
**en.ts keys.** `i18n.ts:331-351` (bot), especially `openBotChat`, `chatEmpty`
and `kickoff`.
**Gateway methods.** `session.list {title:'Bot Chat', include_hidden:true}`,
`session.resume`, `session.create {hidden:true}`, `session.title`,
`session.set_hidden`, `prompt.submit`.

**What it builds.** Name-not-pointer resolution on every open; adopt-before-mint
on a title collision; **fail closed** on a failed lookup, never read as "no chat
exists"; the compression-lineage tip match; unconditional `hidden: true` on
create plus the reconciliation sweep for the `Bot Chat`, `Agent Inbox` and
`Group: *` titles, failing closed on malformed rows and granting new drafts a
grace window.

**Mobile adaptation.** Desktop fronts an open tab; Android has no tabs, so the
equivalent is navigate-and-force-refresh, preserving the busy-chat re-open that
repaints fresh rows. The sweep must not fight `SessionCache`'s merge rule — a
sweep is a partial refresh that layers, and only an explicit tombstone removes a
row.

**Ships WIP-disabled.** The intro kickoff (it belongs to the create path in a
later slice), and any delivery-target claim until the advertisement question is
settled.

**Acceptance evidence.** Unit tests for each hardening wave named in
`canonical-chat-registry.test.ts`: zero rows fails closed, a collision adopts,
the lineage tip matches, a click-path open mints no kickoff. A Robolectric
journey for open, busy re-open and sweep. `docs/parity/bot-chat.md`.

### Slice 5 — Routines: the bot-scoped cron tile

**Blocked?** No.

**Desktop source.** `cron.tsx:73,87-101,103-170,215-248,418-470,480-589,654-687,940-1118,1188-1327`;
`types.ts:250-269`; `cron-rpc-error.test.ts`; `cron-load.test.ts`.
**en.ts keys.** `i18n.ts:446-483` (the bot-scoped block) plus core
`apps/desktop/src/i18n/en.ts:2172-2262` for shared state and schedule labels.
**Gateway methods.** `cron.manage` with `list | add | pause | resume | remove`
(`tui_gateway/methods_tools.py:1027`).

**What it builds.** The Routines list scoped to a bot, using the `profile`
parameter where the gateway honours it and the `[bot:<name>]` name prefix as the
fallback where it does not; the structured schedule picker (once, hourly, daily,
weekdays, weekly, monthly, interval, advanced) composing the same expressions
Desktop emits; the two delivery targets; the continuity checkbox; optimistic
pause and resume with rollback; and the legacy-routine auto-pause with its
security notice.

**Mobile adaptation.** The right-hand tile becomes a sheet or a section on the
bot's screen. The read-only detail dialog stays read-only — it renders from the
list payload with no extra RPC, which is worth preserving on a phone.

**Ships WIP-disabled.** Full job editing (Desktop's app-wide Scheduled jobs page
owns that over REST and is out of this slice) and "trigger now".

**Acceptance evidence.** Unit tests for `composeSchedule` and `scheduleLabel`
round-tripping every frequency under a fixed clock, timezone and locale; for
`[bot:]` scoping on both gateway generations; for optimistic rollback on virtual
time; and for the non-string RPC `error.name` coercion `cron-rpc-error.test.ts`
pins — copy rather than mutate a frozen error, and preserve the original as its
cause. A Robolectric journey over the six pane states.
`docs/parity/bot-routines.md`.

### Slice 6 — Group chats, read-only

**Decision applied.** [ADR 0004](../adr/0004-hosted-rooms-for-group-chats.md):
this slice reads the gateway's hosted rooms, not `hermes-bots-groups`.

**Blocked?** **No.** Every method it needs is db-only and answers without the
worker (`tui_gateway/methods_groups.py:184-215, 346-356, 369-380, 489-495`). A
gateway that answers `groups.capabilities` with -32601 renders the section
empty with a "needs a newer gateway" state.

**Desktop source (UI treatment only).** `group-chat-view.tsx`;
`group-activity.ts:87-129`; `group-hold-status.tsx:16-60`;
`bot-row.tsx:483-528,550-567`; `group-order.ts`; `types.ts:160-188` for the
shape Desktop draws, not for the wire.
**en.ts keys.** `i18n.ts:376-438` (group), plus the roster's `groupChats`.
**Gateway methods.** `groups.capabilities`, `groups.list`, `groups.state`,
`groups.log` (`tui_gateway/methods_groups.py:218-247, 346-356, 369-380,
489-495` @ `72a3277cd7`).

**What it builds.** A capability gate on `protocol_version == 2` and `driver`;
the room list from `groups.list` (ordered `updated_at DESC`, paged by
`next_offset`); the room header from `groups.state.room`; the transcript from
`groups.log` paged on `has_more` from a per-room cursor, rendering the twelve
produced event kinds in the ADR's table (user and member bubbles by thread,
terminal rows, `room.activity` dividers, stop and rename lines) and a generic
system line for any unknown kind; the working/blocked indicator from
`driver_status`; polling at the ADR's cadence with no push events; and the
group row context menu in Desktop's order (Open Group Chat, separator, Delete
group, with Delete disabled behind the WIP chip in this slice).

**Mobile adaptation.** Polling stops in the background and the room catches up
on return. A `since_seq is ahead` error resets the cursor rather than failing
the room.

**Ships WIP-disabled.** Every write control: the composer, Stop, thread
replies, attachments, Disband, Group settings, Manage groups, retry and
approve. Desktop's holds banner, clarify card and "N of M available" badge
render disabled behind the WIP chip too, because the hosted protocol has no
source for them.

**Not rendered.** Desktop's `hermes-bots-groups` blob rooms. The ledger records
this as a `drift` row with evidence `#192 — not read by decision (ADR 0004);
may return as a read-only mirror`.

**Acceptance evidence.** Unit tests over synthetic, host-free `groups.log` and
`groups.state` fixtures: contiguous-seq paging on `has_more`, a short page
under the byte cap, unknown-kind tolerance, `room_history_expired` tombstoning,
`authority_conflict` read-only marking, and cursor reset. A Robolectric journey
rendering a room with a `working` driver, a `blocked` room with a pending retry,
and a disbanded tombstone. `docs/parity/bot-group-chat.md` whose first
`## Divergences` row is the hosted-versus-blob integration difference classified
`drift` (#193), whose blob-rooms row is `drift` with the `#192` evidence above,
and in which every hosted-caused row is `drift` with an issue number; the
disabled controls are `omission` rows carrying `coming soon`. Nothing
hosted-caused is classified `mobile-adaptation`.

### Slice 7 — Group chats, participating

**Decision applied.** [ADR 0004](../adr/0004-hosted-rooms-for-group-chats.md):
Android participates through the hosted protocol. No round engine, no
`hermes-bots-groups` write, no `prompt.submit` into a `Group:` session.

**Blocked?** **On slice 6 only**, plus two narrow unknowns that do not block a
start: whether a local member turn under `approvals.mode: off` bypasses
approval so the approval card is unreachable on that profile (ADR 0004 open
question 1), and a rendered Desktop capture of the room surface for the parity
page. `ui_meta_revisions` CAS and `profiles.configure` write semantics no longer
matter to this slice.

**Desktop source (UI treatment only).** `group-chat-view.tsx` (composer, Stop,
threads); `create-dialog.tsx:1116-1352` (the create-group picker);
`group-hold-status.tsx:16-60` for what the banner looks like, not for its
semantics.
**en.ts keys.** The write half of `i18n.ts:376-438`: `composerPlaceholder`,
`stop`, `newThreadPlaceholder` and the `disband*` family are used as written;
`stopHint`, `allHeldStatus`, `heldMembersStatus` and `holdReleaseHint` describe
per-member holds the hosted engine does not have, so each is a `drift` row with
an issue number rather than a verbatim reuse.
**Gateway methods.** `groups.create`, `groups.send`, `groups.stop`,
`groups.rename`, `groups.disband`, `groups.retry`, `groups.approve`
(`tui_gateway/methods_groups.py:359-366, 383-395, 398-464, 485-488` @
`72a3277cd7`), all needing the live worker except `groups.rename`.

**What it builds.** Create with a client-minted `room_id` (required; the gateway
never mints one, `gateway/hosted_rooms.py:206-207`), a name, and 2 to 6 local
members mapped by `member.profile == HermesProfile.name`, reading `latest_seq`
from `groups.state` afterwards because the create result omits it
(`gateway/hosted_rooms.py:862-866`). Idempotent send: one `event_id` minted and
persisted with the draft, reused on every retry, replaced only on a 4111
"different content"; `thread_id` per thread. Stop as a room-wide fence with
the `cancelled` count. Rename with its own `event_id`. Disband with the 5114
"still stopping" retry. Retry and approve from `driver_status.pending_actions`,
the approval card offering exactly `once` and `deny`. Polling tightens to 1 to
2 s after a send and while `working` or `blocked`. Error handling per the ADR's
table: 4123 keeps the draft and disables writes until capabilities report
`driver: true` again; `authority_conflict` (4111/4113, or 5116 from stop)
marks the room read-only; `room_history_expired` tombstones it.

**Mobile adaptation.** The phone never drives a round. A send is one RPC; the
gateway schedules the turns and the phone catches up from the log after sleep.

**Ships WIP-disabled.** Attachments (`catalog.attachments` is false and the
payload is `{text, thread_id}`), room pictures, Manage groups and membership
edits (members are frozen at create), per-member holds, the clarify card, and
peer members with route status in rooms created on another gateway.

**Never.** `prompt.submit` into a `Group: <room_id>` session (4122 fence,
`tui_gateway/methods_prompt.py:206-243`); reusing a Desktop `roomId`; the seven
replication and peer methods (non-goals per ADR 0004).

**Acceptance evidence.** Virtual-time unit tests: idempotent retry keeps its
`event_id` and a 4111 conflict mints a new one; the stop fence renders every
older user message as fenced; 4123 preserves the draft and re-enables on
`driver: true`; `authority_conflict` and `room_history_expired` transitions;
approval and retry actions consume `pending_actions` exactly. A Robolectric
journey: create, send, watch two `message.member` events arrive from a fixture
log in seq order, stop, see the divider. `docs/parity/bot-group-chat.md` updated
with every changed copy string above as its own `drift` row with an issue
number, and stating which caps the hosted engine enforces (3 rounds, 10
messages, 6 members) against Desktop's.

### Slice 8 — Avatars, pets and bot identity

**Blocked?** **Partly** — the `profiles.set_asset` and `profiles.get_asset`
payload shapes were not read in this audit and must be confirmed first.

**Desktop source.** `avatar-picker.tsx:56-295`; `avatar.tsx`;
`avatar-image.ts:37-57`; `pet.tsx:12-76,127-176,226-278`;
`apps/desktop/src/store/pet-generate.ts:83,136,401,517,611`.
**en.ts keys.** `i18n.ts:353-374` (avatar) and core `en.ts:1669-1698`
(`generatePet`) plus `en.ts:1654-1667` (`chat.pets`).
**Gateway methods.** `profiles.get_asset`, `profiles.set_asset`,
`image.generate`, `pet.gallery`, `pet.info`; optionally `pet.generate`,
`pet.hatch` and `pet.select` with the `pet.generate.progress` and
`pet.hatch.progress` events.

**What it builds.** The four-tab picker in Desktop's order — Bot, Generate,
Upload, Pet — the blob-face-from-name fallback with Lock/Unlock and Randomize,
the 15 MB upload cap, sprite frame-0 extraction for a pet avatar, and at last a
consumer for `HermesProfile.hasAvatar`, which this app parses and ignores today.

**Mobile adaptation.** Upload uses the bounded Android read plus the staged
Gateway byte handoff already established for attachments; no `content://` URI or
device path reaches wire text. Frame extraction is a bounded, cached bitmap
decode with an explicit concurrency ceiling, not an unbounded gallery scroll.

**Ships WIP-disabled.** Pet *generation* (`pet.generate` / `pet.hatch`): the
gallery and adoption ship, generation renders behind the WIP chip in the first
pass because its progress-event lane depends on slice 2 landing cleanly. The pet
**overlay** is omitted outright as a non-goal.

**Acceptance evidence.** Unit tests for deterministic blob-face derivation from a
name, the upload cap, and the frame-0 crop under a fixed decoder. A Robolectric
journey across all four tabs including the image-model-unavailable state.
`docs/parity/bot-avatars.md`.

### Out of the epic

**Cross-connection bots and the bot relay.** `relay.ts` needs one live socket per
registered connection, and `host.profileRoutes`, `host.agents` and
`host.getGateway` have no Android equivalent because this app's active row *is*
its one connection (`AGENTS.md`: "Connections are a list, and the active row is
the app's one connection"). The four `bot_relay.*` methods are perfectly
reachable; the multi-connection client that would drive them is a Gateway-layer
feature, not a Bot Mode feature, and deserves its own epic. Until then a roster
row owned by another connection is not rendered at all — an **omission**, which
must be recorded as one on the parity page rather than quietly dropped.

---

## Not covered

This audit ran to a hard deadline. What it did **not** establish:

- **The capability-advertisement handshake.** `tools/bot_live_delivery.py:29-32`
  says advertisement is mandatory so old clients "must not receive work they
  cannot consume". We confirmed the `bot_live_delivery_consumer` metadata flag is
  written unconditionally (`tui_gateway/session_lifecycle.py:27-35`) but did not
  read the full `active_session_registry` write path, so we cannot say whether
  anything *else* must be advertised. Slice 4's delivery-target behaviour is
  gated on this.
- **`profiles.set_asset` / `profiles.get_asset` payload shapes.** Named
  throughout as the avatar door; never read. Slice 8 must confirm them.
- **`profiles.configure` write semantics for `ui_meta`.** We read the *read* half
  of the group-chat sync contract in detail and the write half only by
  inference. Whether a `profiles.configure` write is a merge or a replace, and
  exactly how `ui_meta_revisions` is checked server-side, decides whether slice 7
  is safe. Unresolved and load-bearing.
- **The full `hermes-bots-groups` blob schema.** Taken from Desktop's
  `types.ts:160-188`, not from a recorded payload. No fixture was captured.
- **`create-dialog.tsx`'s Advanced tabs in depth.** The capability catalog,
  toolsets and the profile-config surface were surveyed, not read. A ninth slice
  may be hiding there.
- **Group threading.** `newThread`, `replyInThread`, `openThread`,
  `collapseThread` and the per-thread watermark model exist in the copy and the
  type; the threading UI itself was not read. Slice 6 and 7 estimates assume a
  flat room and will move if threading is in scope.
- **Any rendered evidence.** No Desktop capture, no Android capture, no parity
  page. Every parity page named in section 6 is a deliverable of its slice, not
  of this document.
- **`hermes-bots` roster metadata (`ui_meta['hermes-bots']`).** Named as the
  gate that makes a profile bot-managed (`tools/bot_mode_probe.py:102-114`);
  its schema was not read.
- **The Kanban and accent bundled plugins**, which sit beside `hermes-bots`
  upstream and may share contribution patterns worth copying.
- **Localisation beyond English.** `i18n.ts` ships `ja`, `zh` and `zh-hant`;
  this app ships English only, and whether that is a divergence to record was not
  decided.

