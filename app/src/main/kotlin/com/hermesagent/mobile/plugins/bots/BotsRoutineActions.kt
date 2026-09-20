package com.hermesagent.mobile.plugins.bots

/** Immutable identity captured by the rendered row, including owner selection (ABA fence). */
data class RoutineTarget(val owner: String, val endpoint: Long, val selection: Long, val jobId: String)

enum class RoutineAction(val wire: String) {
    Pause("pause"), Resume("resume"), Remove("remove"),
}

/** Unknown/legacy records remain read-only; completed records may only be deleted. */
fun RoutineRow.permits(action: RoutineAction): Boolean =
    !legacyDelegated && state != RoutineRunState.Unknown && when (action) {
        RoutineAction.Pause -> active && state != RoutineRunState.Completed
        RoutineAction.Resume -> !active && state == RoutineRunState.Paused
        RoutineAction.Remove -> true
    }
