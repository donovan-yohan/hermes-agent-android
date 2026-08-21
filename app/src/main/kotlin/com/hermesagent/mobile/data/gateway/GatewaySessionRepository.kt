package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.session.ReasoningActivity
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionProgress
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.data.ssh.redact
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject

interface GatewaySessionRepository {
    val connectionState: StateFlow<GatewayConnectionState>
    val sessionRehomes: Flow<SessionRehome> get() = emptyFlow()
    suspend fun refreshSessions()
    suspend fun refreshProjects() = Unit
    suspend fun openProject(projectId: String) = Unit
    suspend fun createProject(name: String, folderPath: String): ProjectCreateOutcome =
        error("Project creation is not implemented by this repository.")
    suspend fun openSession(durableId: String): String
    suspend fun createSession(workspacePath: String? = null): String
    suspend fun submit(durableId: String, text: String): GatewaySubmitOutcome
    suspend fun interrupt(durableId: String)
}

sealed interface GatewaySubmitOutcome {
    data object Accepted : GatewaySubmitOutcome
    data object Ambiguous : GatewaySubmitOutcome
}

data class ProjectCreateOutcome(
    val projectId: String,
    val catalogRefreshed: Boolean,
)

data class SessionRehome(
    val oldDurableId: String,
    val newDurableId: String,
)

/** Explicit, connection-scoped durable ↔ runtime identity. */
internal class SessionIdentityMap {
    private val durableToRuntime = mutableMapOf<String, String>()
    private val runtimeToDurable = mutableMapOf<String, String>()

    @Synchronized
    fun bind(durableId: String, runtimeId: String) {
        require(durableId.isNotBlank() && runtimeId.isNotBlank())
        durableToRuntime.put(durableId, runtimeId)?.let(runtimeToDurable::remove)
        runtimeToDurable.put(runtimeId, durableId)?.let(durableToRuntime::remove)
    }

    @Synchronized fun runtimeFor(durableId: String): String? = durableToRuntime[durableId]
    @Synchronized fun durableFor(runtimeId: String): String? = runtimeToDurable[runtimeId]

    @Synchronized
    fun unbindRuntime(runtimeId: String): String? = runtimeToDurable.remove(runtimeId)?.also {
        durableToRuntime.remove(it)
    }

    @Synchronized
    fun clear() {
        durableToRuntime.clear()
        runtimeToDurable.clear()
    }
}

internal class LiveGatewaySessionRepository(
    private val cache: SessionCache,
    private val connectionStateFlow: StateFlow<GatewayConnectionState>,
    private val clientFlow: StateFlow<GatewayRpcClient?>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : GatewaySessionRepository {
    constructor(
        cache: SessionCache,
        connection: GatewayConnectionManager,
        scope: CoroutineScope,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(cache, connection.state, connection.client, scope, clock)

    override val connectionState: StateFlow<GatewayConnectionState> = connectionStateFlow
    private val rehomeEvents = MutableSharedFlow<SessionRehome>(extraBufferCapacity = 8)
    override val sessionRehomes: Flow<SessionRehome> = rehomeEvents

    private val identities = SessionIdentityMap()
    private val sequence = AtomicLong()
    private val stateLock = Any()
    /** Serializes multi-RPC navigation sequences without blocking event routing. */
    private val navigationMutex = Mutex()
    private val refreshMutex = Mutex()
    /** Serializes catalog and detail snapshots so stale details cannot resurrect a removed project. */
    private val projectMutex = Mutex()
    private val assistantByRuntime = mutableMapOf<String, AssistantTurn>()
    private val reasoningByRuntime = mutableMapOf<String, ReasoningActivity>()
    private val toolsByRuntime = mutableMapOf<String, MutableMap<String, ToolActivity>>()
    private val optimisticUserByRuntime = mutableMapOf<String, UserTurn>()
    private val progressRuntimeIds = mutableSetOf<String>()
    /** Per-connection ordering fences for live state and progress hydration. */
    private val runtimeEventRevisions = mutableMapOf<String, RuntimeEventRevision>()
    private val activeRuntimeIds = linkedSetOf<String>()
    private val reconnectDurableIds = mutableSetOf<String>()
    private val ephemeralSessions = mutableSetOf<String>()
    /** The active drill-in worth rehydrating after a catalog refresh or reconnect. */
    private var lastHydratedProjectId: String? = null
    private var eventJob: Job? = null
    private var bootstrapRefreshJob: Job? = null
    private var connectionGeneration = 0L
    private var metadataRefreshRunning = false
    private var metadataRefreshPending = false
    private var observedClient: GatewayRpcClient? = null
    /** Runtime selected to own identifier-less events; local submits stay pinned while active. */
    private var unscopedRuntimeId: String? = null
    private var localSubmitStartedAtMillis: Long? = null
    private var unscopedTurnIsLive = false

    init {
        scope.launch {
            clientFlow.collect { next ->
                eventJob?.cancel()
                bootstrapRefreshJob?.cancel()
                val reset = synchronized(stateLock) {
                    val previous = observedClient
                    observedClient = next
                    connectionGeneration++
                    if (previous != null && previous !== next) {
                        connectionScopedRuntimeIds().forEach { runtimeId ->
                            identities.durableFor(runtimeId)?.let { durableId ->
                                settleConnectionLoss(durableId, runtimeId)
                                reconnectDurableIds += durableId
                            }
                        }
                    }
                    identities.clear()
                    assistantByRuntime.clear()
                    reasoningByRuntime.clear()
                    toolsByRuntime.clear()
                    optimisticUserByRuntime.clear()
                    progressRuntimeIds.clear()
                    runtimeEventRevisions.clear()
                    activeRuntimeIds.clear()
                    clearUnscopedRuntime()
                    val ghosts = if (next == null) emptyList() else ephemeralSessions.toList()
                    if (next != null) ephemeralSessions.clear()
                    ConnectionReset(
                        generation = connectionGeneration,
                        ephemeralDurableIds = ghosts,
                        reconnectDurableIds = reconnectDurableIds.toList(),
                        clearProjects = previous !== next,
                    )
                }
                // A just-created session is persisted lazily on first submit.
                // Keep it useful while disconnected, then let the next
                // authoritative list decide whether it really exists.
                reset.ephemeralDurableIds.forEach(cache::removeSession)
                if (reset.clearProjects) cache.clearProjects()
                if (next != null) {
                    eventJob = scope.launch {
                        next.events.collect { event ->
                            val refreshMetadata = synchronized(stateLock) {
                                if (reset.generation != connectionGeneration || clientFlow.value !== next) {
                                    false
                                } else {
                                    applyEvent(event)
                                }
                            }
                            if (refreshMetadata) scheduleMetadataRefresh()
                        }
                    }
                    bootstrapRefreshJob = scope.launch {
                        runCatching { refreshSessions() }
                        runCatching { refreshProjects() }
                        reset.reconnectDurableIds.forEach { durableId ->
                            runCatching { openSession(durableId) }
                                .onFailure { failure ->
                                    if (failure is CancellationException) throw failure
                                    synchronized(stateLock) {
                                        if (reset.generation == connectionGeneration && clientFlow.value === next) {
                                            settleReconciliationFailure(durableId)
                                        }
                                    }
                                }
                        }
                    }
                }
            }
        }
    }

    override suspend fun refreshSessions() = refreshMutex.withLock {
        val connection = connectionSnapshot()
        val result = connection.client.request(
            "session.list",
            buildJsonObject {
                put("limit", JsonPrimitive(100))
                put("include_hidden", JsonPrimitive(false))
            },
        )
        val rows = parseSessionList(result, clock())
        synchronized(stateLock) {
            ensureCurrent(connection)
            cache.upsertSessions(
                rows.map { row ->
                    cache.session(row.id)?.let { existing ->
                        row.copy(
                            status = existing.status,
                            progress = existing.progress,
                            activityStartedAtMillis = existing.activityStartedAtMillis,
                        )
                    } ?: row
                },
            )
        }
    }

    override suspend fun refreshProjects() {
        val rehydrate = projectMutex.withLock {
            val connection = connectionSnapshot()
            val payload = try {
                connection.client.request(
                    "projects.tree",
                    buildJsonObject { put("preview_limit", JsonPrimitive(PROJECT_PREVIEW_LIMIT)) },
                )
            } catch (failure: Throwable) {
                if (failure.isMissingProjectsMethod()) {
                    synchronized(stateLock) {
                        ensureCurrent(connection)
                        cache.markProjectsUnavailable()
                    }
                    return@withLock null
                }
                throw failure
            }
            val overview = parseProjectOverview(payload, clock())
            synchronized(stateLock) {
                ensureCurrent(connection)
                cache.replaceProjectOverview(overview.projects, overview.activeProjectId)
                lastHydratedProjectId?.takeIf { projectId ->
                    overview.projects.any { it.id == projectId }
                }.also { lastHydratedProjectId = it }
            }
        }
        rehydrate?.let { projectId ->
            runCatching { openProject(projectId) }
                .onFailure { failure -> if (failure is CancellationException) throw failure }
        }
    }

    override suspend fun openProject(projectId: String) = projectMutex.withLock {
        require(projectId.isNotBlank())
        val connection = connectionSnapshot()
        synchronized(stateLock) {
            ensureCurrent(connection)
            if (cache.state.value.projects.available == true &&
                projectId !in cache.state.value.projects.projects
            ) {
                throw GatewayRpcException("This project is no longer available.")
            }
        }
        val result = connection.client.request(
            "projects.project_sessions",
            buildJsonObject { put("project_id", JsonPrimitive(projectId)) },
        )
        val details = parseProjectDetails(result, clock())
        synchronized(stateLock) {
            ensureCurrent(connection)
            lastHydratedProjectId = projectId
            cache.replaceProjectDetails(details.project, details.sessions)
        }
    }

    override suspend fun createProject(name: String, folderPath: String): ProjectCreateOutcome {
        val cleanName = name.trim()
        val cleanPath = folderPath.trim()
        require(cleanName.isNotEmpty())
        require(cleanPath.isNotEmpty())
        val projectId = projectMutex.withLock {
            val connection = connectionSnapshot()
            val result = connection.client.request(
                "projects.create",
                buildJsonObject {
                    put("name", JsonPrimitive(cleanName))
                    put("folders", JsonArray(listOf(JsonPrimitive(cleanPath))))
                    put("primary_path", JsonPrimitive(cleanPath))
                    put("use", JsonPrimitive(true))
                },
            ).asObject("projects.create")
            synchronized(stateLock) { ensureCurrent(connection) }
            val project = result["project"] as? JsonObject
                ?: throw GatewayRpcException("Hermes did not return the created project.")
            project.string("id")?.takeIf(String::isNotBlank)
                ?: throw GatewayRpcException("Hermes did not return a project id.")
        }
        // Re-read backend truth instead of teaching this write path a second
        // project-tree parser. Creation has already succeeded at this point, so
        // a refresh failure must not tell callers to retry the write.
        val catalogRefreshed = try {
            refreshProjects()
            true
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            false
        }
        return ProjectCreateOutcome(projectId, catalogRefreshed)
    }

    override suspend fun openSession(durableId: String): String = navigationMutex.withLock {
        val connection = connectionSnapshot()
        val knownRuntime = synchronized(stateLock) { identities.runtimeFor(durableId) }
        val liveSnapshot: JsonObject
        val snapshotRevision: RuntimeEventRevision
        val runtimeId: String
        val canonicalId: String
        if (knownRuntime != null) {
            snapshotRevision = synchronized(stateLock) {
                ensureCurrent(connection)
                runtimeEventRevision(knownRuntime)
            }
            liveSnapshot = connection.client.request("session.activate", objectParams("session_id", knownRuntime))
                .asObject("session.activate")
            synchronized(stateLock) { ensureCurrent(connection) }
            runtimeId = knownRuntime
            canonicalId = synchronized(stateLock) { identities.durableFor(runtimeId) } ?: durableId
        } else {
            liveSnapshot = connection.client.request("session.resume", objectParams("session_id", durableId))
                .asObject("session.resume")
            runtimeId = liveSnapshot.string("session_id")
                ?: throw GatewayRpcException("Hermes did not return a runtime session id.")
            canonicalId = liveSnapshot.canonicalDurableId() ?: durableId
            snapshotRevision = synchronized(stateLock) {
                ensureCurrent(connection)
                identities.bind(canonicalId, runtimeId)
                runtimeEventRevision(runtimeId)
            }
        }

        val historyResult = connection.client.request("session.history", objectParams("session_id", runtimeId))
        val history = parseHistory(historyResult, runtimeId, clock())
        synchronized(stateLock) {
            ensureCurrent(connection)
            val currentRevision = runtimeEventRevision(runtimeId)
            val liveSnapshotIsCurrent = currentRevision.live == snapshotRevision.live
            val progressSnapshotIsCurrent = currentRevision.progress == snapshotRevision.progress
            val projection = if (liveSnapshotIsCurrent) {
                parseLiveSessionProjection(liveSnapshot, clock())
            } else {
                EMPTY_LIVE_SESSION_PROJECTION
            }
            val localLive = connectionScopedInflight(runtimeId)
            val reconciled = reconcileAuthoritativeTranscript(
                history,
                runtimeId,
                projection,
                localLive,
            )
            val priorStatus = cache.session(canonicalId)?.status ?: cache.session(durableId)?.status ?: SessionStatus.Idle
            val status = reconcileLiveState(runtimeId, projection, localLive.isNotEmpty(), reconciled, priorStatus)
            if (progressSnapshotIsCurrent) progressRuntimeIds.remove(runtimeId)
            val row = canonicalSummary(
                durableId,
                canonicalId,
                liveSnapshot,
                status,
                liveSnapshotIsCurrent,
                preserveProgress = !progressSnapshotIsCurrent,
            )
            cache.rehomeSession(durableId, row, reconciled)
            reconnectDurableIds.remove(durableId)
            reconnectDurableIds.remove(canonicalId)
            if (canonicalId != durableId) {
                rehomeEvents.tryEmit(SessionRehome(durableId, canonicalId))
            }
        }
        canonicalId
    }

    override suspend fun createSession(workspacePath: String?): String = navigationMutex.withLock {
        val connection = connectionSnapshot()
        val result = connection.client.request(
            "session.create",
            buildJsonObject {
                put("source", JsonPrimitive("desktop"))
                workspacePath?.trim()?.takeIf(String::isNotEmpty)?.let { put("cwd", JsonPrimitive(it)) }
            },
        ).asObject("session.create")
        val runtimeId = result.string("session_id")
            ?: throw GatewayRpcException("Hermes did not return a runtime session id.")
        val durableId = result.string("stored_session_id")
            ?: throw GatewayRpcException("Hermes did not return a durable session id.")
        val info = (result["session"] as? JsonObject) ?: (result["info"] as? JsonObject) ?: result
        synchronized(stateLock) {
            ensureCurrent(connection)
            identities.bind(durableId, runtimeId)
            ephemeralSessions += durableId
            cache.upsertSession(parseSession(info, clock(), durableId))
            val messages = result["messages"]
            if (messages is JsonArray) cache.setTranscript(durableId, parseMessages(messages, runtimeId, clock()))
        }
        durableId
    }

    override suspend fun submit(durableId: String, text: String): GatewaySubmitOutcome {
        val prompt = text.trim()
        require(prompt.isNotEmpty())
        val binding = ensureRuntime(durableId)
        val connection = connectionSnapshot()
        val optimistic = synchronized(stateLock) {
            ensureCurrent(connection)
            if (unscopedRuntimeId != null || activeRuntimeIds.isNotEmpty()) {
                throw GatewayRpcException("Wait for the current turn to finish before sending another message.")
            }
            val currentRuntime = identities.runtimeFor(binding.durableId)
            if (currentRuntime != binding.runtimeId) {
                throw GatewayRpcException("Hermes did not activate this session.")
            }
            unscopedRuntimeId = binding.runtimeId
            activeRuntimeIds += binding.runtimeId
            val now = clock()
            localSubmitStartedAtMillis = now
            unscopedTurnIsLive = false
            val previousSession = cache.session(binding.durableId)
            val previousTranscript = cache.transcript(binding.durableId)
            val optimisticUser = UserTurn("local-user-${sequence.incrementAndGet()}", prompt, now)
            optimisticUserByRuntime[binding.runtimeId] = optimisticUser
            cache.appendEntry(binding.durableId, optimisticUser)
            clearProgress(binding.durableId, binding.runtimeId)
            previousSession?.let { session ->
                cache.upsertSession(
                    session.copy(
                        preview = prompt,
                        lastActiveAtMillis = now,
                        status = SessionStatus.Working,
                        activityStartedAtMillis = now,
                        messageCount = session.messageCount + 1,
                    ),
                )
            }
            OptimisticSubmit(previousSession, previousTranscript)
        }

        try {
            connection.client.request(
                "prompt.submit",
                buildJsonObject {
                    put("session_id", JsonPrimitive(binding.runtimeId))
                    put("text", JsonPrimitive(prompt))
                },
            )
            synchronized(stateLock) { ephemeralSessions.remove(binding.durableId) }
            return GatewaySubmitOutcome.Accepted
        } catch (failure: Throwable) {
            val ambiguous = failure is CancellationException ||
                (failure is GatewayRpcException && failure.requestMayHaveBeenAccepted)
            synchronized(stateLock) {
                val canRollback = unscopedRuntimeId == binding.runtimeId && !unscopedTurnIsLive && !ambiguous
                if (canRollback) {
                    releaseRuntimeGuard(binding.runtimeId)
                    optimisticUserByRuntime.remove(binding.runtimeId)
                    cache.setTranscript(binding.durableId, optimistic.transcript)
                    optimistic.session?.let(cache::upsertSession)
                }
            }
            if (ambiguous) {
                // An RPC-local timeout/cancellation leaves the caller active and
                // is an ambiguous acknowledgement. Parent cancellation makes
                // this context inactive and must continue propagating.
                currentCoroutineContext().ensureActive()
                return GatewaySubmitOutcome.Ambiguous
            }
            throw failure
        }
    }

    override suspend fun interrupt(durableId: String) {
        val runtimeId = synchronized(stateLock) { identities.runtimeFor(durableId) }
            ?: throw GatewayRpcException("Reopen this session before stopping Hermes.")
        connectionSnapshot().client.request("session.interrupt", objectParams("session_id", runtimeId))
    }

    private suspend fun ensureRuntime(durableId: String): SessionBinding {
        synchronized(stateLock) {
            identities.runtimeFor(durableId)?.let { return SessionBinding(durableId, it) }
        }
        val canonicalId = openSession(durableId)
        return synchronized(stateLock) {
            SessionBinding(
                canonicalId,
                identities.runtimeFor(canonicalId)
                    ?: throw GatewayRpcException("Hermes did not activate this session."),
            )
        }
    }

    /** Returns true when authoritative list metadata should be refreshed. */
    private fun applyEvent(event: GatewayEvent): Boolean {
        val payload = event.payload as? JsonObject ?: JsonObject(emptyMap())
        if (event.type == "session.reclaimed") {
            val reclaimedRuntime = payload.string("session_id")?.takeIf(String::isNotBlank) ?: return true
            val mappedDurableId = identities.durableFor(reclaimedRuntime)
            if (mappedDurableId != null) {
                advanceLiveEventRevision(reclaimedRuntime)
                val durableId = payload.string("stored_session_id")
                    ?.takeIf(String::isNotBlank)
                    ?.let { rehomeDurableSession(mappedDurableId, it, reclaimedRuntime) }
                    ?: mappedDurableId
                settleStoppedRuntime(durableId, reclaimedRuntime)
                identities.unbindRuntime(reclaimedRuntime)
            }
            return true
        }

        val runtimeId = event.runtimeSessionId ?: unscopedRuntimeId ?: return false
        var durableId = identities.durableFor(runtimeId) ?: return false
        if (event.type in LIVE_RUNTIME_EVENT_TYPES) advanceLiveEventRevision(runtimeId)
        return when (event.type) {
            "gateway.ready" -> false
            "session.info" -> {
                val eventDurable = payload.string("stored_session_id")
                    ?: payload.string("session_key")
                    ?: payload.string("durable_id")
                val canonicalId = eventDurable?.takeIf(String::isNotBlank) ?: durableId
                val rehomed = canonicalId != durableId
                if (rehomed) durableId = rehomeDurableSession(durableId, canonicalId, runtimeId)
                val running = payload.boolean("running")
                if (running == true) {
                    markRuntimeLive(runtimeId)
                    ephemeralSessions.remove(durableId)
                }
                reconcileSessionInfo(durableId, runtimeId, running)
                val settled = running == false && settleStoppedSessionInfo(durableId, runtimeId)
                if (running == false && !settled && unscopedRuntimeId != runtimeId) {
                    releaseRuntimeGuard(runtimeId)
                }
                rehomed || settled
            }

            "message.start" -> {
                if ((payload.string("role") ?: "assistant") == "assistant") {
                    val turn = AssistantTurn(
                        id = payload.messageId() ?: "gateway-assistant-${sequence.incrementAndGet()}",
                        markdown = payload.contentText(),
                        atMillis = payload.timestamp(clock()),
                        streaming = true,
                    )
                    assistantByRuntime[runtimeId] = turn
                    cache.putEntry(durableId, turn)
                    clearProgress(durableId, runtimeId)
                    markRuntimeLive(runtimeId)
                    ephemeralSessions.remove(durableId)
                    setStatus(durableId, SessionStatus.Working)
                }
                false
            }

            "message.delta" -> {
                val current = assistantByRuntime[runtimeId] ?: AssistantTurn(
                    id = payload.messageId() ?: "gateway-assistant-${sequence.incrementAndGet()}",
                    markdown = "",
                    atMillis = clock(),
                    streaming = true,
                )
                val updated = current.copy(markdown = current.markdown + payload.deltaText(), streaming = true)
                assistantByRuntime[runtimeId] = updated
                cache.putEntry(durableId, updated)
                markRuntimeLive(runtimeId)
                ephemeralSessions.remove(durableId)
                setStatus(durableId, SessionStatus.Working)
                false
            }

            "message.complete" -> {
                completeMessage(durableId, runtimeId, payload)
                true
            }

            "reasoning.delta", "reasoning.available" -> {
                applyReasoning(event.type, durableId, runtimeId, payload)
                markRuntimeLive(runtimeId)
                ephemeralSessions.remove(durableId)
                setStatus(durableId, SessionStatus.Working)
                false
            }

            "thinking.delta" -> {
                applyStatusUpdate(
                    durableId,
                    runtimeId,
                    buildJsonObject {
                        put("kind", JsonPrimitive("thinking"))
                        put("text", JsonPrimitive(payload.deltaText()))
                    },
                )
                markRuntimeLive(runtimeId)
                ephemeralSessions.remove(durableId)
                setStatus(durableId, SessionStatus.Working)
                false
            }

            "tool.start", "tool.progress", "tool.complete" -> {
                sealReasoning(durableId, runtimeId, ToolState.Done)
                applyTool(event.type, durableId, runtimeId, payload)
                markRuntimeLive(runtimeId)
                ephemeralSessions.remove(durableId)
                setStatus(durableId, SessionStatus.Working)
                false
            }

            "status.update" -> {
                applyStatusUpdate(durableId, runtimeId, payload)
                false
            }

            "error" -> {
                val current = assistantByRuntime.remove(runtimeId)
                val errorText = safeGatewayTerminalError(payload.string("error") ?: payload.string("message"))
                val failed = (current ?: AssistantTurn(
                    id = "gateway-error-${sequence.incrementAndGet()}",
                    markdown = "",
                    atMillis = payload.timestamp(clock()),
                )).copy(streaming = false, error = errorText)
                cache.putEntry(durableId, failed)
                sealReasoning(durableId, runtimeId, ToolState.Failed)
                sealTools(durableId, runtimeId, ToolState.Failed)
                optimisticUserByRuntime.remove(runtimeId)
                clearProgress(durableId, runtimeId)
                setStatus(durableId, SessionStatus.Idle)
                ephemeralSessions.remove(durableId)
                releaseRuntimeGuard(runtimeId)
                true
            }

            else -> false
        }
    }

    /**
     * A pinned Desktop `session.info running=false` settles a turn when its
     * completion event was missed. An optimistic submit needs a bounded grace
     * first so the previous idle heartbeat cannot re-open the send guard before
     * the backend reports the new turn live.
     *
     * Source: NousResearch/hermes-agent @ f82f2dbabd9e66b714f2b4f8a40447fe0c13e732,
     * apps/desktop/src/app/session/hooks/use-message-stream/gateway-event.ts:663-724.
     */
    private fun settleStoppedSessionInfo(durableId: String, runtimeId: String): Boolean {
        if (unscopedRuntimeId != runtimeId) return false
        val submittedAt = localSubmitStartedAtMillis
        val remainingGrace = submittedAt?.let {
            (PRE_START_FALSE_SETTLE_GRACE_MILLIS - (clock() - it)).coerceAtLeast(0)
        } ?: 0
        if (!unscopedTurnIsLive && remainingGrace > 0) {
            return false
        }
        settleStoppedRuntime(durableId, runtimeId)
        return true
    }

    private fun markRuntimeLive(runtimeId: String) {
        activeRuntimeIds += runtimeId
        if (unscopedRuntimeId == runtimeId) {
            unscopedTurnIsLive = true
        }
    }

    /** Session-info heartbeats contain state, not a complete session row. */
    private fun reconcileSessionInfo(durableId: String, runtimeId: String, running: Boolean?) {
        val existing = cache.session(durableId) ?: return
        val status = when (running) {
            true -> SessionStatus.Working
            false -> if (unscopedRuntimeId == runtimeId) existing.status else SessionStatus.Idle
            null -> existing.status
        }
        if (status != existing.status) {
            cache.upsertSession(
                existing.copy(
                    status = status,
                    activityStartedAtMillis = if (status == SessionStatus.Working) {
                        existing.activityStartedAtMillis ?: clock()
                    } else {
                        null
                    },
                ),
            )
        }
    }

    private fun settleStoppedRuntime(durableId: String, runtimeId: String) {
        assistantByRuntime.remove(runtimeId)?.let { partial ->
            cache.putEntry(durableId, partial.copy(streaming = false, stopped = true))
        }
        sealReasoning(durableId, runtimeId, ToolState.Stopped)
        sealTools(durableId, runtimeId, ToolState.Stopped)
        optimisticUserByRuntime.remove(runtimeId)
        clearProgress(durableId, runtimeId)
        setStatus(durableId, SessionStatus.Idle)
        releaseRuntimeGuard(runtimeId)
    }

    private fun clearUnscopedRuntime() {
        unscopedRuntimeId = null
        localSubmitStartedAtMillis = null
        unscopedTurnIsLive = false
    }

    private fun releaseRuntimeGuard(runtimeId: String) {
        activeRuntimeIds.remove(runtimeId)
        if (unscopedRuntimeId == runtimeId) clearUnscopedRuntime()
    }

    private fun completeMessage(durableId: String, runtimeId: String, payload: JsonObject) {
        val current = assistantByRuntime.remove(runtimeId)
        val finalText = payload.contentText()
        val status = payload.string("status")?.lowercase()
        val interrupted = status == "interrupted" || payload.boolean("interrupted") == true
        val errorText = if (status == "error") {
            safeGatewayTerminalError(
                payload.string("error") ?: payload.string("message") ?: finalText,
            )
        } else {
            null
        }
        val keepFailedPartial = errorText != null && payload.boolean("partial") == true && current != null
        val completed = (current ?: AssistantTurn(
            id = payload.messageId() ?: "gateway-assistant-${sequence.incrementAndGet()}",
            markdown = finalText,
            atMillis = payload.timestamp(clock()),
        )).copy(
            markdown = when {
                errorText != null -> if (keepFailedPartial) current.markdown else ""
                finalText.isNotBlank() -> finalText
                else -> current?.markdown.orEmpty()
            },
            streaming = false,
            error = errorText,
            stopped = interrupted,
        )
        cache.putEntry(durableId, completed)
        sealReasoning(durableId, runtimeId, if (errorText != null) ToolState.Failed else ToolState.Done)
        sealTools(
            durableId,
            runtimeId,
            when {
                errorText != null -> ToolState.Failed
                interrupted -> ToolState.Stopped
                else -> ToolState.Done
            },
        )
        optimisticUserByRuntime.remove(runtimeId)
        clearProgress(durableId, runtimeId)
        setStatus(durableId, SessionStatus.Idle)
        ephemeralSessions.remove(durableId)
        releaseRuntimeGuard(runtimeId)
    }

    private fun applyTool(type: String, durableId: String, runtimeId: String, payload: JsonObject) {
        val tools = toolsByRuntime.getOrPut(runtimeId, ::mutableMapOf)
        val explicitId = payload.string("tool_id") ?: payload.string("tool_call_id") ?: payload.string("id")
        val id = explicitId ?: tools.keys.singleOrNull() ?: "gateway-tool-${sequence.incrementAndGet()}"
        val previous = tools[id]
        val startedAt = previous?.startedAtMillis ?: clock()
        val elapsed = payload.primitive("duration_s")?.toDoubleOrNull()
            ?: payload.primitive("elapsed_seconds")?.toDoubleOrNull()
            ?: if (type == "tool.complete") (clock() - startedAt).coerceAtLeast(0) / 1_000.0 else previous?.elapsedSeconds
            ?: 0.0
        val toolName = payload.string("name").safeToolLabel(previous?.toolName ?: "Tool")
        val label = (payload.string("label") ?: payload.string("name"))
            .safeToolLabel(previous?.label ?: "Tool")
        val activity = ToolActivity(
            id = id,
            label = label,
            detail = payload.toolDetail(type).ifBlank { previous?.detail.orEmpty() },
            state = when (type) {
                "tool.complete" -> if (payload.toolFailed()) ToolState.Failed else ToolState.Done
                else -> ToolState.Running
            },
            elapsedSeconds = elapsed,
            toolName = toolName,
            argsText = payload.toolInputText() ?: previous?.argsText,
            resultText = payload["result"].safePayloadText() ?: previous?.resultText,
            inlineDiff = payload.jsonString("inline_diff")?.safePayloadText() ?: previous?.inlineDiff,
            startedAtMillis = startedAt,
        )
        cache.putEntry(durableId, activity)
        // Running snapshots are text-only, so completed structure stays here
        // until terminal history or connection settlement replaces it.
        tools[id] = activity
    }

    private fun applyReasoning(type: String, durableId: String, runtimeId: String, payload: JsonObject) {
        val previous = reasoningByRuntime[runtimeId]
        val now = clock()
        val startedAt = previous?.startedAtMillis ?: now
        val complete = type == "reasoning.available"
        val incoming = when (type) {
            "reasoning.delta", "thinking.delta" -> payload.deltaText()
            else -> payload.string("text") ?: payload.contentText()
        }.safePayloadText().orEmpty()
        if (incoming.isBlank() && previous == null) return
        val activity = ReasoningActivity(
            id = previous?.id ?: "gateway-reasoning-${sequence.incrementAndGet()}",
            text = when {
                complete && incoming.isNotBlank() -> incoming
                else -> previous?.text.orEmpty() + incoming
            },
            state = if (complete) ToolState.Done else ToolState.Running,
            startedAtMillis = startedAt,
            elapsedSeconds = if (complete) (now - startedAt).coerceAtLeast(0) / 1_000.0 else 0.0,
        )
        cache.putEntry(durableId, activity)
        if (complete) reasoningByRuntime.remove(runtimeId) else reasoningByRuntime[runtimeId] = activity
    }

    private fun sealReasoning(durableId: String, runtimeId: String, state: ToolState) {
        reasoningByRuntime.remove(runtimeId)?.let { activity ->
            cache.putEntry(
                durableId,
                activity.copy(
                    state = state,
                    elapsedSeconds = (clock() - (activity.startedAtMillis ?: clock())).coerceAtLeast(0) / 1_000.0,
                ),
            )
        }
    }

    private fun sealTools(durableId: String, runtimeId: String, state: ToolState) {
        toolsByRuntime.remove(runtimeId).orEmpty().values.forEach { activity ->
            cache.putEntry(
                durableId,
                if (activity.state == ToolState.Running) {
                    activity.copy(
                        state = state,
                        elapsedSeconds = (clock() - (activity.startedAtMillis ?: clock())).coerceAtLeast(0) / 1_000.0,
                    )
                } else {
                    activity
                },
            )
        }
    }

    private fun setStatus(durableId: String, status: SessionStatus) {
        cache.session(durableId)?.let { existing ->
            val now = clock()
            cache.upsertSession(
                existing.copy(
                    status = status,
                    lastActiveAtMillis = now,
                    activityStartedAtMillis = when (status) {
                        SessionStatus.Working -> existing.activityStartedAtMillis ?: now
                        else -> null
                    },
                ),
            )
        }
    }

    private fun applyStatusUpdate(durableId: String, runtimeId: String, payload: JsonObject) {
        val kind = payload.jsonString("kind")?.trim()?.takeIf(String::isNotEmpty) ?: return
        val text = payload.jsonString("text")
            ?.let(::safeGatewayStatusText)
            ?.takeIf(String::isNotEmpty)
            ?: return
        cache.session(durableId)?.let { existing ->
            cache.upsertSession(existing.copy(progress = SessionProgress(kind.take(MAX_STATUS_KIND), text)))
            progressRuntimeIds += runtimeId
            advanceProgressEventRevision(runtimeId)
        }
    }

    private fun clearProgress(durableId: String, runtimeId: String) {
        progressRuntimeIds.remove(runtimeId)
        cache.session(durableId)?.takeIf { it.progress != null }?.let {
            cache.upsertSession(it.copy(progress = null))
        }
    }

    private fun connectionScopedRuntimeIds(): Set<String> = buildSet {
        unscopedRuntimeId?.let(::add)
        addAll(activeRuntimeIds)
        addAll(assistantByRuntime.keys)
        addAll(reasoningByRuntime.keys)
        addAll(toolsByRuntime.keys)
        addAll(optimisticUserByRuntime.keys)
        addAll(progressRuntimeIds)
    }

    private fun settleConnectionLoss(durableId: String, runtimeId: String) {
        assistantByRuntime[runtimeId]?.let { partial ->
            cache.putEntry(durableId, partial.copy(streaming = false))
        }
        sealReasoning(durableId, runtimeId, ToolState.Stopped)
        sealTools(durableId, runtimeId, ToolState.Stopped)
        clearProgress(durableId, runtimeId)
        cache.session(durableId)?.let { existing ->
            cache.upsertSession(
                existing.copy(
                    status = SessionStatus.Stalled,
                    lastActiveAtMillis = clock(),
                    activityStartedAtMillis = null,
                ),
            )
        }
    }

    private fun settleReconciliationFailure(durableId: String) {
        identities.runtimeFor(durableId)?.let { runtimeId ->
            releaseRuntimeGuard(runtimeId)
            identities.unbindRuntime(runtimeId)
        }
        cache.session(durableId)?.let { existing ->
            cache.upsertSession(
                existing.copy(
                    status = SessionStatus.Idle,
                    progress = SessionProgress(RECONCILIATION_FAILED_KIND, RECONCILIATION_FAILED_TEXT),
                ),
            )
        }
    }

    private fun connectionScopedInflight(runtimeId: String): List<TranscriptEntry> = buildList {
        optimisticUserByRuntime[runtimeId]?.let(::add)
        assistantByRuntime[runtimeId]?.let(::add)
        reasoningByRuntime[runtimeId]?.let(::add)
        addAll(toolsByRuntime[runtimeId].orEmpty().values)
    }

    /**
     * Persisted history replaces every completed optimistic/live row. Only the
     * Gateway's `inflight` projection, plus events that raced ahead of that
     * snapshot on this same connection, may extend it.
     *
     * Source: NousResearch/hermes-agent @ f82f2dbabd9e66b714f2b4f8a40447fe0c13e732,
     * apps/desktop/src/app/session/hooks/use-session-actions/utils.ts:699-924 and
     * tui_gateway/server.py:8813-8874.
     */
    private fun reconcileAuthoritativeTranscript(
        history: List<TranscriptEntry>,
        runtimeId: String,
        projection: LiveSessionProjection,
        localLive: List<TranscriptEntry>,
    ): List<TranscriptEntry> {
        var reconciled = appendInflightProjection(history, runtimeId, projection, clock())
        if (projection.retainedFailure) return reconciled
        // `session.activate`/`session.resume` snapshots `running` and inflight
        // under the same upstream history lock. A reported terminal turn is
        // authoritative over any local stream that raced with the request.
        if (projection.running == false) return reconciled

        val localIsLive = runtimeId in activeRuntimeIds || localLive.any {
            (it is AssistantTurn && it.streaming) ||
                (it is ReasoningActivity && it.state == ToolState.Running) ||
                (it is ToolActivity && it.state == ToolState.Running)
        }
        if (!localIsLive) return reconciled

        localLive.filterIsInstance<UserTurn>().forEach { user ->
            if (!reconciled.openUserRunContains(user.text)) reconciled = reconciled + user
        }

        val localAssistant = localLive.filterIsInstance<AssistantTurn>().lastOrNull()
        val projectedAssistant = projection.inflight?.assistant.orEmpty()
        val localSupersedesProjection = projection.inflight?.corrections.isNullOrEmpty() &&
            localAssistant != null &&
            (projectedAssistant.isBlank() ||
                (localAssistant.markdown.startsWith(projectedAssistant) &&
                    localAssistant.markdown.length > projectedAssistant.length))
        if (localAssistant != null && (projection.inflight == null || localSupersedesProjection)) {
            if (localSupersedesProjection) {
                reconciled = reconciled.filterNot { it.id.startsWith("inflight-assistant-") }
            }
            reconciled = reconciled.replaceOrAppend(localAssistant)
        }

        localLive.filterIsInstance<ToolActivity>().forEach { tool ->
            reconciled = reconciled.replaceOrAppend(tool)
        }
        localLive.filterIsInstance<ReasoningActivity>().forEach { reasoning ->
            reconciled = reconciled.replaceOrAppend(reasoning)
        }
        return reconciled
    }

    private fun reconcileLiveState(
        runtimeId: String,
        projection: LiveSessionProjection,
        hasLocalLiveEntries: Boolean,
        reconciled: List<TranscriptEntry>,
        priorStatus: SessionStatus,
    ): SessionStatus {
        val localBusy = projection.running != false && (
            runtimeId in activeRuntimeIds || hasLocalLiveEntries && reconciled.any {
                (it is AssistantTurn && it.streaming) ||
                    (it is ReasoningActivity && it.state == ToolState.Running) ||
                    (it is ToolActivity && it.state == ToolState.Running)
            }
        )
        val busy = !projection.retainedFailure && (projection.busy || localBusy)

        if (busy) {
            if (unscopedRuntimeId == null && activeRuntimeIds.isEmpty()) {
                // A locally requested resume/activate snapshot is the only
                // non-submit path allowed to claim identifier-less events.
                // Scoped events alone must never retarget that pin after a
                // different turn has completed.
                unscopedRuntimeId = runtimeId
                localSubmitStartedAtMillis = null
                unscopedTurnIsLive = true
            }
            markRuntimeLive(runtimeId)
            projection.inflight?.user?.takeIf(String::isNotBlank)?.let { user ->
                optimisticUserByRuntime[runtimeId] = UserTurn(
                    id = "inflight-user-$runtimeId",
                    text = user,
                    atMillis = projection.inflight.atMillis,
                )
            }
            reconciled.filterIsInstance<AssistantTurn>().lastOrNull { it.streaming }?.let { assistant ->
                assistantByRuntime[runtimeId] = assistant
            }
            reconciled.filterIsInstance<ReasoningActivity>().lastOrNull { it.state == ToolState.Running }
                ?.let { reasoning -> reasoningByRuntime[runtimeId] = reasoning }
            return projection.status?.takeIf { it != SessionStatus.Idle } ?: SessionStatus.Working
        }

        if (projection.hasAuthoritativeState) {
            assistantByRuntime.remove(runtimeId)
            reasoningByRuntime.remove(runtimeId)
            toolsByRuntime.remove(runtimeId)
            optimisticUserByRuntime.remove(runtimeId)
            releaseRuntimeGuard(runtimeId)
            return projection.status ?: SessionStatus.Idle
        }
        return priorStatus
    }

    private fun rehomeDurableSession(fromId: String, targetId: String, runtimeId: String): String {
        if (fromId == targetId) return fromId
        val existing = cache.session(targetId) ?: cache.session(fromId)
        val entries = mergeHistoryWithLiveEntries(
            cache.transcript(fromId),
            cache.transcript(targetId),
        )
        val row = existing?.copy(id = targetId)
            ?: SessionSummary(targetId, "New session", "", clock())
        cache.rehomeSession(fromId, row, entries)
        if (ephemeralSessions.remove(fromId)) ephemeralSessions += targetId
        identities.bind(targetId, runtimeId)
        rehomeEvents.tryEmit(SessionRehome(fromId, targetId))
        return targetId
    }

    private fun connectionSnapshot(): ConnectionSnapshot {
        val client = clientFlow.value ?: throw GatewayRpcException("Connect to a Gateway first.")
        return synchronized(stateLock) {
            if (clientFlow.value !== client) throw GatewayRpcException("The gateway connection changed.")
            ConnectionSnapshot(client, connectionGeneration)
        }
    }

    private fun ensureCurrent(connection: ConnectionSnapshot) {
        if (connection.generation != connectionGeneration || clientFlow.value !== connection.client) {
            throw GatewayRpcException("The gateway connection changed.")
        }
    }

    private fun canonicalSummary(
        requestedId: String,
        canonicalId: String,
        snapshot: JsonObject,
        status: SessionStatus,
        snapshotIsCurrent: Boolean,
        preserveProgress: Boolean,
    ): SessionSummary {
        val existing = cache.session(canonicalId) ?: cache.session(requestedId)
        val activityStartedAtMillis = if (status == SessionStatus.Working) {
            snapshot.primitive("turn_started_at")?.epochMillisOrNull()
                ?: existing?.activityStartedAtMillis
                ?: clock()
        } else {
            null
        }
        if (!snapshotIsCurrent && existing != null) {
            return existing.copy(id = canonicalId, status = status, activityStartedAtMillis = activityStartedAtMillis)
        }
        val parsed = parseSession(snapshot, clock(), canonicalId)
        return existing?.copy(
            id = canonicalId,
            title = snapshot.string("title")?.ifBlank { existing.title } ?: existing.title,
            preview = snapshot.string("preview") ?: existing.preview,
            lastActiveAtMillis = if (snapshot.hasTimestamp()) parsed.lastActiveAtMillis else existing.lastActiveAtMillis,
            messageCount = snapshot.primitive("message_count")?.toIntOrNull() ?: existing.messageCount,
            source = snapshot.string("source") ?: existing.source,
            remoteProfile = snapshot.string("profile") ?: snapshot.string("profile_name") ?: existing.remoteProfile,
            status = status,
            progress = if (preserveProgress) existing.progress else null,
            activityStartedAtMillis = activityStartedAtMillis,
        ) ?: parsed.copy(status = status, activityStartedAtMillis = activityStartedAtMillis)
    }

    private fun runtimeEventRevision(runtimeId: String): RuntimeEventRevision =
        runtimeEventRevisions[runtimeId] ?: RuntimeEventRevision()

    private fun advanceLiveEventRevision(runtimeId: String) {
        val current = runtimeEventRevision(runtimeId)
        runtimeEventRevisions[runtimeId] = current.copy(live = current.live + 1)
    }

    private fun advanceProgressEventRevision(runtimeId: String) {
        val current = runtimeEventRevision(runtimeId)
        runtimeEventRevisions[runtimeId] = current.copy(progress = current.progress + 1)
    }

    /** Coalesce terminal pushes, but rerun once if another terminal edge lands mid-refresh. */
    private fun scheduleMetadataRefresh() {
        val launch = synchronized(stateLock) {
            metadataRefreshPending = true
            if (metadataRefreshRunning) false else {
                metadataRefreshRunning = true
                true
            }
        }
        if (!launch) return
        scope.launch {
            while (true) {
                val shouldRun = synchronized(stateLock) {
                    if (metadataRefreshPending) {
                        metadataRefreshPending = false
                        true
                    } else {
                        metadataRefreshRunning = false
                        false
                    }
                }
                if (!shouldRun) return@launch
                runCatching { refreshSessions() }
                if (cache.state.value.projects.available != false) runCatching { refreshProjects() }
            }
        }
    }

    private data class ConnectionSnapshot(val client: GatewayRpcClient, val generation: Long)
    private data class RuntimeEventRevision(val live: Long = 0, val progress: Long = 0)
    private data class ConnectionReset(
        val generation: Long,
        val ephemeralDurableIds: List<String>,
        val reconnectDurableIds: List<String>,
        val clearProjects: Boolean,
    )
    private data class SessionBinding(val durableId: String, val runtimeId: String)
    private data class OptimisticSubmit(
        val session: SessionSummary?,
        val transcript: List<TranscriptEntry>,
    )
}

internal fun safeGatewayTerminalError(raw: String?): String {
    val classified = redact(raw).take(MAX_GATEWAY_ERROR_CLASSIFICATION_CHARS).lowercase()
    return if (REMOTE_STORAGE_ERROR_MARKERS.any(classified::contains)) {
        "The remote host is out of storage. Free space there, then try again."
    } else {
        "Hermes ended this turn unexpectedly. Check the Gateway, then try again."
    }
}

internal fun safeGatewayStatusText(raw: String): String =
    redact(raw).replace(STATUS_WHITESPACE, " ").trim().take(MAX_STATUS_TEXT)

internal fun parseSessionList(result: JsonElement, nowMillis: Long): List<SessionSummary> {
    val root = result.asObject("session.list")
    val sessions = root["sessions"] as? JsonArray
        ?: throw GatewayRpcException("Hermes returned a malformed session list.")
    return sessions.map { element ->
        val session = element as? JsonObject
            ?: throw GatewayRpcException("Hermes returned a malformed session row.")
        parseSession(session, nowMillis)
    }
}

internal data class ProjectOverviewPayload(
    val projects: List<ProjectSummary>,
    val activeProjectId: String?,
)

internal data class ProjectDetailsPayload(
    val project: ProjectSummary,
    val sessions: List<SessionSummary>,
)

/** Parse only the backend-authored project tree; Android never infers membership from paths. */
internal fun parseProjectOverview(result: JsonElement, nowMillis: Long): ProjectOverviewPayload {
    val root = result.asObject("projects.tree")
    val projects = root["projects"] as? JsonArray
        ?: throw GatewayRpcException("Hermes returned a malformed project list.")
    return ProjectOverviewPayload(
        projects = projects.map { element ->
            parseProject(element as? JsonObject
                ?: throw GatewayRpcException("Hermes returned a malformed project row."), nowMillis)
        },
        activeProjectId = root.string("active_id"),
    )
}

internal fun parseProjectDetails(result: JsonElement, nowMillis: Long): ProjectDetailsPayload {
    val root = result.asObject("projects.project_sessions")
    val projectRoot = root["project"] as? JsonObject
        ?: throw GatewayRpcException("This project is no longer available.")
    val sessions = linkedMapOf<String, SessionSummary>()
    (projectRoot["repos"] as? JsonArray).orEmpty().forEach { repoElement ->
        val repo = repoElement as? JsonObject
            ?: throw GatewayRpcException("Hermes returned a malformed project repository.")
        (repo["groups"] as? JsonArray).orEmpty().forEach { groupElement ->
            val group = groupElement as? JsonObject
                ?: throw GatewayRpcException("Hermes returned a malformed project lane.")
            (group["sessions"] as? JsonArray).orEmpty().forEach { sessionElement ->
                val session = parseSession(
                    sessionElement as? JsonObject
                        ?: throw GatewayRpcException("Hermes returned a malformed project session."),
                    nowMillis,
                )
                sessions.putIfAbsent(session.id, session)
            }
        }
    }
    return ProjectDetailsPayload(parseProject(projectRoot, nowMillis), sessions.values.toList())
}

private fun parseProject(root: JsonObject, nowMillis: Long): ProjectSummary {
    val id = root.string("id")?.takeIf(String::isNotBlank)
        ?: throw GatewayRpcException("Hermes returned a project without an id.")
    val previews = (root["previewSessions"] as? JsonArray).orEmpty().map { element ->
        parseSession(
            element as? JsonObject
                ?: throw GatewayRpcException("Hermes returned a malformed project preview."),
            nowMillis,
        )
    }
    return ProjectSummary(
        id = id,
        label = root.string("label")?.ifBlank { id } ?: id,
        path = root.string("path")?.takeIf(String::isNotBlank),
        isAuto = root.boolean("isAuto") == true,
        isHome = root.boolean("isNoProject") == true,
        sessionCount = root.primitive("sessionCount")?.toIntOrNull() ?: previews.size,
        lastActiveAtMillis = root.primitive("lastActive")?.epochMillisOrNull() ?: 0,
        previewSessions = previews,
    )
}

internal fun parseHistory(result: JsonElement, runtimeId: String, nowMillis: Long): List<TranscriptEntry> {
    val root = result.asObject("session.history")
    val messages = root["messages"] as? JsonArray
        ?: throw GatewayRpcException("Hermes returned malformed session history.")
    return parseMessages(messages, runtimeId, nowMillis)
}

private fun parseMessages(messages: JsonArray, runtimeId: String, nowMillis: Long): List<TranscriptEntry> = buildList {
    messages.forEachIndexed { index, element ->
        val message = element as? JsonObject ?: return@forEachIndexed
        val id = message.messageId() ?: "$runtimeId-history-$index"
        val time = message.timestamp(nowMillis)
        when (message.string("role")) {
            "user" -> add(UserTurn(id, message.answerText(), time))
            "assistant" -> {
                val reasoning = message.reasoningText()
                reasoning.takeIf(String::isNotBlank)?.let {
                    add(
                        ReasoningActivity(
                            id = "$id-reasoning",
                            text = it.safePayloadText().orEmpty(),
                            state = ToolState.Done,
                            elapsedSeconds = message.durationSeconds(),
                        ),
                    )
                }
                val answer = message.answerText()
                if (answer.isNotBlank()) {
                    add(AssistantTurn(id, answer, time))
                }
            }

            "tool" -> {
                val name = message.string("name").safeToolLabel("Tool")
                add(
                    ToolActivity(
                        id = id,
                        label = name,
                        detail = (message.string("context") ?: message.contentText())
                            .safeDisplayText(MAX_TOOL_DETAIL)
                            .orEmpty(),
                        state = if (message.toolFailed()) ToolState.Failed else ToolState.Done,
                        elapsedSeconds = message.durationSeconds(),
                        toolName = name,
                        argsText = message.toolInputText(),
                        resultText = message["result"].safePayloadText() ?: message["content"].safePayloadText(),
                        inlineDiff = message.jsonString("inline_diff")?.safePayloadText(),
                    ),
                )
            }
        }
    }
}

internal fun parseSession(root: JsonObject, nowMillis: Long, authoritativeId: String? = null): SessionSummary {
    val id = authoritativeId ?: root.string("id")
        ?: throw GatewayRpcException("Hermes returned a session without a durable id.")
    return SessionSummary(
        id = id,
        title = root.string("title")?.ifBlank { "New session" } ?: "New session",
        preview = root.string("preview").orEmpty(),
        lastActiveAtMillis = root.timestamp(nowMillis),
        messageCount = root.primitive("message_count")?.toIntOrNull() ?: 0,
        source = root.string("source"),
        remoteProfile = root.string("profile") ?: root.string("profile_name"),
    )
}

private fun JsonElement.asObject(method: String): JsonObject = this as? JsonObject
    ?: throw GatewayRpcException("Hermes returned malformed data for $method.")

private fun Throwable.isMissingProjectsMethod(): Boolean =
    this is GatewayRpcError && (
        code == MISSING_RPC_METHOD_CODE ||
            message.contains("unknown method", ignoreCase = true) ||
            message.contains("method not found", ignoreCase = true)
        )

private fun objectParams(name: String, value: String): JsonObject =
    buildJsonObject { put(name, JsonPrimitive(value)) }

private fun JsonObject.messageId(): String? = string("row_id") ?: string("message_id") ?: string("id")

private fun JsonObject.jsonString(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.canonicalDurableId(): String? =
    string("session_key") ?: string("resumed") ?: string("stored_session_id")

private fun JsonObject.deltaText(): String = string("delta") ?: string("text") ?: contentText()

private fun JsonObject.contentText(): String = when (val content = this["content"] ?: this["text"]) {
    null, JsonNull -> ""
    is JsonPrimitive -> content.content
    is JsonArray -> content.joinToString("") { item ->
        when (item) {
            is JsonPrimitive -> item.content
            is JsonObject -> item.string("text") ?: item.string("content") ?: ""
            else -> ""
        }
    }

    is JsonObject -> content.string("text") ?: content.string("content") ?: ""
}

private fun JsonObject.reasoningText(): String {
    string("reasoning")?.let { return it }
    string("reasoning_content")?.let { return it }
    string("reasoning_details")?.let { return it }
    val content = this["content"] as? JsonArray ?: return ""
    return content.mapNotNull { item ->
        val part = item as? JsonObject ?: return@mapNotNull null
        val type = part.string("type")?.lowercase()
        if (type !in REASONING_CONTENT_TYPES) return@mapNotNull null
        part.string("text") ?: part.string("content")
    }.joinToString("")
}

private fun JsonObject.answerText(): String {
    val content = this["content"] as? JsonArray ?: return contentText()
    return content.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.content
            is JsonObject -> {
                val type = item.string("type")?.lowercase()
                if (type in REASONING_CONTENT_TYPES) null else item.string("text") ?: item.string("content")
            }

            else -> null
        }
    }.joinToString("")
}

private fun JsonObject.durationSeconds(): Double =
    primitive("duration_s")?.toDoubleOrNull()
        ?: primitive("elapsed_seconds")?.toDoubleOrNull()
        ?: 0.0

private fun JsonElement?.safePayloadText(): String? =
    displayText().safeDisplayText(MAX_TOOL_PAYLOAD)

private fun String?.safeDisplayText(limit: Int): String? = this
    ?.let(::redact)
    ?.take(limit)
    ?.takeIf(String::isNotBlank)

private fun String?.safePayloadText(): String? = safeDisplayText(MAX_TOOL_PAYLOAD)

private fun String?.safeToolLabel(fallback: String): String =
    safeDisplayText(MAX_TOOL_LABEL) ?: fallback

private fun JsonObject.toolInputText(): String? =
    this["args"].safePayloadText()
        ?: this["arguments"].safePayloadText()
        ?: this["input"].safePayloadText()
        ?: string("args_text")?.safePayloadText()

private fun JsonObject.toolDetail(type: String): String {
    val result = this["result"].displayText()
    val detail = if (type == "tool.complete") {
        string("summary") ?: result ?: string("context") ?: string("message") ?: ""
    } else {
        string("context") ?: string("summary") ?: string("preview") ?: string("message") ?: result.orEmpty()
    }
    return detail.safeDisplayText(MAX_TOOL_DETAIL).orEmpty()
}

private fun JsonObject.toolFailed(): Boolean {
    val status = string("status")?.lowercase()
    if (status in TOOL_FAILURE_STATUSES || this["error"].isTruthySignal()) return true
    if (boolean("success") == false || boolean("is_error") == true) return true
    val result = this["result"] as? JsonObject ?: return false
    return result.string("status")?.lowercase() in TOOL_FAILURE_STATUSES ||
        result["error"].isTruthySignal() ||
        result.boolean("success") == false ||
        result.boolean("is_error") == true
}

private fun JsonElement?.displayText(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> content
    is JsonObject, is JsonArray -> toString()
}

private fun JsonElement?.isTruthySignal(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonPrimitive -> when {
        !isString -> booleanOrNull ?: (content.toDoubleOrNull()?.let { it != 0.0 } ?: content.isNotBlank())
        else -> content.isNotBlank() && content.lowercase() !in FALSEY_SIGNAL_STRINGS
    }
    is JsonObject -> isNotEmpty()
    is JsonArray -> isNotEmpty()
}

private fun mergeHistoryWithLiveEntries(
    authoritative: List<TranscriptEntry>,
    live: List<TranscriptEntry>,
): List<TranscriptEntry> {
    val merged = authoritative.toMutableList()
    live.forEach { entry ->
        val index = merged.indexOfFirst { it.id == entry.id }
        if (index >= 0) merged[index] = entry else merged += entry
    }
    return merged
}

private fun JsonObject.status(): SessionStatus? = when (string("status")?.lowercase()) {
    "running", "starting", "working", "streaming" -> SessionStatus.Working
    "waiting", "needs_input", "needs-input" -> SessionStatus.NeedsInput
    "background" -> SessionStatus.Background
    "stalled" -> SessionStatus.Stalled
    "idle", "complete", "completed", "done" -> SessionStatus.Idle
    else -> null
}

private data class InflightProjection(
    val user: String,
    val assistant: String,
    val corrections: List<String>,
    val correctionOffsets: List<Int>?,
    val streaming: Boolean,
    val error: String,
    val status: String?,
    val atMillis: Long,
)

private data class LiveSessionProjection(
    val running: Boolean?,
    val status: SessionStatus?,
    val inflight: InflightProjection?,
    val hasAuthoritativeState: Boolean,
) {
    val retainedFailure: Boolean
        get() = inflight?.error?.isNotBlank() == true || inflight?.status.equals("error", ignoreCase = true)
    val busy: Boolean
        get() = running == true || inflight?.streaming == true || status in RESUMED_BUSY_STATUSES
}

private val EMPTY_LIVE_SESSION_PROJECTION = LiveSessionProjection(
    running = null,
    status = null,
    inflight = null,
    hasAuthoritativeState = false,
)

private fun parseLiveSessionProjection(root: JsonObject, fallbackTime: Long): LiveSessionProjection {
    val inflightRoot = root["inflight"] as? JsonObject
    val corrections = (inflightRoot?.get("corrections") as? JsonArray).orEmpty().mapNotNull { item ->
        (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf(String::isNotEmpty)
    }
    val offsetsRoot = inflightRoot?.get("correction_offsets") as? JsonArray
    val offsets = offsetsRoot?.mapNotNull { item ->
        (item as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()
    }?.takeIf { it.size == corrections.size }
    val atMillis = root.primitive("turn_started_at")?.epochMillisOrNull() ?: root.timestamp(fallbackTime)
    val inflight = inflightRoot?.let {
        InflightProjection(
            user = it.string("user").orEmpty().trim(),
            assistant = it.string("assistant").orEmpty(),
            corrections = corrections,
            correctionOffsets = offsets,
            streaming = it.boolean("streaming") == true,
            error = it.string("error").orEmpty().trim(),
            status = it.string("status"),
            atMillis = atMillis,
        )
    }?.takeIf { projection ->
        projection.user.isNotBlank() || projection.assistant.isNotBlank() || projection.corrections.isNotEmpty() ||
            projection.streaming || projection.error.isNotBlank() || !projection.status.isNullOrBlank()
    }
    return LiveSessionProjection(
        running = root.boolean("running"),
        status = root.status(),
        inflight = inflight,
        hasAuthoritativeState = "running" in root || "status" in root || "inflight" in root,
    )
}

private fun appendInflightProjection(
    history: List<TranscriptEntry>,
    runtimeId: String,
    projection: LiveSessionProjection,
    fallbackTime: Long,
): List<TranscriptEntry> {
    val inflight = projection.inflight
    if (inflight == null && !projection.busy) return history
    val restored = history.toMutableList()
    val atMillis = inflight?.atMillis ?: fallbackTime
    val user = inflight?.user.orEmpty()
    if (user.isNotBlank() && !restored.openUserRunContains(user)) {
        restored += UserTurn("inflight-user-$runtimeId", user, atMillis)
    }

    val assistant = inflight?.assistant.orEmpty()
    val error = inflight?.error.orEmpty().takeIf(String::isNotBlank)?.let(::safeGatewayTerminalError)
    val corrections = inflight?.corrections.orEmpty()
    val offsets = inflight?.correctionOffsets
    val usableOffsets = error == null && assistant.isNotEmpty() && offsets != null && offsets.size == corrections.size

    if (usableOffsets) {
        var cursor = 0
        corrections.forEachIndexed { index, correction ->
            val boundary = offsets[index].coerceIn(cursor, assistant.length)
            val segment = assistant.substring(cursor, boundary)
            if (segment.isNotBlank()) {
                restored += AssistantTurn("inflight-assistant-segment-$index-$runtimeId", segment, atMillis)
            }
            cursor = boundary
            if (!restored.openUserRunContains(correction)) {
                restored += UserTurn("inflight-correction-$index-$runtimeId", correction, atMillis)
            }
        }
        restored += AssistantTurn(
            id = "inflight-assistant-$runtimeId",
            markdown = assistant.substring(cursor),
            atMillis = atMillis,
            streaming = projection.busy,
        )
    } else {
        val wantsAssistant = assistant.isNotBlank() || projection.busy || error != null
        if (wantsAssistant) {
            restored += AssistantTurn(
                id = "inflight-assistant-$runtimeId",
                markdown = assistant,
                atMillis = atMillis,
                streaming = projection.busy,
                error = error,
            )
        }
        corrections.forEachIndexed { index, correction ->
            if (!restored.openUserRunContains(correction)) {
                restored += UserTurn("inflight-correction-$index-$runtimeId", correction, atMillis)
            }
        }
    }
    return restored
}

private fun List<TranscriptEntry>.openUserRunContains(text: String): Boolean {
    val normalized = text.normalizedTranscriptText()
    if (normalized.isEmpty()) return false
    for (entry in asReversed()) {
        when (entry) {
            is UserTurn -> if (entry.text.normalizedTranscriptText() == normalized) return true
            is AssistantTurn -> if (!entry.streaming) return false
            is ReasoningActivity -> Unit
            is ToolActivity -> Unit
        }
    }
    return false
}

private fun String.normalizedTranscriptText(): String = replace(STATUS_WHITESPACE, " ").trim()

private fun List<TranscriptEntry>.replaceOrAppend(entry: TranscriptEntry): List<TranscriptEntry> {
    val index = indexOfFirst { it.id == entry.id }
    if (index < 0) return this + entry
    return toMutableList().apply { this[index] = entry }
}

private fun JsonObject.primitive(name: String): String? = (this[name] as? JsonPrimitive)?.content

private fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)
    ?.takeUnless { it.isString }
    ?.booleanOrNull

private fun JsonObject.timestamp(fallback: Long): Long {
    // Desktop sorts by last activity and falls back to creation:
    // NousResearch/hermes-agent @ f82f2dbabd9e66b714f2b4f8a40447fe0c13e732,
    // apps/desktop/src/app/chat/sidebar/projects/workspace-groups.ts:134-135.
    val value = this["last_active"] ?: this["started_at"] ?: this["created_at"] ?: this["timestamp"] ?: return fallback
    val text = (value as? JsonPrimitive)?.content ?: return fallback
    text.epochMillisOrNull()?.let { return it }
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(fallback)
}

private fun JsonObject.hasTimestamp(): Boolean =
    "last_active" in this || "started_at" in this || "created_at" in this || "timestamp" in this

private fun String.epochMillisOrNull(): Long? {
    val number = toBigDecimalOrNull() ?: return null
    val millis = if (number < EPOCH_SECONDS_CUTOFF) number.movePointRight(3) else number
    return runCatching {
        millis.setScale(0, RoundingMode.DOWN).longValueExact()
    }.getOrNull()
}

private const val MAX_TOOL_DETAIL = 4_096
private const val MAX_TOOL_PAYLOAD = 32_768
private const val MAX_TOOL_LABEL = 256
private const val PROJECT_PREVIEW_LIMIT = 3
private const val MISSING_RPC_METHOD_CODE = -32601
private const val MAX_GATEWAY_ERROR_CLASSIFICATION_CHARS = 4_096
private const val MAX_STATUS_KIND = 64
private const val MAX_STATUS_TEXT = 240
private const val RECONCILIATION_FAILED_KIND = "reconcile_failed"
private const val RECONCILIATION_FAILED_TEXT =
    "This turn could not be checked. Reconnect to the Gateway, then reopen the session."
private const val PRE_START_FALSE_SETTLE_GRACE_MILLIS = 15_000L
private val STATUS_WHITESPACE = Regex("\\s+")
private val RESUMED_BUSY_STATUSES = setOf(
    SessionStatus.Working,
    SessionStatus.Stalled,
    SessionStatus.NeedsInput,
    SessionStatus.Background,
)
private val LIVE_RUNTIME_EVENT_TYPES = setOf(
    "session.info",
    "message.start",
    "message.delta",
    "message.complete",
    "reasoning.delta",
    "reasoning.available",
    "thinking.delta",
    "tool.start",
    "tool.progress",
    "tool.complete",
    "error",
)
private val EPOCH_SECONDS_CUTOFF = BigDecimal("10000000000")
private val TOOL_FAILURE_STATUSES = setOf("timeout", "error", "failed", "failure")
private val REASONING_CONTENT_TYPES = setOf("reasoning", "reasoning_text", "thinking")
private val FALSEY_SIGNAL_STRINGS = setOf("", "0", "false", "none", "null")
private val REMOTE_STORAGE_ERROR_MARKERS = setOf("disk full", "no space left on device", "errno 28")
