package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlin.math.abs
import kotlin.random.Random

/*
 * The empty-chat intro splash.
 *
 * Desktop's `Intro` (`apps/desktop/src/components/chat/intro.tsx:160-179` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`) is two things stacked and centred
 * in the transcript slot: the oversized `HERMES AGENT` wordmark, and exactly
 * one line of intro body copy. Its `headline` field is parsed and never
 * rendered — `intro.tsx:176` draws `copy.body` alone — so this port carries the
 * bodies and nothing else.
 *
 * Android does not know the Hermes profile's personality, so only the neutral
 * set applies: `neutralCopy()` (`intro.tsx:103-105`) prefers the `none` records
 * of `intro-copy.jsonl` and falls back to `FALLBACK_COPY`. The `none` records
 * exist (`intro-copy.jsonl:71-75` @ the same SHA), so those five lines are the
 * set, verbatim.
 */

/**
 * Desktop's `WORDMARK` (`intro.tsx:150` @ `3ca096de`), and the name this splash
 * publishes to a screen reader.
 *
 * It is deliberately still one string: [INTRO_WORDMARK_LINES] is how the
 * lettering is *drawn*, and a reader must not hear the split.
 */
const val INTRO_WORDMARK = "HERMES AGENT"

/**
 * How the wordmark is drawn here: `HERMES` over `AGENT`, one shared size.
 *
 * Desktop fits the whole string onto one line (`wordmark.tsx:15-45` @
 * `3ca096de`), which its chat column has the width for. A phone column does
 * not: twelve characters plus a space across `300dp` sets the lettering at
 * roughly the size of a heading, not a wordmark. Two lines put the wider of a
 * six- and a five-character run across the same column, so the same rule —
 * fill `calc(100% - 1rem)` — yields display lettering again. Ledgered in
 * `docs/parity/empty-states.md`.
 */
val INTRO_WORDMARK_LINES: List<String> = listOf("HERMES", "AGENT")

/** Names the splash for the Compose journeys; never spoken. */
const val INTRO_SPLASH_TAG = "Intro splash"

/**
 * The neutral intro bodies, verbatim from the `personality: "none"` records of
 * `apps/desktop/src/components/chat/intro-copy.jsonl:71-75` @ `3ca096de`, in
 * file order — the order `pickCopy` indexes into.
 */
val NEUTRAL_INTRO_COPY: List<String> = listOf(
    "Ask a question, paste an error, or point me at a repo. I can read code, run tools, and help you ship.",
    "Describe the task in your own words. I'll pick the right tools, explain my plan, and check in before " +
        "risky steps.",
    "Drop a file path, a traceback, or a rough idea. I'll investigate, suggest next steps, and keep things " +
        "reversible.",
    "Search the repo, edit files, run tests, open PRs. Tell me the goal and I'll handle the mechanical parts.",
    "Type a task, question, or snippet. I remember the session, cite my sources, and stop to ask when I'm unsure.",
)

/**
 * Desktop's `pickCopy` (`intro.tsx:146-148` @ `3ca096de`): the seed indexes the
 * set, and a seed larger than the set wraps.
 *
 * `abs` is taken in `Long` because `abs(Int.MIN_VALUE)` is still negative and
 * would index backwards; Desktop's `Math.abs` runs on a double and cannot.
 */
fun pickIntroCopy(seed: Int): String =
    NEUTRAL_INTRO_COPY[(abs(seed.toLong()) % NEUTRAL_INTRO_COPY.size).toInt()]

/**
 * Whether the splash renders.
 *
 * Desktop's `shouldShowIntro` (`apps/desktop/src/app/chat/intro-visibility.ts:12-33`
 * @ `3ca096de`) takes eight inputs. Four of them are one fact on a phone and two
 * do not exist here at all:
 *
 * - `enabled` is the Appearance toggle and outranks everything, exactly as it
 *   does upstream. Same here, and it is still the only off switch: with the
 *   toggle off an empty chat is the plain `No messages yet` note it has always
 *   been.
 * - `primary` and `auxiliaryWindow` are Desktop's window model. This app has one
 *   window, so there is no non-primary surface to exclude.
 * - `freshDraftReady`, `routedSessionView`, `selectedSessionId` and
 *   `activeSessionId` are four ways of asking whether a session owns the view.
 *   Here that is one field: [ChatUiState.activeSessionId] is null exactly when
 *   the composer is on a fresh draft.
 * - `messagesEmpty` is [transcriptEmpty].
 *
 * [turnRunning] is this app's own clause and not a Desktop one: the transcript
 * already refuses its empty branch while a turn is in flight, and the splash
 * must not paint over the progress row that replaces it.
 *
 * **A homed session with nothing in it splashes too, and Desktop's does not.**
 * Upstream a session that owns the view is never the intro's case, because
 * Desktop puts `ChatEmptySlot` there instead. This app has never ported that
 * surface, so the alternative here is not Desktop's empty slot but a note that
 * says less than the wordmark does. Ledgered in `docs/parity/empty-states.md`.
 *
 * That change is what [sessionMessageCount] is for. `ChatUiState.transcript` is
 * read straight out of the cache (`ChatViewModel.kt:735`), so a session whose
 * history has not been fetched yet is *also* an empty transcript, and the
 * splash must not flash over it for the frames that read takes. There is no
 * "history loaded" fact in this state, so the count the Gateway itself
 * reports — `session.info`'s `message_count`, carried on
 * [com.hermesagent.mobile.data.session.SessionSummary.messageCount] — is the
 * one used: null while the row has not landed at all, which is the frame after
 * `session.create` and the frames while a resumed session's row is in flight.
 * A Gateway that reports no `message_count` defaults it to zero and can
 * therefore still flash; that is the same window Desktop's own `messagesEmpty`
 * has, and it closes as soon as the first row arrives.
 */
fun shouldShowIntroSplash(
    enabled: Boolean,
    activeSessionId: String?,
    transcriptEmpty: Boolean,
    turnRunning: Boolean,
    sessionMessageCount: Int? = null,
): Boolean {
    if (!enabled || !transcriptEmpty || turnRunning) return false
    // A fresh draft is Desktop's own case and needs no count to vouch for it.
    if (activeSessionId == null) return true
    return sessionMessageCount == 0
}

/**
 * What the splash can say about the session it titles.
 *
 * Empty for a fresh draft, which has neither a project nor a working
 * directory — so Desktop's own case renders exactly what it rendered before.
 * A value class rather than a ViewModel read, because the splash is a
 * composable in `ui/` and the resolution is state the screen already holds.
 */
data class IntroSplashContext(
    /** `ProjectSummary.label` for the project this session belongs to, if known. */
    val projectLabel: String? = null,
    /** `session.info`'s exact cwd, `SessionSummary.worktreePath`. */
    val worktreePath: String? = null,
)

/**
 * The splash itself, centred in whatever slot it is given.
 *
 * @param seed Desktop rolls one per mount (`intro.tsx:161`); so does this, and
 *   the parameter is here so a test can pin the line it gets.
 * @param context The homed session's project and working directory, or empty
 *   on a fresh draft.
 */
@Composable
fun IntroSplash(
    modifier: Modifier = Modifier,
    // `rememberSaveable`, not `remember`: Desktop's `useState` seed lives as
    // long as the mounted component, and a rotation here would otherwise
    // reroll the line mid-conversation — a remount Desktop never performs.
    seed: Int = rememberSaveable { Random.nextInt(0, 100_000) },
    context: IntroSplashContext = IntroSplashContext(),
) {
    val tokens = HermesTheme.tokens
    Box(
        modifier = modifier.fillMaxWidth().testTag(INTRO_SPLASH_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = INTRO_SPLASH_GUTTER, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Wordmark()
            Text(
                text = pickIntroCopy(seed),
                // `[data-slot='aui_intro'] p:last-child` @ `styles.css:1609-1614`:
                // tertiary ink, centred, and held to Desktop's own
                // `max-width: 34rem` reading measure — which a phone column
                // never reaches and a tablet's does.
                //
                // `body`, not `caption`. Desktop sets this line at `0.875rem` —
                // 14px, a step ABOVE its 13px `--conversation-text-font-size`,
                // confirmed by the pinned contract's node 6. This app's largest
                // prose step is `body` at 15sp, so the line lands one step lower
                // relative to its own scale than Desktop's does; `caption`
                // (13sp, Desktop's 12px) would put it two steps lower still.
                // Ledgered in `docs/parity/empty-states.md`.
                style = HermesTheme.type.body,
                color = tokens.textTertiary,
                textAlign = TextAlign.Center,
                // Desktop needs no gutter: `mx-auto` centres the 34rem measure
                // inside a column already inset by the thread's own padding.
                // The splash slot here runs to the screen edge, so the line
                // carries its own. Ledgered.
                modifier = Modifier.widthIn(max = 544.dp).padding(horizontal = 12.dp),
            )
            SessionContextLines(context)
        }
    }
}

/**
 * Where the homed session is working, under the line of copy.
 *
 * Desktop puts the same two facts in its own chrome rather than under the
 * wordmark (`apps/desktop/src/app/chat/index.tsx:419,675,734` @ `3ca096de`),
 * because its window has room for a status bar that carries them all the time.
 * This app's chat chrome does not, and the splash is the one moment a session
 * has nothing else to show — so a session that has just been opened says which
 * project it belongs to and which directory it will act in *before* the first
 * instruction is typed, rather than after. Ledgered in
 * `docs/parity/empty-states.md`.
 *
 * Renders nothing at all when neither fact is known, which includes every
 * fresh draft.
 */
@Composable
private fun SessionContextLines(context: IntroSplashContext) {
    val tokens = HermesTheme.tokens
    val project = context.projectLabel?.takeIf { it.isNotBlank() }
    val path = context.worktreePath?.takeIf { it.isNotBlank() }?.let(::shortenWorktreePath)
    if (project == null && path == null) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (project != null) {
            Text(
                text = project,
                style = HermesTheme.type.caption,
                color = tokens.textTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (path != null) {
            Text(
                text = path,
                // A path is not prose: Desktop sets every one it shows in the
                // terminal family, and a proportional face makes two similar
                // directories hard to tell apart at caption size.
                style = HermesTheme.type.code.copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = tokens.textQuaternary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The tail of a working directory: its last two segments, marked as shortened.
 *
 * A cwd is long and its *end* is the part that identifies it, so the head is
 * what goes. Done here rather than with `TextOverflow.StartEllipsis` because
 * this way the string a test reads is the string a person sees, and one line
 * of arithmetic is cheaper to prove than a layout pass. `Ellipsis` still
 * trails the result, for the case where even two segments overrun.
 *
 * A path of two segments or fewer is already its own tail and is left alone,
 * including `/` itself.
 */
internal fun shortenWorktreePath(path: String): String {
    val trimmed = path.trimEnd('/')
    val segments = trimmed.split('/').filter { it.isNotEmpty() }
    if (segments.size <= 2) return if (trimmed.isEmpty()) path else trimmed
    return "…/" + segments.takeLast(2).joinToString("/")
}


/**
 * Desktop's display lettering (`components/chat/wordmark.tsx:15-45` and
 * `styles.css:1616-1673` @ `3ca096de`), stacked.
 *
 * `.fit-text` sizes the lettering from a container query so it fills
 * `calc(100% - 1rem)` of its column, with a `2.75rem` floor. Compose has no
 * container query, so the same rule is measured: lay each line out once at a
 * probe size, scale by the ratio the column asks for, and clamp.
 *
 * Three things are this port's rather than Desktop's, all ledgered in
 * `docs/parity/empty-states.md`:
 *
 * - **Two lines.** [INTRO_WORDMARK_LINES], sharing one size, at Desktop's `0.9`
 *   leading and `0.08em` tracking. The wider line is what fills the column, so
 *   the fit rule is unchanged — only what it is applied to. [INTRO_WORDMARK] is
 *   still the accessibility name, so a reader hears `HERMES AGENT` once and
 *   does not hear the split.
 * - **A ceiling.** Desktop has none, because `.fit-text` runs inside a column
 *   bounded by `--composer-width`; a tablet column here is not.
 * - **A height guard.** Two lines are twice as tall as one, and a short slot —
 *   a phone in landscape — is the case where filling the width would push the
 *   line of copy the wordmark titles off the screen. The lettering may take at
 *   most [WORDMARK_HEIGHT_SHARE] of the height its slot offers.
 *
 * The face **is** Desktop's. `res/font/collapse_bold.otf` is the same Collapse
 * Bold `styles.css:61-68` loads, decompressed from the pinned checkout's own
 * copy of the file; provenance is in `docs/fonts.md`. It is fixed to the
 * wordmark rather than taken from the preset's `HermesFontChoice`, because
 * `.wordmark` overrides `var(--font-sans)` for every skin upstream — including
 * the monospace-everything one.
 */
@Composable
fun Wordmark(
    modifier: Modifier = Modifier,
    label: String = INTRO_WORDMARK,
    lines: List<String> = INTRO_WORDMARK_LINES,
    maxFontSize: TextUnit = WORDMARK_MAX_FONT_SIZE,
) {
    val tokens = HermesTheme.tokens
    val base = HermesTheme.type.wordmark
    // `text-midground` in light, `text-foreground/90` in dark
    // (`wordmark.tsx:33` @ `3ca096de`). `tokens.accent` IS the resolved
    // midground — `--ui-accent: var(--theme-midground)` (`styles.css:207`) —
    // and the pinned light contract computes `rgb(0, 83, 253)`, which is `nous`
    // `#0053FD` exactly. The dark half is `textPrimary`, this app's own 0.94
    // alpha over the same base against Desktop's 0.90; the four points are
    // ledgered rather than compounded, because multiplying 0.9 into a token
    // that is already 0.94 would land at 0.85 and match neither.
    val ink = if (HermesTheme.isDark) tokens.textPrimary else tokens.accent
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val targetPx = with(density) {
            (this@BoxWithConstraints.maxWidth - WORDMARK_INSET).coerceAtLeast(0.dp).toPx()
        }
        // The height the slot offers, in sp so it can bound a font size
        // directly. An unbounded slot — a preview, or the splash composed on
        // its own in a test — yields `Dp.Infinity` and therefore an infinite
        // budget, which leaves [maxFontSize] the only ceiling.
        val blockSp = with(density) { this@BoxWithConstraints.maxHeight.toSp().value }
        val size = remember(lines, targetPx, blockSp, density.fontScale, base, maxFontSize) {
            fitWordmarkFontSize(measurer, lines, base, targetPx, maxFontSize, blockSp)
        }
        Text(
            text = lines.joinToString("\n"),
            style = base.copy(
                fontSize = size,
                lineHeight = (size.value * WORDMARK_LINE_HEIGHT).sp,
            ),
            color = ink,
            // The only breaks are the ones written above; nothing wraps.
            maxLines = lines.size,
            softWrap = false,
            textAlign = TextAlign.Center,
            // One name, not two words with a pause between them. The visible
            // split is a layout decision and `HERMES AGENT` is still what this
            // says.
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/**
 * This port's ceiling. Desktop has none, because `.fit-text` runs inside a
 * column bounded by `--composer-width`; a tablet column here is not, and
 * unbounded fitting sets the wordmark taller than the surface it titles.
 *
 * It is a **per-line** size: two lines at this size occupy
 * `2 x 0.9 x 72 = 129.6sp`, which is the block [WORDMARK_HEIGHT_SHARE] then
 * has to fit.
 */
internal val WORDMARK_MAX_FONT_SIZE = 72.sp

/**
 * Desktop's `.wordmark` line height, `0.9` (`styles.css:1631` @ `3ca096de`).
 * The block a stacked wordmark occupies is `lines x this x size`.
 */
internal const val WORDMARK_LINE_HEIGHT = 0.9f

/**
 * How much of its slot's height the lettering may take.
 *
 * The splash is two things: the wordmark and the one line of copy it titles
 * (`intro.tsx:160-179`). Filling the width is the rule; filling the *height* is
 * not, and on a short slot — a phone in landscape — an unguarded two-line fit
 * would push the line of copy out of the surface entirely. Half leaves the
 * copy, its gap and the splash's own vertical padding the other half, and on
 * every upright phone the guard is slack: the ceiling above binds first.
 */
internal const val WORDMARK_HEIGHT_SHARE = 0.5f

/**
 * Desktop's `--fit-min`, `2.75rem` at a 16px root (`wordmark.tsx:22`), recorded
 * because it is a value this port measures against rather than clamps to.
 *
 * On one line it was unreachable: `HERMES AGENT` spans `7.756 em` in Collapse
 * (the pinned capture measures `1052.0px` in a `135.637px` face), so `2.75rem`
 * needs `341dp` of glyph run against the `300dp` a `w320dp` phone leaves — and
 * a run wider than its box under `maxLines = 1, softWrap = false` is clipped at
 * both ends in silence. Stacking the wordmark changes that: the widest line is
 * `HERMES`, and the fit now clears Desktop's floor on every phone width
 * without a clamp. `WordmarkFitTest` and `WordmarkFitDeviceTest` measure it.
 *
 * A clamp is still not applied, and the reason is arithmetic rather than
 * caution: [fitWordmarkSp] returns the size at which the run exactly fills the
 * column, so a result below this floor is a column that cannot hold the floor,
 * and raising it would only clip. The floor is honoured by being met.
 */
internal val WORDMARK_MIN_FONT_SIZE_DESKTOP = 44.sp

/** Desktop's `calc(100% - 1rem)` (`wordmark.tsx:24`). */
internal val WORDMARK_INSET = 16.dp

/** `px-0.5` — the intro's own padding at the narrow breakpoint (`intro.tsx:170`). */
internal val INTRO_SPLASH_GUTTER = 2.dp

/**
 * One layout pass per line at a probe size, handed to [fitWordmarkSp] for the
 * arithmetic.
 *
 * The **widest** line is the one measured against the column, because one size
 * is shared: fit the narrower line and the wider one overruns.
 */
internal fun fitWordmarkFontSize(
    measurer: TextMeasurer,
    lines: List<String>,
    style: TextStyle,
    targetWidthPx: Float,
    maxFontSize: TextUnit = WORDMARK_MAX_FONT_SIZE,
    slotHeightSp: Float = Float.POSITIVE_INFINITY,
): TextUnit {
    val probe = WORDMARK_PROBE_FONT_SIZE
    val widest = lines.maxOfOrNull { line ->
        measurer.measure(
            text = AnnotatedString(line),
            style = style.copy(fontSize = probe),
            softWrap = false,
            maxLines = 1,
        ).size.width.toFloat()
    } ?: 0f
    return fitWordmarkSp(
        probeSp = probe.value,
        probeRunPx = widest,
        targetWidthPx = targetWidthPx,
        maxSp = maxFontSize.value,
        lineCount = lines.size,
        slotHeightSp = slotHeightSp,
    ).sp
}

/**
 * The fit itself: scale the probe by the ratio the column asks for, and clamp
 * **above only** — by [maxSp], and by the height [slotHeightSp] leaves for
 * [lineCount] lines at [WORDMARK_LINE_HEIGHT].
 *
 * Kept separate from the measuring so it can be tested against real type
 * metrics: `WordmarkFitTest` drives it from the ems the bundled face reports
 * and the pinned Desktop capture recorded, and `WordmarkFitDeviceTest` from the
 * face Android actually loads, under Robolectric's `NATIVE` graphics.
 * Robolectric's *default* graphics loads no font at all — it measures the whole
 * twelve-character wordmark at 32.5 px when asked for 48 sp, and not even
 * linearly in the size — so a fit asserted under it would be measuring the
 * stub. See `docs/parity/empty-states.md`.
 *
 * There is no floor; see [WORDMARK_MIN_FONT_SIZE_DESKTOP] for why one would
 * only ever clip.
 *
 * A probe run or target of zero — an unmeasured column, a zero-width slot, a
 * font with no metrics — has no ratio to scale by, so it yields nothing to draw
 * rather than a guess that would overflow.
 */
internal fun fitWordmarkSp(
    probeSp: Float,
    probeRunPx: Float,
    targetWidthPx: Float,
    maxSp: Float,
    lineCount: Int = INTRO_WORDMARK_LINES.size,
    slotHeightSp: Float = Float.POSITIVE_INFINITY,
): Float {
    if (targetWidthPx <= 0f || probeRunPx <= 0f || probeSp <= 0f || lineCount <= 0) return 0f
    val byHeight = (slotHeightSp * WORDMARK_HEIGHT_SHARE) / (lineCount * WORDMARK_LINE_HEIGHT)
    val ceiling = minOf(maxSp, byHeight)
    if (ceiling <= 0f) return 0f
    return (probeSp * (targetWidthPx / probeRunPx)).coerceAtMost(ceiling)
}

/** Arbitrary, and cancels out of the ratio; large enough to keep hinting noise small. */
internal val WORDMARK_PROBE_FONT_SIZE = 48.sp
