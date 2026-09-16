package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.data.session.RelativeAgeUnit
import com.hermesagent.mobile.data.session.relativeAge

/**
 * The presentation leaves a roster row is assembled from: the compact age
 * label, the display name, the @handle and the one-line preview.
 *
 * Ports of Desktop at `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`:
 * `apps/desktop/src/plugins/hermes-bots/bot-row.tsx:79-88` (age),
 * `apps/desktop/src/lib/time.ts:194-215` (`coarseElapsed`),
 * `apps/desktop/src/plugins/hermes-bots/labels.ts:14-90` (name + preview),
 * `apps/desktop/src/plugins/hermes-bots/data.ts:957-963` (@handle) and
 * `apps/desktop/src/plugins/hermes-bots/row-helpers.ts` (A2A preview kind).
 *
 * The bucket rule itself lives in core
 * ([com.hermesagent.mobile.data.session.relativeAge]), as `coarseElapsed` does
 * on Desktop, where the plugin imports it from `lib/time.ts` rather than
 * carrying its own copy — the two lists must not be able to disagree about how
 * an age is bucketed. What stays here is the plugin's own label set.
 */

/**
 * The row age's suffix strings.
 *
 * These are core `en.ts` values, not plugin-bundle ones: Desktop's `rowAge`
 * deliberately borrows the session rows' own labels so the two lists in one
 * rail cannot disagree about how an age is spelled
 * (`bot-row.tsx:79-83`; core `en.ts:2534-2537` @ the pin).
 */
data class BotRowAgeLabels(
    val now: String = "now",
    val day: String = "d",
    val hour: String = "h",
    val minute: String = "m",
)

/** Compact age label for a past timestamp ("now", "52m", "3h", "18d"). */
fun rowAgeLabel(
    atMillis: Long,
    nowMillis: Long,
    labels: BotRowAgeLabels = BotRowAgeLabels(),
): String {
    val elapsed = relativeAge(nowMillis - atMillis)
    return when (elapsed.unit) {
        RelativeAgeUnit.Second -> labels.now
        RelativeAgeUnit.Day -> "${elapsed.value}${labels.day}"
        RelativeAgeUnit.Hour -> "${elapsed.value}${labels.hour}"
        RelativeAgeUnit.Minute -> "${elapsed.value}${labels.minute}"
    }
}

/**
 * The @handle users tag a bot with.
 *
 * The primary profile is literally named `default`; its callable alias is
 * `hermes`, so the word "default" never surfaces in the UI
 * (`data.ts:952-963` @ the pin).
 */
fun botHandle(name: String): String =
    if (name.trim().lowercase() == "default") "hermes" else name

/**
 * The name a row shows.
 *
 * Port of `labels.ts:14-64` minus the two inputs this app has no source for:
 * a Bot Mode title and a configured alias route. Those override the core
 * profile display name on Desktop; when either arrives in a later slice it
 * belongs above [displayName]'s `display_name` arm, in Desktop's order.
 */
fun displayName(name: String, displayName: String): String {
    val core = displayName.trim()
    if (core.isNotEmpty()) {
        return core
    }

    if (name.trim().lowercase() == "default") {
        return "Hermes"
    }

    val raw = name.replace(Regex("[-_]+"), " ").trim()
    return raw.replace(Regex("\\b\\w")) { match -> match.value.uppercase() }
}

private const val ROBOT_GLYPH = "\uD83E\uDD16"

/**
 * Bot-to-bot delivery prefix: the current "Message from 🤖 name (@handle):"
 * form or the older "[Message from agent 'name']" shape. Captures the sender.
 * `row-helpers.ts` @ the pin.
 */
private val A2A_PREFIX = Regex(
    "^Message from (?:agent '([^']+)'|$ROBOT_GLYPH\\s*([^\\s(@]+))",
    RegexOption.IGNORE_CASE,
)

/** The prefix alone, for stripping it cleanly off a DM preview. */
private val A2A_STRIP = Regex(
    "^Message from (?:agent '[^']+'|$ROBOT_GLYPH[^:]+):\\s*",
    RegexOption.IGNORE_CASE,
)

/**
 * Whether a preview is a bot-to-bot message, and who sent it.
 *
 * A preview that starts with the delivery prefix is a bot-to-bot message: the
 * receiving bot's row should show WHO sent it rather than presenting it as the
 * human's own chat.
 */
fun previewFromBot(preview: String?): String? {
    val text = preview.orEmpty().trim()
    if (text.isEmpty()) {
        return null
    }
    val match = A2A_PREFIX.find(text) ?: return null
    val sender = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim().lowercase()
    return sender.ifEmpty { null }?.let(::botHandle)
}

private val FENCED_CODE = Regex("```[\\s\\S]*?```")
private val INLINE_CODE = Regex("`([^`\\n]*)`")
private val IMAGE_LINK = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
private val LINK = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
private val BOLD = Regex("(\\*\\*|__)(.*?)\\1")
private val ITALIC = Regex("(^|\\s)[*_](\\S(?:.*?\\S)?)[*_](?=\\s|$|[.,;:!?])")
private val STRIKE = Regex("~~(.*?)~~")
private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+", RegexOption.MULTILINE)
private val QUOTE = Regex("^\\s{0,3}>\\s?", RegexOption.MULTILINE)
private val WHITESPACE_RUN = Regex("\\s+")

/**
 * Flatten markdown syntax out of a one-line roster preview so rows read like
 * Discord's — no raw `**bold**`, `` `code` ``, `> quotes`, or
 * `[link](url)` characters in the preview line (`labels.ts:74-90` @ the pin).
 */
fun stripPreviewMarkdown(text: String?): String =
    text.orEmpty()
        .replace(FENCED_CODE, " ")
        .replace(INLINE_CODE, "\$1")
        .replace(IMAGE_LINK, "\$1")
        .replace(LINK, "\$1")
        .replace(BOLD, "\$2")
        .replace(ITALIC, "\$1\$2")
        .replace(STRIKE, "\$1")
        .replace(HEADING, "")
        .replace(QUOTE, "")
        .replace(WHITESPACE_RUN, " ")
        .trim()

/**
 * The preview line a row renders.
 *
 * DM previews read like DMs: the bot-to-bot delivery prefix is stripped so the
 * message itself shows, and a prefix with no body reads as an ellipsis rather
 * than an empty row (`bot-row.tsx:171-176` @ the pin).
 */
fun displayPreview(preview: String?): String {
    val text = preview.orEmpty().trim()
    if (text.isEmpty()) {
        return ""
    }
    val fromBot = previewFromBot(text)
    val body = if (fromBot != null) {
        text.replaceFirst(A2A_STRIP, "").trim().ifEmpty { "…" }
    } else {
        text
    }
    return stripPreviewMarkdown(body)
}
