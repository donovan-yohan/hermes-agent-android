package com.hermesagent.mobile.data.gateway

/** Issued synchronously for one catalog node and its original connection/profile revision. */
interface ProjectEditor {
    fun isCurrent(): Boolean
    suspend fun execute(operation: ProjectOperation): ProjectEditOutcome
    suspend fun execute(operation: ProjectOperation, isSurfaceCurrent: () -> Boolean): ProjectEditOutcome {
        check(isSurfaceCurrent())
        return execute(operation)
    }
}

sealed interface ProjectOperation {
    data class Rename(val name: String) : ProjectOperation
    data class AddFolder(val path: String, val label: String? = null, val isPrimary: Boolean = false) : ProjectOperation
    data class Color(val value: String?) : ProjectOperation
    data class Icon(val value: String?) : ProjectOperation
    data object SetActive : ProjectOperation
    data object Delete : ProjectOperation
}

/** Only returned after an acknowledged write; a failed readback must never invite replay. */
data class ProjectEditOutcome(val projectId: String, val catalogRefreshed: Boolean, val scopeCurrent: Boolean)
