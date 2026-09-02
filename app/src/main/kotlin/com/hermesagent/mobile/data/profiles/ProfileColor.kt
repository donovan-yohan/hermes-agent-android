package com.hermesagent.mobile.data.profiles

/**
 * A profile's identity colour, so the same profile reads the same everywhere
 * without persisting anything.
 *
 * Straight port of `apps/desktop/src/lib/profile-color.ts:6-43` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`, including the rule that the
 * default profile has no colour of its own. Kept Compose-free so the hash and
 * the hue conversion are unit-testable on their own; the surface converts the
 * packed value and mixes it against theme tokens.
 */

private const val PROFILE_TAG_SATURATION = 68f
private const val PROFILE_TAG_LIGHTNESS = 58f

/** `hash = (hash * 31 + charCode) >>> 0` (`profile-color.ts:9-17`). */
internal fun profileColorHash(value: String): Long {
    var hash = 0L
    for (character in value) {
        hash = (hash * 31 + character.code) and 0xFFFFFFFFL
    }
    return hash
}

/**
 * The packed opaque sRGB colour for a named profile, or null for
 * default/empty, which stays neutral (`profile-color.ts:21-31`).
 */
fun profileColorArgb(name: String?): Int? {
    val key = (name ?: "").trim()
    if (key.isEmpty() || key == DEFAULT_PROFILE) return null
    val hue = (profileColorHash(key) % 360L).toFloat()
    return hslToArgb(hue, PROFILE_TAG_SATURATION, PROFILE_TAG_LIGHTNESS)
}

/**
 * A profile's effective colour: a server-offered `ui_meta` hex wins, else the
 * deterministic hue. Mirrors `resolveProfileColor` (`profile-color.ts:35-43`),
 * whose override source on Desktop is a local long-press pick — a non-goal
 * here, so the only override this build honours is the server's.
 */
fun resolveProfileColorArgb(profile: HermesProfile): Int? {
    if (profile.isDefault) return null
    return parseHexColor(profile.uiMetaColor) ?: profileColorArgb(profile.name)
}

/** Strict `#rgb` / `#rrggbb`; anything else is ignored rather than guessed at. */
internal fun parseHexColor(value: String?): Int? {
    val text = value?.trim()?.removePrefix("#") ?: return null
    if (!text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    return when (text.length) {
        3 -> {
            val r = text[0].digitToInt(16) * 17
            val g = text[1].digitToInt(16) * 17
            val b = text[2].digitToInt(16) * 17
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        6 -> (0xFF shl 24) or text.toInt(16)
        else -> null
    }
}

/** CSS `hsl(h s% l%)` for `h` in degrees, `s`/`l` in percent. */
internal fun hslToArgb(hue: Float, saturationPercent: Float, lightnessPercent: Float): Int {
    val s = (saturationPercent / 100f).coerceIn(0f, 1f)
    val l = (lightnessPercent / 100f).coerceIn(0f, 1f)
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val hPrime = ((hue % 360f) + 360f) % 360f / 60f
    val x = c * (1f - kotlin.math.abs(hPrime % 2f - 1f))
    val (r1, g1, b1) = when (hPrime.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    fun channel(value: Float): Int = ((value + m).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (0xFF shl 24) or (channel(r1) shl 16) or (channel(g1) shl 8) or channel(b1)
}

/**
 * The uppercase initial a named profile's glyph carries
 * (`apps/desktop/src/components/ui/profile-glyph.tsx:29`): alphanumerics only,
 * `?` when the name has none.
 */
fun profileInitial(name: String): String =
    name.firstOrNull { it.isLetterOrDigit() && it.code < 128 }?.uppercase() ?: "?"
