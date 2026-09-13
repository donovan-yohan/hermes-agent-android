package com.hermesagent.mobile.plugins

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Plugin discovery and lifecycle manager.
 *
 * Direct Kotlin port of Desktop's bundled plugin loader
 * (`apps/desktop/src/contrib/plugins.ts:25-63` @
 * `564aef2946c436500a5e80ee117b66b789b3f99a`).
 */
class PluginLoader(
    private val registry: ContributionRegistry,
    private val store: PluginStore,
    private val rest: PluginRest,
    private val socket: PluginSocket,
    private val storageFactory: (String) -> PluginStorage,
    private val osFactory: (String) -> PluginOs,
    /**
     * Builds the per-plugin [PluginHost]. Null leaves every plugin on
     * [UnavailablePluginHost] — the honest door for a context with no live
     * connection, and what tests that do not exercise the door get. Null also
     * means no per-plugin scope is created at all.
     */
    private val hostFactory: ((CoroutineScope) -> PluginHost)? = null,
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

            fun releaseAll() {
                disposers.forEach { dispose ->
                    try {
                        dispose()
                    } catch (_: Throwable) {}
                }
                disposers.clear()
            }

            val activate = {
                releaseAll()

                // One scope per activation, torn down with everything else the
                // plugin registered. Cancelling it is what stops a request that
                // is still in flight when the plugin is disabled.
                val host = hostFactory?.let { factory ->
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    disposers.add { scope.cancel() }
                    factory(scope)
                } ?: UnavailablePluginHost

                try {
                    val ctx = createPluginContext(
                        pluginId = plugin.id,
                        registry = registry,
                        rest = rest,
                        socket = socket,
                        storage = storageFactory(plugin.id),
                        os = osFactory(plugin.id),
                        host = host,
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

            val deactivate = { releaseAll() }

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
