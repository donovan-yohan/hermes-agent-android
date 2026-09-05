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
| Tests | `app/src/test/kotlin/.../ui/chat/IntroSplashTest.kt` and `WordmarkFitTest.kt`, `app/src/test/kotlin/.../data/prefs/HermesPreferencesTest.kt`, `app/src/testDebug/kotlin/.../ui/chat/EmptyStateJourneyTest.kt`, `app/src/testDebug/kotlin/.../ui/appearance/IntroSplashSettingJourneyTest.kt` |

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

What that session *does* see in the gap is the pre-existing
`No messages yet` / `Start a conversation with Hermes.` note, unchanged by this
port and now centred rather than top-anchored. Desktop shows `ChatEmptySlot`
there instead; that surface is not ported and is out of this slice's scope.

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
(`wordmark.tsx:22-24`, `styles.css:1653-1661`). This port clamps the **ceiling
only**, and the floor is the reason.

The pinned light capture measures the visible lettering at `1052.0px` in a
`135.637px` face (`contract.json`, node 4), so `HERMES AGENT` spans **7.756 em**
in Collapse. Roboto Bold at the same `0.08em` tracking measures `405px` at
`48sp` — **8.4375 em**, the wider of the two, which is the face this app draws.
`Wordmark` receives `screen - 2 x 2dp gutter - 16dp inset`, i.e. `screen - 20dp`:

| Screen | Column | Fitted (Roboto) | Fitted (Collapse) | Floor needs, Roboto | Floor reachable |
|---|---|---|---|---|---|
| `w320dp` | 300 dp | 35.6 sp | 38.7 sp | 371.2 dp | no — overruns by 71 dp |
| `w360dp` | 340 dp | 40.3 sp | 43.8 sp | 371.2 dp | no — overruns by 31 dp |
| `w411dp` | 391 dp | 46.3 sp | 50.4 sp | 371.2 dp | yes |

With a floor clamp the two narrow columns would have been raised back to `44sp`
and clipped at both ends in silence, because the wordmark is laid out
`maxLines = 1, softWrap = false`. A font scale above 1.0 widens the run
further; the fit is measured rather than assumed, so it absorbs that too.

Two tests hold this, for two different reasons. `WordmarkFitTest` is plain JVM
and drives `fitWordmarkSp` from the ratio the Desktop capture recorded, so the
provenance of the number is the capture rather than the host. `WordmarkFitDeviceTest`
runs under Robolectric with `@GraphicsMode(NATIVE)` and measures the real
platform face at all three widths, including at font scale 1.5.

`NATIVE` there is load-bearing: Robolectric's **default legacy** graphics has no
font at all and measures the whole twelve-character wordmark at `32.5px` when
asked for `48sp` — about `0.68em` for twelve glyphs, and not even linearly in
the size. A fit asserted under legacy graphics would be measuring the stub. The
`405px` above is the same probe under `NATIVE`.

The device render at `w320dp` below is the third proof, and the one that is not
a measurement at all.

## The font

Desktop draws the wordmark in **Collapse**, loaded from
`@nous-research/ui`'s `dist/fonts/Collapse-Bold.woff2` (`styles.css:61-68`,
`apps/desktop/package.json` pins `@nous-research/ui@0.18.2`). It is not bundled
here, and the reason is not effort:

- `@nous-research/ui@0.18.2` declares `"license": "MIT"` in its `package.json`
  and ships **no** `LICENSE`, `OFL.txt` or any font-specific notice — verified
  against both the npm tarball and the copy installed in the reference
  checkout's `node_modules`. An MIT declaration on a design-system package does
  not, on its own, grant redistribution of a third-party typeface inside it.
- Collapse is a **retail typeface from Blaze Type** (designed by Axel Andre),
  sold commercially. Nothing in the package documents a licence that would let
  this repo ship it in an APK.
- Android's `res/font` cannot read woff2 at all, and woff2 is the only format
  the package ships. Bundling would mean *converting* the face — a modification
  most retail font EULAs forbid outright.

So the licence could not be established, and the rule was to not bundle.
Android draws Desktop's own declared fallback, `var(--font-sans)`, at Desktop's
weight (700), casing (uppercase), tracking (0.08em) and line height (0.9).
`THIRD_PARTY_NOTICES.md` is unchanged, because nothing was added to it.

This is the largest visible difference in this port and the one the Android
half of the report is most needed for: Collapse is a high-contrast display face
and a platform bold sans is not, so the two silhouettes differ even though
every measurable property matches.

## Visual report

- report: docs/parity/visual/empty-states/empty-chat-intro-light/report.html
- commit: 1d37f46

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
fit table above exists for: the lettering fits its column with margin at a size
below Desktop's `2.75rem` floor. Desktop has no `w320dp` to compare it against.
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

- The wordmark fills the column on both sides, and the face is the visible
  difference: Desktop's Collapse is a high-contrast display serif, Android's is
  Roboto Bold. Every other property — uppercase, weight 700, `0.08em` tracking,
  `0.9` line height, the midground/foreground colour roles — matches. That row
  is the ledger's first, and it is the one a reviewer should look hardest at.
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
| The wordmark is set in Collapse, a Blaze Type retail face loaded from `@nous-research/ui` (`styles.css:61-68`) | mobile-adaptation | Desktop's own declared fallback, `var(--font-sans)`, at the same weight, casing, tracking and line height | The licence could not be established: the package declares MIT and ships no font notice, Collapse is sold commercially by Blaze Type, and `res/font` cannot read the only format shipped (woff2), so bundling would require converting a face this repo has no right to convert. The full finding is in **The font** above. The silhouette difference is real, and the light and dark side-by-sides above are what a reviewer judges it on |
| `.fit-text` sizes the lettering from a container query, unbounded above, floor `2.75rem` (`wordmark.tsx:22-24`, `styles.css:1633-1661`) | mobile-adaptation | One measured layout pass at a probe size scaled to the column, clamped at 72 sp and **not** floored | Compose has no container query, so the fit is measured. The ceiling is this port's, because Desktop's column is bounded by `--composer-width` and a tablet column here is not. The floor is dropped because a phone column cannot hold it: at `7.756 em` the run needs `341 dp` at `2.75rem` against `300 dp` on a `w320dp` screen, and `maxLines = 1, softWrap = false` clips an overrun in silence. The per-width table is in **The wordmark's fit** above and `WordmarkFitTest` holds it |
| The lettering renders twice, the `aria-hidden` twin acting as the `.fit-text` width reference (`wordmark.tsx:38-43`) | mobile-adaptation | Rendered once | The doubled span exists only to feed a CSS container query. Compose measures directly, so the second copy would be a node with no purpose and a second thing for the semantics tree to trip over |
| `mix-blend-plus-lighter` on the wordmark (`wordmark.tsx:33`) | mobile-adaptation | Not painted | Compose has no plus-lighter blend for text without rendering the glyphs into a layer and compositing by hand. The colour role is unchanged: midground in light, foreground in dark |
| The dark wordmark is `text-foreground/90` — 0.90 alpha over the foreground (`wordmark.tsx:33`) | mobile-adaptation | `tokens.textPrimary`, which is 0.94 over the same base (`HermesTokens.kt`) | Four points lighter, and deliberately not compounded: multiplying Desktop's 0.90 into a token already at 0.94 lands at 0.85 and matches neither. The alternative is a raw alpha in a component, which the token rule forbids. the dark side-by-side above is what judges whether four points is visible |
| The intro body is `0.875rem` — 14 px, a step ABOVE Desktop's 13 px body (`styles.css:1610-1613`, contract node 6) | mobile-adaptation | `type.body`, 15 sp, which is this app's rendering of Desktop's 13 px | This scale is stepped up ~1.15x for a phone and `body` is its largest prose step, so the line lands one step lower relative to its own scale than Desktop's does. `caption` — 13 sp, this app's 12 px — would put it two steps lower still |
| The intro body has no gutter of its own: `mx-auto` centres a 34 rem measure inside a column the thread has already inset (`intro.tsx:176`, `styles.css:1609-1613`) | mobile-adaptation | The same 34 rem measure, plus 12 dp of horizontal padding | The splash slot here is a `weight(1f)` sibling of the composer with no inset of its own, so it runs to the screen edge; on a `w320dp` phone the line would otherwise wrap against the bezel |
| Every Desktop empty state is `place-items-center` in its section's height | mobile-adaptation | `EmptyState` centres vertically only when its caller asks (`centered = true`), which today is the transcript's plain note | Nine other callers — the session list's loading, archived and project states, and Relay's four panes — already pass a height-filling modifier, and switching them on would move nine surfaces this change has no render of. Whether Desktop centres *those* is a question for `docs/parity/session-list-sections.md` and `docs/parity/relay-channels-surface.md` and their own renders, not something to settle by side effect here. `TextAlign.Center` travels with the same flag for the same reason |
| The blank state's `New project` is a `Button` with its own disabled semantics | mobile-adaptation | A ghost row that publishes `disabled()` and is named by its label alone | `Modifier.clickable(enabled = false)` drops the click action but publishes no disabled state, so a screen reader would announce an ordinary button that does nothing. The label is the only name: `clickable` merges its descendants and a `contentDescription` on the merging node concatenates with the label rather than replacing it |
| `Intro`'s copy seed is a per-mount `useState` (`intro.tsx:161`) | mobile-adaptation | `rememberSaveable` | A rotation recreates the Activity and a plain `remember` would reroll the line mid-conversation — a remount Desktop never performs, because its component does not unmount when the window resizes |
| `Intro` picks a body from a personality-keyed set and falls back to a generated per-personality set (`intro.tsx:113-143`) | mobile-adaptation | Only the neutral (`personality: "none"`) set, verbatim | This app does not know the Hermes profile's personality, so the neutral set is the branch Desktop itself takes for `none` / `default`. Choosing any other set would be inventing a personality the app cannot read. `IntroSplashTest` pins all five lines against the jsonl |
| The intro's slot reserves `padding-bottom: var(--composer-measured-height)` so the splash centres above the composer (`styles.css:1606`) | mobile-adaptation | No reservation | The Android transcript slot is a `weight(1f)` sibling of the composer in a `Column`, so the composer's height is already outside the slot the splash centres in. Same result, one fewer measurement |
| Appearance's `Intro Splash` sits between `Backdrop` and `Composer pop-out` in a list that also holds Language, UI scale, Terminal font, Session density, Tab strip and Translucency (`appearance-settings.tsx:461-737`) | mobile-adaptation | Immediately after the skin list | Every Desktop row between the theme picker and this one is a surface this app does not ship, so "after the skins" is Desktop's position with the absent rows removed, not a new one. The rows Desktop has and this app does not are settings, not controls on this surface, and are not this page's ledger |
| The sidebar blank state carries a `New project` button and nothing else (`section-states.tsx:26-42`) | mobile-adaptation | The same glyph, caption and ghost button, plus one extra line — `Connect to a Gateway to start a session.` — and only while the Gateway is not connected | Desktop's sidebar is never disconnected; a phone's routinely is, and the blank state is then the only place on screen that says which action comes first. The line is absent whenever Desktop would have nothing to add, so a connected Android blank state is Desktop's exactly |
| `New project` is always live in Desktop's blank state | mobile-adaptation | Disabled when the Gateway is not connected or serves no project RPC | Same gate the sidebar header's `+` already uses in project mode. The control stays visible and keeps its glyph and label rather than disappearing |
| `SidebarSessionSkeletons` renders while the unfiltered session set is still loading (`section-states.tsx:11-24`, gated at `sidebar/index.tsx:1423,1426`) | mobile-adaptation | The same five placeholder bars, from the same composable this rail already uses for a search read, gated on `ChatUiState.sessionsLoading` | Desktop draws one component for both waits and so does this; the only difference is that Android tags the two cases apart so a journey can tell which wait it is looking at. Without the gate `rows.isEmpty()` rendered `No sessions yet` with a live `+ New project` during the first fetch and after every reconnect — a claim about the account made before the account had answered |
| `ChatEmptySlot` fills an empty *selected* session when the intro is not showing (`components/assistant-ui/thread/index.tsx:155`) | omission | The pre-existing `No messages yet` note, now centred | out-of-scope: #157 — that surface has never been ported and this slice did not add it. The note it replaces was already there and its copy is unchanged, per the port brief |
| The Appearance rows Desktop has and this app does not (UI scale, Terminal font, Session density, Tab strip, Translucency, Backdrop, Composer pop-out, Reactions, Tips, Tours, Vibe hearts, Tool view, Reasoning disclosure, Embeds) | omission | Absent | out-of-scope: #157 — pre-existing gaps in the Appearance surface that this slice neither introduced nor is scoped to close. This page ledgers the Intro Splash row it added; the surface as a whole owes its own parity page |
