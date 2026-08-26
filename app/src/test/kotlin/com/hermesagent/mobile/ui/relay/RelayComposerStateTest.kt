package com.hermesagent.mobile.ui.relay

import com.hermesagent.mobile.data.gateway.OkHttpGatewayHttp
import com.hermesagent.mobile.data.relay.EMPTY_TEXT_MESSAGE
import com.hermesagent.mobile.data.relay.ERROR_AUTH_REQUIRED
import com.hermesagent.mobile.data.relay.ERROR_INVALID_FORMAT
import com.hermesagent.mobile.data.relay.ERROR_INVALID_TEXT
import com.hermesagent.mobile.data.relay.ERROR_REQUEST_TOO_LARGE
import com.hermesagent.mobile.data.relay.ERROR_TEXT_TOO_LARGE
import com.hermesagent.mobile.data.relay.LARGE_TEXT_MESSAGE
import com.hermesagent.mobile.data.relay.MAX_CHANNEL_ID_BYTES
import com.hermesagent.mobile.data.relay.MAX_CLIENT_MESSAGE_ID_BYTES
import com.hermesagent.mobile.data.relay.MAX_REQUEST_BODY_BYTES
import com.hermesagent.mobile.data.relay.PICK_CHANNEL_MESSAGE
import com.hermesagent.mobile.data.relay.RelayAvailability
import com.hermesagent.mobile.data.relay.RelayChannelsStatus
import com.hermesagent.mobile.data.relay.RelayLaneState
import com.hermesagent.mobile.data.relay.RelayMessage
import com.hermesagent.mobile.data.relay.RelayMessageFormat
import com.hermesagent.mobile.data.relay.RelayPluginRepository
import com.hermesagent.mobile.data.relay.RelayPostResult
import com.hermesagent.mobile.data.relay.UNSUPPORTED_FORMAT_MESSAGE
import com.hermesagent.mobile.data.relay.relayPostBody
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
    fun `the bound is the encoded body, so a text at the text bound is already too large`() {
        // The framing the plugin measures and the app does not get to ignore:
        // `{"text":"…","format":"markdown","clientMessageId":"…"}` around a
        // 36-byte id is 88 bytes, so a text at exactly the 64 KiB text bound
        // arrives as a 65,624-byte request and is refused before it is read
        // (hermes-plugin-relay @ 563a8c8, `dashboard/plugin_api.py:121-136`).
        val atTextBound = "a".repeat(MAX_REQUEST_BODY_BYTES)
        assertEquals(
            65_624,
            relayPostBody(atTextBound, RelayMessageFormat.MARKDOWN, SIZING_CLIENT_MESSAGE_ID)
                .toByteArray(Charsets.UTF_8).size,
        )
        assertEquals(
            RelaySendOutcome(LARGE_TEXT_MESSAGE),
            relayLocalRejection("general", atTextBound),
        )

        // The largest text that actually fits is the budget minus that framing,
        // and it is accepted at exactly that size.
        val largestThatFits = "a".repeat(MAX_REQUEST_BODY_BYTES - FRAMING_BYTES)
        assertEquals(
            MAX_REQUEST_BODY_BYTES,
            relayPostBody(largestThatFits, RelayMessageFormat.MARKDOWN, SIZING_CLIENT_MESSAGE_ID)
                .toByteArray(Charsets.UTF_8).size,
        )
        assertNull(relayLocalRejection("general", largestThatFits))
        assertEquals(
            RelaySendOutcome(LARGE_TEXT_MESSAGE),
            relayLocalRejection("general", largestThatFits + "a"),
        )
    }

    @Test
    fun `the bound is bytes and not characters`() {
        // Two bytes each in UTF-8, so half as many of them fit.
        val justOver = "é".repeat(MAX_REQUEST_BODY_BYTES / 2)
        assertTrue(justOver.length < MAX_REQUEST_BODY_BYTES)
        assertEquals(RelaySendOutcome(LARGE_TEXT_MESSAGE), relayLocalRejection("general", justOver))
    }

    @Test
    fun `escape-dense text crosses the bound at about half its length`() {
        // A quote is one byte typed and two bytes encoded, so a message of
        // them fills the request budget twice as fast. Raw-text arithmetic
        // would have promised this one a send and let the wire refuse it.
        val dense = "\"".repeat(40_000)
        assertTrue(dense.toByteArray(Charsets.UTF_8).size < MAX_REQUEST_BODY_BYTES)
        assertEquals(RelaySendOutcome(LARGE_TEXT_MESSAGE), relayLocalRejection("general", dense))

        // And one that still fits after escaping is still sent.
        assertNull(relayLocalRejection("general", "\"".repeat(30_000)))
    }

    @Test
    fun `no channel and an over-long channel id are both refused before any request`() {
        assertEquals(RelaySendOutcome(PICK_CHANNEL_MESSAGE), relayLocalRejection(null, "ship it"))
        assertEquals(RelaySendOutcome(PICK_CHANNEL_MESSAGE), relayLocalRejection("  ", "ship it"))
        assertEquals(
            RelaySendOutcome(PICK_CHANNEL_MESSAGE),
            relayLocalRejection("c".repeat(MAX_CHANNEL_ID_BYTES + 1), "ship it"),
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
    fun `a conflict is a failure that keeps the draft and retires the id`() {
        val verdict = relayPostVerdict(failed(409))

        assertEquals(CONFLICT_MESSAGE, verdict.outcome.message)
        // Never a retry: re-sending this id is the one thing the contract
        // forbids. The next send is a deliberate one, under a new id.
        assertNull(verdict.outcome.action)
        // An emptied field is a delivery receipt, and this send was refused —
        // Desktop clears its own only on success (`plugin.js:938-950`).
        assertFalse(verdict.clearsDraft)
        assertFalse(verdict.keepsAttempt)
        // The one outcome a later window can prove and retire.
        assertTrue(verdict.watchesForArrival)
    }

    @Test
    fun `no other outcome asks a window to settle it`() {
        val settledByAWindow = listOf(0, 400, 401, 403, 404, 413, 500)
            .filter { relayPostVerdict(failed(it)).watchesForArrival }
        assertEquals(emptyList<Int>(), settledByAWindow)
        assertFalse(relayPostVerdict(RelayPostResult.Accepted(message())).watchesForArrival)
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
    fun `the host's own Relay credential is not a sign-in this device can offer`() {
        // `auth_required` from the plugin is the *host's* Relay credential
        // (`dashboard/plugin_api.py:92-100` at the pin). Reconnecting this
        // device would re-authenticate something that was never the problem,
        // so the sentence names the host and offers no action.
        for (status in listOf(401, 403)) {
            val plugin = relayPostVerdict(failed(status, code = ERROR_AUTH_REQUIRED))
            assertEquals("status $status", AUTH_HINT, plugin.outcome.message)
            assertNull("status $status", plugin.outcome.action)
            assertFalse("status $status", plugin.clearsDraft)
            assertTrue("status $status", plugin.keepsAttempt)
        }

        // A gate-level refusal carries no plugin envelope, and that one really
        // is this device's credential.
        val gate = relayPostVerdict(failed(401))
        assertEquals(REFUSED_MESSAGE, gate.outcome.message)
        assertEquals(RelaySendAction.OpenGateways, gate.outcome.action)
    }

    @Test
    fun `a refused body is named by the plugin's own code, not by the transport`() {
        // What the transport writes for a 400 or a 413 it has no remedy for.
        val generic = "Hermes refused that Gateway request."
        val named = mapOf(
            ERROR_REQUEST_TOO_LARGE to LARGE_TEXT_MESSAGE,
            ERROR_TEXT_TOO_LARGE to LARGE_TEXT_MESSAGE,
            ERROR_INVALID_TEXT to EMPTY_TEXT_MESSAGE,
            ERROR_INVALID_FORMAT to UNSUPPORTED_FORMAT_MESSAGE,
        )
        for ((code, sentence) in named) {
            for (status in listOf(400, 413)) {
                val verdict = relayPostVerdict(failed(status, generic, code = code))
                assertEquals("$code at $status", sentence, verdict.outcome.message)
                // Nothing to retry until the text changes, and changed text is
                // a new message that must not reuse a spent id.
                assertNull("$code at $status", verdict.outcome.action)
                assertFalse("$code at $status", verdict.clearsDraft)
                assertFalse("$code at $status", verdict.keepsAttempt)
            }
        }

        // A refusal nothing classified keeps the transport's safe line rather
        // than being folded into a neighbour's meaning.
        assertEquals(generic, relayPostVerdict(failed(413, generic)).outcome.message)
        assertEquals(generic, relayPostVerdict(failed(400, generic, code = "invented")).outcome.message)
    }

    @Test
    fun `a wire refusal is named through the real transport, not a hand-fed fixture`() = runTest {
        // End to end over `OkHttpGatewayHttp`: the plugin's own 413 envelope
        // (`_error`, `dashboard/plugin_api.py:85-88` at 563a8c8) through the
        // repository and into the sentence the composer renders. A fixture
        // that skips the transport cannot catch the transport being the thing
        // that erased the refusal's name.
        val envelope =
            """{"error":{"code":"request_too_large","message":"Request body is too large","retryable":false}}"""
        val http = OkHttpGatewayHttp(
            http = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(413)
                        .message("Rejected")
                        .body(envelope.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            resolveEndpoint = { "https://gateway.example" },
            resolveAuthorization = { "Authorization" to "Bearer test-token" },
        )
        val refused = RelayPluginRepository { http }
            .post("general", "ship it", RelayMessageFormat.MARKDOWN, "cmid-1")

        // The transport really did write its generic line: this is what the
        // composer used to render.
        assertEquals("Hermes refused that Gateway request.", (refused as RelayPostResult.Failed).safeMessage)
        assertEquals(LARGE_TEXT_MESSAGE, relayPostVerdict(refused).outcome.message)
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
            assertTrue(id.toByteArray(Charsets.UTF_8).size <= MAX_CLIENT_MESSAGE_ID_BYTES)
        }
    }

    @Test
    fun `the size the pre-check budgets for is the size an id actually costs`() {
        // The pre-check runs before an id exists, so it sizes the body with a
        // stand-in. That is exact only while every minted id is this long —
        // and a UUID JSON-escapes to itself, so length is the whole story.
        val budget = SIZING_CLIENT_MESSAGE_ID.toByteArray(Charsets.UTF_8).size
        assertEquals(36, budget)
        for (id in List(16) { newRelayClientMessageId() }) {
            assertEquals(budget, id.toByteArray(Charsets.UTF_8).size)
            assertEquals(
                relayPostBody("t", RelayMessageFormat.MARKDOWN, SIZING_CLIENT_MESSAGE_ID).length,
                relayPostBody("t", RelayMessageFormat.MARKDOWN, id).length,
            )
        }
    }

    private companion object {
        /**
         * `{"text":"…","format":"markdown","clientMessageId":"<36 bytes>"}`
         * around an empty text: the cost the plugin's whole-body gate charges
         * this client for framing, derived rather than copied.
         */
        val FRAMING_BYTES = relayPostBody("", RelayMessageFormat.MARKDOWN, SIZING_CLIENT_MESSAGE_ID)
            .toByteArray(Charsets.UTF_8).size

        fun lane(state: RelayLaneState) = RelayAvailability.Available(
            RelayChannelsStatus(state, message = null, guidance = null),
        )

        fun failed(status: Int, message: String = "refused", code: String? = null) =
            RelayPostResult.Failed(status, message, code = code)

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
