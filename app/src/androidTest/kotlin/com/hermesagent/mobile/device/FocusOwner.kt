package com.hermesagent.mobile.device

/** API34 text reduction. No name, title, token or opaque join key escapes this parser. */
internal object FocusOwner {
    data class Record(val display: Int, val ownerPid: Int, val ownerUid: Int)

    // Names are unescaped in AOSP: reject quotes/newlines instead of interpreting injected syntax.
    private val focused = Regex("    displayId=([0-9]+), name='([0-9a-f]{1,8}) [^'\\r\\n]*'")
    private val display = Regex("  Display: ([0-9]+)")
    private val entry = Regex("      [0-9]+: name='([^'\\r\\n]+)', id=([0-9]+), displayId=([0-9]+), inputConfig=([^,\\r\\n]+), alpha=[0-9]+\\.[0-9]+, frame=\\[-?[0-9]+,-?[0-9]+]\\[-?[0-9]+,-?[0-9]+], globalScale=[0-9]+\\.[0-9]+, applicationInfo.name=[^,\\r\\n]*, applicationInfo.token=[^,\\r\\n]*, touchableRegion=[^\\r\\n]*, ownerPid=([0-9]+), ownerUid=([0-9]+), dispatchingTimeout=[0-9]+ms, hasToken=[^,\\r\\n]*, touchOcclusionMode=[A-Z_]+")

    class Overflow : RuntimeException()
    enum class Status { MISSING_CURRENT_SECTION, NO_FOCUSED_WINDOW, AMBIGUOUS, REJECTED, OVERFLOW, PROBE_FAILURE, MATCHED }
    data class Result(val status: Status, val owner: Record? = null)
    fun reduce(lines: Sequence<String>, targetDisplay: Int): Record? = inspect(lines, targetDisplay).owner
    fun probe(read: () -> Sequence<String>, targetDisplay: Int): Result = try {
        inspect(read(), targetDisplay)
    } catch (error: Exception) {
        Result(if (error is Overflow || error.cause is Overflow) Status.OVERFLOW else Status.PROBE_FAILURE)
    }
    fun inspect(lines: Sequence<String>, targetDisplay: Int): Result {
        if (targetDisplay < 0) return Result(Status.REJECTED)
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
            if (line.length > 16384 || total > 2 * 1024 * 1024) return Result(Status.OVERFLOW)
            if (!sawFocus && line.startsWith("  FocusedWindows:")) {
                sawFocus = true
                if (line == "  FocusedWindows: <none>") return Result(Status.NO_FOCUSED_WINDOW)
                if (line != "  FocusedWindows:") return Result(Status.REJECTED)
                inFocus = true
                continue
            }
            if (inFocus) {
                if (line.startsWith("    ")) {
                    val match = focused.matchEntire(line) ?: return Result(Status.REJECTED)
                    val id = match.groupValues[1].toIntOrNull() ?: return Result(Status.OVERFLOW)
                    if (owners.put(id, match.groupValues[2]) != null) return Result(Status.AMBIGUOUS)
                    continue
                }
                inFocus = false
            }
            if (line.startsWith("  Display:")) {
                val match = display.matchEntire(line) ?: return Result(Status.REJECTED)
                section = match.groupValues[1].toIntOrNull() ?: return Result(Status.OVERFLOW)
                if (!displays.add(section)) return Result(Status.AMBIGUOUS)
                inWindows = false
                continue
            }
            if (line == "    Windows:") {
                if (section == null || inWindows) return Result(Status.REJECTED)
                inWindows = true
                continue
            }
            if (line.startsWith("      ") && inWindows && !line.startsWith("        ")) {
                if (Regex("ownerPid=").findAll(line).count() != 1 ||
                    Regex("ownerUid=").findAll(line).count() != 1) return Result(Status.REJECTED)
                val match = entry.matchEntire(line) ?: return Result(Status.REJECTED)
                val id = match.groupValues[2].toIntOrNull() ?: return Result(Status.OVERFLOW)
                val entryDisplay = match.groupValues[3].toIntOrNull() ?: return Result(Status.OVERFLOW)
                if (id < 0 || entryDisplay != section) return Result(Status.REJECTED)
                val key = entryDisplay to match.groupValues[1]
                if (!entries.add(key)) return Result(Status.AMBIGUOUS)
                if (entryDisplay == targetDisplay && key.second.substringBefore(' ') == owners[targetDisplay]) {
                    val pid = match.groupValues[5].toIntOrNull() ?: return Result(Status.OVERFLOW)
                    val uid = match.groupValues[6].toIntOrNull() ?: return Result(Status.OVERFLOW)
                    if (pid == 0) return Result(Status.REJECTED)
                    if (result != null) return Result(Status.AMBIGUOUS)
                    result = Record(targetDisplay, pid, uid)
                }
            } else if (!line.startsWith("    ")) {
                section = null
                inWindows = false
            }
        }
        return when {
            !sawFocus -> Result(Status.MISSING_CURRENT_SECTION)
            inFocus -> Result(Status.REJECTED)
            owners[targetDisplay] == null -> Result(Status.NO_FOCUSED_WINDOW)
            result == null -> Result(Status.REJECTED)
            else -> Result(Status.MATCHED, result)
        }
    }
}
