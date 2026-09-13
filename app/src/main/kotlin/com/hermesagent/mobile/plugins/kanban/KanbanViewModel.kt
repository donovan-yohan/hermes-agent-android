package com.hermesagent.mobile.plugins.kanban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface KanbanPhase {
    data object Loading : KanbanPhase
    data object Ready : KanbanPhase
    data object Empty : KanbanPhase
    data object Unavailable : KanbanPhase
    data object Refused : KanbanPhase
}

sealed interface KanbanDetail {
    data object None : KanbanDetail
    data class Loading(val task: KanbanTask) : KanbanDetail
    data class Value(val detail: KanbanTaskDetail, val stale: Boolean = false) : KanbanDetail
    data class Gone(val task: KanbanTask) : KanbanDetail
    data class Unavailable(val task: KanbanTask) : KanbanDetail
    data class Refused(val task: KanbanTask) : KanbanDetail
}

data class KanbanUiState(
    val phase: KanbanPhase = KanbanPhase.Loading,
    val columns: List<KanbanColumn> = emptyList(),
    val detail: KanbanDetail = KanbanDetail.None,
    val stale: Boolean = false,
)

/** Endpoint-scoped snapshot. New board/detail intents stamp out older answers. */
class KanbanViewModel(
    private val repository: KanbanPluginRepository,
    connected: StateFlow<Boolean>,
    private val endpointGeneration: StateFlow<Long>,
    private val pluginScope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope
        get() = pluginScope ?: viewModelScope
    private val _uiState = MutableStateFlow(KanbanUiState())
    val uiState: StateFlow<KanbanUiState> = _uiState.asStateFlow()

    private var endpoint = endpointGeneration.value
    private var boardOperation = 0L
    private var detailOperation = 0L

    init {
        scope.launch {
            combine(connected, endpointGeneration) { up, generation -> up to generation }
                .collect { (up, generation) ->
                    if (generation != endpoint) clear(generation)
                    if (up) refreshBoard()
                }
        }
    }

    fun refreshCurrent() {
        when (val detail = _uiState.value.detail) {
            is KanbanDetail.Loading -> refreshDetail(detail.task)
            is KanbanDetail.Value -> refreshDetail(detail.detail.task)
            is KanbanDetail.Gone -> refreshDetail(detail.task)
            is KanbanDetail.Unavailable -> refreshDetail(detail.task)
            is KanbanDetail.Refused -> refreshDetail(detail.task)
            KanbanDetail.None -> refreshBoard()
        }
    }

    fun refreshBoard() = scope.launch {
        ensureEndpoint()
        val endpointAtStart = endpointGeneration.value
        val operation = ++boardOperation
        if (_uiState.value.columns.isEmpty()) {
            _uiState.update { it.copy(phase = KanbanPhase.Loading, stale = false) }
        }

        when (val read = repository.board()) {
            is KanbanRead.Value -> if (acceptBoard(endpointAtStart, operation)) {
                val columns = read.value.columns.filter { it.tasks.isNotEmpty() }
                _uiState.update {
                    it.copy(
                        phase = if (columns.isEmpty()) KanbanPhase.Empty else KanbanPhase.Ready,
                        columns = columns,
                        stale = false,
                    )
                }
            }
            KanbanRead.Unavailable -> if (acceptBoard(endpointAtStart, operation)) {
                _uiState.value = KanbanUiState(KanbanPhase.Unavailable)
            }
            else -> if (acceptBoard(endpointAtStart, operation)) {
                _uiState.update { state ->
                    if (state.columns.isNotEmpty()) state.copy(stale = true)
                    else state.copy(phase = KanbanPhase.Refused)
                }
            }
        }
    }

    fun openTask(task: KanbanTask) = refreshDetail(task)

    private fun refreshDetail(task: KanbanTask) = scope.launch {
        ensureEndpoint()
        val endpointAtStart = endpointGeneration.value
        val operation = ++detailOperation
        val held = (_uiState.value.detail as? KanbanDetail.Value)?.detail
        _uiState.update { it.copy(detail = KanbanDetail.Loading(task)) }

        when (val read = repository.task(task.id)) {
            is KanbanRead.Value -> if (acceptDetail(endpointAtStart, operation)) {
                _uiState.update { it.copy(detail = KanbanDetail.Value(read.value)) }
            }
            KanbanRead.Gone -> if (acceptDetail(endpointAtStart, operation)) {
                _uiState.update { it.copy(detail = KanbanDetail.Gone(task)) }
            }
            KanbanRead.Unavailable -> if (acceptDetail(endpointAtStart, operation)) {
                _uiState.update { state ->
                    if (held != null) state.copy(detail = KanbanDetail.Value(held, stale = true))
                    else state.copy(detail = KanbanDetail.Unavailable(task))
                }
            }
            KanbanRead.Refused -> if (acceptDetail(endpointAtStart, operation)) {
                _uiState.update { state ->
                    if (held != null) state.copy(detail = KanbanDetail.Value(held, stale = true))
                    else state.copy(detail = KanbanDetail.Refused(task))
                }
            }
        }
    }

    fun closeDetail() {
        detailOperation++
        _uiState.update { it.copy(detail = KanbanDetail.None) }
    }

    private fun acceptBoard(generation: Long, operation: Long) =
        generation == endpointGeneration.value && operation == boardOperation

    private fun acceptDetail(generation: Long, operation: Long) =
        generation == endpointGeneration.value && operation == detailOperation

    private fun ensureEndpoint() {
        if (endpointGeneration.value != endpoint) clear(endpointGeneration.value)
    }

    private fun clear(generation: Long) {
        endpoint = generation
        boardOperation++
        detailOperation++
        _uiState.value = KanbanUiState()
    }
}

internal const val KANBAN_UNAVAILABLE = "Kanban is unavailable on this Gateway."
internal const val KANBAN_STALE = "Showing the last board snapshot. Refresh could not be completed."
internal const val KANBAN_DETAIL_STALE = "Showing the last task snapshot. Refresh could not be completed."
