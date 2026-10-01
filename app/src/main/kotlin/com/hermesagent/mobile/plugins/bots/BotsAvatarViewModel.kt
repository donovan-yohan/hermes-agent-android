package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class BotAvatarTicket(val target: BotManagementTarget, val revision: Long)
data class BotAvatarPick(val ticket: BotAvatarTicket, val sequence: Long)
data class BotAvatarState(
    val ticket: BotAvatarTicket? = null, val original: BotAvatarRead? = null,
    val change: BotAvatarChange = BotAvatarChange.Unchanged,
    val loading: Boolean = false, val picking: BotAvatarPick? = null,
    val busy: Boolean = false, val consumed: Boolean = false, val message: String? = null,
) {
    val editable get() = ticket != null && original != null && !loading && picking == null && !busy && !consumed
    val bytes get() = when (val c = change) { is BotAvatarChange.Image -> c.bytes; BotAvatarChange.Clear -> null; else -> original?.bytes }
    val canSave get() = editable && change != BotAvatarChange.Unchanged && !bytes.contentEquals(original?.bytes)
}
class BotsAvatarViewModel(private val host: PluginHost, private val scope: CoroutineScope, private val onChanged: () -> Unit) {
    private val mutableState = MutableStateFlow(BotAvatarState())
    val state = mutableState.asStateFlow()
    private val repository = BotsAvatarRepository(host)
    private var revision = 0L
    private var picks = 0L
    private val pending = mutableMapOf<BotManagementTarget, BotAvatarTicket>()
    private var readRevision = 0L
    init {
        scope.launch { host.endpointGeneration.collect { endpoint ->
            if (state.value.ticket?.target?.endpoint?.let { it != endpoint } == true) close()
        } }
    }
    fun close() { revision++; mutableState.value = BotAvatarState() }
    fun open(target: BotManagementTarget) {
        close()
        if (!validBotId(target.name) || !host.connected.value || target.endpoint != host.endpointGeneration.value) return
        val ticket = BotAvatarTicket(target, revision)
        mutableState.value = BotAvatarState(ticket = ticket, loading = true, consumed = target in pending)
        read(ticket)
    }
    private fun read(ticket: BotAvatarTicket) {
        val request = ++readRevision
        mutableState.value = state.value.copy(loading = true)
        scope.launch {
            val result = repository.read(ticket.target)
            if (!current(ticket) || request != readRevision) return@launch
            mutableState.value = state.value.copy(original = result, loading = false,
                consumed = ticket.target in pending,
                change = BotAvatarChange.Unchanged,
                message = if (result == null) "The avatar could not be read. Close and try again." else null)
        }
    }
    private fun completed(ticket: BotAvatarTicket) {
        // Exact operation identity: a late completion must not clear a newer save.
        if (pending[ticket.target] != ticket) return
        pending.remove(ticket.target)
        val selected = state.value.ticket ?: return
        if (selected != ticket && selected.target == ticket.target && current(selected)) {
            // Reopening while pending may have read the pre-write asset. Supersede
            // that read, never replay the mutation or publish the old dialog draft.
            read(selected)
        }
    }
    private fun current(ticket: BotAvatarTicket) = state.value.ticket == ticket && ticket.target.endpoint == host.endpointGeneration.value
    private fun accepts(snapshot: BotAvatarState) = snapshot === state.value &&
        snapshot.ticket?.let(::current) == true && host.connected.value
    fun beginPick(snapshot: BotAvatarState): BotAvatarPick? {
        if (!accepts(snapshot) || !snapshot.editable) return null
        val pick = BotAvatarPick(snapshot.ticket!!, ++picks)
        mutableState.value = snapshot.copy(picking = pick, message = null)
        return pick
    }
    fun finishPick(pick: BotAvatarPick, bytes: ByteArray?) {
        if (!current(pick.ticket) || state.value.picking != pick) return
        mutableState.value = state.value.copy(picking = null,
            change = if (bytes == null) state.value.change else BotAvatarChange.Image(bytes),
            message = if (bytes == null) "Image selection cancelled. Nothing was saved." else null)
    }
    fun failPick(pick: BotAvatarPick) {
        if (!current(pick.ticket) || state.value.picking != pick) return
        mutableState.value = state.value.copy(picking = null,
            message = "The image could not be opened. Choose a smaller PNG, JPEG, WebP or GIF image.")
    }
    fun clear(snapshot: BotAvatarState) {
        if (!accepts(snapshot) || !snapshot.editable || snapshot.bytes == null) return
        mutableState.value = snapshot.copy(change = BotAvatarChange.Clear, message = null)
    }
    fun save(snapshot: BotAvatarState) {
        if (!accepts(snapshot) || !snapshot.canSave) return
        val ticket = snapshot.ticket ?: return
        if (ticket.target in pending) return
        pending[ticket.target] = ticket
        mutableState.value = snapshot.copy(busy = true, consumed = true, message = null)
        val job = scope.launch {
            try {
                if (!current(ticket) || !host.connected.value) return@launch
                val result = repository.save(ticket.target, snapshot.original?.bytes, snapshot.change) {
                    current(ticket) && host.connected.value
                }
                // Invalidate even when the dialog closed: accepted writes outlive selection.
                if (ticket.target.endpoint == host.endpointGeneration.value) onChanged()
                if (!current(ticket)) return@launch
                mutableState.value = if (result == BotAvatarSave.Saved) state.value.copy(
                    original = BotAvatarRead(snapshot.bytes), change = BotAvatarChange.Unchanged, busy = false, consumed = false,
                    message = if (snapshot.bytes == null) "Avatar removed." else "Avatar saved.")
                else state.value.copy(busy = false, change = BotAvatarChange.Unchanged,
                    message = "The avatar change could not be confirmed. Close and read it again before retrying.")
            } catch (cancelled: CancellationException) {
                if (current(ticket)) mutableState.value = state.value.copy(busy = false, change = BotAvatarChange.Unchanged,
                    message = "The avatar change could not be confirmed. Close and read it again before retrying.")
                throw cancelled
            } finally { completed(ticket) }
        }
        job.invokeOnCompletion { completed(ticket) }
    }
}
