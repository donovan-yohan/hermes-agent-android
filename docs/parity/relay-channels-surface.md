# Relay channels + transcript: source and deviation ledger

The read-only Relay workspace (`plugins/relay/`), ported per
`docs/workflows/port-desktop-surface.md`.

## Pin

| Source | Pin | Read via |
|---|---|---|
| Relay plugin (renderer + backend) | `hermes-plugin-relay` @ `563a8c8` | `git show 563a8c8:<path>` only — the working tree carries unreleased work |
| Gateway / auth gate | `hermes-agent` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3` | read-only checkout |

Every `path:line` below is against those pins.

## Paths that settled the port

| Question | Path |
|---|---|
| Channel row fields and order | `desktop/plugin.js:109-130,481-497` |
| Archived treatment | `desktop/plugin.js:492` |
| Channel order (there is none of the client's own) | `desktop/plugin.js:109-130` |
| Selection and its storage | `desktop/plugin.js:302-318,759,853-872` |
| Message row | `desktop/plugin.js:132-138,502-517` |
| History request | `desktop/plugin.js:24,796` |
| Poll interval and its gate | `desktop/plugin.js:23,1073-1083` |
| Failed poll keeps data | `desktop/plugin.js:804-815` |
| Connection banner copy | `desktop/plugin.js:384-412` |
| Empty/error copy | `desktop/plugin.js:452-470,519-555` |
| Launcher tile copy | `desktop/plugin.js:371-381` |
| Projected wire shape | `relay_proxy.py:228-320` |
| Frozen endpoint list | `docs/desktop.md` |

## What is preserved

Flat rows with no card, hairline dividers, uppercase tracked attribution
labels, plain pre-wrapped message text with no markdown parsing on the read
path, backend-owned channel order, the archived annotation on the name line,
the three-second cadence, the ready-lane gate on that cadence, and "a failed
refresh keeps the last good answer".

## Deviation ledger

Each entry is Desktop's behaviour, this app's behaviour, and why.

**Pane dock → one full-screen destination.**
Desktop puts the channel list and the transcript side by side inside a
workspace pane (`plugin.js:1108-1285`). Android shows one pane at a time behind
a single back affordance.
*Reason:* viewport space. Two panes do not fit a phone.

**Selection is persisted → selection is memory-only.**
Desktop writes the chosen channel to plugin storage under
`relay.selection.channelId` and restores it on mount, falling back to the first
channel (`plugin.js:302-318,853-872`).
*Reason:* explicit state classification (issue #41). Restoring a stored
selection on a one-pane phone would open someone inside a transcript they never
chose, and this app's UI-only rule keeps selection out of persistence.

**Interval refreshes the transcript → interval refreshes the visible pane.**
Desktop's single interval only reloads the selected channel's history because
both panes are already on screen (`plugin.js:1073-1083,889-893`). Android
refreshes the channel list when the list is on screen and the transcript when
the transcript is. Still one request per tick.
*Reason:* mobile priority. Desktop's rule is "refresh the visible page"; on one
pane the visible page is whichever pane is showing.

**No visibility check → the poll is bounded by the resumed surface.**
Desktop has no `visibilitychange` handler; its interval dies when the pane
unmounts.
*Reason:* touch/mobile lifecycle. Closing a pane is Desktop's "stop asking";
the phone's equivalent is the screen leaving the foreground, so
`LifecycleResumeEffect` starts and stops the same loop.

**Backend array order → ordered by `seq` ascending.**
Desktop renders the returned array as-is (`plugin.js:140-169,556`), which is
the newest-first window.
*Reason:* accessibility/readability of a chat transcript, per issue #41. `seq`
is the hub's own monotonic order and is required on every projected row
(`relay_proxy.py:292-305`), so the result is deterministic either way.

**Raw ISO timestamp → formatted label.**
Desktop prints `message.timestamp` verbatim inside a `<time>` element
(`plugin.js:512`).
*Reason:* viewport space. A full ISO instant does not fit a phone row. A stamp
this build cannot parse renders no label rather than wire text.

**No kind/visibility → a quiet classification line.**
Desktop renders neither, and shows `summary` as the row's second line
(`plugin.js:127,492-495`); the pinned mobile client does not project `summary`
but does project `kind` and `visibility` (`relay_proxy.py:265-268`).
*Reason:* mobile priority. Desktop keeps the transcript beside the list, so a
channel's nature is one click away; on a phone the row is the only place to
tell channels apart before committing a whole screen to one.

**No per-message status → a quiet status label.**
Desktop renders no per-row status (`plugin.js:502-517`).
*Reason:* mobile priority, plus the frozen contract fixes no status vocabulary
(`relay_proxy.py:293` accepts any required string). The label reports Relay's
own token, humanised and never remapped, so an unknown value cannot be
mislabelled.

**`Relay connected` banner → no banner when ready.**
Desktop shows a success banner (`plugin.js:392-395`).
*Reason:* viewport space. A permanent success banner on a phone is chrome that
costs a row of channels.

**Archived transcript is an empty state → an archived note above the messages.**
Desktop replaces the message list with `Channel archived` (`plugin.js:530-536`).
*Reason:* the same Desktop string promises "its previous messages remain
available when Relay returns them", so hiding a window Relay did return would
contradict it. The note sits under the channel title instead.

**`Send the first message when Relay is ready.` → `Messages appear here as Relay returns them.`**
*Reason:* this slice ships no composer, so telling someone to send would name
an affordance that does not exist yet. The composer lands with issue #42.

**Host-written text is the whole message → it sits beside this app's sentence.**
Desktop renders `connection.message` as the banner body (`plugin.js:404-406`).
*Reason:* the message is written on the Gateway host. This surface always
states its own sentence and renders the host's text only as an additional
detail, taken from the availability layer's `statusDetail()` — already
redacted, collapsed to one line and bounded there. `guidance` is not rendered
at all: it does not exist at the pinned plugin.

**One sign-in sentence → the sentence the live leg can honour.**
Desktop has one banner for a refused connection (`plugin.js:396-403`).
*Reason:* Android has two legs. `RelayAvailabilityState.signInAvailable` says
whether a Gateway sign-in exists on this one; managed SSH and token mode have
none, so the same refusal asks for a reconnect there. The availability layer
owns both sentences and this surface renders whichever it was handed — the
next step is the Gateways screen either way.

**No "not connected yet" state → one, because the controller can hold none.**
Desktop's pane only mounts inside a connected app.
*Reason:* before the first Connected edge the controller deliberately holds no
availability and no spinner. That is not an answer about Relay and must not
render as one, nor as a blank screen, so it renders `Connect to a Gateway to
open Relay.` with the Gateways action.

## Omissions

Not deviations — things this slice does not ship, stated rather than hidden.

- **Composer.** Issue #42.
- **Harnesses inspector.** Out of scope for v1 (epic #38 boundary).
- **`POST /connection/authorize`.** The lane's `auth_required` state renders
  Relay's own remediation text and Desktop's own `Authorize Relay` button,
  disabled behind the `WIP` chip; redeeming the host's one-time grant is a write
  and belongs with the write slice (#38).
- **`summary` on a channel row.** Not projected by the pinned mobile client.
- **Pagination.** The frozen endpoint takes `limit` and no cursor, and Desktop
  re-requests the same fixed 50-row window every time.
- **Rendered visual capture.** See below.

## Divergences

Classified for `scripts/check-parity-evidence.py`; the ledger above carries the
argument.

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Two panes side by side | mobile-adaptation | One pane at a time, with the transcript pushed as its own destination | A 360 dp column cannot hold a channel list and a transcript at a legible type scale |
| Hover-revealed row affordances | mobile-adaptation | Always visible inside 48 dp targets | Touch has no hover; glyph weight and placement are unchanged |
| Composer | omission | Absent | pill-owed: #42 — retargeted off #101, which shipped every pill it could. A composer is not one control but a whole input surface with its own attachment, send and queue affordances, and a disabled facsimile of it would be a mock of an unbuilt screen rather than a marker on a missing button. #42 builds it. The retarget is an amendment to #101's acceptance and is recorded in the PR body for the owner to carry back onto #101 |
| Harnesses inspector | omission | Absent | out-of-scope: #38 — #38's own Boundaries section excludes it from v1 ("separate decision later"), which the Omissions list above states in prose. A `pill-owed:` marker would park a debt on an issue that will close without discharging it; this is the taxonomy's marker for something an issue deliberately excluded, and it may still return. Moving it off #101 is an amendment to that issue's acceptance and is recorded in the PR body |
| The `auth_required` banner's `Authorize Relay` action (`desktop/plugin.js:384-388,438-446` @ `563a8c8`) | omission | The action renders in Desktop's slot under the banner's title and body, disabled behind the `WIP` chip | coming soon — `RelayJourneyTest.an unauthorized lane offers Desktop's authorize action, marked and inert`. This page pins the Relay plugin at `563a8c8` and settles the banner from `desktop/plugin.js:384-412`, so the pinned contract *does* carry the control; what is missing is the write. Redeeming the host's one-time grant (`POST /connection/authorize`) belongs to #38's write slice, and until it lands the button says so rather than being absent |
| `summary` on a channel row | omission | Not projected | deferred: #43 — the pinned mobile client does not carry the field |

## Visual report

- pending: #43

`.chalk/skills/port-hermes-desktop-surface/scripts/capture-android-reference.py`
needs an attached device or emulator, and
`capture-desktop-reference.mjs` needs a disposable pinned Desktop dev renderer
with CDP. Neither was available in this environment: `adb devices` lists none
and the local SDK has no `emulator` package. The capture is therefore recorded
as **missing**, not fabricated, and belongs to the device-QA slice (issue #43).

What this slice does ship instead: `@Preview` composables for both panes in
phone light and dark (`plugins/relay/RelayScreen.kt`), which is the same in-source
affordance `ChatScreen.kt` uses. The fixtures in them are invented; no host,
channel, person or credential in this repo corresponds to anything real.
