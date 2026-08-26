package com.hermesagent.mobile.ui.relay

import com.hermesagent.mobile.data.relay.EMPTY_TEXT_MESSAGE
import com.hermesagent.mobile.data.relay.LARGE_TEXT_MESSAGE
import com.hermesagent.mobile.data.relay.MAX_ID_BYTES
import com.hermesagent.mobile.data.relay.MAX_TEXT_BYTES
import com.hermesagent.mobile.data.relay.PICK_CHANNEL_MESSAGE
import com.hermesagent.mobile.data.relay.RelayAvailability
import com.hermesagent.mobile.data.relay.RelayChannelsStatus
import com.hermesagent.mobile.data.relay.RelayLaneState
import com.hermesagent.mobile.data.relay.RelayMessage
import com.hermesagent.mobile.data.relay.RelayMessageFormat
import com.hermesagent.mobile.data.relay.RelayPostResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer's policy without a ViewModel around it: what may be sent, what
 * a settled post means for the draft, and — the part exactly-once turns on —
 * whether the `clientMessageId` survives the answer.
 */
class RelayComposerStateTest {

    // ── Local bounds, checked before anything is dispatched ────────────────

    @Test
    fun `a send with something to say and somewhere to send it is not rejected locally`() {
        assertNull(relayLocalRejection("general", "ship it"))
    }

    @Test
    fun `blank text is refused with the same sentence the repository would use`() {
        assertEquals(RelaySendOutcome(EMPTY_TEXT_MESSAGE), relayLocalRejection("general", ""))
        assertEquals(RelaySendOutcome(EMPTY_TEXT_MESSAGE), relayLocalRejection("general", "   \n  "))
    }

    @Test
    fun `text is refused at exactly the byte bound the server enforces`() {
        val atBound = "a".repeat(MAX_TEXT_BYTES)
        assertNull(relayLocalRejection("general", atBound))
        assertEquals(
            RelaySendOutcome(LARGE_TEXT_MESSAGE),
            relayLocalRejection("general", atBound + "a"),
        )
    }

    @Test
    fun `the bound is bytes and not characters`() {
        // Two bytes each in UTF-8, so half as many of them fit.
        val justOver = "é".repeat(MAX_TEXT_BYTES / 2 + 1)
        assertTrue(justOver.length < MAX_TEXT_BYTES)
        assertEquals(RelaySendOutcome(LARGE_TEXT_MESSAGE), relayLocalRejection("general", justOver))
    }

    @Test
    fun `no channel and an over-long channel id are both refused before any request`() {
        assertEquals(RelaySendOutcome(PICK_CHANNEL_MESSAGE), relayLocalRejection(null, "ship it"))
        assertEquals(RelaySendOutcome(PICK_CHANNEL_MESSAGE), relayLocalRejection("  ", "ship it"))
        assertEquals(
            RelaySendOutcome(PICK_CHANNEL_MESSAGE),
            relayLocalRejection("c".repeat(MAX_ID_BYTES + 1), "ship it"),
        )
    }

    @Test
    fun `an empty channel is refused before an empty message is`() {
        // The person cannot fix the text of a message with nowhere to go, so
        // the sentence names the thing that is actually missing.
        assertEquals(RelaySendOutcome(PICK_CHANNEL_MESSAGE), relayLocalRejection(null, ""))
    }

    // ── What a settled post means ─────────────────────────────────────────

    @Test
    fun `an accepted post retires the draft and its id`() {
        val verdict = relayPostVerdict(RelayPostResult.Accepted(message()))

        assertEquals(RelaySendOutcome(SENT_MESSAGE), verdict.outcome)
        assertTrue(verdict.clearsDraft)
        assertFalse(verdict.keepsAttempt)
    }

    @Test
    fun `a conflict is exactly-once working, so it also retires the draft and its id`() {
        val verdict = relayPostVerdict(failed(409))

        assertEquals(CONFLICT_MESSAGE, verdict.outcome.message)
        // Never a retry: re-sending this id is the one thing the contract
        // forbids, and the message it names is already in the channel.
        assertNull(verdict.outcome.action)
        assertTrue(verdict.clearsDraft)
        assertFalse(verdict.keepsAttempt)
    }

    @Test
    fun `an unreachable Gateway keeps the draft and keeps the id`() {
        val verdict = relayPostVerdict(failed(0))

        assertEquals(RelaySendAction.Retry, verdict.outcome.action)
        assertFalse(verdict.clearsDraft)
        assertTrue(verdict.keepsAttempt)
    }

    @Test
    fun `every server fault keeps the id, because none of them says where the message went`() {
        for (status in listOf(500, 502, 503, 504)) {
            val verdict = relayPostVerdict(failed(status))
            assertEquals("status $status", RelaySendAction.Retry, verdict.outcome.action)
            assertTrue("status $status", verdict.keepsAttempt)
            assertFalse("status $status", verdict.clearsDraft)
        }
    }

    @Test
    fun `a 200 this build cannot read keeps the id rather than guessing`() {
        // The repository's contract-violation answer: the post may well have
        // landed, so minting a new id would be the only way to double-post it.
        val verdict = relayPostVerdict(RelayPostResult.Failed(null, "unusable"))

        assertEquals(RelaySendAction.Retry, verdict.outcome.action)
        assertTrue(verdict.keepsAttempt)
    }

    @Test
    fun `a refused credential sends the person to Gateways and still keeps the id`() {
        for (status in listOf(401, 403)) {
            val verdict = relayPostVerdict(failed(status))
            assertEquals("status $status", REFUSED_MESSAGE, verdict.outcome.message)
            assertEquals("status $status", RelaySendAction.OpenGateways, verdict.outcome.action)
            assertFalse("status $status", verdict.clearsDraft)
            assertTrue("status $status", verdict.keepsAttempt)
        }
    }

    @Test
    fun `a refused body spends the id and shows the refusal's own sentence`() {
        for (status in listOf(400, 413)) {
            val verdict = relayPostVerdict(failed(status, LARGE_TEXT_MESSAGE))
            assertEquals("status $status", LARGE_TEXT_MESSAGE, verdict.outcome.message)
            // Nothing to retry until the text changes, and changed text is a
            // new message that must not reuse a spent id.
            assertNull("status $status", verdict.outcome.action)
            assertFalse("status $status", verdict.clearsDraft)
            assertFalse("status $status", verdict.keepsAttempt)
        }
    }

    @Test
    fun `a channel that is gone offers no retry`() {
        val verdict = relayPostVerdict(failed(404))

        assertEquals(MISSING_CHANNEL_MESSAGE, verdict.outcome.message)
        assertNull(verdict.outcome.action)
    }

    @Test
    fun `Relay's own retryable flag never overrides a conflict`() {
        // The repository already refuses to mark a conflict retryable; this is
        // the surface refusing a second time, from the status alone.
        val verdict = relayPostVerdict(RelayPostResult.Failed(409, "conflict", retryable = true))

        assertNull(verdict.outcome.action)
        assertFalse(verdict.keepsAttempt)
    }

    // ── Hint ──────────────────────────────────────────────────────────────

    @Test
    fun `the hint names the lane that is blocking the send`() {
        assertEquals(WRITE_HINT, relayComposerHint(lane(RelayLaneState.READY), archived = false))
        assertEquals(OFFLINE_HINT, relayComposerHint(lane(RelayLaneState.OFFLINE), archived = false))
        assertEquals(AUTH_HINT, relayComposerHint(lane(RelayLaneState.AUTH_REQUIRED), archived = false))
        assertEquals(NOT_READY_HINT, relayComposerHint(lane(RelayLaneState.ERROR), archived = false))
        assertEquals(NOT_READY_HINT, relayComposerHint(null, archived = false))
        assertEquals(NOT_READY_HINT, relayComposerHint(RelayAvailability.Missing, archived = false))
    }

    @Test
    fun `an archived channel outranks a perfectly healthy lane`() {
        assertEquals(ARCHIVED_HINT, relayComposerHint(lane(RelayLaneState.READY), archived = true))
    }

    // ── The two derivations the screen renders without re-deriving ────────

    @Test
    fun `the send control is armed only with something to send and nothing in flight`() {
        val open = RelayComposerUiState(draft = "ship it", hint = WRITE_HINT, editable = true)

        assertTrue(open.canSend)
        // Nothing typed, only whitespace typed, a closed composer, or a post
        // already on the wire — each on its own closes the control.
        assertFalse(open.copy(draft = "").canSend)
        assertFalse(open.copy(draft = "   ").canSend)
        assertFalse(open.copy(editable = false).canSend)
        assertFalse(open.copy(sending = true).canSend)
    }

    @Test
    fun `the status line says what sending does, or why it cannot`() {
        val open = RelayComposerUiState(draft = "ship it", hint = WRITE_HINT, editable = true)
        assertEquals(MARKDOWN_NOTE, open.statusLine)

        // While a post is in flight the note would be describing something the
        // control is no longer offering.
        assertEquals(WRITE_HINT, open.copy(sending = true).statusLine)

        // Closed: the line carries the reason, and it keeps carrying it with a
        // draft still in the field — which is exactly when the placeholder that
        // would otherwise have said it is hidden.
        val archived = RelayComposerUiState(draft = "ship it", hint = ARCHIVED_HINT)
        assertEquals(ARCHIVED_HINT, archived.statusLine)
        assertEquals(OFFLINE_HINT, archived.copy(hint = OFFLINE_HINT).statusLine)
    }

    // ── The id itself ─────────────────────────────────────────────────────

    @Test
    fun `a minted id is unique and fits the contract's bound`() {
        val ids = List(64) { newRelayClientMessageId() }

        assertEquals(64, ids.toSet().size)
        for (id in ids) {
            assertTrue(id.isNotBlank())
            assertTrue(id.toByteArray(Charsets.UTF_8).size <= MAX_ID_BYTES)
        }
    }

    private companion object {
        fun lane(state: RelayLaneState) = RelayAvailability.Available(
            RelayChannelsStatus(state, message = null, guidance = null),
        )

        fun failed(status: Int, message: String = "refused") =
            RelayPostResult.Failed(status, message)

        fun message() = RelayMessage(
            id = "m1",
            channelId = "general",
            seq = 9,
            kind = "message",
            status = "delivered",
            senderKind = "human",
            senderId = "s1",
            senderDisplayName = null,
            text = "ship it",
            format = RelayMessageFormat.MARKDOWN,
            threadId = null,
            parentMessageId = null,
            createdAt = "2026-08-26T09:00:00Z",
            updatedAt = "2026-08-26T09:00:00Z",
            truncated = null,
            clientMessageId = "cid-1",
        )
    }
}
