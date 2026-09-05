# The empty states: intro splash and sidebar blank state

Desktop's two vertically centred empty states, ported: the oversized wordmark
that titles a fresh chat, and the sidebar's blank state when an account has
nothing in it. Both are `place-items-center` in the whole height their slot
leaves, which is the shape this port is actually about — the Android originals
were top-anchored notes.

## Pin

Desktop authority is `3ca096de5f8183cb2e0ec23673f294d5978656a3`.

| What | Desktop |
|---|---|
| Intro component | `apps/desktop/src/components/chat/intro.tsx:160-179` |
| Intro copy records | `apps/desktop/src/components/chat/intro-copy.jsonl`; the neutral set is the `personality: "none"` records at `:71-75` |
| Copy selection | `pickCopy` at `intro.tsx:146-148`, `neutralCopy()` at `:103-105`, `WORDMARK` at `:150`, the per-mount seed at `:161` |
| Intro visibility | `apps/desktop/src/app/chat/intro-visibility.ts:12-33`, wired at `apps/desktop/src/app/chat/index.tsx:500-509` |
| Intro slot paint | `apps/desktop/src/styles.css:1603-1614` |
| Wordmark | `apps/desktop/src/components/chat/wordmark.tsx:15-45`; `.wordmark` and `.fit-text` at `styles.css:1616-1673`; the `@font-face` at `styles.css:61-68` |
| Sidebar blank state | `apps/desktop/src/app/chat/sidebar/section-states.tsx:26-42`, rendered at `apps/desktop/src/app/chat/sidebar/index.tsx:1912` on `!showSessionSections` (`:1426-1427`) |
| Appearance toggle | `apps/desktop/src/app/settings/appearance-settings.tsx:715-736`; the store at `apps/desktop/src/store/intro-splash.ts:5-13` (default **on**) |
| Copy | `apps/desktop/src/i18n/en.ts:588` `Intro Splash`, `:589` `The wordmark and prompt shown on an empty chat.`, `:2218` `No sessions yet`, `:2223` `New project`, `:44-45` `On` / `Off` |

**Two `noSessions` keys, one right answer.** `commandCenter.noSessions`
(`en.ts:1560`) is `No sessions yet.` **with** a full stop; the sidebar's
(`en.ts:2218`) has none. `SidebarBlankState` reads `t.sidebar`, so the string
this port carries is the one without it.

## Android

| What | Android |
|---|---|
| Copy set, seed and visibility rule | `app/src/main/kotlin/com/hermesagent/mobile/ui/chat/IntroSplash.kt` |
| Splash and wordmark composables | the same file, `IntroSplash` and `Wordmark` |
| Where it replaces the plain note | `ui/chat/Transcript.kt`, the `entries.isEmpty()` branch |
| The decision's inputs | `ui/chat/ChatScreen.kt`, `TranscriptPane` |
| Sidebar blank state | `ui/sessions/SessionList.kt`, `SidebarBlankState` |
| Shared empty-state primitive | `ui/common/Primitives.kt`, `EmptyState` |
| Sidebar loading skeletons | the same file, `SidebarSessionSkeletons` |
| Appearance row | `ui/appearance/AppearanceScreen.kt`, `IntroSplashRow` |
| Persistence | `data/prefs/HermesPreferences.kt`, `appearance.introSplash` |
| Bundled wordmark face | `app/src/main/res/font/collapse_bold.otf`; provenance in `docs/fonts.md` |
| Tests | `app/src/test/kotlin/.../ui/chat/IntroSplashTest.kt`, `WordmarkFitTest.kt` and `CollapseBoldFontTest.kt`, `app/src/test/kotlin/.../data/prefs/HermesPreferencesTest.kt`, `app/src/testDebug/kotlin/.../ui/chat/EmptyStateJourneyTest.kt`, `app/src/testDebug/kotlin/.../ui/appearance/IntroSplashSettingJourneyTest.kt` |

### Visibility, mapped honestly

Desktop's `shouldShowIntro` takes eight inputs. This app answers all eight with
four, and none of the collapses invent a state:

| Desktop input | Android |
|---|---|
| `enabled` | the saved `appearance.introSplash`, same default (on) |
| `primary`, `auxiliaryWindow` | one window; no non-primary surface exists to exclude |
| `freshDraftReady`, `routedSessionView`, `selectedSessionId`, `activeSessionId` | one field: `ChatUiState.activeSessionId` is null exactly on a fresh draft |
| `messagesEmpty` | `ChatUiState.transcriptIsEmpty` |

**The loading flash.** Desktop needs `routeSessionMismatch` because its URL can
name a session the store has not resumed. This app has no route: `rehome(id)`
sets `activeSessionId` before any RPC is issued (`ui/chat/ChatViewModel.kt`,
`rehome`), and the transcript is read from that id, so a session that is still
loading its history has a non-null id and cannot reach the splash branch. That
is why this port reads the id and not `activeSession`: `session.create` homes
the composer a frame or two before `session.info` publishes the row, and
`activeSession` is null for exactly that gap. `ChatUiState.activeSessionId` was
added for it. `EmptyStateJourneyTest` pins both halves.

What that session sees in the gap is the pre-existing
`No messages yet` / `Start a conversation with Hermes.` note, centred rather
than top-anchored. Desktop shows `ChatEmptySlot` there instead; that surface is
not ported.

**And once the gap closes, the splash is what an empty session gets.** Desktop
would show `ChatEmptySlot`; this app has never had it, so the alternative here
was that note, which says less than the wordmark does. The owner's call is the
splash — for a homed session as for a fresh draft. Three things guard it, and
none of them is new:

- the Appearance toggle still outranks everything, so `Off` restores the note
  exactly as before;
- `turnRunning` still refuses to paint over the progress row;
- and the loading flash is now refused by the Gateway's own count.
  `ChatUiState.transcript` is read straight from the cache
  (`ChatViewModel.kt:735`), so a session whose history is still being fetched is
  *also* an empty transcript. There is no "history loaded" fact in this state,
  so `SessionSummary.messageCount` — `session.info`'s `message_count` — is what
  vouches for the emptiness: null while the row has not landed at all, non-zero
  while a resumed session's rows are in flight, and zero only when the backend
  has said the session is empty. A Gateway that reports no `message_count`
  defaults it to zero and can still flash for the frames of the first read; that
  is the same window Desktop's own `messagesEmpty` has.

### The sidebar's own loading window

Desktop never lets `No sessions yet` describe an account it has not heard from:
`showSessionSections` is true while `showSessionSkeletons` is
(`sidebar/index.tsx:1423,1426-1427`), so the blank state cannot render during a
list read. The first port of this page shipped without that gate, and
`rows.isEmpty()` therefore rendered the blank state — with a live
`+ New project` — during the very first fetch and after every reconnect.

`GatewaySessionRepository.sessionPaging.loading` is that signal, set for every
live-pool read and cleared when the page lands. `ChatUiState.sessionsLoading`
is it, ANDed with an empty *unfiltered* scoped set — Desktop keys its skeletons
off the unfiltered set for the reason it records, that a filter matching nothing
would otherwise show skeletons on every background refresh. The rail draws the
same five placeholder bars it already draws for a search read; Desktop draws
one component for both cases too.

## The wordmark's fit

Desktop's `.fit-text` clamps between `--fit-min: 2.75rem` and no maximum
(`wordmark.tsx:22-24`, `styles.css:1653-1661`), and sets the whole string on one
line. This port stacks it — `HERMES` over `AGENT` — and that one change is what
the rest of this section is about.

**Why stack it.** The pinned light capture measures the visible lettering at
`1052.0px` in a `135.637px` face (`contract.json`, node 4), so `HERMES AGENT`
spans **7.756 em** in Collapse. `Wordmark` receives
`screen - 2 x 2dp gutter - 16dp inset`, i.e. `screen - 20dp`, so on a `w320dp`
phone the single line fits at about `38.7 sp` — a heading, not a wordmark — and
Desktop's own `2.75rem` floor was *unreachable*, needing `341dp` of glyph run
against `300dp` of column. Under `maxLines = 1, softWrap = false` an overrun is
clipped at both ends in silence, so this port could not clamp to the floor and
said so.

**What stacking changes.** The run that has to fit is now the wider *line*.
`HERMES` spans `3.727 em` of glyph advance in the shipped face plus
`.wordmark`'s `0.08em` per character — **4.207 em**; `AGENT` spans `3.261 em`
and is never the constraint. Both numbers are read off
`res/font/collapse_bold.otf` by `CollapseBoldFont`, and
`CollapseBoldFontTest` reproduces the capture's `7.756 em` from the same
advances, so the table below and the pinned Desktop render are the same
measurement:

| Screen | Column | Widest line | Fitted | Block at `0.9` leading | Desktop's `2.75rem` floor |
|---|---|---|---|---|---|
| `w320dp` | 300 dp | `HERMES`, 4.207 em | 71.3 sp | 128.3 dp | cleared |
| `w360dp` | 340 dp | `HERMES`, 4.207 em | 72 sp — the ceiling | 129.6 dp | cleared |
| `w411dp` | 391 dp | `HERMES`, 4.207 em | 72 sp — the ceiling | 129.6 dp | cleared |

So the floor is honoured on every phone width, and honoured by being *met*
rather than by a clamp. There is still no clamp, and the reason is arithmetic:
`fitWordmarkSp` returns the size at which the run exactly fills the column, so a
result below the floor would mean a column that cannot hold the floor, and
raising it would only clip.

**The two ceilings are this port's.** Desktop needs none, because `.fit-text`
runs inside a column bounded by `--composer-width`.

- `WORDMARK_MAX_FONT_SIZE`, 72 sp per line, which binds on every phone at or
  above `w360dp`.
- A height guard, which one line did not need. Two lines are twice as tall, and
  a short slot — a phone in landscape — is where filling the width would push
  the line of copy the wordmark titles off the surface. The lettering may take
  at most half the height its slot offers; on every upright phone the guard is
  slack and the ceiling binds first.

A font scale above 1.0 widens the run; the fit is measured rather than assumed,
so it absorbs that too.

Three tests hold this, for three different reasons. `CollapseBoldFontTest` pins
the shipped face by digest and reports its advances, so the ems above are
properties of a committed file. `WordmarkFitTest` is plain JVM and drives
`fitWordmarkSp` from those advances. `WordmarkFitDeviceTest` runs under
Robolectric with `@GraphicsMode(NATIVE)`, loads `R.font.collapse_bold` through
`ResourcesCompat`, measures the real face at all three widths, and checks that
the lettering lays out as two stacked lines at one size.

**Font scale is proved in the pure test only.** Robolectric does not plumb one
into text layout — a scaled `Density` provided through `LocalDensity`, and one
handed straight to a constructed `TextMeasurer`, both return exactly the
unscaled size — so the device test does not claim it. What matters is the
invariant, not the platform: a run that measures wider yields a proportionally
smaller fit, and `WordmarkFitTest` asserts that with the widened run supplied
directly. On a real device the emulator's own scale is 1.0.

`NATIVE` there is load-bearing: Robolectric's **default legacy** graphics has no
font at all and measures the whole twelve-character wordmark at `32.5px` when
asked for `48sp` — about `0.68em` for twelve glyphs, and not even linearly in
the size. A fit asserted under legacy graphics would be measuring the stub.

The device render at `w320dp` below is the third proof, and the one that is not
a measurement at all.

## The font

**Resolved.** Desktop draws the wordmark in **Collapse**, loaded from
`@nous-research/ui`'s `dist/fonts/Collapse-Bold.woff2` (`styles.css:62-68`), and
this app now draws it in the same file. `res/font/collapse_bold.otf` is that
woff2 with the container removed and nothing else changed — no subsetting, no
re-hinting, no outline conversion, no name-table edit. Digests, the exact
command and the licence line are in [`../fonts.md`](../fonts.md).

Collapse is a commercial Blaze Type face; the repo owner states permission to
use it in this app.

The divergence ledger below used to carry a row for this: the wordmark set in
Desktop's declared fallback, `var(--font-sans)`, because the licence could not
be established from `@nous-research/ui`'s own metadata. That row is gone,
because the difference it recorded is gone. What replaces it is a stricter
claim than "same weight and tracking": `CollapseBoldFontTest` pins the shipped
bytes by digest and reproduces the pinned Desktop capture's `7.756 em` from
them, so the two sides are provably the same outlines.

The face is fixed to the wordmark rather than taken from the preset's font
choice, because `.wordmark` names `'Collapse', var(--font-sans)` and therefore
overrides the theme sans for every skin upstream — including `cyberpunk`, which
sets the rest of the UI in a monospace. `WordmarkFitDeviceTest` asserts that.

`THIRD_PARTY_NOTICES.md` carries the notice.

## Visual report

- report: docs/parity/visual/empty-states/empty-chat-intro-light/report.html
- commit: 1d37f46
- captures: the Android halves of `empty-chat-intro-light`, `empty-chat-intro-dark`
  and `empty-chat-intro-w320dp-dark` were retaken on `emulator-5554` from this
  branch's debug build after the bundled Collapse Bold, the stacked wordmark
  and the session lines; the Desktop halves are unchanged, because Desktop did
  not move. The retaken Android halves show a homed empty session (title
  `New session`, the cwd line beneath the body), which is the case this branch
  adds; Desktop's half is its fresh-draft intro.

Four rendered side-by-sides, one per surface per mode; the table below lists
them all. #152 is closed by this packet.

Desktop was captured from a disposable pinned export at
`3ca096de5f8183cb2e0ec23673f294d5978656a3`; Android from `emulator-5554`
(`sdk_gphone16k_arm64`, Android 17, `1280x2856` at density 480 — `426dp` wide,
font scale 1.0) running this branch's own debug build. Neither side carries a
host, credential, fingerprint or private session text.

| Packet | Desktop | Android | Report |
|---|---|---|---|
| `empty-chat-intro-light` | yes | yes | yes |
| `empty-chat-intro-dark` | yes | yes | yes |
| `sidebar-blank-state-light` | yes | yes | yes |
| `sidebar-blank-state-dark` | yes | yes | yes |
| `empty-chat-intro-w320dp-dark` | — | yes | — |
| `appearance-intro-splash-row-light` | — | yes | — |

Each `report.html` sits beside its two halves under
`docs/parity/visual/empty-states/<name>/`.

The last two are Android-only on purpose. `empty-chat-intro-w320dp-dark` is the
narrowest supported phone, taken with `wm size 960x2140` at density 480 and
reset afterwards; its `contract.json` records the override. It is the render the
fit table above exists for. It was taken when the wordmark was one line, and it
shows what that cost: the lettering fits its column only at a size *below*
Desktop's `2.75rem` floor. It is the before half of this change and is owed a
retake. Desktop has no `w320dp` to compare it against.
`appearance-intro-splash-row-light` is the Appearance row, evidence that the
`Intro Splash` title, description and Off/On control ship verbatim.

**How the blank state was reached on a device.** It needs a session list with
zero rows, and every Hermes profile on the QA Gateway carried sessions; the
`Archived` filter renders `Nothing archived`, a different empty state, and a
never-connected saved connection would have reached only the *disconnected*
variant. A `demo` profile with no sessions was added to the Gateway for the
capture, and the app switched to it — so what is rendered is the state Desktop
renders: connected, projects available, the ghost `New project` live and no
extra line. The disabled variant is covered by `EmptyStateJourneyTest` rather
than by a picture.

The roster is fetched once per connection, so a profile added while the app is
running does not appear until it re-reads; the capture restarted the app to pick
`demo` up. Worth knowing before anyone tries to reproduce this.

Two facts the Desktop contracts settle, so the ledger below is not arguing from
source alone. In the `nous` light skin the wordmark computes to
`rgb(0, 83, 253)` — `#0053FD`, which is `--theme-midground` and therefore this
app's `tokens.accent`; the Android light capture draws it in the same blue. And
the fitted lettering measures `1052.0px` wide in a `135.637px` face, which is
the `7.756 em` the fit table above is built on.

### What the side-by-side shows

- **This bullet describes the captures as they stand, which is before this
  change.** In them the wordmark fills the column on both sides and the face is
  the visible difference: Desktop's Collapse against Android's Roboto Bold,
  every other property — uppercase, weight 700, `0.08em` tracking, `0.9` line
  height, the midground/foreground colour roles — matching. Both halves of that
  sentence are what the retake has to settle: the face is now Desktop's own
  file, and Android's lettering is two lines where Desktop's is one. Those are
  the two rows a reviewer should look hardest at.
- The body line sits directly under the lettering on both, centred, in the
  tertiary ink. Desktop's fits one line in a `1132px` column; Android's wraps to
  two or three in a phone column, which is the measure doing its job.
- Android's splash is centred in the transcript slot between the chrome and the
  composer; Desktop reserves `--composer-measured-height` to achieve the same
  thing in a single scroll container.
- The blank state matches closely: same `root-folder` glyph at the same visual
  size in the quaternary ink, the same caption in the tertiary, the same ghost
  button with an `add` glyph before its label, all centred in the full remaining
  height of the list. Android's target is the 48 dp touch floor rather than
  Desktop's `size="sm"` button, which is the ledgered adaptation and the only
  difference visible in the pair.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| The wordmark is one line, `HERMES AGENT`, fitted to `calc(100% - 1rem)` (`wordmark.tsx:15-45`) | mobile-adaptation | Two lines, `HERMES` over `AGENT`, one shared size, same `0.9` leading and `0.08em` tracking, both centred | Viewport space. Twelve characters plus a space across the `300 dp` a `w320dp` phone leaves sets the lettering at about `38.7 sp` — a heading, not display type — and Desktop's own `2.75rem` floor is unreachable there. Stacking puts the wider of a six- and a five-character run across the same column, so the same fill rule yields `71.3 sp` and clears the floor on every phone width; the table is in **The wordmark's fit** above. Wide layouts stack too rather than reverting to one line, because a tablet is the same app and one identity beats a breakpoint. `INTRO_WORDMARK` stays the accessibility name, so a screen reader hears `HERMES AGENT` once and never the split |
| `.fit-text` sizes the lettering from a container query, unbounded above, floor `2.75rem` (`wordmark.tsx:22-24`, `styles.css:1633-1661`) | mobile-adaptation | One measured layout pass per line at a probe size scaled to the column, clamped at 72 sp per line and by half the slot's height, and still not floored | Compose has no container query, so the fit is measured. Both ceilings are this port's: Desktop's column is bounded by `--composer-width` and a tablet column here is not, and two lines are twice as tall, so a landscape phone would otherwise push the copy line off the surface. The floor is not clamped because a clamp could only ever raise a size the column has already been measured as unable to hold — but stacked, the fit now *meets* the floor everywhere, which is the difference from the one-line port. `WordmarkFitTest`, `WordmarkFitDeviceTest` and `CollapseBoldFontTest` hold it |
| The intro renders only for a fresh draft; a session that owns the view gets `ChatEmptySlot` (`intro-visibility.ts:12-33`, `thread/index.tsx:155`) | mobile-adaptation | The splash also renders for a homed session whose transcript is empty and whose turn is not running | `ChatEmptySlot` has never been ported, so the alternative here was not Desktop's empty slot but the plain `No messages yet` note, which says less than the wordmark and the intro line do. The three guards are unchanged: the Appearance toggle still restores the note when it is `Off`, a running turn still wins, and the loading flash is refused by `SessionSummary.messageCount` — `session.info`'s own count — rather than by assuming an unread transcript is an empty one. `IntroSplashTest` and `EmptyStateJourneyTest` hold each clause |
| The session's working directory and project live in Desktop's own chrome (`app/chat/index.tsx:419,675,734`) | mobile-adaptation | Two small centred lines under the intro copy — the project label in `type.caption`, the cwd's last two segments in the terminal family — and only when a session is homed | Viewport space. Desktop's window has room for a status bar carrying both facts at all times; a phone's chat chrome does not, and the splash is the one moment a session has nothing else on screen. So an opened session says which project it belongs to and which directory it will act in *before* the first instruction, rather than after. A fresh draft has neither and renders exactly what it rendered before. The path is shortened head-first because the tail identifies it, and the project resolves only when the catalog has been read — unknown says nothing rather than guessing |
| The lettering renders twice, the `aria-hidden` twin acting as the `.fit-text` width reference (`wordmark.tsx:38-43`) | mobile-adaptation | Rendered once | The doubled span exists only to feed a CSS container query. Compose measures directly, so the second copy would be a node with no purpose and a second thing for the semantics tree to trip over |
| `mix-blend-plus-lighter` on the wordmark (`wordmark.tsx:33`) | mobile-adaptation | Not painted | Compose has no plus-lighter blend for text without rendering the glyphs into a layer and compositing by hand. The colour role is unchanged: midground in light, foreground in dark |
| The dark wordmark is `text-foreground/90` — 0.90 alpha over the foreground (`wordmark.tsx:33`) | mobile-adaptation | `tokens.textPrimary`, which is 0.94 over the same base (`HermesTokens.kt`) | Four points lighter, and deliberately not compounded: multiplying Desktop's 0.90 into a token already at 0.94 lands at 0.85 and matches neither. The alternative is a raw alpha in a component, which the token rule forbids. the dark side-by-side above is what judges whether four points is visible |
| The intro body is `0.875rem` — 14 px, a step ABOVE Desktop's 13 px body (`styles.css:1610-1613`, contract node 6) | mobile-adaptation | `type.body`, 15 sp, which is this app's rendering of Desktop's 13 px | This scale is stepped up ~1.15x for a phone and `body` is its largest prose step, so the line lands one step lower relative to its own scale than Desktop's does. `caption` — 13 sp, this app's 12 px — would put it two steps lower still |
| The intro body has no gutter of its own: `mx-auto` centres a 34 rem measure inside a column the thread has already inset (`intro.tsx:176`, `styles.css:1609-1613`) | mobile-adaptation | The same 34 rem measure, plus 12 dp of horizontal padding | The splash slot here is a `weight(1f)` sibling of the composer with no inset of its own, so it runs to the screen edge; on a `w320dp` phone the line would otherwise wrap against the bezel |
| Every Desktop empty state is `place-items-center` in its section's height | mobile-adaptation | `EmptyState` centres vertically only when its caller asks (`centered = true`), which today is the transcript's plain note | Nine other callers — the session list's loading, archived and project states, and Relay's four panes — already pass a height-filling modifier, and switching them on would move nine surfaces this change has no render of. Whether Desktop centres *those* is a question for `docs/parity/session-list-sections.md` and `docs/parity/relay-channels-surface.md` and their own renders, not something to settle by side effect here — #158 owns that read. `TextAlign.Center` travels with the same flag for the same reason |
| The blank state's `New project` is a `Button` with its own disabled semantics | mobile-adaptation | A ghost row that publishes `disabled()` and is named by its label alone | `Modifier.clickable(enabled = false)` drops the click action but publishes no disabled state, so a screen reader would announce an ordinary button that does nothing. The label is the only name: `clickable` merges its descendants and a `contentDescription` on the merging node concatenates with the label rather than replacing it |
| The blank state's ghost button is `size="sm"` — a ~28 px control sitting `mt-0.5` under the caption (`section-states.tsx:33-36`) | mobile-adaptation | The same fill-less button at the 48 dp platform touch floor, which widens the caption-to-button gap | A touch target may not be smaller than the floor, and the floor grows the box rather than the glyph or the label. The extra air between `No sessions yet` and `New project` is that growth and nothing else: ink, glyph size, label and order are unchanged. Visible in the light and dark side-by-sides above |
| `Intro`'s copy seed is a per-mount `useState` (`intro.tsx:161`) | mobile-adaptation | `rememberSaveable` | A rotation recreates the Activity and a plain `remember` would reroll the line mid-conversation — a remount Desktop never performs, because its component does not unmount when the window resizes |
| `Intro` picks a body from a personality-keyed set and falls back to a generated per-personality set (`intro.tsx:113-143`) | mobile-adaptation | Only the neutral (`personality: "none"`) set, verbatim | This app does not know the Hermes profile's personality, so the neutral set is the branch Desktop itself takes for `none` / `default`. Choosing any other set would be inventing a personality the app cannot read. `IntroSplashTest` pins all five lines against the jsonl |
| The intro's slot reserves `padding-bottom: var(--composer-measured-height)` so the splash centres above the composer (`styles.css:1606`) | mobile-adaptation | No reservation | The Android transcript slot is a `weight(1f)` sibling of the composer in a `Column`, so the composer's height is already outside the slot the splash centres in. Same result, one fewer measurement |
| Appearance's `Intro Splash` sits between `Backdrop` and `Composer pop-out` in a list that also holds Language, UI scale, Terminal font, Session density, Tab strip and Translucency (`appearance-settings.tsx:461-737`) | mobile-adaptation | Immediately after the skin list | Every Desktop row between the theme picker and this one is a surface this app does not ship, so "after the skins" is Desktop's position with the absent rows removed, not a new one. The rows Desktop has and this app does not are settings, not controls on this surface, and are not this page's ledger |
| The sidebar blank state carries a `New project` button and nothing else (`section-states.tsx:26-42`) | mobile-adaptation | The same glyph, caption and ghost button, plus one extra line — `Connect to a Gateway to start a session.` — and only while the Gateway is not connected | Desktop's sidebar is never disconnected; a phone's routinely is, and the blank state is then the only place on screen that says which action comes first. The line is absent whenever Desktop would have nothing to add, so a connected Android blank state is Desktop's exactly |
| `New project` is always live in Desktop's blank state | mobile-adaptation | Disabled when the Gateway is not connected or serves no project RPC | Same gate the sidebar header's `+` already uses in project mode. The control stays visible and keeps its glyph and label rather than disappearing |
| `SidebarSessionSkeletons` renders while the unfiltered session set is still loading (`section-states.tsx:11-24`, gated at `sidebar/index.tsx:1423,1426`) | mobile-adaptation | The same five placeholder bars, from the same composable this rail already uses for a search read, gated on `ChatUiState.sessionsLoading` | Desktop draws one component for both waits and so does this; the only difference is that Android tags the two cases apart so a journey can tell which wait it is looking at. Without the gate `rows.isEmpty()` rendered `No sessions yet` with a live `+ New project` during the first fetch and after every reconnect — a claim about the account made before the account had answered |
| `ChatEmptySlot` fills an empty *selected* session when the intro is not showing (`components/assistant-ui/thread/index.tsx:155`) | omission | The pre-existing `No messages yet` note, centred — now reached only with the Appearance toggle `Off`, or while a session's row and history are still landing | out-of-scope: #157 — that surface has never been ported and this slice did not add it. Its copy is unchanged; what changed is how rarely it is seen, because the row above gives an empty homed session the splash instead |
| The Appearance rows Desktop has and this app does not (UI scale, Terminal font, Session density, Tab strip, Translucency, Backdrop, Composer pop-out, Reactions, Tips, Tours, Vibe hearts, Tool view, Reasoning disclosure, Embeds) | omission | Absent | out-of-scope: #157 — pre-existing gaps in the Appearance surface that this slice neither introduced nor is scoped to close. This page ledgers the Intro Splash row it added; the surface as a whole owes its own parity page |
