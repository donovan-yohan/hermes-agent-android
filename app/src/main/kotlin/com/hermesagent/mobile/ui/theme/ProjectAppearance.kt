package com.hermesagent.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import com.hermesagent.mobile.ui.common.HermesIcon

/** Desktop project-appearance.tsx + profile-color.ts @ 41dfcf2c. Wire values stay exact. */
object ProjectAppearance {
    val swatches: List<String> = List(12) { "hsl(${it * 30} 68% 58%)" }
    val icons: Map<String, HermesIcon> = linkedMapOf(
        "folder-library" to HermesIcon.FolderLibrary, "repo" to HermesIcon.Repo,
        "rocket" to HermesIcon.Rocket, "beaker" to HermesIcon.Beaker,
        "flame" to HermesIcon.Flame, "star-full" to HermesIcon.StarFull,
        "heart" to HermesIcon.Heart, "zap" to HermesIcon.Zap,
        "target" to HermesIcon.Target, "lightbulb" to HermesIcon.Lightbulb,
        "tools" to HermesIcon.Tools, "device-desktop" to HermesIcon.Monitor,
        "device-mobile" to HermesIcon.DeviceMobile, "terminal" to HermesIcon.Terminal,
        "dashboard" to HermesIcon.Dashboard, "globe" to HermesIcon.Globe,
        "broadcast" to HermesIcon.Broadcast, "cloud" to HermesIcon.Cloud,
        "database" to HermesIcon.Database, "package" to HermesIcon.Package,
        "book" to HermesIcon.Book, "organization" to HermesIcon.Organization,
        "bug" to HermesIcon.Bug, "shield" to HermesIcon.Shield,
        "key" to HermesIcon.Key, "gift" to HermesIcon.Gift,
        "telescope" to HermesIcon.Telescope, "home" to HermesIcon.Home,
    )
    fun glyph(name: String?, auto: Boolean, home: Boolean = false): HermesIcon =
        icons[name] ?: if (home) HermesIcon.Home else if (auto) HermesIcon.Repo else HermesIcon.FolderLibrary

    fun color(value: String?, fallback: Color): Color {
        val index = swatches.indexOf(value)
        if (index >= 0) return Color.hsl(index * 30f, .68f, .58f)
        if (value != null && value.matches(Regex("#[0-9a-fA-F]{6}"))) return Color(0xFF000000 or value.drop(1).toLong(16))
        return fallback
    }
}
