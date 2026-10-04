package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BotMcpTicket(val target: BotManagementTarget, val revision: Long)
data class BotMcpState(
    val ticket: BotMcpTicket? = null,
    val rows: List<McpServer>? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val editable get() = ticket != null && rows != null && !loading && !busy
}

/** Plugin-owned operations outlive the editor. Closing revokes admission, never cancels dispatched work. */
class BotsMcpViewModel(private val host: PluginHost, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(BotMcpState())
    val state = mutableState.asStateFlow()
    private var revision = 0L
    private var readRevision = 0L
    private var authority: PluginMcpScope? = null
    private val pending = mutableMapOf<BotManagementTarget, BotMcpTicket>()
    init {
        scope.launch { host.endpointGeneration.collect { endpoint ->
            if (state.value.ticket?.target?.endpoint?.let { it != endpoint } == true) close()
        } }
    }
    private fun current(ticket: BotMcpTicket) = state.value.ticket == ticket &&
        ticket.target.endpoint == host.endpointGeneration.value
    fun open(target: BotManagementTarget) {
        close()
        if (!validBotId(target.name) || target.name == "current" || !host.connected.value ||
            target.endpoint != host.endpointGeneration.value) return
        val ticket = BotMcpTicket(target, revision)
        val bound = host.mcp.openScope(target.endpoint, target.name)
        authority = bound
        mutableState.value = BotMcpState(ticket = ticket, loading = true, busy = target in pending)
        read(ticket, bound)
    }
    private fun read(ticket: BotMcpTicket, bound: PluginMcpScope, notice: String? = null) {
        val sequence = ++readRevision
        mutableState.value = state.value.copy(loading = true, message = notice)
        scope.launch {
            val result = try { bound.listServers() }
            catch (_: CancellationException) { McpResult.Failure(McpFailure.Unreachable) }
            catch (_: Exception) { McpResult.Failure(McpFailure.Unreachable) }
            if (!current(ticket) || sequence != readRevision) return@launch
            mutableState.value = state.value.copy(loading = false, busy = ticket.target in pending,
                rows = (result as? McpResult.Success)?.value,
                message = if (result is McpResult.Failure)
                    listOfNotNull(notice, mcpReadFailure(result.reason)).joinToString(" ") else notice)
        }
    }
    fun refresh(snapshot: BotMcpState) {
        val ticket = snapshot.ticket ?: return
        if (snapshot !== state.value || !current(ticket) || snapshot.loading || snapshot.busy || !host.connected.value) return
        authority?.let { read(ticket, it) }
    }
    fun close() {
        // This scope's lock prevents future enqueue. Already admitted network work is not rolled back.
        authority?.close(); authority = null
        revision++; readRevision++
        mutableState.value = BotMcpState()
    }
    fun toggle(snapshot: BotMcpState, name: String) {
        val ticket = snapshot.ticket ?: return
        if (snapshot !== state.value || !snapshot.editable || !current(ticket) || !host.connected.value) return
        val row = snapshot.rows?.singleOrNull { it.name == name } ?: return
        if (row.source != McpSource.Config) return
        val bound = authority ?: return
        if (ticket.target in pending) return
        pending[ticket.target] = ticket
        mutableState.value = snapshot.copy(busy = true, message = null)
        scope.launch {
            val result = try { bound.setEnabled(name, !row.enabled) }
            catch (_: CancellationException) { McpResult.Failure(McpFailure.Unconfirmed, true) }
            catch (_: Exception) { McpResult.Failure(McpFailure.Unconfirmed, true) }
            pending.remove(ticket.target)
            val selected = state.value.ticket ?: return@launch
            if (!current(selected) || selected.target != ticket.target) return@launch
            if (selected == ticket && result is McpResult.Success) {
                mutableState.value = state.value.copy(busy = false, rows = result.value, message = "MCP selection saved. Applies to future sessions or Gateway starts.")
            } else {
                mutableState.value = state.value.copy(busy = false)
                // Even a stale/uncertain operation gets a new named read, NEVER an automatic retry.
                authority?.let { read(selected, it, "Change not confirmed. Review the current selection before trying again.") }
            }
        }
    }
}

private fun mcpReadFailure(reason: McpFailure): String = when (reason) {
    McpFailure.MissingCapability, McpFailure.UnavailableOnGateway -> "Configured MCP servers are unavailable on this Gateway."
    McpFailure.Refused -> "This Gateway refused access to configured MCP servers."
    McpFailure.InvalidScope, McpFailure.StaleScope -> "This profile changed. Close and open it again."
    else -> "MCP servers failed to load. Refresh to try again."
}
