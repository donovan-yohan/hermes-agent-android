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
| Appearance row | `ui/appearance/AppearanceScreen.kt`, `IntroSplashRow` |
| Persistence | `data/prefs/HermesPreferences.kt`, `appearance.introSplash` |
| Tests | `app/src/test/kotlin/.../ui/chat/IntroSplashTest.kt`, `app/src/testDebug/kotlin/.../ui/chat/EmptyStateJourneyTest.kt` |

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

- pending: #152

**The Desktop halves exist and are stored in this repo**, captured from a
disposable pinned export at `3ca096de5f8183cb2e0ec23673f294d5978656a3` against
seeded state with no host, credential, fingerprint or private session text:

| Name | Path |
|---|---|
| Empty chat, intro splash, light | `docs/parity/visual/empty-states/empty-chat-intro-light/desktop/` |
| Empty chat, intro splash, dark | `docs/parity/visual/empty-states/empty-chat-intro-dark/desktop/` |
| Sidebar blank state, light | `docs/parity/visual/empty-states/sidebar-blank-state-light/desktop/` |
| Sidebar blank state, dark | `docs/parity/visual/empty-states/sidebar-blank-state-dark/desktop/` |

Each carries `reference.png` and `contract.json`. They are deliberately **not**
recorded as `report:` — `scripts/check-parity-evidence.py` refuses a page that
claims a report and owes one at the same time, and there is no `report.html`
because a side-by-side needs two sides. The Android half is owed by #152: the
emulator was in another lane's hands when this branch landed and no device was
touched.

Two facts the Desktop contracts settle, so the ledger below is not arguing from
source alone. In the `nous` light skin the wordmark computes to
`rgb(0, 83, 253)` — `#0053FD`, which is `--theme-midground` and therefore this
app's `tokens.accent`. And `.fit-text` resolved to `135.6px` in a `1132px`
column, a ratio of about `0.12`; on a phone column that lands within a couple of
sp of Desktop's own `2.75rem` floor, which is why the Android fit clamps there.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| The wordmark is set in Collapse, a Blaze Type retail face loaded from `@nous-research/ui` (`styles.css:61-68`) | mobile-adaptation | Desktop's own declared fallback, `var(--font-sans)`, at the same weight, casing, tracking and line height | The licence could not be established: the package declares MIT and ships no font notice, Collapse is sold commercially by Blaze Type, and `res/font` cannot read the only format shipped (woff2), so bundling would require converting a face this repo has no right to convert. The full finding is in **The font** above. The silhouette difference is real and is what #152 owes a render of |
| `.fit-text` sizes the lettering from a container query, unbounded above, floor `2.75rem` (`wordmark.tsx:22-24`, `styles.css:1633-1661`) | mobile-adaptation | One measured layout pass at a probe size scaled to the column, clamped to 44–72 sp | Compose has no container query. The floor is Desktop's `--fit-min` at a 16 px root; the ceiling is this port's, because Desktop's column is bounded by `--composer-width` and a tablet column here is not — unbounded fitting sets twelve characters taller than the composer. The Desktop contract's own `135.6px / 1132px` ratio puts a phone column within a couple of sp of the floor |
| The lettering renders twice, the `aria-hidden` twin acting as the `.fit-text` width reference (`wordmark.tsx:38-43`) | mobile-adaptation | Rendered once | The doubled span exists only to feed a CSS container query. Compose measures directly, so the second copy would be a node with no purpose and a second thing for the semantics tree to trip over |
| `mix-blend-plus-lighter` on the wordmark (`wordmark.tsx:33`) | mobile-adaptation | Not painted | Compose has no plus-lighter blend for text without rendering the glyphs into a layer and compositing by hand. The colour role is unchanged: midground in light, foreground in dark |
| `Intro` picks a body from a personality-keyed set and falls back to a generated per-personality set (`intro.tsx:113-143`) | mobile-adaptation | Only the neutral (`personality: "none"`) set, verbatim | This app does not know the Hermes profile's personality, so the neutral set is the branch Desktop itself takes for `none` / `default`. Choosing any other set would be inventing a personality the app cannot read. `IntroSplashTest` pins all five lines against the jsonl |
| The intro's slot reserves `padding-bottom: var(--composer-measured-height)` so the splash centres above the composer (`styles.css:1606`) | mobile-adaptation | No reservation | The Android transcript slot is a `weight(1f)` sibling of the composer in a `Column`, so the composer's height is already outside the slot the splash centres in. Same result, one fewer measurement |
| Appearance's `Intro Splash` sits between `Backdrop` and `Composer pop-out` in a list that also holds Language, UI scale, Terminal font, Session density, Tab strip and Translucency (`appearance-settings.tsx:461-737`) | mobile-adaptation | Immediately after the skin list | Every Desktop row between the theme picker and this one is a surface this app does not ship, so "after the skins" is Desktop's position with the absent rows removed, not a new one. The rows Desktop has and this app does not are settings, not controls on this surface, and are not this page's ledger |
| The sidebar blank state carries a `New project` button and nothing else (`section-states.tsx:26-42`) | mobile-adaptation | The same glyph, caption and ghost button, plus one extra line — `Connect to a Gateway to start a session.` — and only while the Gateway is not connected | Desktop's sidebar is never disconnected; a phone's routinely is, and the blank state is then the only place on screen that says which action comes first. The line is absent whenever Desktop would have nothing to add, so a connected Android blank state is Desktop's exactly |
| `New project` is always live in Desktop's blank state | mobile-adaptation | Disabled when the Gateway is not connected or serves no project RPC | Same gate the sidebar header's `+` already uses in project mode. The control stays visible and keeps its glyph and label rather than disappearing |
| `SidebarSessionSkeletons` renders while the unfiltered session set is still loading (`section-states.tsx:11-24`, gated at `sidebar/index.tsx:1426`) | omission | Not rendered | out-of-scope: #152 — the skeletons are a separate loading state with their own row chrome, deliberately excluded from this slice, which is about the two centred empty states. The Android list shows its existing loading copy in that window, unchanged |
| `ChatEmptySlot` fills an empty *selected* session when the intro is not showing (`components/assistant-ui/thread/index.tsx:155`) | omission | The pre-existing `No messages yet` note, now centred | out-of-scope: #152 — that surface has never been ported and this slice did not add it. The note it replaces was already there and its copy is unchanged, per the port brief |
| The Appearance rows Desktop has and this app does not (UI scale, Terminal font, Session density, Tab strip, Translucency, Backdrop, Composer pop-out, Reactions, Tips, Tours, Vibe hearts, Tool view, Reasoning disclosure, Embeds) | omission | Absent | out-of-scope: #152 — pre-existing gaps in the Appearance surface that this slice neither introduced nor is scoped to close. This page ledgers the Intro Splash row it added; the surface as a whole owes its own parity page |
