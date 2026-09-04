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
    seed: Int = remember { Random.nextInt(0, 100_000) },
) {
    val tokens = HermesTheme.tokens
    Box(
        modifier = modifier.fillMaxWidth().testTag(INTRO_SPLASH_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            // `px-0.5 py-6` — the intro's own padding at the narrow breakpoint
            // (`intro.tsx:170`); the wordmark carries its own 1rem inset.
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Wordmark(INTRO_WORDMARK)
            Text(
                text = pickIntroCopy(seed),
                // `[data-slot='aui_intro'] p:last-child` @ `styles.css:1609-1614`:
                // tertiary ink, caption size, centred, and held to Desktop's
                // own `max-width: 34rem` reading measure — which a phone column
                // never reaches and a tablet's does.
                style = HermesTheme.type.caption,
                color = tokens.textTertiary,
                textAlign = TextAlign.Center,
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
    minFontSize: TextUnit = WORDMARK_MIN_FONT_SIZE,
    maxFontSize: TextUnit = WORDMARK_MAX_FONT_SIZE,
) {
    val tokens = HermesTheme.tokens
    val base = HermesTheme.type.wordmark
    // `text-midground` in light, `text-foreground/90` in dark
    // (`wordmark.tsx:33` @ `3ca096de`). `tokens.accent` IS the resolved
    // midground — `--ui-accent: var(--theme-midground)` (`styles.css:207`).
    val ink = if (HermesTheme.isDark) tokens.textPrimary else tokens.accent
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val targetPx = with(density) {
            (this@BoxWithConstraints.maxWidth - WORDMARK_INSET).coerceAtLeast(0.dp).toPx()
        }
        val size = remember(text, targetPx, density.fontScale, base) {
            fitFontSize(measurer, text, base, targetPx, minFontSize, maxFontSize)
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

/** Desktop's `--fit-min`, `2.75rem` at a 16px root (`wordmark.tsx:22`). */
private val WORDMARK_MIN_FONT_SIZE = 44.sp

/**
 * This port's ceiling. Desktop has none because its column is bounded by
 * `--composer-width`; a tablet column here is not, and unbounded fitting sets
 * the wordmark larger than the surface it titles.
 */
private val WORDMARK_MAX_FONT_SIZE = 72.sp

/** Desktop's `calc(100% - 1rem)` (`wordmark.tsx:24`). */
private val WORDMARK_INSET = 16.dp

/**
 * One layout pass at a probe size, then the ratio the column asks for. A
 * measurement of zero — an unmeasured column, a zero-width slot — falls to the
 * floor rather than dividing by it.
 */
private fun fitFontSize(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    targetWidthPx: Float,
    minFontSize: TextUnit,
    maxFontSize: TextUnit,
): TextUnit {
    if (targetWidthPx <= 0f) return minFontSize
    val probe = 48.sp
    val measured = measurer.measure(
        text = AnnotatedString(text),
        style = style.copy(fontSize = probe),
        softWrap = false,
        maxLines = 1,
    ).size.width.toFloat()
    if (measured <= 0f) return minFontSize
    val fitted = probe.value * (targetWidthPx / measured)
    return fitted.coerceIn(minFontSize.value, maxFontSize.value).sp
}
