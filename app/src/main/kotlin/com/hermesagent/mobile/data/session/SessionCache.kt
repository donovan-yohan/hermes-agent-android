package com.hermesagent.mobile.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Everything the cache holds. Sessions, transcripts and project membership move atomically. */
data class SessionCacheState(
    val sessions: Map<String, SessionSummary> = emptyMap(),
    val transcripts: Map<String, List<TranscriptEntry>> = emptyMap(),
    /** Canonical durable-id aliases published atomically with a re-home. */
    val rehomes: Map<String, String> = emptyMap(),
    val projects: ProjectCatalogState = ProjectCatalogState(),
)

/**
 * The cache of backend truth.
 *
 * The live Gateway repository is the writer. Its merge rules are taken from
 * `apps/desktop/AGENTS.md` ("Server truth is cached, not owned" @ `29112bef`):
 *
 * - **Merge, don't clobber.** [upsertSessions] layers new information over
 *   what is already known; it never drops a row the refresh did not mention.
 *   A row disappears only through [removeSession], the explicit tombstone.
 * - **Preserve reference identity on no-ops.** An upsert that changes nothing
 *   returns the same state instance, so Compose does not recompose the list.
 * - **Project membership is a snapshot.** Android never derives it from cwd;
 *   `projects.tree` replaces the overview and `projects.project_sessions`
 *   replaces one project's hydrated membership.
 *
 * UI-only state (search text, drawer open, draft) is deliberately *not* here.
 * It belongs to the ViewModel and dies with the screen.
 */
class SessionCache {

    private val _state = MutableStateFlow(SessionCacheState())
    val state: StateFlow<SessionCacheState> = _state.asStateFlow()

    fun upsertSessions(rows: List<SessionSummary>) {
        if (rows.isEmpty()) return
        _state.update { current ->
            val merged = current.sessions.toMutableMap()
            var changed = false
            for (row in rows) {
                if (merged[row.id] != row) {
                    merged[row.id] = row
                    changed = true
                }
            }
            if (changed) current.copy(sessions = merged) else current
        }
    }

    fun upsertSession(row: SessionSummary) = upsertSessions(listOf(row))

    /** Replace the authoritative project overview and merge its preview rows. */
    fun replaceProjectOverview(rows: List<ProjectSummary>, activeProjectId: String?) {
        _state.update { current ->
            val projectIds = rows.mapTo(linkedSetOf(), ProjectSummary::id)
            val existingHydrated = current.projects.hydratedProjectIds.intersect(projectIds)
            val memberships = rows.associateTo(linkedMapOf()) { project ->
                val previewIds = project.previewSessions.map(SessionSummary::id)
                val ids = if (project.id in existingHydrated) {
                    current.projects.memberships[project.id].orEmpty()
                } else {
                    previewIds
                }
                project.id to ids
            }
            val sessions = current.sessions.toMutableMap()
            rows.flatMap(ProjectSummary::previewSessions).forEach { preview ->
                sessions.putIfAbsent(preview.id, preview)
            }
            val catalog = ProjectCatalogState(
                projects = rows.associateByTo(linkedMapOf(), ProjectSummary::id),
                activeProjectId = activeProjectId,
                memberships = memberships,
                hydratedProjectIds = existingHydrated,
                available = true,
            )
            if (sessions == current.sessions && catalog == current.projects) {
                current
            } else {
                current.copy(sessions = sessions, projects = catalog)
            }
        }
    }

    /** Atomically publish one fully hydrated project and every session it owns. */
    fun replaceProjectDetails(project: ProjectSummary, sessions: List<SessionSummary>) {
        _state.update { current ->
            val existingProject = current.projects.projects[project.id]
            val mergedProject = if (project.previewSessions.isEmpty() && existingProject != null) {
                project.copy(previewSessions = existingProject.previewSessions)
            } else {
                project
            }
            val mergedSessions = current.sessions.toMutableMap()
            sessions.forEach { row ->
                val existing = mergedSessions[row.id]
                mergedSessions[row.id] = if (existing == null) {
                    row
                } else {
                    row.copy(
                        status = existing.status,
                        progress = existing.progress,
                        composerStatus = existing.composerStatus,
                        activityStartedAtMillis = existing.activityStartedAtMillis,
                        gitBranch = row.gitBranch ?: existing.gitBranch,
                        worktreePath = row.worktreePath ?: existing.worktreePath,
                        // Project membership rows carry no owning profile, so a
                        // drill-in must not move a row out of the profile scope
                        // it was listed in.
                        remoteProfile = row.remoteProfile ?: existing.remoteProfile,
                    )
                }
            }
            val catalog = current.projects.copy(
                projects = current.projects.projects + (project.id to mergedProject),
                memberships = current.projects.memberships + (project.id to sessions.map(SessionSummary::id)),
                hydratedProjectIds = current.projects.hydratedProjectIds + project.id,
                available = true,
            )
            if (mergedSessions == current.sessions && catalog == current.projects) {
                current
            } else {
                current.copy(sessions = mergedSessions, projects = catalog)
            }
        }
    }

    /**
     * Drop everything, because the next backend is a different machine.
     *
     * This is the one wholesale clear, and it is not a weaker tombstone: it is
     * what Desktop's `wipeSessionListsForGatewaySwitch` does on the same event
     * (`apps/desktop/src/store/gateway-switch.ts:47-96` @ `29112bef`). Two
     * gateways can recycle the same durable id, and painting one machine's
     * conversation under another's row is worse than an empty list — so a
     * switch clears rather than merges, and only a switch may call this.
     */
    internal fun resetForEndpointSwitch() {
        _state.update { current -> if (current == SessionCacheState()) current else SessionCacheState() }
    }

    /** A new Gateway generation must not paint the previous profile's project catalog. */
    fun clearProjects() {
        _state.update { current ->
            if (current.projects == ProjectCatalogState()) current else current.copy(projects = ProjectCatalogState())
        }
    }

    /** Clear connection-owned projections; acknowledged queue rows may bridge a reconnect for resume reconciliation. */
    fun clearConnectionScopedFields(preserveGatewayQueue: Boolean = false) {
        _state.update { current ->
            val sessions = current.sessions.mapValues { (_, row) ->
                val composerStatus = if (preserveGatewayQueue) {
                    row.composerStatus.retainingGatewayQueue()
                } else {
                    null
                }
                row.copy(
                    progress = null,
                    gitBranch = null,
                    worktreePath = null,
                    composerStatus = composerStatus,
                    activityStartedAtMillis = null,
                )
            }
            if (sessions == current.sessions) current else current.copy(sessions = sessions)
        }
    }

    /** Explicit compatibility state for Gateways predating the projects RPCs. */
    fun markProjectsUnavailable() {
        _state.update { current ->
            val unavailable = ProjectCatalogState(available = false)
            if (current.projects == unavailable) current else current.copy(projects = unavailable)
        }
    }

    /** Explicit tombstone. The only way a session leaves the cache. */
    fun removeSession(id: String) {
        _state.update { current ->
            if (!current.sessions.containsKey(id) && !current.transcripts.containsKey(id)) {
                current
            } else {
                val projects = current.projects.copy(
                    projects = current.projects.projects.mapValues { (_, project) ->
                        project.copy(previewSessions = project.previewSessions.filterNot { it.id == id })
                    },
                    memberships = current.projects.memberships.mapValues { (_, ids) -> ids.filterNot { it == id } },
                )
                current.copy(
                    sessions = current.sessions - id,
                    transcripts = current.transcripts - id,
                    rehomes = current.rehomes.filter { (from, to) -> from != id && to != id },
                    projects = projects,
                )
            }
        }
    }

    fun appendEntry(sessionId: String, entry: TranscriptEntry) {
        _state.update { current ->
            val existing = current.transcripts[sessionId].orEmpty()
            current.copy(transcripts = current.transcripts + (sessionId to existing + entry))
        }
    }

    /**
     * Replace an entry by id, or append it when it is new. This is how a
     * streaming turn grows: one entry rewritten in place rather than a new
     * message per delta.
     *
     * The replacement is wholesale, so a caller that overwrites a hydrated
     * entry with a live one drops that row's durable [TranscriptEntry.rowId];
     * a transcript merge that can carry persisted rows (#68 S25) has to
     * preserve the address already held when the incoming entry has none.
     */
    fun putEntry(sessionId: String, entry: TranscriptEntry) {
        _state.update { current ->
            val existing = current.transcripts[sessionId].orEmpty()
            val index = existing.indexOfFirst { it.id == entry.id }
            when {
                index < 0 -> current.copy(transcripts = current.transcripts + (sessionId to existing + entry))
                existing[index] == entry -> current
                else -> {
                    val updated = existing.toMutableList().apply { this[index] = entry }
                    current.copy(transcripts = current.transcripts + (sessionId to updated))
                }
            }
        }
    }

    fun setTranscript(sessionId: String, entries: List<TranscriptEntry>) {
        _state.update { current ->
            if (current.transcripts[sessionId] == entries) {
                current
            } else {
                current.copy(transcripts = current.transcripts + (sessionId to entries))
            }
        }
    }

    /**
     * Atomically move a backend session to its canonical durable id. This is
     * used when `session.resume` follows a compression continuation: the
     * summary and transcript move together, so observers never see a canonical
     * row without its conversation.
     */
    fun rehomeSession(fromId: String, row: SessionSummary, entries: List<TranscriptEntry>) {
        _state.update { current ->
            val targetId = row.id
            val sessions = current.sessions.toMutableMap().apply {
                remove(fromId)
                this[targetId] = row
            }
            val transcripts = current.transcripts.toMutableMap().apply {
                remove(fromId)
                this[targetId] = entries
            }
            val rehomes = current.rehomes.mapValues { (_, to) -> if (to == fromId) targetId else to }
                .toMutableMap()
                .apply {
                    remove(targetId)
                    if (fromId != targetId) this[fromId] = targetId
                }
            val projects = current.projects.copy(
                projects = current.projects.projects.mapValues { (_, project) ->
                    project.copy(
                        previewSessions = project.previewSessions
                            .map { preview -> if (preview.id == fromId) row else preview }
                            .distinctBy(SessionSummary::id),
                    )
                },
                memberships = current.projects.memberships.mapValues { (_, ids) ->
                    ids.map { id -> if (id == fromId) targetId else id }.distinct()
                },
            )
            if (sessions == current.sessions && transcripts == current.transcripts &&
                rehomes == current.rehomes && projects == current.projects
            ) {
                current
            } else {
                current.copy(sessions = sessions, transcripts = transcripts, rehomes = rehomes, projects = projects)
            }
        }
    }

    fun session(id: String): SessionSummary? = _state.value.sessions[id]

    fun transcript(id: String): List<TranscriptEntry> = _state.value.transcripts[id].orEmpty()
}
