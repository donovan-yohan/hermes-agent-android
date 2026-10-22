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
    /** Exact RPC route: omitted launch (null) is not literal "default". */
    val activeProjectScope: String? = null,
    val projectSnapshots: Map<String?, ProjectCatalogState> = emptyMap(),
) {
    val projects: ProjectCatalogState get() = projectsForScope(activeProjectScope)
    fun projectsForScope(profile: String?): ProjectCatalogState = projectSnapshots[profile] ?: UNKNOWN_PROJECTS

    internal fun withProjects(catalog: ProjectCatalogState, scope: String? = activeProjectScope): SessionCacheState =
        if (catalog == projectsForScope(scope)) this else copy(projectSnapshots = projectSnapshots + (scope to catalog))
}

private val UNKNOWN_PROJECTS = ProjectCatalogState()

/**
 * The cache of backend truth.
 *
 * The live Gateway repository is the writer. Its merge rules are taken from
 * `apps/desktop/AGENTS.md` ("Server truth is cached, not owned" @ `72a3277cd7`):
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

    private val _endpointGeneration = MutableStateFlow(0L)

    /**
     * Which backend this cache holds, bumped by [resetForEndpointSwitch] and by
     * nothing else.
     *
     * It is not part of [SessionCacheState] because it is not a row: it says
     * *whose* rows these are. A reader that keeps its own endpoint-scoped copy
     * of backend truth — the Archived view's capped `archived=only` pool is the
     * one — needs to learn that the copy it holds is now about a different
     * machine, and this is the same seam saying so rather than a second one.
     */
    val endpointGeneration: StateFlow<Long> = _endpointGeneration.asStateFlow()

    fun upsertSessions(rows: List<SessionSummary>) {
        if (rows.isEmpty()) return
        _state.update { current ->
            val merged = current.sessions.toMutableMap()
            var changed = false
            for (row in rows) {
                val next = row.copy(hidden = row.hidden ?: merged[row.id]?.hidden)
                if (merged[row.id] != next) {
                    merged[row.id] = next
                    changed = true
                }
            }
            if (changed) current.copy(sessions = merged) else current
        }
    }

    fun upsertSession(row: SessionSummary) = upsertSessions(listOf(row))

    /**
     * Replace the authoritative project overview and merge its preview rows.
     *
     * [authoritativeSessionProfiles] says whether the caller's read was scoped
     * by the request it made. A stamped concrete owner stays concrete; an absent
     * stamp is authoritative legacy launch ownership, not missing metadata.
     * Only the request-scoped project RPCs can claim that.
     */
    fun replaceProjectOverview(
        rows: List<ProjectSummary>,
        activeProjectId: String?,
        authoritativeSessionProfiles: Boolean = false,
        scope: String? = state.value.activeProjectScope,
    ) {
        _state.update { current ->
            val previous = current.projectsForScope(scope)
            val projectIds = rows.mapTo(linkedSetOf(), ProjectSummary::id)
            val existingHydrated = previous.hydratedProjectIds.intersect(projectIds)
            val memberships = rows.associateTo(linkedMapOf()) { project ->
                val previewIds = project.previewSessions.map(SessionSummary::id)
                val ids = if (project.id in existingHydrated) {
                    previous.memberships[project.id].orEmpty()
                } else {
                    previewIds
                }
                project.id to ids
            }
            val sessions = current.sessions.toMutableMap()
            rows.flatMap(ProjectSummary::previewSessions).forEach { preview ->
                val existing = sessions[preview.id]
                if (existing != null && existing.remoteProfile != preview.remoteProfile) return@forEach
                sessions[preview.id] = existing?.copy(
                    // A preview can correct scope, but must not replace richer
                    // session-list metadata or an in-flight turn's state.
                    remoteProfile = if (authoritativeSessionProfiles) preview.remoteProfile
                        else preview.remoteProfile ?: existing.remoteProfile,
                    hidden = preview.hidden ?: existing.hidden,
                ) ?: preview
            }
            val catalog = ProjectCatalogState(
                projects = rows.associateByTo(linkedMapOf(), ProjectSummary::id),
                activeProjectId = activeProjectId,
                memberships = memberships,
                hydratedProjectIds = existingHydrated,
                available = true,
                sessionProfiles = buildMap {
                    val retainedIds = memberships.values.flatten().toSet()
                    putAll(previous.sessionProfiles.filterKeys { it in retainedIds })
                    rows.flatMap(ProjectSummary::previewSessions).forEach { put(it.id, it.remoteProfile) }
                },
                sessionRows = previous.sessionRows.filterKeys { it in memberships.values.flatten().toSet() } +
                    rows.flatMap(ProjectSummary::previewSessions).associateBy(SessionSummary::id),
            )
            if (sessions == current.sessions && catalog == previous) {
                current
            } else {
                current.withProjects(catalog, scope).copy(sessions = sessions)
            }
        }
    }

    /**
     * Atomically publish one fully hydrated project and every session it owns.
     *
     * [authoritativeSessionProfiles] carries the same meaning as in
     * [replaceProjectOverview]: a request-scoped read's absent profile is
     * legacy launch ownership, and a concrete stamp replaces the previous owner.
     */
    fun replaceProjectDetails(
        project: ProjectSummary,
        sessions: List<SessionSummary>,
        authoritativeSessionProfiles: Boolean = false,
        scope: String? = state.value.activeProjectScope,
    ) {
        _state.update { current ->
            val previous = current.projectsForScope(scope)
            val existingProject = previous.projects[project.id]
            val mergedProject = if (project.previewSessions.isEmpty() && existingProject != null) {
                project.copy(previewSessions = existingProject.previewSessions)
            } else {
                project
            }
            val mergedSessions = current.sessions.toMutableMap()
            sessions.forEach { row ->
                val existing = mergedSessions[row.id]
                if (existing != null && existing.remoteProfile != row.remoteProfile) return@forEach
                mergedSessions[row.id] = if (existing == null) {
                    row
                } else {
                    row.copy(
                        hidden = row.hidden ?: existing.hidden,
                        status = existing.status,
                        progress = existing.progress,
                        composerStatus = existing.composerStatus,
                        activityStartedAtMillis = existing.activityStartedAtMillis,
                        gitBranch = row.gitBranch ?: existing.gitBranch,
                        worktreePath = row.worktreePath ?: existing.worktreePath,
                        // Request-scoped reads know that null means launch-owned;
                        // otherwise an absent stamp adds no ownership evidence.
                        remoteProfile = if (authoritativeSessionProfiles) row.remoteProfile
                            else row.remoteProfile ?: existing.remoteProfile,
                    )
                }
            }
            val catalog = previous.copy(
                projects = previous.projects + (project.id to mergedProject),
                memberships = previous.memberships + (project.id to sessions.map(SessionSummary::id)),
                hydratedProjectIds = previous.hydratedProjectIds + project.id,
                available = true,
                sessionProfiles = previous.sessionProfiles + sessions.associate { it.id to it.remoteProfile },
                sessionRows = previous.sessionRows + sessions.associateBy(SessionSummary::id),
            )
            if (mergedSessions == current.sessions && catalog == previous) {
                current
            } else {
                current.withProjects(catalog, scope).copy(sessions = mergedSessions)
            }
        }
    }

    /**
     * Drop everything, because the next backend is a different machine.
     *
     * This is the one wholesale clear, and it is not a weaker tombstone: it is
     * what Desktop's `wipeSessionListsForGatewaySwitch` does on the same event
     * (`apps/desktop/src/store/gateway-switch.ts:48-97` @ `72a3277cd7`). Two
     * gateways can recycle the same durable id, and painting one machine's
     * conversation under another's row is worse than an empty list — so a
     * switch clears rather than merges, and only a switch may call this.
     */
    internal fun resetForEndpointSwitch() {
        _state.update { current -> if (current == SessionCacheState()) current else SessionCacheState() }
        _endpointGeneration.update { it + 1 }
    }

    /** Restore backend project truth only; live sessions/transcripts and UI selection never rewind. */
    fun activateProjectScope(profile: String?) {
        _state.update { current ->
            if (current.activeProjectScope == profile) current else current.copy(activeProjectScope = profile)
        }
    }

    /** Reconnect invalidates every transport-owned catalog, not just the visible one. */
    fun clearProjects() {
        _state.update { current ->
            if (current.projectSnapshots.isEmpty()) current else current.copy(projectSnapshots = emptyMap())
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
            current.withProjects(unavailable)
        }
    }

    /** Explicit tombstone. The only way a session leaves the cache. */
    fun removeSession(id: String) {
        _state.update { current ->
            val hasMembership = current.projectSnapshots.values.any { catalog ->
                catalog.memberships.values.any { id in it } ||
                    catalog.projects.values.any { project -> project.previewSessions.any { it.id == id } }
            }
            if (!current.sessions.containsKey(id) && !current.transcripts.containsKey(id) && !hasMembership &&
                current.rehomes.none { (from, to) -> from == id || to == id }
            ) {
                current
            } else {
                val snapshots = current.projectSnapshots.mapValues { (_, catalog) ->
                    val next = catalog.copy(
                        projects = catalog.projects.mapValues { (_, project) ->
                            project.copy(previewSessions = project.previewSessions.filterNot { it.id == id })
                        },
                        memberships = catalog.memberships.mapValues { (_, ids) -> ids.filterNot { it == id } },
                        sessionProfiles = catalog.sessionProfiles - id,
                        sessionRows = catalog.sessionRows - id,
                    )
                    if (next == catalog) catalog else next
                }
                current.copy(
                    sessions = current.sessions - id,
                    transcripts = current.transcripts - id,
                    rehomes = current.rehomes.filter { (from, to) -> from != id && to != id },
                    projectSnapshots = snapshots,
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
     * The replacement is wholesale in everything but the durable address: an
     * incoming entry that carries no [TranscriptEntry.rowId] keeps the one the
     * entry it replaces already held, because null there means "the backend has
     * not written this row down yet", never "this row has no durable identity".
     * Dropping it would cost the transcript merge (#68 S25) the only key it can
     * dedupe an overlapping older page on.
     */
    fun putEntry(sessionId: String, entry: TranscriptEntry) {
        _state.update { current ->
            val existing = current.transcripts[sessionId].orEmpty()
            val index = existing.indexOfFirst { it.id == entry.id }
            if (index < 0) {
                return@update current.copy(transcripts = current.transcripts + (sessionId to existing + entry))
            }
            val incoming = entry.preservingRowIdOf(existing[index])
            if (existing[index] == incoming) {
                current
            } else {
                val updated = existing.toMutableList().apply { this[index] = incoming }
                current.copy(transcripts = current.transcripts + (sessionId to updated))
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
            val canonical = row.copy(
                hidden = row.hidden ?: current.sessions[targetId]?.hidden ?: current.sessions[fromId]?.hidden,
            )
            val sessions = current.sessions.toMutableMap().apply {
                remove(fromId)
                this[targetId] = canonical
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
            val snapshots = current.projectSnapshots.mapValues { (profile, catalog) ->
                val owner = if (catalog.sessionProfiles.containsKey(fromId)) catalog.sessionProfiles[fromId]
                    else profile
                if (owner != canonical.remoteProfile) return@mapValues catalog
                val next = catalog.copy(
                    projects = catalog.projects.mapValues { (_, project) ->
                        project.copy(
                            previewSessions = project.previewSessions
                                .map { preview -> if (preview.id == fromId || preview.id == targetId) canonical else preview }
                                .distinctBy(SessionSummary::id),
                        )
                    },
                    memberships = catalog.memberships.mapValues { (_, ids) ->
                        ids.map { id -> if (id == fromId) targetId else id }.distinct()
                    },
                    sessionProfiles = if (catalog.sessionProfiles.containsKey(fromId)) {
                        catalog.sessionProfiles - fromId + (targetId to canonical.remoteProfile)
                    } else catalog.sessionProfiles,
                    sessionRows = if (catalog.sessionRows.containsKey(fromId)) {
                        catalog.sessionRows - fromId + (targetId to canonical)
                    } else catalog.sessionRows,
                )
                if (next == catalog) catalog else next
            }
            if (sessions == current.sessions && transcripts == current.transcripts &&
                rehomes == current.rehomes && snapshots == current.projectSnapshots
            ) {
                current
            } else {
                current.copy(sessions = sessions, transcripts = transcripts, rehomes = rehomes, projectSnapshots = snapshots)
            }
        }
    }

    fun session(id: String): SessionSummary? = _state.value.sessions[id]

    fun transcript(id: String): List<TranscriptEntry> = _state.value.transcripts[id].orEmpty()
}
