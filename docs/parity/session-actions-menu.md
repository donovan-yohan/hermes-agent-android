# Per-session actions menu: source and deviation ledger

The per-session actions menu (`ui/sessions/SessionActionsMenu.kt`), reached from
every session row (`ui/sessions/SessionList.kt`) and from the chat header for the
open session (`ui/chat/ChatScreen.kt`), ported per
[`docs/workflows/port-desktop-surface.md`](../workflows/port-desktop-surface.md).

This slice ships the **container**, not the verbs. Rename lands in S14, delete in
S15, and pin / unread / branch / export / move-to-project / archive later still.
The point of shipping the shell first is that the group order below is fixed and
tested *now*, so none of those slices can reorder the menu on their way in.

## Pin

| Source | Pin | Read via |
|---|---|---|
| Desktop renderer and i18n | `hermes-agent` @ `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732` | read-only checkout; the working tree has drifted, so every citation below was taken with `git show <sha>:<path>` |

Every `path:line` below is against that SHA.

## Paths that settled the port

| Question | Path |
|---|---|
| Group order and the separator rule | `apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:234,291,344,371,433,465-522` |
| The menu kit both surfaces share | `apps/desktop/src/components/ui/actions-menu.tsx:37-98,119-146` |
| Codicon vocabulary | `session-actions-menu.tsx:292,304,317,345,357,435,444`; trigger glyph at `session-row.tsx:326` |
| Trigger placement and spoken name | `session-row.tsx:316-327`; `apps/desktop/src/i18n/en.ts:2167` |
| Item labels | `apps/desktop/src/i18n/en.ts:2151-2167` |
| Copy-ID behaviour inside a menu | `apps/desktop/src/components/ui/copy-button.tsx:87-102` |
| Modifier chords with no touch equivalent | `apps/desktop/src/app/chat/sidebar/session-row-gesture.ts:27-50` |

## Group order

Declaration order in `SessionActionsGroup` is the contract;
`sessionActionsMenuPlan` sorts by it. `SessionActionsMenuTest` asserts the order
literally, so reordering two constants fails the build.

| # | Group | Desktop | What it holds | Ships here |
|---|---|---|---|---|
| 1 | `Open` | `openItems` (`:234`) | Open in new tab, New window, Open in terminal | **Never** — Android has no tabs, no second window, and no local terminal. The slot exists only to keep the numbering honest. |
| 2 | `Identity` | `identityItems` (`:291`) + the Copy ID row (`:479-488`) | Rename, Pin, Mark as read/unread, Copy ID | **Copy ID** (S13). Rename in S14. |
| 3 | `Work` | `workItems` (`:344`) + Move to project (`:491-499`) | Branch, Export, Move to project | Not yet |
| 4 | `Tab` | `tabItems` (`:371`) | Reload, Close, Close others / to the right / all | **Never** — no tab strip on a phone |
| 5 | `Danger` | `dangerItems` (`:433`) | Archive, then Delete (last, destructive-red) | Delete in S15 |

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
| `check` (lucide upstream) | `copy-button.tsx` | `Check` | `U+EAB2` | yes | **yes** — copy confirmation |
| `edit` | `:292` | `Edit` | `U+EA73` | yes | S14 (Rename) |
| `pin` | `:304` | `Pin` | `U+EB2B` | yes | later |
| `mail` | `:317` | `Mail` | `U+EB1C` | yes | later |
| `mail-read` | `:317` | `MailRead` | `U+EB1B` | yes | later |
| `repo-forked` | `:345` | `RepoForked` | `U+EA63` | yes | later |
| `cloud-download` | `:357` | `CloudDownload` | `U+EAC2` | yes | later |
| `folder` | `:493` | `Folder` | `U+EA83` | yes | later |
| `archive` | `:435` | `Archive` | `U+EA98` | yes | later |
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
the danger group without it. `SessionActionItem.destructive` carries that flag
and `only Delete is destructive-red` asserts it against the transcribed Desktop
menu, so S15 cannot quietly redden Archive too.

## Mobile adaptation

| Desktop | Android | Reason |
|---|---|---|
| 20px kebab, `opacity-0` until row hover (`session-row.tsx:319`) | Same glyph at 14sp inside a 48dp target, always visible | Touch has no hover. The glyph's visual weight and its right-edge placement are unchanged. |
| Kebab `absolute right-0` over the trailing meta slot (`:320`) | Overlay aligned `CenterEnd` in the row's existing `Box`, with `touchTarget / 2 + 8.dp` of end inset reserved on the row content | Same trick, same reason: the 48dp target must not grow the row or reflow it. Since the meta cannot swap out on hover, the space is simply reserved. |
| Right-click opens `SessionContextMenu` with the same items (`session-actions-menu.tsx:621-639`) | Not ported | The tap target is the only path in. Long-press belongs to text selection on a phone; binding the menu to it would fight the transcript's selection gesture. |
| ⇧-click pin, ⌥⇧-click archive (`session-row-gesture.ts:33,45`) | Not ported; they become ordinary menu items when their slices land | A soft keyboard has no modifier keys. **Decided once, here** — no later slice should reopen it. |
| `w-40` (160px) content | `widthIn(min = 220.dp)` | The phone type scale is ~1.15× Desktop's, and this matches the width of the sidebar's existing dropdown so the two menus read as one system. |
| `sideOffset={6}` | `DpOffset(0.dp, 6.dp)` | Unchanged. |
| Radix `DropdownMenuContent` with `aria-label="Session actions"` | Compose `DropdownMenu`; the **trigger** carries `contentDescription = "Session actions"`, the content carries only a test tag | On Android a `contentDescription` on the menu container would merge its children and swallow the item labels. The trigger is where TalkBack needs the name, and it is where Desktop puts it too (`session-row.tsx:317`). |
| Menu item is a `<DropdownMenuItem>` | A `Row` at `heightIn(min = touchTarget)`, painted from `HermesTheme.tokens` only | 48dp floor. No Material surface, elevation, ripple colour or type default is used: `containerColor = cardSurface`, `tonalElevation`/`shadowElevation` `0.dp`, 1dp `strokePrimary` border, 6dp radius, separators in `strokeTertiary` — the same recipe as `SidebarViewMenu`. |
| Copy ID `event.preventDefault()` keeps the menu open (`copy-button.tsx:94-97`) | Same: the item swaps to `Check` + `Session ID copied` and the menu stays up | Also this app's established clipboard grammar (`Transcript.kt`, `CodingStatusRow.kt`): Android 13+ already raises a system clipboard notice, and a second app-level notice would be talking over the platform. |
| Nested `Appearance` and `Move to project` submenus (`:470-478,491-499`) | Not ported | The port workflow's standing rule: nested pointer submenus are brittle on a phone. When those verbs land they flatten into their group. |

### Deviation the reviewer should weigh explicitly

Desktop keeps every identity / work / danger item mounted and **disabled** when
its handler is missing. This port **omits** an unavailable verb instead.

Shipping a permanently greyed-out `Rename` would be the menu advertising a
capability the app does not have — the port workflow calls that an omission, not
a deviation, and says to keep the port incomplete rather than fake the row. The
group slots are what preserve ordering, and they are preserved structurally (in
`SessionActionsGroup`) rather than by rendering dead items.

## What ships in S13

One verb, honestly:

| Item | Group | Glyph | Label | Source |
|---|---|---|---|---|
| Copy ID | Identity | `Copy` → `Check` | `Copy ID` → `Session ID copied` | `en.ts:2156`; `copy-button.tsx:87-102` |

Everything else in the table above is absent, and its group slot is present.

## Omissions

- Every verb in the epic's rank-5 and rank-6 lists: rename (S14), delete (S15),
  pin, mark read/unread, branch, export, move to project, archive, colour.
- The open group and the tab group, permanently — no tabs, windows, or local
  terminal on this platform.
- The right-click / context-menu twin of the dropdown, permanently.
- Both nested submenus; they flatten when their verbs arrive.

## Evidence

| Check | Where |
|---|---|
| Group order, separator placement, codicon map, destructive flag, shipped item list | `app/src/test/kotlin/com/hermesagent/mobile/ui/sessions/SessionActionsMenuTest.kt` |
| Every `HermesIcon` code point resolves in the shipped `codicon.ttf` | `app/src/test/kotlin/com/hermesagent/mobile/ui/common/HermesIconFontTest.kt` |
| 48dp control, unfragmented row label, tap-not-long-press, clipboard write, chat-header parity | `app/src/testDebug/kotlin/com/hermesagent/mobile/ui/sessions/SessionActionsMenuJourneyTest.kt` |

Shared helpers this slice extracted rather than re-spelled:
`ui/common/copyToClipboard` now backs all three clipboard controls
(`Transcript.kt`, `CodingStatusRow.kt`, and this menu), and the menu's group
rules use the existing `Hairline()` primitive.

Mutation check performed for this slice: swapping `Identity` and `Work` in
`SessionActionsGroup` fails three tests in `SessionActionsMenuTest`, including
the two that read the rendered plan rather than the enum.

## Visual capture

Not captured. The Desktop reference capture in the port workflow needs a
disposable pinned dev renderer with CDP, which was not available for this slice;
the menu's geometry, colour roles and glyph vocabulary are pinned against source
and the shipped font instead. A capture pass belongs with S14/S15, when the menu
has enough items for a screenshot to be worth comparing.
