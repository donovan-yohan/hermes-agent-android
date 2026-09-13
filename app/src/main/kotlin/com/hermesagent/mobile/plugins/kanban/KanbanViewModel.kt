package com.hermesagent.mobile.plugins.kanban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface KanbanPhase { data object Loading : KanbanPhase; data object Ready : KanbanPhase; data object Empty : KanbanPhase; data object Unavailable : KanbanPhase; data object Refused : KanbanPhase }
sealed interface KanbanDetail { data object None : KanbanDetail; data object Loading : KanbanDetail; data class Value(val task: KanbanTask) : KanbanDetail; data object Gone : KanbanDetail; data object Refused : KanbanDetail }
data class KanbanUiState(val phase: KanbanPhase = KanbanPhase.Loading, val columns: List<KanbanColumn> = emptyList(), val detail: KanbanDetail = KanbanDetail.None, val stale: Boolean = false) {
    val firstTask: KanbanTask? get() = columns.asSequence().flatMap { it.tasks.asSequence() }.firstOrNull()
}

/** Endpoint-scoped, manually refreshed Kanban snapshot. No polling or persistence. */
class KanbanViewModel(
    private val repository: KanbanPluginRepository,
    connected: StateFlow<Boolean>,
    private val endpointGeneration: StateFlow<Long>,
) : ViewModel() {
    private val _uiState = MutableStateFlow(KanbanUiState())
    val uiState: StateFlow<KanbanUiState> = _uiState.asStateFlow()
    private var endpoint = endpointGeneration.value

    init {
        viewModelScope.launch {
            combine(connected, endpointGeneration) { up, generation -> up to generation }.collect { (up, generation) ->
                if (generation != endpoint) clear(generation)
                if (up) refresh()
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        val generation = endpointGeneration.value
        if (_uiState.value.columns.isEmpty()) _uiState.update { it.copy(phase = KanbanPhase.Loading, stale = false) }
        when (val read = repository.board()) {
            is KanbanRead.Value -> if (generation == endpointGeneration.value) {
                val columns = read.value.columns.filter { it.tasks.isNotEmpty() }
                _uiState.value = KanbanUiState(if (columns.isEmpty()) KanbanPhase.Empty else KanbanPhase.Ready, columns)
                columns.asSequence().flatMap { it.tasks.asSequence() }.firstOrNull()?.let(::openTask)
            }
            KanbanRead.Unavailable -> if (generation == endpointGeneration.value) _uiState.value = KanbanUiState(KanbanPhase.Unavailable)
            else -> if (generation == endpointGeneration.value) _uiState.update { state ->
                if (state.columns.isNotEmpty()) state.copy(stale = true) else state.copy(phase = KanbanPhase.Refused)
            }
        }
    }

    fun openTask(task: KanbanTask) = viewModelScope.launch {
        val generation = endpointGeneration.value
        _uiState.update { it.copy(detail = KanbanDetail.Loading) }
        when (val read = repository.task(task.id)) {
            is KanbanRead.Value -> if (generation == endpointGeneration.value) _uiState.update { it.copy(detail = KanbanDetail.Value(read.value.task)) }
            KanbanRead.Gone -> if (generation == endpointGeneration.value) _uiState.update { it.copy(detail = KanbanDetail.Gone) }
            else -> if (generation == endpointGeneration.value) _uiState.update { it.copy(detail = KanbanDetail.Refused) }
        }
    }
    fun closeDetail() { _uiState.update { it.copy(detail = KanbanDetail.None) } }
    private fun clear(generation: Long) { endpoint = generation; _uiState.value = KanbanUiState() }
}

internal const val KANBAN_UNAVAILABLE = "Kanban is unavailable on this Gateway."
internal const val KANBAN_STALE = "Showing the last board snapshot. Refresh could not be completed."
