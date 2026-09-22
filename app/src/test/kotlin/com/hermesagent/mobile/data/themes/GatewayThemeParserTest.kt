package com.hermesagent.mobile.data.themes

import androidx.compose.ui.graphics.Color
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.mix
import com.hermesagent.mobile.ui.theme.over
import com.hermesagent.mobile.ui.theme.toHex
import com.hermesagent.mobile.ui.theme.withAlpha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayThemeParserTest {
    @Test fun `converts frozen HermesSkin colors without accepting arbitrary assets`() {
        val skin = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"name":"backend-neon","description":"Backend skin","colors":{"background":"#101820","ui_text":"#f2f4f8","ui_accent":"#00c2ff","ui_border":"#36505f","ui_error":"#ff5577"},"banner_logo":"data:text/plain,secret"}""",
        ) as kotlinx.serialization.json.JsonObject
        val theme = parseBackendSkin(skin)!!
        assertEquals("backend-neon", theme.name)
        assertEquals(Color(0xFF101820), theme.preset.colors.background)
        assertEquals(Color(0xFFF2F4F8), theme.preset.colors.foreground)
        assertEquals(Color(0xFF00C2FF), theme.preset.colors.primary)
        assertTrue(theme.preset.description == "Backend skin")
    }

    @Test fun `matches Desktop defaults alpha normalization and contrast guard`() {
        val skin = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"name":"sparse","colors":{"ui_text":"#fff8","ui_accent":"#777"}}""",
        ) as kotlinx.serialization.json.JsonObject
        val palette = parseBackendSkin(skin)!!.preset.colors
        assertEquals(Color(0xFF141414), palette.background)
        assertEquals(Color(0xFF888888), palette.foreground)
        assertTrue(com.hermesagent.mobile.ui.theme.contrastRatio(palette.primary, palette.sidebarBackground!!) >= 4.5f)
    }

    @Test fun `rejects empty or builtin HermesSkin names and skins without usable colors`() {
        val empty = kotlinx.serialization.json.Json.parseToJsonElement("""{"name":"backend"}""") as kotlinx.serialization.json.JsonObject
        val builtin = kotlinx.serialization.json.Json.parseToJsonElement("""{"name":"${BuiltinThemes.DEFAULT_NAME}","colors":{"background":"#000000","ui_text":"#ffffff"}}""") as kotlinx.serialization.json.JsonObject
        assertEquals(null, parseBackendSkin(empty))
        assertEquals(null, parseBackendSkin(builtin))
    }

    @Test fun `accepts a bounded custom definition and skips dashboard builtins`() {
        val parsed = parseGatewayThemes(fixture().toByteArray()) as GatewayThemeParse.Ok
        assertEquals("host-active", parsed.active)
        assertEquals(listOf("custom"), parsed.themes.map { it.name })
        assertEquals("Custom", parsed.themes.single().preset.label)
        assertEquals(parsed.themes.single().preset.colors, parsed.themes.single().preset.darkColors)

        // Compose stores a constructed `Color` quantised, so the derivation is
        // asserted by rebuilding it from the same primitives rather than from raw
        // float arithmetic, which no longer survives that quantisation.
        val colors = parsed.themes.single().preset.colors
        val background = Color(0xFF112233).withAlpha(.5f).over(Color.Black)
        val midground = Color(0xFF445566).withAlpha(.75f).over(background)
        assertEquals(background, colors.background)
        assertEquals(1f, colors.background.alpha)
        assertEquals(midground, colors.foreground)
        assertEquals(mix(background, midground, .04f), colors.card)
        assertEquals(mix(background, midground, .08f), colors.muted)
        assertEquals(mix(background, midground, .10f), colors.accent)
        assertEquals(mix(background, midground, .06f), colors.secondary)
        assertEquals(midground, colors.primary)
        assertEquals(background, colors.primaryForeground)
        assertEquals(midground.withAlpha(.15f), colors.border)
        assertEquals(midground.withAlpha(.15f), colors.input)
        assertEquals(midground.withAlpha(.8f), colors.mutedForeground)
        assertEquals(Color(0xFFFB2C36), colors.destructive)
        assertEquals("#778899", colors.userBubble?.toHex())
        // One channel computed by hand, to prove the composite is the documented
        // lerp and not a copy: #11 at half alpha over black is 8.5/255, which
        // round-trips to the nearest 8-bit step.
        assertEquals(9f / 255f, colors.background.red, 1f / 255f)
    }

    @Test fun `rejects every malformed whole payload without retaining server text`() {
        val tooMany = (1..201).joinToString(",") { "{\"name\":\"x$it\",\"label\":\"x\",\"description\":\"\"}" }
        val cases = listOf(
            "not json", "{}", "{\"themes\":[{}]}", "{\"themes\":[],\"server-secret\":true}",
            fixture().replace("\"name\":\"custom\"", "\"name\":\"   \""),
            fixture().replace("\"name\":\"custom\"", "\"name\":\"${"n".repeat(65)}\""),
            fixture().replace("\"label\":\"Custom\"", "\"label\":\"${"l".repeat(129)}\""),
            fixture().replace("\"description\":\"\"", "\"description\":\"${"d".repeat(513)}\""),
            fixture().replace("\"label\":\"Custom\"", "\"label\":\"Custom\",\"extra\":\"server-secret\""),
            fixture().replace("\"layoutVariant\":\"standard\"", "\"layoutVariant\":\"standard\",\"unknown\":\"server-secret\""),
            fixture().replace("\"#112233\"", "\"bad\""),
            fixture().replace("\"alpha\":0.5", "\"alpha\":1.00000001"),
            fixture().replace("\"foreground\":{\"hex\":\"#778899\",\"alpha\":1}", "\"foreground\":true"),
            fixture().replace("\"foreground\":{\"hex\":\"#778899\",\"alpha\":1},", ""),
            fixture().replace("\"layoutVariant\":\"standard\"", "\"assets\":{}"),
            fixture().replace("\"layoutVariant\":\"standard\"", "\"customCSS\":\"server-secret\""),
            fixture().replace("\"layoutVariant\":\"standard\"", "\"componentStyles\":{}"),
            fixture().replace("\"layoutVariant\":\"standard\"", "\"typography\":{\"fontUrl\":\"server-secret\"}"),
            "{\"themes\":[$tooMany]}",
        )
        cases.forEach { body ->
            val rejected = parseGatewayThemes(body.toByteArray()) as GatewayThemeParse.Rejected
            assertTrue(rejected.reason.name.isNotBlank())
            assertTrue(rejected.toString().contains("server-secret").not())
        }
    }

    @Test fun `rejects duplicate and Android builtin custom names while allowing empty description`() {
        val duplicate = fixture().replace("\"themes\":[", "\"themes\":[${custom()},")
        assertTrue(parseGatewayThemes(duplicate.toByteArray()) is GatewayThemeParse.Rejected)
        val collision = fixture().replace("\"name\":\"custom\"", "\"name\":\"${BuiltinThemes.DEFAULT_NAME}\"")
        assertTrue(parseGatewayThemes(collision.toByteArray()) is GatewayThemeParse.Rejected)
        assertTrue(parseGatewayThemes(fixture().toByteArray()) is GatewayThemeParse.Ok)
    }

    private fun fixture() = """{"themes":[{"name":"dashboard","label":"Dashboard","description":""},${custom()}],"active":"host-active"}"""
    private fun custom() = """{"name":"custom","label":"Custom","description":"","definition":{"name":"custom","palette":{"background":{"hex":"#112233","alpha":0.5},"midground":{"hex":"#445566","alpha":0.75},"foreground":{"hex":"#778899","alpha":1},"warmGlow":"rgba(1,2,3,.4)","noiseOpacity":0.2},"typography":{"fontSans":"x"},"layout":{"radius":"4px","density":"normal"},"layoutVariant":"standard"}}"""
}
