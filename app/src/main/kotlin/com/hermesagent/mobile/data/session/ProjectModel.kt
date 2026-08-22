package com.hermesagent.mobile.data.session

/**
 * One authoritative project node from Hermes' `projects.tree` contract.
 *
 * Project identity is deliberately independent from session identity. Explicit
 * projects use their projects.db id, auto projects use the backend-selected
 * workspace root, and [isHome] marks the synthetic `__no_project__` bucket.
 * The backend, never Android path heuristics, decides membership.
 */
data class ProjectSummary(
    val id: String,
    val label: String,
    val path: String?,
    val isAuto: Boolean = false,
    val isHome: Boolean = false,
    val sessionCount: Int = 0,
    val lastActiveAtMillis: Long = 0,
    val previewSessions: List<SessionSummary> = emptyList(),
)

/** Backend-authoritative project catalog plus hydrated project memberships. */
data class ProjectCatalogState(
    val projects: Map<String, ProjectSummary> = emptyMap(),
    val activeProjectId: String? = null,
    val memberships: Map<String, List<String>> = emptyMap(),
    val hydratedProjectIds: Set<String> = emptySet(),
    /** Null until probed, false only when this Gateway lacks the projects RPCs. */
    val available: Boolean? = null,
)
