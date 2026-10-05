package com.hermesagent.mobile.device

/** Failure-only exact-owner reduction. Raw names and the WindowState join key stay ephemeral. */
internal object OwnerMetadata {
    data class Record(val processRole: String = "UNKNOWN", val windowType: String = "UNKNOWN", val inputConfig: List<String>? = null)
    private val processes = mapOf("system_server" to "SYSTEM_SERVER", "com.android.systemui" to "SYSTEM_UI")
    private val types = setOf("BASE_APPLICATION", "APPLICATION", "APPLICATION_STARTING", "APPLICATION_ATTACHED_DIALOG", "SYSTEM_ALERT", "KEYGUARD", "KEYGUARD_DIALOG", "SYSTEM_DIALOG", "STATUS_BAR", "NOTIFICATION_SHADE", "INPUT_METHOD", "INPUT_METHOD_DIALOG", "NAVIGATION_BAR", "ACCESSIBILITY_OVERLAY", "APPLICATION_OVERLAY", "SECURE_SYSTEM_OVERLAY", "TOAST", "SYSTEM_ERROR", "DREAM", "DISPLAY_OVERLAY", "POINTER", "DRAG", "DOCK_DIVIDER")
    private val configs = setOf("NO_INPUT_CHANNEL", "NOT_VISIBLE", "NOT_FOCUSABLE", "NOT_TOUCHABLE", "PREVENT_SPLITTING", "DUPLICATE_TOUCH_TO_WALLPAPER", "IS_WALLPAPER", "PAUSE_DISPATCHING", "TRUSTED_OVERLAY", "WATCH_OUTSIDE_TOUCH", "SLIPPERY", "DISABLE_USER_ACTIVITY", "DROP_INPUT", "DROP_INPUT_IF_OBSCURED", "SPY", "INTERCEPTS_STYLUS", "NOT_TOUCH_MODAL")
    fun reduce(input: String, owner: FocusOwner.Record, ps: String, windows: String): Record {
        val rows = ps.lineSequence().mapNotNull { Regex("\\s*([0-9]+)\\s+([0-9]+)\\s+(\\S+)\\s*").matchEntire(it) }
            .filter { it.groupValues[1].toIntOrNull() == owner.ownerPid }.toList()
        val process = rows.singleOrNull()?.takeIf { it.groupValues[2].toIntOrNull() == owner.ownerUid }
            ?.let { processes[it.groupValues[3]] } ?: "UNKNOWN"
        if (FocusOwner.reduce(input.lineSequence(), owner.display) != owner) return Record(process)
        // Current first focus section, not focus requests or historical events.
        val focus = input.substringAfter("  FocusedWindows:\n", "").lineSequence().takeWhile { it.startsWith("    ") }
            .mapNotNull { Regex("    displayId=([0-9]+), name='([0-9a-f]{1,8}) [^'\\r\\n]*'").matchEntire(it) }
            .singleOrNull { it.groupValues[1].toIntOrNull() == owner.display } ?: return Record(process)
        val key = focus.groupValues[2]
        val entries = input.substringBefore("  Global Monitors:").substringBefore("  Global monitors on display ").lineSequence()
            .mapNotNull { Regex("      [0-9]+: name='$key [^'\\r\\n]*', id=[0-9]+, displayId=${owner.display}, inputConfig=([^,\\r\\n]+),.*").matchEntire(it) }.toList()
        val config = entries.singleOrNull()?.groupValues?.get(1)?.let { if (it == "0x0") emptyList() else it.split(" | ") }?.takeIf { values ->
            values.size <= configs.size && values.distinct().size == values.size && values.all { it in configs }
        }
        val blocks = windows.split(Regex("(?m)^  Window #[0-9]+ ")).drop(1)
            .filter { Regex("Window\\{$key u[0-9]+ [^{}\\r\\n]*\\}:\\n").find(it)?.range?.first == 0 }
        val type = blocks.singleOrNull()?.takeIf { block ->
            Regex("(?m)^    mDisplayId=${owner.display}(?: |$)").containsMatchIn(block) &&
                Regex("(?m)^    mOwnerUid=${owner.ownerUid}(?: |$)").containsMatchIn(block)
        }?.let { block -> Regex("(?m)^    mAttrs=[^\\r\\n]*\\bty=([A-Z_]+)(?: |\\n|\\})").findAll(block).toList().singleOrNull()?.groupValues?.get(1) }
            ?.takeIf { it in types } ?: "UNKNOWN"
        return Record(process, type, config)
    }
}
