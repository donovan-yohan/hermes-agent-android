package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BotToolsetsTicket(val target: BotManagementTarget, val revision: Long)
data class BotToolsetsState(
    val ticket: BotToolsetsTicket? = null,
    val original: BotToolsetsRead? = null,
    val draft: Set<String> = emptySet(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val consumed: Boolean = false,
    val confirmDefaults: Boolean = false,
    val message: String? = null,
) {
    val editable get() = ticket != null && original != null && !loading && !busy && !consumed
    // Saving all selected still pins them. Never encode an implicit default as an empty selection.
    val canSave get() = editable && !confirmDefaults && draft.isNotEmpty() &&
        (original?.pinned == false || draft != original?.enabled)
}

class BotsToolsetsViewModel(private val host: PluginHost, private val scope: CoroutineScope,
    private val onChanged: () -> Unit, private val repository: BotsToolsetsRepository = BotsToolsetsRepository(host)) {
    private val mutableState = MutableStateFlow(BotToolsetsState())
    val state = mutableState.asStateFlow()
    private var revision = 0L
    private var readRevision = 0L
    // Accepted operations belong to endpoint/profile, not to the currently selected sheet.
    private val pending = mutableMapOf<BotManagementTarget, BotToolsetsTicket>()
    init {
        scope.launch { host.endpointGeneration.collect { endpoint ->
            if (state.value.ticket?.target?.endpoint?.let { it != endpoint } == true) close()
        } }
    }
    fun close() { revision++; mutableState.value = BotToolsetsState() }
    fun open(target: BotManagementTarget) {
        close()
        if (!validBotId(target.name) || !host.connected.value || target.endpoint != host.endpointGeneration.value) return
        val ticket = BotToolsetsTicket(target, revision)
        mutableState.value = BotToolsetsState(ticket = ticket, loading = true, consumed = target in pending)
        read(ticket)
    }
    private fun current(ticket: BotToolsetsTicket) = state.value.ticket == ticket &&
        ticket.target.endpoint == host.endpointGeneration.value
    private fun accepts(snapshot: BotToolsetsState) = snapshot === state.value &&
        snapshot.ticket?.let(::current) == true && host.connected.value
    private fun read(ticket: BotToolsetsTicket) {
        val sequence = ++readRevision
        mutableState.value = state.value.copy(loading = true)
        scope.launch {
            val result = repository.describe(ticket.target)
            if (!current(ticket) || sequence != readRevision) return@launch
            mutableState.value = state.value.copy(original = result, draft = result?.enabled.orEmpty(), loading = false,
                consumed = ticket.target in pending, confirmDefaults = false,
                message = if (result == null) "Toolsets could not be read. Close and try again."
                    else if (ticket.target in pending) "A previous save is still pending." else null)
        }
    }
    fun toggle(snapshot: BotToolsetsState, name: String) {
        if (!accepts(snapshot) || !snapshot.editable || snapshot.confirmDefaults || snapshot.original?.rows?.none { it.name == name } != false) return
        mutableState.value = snapshot.copy(draft = if (name in snapshot.draft) snapshot.draft - name else snapshot.draft + name, message = null)
    }
    fun requestDefaults(snapshot: BotToolsetsState) {
        if (!accepts(snapshot) || !snapshot.editable || snapshot.original?.pinned != true) return
        mutableState.value = snapshot.copy(confirmDefaults = true, message = null)
    }
    fun cancelDefaults(snapshot: BotToolsetsState) {
        if (!accepts(snapshot) || !snapshot.editable || !snapshot.confirmDefaults) return
        mutableState.value = snapshot.copy(confirmDefaults = false)
    }
    fun confirmDefaults(snapshot: BotToolsetsState) {
        if (!accepts(snapshot) || !snapshot.editable || !snapshot.confirmDefaults) return
        submit(snapshot, restore = true)
    }
    fun save(snapshot: BotToolsetsState) {
        if (!accepts(snapshot) || !snapshot.canSave) return
        submit(snapshot, restore = false)
    }
    private fun completed(ticket: BotToolsetsTicket) {
        if (pending[ticket.target] != ticket) return
        pending.remove(ticket.target)
        val selected = state.value.ticket ?: return
        if (selected != ticket && selected.target == ticket.target && current(selected)) read(selected)
    }
    private fun submit(snapshot: BotToolsetsState, restore: Boolean) {
        val ticket = snapshot.ticket ?: return
        val baseline = snapshot.original ?: return
        if (ticket.target in pending) return
        pending[ticket.target] = ticket
        mutableState.value = snapshot.copy(busy = true, consumed = true, confirmDefaults = false, message = null)
        val job = scope.launch {
            try {
                val allowed = { current(ticket) && host.connected.value && pending[ticket.target] == ticket }
                val result = if (restore) repository.restoreDefaults(ticket.target, baseline, allowed)
                    else repository.save(ticket.target, baseline, snapshot.draft, allowed)
                if (!current(ticket)) return@launch
                mutableState.value = when (result) {
                    is BotToolsetsSave.Saved -> state.value.copy(original = result.actual, draft = result.actual.enabled,
                        busy = false, consumed = false, message = if (restore) "Defaults restored." else "Toolsets saved.")
                    BotToolsetsSave.Stale -> state.value.copy(busy = false,
                        message = "Toolsets changed elsewhere. Close and read them again before saving.")
                    else -> state.value.copy(busy = false,
                        message = "The toolset change could not be confirmed. Close and read it again before retrying.")
                }
                if (result is BotToolsetsSave.Saved) onChanged()
            } catch (cancelled: CancellationException) {
                if (current(ticket)) mutableState.value = state.value.copy(busy = false,
                    message = "The toolset change could not be confirmed. Close and read it again before retrying.")
                throw cancelled
            } finally { completed(ticket) }
        }
        job.invokeOnCompletion { completed(ticket) }
    }
}
