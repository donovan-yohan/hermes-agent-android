package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.profiles.ALL_PROFILES
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
        val requestedCatalog = state.projectsForScope(scope.sessionProfileParam)
        result?.let {
            if (sessions === state.sessions && catalog === requestedCatalog && this.endpoint == endpoint &&
                this.scope == scope && this.projectId == projectId && this.query == query && this.locale == locale
            ) return it
        }
        val showsCatalog = projectProfileScopeOf(scope).showsCatalog
        val selected = projectId?.takeIf { showsCatalog }?.let(requestedCatalog.projects::get)
        // An omitted route can serve a named launch profile. Its project reply
        // already scopes membership; never erase the producer's concrete owner.
        val omittedRoute = scope.sessionProfileParam == null
        val scoped = filterSessionsByProfileScope(
            selected?.id?.let { requestedCatalog.memberships[it].orEmpty() }
                ?.mapNotNull { id ->
                    val owner = if (requestedCatalog.sessionProfiles.containsKey(id)) requestedCatalog.sessionProfiles[id]
                        else scope.sessionProfileParam
                    state.sessions[id]?.takeIf { it.remoteProfile == owner }
                } ?: state.sessions.values.toList(),
            if (selected != null && omittedRoute) ALL_PROFILES else scope.key,
        )
        val projects = if (selected == null && showsCatalog) {
            sortProjectsForOverview(requestedCatalog.projects.values, requestedCatalog.activeProjectId, locale)
                .map { project ->
                    project.copy(previewSessions = filterSessionsByProfileScope(
                        project.previewSessions.mapNotNull { preview ->
                            val live = state.sessions[preview.id]
                            // Null means omitted/launch, not explicit default.
                            // The resume overload cannot carry that owner over
                            // a foreign raw-id slot; wait for revalidation.
                            if (preview.remoteProfile == null && live?.remoteProfile != null) null
                            else live?.takeIf { it.remoteProfile == preview.remoteProfile } ?: preview
                        }.filter { it.hidden != true },
                        if (omittedRoute) ALL_PROFILES else scope.key,
                    ))
                }.filter { it.matchesProjectQuery(query, locale) }
        } else emptyList()
        return Result(selected, scoped, projects).also {
            sessions = state.sessions
            catalog = requestedCatalog
            this.endpoint = endpoint
            this.scope = scope
            this.projectId = projectId
            this.query = query
            this.locale = locale
            result = it
        }
    }
}
