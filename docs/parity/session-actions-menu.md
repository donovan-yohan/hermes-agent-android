# Per-session actions menu: source and deviation ledger

The per-session actions menu (`ui/sessions/SessionActionsMenu.kt`), reached from
every session row (`ui/sessions/SessionList.kt`) and from the chat header for the
open session (`ui/chat/ChatScreen.kt`), ported per
[`docs/workflows/port-desktop-surface.md`](../workflows/port-desktop-surface.md).

The menu shipped as a **container** first, deliberately: the group order below
was fixed and tested before any verb landed, so no later slice could reorder it
on its way in. Copy ID landed with the shell (S13), Rename and Delete with #65,
and **Pin / Unpin, Mark as read / unread and Archive / Unarchive with #66**.
Branch, Export and Move to project are still empty slots.

## Pin

| Source | Pin | Read via |
|---|---|---|
| Desktop renderer and i18n | `hermes-agent` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3` | read-only checkout; the working tree has drifted, so every citation below was taken with `git show <sha>:<path>` |

Every `path:line` below is against that SHA.

## Paths that settled the port

| Question | Path |
|---|---|
| Group order and the separator rule | `apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:234,291,344,371,433,465-522` |
| The menu kit both surfaces share | `apps/desktop/src/components/ui/actions-menu.tsx:37-98,119-146` |
| Codicon vocabulary | `session-actions-menu.tsx:292,304,317,345,357,435,444`; trigger glyph at `session-row.tsx:326` |
| Trigger placement and spoken name | `session-row.tsx:316-327`; `apps/desktop/src/i18n/en.ts:2319` |
| Item labels | `apps/desktop/src/i18n/en.ts:2303-2336` |
| Copy-ID behaviour inside a menu | `apps/desktop/src/components/ui/copy-button.tsx:92-140,166-181` |
| The words the item swaps to | `copy-button.tsx:142,147-151,161-164`; `apps/desktop/src/i18n/en.ts:21,29,2318` |
| How long the swap lasts | `copy-button.tsx:15` (`COPIED_RESET_MS`), `:115-123,128-136` |
| What the Copy ID row is handed | `session-actions-menu.tsx:479-488` |
| Modifier chords with no touch equivalent | `apps/desktop/src/app/chat/sidebar/session-row-gesture.ts:27-50` |

## Group order

Declaration order in `SessionActionsGroup` is the contract;
`sessionActionsMenuPlan` sorts by it. `SessionActionsMenuTest` asserts the order
literally, so reordering two constants fails the build.

| # | Group | Desktop | What it holds | Ships here |
|---|---|---|---|---|
| 1 | `Open` | `openItems` (`:234`) | Open in new tab, New window, Open in terminal | **Never** — Android has no tabs, no second window, and no local terminal. The slot exists only to keep the numbering honest. |
| 2 | `Identity` | `identityItems` (`:291`) + the Copy ID row (`:479-488`) | Rename, Pin, Mark as read/unread, Copy ID | **Copy ID** (S13), **Rename** (S14, #65) — proved by `GatewaySessionRepositoryTest.renameSession with live runtime id calls session_title RPC and updates cache`, `.renameSession without live runtime id calls REST PATCH and updates cache`, `.renameSession falls back to REST PATCH when the session_title RPC fails` and `SessionActionsMenuJourneyTest.renaming a session seeds the dialog with current title and saves on confirm`. **Pin / Unpin and Mark as read / unread** (#66) — proved by `SessionActionsMenuTest.the ported menu sits in Desktop's slots`, `.a pinned row offers the way back out of the section`, `.the read-state row is one slot naming the action it performs`, `GatewaySessionRepositoryTest.setSessionPinned writes the pin with the row's own profile and paints it first` and `.marking read clears the watermark and the finished-turn dot together`. |
| 3 | `Work` | `workItems` (`:344`) + Move to project (`:491-499`) | Branch, Export, Move to project | Not yet |
| 4 | `Tab` | `tabItems` (`:371`) | Reload, Close, Close others / to the right / all | **Never** — no tab strip on a phone |
| 5 | `Danger` | `dangerItems` (`:433`) | Archive, then Delete (last, destructive-red) | **Delete** (S15, #65) — proved by `GatewaySessionRepositoryTest.deleteSession with live runtime id calls session_delete RPC and cleans up cache and runtime maps`, `.deleteSession refuses deletion of running session with 4023 safe error` and `SessionActionsMenuJourneyTest.deleting a session opens confirmation dialog with redacted title and deletes on confirm`. **Archive / Unarchive** (#66), above Delete and not destructive-red — proved by `SessionActionsMenuTest.an archived row offers the restore in the same slot`, `.every flag combination keeps the menu structure` and `GatewaySessionRepositoryTest.setSessionArchived files the row in place and off the live list`. |

### Does Desktop render a separator for an empty group?

**No — and it never has to.** This was the one question worth settling before
writing the renderer, because getting it wrong bakes a stray rule into every
later slice.

`renderItems` (`:465-522`) is not uniform:

| Rule | Desktop line | Guard |
|---|---|---|
| after `openItems` | `:468` | `openItems.length > 0 &&` — **conditional** |
| after identity + Appearance + Copy ID | `:489` | unconditional |
| before `tabItems` | `:500` | `tabItems.length > 0 &&` — **conditional** |
| before `dangerItems` | `:506` | unconditional |
| before Hide tab bar | `:508` | `onHideTabBar &&` — **conditional** |

The two unconditional rules are safe upstream only because `identityItems`,
`workItems` and `dangerItems` are never empty there: Desktop always *renders*
those items and merely **disables** the ones whose handler is missing
(`disabled: !onPin`, `disabled: !onBranch`, `disabled: !onArchive`,
`disabled: !onDelete`). So upstream never paints a leading, trailing or doubled
rule.

**Ported rule:** walk the groups in fixed order and emit a separator between two
adjacent groups that both have items. For every configuration Desktop can
actually produce, that is byte-for-byte the same rendered sequence — asserted by
`the Desktop row menu renders three rules between its four populated groups`,
which reconstructs Desktop's full row menu and expects exactly three rules. It
also degrades honestly while Android's groups are still filling: today's
one-item menu shows one item and no rules, instead of a lone verb fenced by two
meaningless lines.

## Codicon map

The glyph vocabulary is fixed by Desktop and must not be substituted with
Material icons. Code points are Codicons 0.0.45 (`THIRD_PARTY_NOTICES.md`), the
same font Desktop uses. `HermesIconFontTest` parses the shipped
`app/src/main/res/font/codicon.ttf` cmap and fails if any `HermesIcon` code
point is not mapped — a wrong number renders as a blank box on device and as
nothing at all in a screenshot, so the inspection is a gate rather than a note.

| Desktop codicon | Desktop line | `HermesIcon` | Code point | In `codicon.ttf` | Ships in S13 |
|---|---|---|---|---|---|
| `kebab-vertical` | `session-row.tsx:326` | `KebabVertical` | `U+EB10` | yes | **yes** — the trigger |
| `copy` (lucide upstream, see below) | `session-actions-menu.tsx:479-488` | `Copy` | `U+EBCC` | yes | **yes** — Copy ID |
| `check` (lucide upstream) | `copy-button.tsx:142` | `Check` | `U+EAB2` | yes | **yes** — copy confirmation |
| `close` (lucide `X` upstream) | `copy-button.tsx:142` | `Close` | `U+EA76` | yes | **yes** — copy failure |
| `edit` | `:292` | `Edit` | `U+EA73` | yes | S14 (Rename) |
| `pin` | `:304` | `Pin` | `U+EB2B` | yes | #66 (Pin / Unpin) |
| `mail` | `:317` | `Mail` | `U+EB1C` | yes | #66 (Mark as unread) |
| `mail-read` | `:317` | `MailRead` | `U+EB1B` | yes | #66 (Mark as read) |
| `repo-forked` | `:345` | `RepoForked` | `U+EA63` | yes | later |
| `cloud-download` | `:357` | `CloudDownload` | `U+EAC2` | yes | later |
| `folder` | `:493` | `Folder` | `U+EA83` | yes | later |
| `archive` | `:435` | `Archive` | `U+EA98` | yes | #66 (Archive / Unarchive) |
| `trash` | `:444` | `Trash` | `U+EA81` | yes | S15 (Delete) |

Two notes the source settles rather than guesswork:

- Codicon has **no `mail-unread` glyph**, which is why Desktop uses closed `mail`
  for unread and open `mail-read` for read (`:317-321`).
- Codicon has **no `git-fork` glyph** (only `git-fork-private`), which is why the
  branch verb is `repo-forked` (`:345-350`).
- Desktop's Copy ID row is drawn by `CopyButton`, which uses **lucide** `Copy` /
  `Check`, not a codicon (`copy-button.tsx:9`). Android has one glyph family, so
  it uses this app's established codicon copy/check pair — the same pair
  `Transcript.kt` and `CodingStatusRow.kt` already use for clipboard actions.

Only **Delete** carries the destructive-red variant (`:445,461`). Archive shares
the danger group without it. `SessionActionItem.destructive` carries that flag,
`only Delete is destructive-red` asserts it against the transcribed Desktop
menu, and `every flag combination keeps the menu structure` re-asserts it for
every pinned/unread/archived state the shipped menu can be in — so nothing can
quietly redden Archive.

Two glyph facts the source settles for #66's verbs:

- The envelope pair names the **action**, not the state: Desktop picks
  `unread || isUnread ? 'mail-read' : 'mail'` alongside
  `markRead : markUnread` (`:314-315`), so `Mark as read` carries the *open*
  envelope. Reading the glyph as a state indicator and inverting it is the
  obvious mistake and it is wrong.
- Pin and Archive each use **one** glyph for both directions; only the label
  swaps (`:299-300`, `:434-435`).

## Mobile adaptation

| Desktop | Android | Reason |
|---|---|---|
| 20px kebab, `opacity-0` until row hover (`session-row.tsx:319`) | Same glyph at 14sp inside a 48dp target, always visible | Touch has no hover. The glyph's visual weight and its right-edge placement are unchanged. |
| Kebab `absolute right-0` over the trailing meta slot (`:320`) | Overlay aligned `CenterEnd` in the row's existing `Box`, with a full `touchTarget` of end inset reserved on the row content | Same trick, same reason: the 48dp target must not grow the row or reflow it. Since the meta cannot swap out on hover, the space is simply reserved — and it is the control's *whole* width, not the distance to its glyph: the glyph is centred but the hit box is not, so anything drawn in the last 48dp would be visible and untappable. |
| Right-click opens `SessionContextMenu` with the same items (`session-actions-menu.tsx:621-639`) | Not ported | The tap target is the only path in. Long-press belongs to text selection on a phone; binding the menu to it would fight the transcript's selection gesture. |
| ⇧-click pin, ⌥⇧-click archive (`session-row-gesture.ts:33,45`) | Not ported; they become ordinary menu items when their slices land | A soft keyboard has no modifier keys. **Decided once, here** — no later slice should reopen it. |
| `w-40` (160px) content | `widthIn(min = 220.dp)` | The phone type scale is ~1.15× Desktop's, and this matches the width of the sidebar's existing dropdown so the two menus read as one system. |
| `sideOffset={6}` | `DpOffset(0.dp, 6.dp)` | Unchanged. |
| Radix `DropdownMenuContent` with `aria-label="Session actions"` | Compose `DropdownMenu`; the **trigger** carries `contentDescription = "Session actions"`, the content carries only a test tag | On Android a `contentDescription` on the menu container would merge its children and swallow the item labels. The trigger is where TalkBack needs the name, and it is where Desktop puts it too (`session-row.tsx:317`). |
| Menu item is a `<DropdownMenuItem>` | A `Row` at `heightIn(min = touchTarget)`, painted from `HermesTheme.tokens` only | 48dp floor. No Material surface, elevation, ripple colour or type default is used: `containerColor = cardSurface`, `tonalElevation`/`shadowElevation` `0.dp`, 1dp `strokePrimary` border, 6dp radius, separators in `strokeTertiary` — the same recipe as `SidebarViewMenu`. |
| Copy ID `event.preventDefault()` keeps the menu open (`copy-button.tsx:94-97`) | Same: the item swaps in place and the menu stays up | Also this app's established clipboard grammar (`Transcript.kt`, `CodingStatusRow.kt`): Android 13+ already raises a system clipboard notice, and a second app-level notice would be talking over the platform. |
| Confirmation reads `t.common.copied` (`copy-button.tsx:147-148`; `en.ts:21`) | **Same**: `Copied` | Verbatim. This row previously read `Session ID copied`, which was this port's own phrasing rather than Desktop's word. |
| Confirmation clears itself after `COPIED_RESET_MS` = 1500 (`copy-button.tsx:15,120-123,133-136`) | **Same**: `LaunchedEffect` + `COPY_CONFIRM_MILLIS`, which is 1500 | Verbatim, including the repeat press: Desktop clears its pending timeout before setting a new one, so a second press restarts the beat rather than inheriting the first one's remainder. The constant is the one `Transcript.kt` and `CodingStatusRow.kt` already share, moved to `ui/common/Clipboard.kt` beside the write itself. |
| Copy failure raises a notification *and* swaps the item to `X` + `t.common.failed`, with `copyIdFailed` on its tooltip and `aria-label` (`copy-button.tsx:142,149-151,161-164`; `session-actions-menu.tsx:482,486`) | One slot: the item swaps to `Close` + `Could not copy session ID` (`en.ts:2318`) for the same 1500ms | Touch has no hover, so the tooltip has nowhere to go, and this build has no notification centre. Desktop's three surfaces collapse to the one the phone has — and the specific message goes there rather than the bare `Failed`, because it is the half that says what did not happen. |
| Copy failure is **not** tinted: the row is handed `text-current` and no variant (`session-actions-menu.tsx:483`) | **Same**: the failure row keeps `textSecondary` | Deliberate, and against the reviewer's suggestion: destructive-red is Delete's alone here. Reddening a transient, self-clearing failure would make a message that resolves itself in a second and a permanently destructive verb read alike. |
| Nested `Appearance` and `Move to project` submenus (`:470-478,491-499`) | Not ported | The port workflow's standing rule: nested pointer submenus are brittle on a phone. When those verbs land they flatten into their group. |
| Rename input seeded with the raw session title (`session-actions-menu.tsx:637-720`) | **Same**: the field shows the real title, and it is the one title across these two dialogs that is not passed through `redact()` | It is the text being *edited*, not text describing something. A redacted seed would either be saved back over the real title or have to be reversed before it was sent, and neither is safer than showing someone a title they already own. The delete confirmation only *describes* a title, and that one is redacted (`DeleteSessionDialog.kt:54`). |

### Deviation the reviewer should weigh explicitly

> **Superseded by [#101](https://github.com/donovan-yohan/hermes-agent-android/issues/101).**
> The standing rule is now Desktop's: a mode or control this app does not
> support **yet** stays visible and disabled with a "coming soon" pill rather
> than being absent, so the menu's shape is the same one Desktop teaches. Only
> a *non-goal* — something this platform will never have — is omitted outright.
> The reasoning below still settles the non-goals (the right-click twin, the
> modifier gestures, the tab and open groups) and the blank-id case. It no
> longer settles rename, delete, pin, archive or the nested submenus; those are
> `pill-owed` rows in the Divergences table.

Desktop keeps every identity / work / danger item mounted and **disabled** when
its handler is missing. This port **omits** an unavailable verb instead.

Shipping a permanently greyed-out `Rename` would be the menu advertising a
capability the app does not have — the port workflow calls that an omission, not
a deviation, and says to keep the port incomplete rather than fake the row. The
group slots are what preserve ordering, and they are preserved structurally (in
`SessionActionsGroup`) rather than by rendering dead items.

The same reasoning settles the blank-id case. Desktop disables its whole menu
when there is no session id (`disabled={!sessionId}`, `:471,481`); with nothing
left to disable, `sessionActionItems` returns nothing and the control is not
rendered at all — the alternative is an empty bordered popup, which is the menu
promising something it has not got. `parseSession` rejects a *missing* id, not
an empty one, so this is a state the UI can actually be handed.

## What ships in S13

One verb, honestly:

| Item | Group | Glyph | Label | Source |
|---|---|---|---|---|
| Copy ID | Identity | `Copy` | `Copy ID` | `en.ts:2308`; `session-actions-menu.tsx:479-488` |
| …once copied | Identity | `Check` | `Copied` | `copy-button.tsx:142,147-148`; `en.ts:21` |
| …if the clipboard refuses | Identity | `Close` | `Could not copy session ID` | `copy-button.tsx:142,161-164`; `en.ts:2318` |

All three are the same item in the same slot: the icon and the label change, the
group does not, and the last two settle back to the first after 1500ms.

Everything else in the table above is absent, and its group slot is present.

## What #66 added

| Item | Group | Glyph | Label | Source |
|---|---|---|---|---|
| Pin | Identity | `Pin` | `Pin` | `en.ts:2303`; `session-actions-menu.tsx:297-305` |
| …when the row is pinned | Identity | `Pin` | `Unpin` | `en.ts:2304`; `:300` |
| Mark as unread | Identity | `Mail` | `Mark as unread` | `en.ts:2305`; `:310-333` |
| …when the row is unread | Identity | `MailRead` | `Mark as read` | `en.ts:2306`; `:314-315` |
| Archive | Danger | `Archive` | `Archive` | `en.ts:2312`; `:431-440` |
| …when the row is archived | Danger | `Archive` | `Unarchive` | `en.ts:1156` (Desktop's settings page) |

Each is one PATCH on `PATCH /api/sessions/{id}` (`hermes_cli/web_routers/sessions.py:825-832`
@ the pin), written optimistically and repainted on refusal. The unread row
drives off **both** unread sources exactly as Desktop's does (`:311,314-315`):
this client's transient finished-turn dot and the backend's durable watermark,
and marking read clears both in one action.

## Omissions

- The remaining verbs in the epic's rank-6 list: branch, export, move to
  project, colour.
- The open group and the tab group, permanently — no tabs, windows, or local
  terminal on this platform.
- The right-click / context-menu twin of the dropdown, permanently.
- Both nested submenus; they flatten when their verbs arrive.

## Evidence

| Check | Where |
|---|---|
| Group order, separator placement, codicon map, destructive flag, all three copy states | `app/src/test/kotlin/com/hermesagent/mobile/ui/sessions/SessionActionsMenuTest.kt` |
| Every `HermesIcon` code point resolves in the shipped `codicon.ttf`, and the reader that says so is not simply saying yes | `app/src/test/kotlin/com/hermesagent/mobile/ui/common/HermesIconFontTest.kt` |
| A refused clip is reported rather than thrown, and an accepted one lands under its own label | `app/src/test/kotlin/com/hermesagent/mobile/ui/common/ClipboardTest.kt` |
| 48dp control, reserved end inset, unfragmented row label, tap-not-long-press, clipboard write, confirmation and its 1500ms settle, refusal in place, no control for a blank id, destructive ink, chat-header parity | `app/src/testDebug/kotlin/com/hermesagent/mobile/ui/sessions/SessionActionsMenuJourneyTest.kt` |
| Rename and delete resolution: the `session.title` RPC for a live runtime, REST `PATCH` for a persisted row or a clear, the fall-through to REST when the RPC refuses, the mapped failures, `session.delete` with its 4023 refusal and 4007 already-deleted, REST `DELETE` with 404 as success, and a rename that outlives a `session.list` refresh in flight | `app/src/test/kotlin/com/hermesagent/mobile/data/gateway/GatewaySessionRepositoryTest.kt` |
| Dialogs as a reader meets them: the rename field seeded, cleared and committed, the delete confirmation's redacted body, the in-flight and inline-failure states, and the three ways out (Cancel, system back, a tap outside) that are never a confirm | `app/src/testDebug/kotlin/com/hermesagent/mobile/ui/sessions/SessionActionsMenuJourneyTest.kt` |

Shared helpers this slice extracted rather than re-spelled:
`ui/common/copyToClipboard` now backs all three clipboard controls
(`Transcript.kt`, `CodingStatusRow.kt`, and this menu), along with
`COPY_CONFIRM_MILLIS` and the `ClipboardWriter` test seam; the menu's group
rules use the existing `Hairline()` primitive.

**One thing that story does not yet cover.** `copyToClipboard` now reports a
refusal instead of raising one, but only this menu acts on it —
`Transcript.kt`'s reply copy and `CodingStatusRow.kt`'s worktree path still
discard the result and confirm optimistically. That is their behaviour before
this slice minus the crash, not a new fault, and giving each of them a failure
state is a product decision for their own surface. It is a follow-up, not a
gap in this one.

Mutations run against this slice, each applied alone and restored (the point of
a gate is that removing the behaviour turns it red):

| Mutation | Red |
|---|---|
| Swap `Identity` and `Work` in `SessionActionsGroup` | 3 |
| Emit a separator unconditionally | 4 |
| Drop the empty-group guard | 3 |
| `val ink = tokens.textSecondary` — ignore `destructive` | 1 |
| `copyToClipboard` swallows the failure and returns `true` | 2 |
| Row end inset back to `touchTarget / 2 + 8.dp` | 1 |
| Control mounts even when the plan is empty | 1 |
| Confirmation never settles back to idle | 2 |
| Confirmation label back to `Session ID copied` | 3 |
| cmap reader runs each segment one code point long | 1 |
| `hasSessionActions` always says yes | 3 |

`else -> error(...)` in the control's `when` has no mutation: it is the branch
that must never be reached, and its whole job is to fail loudly the first time
S14's Rename arrives without a handler instead of rendering a dead row.

## Divergences

Classified for `scripts/check-parity-evidence.py`; the adaptation table above
carries the argument and the citations.

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| 20 px kebab, `opacity-0` until row hover (`session-row.tsx:319`) | mobile-adaptation | The same glyph at 14 sp inside a 48 dp target, always visible | Touch has no hover; visual weight and right-edge placement are unchanged |
| Kebab `absolute right-0` over the trailing meta slot (`:320`) | mobile-adaptation | Overlay aligned `CenterEnd`, with the control's whole width reserved as end inset | The 48 dp target must not grow the row or reflow it, and the hit box is wider than the centred glyph |
| `w-40` (160 px) content | mobile-adaptation | `widthIn(min = 220.dp)` | The phone type scale is ~1.15× Desktop's, and this matches the sidebar's existing dropdown so the two menus read as one system |
| `aria-label="Session actions"` on `DropdownMenuContent` | mobile-adaptation | The **trigger** carries the `contentDescription`; the content carries only a test tag | On Android a description on the menu container merges its children and swallows the item labels |
| Copy failure raises a notification *and* swaps the item, with a tooltip (`copy-button.tsx:142,149-164`) | mobile-adaptation | One slot: the item swaps to `Close` + `Could not copy session ID` for the same 1500 ms | Touch has no hover so the tooltip has nowhere to go, and this build has no notification centre |
| Desktop `Renamed` toast (`en.ts:2328`) | omission | Inline failure on refusal, dialog dismiss on success | deferred: #73 (in-app-notification-stack) |
| Desktop `Session deleted` toast (`en.ts:2336`) | omission | Rendered via chat `notice` banner | deferred: #73 (in-app-notification-stack) |
| Unavailable verbs stay mounted and **disabled** | omission | Absent | pill-owed: #101 — this page previously argued for omitting them; the standing rule is now a visible disabled row with a "coming soon" pill |
| Branch, export, move to project, colour | omission | Absent | pill-owed: #101 — pin, archive and read-state are no longer among them; they ship in #66 |
| Read-state item is `disabled` when neither `onToggleUnread` nor a live dot exists (`session-actions-menu.tsx:311`) | mobile-adaptation | Always enabled | The handler always exists on this surface, so the disabled branch is unreachable rather than dropped — the Gateway, not the client, decides whether a row can carry a watermark |
| `Unarchive` lives on the Archived Chats settings page (`app/settings/sessions-settings.tsx:148-154`) | mobile-adaptation | In the row's own actions menu, with Desktop's own word | That settings page is a declared non-goal here, so the row menu is the only place a reversible verb can live; moving the verb rather than inventing a word keeps the vocabulary Desktop's |
| Archived Chats settings page, and auto-archive-after-N-days | omission | Absent | deferred: #73 — session maintenance; #66 declares both non-goals |
| `Renamed` / `Session deleted` / pin and archive toasts (`en.ts:2328,2336`) | omission | Chat `notice` banner, or nothing on success | deferred: #73 (in-app-notification-stack) |
| Nested `Appearance` and `Move to project` submenus (`:470-478,491-499`) | omission | Absent | pill-owed: #101 — they flatten into their group when their verbs land; nested pointer submenus are not what ships |
| Right-click `SessionContextMenu` (`session-actions-menu.tsx:621-639`) | omission | Absent | non-goal: long-press belongs to text selection on a phone, and binding the menu to it would fight the transcript's gesture |
| ⇧-click pin, ⌥⇧-click archive (`session-row-gesture.ts:33,45`) | omission | Absent | non-goal: a soft keyboard has no modifier keys |
| The open group and the tab group | omission | Absent | non-goal: no tabs, windows or local terminal on this platform |

## Visual report

- pending: #65
- pending: #66

Not captured. The Desktop reference capture in the port workflow needs a
disposable pinned dev renderer with CDP, which was not available for this slice;
the menu's geometry, colour roles and glyph vocabulary are pinned against source
and the shipped font instead. A capture pass belongs with #65, when the menu
has enough items for a screenshot to be worth comparing — and #66 owes its own,
because Pin / Unpin, the read-state row and Archive / Unarchive are three more
rendered items that no side-by-side has yet seen.
