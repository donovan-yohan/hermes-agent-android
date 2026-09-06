package com.hermesagent.mobile.plugins

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plugin discovery and lifecycle manager.
 *
 * Direct Kotlin port of Desktop's bundled plugin loader
 * (`apps/desktop/src/contrib/plugins.ts:25-63` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 */
class PluginLoader(
    private val registry: ContributionRegistry,
    private val store: PluginStore,
    private val rest: PluginRest,
    private val socket: PluginSocket,
    private val storageFactory: (String) -> PluginStorage,
    private val osFactory: (String) -> PluginOs,
) {
    private val loaded = AtomicBoolean(false)

    fun discover(plugins: List<HermesPlugin> = BundledPlugins.ALL) {
        if (!loaded.compareAndSet(false, true)) {
            return
        }

        for (plugin in plugins) {
            val record = PluginRecord(
                id = plugin.id,
                name = plugin.name ?: plugin.id,
                description = plugin.description,
                kind = PluginKind.Bundled,
                status = PluginStatus.Disabled,
            )

            val disposers = mutableListOf<() -> Unit>()

            val activate = {
                disposers.forEach { dispose ->
                    try {
                        dispose()
                    } catch (_: Throwable) {}
                }
                disposers.clear()

                try {
                    val ctx = createPluginContext(
                        pluginId = plugin.id,
                        registry = registry,
                        rest = rest,
                        socket = socket,
                        storage = storageFactory(plugin.id),
                        os = osFactory(plugin.id),
                        onDispose = { dispose -> disposers.add(dispose) },
                    )
                    plugin.register(ctx)
                    store.publishPlugin(record.copy(status = PluginStatus.Loaded))
                } catch (t: Throwable) {
                    store.publishPlugin(
                        record.copy(
                            status = PluginStatus.Error,
                            error = t.message ?: t.toString(),
                        )
                    )
                }
            }

            val deactivate = {
                disposers.forEach { dispose ->
                    try {
                        dispose()
                    } catch (_: Throwable) {}
                }
                disposers.clear()
            }

            store.publishPlugin(
                record.copy(status = PluginStatus.Disabled),
                handle = object : PluginHandle {
                    override suspend fun activate() = activate()
                    override fun deactivate() = deactivate()
                }
            )

            if (store.pluginActive(plugin.id, plugin.defaultEnabled)) {
                activate()
            }
        }
    }
}
