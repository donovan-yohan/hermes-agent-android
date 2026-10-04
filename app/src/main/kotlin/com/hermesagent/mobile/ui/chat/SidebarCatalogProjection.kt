package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.profiles.ProfileScope
import com.hermesagent.mobile.data.profiles.filterSessionsByProfileScope
import com.hermesagent.mobile.data.session.ProjectCatalogState
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.session.SessionCacheState
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.matchesProjectQuery
import com.hermesagent.mobile.data.session.sortProjectsForOverview
import java.util.Locale

/** Single-entry, clock-independent projection. Called only by the uiState transform;
 * never collects cache fragments separately from the snapshot resolving rehomes.
 */
internal class SidebarCatalogProjection {
    private var sessions: Map<String, SessionSummary>? = null
    private var catalog: ProjectCatalogState? = null
    private var endpoint = -1L
    private var scope: ProfileScope? = null
    private var projectId: String? = null
    private var query: String? = null
    private var locale: Locale? = null
    private var result: Result? = null

    class Result(
        val selectedProject: ProjectSummary?,
        val scopedSessions: List<SessionSummary>,
        val projects: List<ProjectSummary>,
    )

    fun derive(state: SessionCacheState, endpoint: Long, scope: ProfileScope, projectId: String?,
        query: String, locale: Locale = Locale.getDefault()): Result {
        result?.let {
            if (sessions === state.sessions && catalog === state.projects && this.endpoint == endpoint &&
                this.scope == scope && this.projectId == projectId && this.query == query && this.locale == locale
            ) return it
        }
        val showsCatalog = projectProfileScopeOf(scope).showsCatalog
        val selected = projectId?.takeIf { showsCatalog }?.let(state.projects.projects::get)
        val scoped = filterSessionsByProfileScope(
            selected?.id?.let { state.projects.memberships[it].orEmpty() }
                ?.mapNotNull(state.sessions::get) ?: state.sessions.values.toList(), scope.key,
        )
        val projects = if (selected == null && showsCatalog) {
            sortProjectsForOverview(state.projects.projects.values, state.projects.activeProjectId, locale)
                .map { project ->
                    project.copy(previewSessions = filterSessionsByProfileScope(
                        project.previewSessions.map { state.sessions[it.id] ?: it }.filter { it.hidden != true },
                        scope.key,
                    ))
                }.filter { it.matchesProjectQuery(query, locale) }
        } else emptyList()
        return Result(selected, scoped, projects).also {
            sessions = state.sessions
            catalog = state.projects
            this.endpoint = endpoint
            this.scope = scope
            this.projectId = projectId
            this.query = query
            this.locale = locale
            result = it
        }
    }
}
