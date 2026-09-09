package com.hermesagent.mobile.plugins

/**
 * Where a plugin comes from. Mobile is compiled-in [Bundled] only (see ADR 0003).
 */
enum class PluginKind(val wire: String) {
    Bundled("bundled"),
    Disk("disk"),
    Runtime("runtime"),
}

/**
 * Lifecycle status in the inventory.
 */
enum class PluginStatus(val wire: String) {
    Disabled("disabled"),
    Loaded("loaded"),
    Error("error"),
}

/**
 * Reactive inventory record for one plugin.
 *
 * Direct Kotlin port of Desktop's `PluginRecord`
 * (`apps/desktop/src/contrib/plugins-store.ts:16-25` @
 * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`).
 */
data class PluginRecord(
    val id: String,
    val name: String,
    val kind: PluginKind = PluginKind.Bundled,
    val status: PluginStatus = PluginStatus.Disabled,
    /** One-liner from plugin metadata. */
    val description: String? = null,
    /** Load/registration failure message (status Error). */
    val error: String? = null,
)
