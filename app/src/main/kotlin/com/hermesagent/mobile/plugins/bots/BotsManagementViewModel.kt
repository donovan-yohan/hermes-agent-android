package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.UUID

/** Supplied by the application from its persisted endpoint record, never a transport generation. */
data class BotStorageEndpoint(val stableId: String, val generation: Long)

/** The dialog keeps its original owner/endpoint for its entire lifetime. */
enum class BotManagementDialog { New, Edit, Duplicate, Delete, Section, Move, Action }
data class BotManagementState(
    val dialog: BotManagementDialog? = null,
    val target: BotManagementTarget? = null,
    val draft: BotIdentityDraft = BotIdentityDraft(),
    val sectionId: String? = null,
    val sectionName: String = "",
    val choices: List<BotSection> = emptyList(),
    val busy: Boolean = false,
    /** Every dispatched mutation is consumed, including timeout/partial failure. */
    val consumed: Boolean = false,
    val message: String? = null,
    val storageEndpoint: BotStorageEndpoint? = null,
)
enum class BotRowAction { Pin, Hide, Edit, Duplicate, Delete, Move }

class BotsManagementViewModel(
    private val host: PluginHost,
    private val storage: PluginStorage,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
    initialSections: List<BotSection> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val newSectionId: () -> String = { "sec-${UUID.randomUUID()}" },
    private val storageEndpoint: StateFlow<BotStorageEndpoint?> = MutableStateFlow(null),
) {
    private val repository = BotsManagementRepository(host)
    private val mutableState = MutableStateFlow(BotManagementState())
    val state = mutableState.asStateFlow()
    private val mutableSections = MutableStateFlow(initialSections)
    val sections = mutableSections.asStateFlow()
    private var sectionsLoaded = false
    private var revision = 0L
    private var sectionMembers: List<BotRosterRow> = emptyList()

    private var sectionEndpoint: BotStorageEndpoint? = null
    private val initialGeneration = host.endpointGeneration.value
    private var sectionGeneration = initialGeneration

    private fun liveStorageEndpoint(): BotStorageEndpoint? = storageEndpoint.value?.takeIf {
        it.stableId.isNotBlank() && it.generation == host.endpointGeneration.value
    }

    private fun syncSections() {
        val live = liveStorageEndpoint()
        if (sectionGeneration != host.endpointGeneration.value || sectionEndpoint != live) {
            sectionGeneration = host.endpointGeneration.value
            sectionEndpoint = live
            sectionsLoaded = false
            mutableSections.value = emptyList()
            reset()
        }
    }

    init {
        scope.launch {
            combine(host.endpointGeneration, storageEndpoint) { _, _ -> syncSections(); liveStorageEndpoint() }
                .collectLatest { endpoint ->
                    syncSections()
                    if (endpoint == null) return@collectLatest
                    try {
                        val raw = storage.get(sectionsKey(endpoint))
                        if (endpoint != liveStorageEndpoint()) return@collectLatest
                        mutableSections.value = raw?.let(::decodeSections) ?: initialSections.takeIf { endpoint.generation == initialGeneration }.orEmpty()
                        sectionsLoaded = true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* No storage receipt: local section writes stay disabled. */ }
                }
        }
    }

    private fun admit(endpoint: Long): Boolean {
        syncSections()
        return !state.value.busy && endpoint == host.endpointGeneration.value && host.connected.value
    }

    private fun reset() {
        revision++
        sectionMembers = emptyList()
        mutableState.value = BotManagementState()
    }

    fun close() { if (!state.value.busy) reset() }

    fun openNew(endpoint: Long) {
        if (!admit(endpoint)) return
        reset()
        mutableState.value = BotManagementState(BotManagementDialog.New, BotManagementTarget("", endpoint))
    }

    fun openSection(
        endpoint: Long, section: BotSection? = null, rows: List<BotRosterRow> = emptyList(),
        choices: List<BotSection> = mutableSections.value,
    ) {
        if (!admit(endpoint)) return
        if (!sectionsLoaded || liveStorageEndpoint() == null) {
            reset()
            mutableState.value = BotManagementState(BotManagementDialog.Section, BotManagementTarget("", endpoint),
                consumed = true, message = if (liveStorageEndpoint() == null)
                    "Section storage is unavailable until this endpoint has a stable identity."
                else "Section storage could not be read. Reconnect before trying again.")
            return
        }
        reset()
        sectionMembers = rows.filter { it.storedMeta?.sectionId == section?.id && section != null }
        mutableState.value = BotManagementState(
            dialog = BotManagementDialog.Section, target = BotManagementTarget("", endpoint),
            sectionId = section?.id, sectionName = section?.name.orEmpty(), choices = choices,
            storageEndpoint = sectionEndpoint,
        )
    }

    fun act(row: BotRosterRow, action: BotRowAction, roster: BotsRosterUiState) {
        if (!admit(roster.endpoint) || roster.managementRows.none { it == row }) return
        if (action == BotRowAction.Delete && row.isDefault) return
        reset()
        val target = BotManagementTarget(row.name, roster.endpoint)
        val draft = BotIdentityDraft(row.name, row.displayName, row.description)
        when (action) {
            BotRowAction.Edit -> {
                mutableState.value = BotManagementState(BotManagementDialog.Edit, target, draft, busy = true)
                val admitted = revision
                scope.launch {
                    val loaded = repository.describe(target)
                    if (!current(admitted, target)) return@launch
                    mutableState.value = state.value.copy(
                        draft = loaded ?: draft, busy = false, consumed = loaded == null,
                        message = if (loaded == null) "The bot could not be read. Refresh and try again." else null,
                    )
                }
            }
            BotRowAction.Duplicate -> {
                val name = nextBotCopyName(row.name, roster.managementRows.map { it.name }.toSet())
                mutableState.value = BotManagementState(
                    BotManagementDialog.Duplicate, target,
                    draft.copy(name = name.orEmpty(), title = "${row.displayName.ifBlank { row.name }} (copy)"),
                    consumed = name == null,
                    message = if (name == null) "Choose another bot name after refreshing the roster." else null,
                )
            }
            BotRowAction.Delete -> mutableState.value = BotManagementState(
                BotManagementDialog.Delete, target, draft, consumed = true,
                message = "Delete is unavailable in this app. Use Desktop to safely delete this bot.",
            )
            BotRowAction.Move -> mutableState.value = BotManagementState(
                BotManagementDialog.Move, target, draft,
                sectionId = row.storedMeta?.sectionId, choices = roster.userSections,
            )
            BotRowAction.Pin, BotRowAction.Hide -> {
                mutableState.value = BotManagementState(BotManagementDialog.Action, target, draft)
                val patch = buildJsonObject {
                    if (action == BotRowAction.Pin) put("pinned", row.rosterKey !in roster.pinnedKeys)
                    else put("hidden", row.storedMeta?.hidden != true)
                }
                mutate { repository.patchMeta(target, patch) }
            }
        }
    }

    fun updateDraft(draft: BotIdentityDraft) {
        if (!state.value.busy && !state.value.consumed) mutableState.value = state.value.copy(draft = draft)
    }
    fun updateSectionName(name: String) {
        if (!state.value.busy && !state.value.consumed) mutableState.value = state.value.copy(sectionName = name)
    }
    fun selectSection(id: String?) {
        if (!state.value.busy && !state.value.consumed && (id == null || state.value.choices.any { it.id == id }))
            mutableState.value = state.value.copy(sectionId = id)
    }

    fun submit() {
        val admitted = state.value
        val target = admitted.target ?: return
        if (target.endpoint != host.endpointGeneration.value) { reset(); return }
        if (admitted.busy || admitted.consumed || !host.connected.value) return
        when (admitted.dialog) {
            BotManagementDialog.New, BotManagementDialog.Duplicate -> {
                if (!validBotId(admitted.draft.name) || admitted.draft.name == "default") return
                mutate { repository.create(target.endpoint, admitted.draft,
                    cloneFrom = target.name.takeIf { admitted.dialog == BotManagementDialog.Duplicate },
                    createdAtMillis = clock()) }
            }
            BotManagementDialog.Edit -> mutate { repository.saveIdentity(target, admitted.draft) }
            BotManagementDialog.Delete -> mutate { repository.delete(target) }
            BotManagementDialog.Move -> mutate {
                repository.patchMeta(target, sectionPatch(admitted.sectionId,
                    admitted.choices.firstOrNull { it.id == admitted.sectionId }?.name))
            }
            BotManagementDialog.Section -> {
                if (admitted.sectionName.isBlank()) return
                mutate {
                    val section = BotSection(admitted.sectionId ?: newSectionId(), admitted.sectionName.trim())
                    // Existing members are stamped before the local rename is committed. A partial
                    // server failure remains visible as unconfirmed and never replays automatically.
                    for (member in sectionMembers) {
                        if (repository.patchMeta(BotManagementTarget(member.name, target.endpoint),
                                sectionPatch(section.id, section.name)) != BotManagementResult.Confirmed)
                            return@mutate BotManagementResult.Unconfirmed
                    }
                    val previous = mutableSections.value
                    persistSections(admitted.storageEndpoint, if (previous.any { it.id == section.id })
                        previous.map { if (it.id == section.id) section else it } else previous + section)
                }
            }
            else -> Unit
        }
    }

    fun moveSection(delta: Int) {
        val admitted = state.value
        val id = admitted.sectionId ?: return
        if (admitted.dialog != BotManagementDialog.Section || delta !in listOf(-1, 1)) return
        val next = admitted.choices.toMutableList()
        val from = next.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in next.indices) return
        val item = next.removeAt(from)
        next.add(to, item)
        mutate { persistSections(admitted.storageEndpoint, next) }
    }

    fun deleteSection() {
        val admitted = state.value
        val target = admitted.target ?: return
        if (admitted.dialog != BotManagementDialog.Section || admitted.sectionId == null) return
        mutate {
            for (member in sectionMembers) {
                if (repository.patchMeta(BotManagementTarget(member.name, target.endpoint), sectionPatch(null, null))
                    != BotManagementResult.Confirmed) return@mutate BotManagementResult.Unconfirmed
            }
            persistSections(admitted.storageEndpoint, mutableSections.value.filterNot { it.id == admitted.sectionId })
        }
    }

    private fun sectionsKey(endpoint: BotStorageEndpoint): String = "bot-sections-v2-" +
        endpoint.stableId.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    private suspend fun persistSections(endpoint: BotStorageEndpoint?, next: List<BotSection>): BotManagementResult {
        if (endpoint == null || endpoint != liveStorageEndpoint()) return BotManagementResult.Unsupported
        val raw = JsonArray(next.map { buildJsonObject { put("id", it.id); put("name", it.name) } }).toString()
        val key = sectionsKey(endpoint)
        storage.set(key, raw)
        if (endpoint != liveStorageEndpoint()) return BotManagementResult.Unconfirmed
        if (storage.get(key) != raw || endpoint != liveStorageEndpoint()) return BotManagementResult.Unconfirmed
        mutableSections.value = next
        return BotManagementResult.Confirmed
    }

    private fun current(admitted: Long, target: BotManagementTarget): Boolean =
        admitted == revision && target.endpoint == host.endpointGeneration.value

    private fun mutate(block: suspend () -> BotManagementResult) {
        val target = state.value.target ?: return
        if (!admit(target.endpoint) || state.value.consumed) return
        val admitted = revision
        mutableState.value = state.value.copy(busy = true, consumed = true, message = null)
        scope.launch {
            if (!current(admitted, target)) return@launch
            if (!host.connected.value) {
                mutableState.value = state.value.copy(busy = false, message = "Disconnected. Close and refresh before trying again.")
                return@launch
            }
            val result = try { block() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { BotManagementResult.Unconfirmed }
            if (!current(admitted, target)) return@launch
            // Both success and uncertainty reconcile through authoritative roster truth.
            onChanged()
            if (result == BotManagementResult.Confirmed) reset()
            else mutableState.value = state.value.copy(busy = false, message = result.sentence())
        }
    }

    private fun sectionPatch(id: String?, name: String?) = buildJsonObject {
        put("sectionId", id?.let(::JsonPrimitive) ?: JsonNull)
        put("sectionName", name?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun decodeSections(raw: String): List<BotSection> {
        val array = Json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
        return normalizeBotSections(array.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val id = (row["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val name = (row["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            BotSection(id, name)
        })
    }

}
