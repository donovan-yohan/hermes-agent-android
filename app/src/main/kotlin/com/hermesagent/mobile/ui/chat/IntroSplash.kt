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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
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

/** Desktop's `WORDMARK` (`intro.tsx:150` @ `3ca096de`). */
const val INTRO_WORDMARK = "HERMES AGENT"

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
 *   does upstream. Same here.
 * - `primary` and `auxiliaryWindow` are Desktop's window model. This app has one
 *   window, so there is no non-primary surface to exclude.
 * - `freshDraftReady`, `routedSessionView`, `selectedSessionId` and
 *   `activeSessionId` are four ways of asking whether a session owns the view.
 *   Here that is one field: `ChatViewModel.activeSessionId` is null exactly when
 *   the composer is on a fresh draft, and the transcript it publishes is read
 *   from that id (`ChatViewModel.kt:543,697`). A session that is still loading
 *   its history therefore has a non-null id and cannot flash the splash — the
 *   sequence Desktop's `routeSessionMismatch` guards against cannot arise,
 *   because there is no route here to be ahead of the store.
 * - `messagesEmpty` is [transcriptEmpty].
 *
 * [turnRunning] is this app's own clause and not a Desktop one: the transcript
 * already refuses its empty branch while a turn is in flight, and the splash
 * must not paint over the progress row that replaces it.
 */
fun shouldShowIntroSplash(
    enabled: Boolean,
    activeSessionId: String?,
    transcriptEmpty: Boolean,
    turnRunning: Boolean,
): Boolean = enabled && activeSessionId == null && transcriptEmpty && !turnRunning

/**
 * The splash itself, centred in whatever slot it is given.
 *
 * @param seed Desktop rolls one per mount (`intro.tsx:161`); so does this, and
 *   the parameter is here so a test can pin the line it gets.
 */
@Composable
fun IntroSplash(
    modifier: Modifier = Modifier,
    // `rememberSaveable`, not `remember`: Desktop's `useState` seed lives as
    // long as the mounted component, and a rotation here would otherwise
    // reroll the line mid-conversation — a remount Desktop never performs.
    seed: Int = rememberSaveable { Random.nextInt(0, 100_000) },
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
            Wordmark(INTRO_WORDMARK)
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
        }
    }
}

/**
 * Desktop's display lettering (`components/chat/wordmark.tsx:15-45` and
 * `styles.css:1616-1673` @ `3ca096de`).
 *
 * `.fit-text` sizes the lettering from a container query so it fills
 * `calc(100% - 1rem)` of its column, with a `2.75rem` floor. Compose has no
 * container query, so the same rule is measured: lay the string out once at a
 * probe size, scale by the ratio the column asks for, and clamp. The floor is
 * Desktop's `--fit-min`; the ceiling is this port's, because a phone column with
 * no upper bound would set twelve characters taller than the composer.
 *
 * The face is **not** Desktop's. Desktop draws this in Collapse
 * (`styles.css:61-68` loads `@nous-research/ui`'s `Collapse-Bold.woff2`), a
 * Blaze Type retail typeface that package neither licenses nor documents — and
 * `res/font` cannot read woff2 anyway, so bundling it would mean converting a
 * face this repo has no right to convert. Desktop's own declared fallback is
 * `var(--font-sans)`, so that is what this draws: same weight, same casing,
 * same tracking, the family behind it. Ledgered in
 * `docs/parity/empty-states.md`.
 */
@Composable
fun Wordmark(
    text: String,
    modifier: Modifier = Modifier,
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
        val size = remember(text, targetPx, density.fontScale, base, maxFontSize) {
            fitWordmarkFontSize(measurer, text, base, targetPx, maxFontSize)
        }
        Text(
            text = text,
            style = base.copy(fontSize = size, lineHeight = (size.value * 0.9f).sp),
            color = ink,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * This port's ceiling. Desktop has none, because `.fit-text` runs inside a
 * column bounded by `--composer-width`; a tablet column here is not, and
 * unbounded fitting sets the wordmark taller than the surface it titles.
 */
internal val WORDMARK_MAX_FONT_SIZE = 72.sp

/**
 * Desktop's `--fit-min`, `2.75rem` at a 16px root (`wordmark.tsx:22`), recorded
 * because it is the value this port deliberately does **not** enforce.
 *
 * A floor is safe on Desktop because its narrowest chat column is still wider
 * than the run: the pinned capture measures `HERMES AGENT` at `1052.0px` in a
 * `135.637px` face, `7.756 em` in Collapse; Roboto Bold at the same tracking is
 * `8.4375 em`. At `2.75rem` that is `341dp` of glyph run in Collapse and
 * `371dp` in Roboto, against the `300dp` column a `w320dp` phone leaves — and a
 * run wider than its box under `maxLines = 1, softWrap = false` is clipped at
 * both ends in silence, worse at a font scale above 1.0. So the fit clamps the
 * ceiling only and lets the size fall below this when the column cannot hold
 * the run. `WordmarkFitTest` and `WordmarkFitDeviceTest` measure it;
 * `docs/parity/empty-states.md` has the per-width table and a device render at
 * `w320dp`.
 */
internal val WORDMARK_MIN_FONT_SIZE_DESKTOP = 44.sp

/** Desktop's `calc(100% - 1rem)` (`wordmark.tsx:24`). */
internal val WORDMARK_INSET = 16.dp

/** `px-0.5` — the intro's own padding at the narrow breakpoint (`intro.tsx:170`). */
internal val INTRO_SPLASH_GUTTER = 2.dp

/**
 * One layout pass at a probe size, handed to [fitWordmarkSp] for the arithmetic.
 */
internal fun fitWordmarkFontSize(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    targetWidthPx: Float,
    maxFontSize: TextUnit = WORDMARK_MAX_FONT_SIZE,
): TextUnit {
    val probe = WORDMARK_PROBE_FONT_SIZE
    val measured = measurer.measure(
        text = AnnotatedString(text),
        style = style.copy(fontSize = probe),
        softWrap = false,
        maxLines = 1,
    ).size.width.toFloat()
    return fitWordmarkSp(probe.value, measured, targetWidthPx, maxFontSize.value).sp
}

/**
 * The fit itself: scale the probe by the ratio the column asks for, and clamp
 * **above only**.
 *
 * Kept separate from the measuring so it can be tested against real type
 * metrics: `WordmarkFitTest` drives it from the ratio the pinned Desktop
 * capture recorded, and `WordmarkFitDeviceTest` from the platform face under
 * Robolectric's `NATIVE` graphics. Robolectric's *default* graphics loads no
 * font at all — it measures the whole twelve-character wordmark at 32.5 px when
 * asked for 48 sp, and not even linearly in the size — so a fit asserted under
 * it would be measuring the stub. See `docs/parity/empty-states.md`.
 *
 * There is no floor. Desktop's `--fit-min` is safe upstream because its chat
 * column always exceeds the run; a phone column does not, and under
 * `maxLines = 1, softWrap = false` a run wider than its box is clipped at both
 * ends in silence.
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
): Float {
    if (targetWidthPx <= 0f || probeRunPx <= 0f || probeSp <= 0f) return 0f
    return (probeSp * (targetWidthPx / probeRunPx)).coerceAtMost(maxSp)
}

/** Arbitrary, and cancels out of the ratio; large enough to keep hinting noise small. */
internal val WORDMARK_PROBE_FONT_SIZE = 48.sp
