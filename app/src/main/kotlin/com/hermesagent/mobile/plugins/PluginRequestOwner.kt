package com.hermesagent.mobile.plugins

/**
 * Irrevocable visible-owner lease. Close and immediate transport admission share
 * one monitor, just like the bounded Skills/MCP scopes. This is not a dispatcher
 * escape: only the host can admit a send. No response wait holds this monitor.
 */
class PluginRequestOwner : AutoCloseable {
    private val lock = Any()
    private var closed = false

    internal fun isCurrent(): Boolean = synchronized(lock) { !closed }
    internal fun dispatch(send: () -> Boolean): Boolean = synchronized(lock) { !closed && send() }
    override fun close() { synchronized(lock) { closed = true } }
}

/** Replacement revokes the previous store even for A → B → A or a same-profile reopen. */
class AgentPluginsOwner {
    private var store: AgentPluginsStore? = null

    fun open(host: PluginHost, profile: String): AgentPluginsStore {
        store?.close()
        return AgentPluginsStore(host, profile).also { store = it }
    }
}
