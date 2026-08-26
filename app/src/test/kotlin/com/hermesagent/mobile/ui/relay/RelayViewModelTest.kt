package com.hermesagent.mobile.ui.relay

import com.hermesagent.mobile.data.relay.MAX_HISTORY_LIMIT
import com.hermesagent.mobile.data.relay.RelayAvailability
import com.hermesagent.mobile.data.relay.RelayAvailabilityState
import com.hermesagent.mobile.data.relay.RelayChannel
import com.hermesagent.mobile.data.relay.RelayChannelsStatus
import com.hermesagent.mobile.data.relay.RelayHistory
import com.hermesagent.mobile.data.relay.RelayLaneState
import com.hermesagent.mobile.data.relay.RelayMessage
import com.hermesagent.mobile.data.relay.RelayMessageFormat
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Everything here runs on virtual time. The poll interval is injected, so a
 * loop that reached a real clock would hang these tests rather than pass them
 * three seconds at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val availability = MutableStateFlow(RelayAvailabilityState())
    private val reader = RecordingReader()
    private var refreshes = 0

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a resumed surface loads the visible pane as soon as the lane is ready`() = relayTest { viewModel ->

        viewModel.surfaceResumed()
        runCurrent()
        // Nothing is asked of a Gateway that has not said Relay is ready.
        assertEquals(0, reader.channelCalls)
        assertEquals(1, refreshes)

        becomeReady()
        settle()

        assertEquals(1, reader.channelCalls)
        assertEquals(listOf("general", "builds"), viewModel.uiState.value.channels.map { it.id })
        assertTrue(viewModel.uiState.value.channelsLoaded)
    }

    @Test
    fun `the visible pane refreshes every three seconds and nothing else does`() = relayTest { viewModel ->
        becomeReady()
        viewModel.surfaceResumed()
        settle()
        assertEquals(1, reader.channelCalls)

        tick()
        assertEquals(2, reader.channelCalls)

        tick()
        assertEquals(3, reader.channelCalls)
        // The channels pane is on screen, so the transcript is not being asked
        // for: one request per tick, exactly like Desktop's single interval.
        assertEquals(0, reader.historyCalls)
    }

    @Test
    fun `selecting a channel moves the poll to the transcript`() = relayTest { viewModel ->
        becomeReady()
        viewModel.surfaceResumed()
        settle()

        viewModel.selectChannel("general")
        settle()
        assertEquals(1, reader.historyCalls)
        assertEquals("general", viewModel.uiState.value.selectedChannelId)
        // Oldest to newest, from a window that arrived newest first.
        assertEquals(
            listOf("general-1", "general-2"),
            viewModel.uiState.value.transcript.map { it.id },
        )

        val channelsSoFar = reader.channelCalls
        tick()
        assertEquals(2, reader.historyCalls)
        assertEquals(channelsSoFar, reader.channelCalls)

        viewModel.clearSelection()
        settle()
        assertEquals(channelsSoFar + 1, reader.channelCalls)
        assertTrue(viewModel.uiState.value.transcript.isEmpty())
    }

    @Test
    fun `history asks for the contract's bounded window`() = relayTest { viewModel ->
        becomeReady()
        viewModel.surfaceResumed()
        settle()
        viewModel.selectChannel("general")
        settle()

        assertEquals(listOf(MAX_HISTORY_LIMIT), reader.limits)
    }

    @Test
    fun `a hidden surface stops asking and a resumed one starts again`() = relayTest { viewModel ->
        becomeReady()
        viewModel.surfaceResumed()
        settle()
        val whileVisible = reader.channelCalls

        viewModel.surfacePaused()
        tick(5)
        assertEquals(whileVisible, reader.channelCalls)

        viewModel.surfaceResumed()
        settle()
        assertEquals(whileVisible + 1, reader.channelCalls)
    }

    @Test
    fun `a lane that stops being ready stops the requests without blanking the screen`() = relayTest { viewModel ->
        becomeReady()
        viewModel.surfaceResumed()
        settle()
        val loaded = reader.channelCalls

        availability.value = RelayAvailabilityState(lane(RelayLaneState.OFFLINE))
        tick(3)

        assertEquals(loaded, reader.channelCalls)
        // The rows Relay last returned stay on screen under the offline notice.
        assertEquals(2, viewModel.uiState.value.channels.size)
        assertEquals("Relay is offline", viewModel.uiState.value.notice?.title)
    }

    @Test
    fun `an unusable answer keeps the previous one and says so quietly`() = relayTest { viewModel ->
        becomeReady()
        viewModel.surfaceResumed()
        settle()
        assertFalse(viewModel.uiState.value.stale)

        reader.channelAnswer = null
        tick()

        assertTrue(viewModel.uiState.value.stale)
        assertEquals(2, viewModel.uiState.value.channels.size)
        assertTrue(viewModel.uiState.value.channelsLoaded)
        // A failed refresh is a state on the surface, never an error notice.
        assertEquals(null, viewModel.uiState.value.notice)

        reader.channelAnswer = RecordingReader.CHANNELS
        tick()
        assertFalse(viewModel.uiState.value.stale)
    }

    @Test
    fun `a reader that throws settles as stale rather than tearing down the loop`() = relayTest { viewModel ->
        becomeReady()
        reader.channelThrows = true
        viewModel.surfaceResumed()
        settle()

        assertTrue(viewModel.uiState.value.stale)
        assertFalse(viewModel.uiState.value.channelsLoaded)

        reader.channelThrows = false
        tick()
        assertTrue(viewModel.uiState.value.channelsLoaded)
    }

    @Test
    fun `a window that lands after the person left the channel is dropped`() = relayTest { viewModel ->
        becomeReady()
        viewModel.surfaceResumed()
        settle()

        reader.historyDelayMillis = 5_000
        viewModel.selectChannel("general")
        advanceTimeBy(1_000)
        runCurrent()
        viewModel.clearSelection()
        settle()

        assertTrue(viewModel.uiState.value.transcript.isEmpty())
        assertFalse(viewModel.uiState.value.transcriptLoaded)
    }

    @Test
    fun `retry re-probes availability and reloads the visible pane`() = relayTest { viewModel ->
        becomeReady()
        viewModel.surfaceResumed()
        settle()
        val before = reader.channelCalls

        viewModel.retry()
        settle()

        assertEquals(2, refreshes)
        assertEquals(before + 1, reader.channelCalls)
    }

    @Test
    fun `a manual look restarts the cadence instead of stacking on it`() = relayTest { viewModel ->
        becomeReady()
        viewModel.surfaceResumed()
        settle()
        assertEquals(1, reader.channelCalls)

        // Two seconds into the interval, someone taps Try again.
        advanceTimeBy(2_000)
        runCurrent()
        viewModel.retry()
        settle()
        assertEquals(2, reader.channelCalls)

        // The tick that was one second away must not fire: the pane was just
        // looked at, so the next look is three seconds from that.
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(2, reader.channelCalls)

        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(3, reader.channelCalls)
    }

    @Test
    fun `a Gateway without the plugin says so at the entry point`() = relayTest { viewModel ->
        availability.value = RelayAvailabilityState(RelayAvailability.Missing)
        viewModel.surfaceResumed()
        settle()

        assertTrue(viewModel.uiState.value.unavailableOnGateway)
        assertFalse(viewModel.uiState.value.relayAnswered)
        assertFalse(viewModel.uiState.value.showsContent)
        assertEquals(0, reader.channelCalls)
    }

    /**
     * One Relay ViewModel on the test scheduler, with a live `uiState`
     * collector and a guaranteed stop.
     *
     * Both halves are load-bearing. `uiState` is `combine` +
     * `WhileSubscribed`, so without a collector every assertion reads the
     * initial value. And the poll loop is deliberately endless while the
     * surface is visible, so a test that ended without stopping it would leave
     * a timed task on the scheduler and hang `runTest`'s own teardown.
     */
    private fun relayTest(body: suspend TestScope.(RelayViewModel) -> Unit) = runTest(dispatcher) {
        val viewModel = RelayViewModel(
            availability = availability,
            refreshAvailability = { refreshes++ },
            reader = reader,
            clock = { NOW },
            zone = { ZoneId.of("UTC") },
            locale = { Locale.UK },
            wait = { millis -> delay(millis) },
        )
        backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()
        try {
            body(viewModel)
        } finally {
            viewModel.surfacePaused()
        }
    }

    /**
     * The poll loop is deliberately endless while the surface is visible, so
     * `advanceUntilIdle` would spin the virtual clock forever. Every wait here
     * is therefore an explicit tick.
     */
    private fun TestScope.settle() {
        runCurrent()
    }

    private fun TestScope.tick(count: Int = 1) {
        repeat(count) {
            advanceTimeBy(RelayViewModel.POLL_INTERVAL_MILLIS)
            runCurrent()
        }
    }

    private fun becomeReady() {
        availability.value = RelayAvailabilityState(lane(RelayLaneState.READY))
    }

    private companion object {
        const val NOW = 1_787_745_600_000L

        fun lane(state: RelayLaneState) = RelayAvailability.Available(
            RelayChannelsStatus(state, message = null, guidance = null),
        )
    }
}

/** Answers a fixed script and counts what was asked of it. */
private class RecordingReader : RelayChannelReader {
    var channelAnswer: List<RelayChannel>? = CHANNELS
    var channelThrows = false
    var historyDelayMillis = 0L
    var channelCalls = 0
        private set
    var historyCalls = 0
        private set
    val limits = mutableListOf<Int>()

    override suspend fun channels(): List<RelayChannel>? {
        channelCalls++
        if (channelThrows) error("the transport blew up in a way nobody predicted")
        return channelAnswer
    }

    override suspend fun history(channelId: String, limit: Int): RelayHistory? {
        historyCalls++
        limits += limit
        if (historyDelayMillis > 0) delay(historyDelayMillis)
        // Newest first, as the frozen contract returns it.
        return RelayHistory(
            messages = listOf(message(channelId, 2), message(channelId, 1)),
            hasMore = false,
            nextCursorBeforeSeq = null,
            nextCursorAfterSeq = null,
        )
    }

    companion object {
        val CHANNELS = listOf(
            RelayChannel(
                id = "general",
                title = "general",
                kind = "channel",
                visibility = "public",
                archived = false,
                latestSeq = 2,
                messageCount = 2,
                threadCount = 0,
                lastMessage = null,
            ),
            RelayChannel(
                id = "builds",
                title = "builds",
                kind = null,
                visibility = null,
                archived = null,
                latestSeq = null,
                messageCount = null,
                threadCount = null,
                lastMessage = null,
            ),
        )

        fun message(channelId: String, seq: Long) = RelayMessage(
            id = "$channelId-$seq",
            channelId = channelId,
            seq = seq,
            kind = "message",
            status = "delivered",
            senderKind = "human",
            senderId = "sender-$seq",
            senderDisplayName = null,
            text = "message $seq",
            format = RelayMessageFormat.TEXT,
            threadId = null,
            parentMessageId = null,
            createdAt = "2026-08-26T09:0$seq:00Z",
            updatedAt = "2026-08-26T09:0$seq:00Z",
            truncated = null,
            clientMessageId = null,
        )
    }
}
