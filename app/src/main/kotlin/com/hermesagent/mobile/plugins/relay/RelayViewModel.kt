package com.hermesagent.mobile.plugins.relay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
 * Narrow on purpose: a seam this small is what lets the whole polling loop run
 * on virtual time. `null` from either call is the repository's fail-closed
 * answer — "nothing usable came back" — never an empty result.
 */
interface RelayChannelReader {
    suspend fun channels(): List<RelayChannel>?

    suspend fun history(channelId: String, limit: Int = 50): RelayHistory?
}

/**
 * The write half, kept separate from the read half on purpose: reads are a
 * poll nobody asked for and may fail silently, while a post is one deliberate
 * act whose outcome a person is waiting on. Only [RelayPostResult] crosses
 * this seam, so the retry policy above it never has to read a status code out
 * of a transport.
 */
interface RelayPoster {
    suspend fun post(
        channelId: String,
        text: String,
        format: RelayMessageFormat,
        clientMessageId: String,
    ): RelayPostResult
}

/**
 * One send, as the retry key sees it: the exact bytes and the exact id they
 * were first dispatched under. Held together because they are only ever
 * meaningful together — an id without its text cannot be re-sent safely, and
 * text without its id is a new message.
 */
private data class RelayAttempt(val text: String, val clientMessageId: String)

/** One channel's composer. UI-only, per channel, and never written anywhere. */
private data class RelaySendState(
    val draft: String = "",
    /**
     * The attempt whose `clientMessageId` the next send may reuse. Non-null
     * exactly while an answer about where that message ended up is missing.
     */
    val attempt: RelayAttempt? = null,
    val sending: Boolean = false,
    val outcome: RelaySendOutcome? = null,
    /**
     * The `clientMessageId` of an attempt Relay answered with a conflict, held
     * only until a window carries the row it named. Not a retry key — that one
     * is spent — but the single fact that lets the poll retire a warning
     * instead of leaving it standing over a message that is plainly there.
     */
    val awaitingArrival: String? = null,
    /** Relay's id for the newest row this device got it to store here. */
    val lastAcceptedId: String? = null,
)

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
    /**
     * Rows Relay acknowledged, per channel, that the polled window has not
     * caught up with yet. Not a queue and not a draft store: every row in here
     * has already been stored by Relay and carries Relay's own id and `seq`,
     * which is exactly what lets the next window reconcile it away.
     */
    val acknowledged: Map<String, List<RelayMessage>> = emptyMap(),
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
 * Sending adds a fourth, and it is the only rule here that is not about
 * reading: **a message is dispatched under one `clientMessageId` until Relay
 * says where it ended up.** Everything that policy turns on lives in
 * [relayPostVerdict]; this class only holds the attempt it names and hands
 * back the same bytes and the same id on a retry.
 *
 * Nothing here is persisted — drafts included. Selection in particular is
 * deliberately not: Desktop restores a stored channel id on mount
 * (`desktop/plugin.js:302-318`), which on a phone would land someone inside a
 * transcript they never chose.
 */
class RelayViewModel(
    private val availability: StateFlow<RelayAvailabilityState>,
    private val refreshAvailability: () -> Unit,
    private val reader: RelayChannelReader,
    private val poster: RelayPoster,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val locale: () -> Locale = { Locale.getDefault() },
    /** Injected so the poll cadence is driven by the test scheduler, not a clock. */
    private val wait: suspend (Long) -> Unit = { millis -> delay(millis) },
    /** Injected so a test can name the id it expects a retry to reuse. */
    private val newClientMessageId: () -> String = ::newRelayClientMessageId,
) : ViewModel() {

    private val data = MutableStateFlow(RelayData())

    /**
     * Composer state per channel, deliberately outside [data]: a keystroke must
     * not re-project a 50-row transcript, and Relay's own data must not be
     * invalidated by one. Both halves meet in the combine below.
     */
    private val sends = MutableStateFlow<Map<String, RelaySendState>>(emptyMap())
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

    val uiState: StateFlow<RelayUiState> = combine(
        display,
        sends,
        availability,
    ) { rows, composers, gateway ->
        // One answer to "may this lane be asked anything", used by the panes
        // and by the composer alike.
        val relayReady = gateway.laneIsReady()
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
            composer = composerState(rows, composers[rows.selectedChannelId], gateway, relayReady),
            stale = rows.stale,
            unavailableOnGateway = gateway.availability == RelayAvailability.Missing,
            relayAnswered = gateway.availability is RelayAvailability.Available,
            relayReady = relayReady,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RelayUiState())

    /**
     * Whether a post is even possible is a fact about three different things —
     * a channel is open, Relay's lane is ready, and the channel takes writes —
     * so it is answered in one place rather than three times down the screen.
     */
    private fun composerState(
        rows: RelayDisplay,
        send: RelaySendState?,
        gateway: RelayAvailabilityState,
        relayReady: Boolean,
    ): RelayComposerUiState {
        val current = send ?: RelaySendState()
        val postable = rows.selectedChannelId != null &&
            relayReady &&
            !rows.selectedChannelArchived
        return RelayComposerUiState(
            draft = current.draft,
            hint = relayComposerHint(gateway.availability, rows.selectedChannelArchived),
            // A post already in flight for *this* channel is what closes the
            // control. Another channel's post is somebody else's business.
            editable = postable,
            sending = current.sending,
            outcome = current.outcome,
            lastAcceptedId = current.lastAcceptedId,
        )
    }

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
     * The draft for the open channel. Per channel and UI-only: leaving a
     * channel keeps what was typed there, and nothing about it reaches disk.
     */
    fun setDraft(text: String) {
        val channelId = data.value.selectedChannelId ?: return
        updateSend(channelId) { send ->
            send.copy(
                draft = text,
                // A receipt is spent by the next keystroke. A decision — retry,
                // reconnect — is not: it outlives the draft that caused it,
                // because the message it describes may still be in flight.
                outcome = send.outcome?.takeIf { it.action != null },
            )
        }
    }

    /**
     * The send tap.
     *
     * Whether this is a first attempt or a retry is not a flag the caller
     * passes; it is read off the draft. Text that still matches the attempt
     * waiting for an answer *is* that attempt, so double-tapping send cannot
     * post twice, while text that has changed is a new message and gets a new
     * id. Nothing is dispatched for input the server would refuse anyway.
     */
    fun sendDraft() {
        val channelId = data.value.selectedChannelId
        val send = channelId?.let(sends.value::get) ?: RelaySendState()
        // A post already on the wire for this channel owns the composer. That
        // is asked first: a second tap during a send is not a new message, and
        // it must not get to replace the outcome of the one in flight either.
        if (send.sending) return
        val text = send.draft.trim()
        relayLocalRejection(channelId, text)?.let { rejection ->
            // Zero requests: this refusal needed no Gateway to answer it.
            channelId?.let { id -> updateSend(id) { it.copy(outcome = rejection) } }
            return
        }
        if (channelId == null) return
        val reusable = send.attempt?.takeIf { it.text == text }
        dispatch(channelId, reusable ?: RelayAttempt(text, newClientMessageId()))
    }

    /**
     * The retry beside an unconfirmed send. Deliberately re-sends the attempt's
     * own captured text and id rather than whatever is in the field now: the
     * message whose fate is unknown is the one that has to be settled.
     */
    fun retrySend() {
        val channelId = data.value.selectedChannelId ?: return
        val send = sends.value[channelId] ?: return
        val attempt = send.attempt ?: return
        if (send.sending) return
        dispatch(channelId, attempt)
    }

    /**
     * One post, under one id.
     *
     * Not cancelled by [surfacePaused]: a request already on the wire is not
     * made un-sent by backgrounding the app, and abandoning it would turn a
     * decided outcome into an unknown one.
     */
    private fun dispatch(channelId: String, attempt: RelayAttempt) {
        updateSend(channelId) { it.copy(attempt = attempt, sending = true) }
        viewModelScope.launch {
            val result = try {
                poster.post(
                    channelId = channelId,
                    text = attempt.text,
                    // Desktop posts Markdown and offers no format control
                    // (`desktop/plugin.js:930`); a picker for a choice nobody
                    // makes is not an adaptation.
                    format = RelayMessageFormat.MARKDOWN,
                    clientMessageId = attempt.clientMessageId,
                )
            } catch (cancelled: CancellationException) {
                updateSend(channelId) { it.copy(sending = false) }
                throw cancelled
            } catch (_: Throwable) {
                // A transport that threw says exactly as much about where the
                // message ended up as a timeout does: nothing. So it is the
                // same answer, and it keeps the same id.
                RelayPostResult.Failed(0, TRANSPORT_DOWN_MESSAGE, retryable = true)
            }
            settle(channelId, attempt, result)
        }
    }

    /** Apply one settled post to the draft, its id, and the transcript. */
    private fun settle(channelId: String, attempt: RelayAttempt, result: RelayPostResult) {
        val verdict = relayPostVerdict(result)
        val stored = (result as? RelayPostResult.Accepted)?.message
        stored?.let { accepted ->
            // Paint it now. The row is Relay's own, id and `seq` included, so
            // the next window replaces it rather than doubling it.
            data.update { it.acknowledging(channelId, accepted) }
        }
        updateSend(channelId) { send ->
            // One question, asked once and spelled once: is the post that just
            // settled still the one this composer is holding? A newer attempt
            // owns the slot otherwise, and nothing here may touch it.
            val stillCurrent = send.attempt == attempt
            send.copy(
                // Tells the transcript that this arrival is the reader's own,
                // which is the one that outranks having scrolled back.
                lastAcceptedId = stored?.id ?: send.lastAcceptedId,
                // Only the text this attempt actually carried is retired. A
                // draft edited while the post was in flight is a different
                // message and survives its predecessor's success.
                draft = if (verdict.clearsDraft && stillCurrent && send.draft.trim() == attempt.text) {
                    ""
                } else {
                    send.draft
                },
                attempt = if (verdict.keepsAttempt || !stillCurrent) send.attempt else null,
                sending = false,
                outcome = verdict.outcome,
                awaitingArrival = attempt.clientMessageId.takeIf { verdict.watchesForArrival },
            )
        }
        // Accepted or ambiguous, this channel's window may have moved; Desktop
        // reloads after every settled post for the same reason
        // (`desktop/plugin.js:949-951`). A post that settled after the surface
        // left does not get to break rule 2 — the resume edge reloads anyway.
        if (resumed.value) refreshVisiblePane()
    }

    private fun updateSend(channelId: String, edit: (RelaySendState) -> RelaySendState) {
        sends.update { current ->
            val next = edit(current[channelId] ?: RelaySendState())
            // A channel with nothing typed, nothing pending and nothing to say
            // leaves the map rather than accumulating in it.
            if (next == RelaySendState()) current - channelId else current + (channelId to next)
        }
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
        retireSettledConflict(channelId, answer.messages)
        data.update { current ->
            val pending = current.acknowledged[channelId]
            // Nothing is waiting on almost every tick, and reconciling nothing
            // should cost nothing: no id set, no list copy.
            if (pending.isNullOrEmpty()) return@update current.copy(messages = answer, stale = false)
            // Reconcile by id, so exactly one copy of an acknowledged row
            // survives: the window's, as soon as the window has it.
            val carried = answer.messages.mapTo(HashSet()) { it.id }
            val waiting = pending.filterNot { it.id in carried }
            current.copy(
                messages = answer,
                stale = false,
                acknowledged = if (waiting.isEmpty()) {
                    current.acknowledged - channelId
                } else {
                    current.acknowledged + (channelId to waiting)
                },
            )
        }
    }

    /**
     * Retire a conflict the window has now answered.
     *
     * A conflict says Relay is already holding a message under this attempt's
     * id, and the composer says so as a failure because at that moment the app
     * cannot see the thing being claimed. The next window can: a row carrying
     * that `clientMessageId` is the claim proved, and a warning about a message
     * the person is now looking at is only noise. The draft is deliberately
     * left alone — a poll never deletes what someone typed.
     *
     * Costs nothing on the ticks that matter: no conflict outstanding, no scan.
     */
    private fun retireSettledConflict(channelId: String, window: List<RelayMessage>) {
        val awaited = sends.value[channelId]?.awaitingArrival ?: return
        if (window.none { it.clientMessageId == awaited }) return
        updateSend(channelId) { send ->
            // Only if it is still the same claim: anything the person has done
            // since owns the slot.
            if (send.awaitingArrival != awaited) send else send.copy(outcome = null, awaitingArrival = null)
        }
    }

    /** Hold one acknowledged row until the poll carries it. Never a duplicate. */
    private fun RelayData.acknowledging(channelId: String, message: RelayMessage): RelayData {
        val waiting = acknowledged[channelId].orEmpty()
        if (waiting.any { it.id == message.id }) return this
        return copy(acknowledged = acknowledged + (channelId to (waiting + message)))
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
        // The window Relay returned, plus any row it acknowledged to this app
        // that the window has not caught up with. `relayTranscriptRows` orders
        // by the hub's own `seq`, so an appended row lands where Relay put it
        // rather than merely at the end.
        val window = current.messages?.let { history ->
            val pending = current.acknowledged[current.selectedChannelId]
            // The common case is nothing to merge, and it must not pay for a
            // set of 50 ids plus a copy of the window to discover that.
            if (pending.isNullOrEmpty()) return@let history.messages
            val carried = history.messages.mapTo(HashSet()) { it.id }
            history.messages + pending.filterNot { it.id in carried }
        }
        return RelayDisplay(
            channels = current.channels?.let { relayChannelRows(it, language, times) },
            selectedChannelId = current.selectedChannelId,
            selectedChannelTitle = selected?.title,
            // Either source is enough, exactly as Desktop treats them
            // (`desktop/plugin.js:528,1087`). The channel row is a snapshot
            // from the last channels poll and that poll stops while a
            // transcript is open, so the window's own answer — when a plugin
            // sends one — is the fresher of the two.
            selectedChannelArchived = selected?.archived == true || current.messages?.archived == true,
            transcript = window?.let { relayTranscriptRows(it, language, times) },
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
            poster: RelayPoster,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RelayViewModel(
                availability = availability,
                refreshAvailability = refreshAvailability,
                reader = reader,
                poster = poster,
            ) as T
        }
    }
}
