package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BotSkillsTicket(val target: BotManagementTarget, val revision: Long)
data class BotSkillsState(
    val ticket: BotSkillsTicket? = null,
    val rows: List<InstalledSkill>? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val editable get() = ticket != null && rows != null && !loading && !busy
}

/** Plugin-owned operations outlive the editor. Closing revokes admission, never cancels dispatched work. */
class BotsSkillsViewModel(private val host: PluginHost, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(BotSkillsState())
    val state = mutableState.asStateFlow()
    private var revision = 0L
    private var readRevision = 0L
    private var authority: PluginSkillsScope? = null
    private val pending = mutableMapOf<BotManagementTarget, BotSkillsTicket>()
    init {
        scope.launch { host.endpointGeneration.collect { endpoint ->
            if (state.value.ticket?.target?.endpoint?.let { it != endpoint } == true) close()
        } }
    }
    private fun current(ticket: BotSkillsTicket) = state.value.ticket == ticket &&
        ticket.target.endpoint == host.endpointGeneration.value
    fun open(target: BotManagementTarget) {
        close()
        if (!validBotId(target.name) || target.name == "current" || !host.connected.value ||
            target.endpoint != host.endpointGeneration.value) return
        val ticket = BotSkillsTicket(target, revision)
        val bound = host.skills.openScope(target.endpoint, target.name)
        authority = bound
        mutableState.value = BotSkillsState(ticket = ticket, loading = true, busy = target in pending)
        read(ticket, bound)
    }
    private fun read(ticket: BotSkillsTicket, bound: PluginSkillsScope, notice: String? = null) {
        val sequence = ++readRevision
        mutableState.value = state.value.copy(loading = true, message = notice)
        scope.launch {
            val result = try { bound.listInstalled() }
            catch (_: CancellationException) { SkillsResult.Failure(SkillsFailure.Unreachable) }
            catch (_: Exception) { SkillsResult.Failure(SkillsFailure.Unreachable) }
            if (!current(ticket) || sequence != readRevision) return@launch
            mutableState.value = state.value.copy(loading = false, busy = ticket.target in pending,
                rows = (result as? SkillsResult.Success)?.value,
                message = if (result is SkillsResult.Failure)
                    listOfNotNull(notice, skillsReadFailure(result.reason)).joinToString(" ") else notice)
        }
    }
    fun refresh(snapshot: BotSkillsState) {
        val ticket = snapshot.ticket ?: return
        if (snapshot !== state.value || !current(ticket) || snapshot.loading || snapshot.busy || !host.connected.value) return
        authority?.let { read(ticket, it) }
    }
    fun close() {
        // This scope's lock prevents future enqueue. Already admitted network work is not rolled back.
        authority?.close(); authority = null
        revision++; readRevision++
        mutableState.value = BotSkillsState()
    }
    fun toggle(snapshot: BotSkillsState, name: String) {
        val ticket = snapshot.ticket ?: return
        if (snapshot !== state.value || !snapshot.editable || !current(ticket) || !host.connected.value) return
        val row = snapshot.rows?.singleOrNull { it.name == name } ?: return
        val bound = authority ?: return
        if (ticket.target in pending) return
        pending[ticket.target] = ticket
        mutableState.value = snapshot.copy(busy = true, message = null)
        scope.launch {
            val result = try { bound.toggleInstalled(name, !row.enabled) }
            catch (_: CancellationException) { SkillsResult.Failure(SkillsFailure.Unconfirmed, true) }
            catch (_: Exception) { SkillsResult.Failure(SkillsFailure.Unconfirmed, true) }
            pending.remove(ticket.target)
            val selected = state.value.ticket ?: return@launch
            if (!current(selected) || selected.target != ticket.target) return@launch
            if (selected == ticket && result is SkillsResult.Success) {
                mutableState.value = state.value.copy(busy = false, rows = result.value, message = "Skill saved.")
            } else {
                mutableState.value = state.value.copy(busy = false)
                // Even a stale/uncertain operation gets a new named read, NEVER an automatic retry.
                authority?.let { read(selected, it, "Change not confirmed. Review the current selection before trying again.") }
            }
        }
    }
}

private fun skillsReadFailure(reason: SkillsFailure): String = when (reason) {
    SkillsFailure.MissingCapability, SkillsFailure.UnavailableOnGateway -> "Installed skills are unavailable on this Gateway."
    SkillsFailure.Refused -> "This Gateway refused access to installed skills."
    SkillsFailure.InvalidScope, SkillsFailure.StaleScope -> "This profile changed. Close and open it again."
    else -> "Skills failed to load. Refresh skills to try again."
}
