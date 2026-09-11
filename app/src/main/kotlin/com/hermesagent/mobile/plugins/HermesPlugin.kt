package com.hermesagent.mobile.plugins

/**
 * Scoped context handed to a plugin's [HermesPlugin.register].
 *
 * Direct Kotlin port of Desktop's `PluginContext`
 * (`apps/desktop/src/contrib/plugin.ts:70-104` @
 * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`).
 */
interface PluginContext {
    /** The resolved plugin source tag, e.g. `'plugin:kanban'`. */
    val source: String

    /** Register one contribution (id namespaced, source stamped). */
    fun register(contribution: PluginContribution): () -> Unit

    /** Register several at once; returned disposer removes all of them. */
    fun registerMany(contributions: List<PluginContribution>): () -> Unit

    /** Register an arbitrary cleanup to run on unload/disable. */
    fun onDispose(fn: () -> Unit)

    /** REST to this plugin's own backend namespace (`/api/plugins/<id>`). */
    suspend fun rest(
        path: String,
        options: PluginRestOptions = PluginRestOptions(),
    ): PluginRestResult

    /** Live WebSocket to this plugin's own namespace. */
    fun socket(path: String, onMessage: (String) -> Unit): () -> Unit

    /** The curated OS door. */
    val os: PluginOs

    /** Plugin-scoped persistence (`hermes.plugin.<id>.<key>`). */
    val storage: PluginStorage

    /** Gateway JSON-RPC and the gateway event tap. */
    val host: PluginHost

    /** Plugin-scoped locale bundles, resolved against the app's locale. */
    val i18n: PluginI18n
}

/**
 * The plugin contract.
 *
 * Direct Kotlin port of Desktop's `HermesPlugin`
 * (`apps/desktop/src/contrib/plugin.ts:106-118` @
 * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`).
 */
interface HermesPlugin {
    /** Stable slug — becomes the `plugin:<id>` source and id namespace. */
    val id: String

    /** Human name for settings / about UI. */
    val name: String? get() = null

    /** One-liner for settings inventory. */
    val description: String? get() = null

    /** Registers on load when the user hasn't chosen (default true). */
    val defaultEnabled: Boolean get() = true

    /** Called once at load; wire contributions through [ctx]. */
    fun register(ctx: PluginContext)
}

/**
 * Build the scoped context handed to a plugin's `register`.
 *
 * [host] defaults to [UnavailablePluginHost]: a context built without a live
 * connection still hands back a real door that refuses, rather than a null a
 * plugin would have to guard. Production always passes the connection-backed
 * one.
 */
fun createPluginContext(
    pluginId: String,
    registry: ContributionRegistry,
    rest: PluginRest,
    socket: PluginSocket,
    storage: PluginStorage,
    os: PluginOs,
    host: PluginHost = UnavailablePluginHost,
    locales: PluginLocaleRegistry = PluginLocaleRegistry.shared,
    onDispose: ((() -> Unit) -> Unit)? = null,
): PluginContext {
    val source = "plugin:$pluginId"

    fun scope(c: PluginContribution): Contribution = Contribution(
        id = "$pluginId:${c.id}",
        area = c.area,
        source = source,
        title = c.title,
        order = c.order,
        `when` = c.`when`,
        enabled = c.enabled,
        render = c.render,
        data = c.data,
    )

    fun track(dispose: () -> Unit): () -> Unit {
        onDispose?.invoke(dispose)
        return dispose
    }

    return object : PluginContext {
        override val source: String = source

        override fun register(contribution: PluginContribution): () -> Unit =
            track(registry.register(scope(contribution)))

        override fun registerMany(contributions: List<PluginContribution>): () -> Unit =
            track(registry.registerMany(contributions.map(::scope)))

        override fun onDispose(fn: () -> Unit) {
            onDispose?.invoke(fn)
        }

        override suspend fun rest(path: String, options: PluginRestOptions): PluginRestResult =
            rest.execute(pluginId, path, options)

        override fun socket(path: String, onMessage: (String) -> Unit): () -> Unit =
            track(socket.connect(pluginId, path, onMessage))

        override val os: PluginOs = os

        override val storage: PluginStorage = storage

        override val host: PluginHost = host

        override val i18n: PluginI18n = locales.scoped(pluginId, ::track)
    }
}
