package com.hermesagent.mobile.device

/** API34 text reduction. No name, title, token or opaque join key escapes this parser. */
internal object FocusOwner {
    data class Record(val display: Int, val ownerPid: Int, val ownerUid: Int)

    // Names are unescaped in AOSP: reject quotes/newlines instead of interpreting injected syntax.
    private val focused = Regex("    displayId=([0-9]+), name='([0-9a-f]{1,8}) [^'\\r\\n]*'")
    private val display = Regex("  Display: ([0-9]+)")
    private val entry = Regex("      [0-9]+: name='([0-9a-f]{1,8}) [^'\\r\\n]*', id=([0-9]+), displayId=([0-9]+), inputConfig=([^,\\r\\n]+), alpha=[0-9]+\\.[0-9]+, frame=\\[-?[0-9]+,-?[0-9]+]\\[-?[0-9]+,-?[0-9]+], globalScale=[0-9]+\\.[0-9]+, applicationInfo.name=[^,\\r\\n]*, applicationInfo.token=[^,\\r\\n]*, touchableRegion=[^\\r\\n]*, ownerPid=([0-9]+), ownerUid=([0-9]+), dispatchingTimeout=[0-9]+ms, hasToken=[^,\\r\\n]*, touchOcclusionMode=[A-Z_]+")

    fun reduce(lines: Sequence<String>, targetDisplay: Int): Record? {
        if (targetDisplay < 0) return null
        var sawFocus = false
        var inFocus = false
        val owners = mutableMapOf<Int, String>()
        var section: Int? = null
        var inWindows = false
        val displays = mutableSetOf<Int>()
        val entries = mutableSetOf<Pair<Int, String>>()
        var result: Record? = null
        var total = 0
        for (line in lines) {
            // Everything after the display/window list is event/history, not current ownership.
            if (line.startsWith("  Global Monitors:") || line.startsWith("  Global monitors on display ")) break
            total += line.length
            if (line.length > 16384 || total > 2 * 1024 * 1024) return null
            if (!sawFocus && line.startsWith("  FocusedWindows:")) {
                sawFocus = true
                if (line != "  FocusedWindows:") return null
                inFocus = true
                continue
            }
            if (inFocus) {
                if (line.startsWith("    ")) {
                    val match = focused.matchEntire(line) ?: return null
                    val id = match.groupValues[1].toIntOrNull() ?: return null
                    if (owners.put(id, match.groupValues[2]) != null) return null
                    continue
                }
                inFocus = false
            }
            if (line.startsWith("  Display:")) {
                val match = display.matchEntire(line) ?: return null
                section = match.groupValues[1].toIntOrNull() ?: return null
                if (!displays.add(section)) return null
                inWindows = false
                continue
            }
            if (line == "    Windows:") {
                if (section == null || inWindows) return null
                inWindows = true
                continue
            }
            if (line.startsWith("      ") && inWindows && !line.startsWith("        ")) {
                if (Regex("ownerPid=").findAll(line).count() != 1 ||
                    Regex("ownerUid=").findAll(line).count() != 1) return null
                val match = entry.matchEntire(line) ?: return null
                val id = match.groupValues[2].toIntOrNull() ?: return null
                val entryDisplay = match.groupValues[3].toIntOrNull() ?: return null
                if (id < 0 || entryDisplay != section) return null
                val key = entryDisplay to match.groupValues[1]
                if (!entries.add(key)) return null
                if (entryDisplay == targetDisplay && owners[targetDisplay] == key.second) {
                    val pid = match.groupValues[5].toIntOrNull() ?: return null
                    val uid = match.groupValues[6].toIntOrNull() ?: return null
                    if (pid == 0 || result != null) return null
                    result = Record(targetDisplay, pid, uid)
                }
            } else if (!line.startsWith("    ")) {
                section = null
                inWindows = false
            }
        }
        return if (sawFocus && !inFocus) result else null
    }
}
