package com.hermesagent.mobile.data.themes

import androidx.compose.ui.graphics.Color
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesFontChoice
import com.hermesagent.mobile.ui.theme.HermesPalette
import com.hermesagent.mobile.ui.theme.HermesThemePreset
import com.hermesagent.mobile.ui.theme.mix
import com.hermesagent.mobile.ui.theme.over
import com.hermesagent.mobile.ui.theme.readableOn
import com.hermesagent.mobile.ui.theme.withAlpha
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One custom Dashboard theme supplied by the active Gateway. */
data class GatewayTheme(
    val name: String,
    val label: String,
    val description: String,
    val preset: HermesThemePreset,
)

/** A complete verdict for one untrusted Dashboard themes envelope. */
sealed interface GatewayThemeParse {
    data class Ok(val themes: List<GatewayTheme>, val active: String?) : GatewayThemeParse
    data class Rejected(val reason: GatewayThemeRejection) : GatewayThemeParse
}

/** Stable local classifications; none carries Gateway-authored text. */
enum class GatewayThemeRejection { Envelope, Entry, Palette, TooLarge, Duplicate, BuiltinCollision, Unsupported }

/** A normalised Dashboard layer before it is composited into Android's opaque palette. */
internal data class LayerR(val color: Color, val alpha: Float)

/**
 * Parses `/api/dashboard/themes` as one bounded unit. The pinned Gateway accepts both
 * bare hex and `{hex, alpha}` layers (`hermes_cli/web_server_dashboard.py:242-258` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`); Android accepts the same input but
 * deliberately recognises only the documented, renderable subset.
 */
fun parseGatewayThemes(bytes: ByteArray): GatewayThemeParse {
    if (bytes.size > MAX_ENVELOPE_BYTES) return rejected(GatewayThemeRejection.TooLarge)
    val root = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }
        .getOrNull() ?: return rejected(GatewayThemeRejection.Envelope)
    if (!root.keys.all { it == "themes" || it == "active" }) return rejected(GatewayThemeRejection.Envelope)
    val themes = root["themes"] as? JsonArray ?: return rejected(GatewayThemeRejection.Envelope)
    if (themes.size > MAX_THEMES) return rejected(GatewayThemeRejection.TooLarge)
    val active = when (val raw = root["active"]) {
        null, JsonNull -> null
        is JsonPrimitive -> raw.takeIf { it.isString }?.content
        else -> null
    } ?: if (root.containsKey("active") && root["active"] !is JsonNull) return rejected(GatewayThemeRejection.Envelope) else null

    val names = HashSet<String>()
    val customs = ArrayList<GatewayTheme>()
    for (raw in themes) {
        val entry = raw as? JsonObject ?: return rejected(GatewayThemeRejection.Entry)
        if (!entry.keys.all { it in ENTRY_KEYS }) return rejected(GatewayThemeRejection.Entry)
        val name = entry.string("name") ?: return rejected(GatewayThemeRejection.Entry)
        if (!isSafeCustomThemeName(name)) return rejected(if (name.length > MAX_NAME) GatewayThemeRejection.TooLarge else GatewayThemeRejection.Entry)
        if (name != name.trim()) return rejected(GatewayThemeRejection.Entry)
        val label = entry.string("label") ?: return rejected(GatewayThemeRejection.Entry)
        if (label.trim().isEmpty()) return rejected(GatewayThemeRejection.Entry)
        if (label.length > MAX_LABEL) return rejected(GatewayThemeRejection.TooLarge)
        // The pinned normaliser emits an empty description for omitted YAML (`:388-390`).
        val description = entry.string("description") ?: return rejected(GatewayThemeRejection.Entry)
        if (description.length > MAX_DESCRIPTION) return rejected(GatewayThemeRejection.TooLarge)
        if (!names.add(name)) return rejected(GatewayThemeRejection.Duplicate)

        // Dashboard built-ins have no definition. They do not participate in Android's
        // custom registry or collision check because their names are not candidates here.
        val definition = entry["definition"] ?: continue
        if (BuiltinThemes.ALL.any { it.name == name }) return rejected(GatewayThemeRejection.BuiltinCollision)
        when (val parsed = parseDefinition(definition as? JsonObject ?: return rejected(GatewayThemeRejection.Entry), name)) {
            is DefinitionParse.Rejected -> return rejected(parsed.reason)
            is DefinitionParse.Ok -> customs += GatewayTheme(name, label, description, preset(name, label, description, parsed.palette))
        }
    }
    return GatewayThemeParse.Ok(customs, active)
}

internal fun isSafeCustomThemeName(raw: String): Boolean {
    val trimmed = raw.trim()
    return raw.length <= MAX_NAME && raw.none(Char::isISOControl) && trimmed.isNotEmpty()
}

private fun rejected(reason: GatewayThemeRejection) = GatewayThemeParse.Rejected(reason)
private sealed interface DefinitionParse {
    data class Ok(val palette: HermesPalette) : DefinitionParse
    data class Rejected(val reason: GatewayThemeRejection) : DefinitionParse
}

private fun parseDefinition(definition: JsonObject, entryName: String): DefinitionParse {
    if (definition.keys.any { it in FORBIDDEN_DEFINITION_KEYS }) return DefinitionParse.Rejected(GatewayThemeRejection.Unsupported)
    if (!definition.keys.all { it in DEFINITION_KEYS }) return DefinitionParse.Rejected(GatewayThemeRejection.Entry)
    if (definition.string("name") != entryName) return DefinitionParse.Rejected(GatewayThemeRejection.Entry)
    for (key in listOf("label", "description")) if (key in definition && definition.string(key) == null) return DefinitionParse.Rejected(GatewayThemeRejection.Entry)
    val palette = definition["palette"] as? JsonObject ?: return DefinitionParse.Rejected(GatewayThemeRejection.Palette)
    if (!palette.keys.all { it in PALETTE_KEYS }) return DefinitionParse.Rejected(GatewayThemeRejection.Palette)
    val background = parseLayer(palette["background"]) ?: return DefinitionParse.Rejected(GatewayThemeRejection.Palette)
    val midground = parseLayer(palette["midground"]) ?: return DefinitionParse.Rejected(GatewayThemeRejection.Palette)
    val foreground = parseLayer(palette["foreground"]) ?: return DefinitionParse.Rejected(GatewayThemeRejection.Palette)
    palette["warmGlow"]?.let { if (it.stringOrNull()?.length?.let { n -> n <= 256 } != true) return DefinitionParse.Rejected(GatewayThemeRejection.Palette) }
    palette["noiseOpacity"]?.let { if (it.numberOrNull()?.takeIf { n -> n in 0.0..1.0 } == null) return DefinitionParse.Rejected(GatewayThemeRejection.Palette) }
    val ignored = validateIgnored(definition)
    if (ignored != null) return DefinitionParse.Rejected(ignored)
    return DefinitionParse.Ok(mapGatewayThemePalette(background, midground, foreground))
}

private fun validateIgnored(definition: JsonObject): GatewayThemeRejection? {
    definition["typography"]?.let { typography ->
        val obj = typography as? JsonObject ?: return GatewayThemeRejection.Entry
        if ("fontUrl" in obj) return GatewayThemeRejection.Unsupported
        if (!obj.keys.all { it in TYPOGRAPHY_KEYS } || obj.values.any { it.stringOrNull()?.length?.let { n -> n <= 1024 } != true }) return GatewayThemeRejection.Entry
    }
    definition["layout"]?.let { layout ->
        val obj = layout as? JsonObject ?: return GatewayThemeRejection.Entry
        if (!obj.keys.all { it in LAYOUT_KEYS }) return GatewayThemeRejection.Entry
        if (obj["radius"]?.stringOrNull()?.length?.let { it <= 64 } == false || obj["density"]?.stringOrNull()?.length?.let { it <= 32 } == false) return GatewayThemeRejection.Entry
        if (obj.values.any { it.stringOrNull() == null }) return GatewayThemeRejection.Entry
    }
    definition["layoutVariant"]?.let { if (it.stringOrNull() == null) return GatewayThemeRejection.Entry }
    for (key in listOf("colorOverrides", "seriesColors")) definition[key]?.let { value ->
        val obj = value as? JsonObject ?: return GatewayThemeRejection.Entry
        if (obj.values.any { it.stringOrNull()?.length?.let { n -> n <= 256 } != true }) return GatewayThemeRejection.Entry
    }
    definition["swatchColors"]?.let { value ->
        val rows = value as? JsonArray ?: return GatewayThemeRejection.Entry
        if (rows.size != 3 || rows.any { it.stringOrNull()?.length?.let { n -> n <= 256 } != true }) return GatewayThemeRejection.Entry
    }
    for (key in listOf("terminalBackground", "terminalForeground")) definition[key]?.let { if (it.stringOrNull()?.length?.let { n -> n <= 64 } != true) return GatewayThemeRejection.Entry }
    return null
}

private fun parseLayer(element: kotlinx.serialization.json.JsonElement?): LayerR? {
    val (hex, alpha) = when (element) {
        is JsonPrimitive -> if (element.isString) element.content to 1f else return null
        is JsonObject -> {
            if (!element.keys.all { it == "hex" || it == "alpha" }) return null
            val hex = element.string("hex") ?: return null
            val alpha = element["alpha"]?.numberOrNull() ?: return null
            if (alpha !in 0.0..1.0) return null
            hex to alpha.toFloat()
        }
        else -> return null
    }
    if (!HEX.matches(hex) || alpha !in 0f..1f) return null
    return LayerR(hexColor(hex), alpha)
}

/**
 * Dashboard `index.css:157-181` token derivation, with layers composited for Compose.
 * The documented foreground layer is validated above but the pinned dashboard shell derives all
 * of these shadcn foreground slots from midground (`web/src/index.css:157-181` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`). Android uses the composited foreground
 * layer for the user bubble, a shell surface with no corresponding dashboard token.
 */
internal fun mapGatewayThemePalette(background: LayerR, midground: LayerR, foreground: LayerR): HermesPalette {
    val base = background.color.withAlpha(background.color.alpha * background.alpha).over(Color.Black)
    val middle = midground.color.withAlpha(midground.color.alpha * midground.alpha).over(base)
    val overlay = foreground.color.withAlpha(foreground.color.alpha * foreground.alpha).over(base)
    val ink = middle
    val card = mix(base, middle, .04f)
    val muted = mix(base, middle, .08f)
    val accent = mix(base, middle, .10f)
    val secondary = mix(base, middle, .06f)
    val border = middle.withAlpha(.15f)
    return HermesPalette(
        background = base, foreground = ink, card = card, cardForeground = ink,
        muted = muted, mutedForeground = middle.withAlpha(.8f), popover = card, popoverForeground = ink,
        primary = middle, primaryForeground = base, secondary = secondary, secondaryForeground = ink,
        accent = accent, accentForeground = ink, border = border, input = border, ring = middle,
        destructive = Color(0xFFFB2C36), destructiveForeground = Color.White,
        midground = middle, midgroundForeground = readableOn(middle), composerRing = middle,
        sidebarBackground = base, sidebarBorder = border, userBubble = overlay, userBubbleBorder = border,
    )
}

private fun preset(name: String, label: String, description: String, colors: HermesPalette) = HermesThemePreset(
    name = name, label = label, description = description, colors = colors, darkColors = colors, fonts = HermesFontChoice(),
)

private fun JsonObject.string(name: String): String? = this[name].stringOrNull()
private fun kotlinx.serialization.json.JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun kotlinx.serialization.json.JsonElement?.numberOrNull(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
private fun hexColor(hex: String): Color {
    val value = hex.removePrefix("#").toLong(16)
    return if (hex.length == 7) Color(0xFF000000 or value) else Color(value)
}

private const val MAX_THEMES = 200
private const val MAX_ENVELOPE_BYTES = 256 * 1024
private const val MAX_NAME = 64
private const val MAX_LABEL = 128
private const val MAX_DESCRIPTION = 512
private val HEX = Regex("^#[0-9a-fA-F]{6}$|^#[0-9a-fA-F]{8}$")
private val ENTRY_KEYS = setOf("name", "label", "description", "definition")
private val DEFINITION_KEYS = setOf("name", "label", "description", "palette", "typography", "layout", "layoutVariant", "colorOverrides", "seriesColors", "swatchColors", "terminalBackground", "terminalForeground")
private val FORBIDDEN_DEFINITION_KEYS = setOf("assets", "customCSS", "componentStyles")
private val PALETTE_KEYS = setOf("background", "midground", "foreground", "warmGlow", "noiseOpacity")
private val TYPOGRAPHY_KEYS = setOf("fontSans", "fontMono", "fontDisplay", "baseSize", "lineHeight", "letterSpacing")
private val LAYOUT_KEYS = setOf("radius", "density")
