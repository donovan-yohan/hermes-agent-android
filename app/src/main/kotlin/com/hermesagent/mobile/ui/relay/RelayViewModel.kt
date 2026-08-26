package com.hermesagent.mobile.ui.relay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesagent.mobile.data.relay.MAX_HISTORY_LIMIT
import com.hermesagent.mobile.data.relay.RelayAvailability
import com.hermesagent.mobile.data.relay.RelayAvailabilityState
import com.hermesagent.mobile.data.relay.RelayChannel
import com.hermesagent.mobile.data.relay.RelayHistory
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The read half of the Relay plugin client, as this surface needs it.
 *
 * Narrow on purpose: posting is a separate slice, and a seam this small is
 * what lets the whole polling loop run on virtual time. `null` from either
 * call is the repository's fail-closed answer — "nothing usable came back" —
 * never an empty result.
 */
internal interface RelayChannelReader {
    suspend fun channels(): List<RelayChannel>?

    suspend fun history(channelId: String, limit: Int): RelayHistory?
}

/** Relay's data as the surface paints it. Null means "no answer yet", not "empty". */
private data class RelayDisplay(
    val channels: List<RelayChannelRow>?,
    val selectedChannelId: String?,
    val selectedChannelTitle: String?,
    val selectedChannelArchived: Boolean,
    val transcript: List<RelayTranscriptRow>?,
    val stale: Boolean,
)

/** Backend-authoritative Relay data, held only for as long as the surface is. */
private data class RelayData(
    val channels: List<RelayChannel>? = null,
    val selectedChannelId: String? = null,
    val messages: RelayHistory? = null,
    val stale: Boolean = false,
)

/**
 * UI-only state over the Relay plugin's read endpoints.
 *
 * Three rules keep this honest:
 *
 * 1. **The visible pane is the polled pane.** Desktop refreshes its visible
 *    page every three seconds and gates that interval on a ready lane plus a
 *    selected channel (hermes-plugin-relay @ `563a8c8`,
 *    `desktop/plugin.js:23,1073-1083`). Both of its panes are on screen at
 *    once; a phone shows one, so the same single tick refreshes whichever pane
 *    the person is actually looking at. One request per tick either way.
 * 2. **A hidden surface polls nothing, and neither does a lane nobody
 *    asks.** The loop exists only while the surface is between
 *    [surfaceResumed] and [surfacePaused] *and* the lane is ready, so neither
 *    backgrounding the app nor an offline lane leaves a timer running against
 *    a Gateway nobody is asking anything of.
 * 3. **A failed refresh never blanks the screen.** The repository is
 *    fail-closed, so an unusable answer keeps the last good one and raises
 *    [RelayUiState.stale]. There is no error toast: stale data is still true,
 *    it is only older than this second.
 *
 * Nothing here is persisted. Selection in particular is deliberately not:
 * Desktop restores a stored channel id on mount (`desktop/plugin.js:302-318`),
 * which on a phone would land someone inside a transcript they never chose.
 */
internal class RelayViewModel(
    private val availability: StateFlow<RelayAvailabilityState>,
    private val refreshAvailability: () -> Unit,
    private val reader: RelayChannelReader,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val locale: () -> Locale = { Locale.getDefault() },
    /** Injected so the poll cadence is driven by the test scheduler, not a clock. */
    private val wait: suspend (Long) -> Unit = { millis -> delay(millis) },
) : ViewModel() {

    private val data = MutableStateFlow(RelayData())
    private val resumed = MutableStateFlow(false)
    private var poll: Job? = null
    private var fetch: Job? = null

    /**
     * Display rows are derived from Relay's data alone, so they are mapped on
     * that flow rather than inside the combine below. Availability emits on
     * every probe round-trip — `probing` flips twice for an answer that never
     * changed — and none of those may cost a re-map of a 50-row transcript.
     */
    private val display: Flow<RelayDisplay> = data.map(::toDisplay)

    val uiState: StateFlow<RelayUiState> = combine(display, availability) { rows, gateway ->
        RelayUiState(
            notice = relayNotice(gateway),
            connecting = gateway.awaitingFirstAnswer,
            channels = rows.channels.orEmpty(),
            channelsLoaded = rows.channels != null,
            selectedChannelId = rows.selectedChannelId,
            selectedChannelTitle = rows.selectedChannelTitle,
            selectedChannelArchived = rows.selectedChannelArchived,
            transcript = rows.transcript.orEmpty(),
            transcriptLoaded = rows.transcript != null,
            stale = rows.stale,
            unavailableOnGateway = gateway.availability == RelayAvailability.Missing,
            relayAnswered = gateway.availability is RelayAvailability.Available,
            relayReady = gateway.laneIsReady(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RelayUiState())

    init {
        // Rule 1's other half: the first load rides the same liveness edge the
        // availability controller settles on, so opening the surface before the
        // Gateway answers still paints as soon as it does, without a timer
        // being the thing that noticed.
        viewModelScope.launch {
            combine(resumed, availability) { visible, gateway -> visible && gateway.laneIsReady() }
                .distinctUntilChanged()
                .collect { readable -> if (readable) refreshVisiblePane() else stopTick() }
        }
    }

    /**
     * The surface is on screen. Re-probe availability through the controller's
     * own entry point. Neither the first load nor the tick is started here:
     * both ride the liveness edge above, so a surface opened before the
     * Gateway has answered still paints the moment it does — and a lane
     * nothing is ever asked of never wakes a coroutine every three seconds
     * only to refuse itself.
     */
    fun surfaceResumed() {
        resumed.value = true
        refreshAvailability()
    }

    /**
     * The cadence means "three seconds since the last look", so anything that
     * looks now restarts it. Without that, a tap taken just before a scheduled
     * tick is followed almost immediately by a second identical request.
     *
     * Only ever reached from a look that actually happened, so the tick exists
     * exactly while the surface is visible *and* the lane is ready.
     */
    private fun restartTick() {
        poll?.cancel()
        poll = viewModelScope.launch {
            while (isActive) {
                wait(POLL_INTERVAL_MILLIS)
                // The tick may not restart itself; cancelling the job it is
                // running in would end the loop mid-iteration.
                refreshVisiblePane(resetTick = false)
            }
        }
    }

    private fun stopTick() {
        poll?.cancel()
        poll = null
    }

    /** The surface is gone. Nothing may keep asking the Gateway on its behalf. */
    fun surfacePaused() {
        resumed.value = false
        stopTick()
        fetch?.cancel()
        fetch = null
    }

    override fun onCleared() {
        surfacePaused()
        super.onCleared()
    }

    /**
     * Open one channel. The previous transcript is dropped rather than shown
     * under a new title, and the fresh window loads immediately instead of
     * waiting out a tick.
     */
    fun selectChannel(channelId: String) {
        if (data.value.selectedChannelId == channelId) return
        data.update { it.copy(selectedChannelId = channelId, messages = null, stale = false) }
        refreshVisiblePane()
    }

    /** Back to the list. Selection is the only thing that changes. */
    fun clearSelection() {
        if (data.value.selectedChannelId == null) return
        data.update { it.copy(selectedChannelId = null, messages = null, stale = false) }
        refreshVisiblePane()
    }

    /** The explicit retry beside a notice: re-probe, then reload what is visible. */
    fun retry() {
        refreshAvailability()
        refreshVisiblePane()
    }

    /**
     * One request for whichever pane is on screen. A lane Relay does not call
     * ready is not asked for data at all — the notice already explains why,
     * and a request that can only fail is not a refresh.
     */
    private fun refreshVisiblePane(resetTick: Boolean = true) {
        if (!availability.value.laneIsReady()) return
        if (resetTick && resumed.value) restartTick()
        val channelId = data.value.selectedChannelId
        fetch?.cancel()
        fetch = viewModelScope.launch {
            if (channelId == null) loadChannels() else loadHistory(channelId)
        }
    }

    private suspend fun loadChannels() {
        val answer = readOrNull { reader.channels() }
        if (answer == null) {
            markStale()
            return
        }
        data.update { it.copy(channels = answer, stale = false) }
    }

    private suspend fun loadHistory(channelId: String) {
        val answer = readOrNull { reader.history(channelId, MAX_HISTORY_LIMIT) }
        // The selection may have moved while the window was in flight; a
        // transcript must never land under a channel the person left.
        if (data.value.selectedChannelId != channelId) return
        if (answer == null) {
            markStale()
            return
        }
        data.update { it.copy(messages = answer, stale = false) }
    }

    /**
     * One failure policy for both reads. The repository is already fail-closed,
     * so `null` and a thrown transport both mean the same thing — nothing
     * usable came back — and neither may take the loop down with it. A
     * cancellation is not a Gateway fault and is rethrown untouched.
     */
    private suspend fun <T> readOrNull(read: suspend () -> T?): T? = try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private fun markStale() {
        data.update { it.copy(stale = true) }
    }

    private fun toDisplay(current: RelayData): RelayDisplay {
        val language = locale()
        val times = RelayTimeLabels(zone(), language, clock())
        val selected = current.channels?.firstOrNull { it.id == current.selectedChannelId }
        return RelayDisplay(
            channels = current.channels?.let { relayChannelRows(it, language, times) },
            selectedChannelId = current.selectedChannelId,
            selectedChannelTitle = selected?.title,
            selectedChannelArchived = selected?.archived == true,
            transcript = current.messages?.let { relayTranscriptRows(it.messages, language, times) },
            stale = current.stale,
        )
    }

    companion object {
        /** Desktop's `POLL_INTERVAL_MS` (`desktop/plugin.js:23`). */
        const val POLL_INTERVAL_MILLIS = 3_000L

        fun factory(
            availability: StateFlow<RelayAvailabilityState>,
            refreshAvailability: () -> Unit,
            reader: RelayChannelReader,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RelayViewModel(
                availability = availability,
                refreshAvailability = refreshAvailability,
                reader = reader,
            ) as T
        }
    }
}
