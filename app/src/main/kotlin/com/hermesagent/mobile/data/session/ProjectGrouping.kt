package com.hermesagent.mobile.data.session

import java.util.Locale

/**
 * Desktop's deterministic project-overview order at pinned upstream
 * `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`:
 * Home first; then active explicit project, explicit before auto, projects with
 * sessions before empty discoveries, recent activity, and finally label.
 * Mobile deliberately does not port Desktop's drag-order persistence.
 */
fun sortProjectsForOverview(
    projects: Collection<ProjectSummary>,
    activeProjectId: String?,
    locale: Locale = Locale.getDefault(),
): List<ProjectSummary> {
    val sorted = projects.sortedWith(
        compareByDescending<ProjectSummary> { it.id == activeProjectId && !it.isAuto }
            .thenBy { it.isAuto }
            .thenByDescending { it.sessionCount > 0 }
            .thenByDescending { it.lastActiveAtMillis }
            .thenBy { it.label.lowercase(locale) }
            .thenBy { it.id.lowercase(locale) },
    )
    return sorted.sortedByDescending(ProjectSummary::isHome)
}

fun ProjectSummary.matchesProjectQuery(query: String, locale: Locale = Locale.getDefault()): Boolean {
    val needle = query.trim().lowercase(locale)
    return needle.isEmpty() ||
        label.lowercase(locale).contains(needle) ||
        previewSessions.any { session ->
            session.title.lowercase(locale).contains(needle) || session.preview.lowercase(locale).contains(needle)
        }
}
