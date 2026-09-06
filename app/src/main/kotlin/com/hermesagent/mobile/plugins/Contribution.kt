package com.hermesagent.mobile.plugins

import androidx.compose.runtime.Composable

/**
 * Where a contribution came from. `'core'` is the app's own default UI;
 * anything else is a plugin/extension id (e.g. `'plugin:kanban'`). This is the
 * provenance tag that drives precedence and, later, the trust/capability gate.
 */
const val CONTRIBUTION_SOURCE_CORE: String = "core"

/**
 * The single, uniform primitive every surface consumes.
 *
 * Direct Kotlin/Compose port of Desktop's `Contribution`
 * (`apps/desktop/src/contrib/types.ts:25-45` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 */
data class Contribution(
    /** Stable id, unique within its area. Re-registering the same id replaces it. */
    val id: String,
    /** Namespaced area id this contribution targets, e.g. `'secondarySidebar'`. */
    val area: String,
    /** Provenance; defaults to `'core'` when omitted. */
    val source: String = CONTRIBUTION_SOURCE_CORE,
    /** Human label (pane tab / header). Optional for bar items. */
    val title: String? = null,
    /** Ascending sort key within the area; ties keep insertion order. */
    val order: Int? = null,
    /**
     * Dynamic visibility predicate. Omit for always-on.
     * Note: evaluated when the area's snapshot is built on mutation.
     */
    val `when`: (() -> Boolean)? = null,
    /** Soft disable without unregistering. `false` hides it. */
    val enabled: Boolean = true,
    /** Renders the contribution's content (UI contributions). */
    val render: (@Composable () -> Unit)? = null,
    /** Declarative payload for data contributions (Family B). */
    val data: Any? = null,
)

/**
 * Input contribution shape when registering via [PluginContext].
 *
 * In Desktop (`apps/desktop/src/contrib/plugin.ts:6`), `id` is scoped with the
 * plugin id and `source` is stamped automatically as `'plugin:<id>'`.
 */
data class PluginContribution(
    val id: String,
    val area: String,
    val title: String? = null,
    val order: Int? = null,
    val `when`: (() -> Boolean)? = null,
    val enabled: Boolean = true,
    val render: (@Composable () -> Unit)? = null,
    val data: Any? = null,
)
