package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BotModelTicket(val target: BotManagementTarget, val revision: Long)
data class BotModelState(
    val ticket: BotModelTicket? = null,
    val original: BotModelSelection? = null,
    val draft: BotModelSelection = BotModelSelection(),
    val providers: List<BotModelProvider> = emptyList(),
    val loading: Boolean = false,
    val inventoryLoading: Boolean = false,
    val inventoryMessage: String? = null,
    val busy: Boolean = false,
    val consumed: Boolean = false,
    val warning: String? = null,
    val message: String? = null,
) {
    val editable: Boolean get() = ticket != null && original != null && !loading && !busy && !consumed
    val canSave: Boolean get() = editable && warning == null && draft.valid && draft != original
}

/** Each rendered callback carries its snapshot; navigation cannot retarget it. */
class BotsModelViewModel(
    private val host: PluginHost,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
    private val repository: BotsModelRepository = BotsModelRepository(host),
) {
    private val mutableState = MutableStateFlow(BotModelState())
    val state = mutableState.asStateFlow()
    private var revision = 0L
    // Accepted writes outlive the selected dialog. A→B→A must not authorize another write.
    private val pending = mutableMapOf<BotManagementTarget, Any>()

    init {
        scope.launch {
            host.endpointGeneration.collect { endpoint ->
                if (state.value.ticket?.target?.endpoint?.let { it != endpoint } == true) close()
            }
        }
    }

    fun close() { revision++; mutableState.value = BotModelState() }

    fun open(target: BotManagementTarget) {
        close()
        if (!validBotId(target.name) || target.endpoint != host.endpointGeneration.value || !host.connected.value) return
        val ticket = BotModelTicket(target, revision)
        mutableState.value = BotModelState(ticket = ticket, loading = true, inventoryLoading = true,
            consumed = target in pending,
            message = if (target in pending) "A previous save is still pending. Close and reopen after it finishes." else null)
        scope.launch {
            val original = repository.describe(target)
            if (!current(ticket)) return@launch
            mutableState.value = state.value.copy(original = original, draft = original ?: BotModelSelection(),
                loading = false, message = if (original == null) "The model could not be read. Close and try again." else state.value.message)
        }
        scope.launch {
            val providers = repository.options(target)
            if (!current(ticket)) return@launch
            // Inventory never owns the draft, even when it fails after manual entry.
            mutableState.value = state.value.copy(providers = providers.orEmpty(), inventoryLoading = false,
                inventoryMessage = if (providers == null) "Model choices could not be loaded. Enter a provider and model manually." else null)
        }
    }

    private fun current(ticket: BotModelTicket): Boolean = state.value.ticket == ticket &&
        ticket.target.endpoint == host.endpointGeneration.value
    private fun accepts(snapshot: BotModelState): Boolean = snapshot === state.value &&
        snapshot.ticket?.let(::current) == true && host.connected.value

    fun update(snapshot: BotModelState, selection: BotModelSelection) {
        if (!accepts(snapshot) || !snapshot.editable) return
        mutableState.value = snapshot.copy(draft = selection, warning = null, message = null)
    }
    fun cancelWarning(snapshot: BotModelState) {
        if (!accepts(snapshot) || snapshot.busy || snapshot.warning == null) return
        mutableState.value = snapshot.copy(warning = null, message = "Model change cancelled. Nothing was saved.")
    }
    fun save(snapshot: BotModelState) {
        if (!accepts(snapshot) || !snapshot.canSave) return
        submit(snapshot, false)
    }
    fun confirm(snapshot: BotModelState) {
        if (!accepts(snapshot) || !snapshot.editable || snapshot.warning == null || !snapshot.draft.valid) return
        submit(snapshot, true)
    }

    private fun submit(snapshot: BotModelState, confirmed: Boolean) {
        val ticket = snapshot.ticket ?: return
        if (ticket.target in pending) return
        val operation = Any()
        pending[ticket.target] = operation
        mutableState.value = snapshot.copy(busy = true, consumed = true, warning = null, message = null)
        val job = scope.launch {
            try {
                if (!current(ticket) || !host.connected.value) return@launch
                val result = repository.save(ticket.target, snapshot.draft, confirmed)
                if (!current(ticket)) return@launch
                mutableState.value = when (result) {
                    BotModelSave.Saved -> state.value.copy(original = snapshot.draft, busy = false,
                        message = "Model saved.")
                    is BotModelSave.Confirmation -> if (!confirmed) state.value.copy(busy = false,
                        consumed = false, warning = result.message) else state.value.copy(busy = false,
                        message = "The model change could not be confirmed. Close and read it again before retrying.")
                    else -> state.value.copy(busy = false,
                        message = "The model change could not be confirmed. Close and read it again before retrying.")
                }
                if (result == BotModelSave.Saved) onChanged()
            } catch (cancelled: CancellationException) {
                if (current(ticket)) mutableState.value = state.value.copy(busy = false,
                    message = "The model change could not be confirmed. Close and read it again before retrying.")
                throw cancelled
            } finally {
                if (pending[ticket.target] === operation) pending.remove(ticket.target)
            }
        }
        // Also cleans a launch rejected by an already-cancelled scope.
        job.invokeOnCompletion { if (pending[ticket.target] === operation) pending.remove(ticket.target) }
    }
}
