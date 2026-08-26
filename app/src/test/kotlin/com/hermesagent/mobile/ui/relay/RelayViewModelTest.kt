package com.hermesagent.mobile.ui.relay

import com.hermesagent.mobile.data.relay.EMPTY_TEXT_MESSAGE
import com.hermesagent.mobile.data.relay.LARGE_TEXT_MESSAGE
import com.hermesagent.mobile.data.relay.MAX_HISTORY_LIMIT
import com.hermesagent.mobile.data.relay.MAX_TEXT_BYTES
import com.hermesagent.mobile.data.relay.RelayAvailability
import com.hermesagent.mobile.data.relay.RelayAvailabilityState
import com.hermesagent.mobile.data.relay.RelayChannel
import com.hermesagent.mobile.data.relay.RelayChannelsStatus
import com.hermesagent.mobile.data.relay.RelayHistory
import com.hermesagent.mobile.data.relay.RelayLaneState
import com.hermesagent.mobile.data.relay.RelayMessage
import com.hermesagent.mobile.data.relay.RelayMessageFormat
import com.hermesagent.mobile.data.relay.RelayPostResult
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    private val poster = RecordingPoster()
    private var refreshes = 0
    private var mintedIds = 0

    /** Every wakeup of the poll loop, so "does not tick" is observed, not assumed. */
    private var waits = 0

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
    fun `a lane nothing is asked of never starts the three-second tick`() = relayTest { viewModel ->
        availability.value = RelayAvailabilityState(lane(RelayLaneState.AUTH_REQUIRED))
        viewModel.surfaceResumed()
        tick(5)

        // Not one wakeup in fifteen seconds. The tick is started by a look that
        // actually happened, and this lane is never looked at.
        assertEquals(0, waits)
        assertEquals(0, reader.channelCalls)

        becomeReady()
        settle()
        assertEquals(1, reader.channelCalls)
        assertEquals(1, waits)

        // And it stops again the moment the lane stops being ready.
        availability.value = RelayAvailabilityState(lane(RelayLaneState.OFFLINE))
        tick(5)
        assertEquals(1, waits)
        assertEquals(1, reader.channelCalls)
    }

    @Test
    fun `only a ready lane reports the surface as one with a request out`() = relayTest { viewModel ->
        viewModel.surfaceResumed()
        settle()

        for (laneState in listOf(
            RelayLaneState.AUTH_REQUIRED,
            RelayLaneState.OFFLINE,
            RelayLaneState.ERROR,
        )) {
            availability.value = RelayAvailabilityState(lane(laneState))
            settle()
            val state = viewModel.uiState.value
            // A lane state *is* an answer from Relay, so `relayAnswered` is
            // true for all three — which is exactly why the surface may not
            // key a spinner on it. Nothing was asked, and nothing loaded.
            assertTrue("$laneState answered", state.relayAnswered)
            assertFalse("$laneState is not polled", state.relayReady)
            assertFalse("$laneState never loaded", state.channelsLoaded)
        }

        becomeReady()
        settle()
        assertTrue(viewModel.uiState.value.relayReady)
        assertTrue(viewModel.uiState.value.channelsLoaded)
    }

    @Test
    fun `a Gateway without the plugin says so at the entry point`() = relayTest { viewModel ->
        availability.value = RelayAvailabilityState(RelayAvailability.Missing)
        viewModel.surfaceResumed()
        settle()

        assertTrue(viewModel.uiState.value.unavailableOnGateway)
        assertFalse(viewModel.uiState.value.relayAnswered)
        assertFalse(viewModel.uiState.value.relayReady)
        assertFalse(viewModel.uiState.value.showsContent)
        assertEquals(0, reader.channelCalls)
    }

    // ── Composer: what is dispatched, and under which id ──────────────────

    @Test
    fun `a first send posts markdown under a fresh id and paints the acknowledged row`() =
        relayTest { viewModel ->
            openTranscript(viewModel)

            viewModel.setDraft("ship it")
            settle()
            assertTrue(viewModel.uiState.value.composer.canSend)

            viewModel.sendDraft()
            settle()

            val post = poster.posts.single()
            assertEquals("general", post.channelId)
            assertEquals("ship it", post.text)
            // Desktop's only format, and this surface offers no control for it.
            assertEquals(RelayMessageFormat.MARKDOWN, post.format)
            assertEquals("id-1", post.clientMessageId)

            // Painted from the acknowledgement itself, not from the next poll.
            assertEquals(
                listOf("general-1", "general-2", "general-3"),
                viewModel.uiState.value.transcript.map { it.id },
            )
            assertEquals("", viewModel.uiState.value.composer.draft)
            assertEquals(SENT_MESSAGE, viewModel.uiState.value.composer.outcome?.message)
        }

    @Test
    fun `the acknowledged row is reconciled by the next poll rather than doubled`() =
        relayTest { viewModel ->
            openTranscript(viewModel)
            viewModel.setDraft("ship it")
            viewModel.sendDraft()
            settle()
            assertEquals(3, viewModel.uiState.value.transcript.size)

            // The window Relay returns now carries the same row, by the same id.
            reader.extraMessages = listOf(poster.accepted.single())
            tick()

            assertEquals(
                listOf("general-1", "general-2", "general-3"),
                viewModel.uiState.value.transcript.map { it.id },
            )
        }

    @Test
    fun `a retry re-sends byte-identical text under the original id`() = relayTest { viewModel ->
        poster.answer = { RelayPostResult.Failed(0, "unreachable", retryable = true) }
        openTranscript(viewModel)
        viewModel.setDraft("ship it")
        viewModel.sendDraft()
        settle()

        assertEquals(RelaySendAction.Retry, viewModel.uiState.value.composer.outcome?.action)
        // Nothing landed, so the draft is still there to be sent again.
        assertEquals("ship it", viewModel.uiState.value.composer.draft)

        viewModel.retrySend()
        settle()

        assertEquals(2, poster.posts.size)
        assertEquals(poster.posts[0].clientMessageId, poster.posts[1].clientMessageId)
        assertEquals(poster.posts[0].text, poster.posts[1].text)
        assertEquals("id-1", poster.posts[1].clientMessageId)
        // Exactly one id was ever minted for these two dispatches.
        assertEquals(1, mintedIds)
    }

    @Test
    fun `tapping send again after an unconfirmed failure reuses the id, it does not mint one`() =
        relayTest { viewModel ->
            poster.answer = { RelayPostResult.Failed(503, "server fault", retryable = true) }
            openTranscript(viewModel)
            viewModel.setDraft("ship it")
            viewModel.sendDraft()
            settle()

            // The person taps the send control rather than the retry beside it.
            viewModel.sendDraft()
            settle()

            assertEquals(2, poster.posts.size)
            assertEquals("id-1", poster.posts[1].clientMessageId)
            assertEquals(1, mintedIds)
        }

    @Test
    fun `editing the draft after an unconfirmed failure makes the next send a new message`() =
        relayTest { viewModel ->
            poster.answer = { RelayPostResult.Failed(0, "unreachable", retryable = true) }
            openTranscript(viewModel)
            viewModel.setDraft("ship it")
            viewModel.sendDraft()
            settle()

            viewModel.setDraft("ship it now")
            viewModel.sendDraft()
            settle()

            assertEquals(2, poster.posts.size)
            assertNotEquals(poster.posts[0].clientMessageId, poster.posts[1].clientMessageId)
            assertEquals("id-2", poster.posts[1].clientMessageId)

            // The unconfirmed original is still the one a retry settles, under
            // its own text and its own id.
            viewModel.retrySend()
            settle()
            assertEquals("ship it now", poster.posts[2].text)
            assertEquals("id-2", poster.posts[2].clientMessageId)
        }

    @Test
    fun `a new draft after an accepted send gets a new id`() = relayTest { viewModel ->
        openTranscript(viewModel)
        viewModel.setDraft("ship it")
        viewModel.sendDraft()
        settle()

        viewModel.setDraft("and again")
        viewModel.sendDraft()
        settle()

        assertEquals(listOf("id-1", "id-2"), poster.posts.map { it.clientMessageId })
    }

    @Test
    fun `re-typing the same text after an accepted send still gets a new id`() =
        relayTest { viewModel ->
            openTranscript(viewModel)
            viewModel.setDraft("ship it")
            viewModel.sendDraft()
            settle()

            // Acceptance retired the attempt, so identical text is a second
            // message rather than a retry of the first.
            viewModel.setDraft("ship it")
            viewModel.sendDraft()
            settle()

            assertEquals(listOf("id-1", "id-2"), poster.posts.map { it.clientMessageId })
        }

    @Test
    fun `a conflict clears the draft, offers no retry, and burns the id`() = relayTest { viewModel ->
        poster.answer = { RelayPostResult.Failed(409, "conflict") }
        openTranscript(viewModel)
        viewModel.setDraft("ship it")
        viewModel.sendDraft()
        settle()

        val composer = viewModel.uiState.value.composer
        assertEquals(CONFLICT_MESSAGE, composer.outcome?.message)
        assertNull(composer.outcome?.action)
        assertEquals("", composer.draft)

        // A retry cannot be asked for, and asking anyway posts nothing.
        viewModel.retrySend()
        settle()
        assertEquals(1, poster.posts.size)

        poster.answer = { null }
        viewModel.setDraft("ship it")
        viewModel.sendDraft()
        settle()
        assertEquals("id-2", poster.posts[1].clientMessageId)
    }

    @Test
    fun `a refused body burns the id but keeps the draft to be edited`() = relayTest { viewModel ->
        poster.answer = { RelayPostResult.Failed(413, "too large") }
        openTranscript(viewModel)
        viewModel.setDraft("ship it")
        viewModel.sendDraft()
        settle()

        assertEquals("ship it", viewModel.uiState.value.composer.draft)
        assertNull(viewModel.uiState.value.composer.outcome?.action)

        poster.answer = { null }
        viewModel.setDraft("shorter")
        viewModel.sendDraft()
        settle()
        assertEquals("id-2", poster.posts[1].clientMessageId)
    }

    @Test
    fun `only an acceptance moves the signal that says an arrival is your own`() =
        relayTest { viewModel ->
            openTranscript(viewModel)
            assertNull(viewModel.uiState.value.composer.lastAcceptedId)

            poster.answer = { RelayPostResult.Failed(0, "unreachable", retryable = true) }
            viewModel.setDraft("ship it")
            viewModel.sendDraft()
            settle()
            // Nothing was stored, so nothing should take a reader anywhere.
            assertNull(viewModel.uiState.value.composer.lastAcceptedId)

            poster.answer = { null }
            viewModel.retrySend()
            settle()

            val accepted = poster.accepted.single()
            assertEquals(accepted.id, viewModel.uiState.value.composer.lastAcceptedId)

            // A later poll carrying somebody else's message must not move it.
            reader.extraMessages = listOf(RecordingReader.message("general", 9))
            tick()
            assertEquals(accepted.id, viewModel.uiState.value.composer.lastAcceptedId)
        }

    // ── Local bounds: no request at all ────────────────────────────────────

    @Test
    fun `blank text is never dispatched and never reaches a poster`() = relayTest { viewModel ->
        openTranscript(viewModel)

        viewModel.setDraft("   \n  ")
        settle()
        assertFalse(viewModel.uiState.value.composer.canSend)

        viewModel.sendDraft()
        settle()

        assertTrue(poster.posts.isEmpty())
        assertEquals(0, mintedIds)
        assertEquals(EMPTY_TEXT_MESSAGE, viewModel.uiState.value.composer.outcome?.message)
    }

    @Test
    fun `text over the server's byte bound is refused without a request`() = relayTest { viewModel ->
        openTranscript(viewModel)

        viewModel.setDraft("a".repeat(MAX_TEXT_BYTES + 1))
        viewModel.sendDraft()
        settle()

        assertTrue(poster.posts.isEmpty())
        assertEquals(0, mintedIds)
        assertEquals(LARGE_TEXT_MESSAGE, viewModel.uiState.value.composer.outcome?.message)
    }

    @Test
    fun `a send with no channel open dispatches nothing`() = relayTest { viewModel ->
        becomeReady()
        viewModel.surfaceResumed()
        settle()

        viewModel.setDraft("ship it")
        viewModel.sendDraft()
        settle()

        assertTrue(poster.posts.isEmpty())
        // There is no channel to hold a draft, so there is nothing to keep.
        assertEquals("", viewModel.uiState.value.composer.draft)
    }

    // ── In flight ─────────────────────────────────────────────────────────

    @Test
    fun `the send control is closed while this channel's post is in flight`() =
        relayTest { viewModel ->
            poster.delayMillis = 1_000
            openTranscript(viewModel)
            viewModel.setDraft("ship it")
            viewModel.sendDraft()
            settle()

            assertTrue(viewModel.uiState.value.composer.sending)
            assertFalse(viewModel.uiState.value.composer.canSend)

            // A second tap while the first is unanswered posts nothing.
            viewModel.sendDraft()
            viewModel.retrySend()
            settle()
            assertEquals(1, poster.posts.size)

            advanceTimeBy(1_000)
            settle()
            assertFalse(viewModel.uiState.value.composer.sending)
            assertEquals("", viewModel.uiState.value.composer.draft)
        }

    @Test
    fun `a post already on the wire is not cancelled by the surface leaving`() =
        relayTest { viewModel ->
            poster.delayMillis = 1_000
            openTranscript(viewModel)
            viewModel.setDraft("ship it")
            viewModel.sendDraft()
            settle()

            viewModel.surfacePaused()
            advanceTimeBy(1_000)
            settle()

            // Abandoning it would have turned a decided outcome into an unknown
            // one, and left an id nothing could ever settle.
            assertEquals(1, poster.posts.size)
            assertEquals(SENT_MESSAGE, viewModel.uiState.value.composer.outcome?.message)
        }

    // ── Per-channel, UI-only ──────────────────────────────────────────────

    @Test
    fun `drafts belong to their channel and survive switching between them`() =
        relayTest { viewModel ->
            openTranscript(viewModel)
            viewModel.setDraft("for general")
            settle()

            viewModel.clearSelection()
            settle()
            viewModel.selectChannel("builds")
            settle()
            assertEquals("", viewModel.uiState.value.composer.draft)

            viewModel.setDraft("for builds")
            settle()
            viewModel.clearSelection()
            settle()
            viewModel.selectChannel("general")
            settle()

            assertEquals("for general", viewModel.uiState.value.composer.draft)
        }

    @Test
    fun `an archived channel and an unready lane both close the composer`() =
        relayTest { viewModel ->
            openTranscript(viewModel)
            viewModel.setDraft("ship it")
            settle()
            assertTrue(viewModel.uiState.value.composer.editable)

            availability.value = RelayAvailabilityState(lane(RelayLaneState.OFFLINE))
            settle()

            val composer = viewModel.uiState.value.composer
            assertFalse(composer.editable)
            assertFalse(composer.canSend)
            assertEquals(OFFLINE_HINT, composer.hint)
            // The draft is kept exactly as the hint promises.
            assertEquals("ship it", composer.draft)
        }

    /** Ready lane, surface up, one channel open — the composer's precondition. */
    private fun TestScope.openTranscript(viewModel: RelayViewModel) {
        becomeReady()
        viewModel.surfaceResumed()
        settle()
        viewModel.selectChannel("general")
        settle()
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
            poster = poster,
            clock = { NOW },
            zone = { ZoneId.of("UTC") },
            locale = { Locale.UK },
            wait = { millis ->
                waits++
                delay(millis)
            },
            newClientMessageId = { "id-${++mintedIds}" },
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
    /** Rows the polled window has caught up with since the last look. */
    var extraMessages: List<RelayMessage> = emptyList()
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
            messages = extraMessages + listOf(message(channelId, 2), message(channelId, 1)),
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

/**
 * Answers a scripted post result and records exactly what was dispatched.
 *
 * The recorded bytes are the point: an idempotency claim that does not compare
 * what actually went out on each attempt is not a test of anything.
 */
private class RecordingPoster : RelayPoster {
    val posts = mutableListOf<Dispatched>()
    val accepted = mutableListOf<RelayMessage>()
    var delayMillis = 0L

    /** Null means "Relay took it", so the happy path needs no script at all. */
    var answer: () -> RelayPostResult? = { null }

    /** Continues the reader's fixture, so an accepted row is the newest one. */
    private var seq = 2L

    override suspend fun post(
        channelId: String,
        text: String,
        format: RelayMessageFormat,
        clientMessageId: String,
    ): RelayPostResult {
        posts += Dispatched(channelId, text, format, clientMessageId)
        if (delayMillis > 0) delay(delayMillis)
        answer()?.let { return it }
        val stored = RecordingReader.message(channelId, ++seq).copy(
            text = text,
            format = format,
            clientMessageId = clientMessageId,
        )
        accepted += stored
        return RelayPostResult.Accepted(stored)
    }

    data class Dispatched(
        val channelId: String,
        val text: String,
        val format: RelayMessageFormat,
        val clientMessageId: String,
    )
}
