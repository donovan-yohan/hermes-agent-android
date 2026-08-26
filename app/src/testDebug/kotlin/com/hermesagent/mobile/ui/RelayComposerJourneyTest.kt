package com.hermesagent.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.hermesagent.mobile.data.relay.RelayAvailability
import com.hermesagent.mobile.data.relay.RelayAvailabilityState
import com.hermesagent.mobile.data.relay.RelayChannel
import com.hermesagent.mobile.data.relay.RelayChannelsStatus
import com.hermesagent.mobile.data.relay.RelayHistory
import com.hermesagent.mobile.data.relay.RelayLaneState
import com.hermesagent.mobile.data.relay.RelayMessage
import com.hermesagent.mobile.data.relay.RelayMessageFormat
import com.hermesagent.mobile.data.relay.RelayPostResult
import com.hermesagent.mobile.ui.relay.AUTH_HINT
import com.hermesagent.mobile.ui.relay.COMPOSER_FIELD_TAG
import com.hermesagent.mobile.ui.relay.CONFLICT_MESSAGE
import com.hermesagent.mobile.ui.relay.RelayChannelReader
import com.hermesagent.mobile.ui.relay.RelayPoster
import com.hermesagent.mobile.ui.relay.RelayScreen
import com.hermesagent.mobile.ui.relay.RelayViewModel
import com.hermesagent.mobile.ui.relay.SEND_OUTCOME_TAG
import com.hermesagent.mobile.ui.relay.SEND_TAG
import com.hermesagent.mobile.ui.relay.TRANSCRIPT_TAG
import com.hermesagent.mobile.ui.relay.RETRY_SEND_TAG
import com.hermesagent.mobile.ui.relay.SEND_GATEWAYS_TAG
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesSpacing
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The composer as a person walks it, driven by the real [RelayViewModel] rather
 * than by a state stub.
 *
 * That is the whole point of this class: the acceptance for this slice is about
 * *what goes on the wire across two taps*, and a harness that re-implements the
 * retry policy in order to assert it proves nothing. Only the transport is
 * faked, so every id here is one the production policy actually chose.
 *
 * The poll never fires — `wait` suspends forever — so nothing in these
 * assertions depends on a tick landing between two clicks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelayComposerJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    private val availability = MutableStateFlow(RelayAvailabilityState(READY_LANE))
    private val reader = FakeReader()
    private val poster = FakePoster()
    private var openedGateways = 0

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `typing and sending posts the draft and paints the acknowledged row`() {
        launch()
        openChannel()

        compose.onNodeWithTag(COMPOSER_FIELD_TAG).performTextInput("parity is green")
        compose.onNodeWithTag(SEND_TAG).performClick()
        compose.waitForIdle()

        val post = poster.posts.single()
        assertEquals("product", post.channelId)
        assertEquals("parity is green", post.text)
        assertEquals(RelayMessageFormat.MARKDOWN, post.format)

        // The acknowledged row is on screen without a poll having run.
        compose.onNodeWithText("parity is green").assertIsDisplayed()
        // And the field is empty again, so the next thing typed is a new message.
        compose.onNodeWithText("Write a message…").assertIsDisplayed()
    }

    @Test
    fun `an unconfirmed send offers a retry that re-sends the identical id`() {
        poster.answer = { RelayPostResult.Failed(0, "unreachable", retryable = true) }
        launch()
        openChannel()

        compose.onNodeWithTag(COMPOSER_FIELD_TAG).performTextInput("parity is green")
        compose.onNodeWithTag(SEND_TAG).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(SEND_OUTCOME_TAG).assertIsDisplayed()
        compose.onNodeWithTag(RETRY_SEND_TAG).performClick()
        compose.waitForIdle()

        assertEquals(2, poster.posts.size)
        assertEquals(poster.posts[0].clientMessageId, poster.posts[1].clientMessageId)
        assertEquals(poster.posts[0].text, poster.posts[1].text)
    }

    @Test
    fun `a conflict keeps the draft, offers no retry, and the next message gets a new id`() {
        poster.answer = { RelayPostResult.Failed(409, "conflict") }
        launch()
        openChannel()

        compose.onNodeWithTag(COMPOSER_FIELD_TAG).performTextInput("parity is green")
        compose.onNodeWithTag(SEND_TAG).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(CONFLICT_MESSAGE).assertIsDisplayed()
        // The one retry the contract forbids is not offered.
        assertTrue(compose.onAllNodesWithTag(RETRY_SEND_TAG).fetchSemanticsNodes().isEmpty())
        // The refused message is still in the field, ready to be sent again on
        // purpose — nothing here says it was delivered.
        compose.onNodeWithText("parity is green").assertIsDisplayed()

        poster.answer = { null }
        compose.onNodeWithTag(SEND_TAG).performClick()
        compose.waitForIdle()

        assertEquals(2, poster.posts.size)
        assertEquals(poster.posts[0].text, poster.posts[1].text)
        assertNotEquals(poster.posts[0].clientMessageId, poster.posts[1].clientMessageId)
    }

    @Test
    fun `the host's own Relay credential is not a reconnect this device can offer`() {
        poster.answer = {
            RelayPostResult.Failed(401, "refused", code = "auth_required")
        }
        launch()
        openChannel()

        compose.onNodeWithTag(COMPOSER_FIELD_TAG).performTextInput("parity is green")
        compose.onNodeWithTag(SEND_TAG).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(AUTH_HINT).assertIsDisplayed()
        // Reconnecting this device would re-authenticate something that was
        // never the problem, so the action is not offered at all.
        assertTrue(compose.onAllNodesWithTag(SEND_GATEWAYS_TAG).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a refused credential points at Gateways instead of at a retry`() {
        poster.answer = { RelayPostResult.Failed(401, "refused") }
        launch()
        openChannel()

        compose.onNodeWithTag(COMPOSER_FIELD_TAG).performTextInput("parity is green")
        compose.onNodeWithTag(SEND_TAG).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(SEND_GATEWAYS_TAG).performClick()
        assertEquals(1, openedGateways)
    }

    @Test
    fun `an empty draft cannot be sent and reaches no transport`() {
        launch()
        openChannel()

        compose.onNodeWithTag(SEND_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(SEND_TAG).performClick()
        compose.waitForIdle()

        assertTrue(poster.posts.isEmpty())

        // Whitespace is not text either, and still nothing is dispatched.
        compose.onNodeWithTag(COMPOSER_FIELD_TAG).performTextInput("   ")
        compose.onNodeWithTag(SEND_TAG).assertIsNotEnabled()
        assertTrue(poster.posts.isEmpty())
    }

    @Test
    fun `Enter inserts a newline instead of sending`() {
        launch()
        openChannel()

        compose.onNodeWithTag(COMPOSER_FIELD_TAG).performTextInput("first\n")
        compose.waitForIdle()
        // The return key is a return key: nothing left for Relay.
        assertTrue(poster.posts.isEmpty())

        compose.onNodeWithTag(COMPOSER_FIELD_TAG).performTextInput("second")
        compose.onNodeWithTag(SEND_TAG).performClick()
        compose.waitForIdle()

        assertEquals("first\nsecond", poster.posts.single().text)
    }

    @Test
    fun `the send control is a 48dp target with a spoken label`() {
        launch()
        openChannel()

        // Both dimensions: a control 48dp tall and 20dp wide is not a 48dp
        // target, and a height-only assertion cannot tell the two apart.
        compose.onNodeWithTag(SEND_TAG).assertHeightIsAtLeast(HermesSpacing().touchTarget)
        compose.onNodeWithTag(SEND_TAG).assertWidthIsAtLeast(HermesSpacing().touchTarget)
        compose.onNodeWithContentDescription("Send message").assertIsDisplayed()
        compose.onNodeWithContentDescription("Relay message").assertIsDisplayed()
    }

    @Test
    fun `an archived channel explains itself instead of accepting a post`() {
        reader.archived = true
        launch()
        compose.onNodeWithTag("Relay channel archive").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("This channel is archived.").assertIsDisplayed()
        compose.onNodeWithTag(SEND_TAG).assertIsNotEnabled()
    }

    @Test
    fun `a lane that stops being ready closes the composer and keeps the draft`() {
        launch()
        openChannel()
        compose.onNodeWithTag(COMPOSER_FIELD_TAG).performTextInput("parity is green")

        availability.value = RelayAvailabilityState(lane(RelayLaneState.OFFLINE))
        compose.waitForIdle()

        compose.onNodeWithTag(SEND_TAG).assertIsNotEnabled()
        // Exactly what the hint beside it promises.
        compose.onNodeWithText("parity is green").assertIsDisplayed()
        compose.onNodeWithText("Relay is offline. Your draft is kept until it reconnects.")
            .assertIsDisplayed()
    }

    @Test
    fun `a message you sent takes you to it even after reading backwards`() {
        reader.messageCount = 40
        launch()
        openChannel()

        // Read back up the transcript, the way a person does.
        compose.onNodeWithTag(TRANSCRIPT_TAG).performTouchInput { swipeDown() }
        compose.waitForIdle()

        compose.onNodeWithTag(COMPOSER_FIELD_TAG).performTextInput("parity is green")
        compose.onNodeWithTag(SEND_TAG).performClick()
        compose.waitForIdle()

        // Someone else's message must not yank a reader back; your own must.
        compose.onNodeWithText("parity is green").assertIsDisplayed()
    }

    private fun openChannel() {
        compose.onNodeWithTag("Relay channel product").performClick()
        compose.waitForIdle()
    }

    private fun launch() {
        compose.setContent {
            val viewModel = remembered()
            val state by viewModel.uiState.collectAsState()
            HermesTheme(AppearanceSelection()) {
                RelayScreen(
                    state = state,
                    actions = RelayActions(
                        onSelectChannel = viewModel::selectChannel,
                        onClearSelection = viewModel::clearSelection,
                        onRetry = viewModel::retry,
                        onDraftChange = viewModel::setDraft,
                        onSend = viewModel::sendDraft,
                        onRetrySend = viewModel::retrySend,
                        onResume = viewModel::surfaceResumed,
                        onPause = viewModel::surfacePaused,
                    ),
                    onLeave = {},
                    onOpenGateways = { openedGateways++ },
                )
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun remembered(): RelayViewModel = remember {
        RelayViewModel(
            availability = availability,
            refreshAvailability = {},
            reader = reader,
            poster = poster,
            // The tick is not what is under test, and a timer firing between
            // two clicks is exactly the flake this class must not have.
            wait = { awaitCancellation() },
        )
    }

    private class FakeReader : RelayChannelReader {
        var archived = false
        /** Enough rows to push the tail off screen when a test needs to scroll. */
        var messageCount = 2

        override suspend fun channels(): List<RelayChannel> = listOf(
            channel("product", archived = false),
            channel("archive", archived = true),
        )

        override suspend fun history(channelId: String, limit: Int) = RelayHistory(
            messages = (messageCount downTo 1).map { seq -> message(channelId, seq.toLong()) },
            hasMore = false,
            nextCursorBeforeSeq = null,
            nextCursorAfterSeq = null,
            // Only ever set by a test that is asking what happens when the
            // window itself says the channel is closed.
            archived = true.takeIf { archived && channelId == "archive" },
        )
    }

    private class FakePoster : RelayPoster {
        val posts = mutableListOf<Dispatched>()
        var answer: () -> RelayPostResult? = { null }

        /**
         * Past anything the reader's window carries, so an accepted row is
         * genuinely new. A seq that collided with a polled row would be
         * reconciled away as a duplicate and quietly prove nothing.
         */
        private var seq = 1_000L

        override suspend fun post(
            channelId: String,
            text: String,
            format: RelayMessageFormat,
            clientMessageId: String,
        ): RelayPostResult {
            posts += Dispatched(channelId, text, format, clientMessageId)
            answer()?.let { return it }
            return RelayPostResult.Accepted(
                message(channelId, ++seq).copy(text = text, clientMessageId = clientMessageId),
            )
        }

        data class Dispatched(
            val channelId: String,
            val text: String,
            val format: RelayMessageFormat,
            val clientMessageId: String,
        )
    }

    private companion object {
        val READY_LANE = lane(RelayLaneState.READY)

        fun lane(state: RelayLaneState) = RelayAvailability.Available(
            RelayChannelsStatus(state, message = null, guidance = null),
        )

        fun channel(id: String, archived: Boolean) = RelayChannel(
            id = id,
            title = id,
            kind = "channel",
            visibility = "public",
            archived = archived,
            latestSeq = 2,
            messageCount = 2,
            threadCount = 0,
            lastMessage = null,
        )

        fun message(channelId: String, seq: Long) = RelayMessage(
            id = "$channelId-$seq",
            channelId = channelId,
            seq = seq,
            kind = "message",
            status = "delivered",
            senderKind = "human",
            senderId = "sender-$seq",
            senderDisplayName = "Ada",
            text = "message $seq",
            format = RelayMessageFormat.MARKDOWN,
            threadId = null,
            parentMessageId = null,
            createdAt = "2026-08-26T09:0$seq:00Z",
            updatedAt = "2026-08-26T09:0$seq:00Z",
            truncated = null,
            clientMessageId = null,
        )
    }
}
