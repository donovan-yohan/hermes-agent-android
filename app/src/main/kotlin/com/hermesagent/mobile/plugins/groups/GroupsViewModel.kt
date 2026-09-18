package com.hermesagent.mobile.plugins.groups

import com.hermesagent.mobile.plugins.PluginRefusalReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** A repository and liveness check bound to one authenticated connection leg. */
class GroupReadConnection(val repository: GroupsRepository, val isCurrent: () -> Boolean)
enum class GroupsPhase { Loading, Ready, Unsupported, Failure }
data class GroupTranscript(
    val state: GroupState, val events: List<GroupEvent> = emptyList(), val cursor: Long = 0,
    val connectedGatewayId: String? = null,
) {
    /** Ownership is relative to this connection, never inferred from a historical event kind. */
    val authorityLost: Boolean get() = connectedGatewayId != null && state.room.authority != connectedGatewayId
}
data class GroupsUiState(
    val phase: GroupsPhase = GroupsPhase.Loading,
    val rooms: List<HostedGroup> = emptyList(),
    val selected: String? = null,
    val transcript: GroupTranscript? = null,
    val stale: Boolean = false,
    val expired: Boolean = false,
    val authorityConflict: Boolean = false,
    val roomLoading: Boolean = false,
)

/** Instance-local cache; only an endpoint change drops durable room identities. */
class GroupsViewModel(
    private val scope: CoroutineScope,
    private val connections: StateFlow<GroupReadConnection?>,
    private val endpoint: StateFlow<Long>,
    private val fastMillis: Long = 1_500,
    private val idleMillis: Long = 10_000,
) {
    private val visible = MutableStateFlow(false)
    private val selected = MutableStateFlow<String?>(null)
    private val retry = MutableStateFlow(0L)
    private val mutable = MutableStateFlow(GroupsUiState())
    val uiState: StateFlow<GroupsUiState> = mutable.asStateFlow()
    private var cacheEndpoint = endpoint.value
    private val cache = mutableMapOf<String, GroupTranscript>()
    private val retired = mutableSetOf<String>()
    private var capabilityConnection: GroupReadConnection? = null
    private var capabilities: GroupCapabilities? = null

    init {
        require(fastMillis > 0 && idleMillis > 0)
        // Clearing identity cannot wait for cancellation of a non-cooperative old read.
        scope.launch { endpoint.collect { clearIfMoved() } }
        scope.launch {
            combine(connections, endpoint, visible, selected, retry) { connection, generation, foreground, id, _ ->
                ReadTarget(connection, generation, foreground, id)
            }.collectLatest { target ->
                clearIfMoved()
                if (target.id != selected.value) return@collectLatest
                val connection = target.connection
                if (connection == null || !connection.isCurrent()) {
                    mutable.update { it.copy(stale = it.rooms.isNotEmpty() || it.transcript != null) }
                    return@collectLatest
                }
                try {
                    if (capabilityConnection !== connection) {
                        val answer = connection.repository.capabilities()
                        requireCurrent(target)
                        capabilities = answer
                        capabilityConnection = connection
                        // Cached ownership remains useful on a failed catch-up, but its comparison
                        // must use this newly authenticated leg's Gateway identity.
                        if (answer.version == 2L) {
                            cache.replaceAll { _, transcript -> transcript.copy(connectedGatewayId = answer.authorityGatewayId) }
                            mutable.update { it.copy(transcript = it.transcript?.copy(connectedGatewayId = answer.authorityGatewayId)) }
                        }
                    }
                    if (capabilities?.version != 2L) {
                        mutable.update { it.copy(phase = GroupsPhase.Unsupported, stale = it.rooms.isNotEmpty()) }
                        return@collectLatest
                    }
                    if (!target.foreground) return@collectLatest
                    while (true) {
                        requireCurrent(target)
                        if (target.id == null) refreshList(target) else refreshRoom(target)
                        val state = mutable.value.transcript?.state
                        delay(if (state?.working == true || state?.blocked == true) fastMillis else idleMillis)
                    }
                } catch (failure: GroupReadFailure) {
                    requireCurrent(target)
                    if (failure.unsupported) {
                        if (capabilityConnection !== connection) {
                            capabilities = GroupCapabilities(0, false, 1)
                            capabilityConnection = connection
                        }
                        // Missing any required read method is a newer-Gateway state until reconnect/retry.
                        mutable.update { it.copy(phase = GroupsPhase.Unsupported, stale = it.rooms.isNotEmpty() || it.transcript != null) }
                    } else if (failure.reason == PluginRefusalReason.RoomHistoryExpired && target.id != null) {
                        retired += target.id
                        cache.remove(target.id)
                        mutable.update { it.copy(rooms = it.rooms.filterNot { room -> room.id == target.id },
                            transcript = null, expired = true, roomLoading = false, stale = false) }
                    } else {
                        mutable.update { it.copy(phase = GroupsPhase.Failure, roomLoading = false,
                            stale = it.rooms.isNotEmpty() || it.transcript != null,
                            authorityConflict = failure.reason == PluginRefusalReason.AuthorityConflict) }
                    }
                    // A failed cycle stops. Only an explicit retry, resume, selection or reconnect restarts it.
                }
            }
        }
    }

    fun setForeground(value: Boolean) { visible.value = value }
    fun refresh() { clearIfMoved(); retry.value += 1 }
    fun open(id: String) {
        clearIfMoved()
        if (id == selected.value || id in retired || mutable.value.rooms.none { it.id == id }) return
        // Publish loading before selection can synchronously complete a read on Main.immediate.
        mutable.update { it.copy(selected = id, transcript = cache[id], roomLoading = true,
            expired = false, authorityConflict = false, stale = false) }
        selected.value = id
    }
    fun closeRoom() {
        selected.value = null
        mutable.update { it.copy(selected = null, transcript = null, expired = false, authorityConflict = false, roomLoading = false) }
    }

    private fun clearIfMoved() {
        if (cacheEndpoint == endpoint.value) return
        cacheEndpoint = endpoint.value
        cache.clear(); retired.clear()
        selected.value = null
        capabilities = null; capabilityConnection = null
        mutable.value = GroupsUiState()
    }

    private suspend fun requireCurrent(target: ReadTarget) {
        currentCoroutineContext().ensureActive()
        if (endpoint.value != target.endpoint || target.connection !== connections.value ||
            target.connection?.isCurrent() != true) throw kotlinx.coroutines.CancellationException("Group read leg changed")
    }

    private suspend fun refreshList(target: ReadTarget) {
        val rooms = target.connection!!.repository.list()
        requireCurrent(target)
        mutable.update { it.copy(phase = GroupsPhase.Ready, rooms = rooms.filterNot { room -> room.id in retired }, stale = false) }
    }

    private suspend fun refreshRoom(target: ReadTarget) {
        val id = target.id ?: return
        if (id in retired) return
        val repository = target.connection!!.repository
        val state = repository.state(id)
        requireCurrent(target)
        val held = cache[id]
        // Only fresh state.latest_seq proves cursor-ahead; a reasonless 4112 never does.
        val reset = held != null && state.room.latest < held.cursor
        val connectedGatewayId = capabilities!!.authorityGatewayId
        var next = if (reset || held == null) GroupTranscript(state, connectedGatewayId = connectedGatewayId)
            else held.copy(state = state, connectedGatewayId = connectedGatewayId)
        repository.log(id, next.cursor, capabilities!!.limit) { page ->
            // No suspension between this check and apply. A cancelled non-cooperative answer cannot paint.
            if (endpoint.value != target.endpoint || connections.value !== target.connection ||
                !target.connection.isCurrent() || id in retired) throw kotlinx.coroutines.CancellationException("Stale group page")
            next = next.copy(
                state = next.state.copy(room = next.state.room.copy(
                    disbanded = next.state.room.disbanded || page.events.any { it.kind == "room.disbanded" },
                    // Page authority is the store's current owner, including on empty pages;
                    // a historical claim in this page may already have been superseded.
                    authority = page.authority,
                )),
                events = next.events + page.events,
                cursor = page.cursor,
            )
        }
        requireCurrent(target)
        // Commit the catch-up atomically, particularly when replacing an ahead cursor's history.
        cache[id] = next
        if (next.state.room.disbanded) retired += id
        mutable.update { it.copy(phase = GroupsPhase.Ready, transcript = next, roomLoading = false,
            stale = false, authorityConflict = false,
            rooms = if (next.state.room.disbanded) it.rooms.filterNot { room -> room.id == id }
                else it.rooms.map { room -> if (room.id == id) next.state.room else room }) }
    }

    private data class ReadTarget(val connection: GroupReadConnection?, val endpoint: Long, val foreground: Boolean, val id: String?)
}
