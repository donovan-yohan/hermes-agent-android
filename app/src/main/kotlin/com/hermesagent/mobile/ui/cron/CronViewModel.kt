package com.hermesagent.mobile.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermesagent.mobile.data.composer.ModelCatalog
import com.hermesagent.mobile.data.cron.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** References are render-time capabilities, not ids resolved against the current profile. */
data class CronJobKey(val profile: String, val id: String)
data class CronJobRef(val endpoint: Long, val revision: Long, val job: CronJob, val serial: Long = 0L) {
    val key get() = CronJobKey(job.profile, job.id)
}
data class CronJobReview(val ref: CronJobRef, val job: CronJob? = null, val loading: Boolean = true)
data class CronHistory(val rows: List<CronRun> = emptyList(), val loading: Boolean = false, val failure: CronResult.Failure? = null)
data class CronEditorRef(val endpoint: Long, val revision: Long, val serial: Long, val profile: String)
data class CronEditorState(
    val ref: CronEditorRef,
    val job: CronJob? = null,
    val draft: CronDraft = CronDraft(),
    val blueprintKey: String? = null,
    val values: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val models: ModelCatalog = ModelCatalog(),
    val deliveryTargets: List<CronDeliveryTarget> = emptyList(),
    val discoveryFailed: Boolean = false,
)
data class CronUiState(
    val endpoint: Long = 0L,
    val revision: Long = 0L,
    val profile: String = "default",
    val jobs: List<CronJob> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val stale: Boolean = false,
    val failure: CronResult.Failure? = null,
    val notice: String? = null,
    val query: String = "",
    val pending: Map<CronJobKey, Long> = emptyMap(),
    val busy: Set<CronJobKey> = emptySet(),
    val locked: Set<CronJobKey> = emptySet(),
    val editor: CronEditorState? = null,
    val creationLocked: Boolean = false,
    val review: CronJobReview? = null,
    val selected: CronJobRef? = null,
    val peek: CronJobRef? = null,
    val histories: Map<CronJobKey, CronHistory> = emptyMap(),
    val deleteTarget: CronJobRef? = null,
    val blueprints: List<CronBlueprint> = emptyList(),
    val blueprintsLoading: Boolean = false,
    val blueprintsFailure: CronResult.Failure? = null,
    val nowMillis: Long = 0L,
)

/** Main-thread controller shared by the core manager and sidebar; never the Bots RPC facade. */
class CronViewModel(
    private val api: CronApi,
    private val endpoint: StateFlow<Long>,
    private val connected: StateFlow<Boolean>,
    hints: Flow<Unit> = emptyFlow(),
    ownerScope: CoroutineScope? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val loadModels: suspend (profile: String) -> ModelCatalog = { ModelCatalog() },
    private val connectionIdentity: () -> Any? = { null },
    connectionIdentities: StateFlow<Any?>? = null,
) : ViewModel() {
    private val work = ownerScope ?: viewModelScope
    private val mutable = MutableStateFlow(CronUiState(endpoint = endpoint.value, nowMillis = nowMillis()))
    val state: StateFlow<CronUiState> = mutable.asStateFlow()
    private var profile = "default"
    private var revision = 0L
    private var serial = 0L
    private var readRevision = 0L
    private var inventoryRead: Triple<Long, Long, Long>? = null
    private var blueprintRead: Any? = null
    private var lastInventory: Triple<Long, Long, Long>? = null
    private val uncertainCreateAfter = mutableMapOf<Pair<Long, String>, Long>()
    private var observedConnection = connectionIdentity()
    private var shown = false
    private val scopes = mutableSetOf<CronScope>()
    private val visibleOwners = mutableSetOf<Any>()
    private var poll: Job? = null
    // Uncertain mutation locks survive view/profile ABA; they never grant an automatic retry.
    private val uncertainCreates = mutableSetOf<Pair<Long, String>>()
    private val uncertainJobs = mutableSetOf<Pair<Long, CronJobKey>>()
    private val pendingAt = mutableMapOf<Pair<Long, CronJobKey>, Long>()
    private val pendingTokens = mutableMapOf<Pair<Long, CronJobKey>, Any>()
    private val pendingBaseline = mutableMapOf<Pair<Long, CronJobKey>, Set<String>>()
    private val inFlightCreates = mutableSetOf<Pair<Long, String>>()
    private val inFlightJobs = mutableMapOf<Pair<Long, CronJobKey>, Any>()
    private val provisionalTriggers = mutableMapOf<Pair<Long, CronJobKey>, Any>()
    private val feedbackFailures = mutableMapOf<Any, CronResult.Failure>()
    private val historyReads = mutableMapOf<CronJobKey, Pair<CronJobRef, Any>>()

    init {
        var observedEndpoint = endpoint.value
        var observedOnline = connected.value
        work.launch {
            combine(endpoint, connected, connectionIdentities ?: flowOf(null)) { ep, online, _ -> ep to online }.collect { (ep, online) ->
                val leg = connectionIdentity()
                if (ep != observedEndpoint || leg !== observedConnection) {
                    observedEndpoint = ep
                    observedConnection = leg
                    reset(clearJobs = true)
                    if (shown && online) { refresh(); loadBlueprints() }
                } else if (!online && observedOnline) {
                    reset(clearJobs = false)
                    mutable.value = state.value.copy(stale = true, failure = CronResult.Failure(CronFailure.Unreachable))
                } else if (online && !observedOnline && shown) {
                    refresh(); loadBlueprints()
                }
                observedOnline = online
            }
        }
        work.launch { hints.collect {
            if (visibleOwners.isNotEmpty()) refreshVisible() else refresh()
        } }
    }

    private fun scope(ep: Long, owner: String) = api.openScope(ep, owner).also { scopes += it }
    private suspend fun <T> use(scope: CronScope, block: suspend (CronScope) -> CronResult<T>): CronResult<T> = try {
        block(scope)
    } catch (cancel: CancellationException) { throw cancel
    } catch (_: Exception) { CronResult.Failure(CronFailure.Unreachable)
    } finally { scope.close(); scopes -= scope }
    private fun current(ep: Long, rev: Long) = ep == endpoint.value && rev == revision && connected.value && connectionIdentity() === observedConnection
    private fun writableProfile() = if (profile == "all") "default" else profile
    private fun locks() = (uncertainJobs + provisionalTriggers.keys).filter { it.first == endpoint.value }.map { it.second }.toSet()
    private fun busyJobs() = inFlightJobs.keys.filter { it.first == endpoint.value }.map { it.second }.toSet()
    private fun invalidateInventory() {
        mutable.value = state.value.copy(review = null)
        readRevision++
        inventoryRead = null
        lastInventory = null
    }
    private fun beginJob(ep: Long, key: CronJobKey): Any? {
        val owner = ep to key
        if (owner in inFlightJobs || owner in uncertainJobs) return null
        return Any().also { token ->
            invalidateInventory()
            inFlightJobs[owner] = token
            mutable.value = state.value.copy(busy = busyJobs())
        }
    }
    private suspend fun <T> mutateJob(ep: Long, key: CronJobKey, token: Any, target: CronScope,
        mutation: suspend (CronScope) -> CronResult<T>): CronResult<T> {
        val owner = ep to key
        try {
            return use(target, mutation).also { result ->
                if (result is CronResult.Failure && result.mutationMayHaveApplied) uncertainJobs += owner
            }
        } catch (cancel: CancellationException) {
            uncertainJobs += owner
            throw cancel
        } finally {
            if (inFlightJobs[owner] === token) inFlightJobs.remove(owner)
            invalidateInventory()
            if (provisionalTriggers[owner] === token) provisionalTriggers.remove(owner)
            val feedbackFailure = feedbackFailures.remove(token)
            mutable.value = state.value.copy(busy = busyJobs(), locked = locks(),
                failure = state.value.failure.takeUnless { feedbackFailure != null && it === feedbackFailure })
        }
    }
    private fun creationLocked(): Boolean {
        val editor = state.value.editor
        if (editor?.job != null) return false
        val key = endpoint.value to (editor?.ref?.profile ?: writableProfile())
        return key in uncertainCreates || key in inFlightCreates
    }
    private fun reset(clearJobs: Boolean) {
        revision++
        invalidateInventory()
        blueprintRead = null
        historyReads.clear()
        scopes.toList().forEach(CronScope::close); scopes.clear()
        val old = state.value
        mutable.value = CronUiState(
            endpoint = endpoint.value, revision = revision, profile = profile,
            jobs = if (clearJobs) emptyList() else old.jobs,
            loaded = !clearJobs && old.loaded, stale = !clearJobs,
            locked = locks(), busy = busyJobs(),
            creationLocked = (endpoint.value to writableProfile()).let { it in uncertainCreates || it in inFlightCreates }, nowMillis = nowMillis(),
            pending = pendingAt.filterKeys { it.first == endpoint.value }.mapKeys { it.key.second },
        )
    }

    fun show(profile: String) {
        if (shown && this.profile == profile && state.value.endpoint == endpoint.value) return
        shown = true
        this.profile = profile
        reset(clearJobs = true)
        if (connected.value) { refresh(); loadBlueprints() }
    }
    fun setQuery(value: String) { mutable.value = state.value.copy(query = value) }
    fun setVisible(visible: Boolean) = setVisibility("legacy", visible)
    /** The drawer can remain composed while hidden: the shell MUST pass its actual visibility. */
    fun setVisibility(owner: Any, visible: Boolean) {
        val changed = if (visible) visibleOwners.add(owner) else visibleOwners.remove(owner)
        if (!changed) return
        restartPoll()
        if (visible) refreshVisible()
    }
    private fun restartPoll() {
        poll?.cancel()
        if (visibleOwners.isEmpty()) return
        poll = work.launch {
            while (visibleOwners.isNotEmpty()) {
                delay(if (state.value.pending.isEmpty()) 8_000 else 1_000)
                refreshVisible()
            }
        }
    }
    fun onResume() { if (visibleOwners.isNotEmpty()) refreshVisible() }
    private fun refreshVisible() {
        if (!shown || !current(state.value.endpoint, revision)) return
        mutable.value = state.value.copy(nowMillis = nowMillis())
        refresh()
        listOfNotNull(state.value.selected, state.value.peek).distinctBy { it.key }.forEach(::loadHistory)
    }
    fun refresh() {
        if (!shown || !current(state.value.endpoint, revision)) return
        if (inventoryRead != null) return
        val ep = endpoint.value
        val rev = revision
        val read = ++readRevision
        val ticket = Triple(ep, rev, read)
        inventoryRead = ticket
        val target = scope(ep, profile)
        mutable.value = state.value.copy(loading = !state.value.loaded)
        work.launch {
            val result = try { use(target) { it.jobs() } } finally {
                if (inventoryRead === ticket) inventoryRead = null
            }
            if (!current(ep, rev) || read != readRevision) return@launch
            when (result) {
                is CronResult.Success -> {
                    val rows = result.value
                    fun retained(ref: CronJobRef?) = ref?.takeIf { r -> rows.any { it.id == r.job.id && it.profile == r.job.profile } }
                    lastInventory = ticket
                    mutable.value = state.value.copy(jobs = rows, loaded = true, loading = false, stale = false,
                        failure = state.value.failure?.takeIf { it.mutationMayHaveApplied || it.savedJobId != null },
                        selected = retained(state.value.selected), peek = retained(state.value.peek),
                        review = state.value.review?.takeIf { it.loading || it.job in rows })
                }
                is CronResult.Failure -> mutable.value = state.value.copy(loading = false, stale = true, failure = result)
            }
        }
    }
    fun reloadBlueprints() = loadBlueprints()
    private fun loadBlueprints() {
        if (!shown || !current(state.value.endpoint, revision) || blueprintRead != null) return
        val token = Any()
        blueprintRead = token
        val ep = endpoint.value; val rev = revision
        val target = scope(ep, writableProfile())
        mutable.value = state.value.copy(blueprintsLoading = true)
        work.launch {
            val result = try { use(target) { it.blueprints() } } finally {
                if (blueprintRead === token) blueprintRead = null
            }
            if (!current(ep, rev)) return@launch
            mutable.value = when (result) {
                is CronResult.Success -> state.value.copy(blueprints = result.value, blueprintsLoading = false, blueprintsFailure = null)
                is CronResult.Failure -> state.value.copy(blueprintsLoading = false, blueprintsFailure = result)
            }
        }
    }

    /** Read-only reconciliation; never infers whether a failed mutation was applied. */
    fun reviewJob(ref: CronJobRef) {
        val owner = ref.endpoint to ref.key
        if (!valid(ref) || owner !in uncertainJobs || owner in inFlightJobs || state.value.review?.loading == true) return
        val review = CronJobReview(ref.copy(serial = ++serial))
        invalidateInventory()
        val ticket = Triple(ref.endpoint, ref.revision, ++readRevision)
        inventoryRead = ticket
        mutable.value = state.value.copy(review = review)
        val inventory = scope(ref.endpoint, profile)
        work.launch {
            try {
                val rows = use(inventory) { it.jobs() }
                if (!current(ref.endpoint, ref.revision) || state.value.review?.ref != review.ref || readRevision != ticket.third) return@launch
                if (rows is CronResult.Failure) {
                    mutable.value = state.value.copy(review = null, stale = true, failure = rows)
                    return@launch
                }
                rows as CronResult.Success
                if (rows.value.none { it.id == ref.job.id && it.profile == ref.job.profile }) {
                    mutable.value = state.value.copy(review = null, jobs = rows.value, selected = null,
                        loaded = true, stale = false, notice = "The job is no longer listed. Nothing was submitted again.")
                    return@launch
                }
                val detail = use(scope(ref.endpoint, ref.job.profile)) { it.detail(ref.job.id) }
                if (!current(ref.endpoint, ref.revision) || state.value.review?.ref != review.ref || readRevision != ticket.third) return@launch
                if (detail is CronResult.Success && detail.value.id == ref.job.id && detail.value.profile == ref.job.profile) {
                    lastInventory = ticket
                    mutable.value = state.value.copy(review = review.copy(job = detail.value, loading = false),
                        jobs = rows.value.filterNot { it.id == ref.job.id && it.profile == ref.job.profile } + detail.value,
                        loaded = true, loading = false, stale = false)
                } else {
                    mutable.value = state.value.copy(review = null, notice = "Failed to review the current job. Try again.")
                }
            } finally {
                if (inventoryRead === ticket) inventoryRead = null
            }
        }
    }
    fun acknowledgeJobOutcome(ref: CronJobRef) {
        val review = state.value.review ?: return
        val owner = ref.endpoint to ref.key
        if (review.ref != ref || review.loading || review.job == null || !valid(ref) || state.value.stale ||
            owner !in uncertainJobs || owner in inFlightJobs) return
        uncertainJobs -= owner
        val editor = state.value.editor
        mutable.value = state.value.copy(review = null, locked = locks(), failure = null,
            editor = editor?.let { if (it.job?.let { job -> CronJobKey(job.profile, job.id) } == ref.key)
                it.copy(ref = it.ref.copy(serial = ++serial), job = review.job, error = null) else it },
            notice = "Current job reviewed. Nothing was submitted again.")
    }

    fun reference(job: CronJob): CronJobRef? = state.value.jobs.find { it == job }?.let { CronJobRef(state.value.endpoint, revision, it) }
    private fun valid(ref: CronJobRef) = current(ref.endpoint, ref.revision) && ref.job.profile != "all" &&
        state.value.jobs.any { it.id == ref.job.id && it.profile == ref.job.profile }
    fun select(job: CronJob) { reference(job)?.let(::select) }
    fun select(ref: CronJobRef) {
        if (!valid(ref)) return
        val selected = ref.copy(serial = ++serial)
        mutable.value = state.value.copy(selected = selected, review = null)
        loadHistory(selected)
    }
    fun clearSelection(ref: CronJobRef) { if (state.value.selected == ref) mutable.value = state.value.copy(selected = null, review = null) }
    fun togglePeek(job: CronJob) { reference(job)?.let(::togglePeek) }
    fun togglePeek(ref: CronJobRef) {
        if (!valid(ref)) return
        val peek = if (state.value.peek?.key == ref.key) null else ref.copy(serial = ++serial)
        mutable.value = state.value.copy(peek = peek)
        peek?.let(::loadHistory)
    }
    private fun activeHistory(ref: CronJobRef) = state.value.selected == ref || state.value.peek == ref
    private fun loadHistory(ref: CronJobRef) {
        if (!valid(ref)) return
        if (historyReads[ref.key]?.first?.let { valid(it) && activeHistory(it) } == true) return
        val read = ref to Any()
        historyReads[ref.key] = read
        val target = scope(ref.endpoint, ref.job.profile)
        val previous = state.value.histories[ref.key] ?: CronHistory()
        mutable.value = state.value.copy(histories = state.value.histories + (ref.key to previous.copy(loading = previous.rows.isEmpty())))
        work.launch {
            val result = try { use(target) { it.runs(ref.job.id, 20) } } finally {
                if (historyReads[ref.key] === read) historyReads.remove(ref.key)
            }
            if (!valid(ref) || !activeHistory(ref) || historyReads.containsKey(ref.key)) return@launch
            when (result) {
                is CronResult.Success -> {
                    val rows = result.value.filter { it.profile == ref.job.profile }
                    mutable.value = state.value.copy(histories = state.value.histories + (ref.key to CronHistory(rows)))
                    val key = ref.endpoint to ref.key
                    val at = pendingAt[key]
                    if (at != null && rows.any { it.id !in pendingBaseline[key].orEmpty() && ((it.startedAt ?: it.lastActive ?: 0.0) * 1000) >= at - 2000 }) {
                        pendingAt.remove(key); pendingBaseline.remove(key); pendingTokens.remove(key)
                        mutable.value = state.value.copy(pending = state.value.pending - ref.key)
                        restartPoll()
                    }
                }
                is CronResult.Failure -> mutable.value = state.value.copy(histories = state.value.histories + (ref.key to previous.copy(loading = false, failure = result)))
            }
        }
    }
    fun reportRunOpenFailure() {
        mutable.value = state.value.copy(notice = "Failed to open cron run. Try again.")
    }

    /** Revalidate captured owner, view revision, selection instance, and exact snapshot row before admission. */
    fun openRun(ref: CronJobRef, run: CronRun, open: (CronRun) -> Unit) {
        if (valid(ref) && activeHistory(ref) && run.profile == ref.job.profile && run.isConversation &&
            state.value.histories[ref.key]?.rows?.contains(run) == true) open(run)
    }

    fun act(job: CronJob, action: CronAction) { reference(job)?.let { act(it, action) } }
    fun act(ref: CronJobRef, action: CronAction) {
        if (action == CronAction.Delete) { requestDelete(ref); return }
        if (!valid(ref) || ref.key in state.value.busy || ref.key in state.value.locked || (action == CronAction.Trigger && ref.key in state.value.pending)) return
        val token = beginJob(ref.endpoint, ref.key) ?: return
        mutable.value = state.value.copy(failure = null, notice = null)
        if (action == CronAction.Trigger) queue(ref, token)
        val target = scope(ref.endpoint, ref.job.profile)
        work.launch(start = CoroutineStart.UNDISPATCHED) {
            var detail: CronResult<CronJob>? = null
            val result = mutateJob(ref.endpoint, ref.key, token, target) {
                val outcome = it.act(ref.job, action)
                if (outcome is CronResult.Success && action != CronAction.Trigger && current(ref.endpoint, ref.revision)) {
                    detail = use(scope(ref.endpoint, ref.job.profile)) { scope -> scope.detail(ref.job.id) }
                }
                outcome
            }
            if (action == CronAction.Trigger && result is CronResult.Failure && !result.mutationMayHaveApplied) {
                pendingAt.remove(ref.endpoint to ref.key)
                pendingBaseline.remove(ref.endpoint to ref.key)
                pendingTokens.remove(ref.endpoint to ref.key)
                if (endpoint.value == ref.endpoint) mutable.value = state.value.copy(pending = state.value.pending - ref.key)
            }
            if (!current(ref.endpoint, ref.revision)) return@launch
            when (result) {
                is CronResult.Success -> {
                    if (action == CronAction.Trigger) {
                        mutable.value = state.value.copy(notice = "Cron triggered")
                        listOfNotNull(state.value.selected, state.value.peek).filter { it.key == ref.key }.forEach(::loadHistory)
                    } else {
                        val confirmed = detail
                        if (confirmed is CronResult.Success) {
                            upsert(confirmed.value)
                            mutable.value = state.value.copy(notice = if (action == CronAction.Pause) "Cron paused" else "Cron resumed")
                        } else if (confirmed is CronResult.Failure) mutable.value = state.value.copy(stale = true, failure = confirmed)
                    }
                    if (action == CronAction.Trigger) refresh()
                }
                is CronResult.Failure -> {
                    mutable.value = state.value.copy(failure = result, locked = locks())
                }
            }
        }
    }
    private fun queue(ref: CronJobRef, token: Any) {
        val at = nowMillis(); val key = ref.endpoint to ref.key
        pendingAt[key] = at
        pendingTokens[key] = token
        pendingBaseline[key] = state.value.histories[ref.key]?.rows?.map { it.id }?.toSet().orEmpty()
        mutable.value = state.value.copy(pending = state.value.pending + (ref.key to at))
        restartPoll()
        work.launch {
            delay(90_000)
            if (pendingTokens[key] !== token) return@launch
            pendingAt.remove(key); pendingBaseline.remove(key); pendingTokens.remove(key)
            val unresolved = inFlightJobs[key] === token
            val failure = if (unresolved) CronResult.Failure(CronFailure.Unconfirmed, true) else null
            if (unresolved) {
                provisionalTriggers[key] = token
                feedbackFailures[token] = failure!!
            }
            if (endpoint.value == ref.endpoint) mutable.value = state.value.copy(pending = state.value.pending - ref.key,
                locked = locks(), busy = busyJobs(), failure = failure ?: state.value.failure)
            restartPoll()
        }
    }
    fun requestDelete(job: CronJob) { reference(job)?.let(::requestDelete) }
    fun requestDelete(ref: CronJobRef) { if (valid(ref)) mutable.value = state.value.copy(deleteTarget = ref.copy(serial = ++serial), failure = null) }
    fun cancelDelete() { state.value.deleteTarget?.let(::cancelDelete) }
    fun cancelDelete(ref: CronJobRef) { if (state.value.deleteTarget == ref && ref.key !in state.value.busy) mutable.value = state.value.copy(deleteTarget = null) }
    fun confirmDelete() { state.value.deleteTarget?.let(::confirmDelete) }
    fun confirmDelete(ref: CronJobRef) {
        if (state.value.deleteTarget != ref || !valid(ref) || ref.key in state.value.busy || ref.key in state.value.locked) return
        val token = beginJob(ref.endpoint, ref.key) ?: return
        mutable.value = state.value.copy(failure = null)
        val target = scope(ref.endpoint, ref.job.profile)
        work.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = mutateJob(ref.endpoint, ref.key, token, target) { it.act(ref.job, CronAction.Delete) }
            if (!current(ref.endpoint, ref.revision)) return@launch
            when (result) {
                is CronResult.Success -> {
                    mutable.value = state.value.copy(deleteTarget = null, jobs = state.value.jobs.filterNot { it.id == ref.job.id && it.profile == ref.job.profile },
                        selected = state.value.selected?.takeUnless { it.key == ref.key }, peek = state.value.peek?.takeUnless { it.key == ref.key }, notice = "Cron deleted")
                    refresh()
                }
                is CronResult.Failure -> {
                    mutable.value = state.value.copy(failure = result, locked = locks())
                }
            }
        }
    }

    private fun editorCurrent(ref: CronEditorRef) = state.value.editor?.ref == ref && current(ref.endpoint, ref.revision)
    fun edit(job: CronJob? = null) { if (job == null) openEditor(null) else reference(job)?.let(::edit) }
    private fun jobBlocked(ep: Long, job: CronJob) = (ep to CronJobKey(job.profile, job.id)).let { it in uncertainJobs || it in inFlightJobs }
    fun edit(ref: CronJobRef) { if (valid(ref) && !jobBlocked(ref.endpoint, ref.job)) openEditor(ref.job) }
    private fun openEditor(job: CronJob?) {
        if (!current(state.value.endpoint, revision)) return
        val ref = CronEditorRef(endpoint.value, revision, ++serial, job?.profile ?: writableProfile())
        mutable.value = state.value.copy(editor = CronEditorState(ref, job = job, loading = job != null), failure = null, notice = null,
            creationLocked = job == null && ((ref.endpoint to ref.profile) in uncertainCreates || (ref.endpoint to ref.profile) in inFlightCreates))
        if (job != null) {
            val target = scope(ref.endpoint, ref.profile)
            work.launch {
                val result = use(target) { it.detail(job.id) }
                if (!editorCurrent(ref)) return@launch
                when (result) {
                    is CronResult.Success -> {
                        val raw = result.value
                        mutable.value = state.value.copy(editor = state.value.editor!!.copy(job = raw,
                            draft = CronDraft(raw.name, raw.prompt, raw.schedule, raw.deliver, raw.model, raw.provider), loading = false))
                    }
                    is CronResult.Failure -> mutable.value = state.value.copy(editor = state.value.editor!!.copy(error = "Failed to load cron job", loading = true), failure = result)
                }
            }
        }
        val target = scope(ref.endpoint, ref.profile)
        work.launch {
            val delivery = use(target) { it.deliveryTargets() }
            if (!editorCurrent(ref)) return@launch
            mutable.value = state.value.copy(editor = state.value.editor!!.copy(deliveryTargets = (delivery as? CronResult.Success)?.value.orEmpty(), discoveryFailed = delivery is CronResult.Failure))
        }
        work.launch {
            val catalog = try { loadModels(ref.profile) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { ModelCatalog() }
            if (editorCurrent(ref)) mutable.value = state.value.copy(editor = state.value.editor!!.copy(models = catalog))
        }
    }
    fun updateDraft(ref: CronEditorRef, draft: CronDraft) {
        if (editorCurrent(ref) && state.value.editor?.saving == false && state.value.editor?.job?.let { jobBlocked(ref.endpoint, it) } != true) mutable.value = state.value.copy(editor = state.value.editor!!.copy(draft = draft, error = null))
    }
    fun updateBlueprintValues(ref: CronEditorRef, values: Map<String, String>) {
        if (editorCurrent(ref) && state.value.editor?.saving == false) mutable.value = state.value.copy(editor = state.value.editor!!.copy(values = values, error = null))
    }
    fun chooseBlueprint(ref: CronEditorRef, key: String?) {
        val editor = state.value.editor ?: return
        if (!editorCurrent(ref) || editor.job != null || editor.saving) return
        val blueprint = state.value.blueprints.find { it.key == key }
        mutable.value = state.value.copy(editor = editor.copy(blueprintKey = blueprint?.key,
            values = blueprint?.fields?.associate { field -> field.name to field.defaultValue.orEmpty().let {
                if (field.name == "deliver" && (it.isEmpty() || it == "origin")) "local" else it
            } }.orEmpty(), error = null))
    }
    fun closeEditor() { state.value.editor?.ref?.let(::closeEditor) }
    /** SecureScreenLifetime calls this before clearing FLAG_SECURE. No raw draft enters SavedState. */
    fun closeEditor(ref: CronEditorRef) { if (state.value.editor?.ref == ref) mutable.value = state.value.copy(editor = null, review = null) }
    fun save(draft: CronDraft) { state.value.editor?.ref?.let { save(it, draft) } }
    fun save(ref: CronEditorRef, draft: CronDraft) {
        val editor = state.value.editor ?: return
        if (!editorCurrent(ref) || editor.loading || editor.saving) return
        if (editor.job == null && ((ref.endpoint to ref.profile) in uncertainCreates || (ref.endpoint to ref.profile) in inFlightCreates)) return
        if (editor.job?.let { jobBlocked(ref.endpoint, it) } == true) return
        val validation = when {
            draft.prompt.isBlank() && draft.schedule.isBlank() && editor.job?.scriptOnly != true -> "Prompt and schedule are required."
            draft.schedule.isBlank() -> "Schedule is required."
            draft.prompt.isBlank() && editor.job?.scriptOnly != true -> "Prompt is required."
            else -> null
        }
        if (validation != null) { mutable.value = state.value.copy(editor = editor.copy(error = validation)); return }
        submit(ref, editor.job) { if (editor.job == null) it.create(draft) else it.update(editor.job, draft) }
    }
    fun instantiate(key: String, values: Map<String, String>) { state.value.editor?.ref?.let { instantiate(it, key, values) } }
    fun instantiate(ref: CronEditorRef, key: String, values: Map<String, String>) {
        val editor = state.value.editor ?: return
        if (!editorCurrent(ref) || editor.job != null || editor.saving || editor.loading || editor.blueprintKey != key || (ref.endpoint to ref.profile) in uncertainCreates || (ref.endpoint to ref.profile) in inFlightCreates) return
        submit(ref, null) { it.instantiate(key, values) }
    }
    /** Explicit review only: never matches titles, retries a write, or reuses the uncertain draft. */
    fun acknowledgeCreationOutcome(ref: CronEditorRef) {
        val editor = state.value.editor ?: return
        val key = ref.endpoint to ref.profile
        val after = uncertainCreateAfter[key] ?: return
        val read = lastInventory ?: return
        if (!editorCurrent(ref) || editor.job != null || editor.saving || key in inFlightCreates ||
            state.value.stale || !state.value.loaded || read.first != ref.endpoint || read.second != ref.revision || read.third <= after) return
        uncertainCreates -= key
        uncertainCreateAfter.remove(key)
        openEditor(null)
        mutable.value = state.value.copy(notice = "Review existing cron jobs before creating another.")
    }
    private fun submit(ref: CronEditorRef, job: CronJob?, mutation: suspend (CronScope) -> CronResult<CronJob>) {
        val key = ref.endpoint to ref.profile
        val jobKey = job?.let { CronJobKey(it.profile, it.id) }
        val token = jobKey?.let { beginJob(ref.endpoint, it) ?: return }
        if (job == null) {
            invalidateInventory()
            inFlightCreates += key
        }
        mutable.value = state.value.copy(editor = state.value.editor?.copy(saving = true, error = null))
        val target = scope(ref.endpoint, ref.profile)
        work.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = if (jobKey != null) mutateJob(ref.endpoint, jobKey, token!!, target, mutation) else try {
                use(target, mutation)
            } catch (cancel: CancellationException) {
                uncertainCreates += key
                uncertainCreateAfter[key] = readRevision
                throw cancel
            } finally {
                invalidateInventory()
                inFlightCreates -= key
                mutable.value = state.value.copy(creationLocked = creationLocked())
            }
            if (result is CronResult.Failure && (result.mutationMayHaveApplied || result.kind == CronFailure.SavedRegistrationFailed)) {
                if (job == null) {
                    uncertainCreates += key
                    uncertainCreateAfter[key] = readRevision
                } else uncertainJobs += ref.endpoint to CronJobKey(job.profile, job.id)
            }
            mutable.value = state.value.copy(creationLocked = creationLocked(), locked = locks())
            if (!current(ref.endpoint, ref.revision)) return@launch
            when (result) {
                is CronResult.Success -> {
                    upsert(result.value)
                    mutable.value = state.value.copy(editor = state.value.editor?.takeUnless { it.ref == ref }, notice = if (job == null) "Cron created" else "Cron updated")
                }
                is CronResult.Failure -> mutable.value = state.value.copy(failure = result, creationLocked = creationLocked(), locked = locks(),
                    editor = state.value.editor?.let { if (it.ref == ref) it.copy(saving = false, error = "Failed to save cron job") else it })
            }
        }
    }
    private fun upsert(job: CronJob) {
        invalidateInventory()
        mutable.value = state.value.copy(jobs = state.value.jobs.filterNot { it.id == job.id && it.profile == job.profile } + job)
    }
    override fun onCleared() { scopes.toList().forEach(CronScope::close); scopes.clear(); poll?.cancel() }
}
