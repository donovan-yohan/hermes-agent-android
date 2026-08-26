package com.hermesagent.mobile.ui.relay

import com.hermesagent.mobile.data.relay.EMPTY_TEXT_MESSAGE
import com.hermesagent.mobile.data.relay.LARGE_TEXT_MESSAGE
import com.hermesagent.mobile.data.relay.MAX_ID_BYTES
import com.hermesagent.mobile.data.relay.MAX_TEXT_BYTES
import com.hermesagent.mobile.data.relay.PICK_CHANNEL_MESSAGE
import com.hermesagent.mobile.data.relay.RelayAvailability
import com.hermesagent.mobile.data.relay.RelayLaneState
import com.hermesagent.mobile.data.relay.RelayPostResult
import java.util.UUID

/**
 * The one action a send outcome can offer. Deliberately not [RelayNoticeAction]:
 * a notice acts on the *connection*, this acts on one message, and the same
 * word would mean two different things in two places on the same screen.
 */
enum class RelaySendAction {
    /**
     * Send the same attempt again. It carries the original `clientMessageId`,
     * so Relay's exactly-once contract collapses a retry that already landed
     * into a conflict rather than a second message.
     */
    Retry,

    /** The app's existing reconnect/sign-in path, for a refused credential. */
    OpenGateways,
}

/**
 * What the composer says about the last send, and what can be done about it.
 *
 * One slot, one sentence: an outcome replaces its predecessor rather than
 * stacking, and it is announced once when it settles.
 */
data class RelaySendOutcome(
    val message: String,
    val action: RelaySendAction? = null,
)

/** Everything the Relay composer renders. Nothing here is persisted. */
data class RelayComposerUiState(
    /** The per-channel draft. UI-only, kept while this surface lives. */
    val draft: String = "",
    /** Placeholder and quiet status line — always says why sending is off. */
    val hint: String = WRITE_HINT,
    /** The field accepts typing. False for a lane or channel that cannot take a post. */
    val editable: Boolean = false,
    /** A post for *this* channel is in flight. Another channel's post never gates this one. */
    val sending: Boolean = false,
    val outcome: RelaySendOutcome? = null,
    /**
     * Relay's own id for the newest row this device got it to store in the open
     * channel, or null before there is one.
     *
     * Exists so the transcript can tell *your* message arriving from anyone
     * else's: a poll landing someone else's message must not yank a reader back
     * to the tail, but the message you just sent must, whatever you were
     * reading. A changing id is the signal; the value itself is never rendered.
     */
    val lastAcceptedId: String? = null,
) {
    /**
     * The send control is armed: something to send, somewhere to send it, and
     * nothing already in flight.
     *
     * Derived rather than stored, because a stored copy is one a preview or a
     * fixture can set to disagree with the very fields it summarises — an
     * armed control over a field that refuses typing compiles perfectly well.
     */
    val canSend: Boolean get() = editable && draft.isNotBlank() && !sending

    /**
     * The quiet line under the field: what sending does when it can, and why
     * it cannot when it cannot. Desktop swaps one line between exactly those
     * two jobs (hermes-plugin-relay @ `563a8c8`, `desktop/plugin.js:1217`).
     *
     * Resolved here rather than in the composable so the swap is one decision
     * with one home — beside [relayComposerHint], which owns the other half of
     * the same sentence — instead of a branch the Compose layer re-derives.
     */
    val statusLine: String get() = if (editable && !sending) MARKDOWN_NOTE else hint
}

/**
 * The composer's own placeholder and status line.
 *
 * Desktop derives the same sentence from the same three facts
 * (hermes-plugin-relay @ `563a8c8`, `desktop/plugin.js:1088-1094`); archived
 * beats the lane state there and here, because an archived channel refuses a
 * post on a perfectly healthy lane.
 */
internal fun relayComposerHint(availability: RelayAvailability?, archived: Boolean): String {
    if (archived) return ARCHIVED_HINT
    val lane = (availability as? RelayAvailability.Available)?.channels?.state ?: return NOT_READY_HINT
    return when (lane) {
        RelayLaneState.READY -> WRITE_HINT
        RelayLaneState.OFFLINE -> OFFLINE_HINT
        RelayLaneState.AUTH_REQUIRED -> AUTH_HINT
        RelayLaneState.ERROR -> NOT_READY_HINT
    }
}

/**
 * The server's own bounds, checked before anything is dispatched.
 *
 * These mirror `relay_proxy.py`'s limits through the repository's constants
 * rather than through numbers copied a second time, so the two cannot drift.
 * The repository re-checks all of it and the server remains authoritative;
 * this exists so a refusal the app can already predict costs no request and
 * no round trip a person has to wait out.
 *
 * Returns null when the send may proceed.
 */
internal fun relayLocalRejection(channelId: String?, text: String): RelaySendOutcome? = when {
    channelId == null || channelId.isBlank() -> RelaySendOutcome(PICK_CHANNEL_MESSAGE)
    channelId.toByteArray(Charsets.UTF_8).size > MAX_ID_BYTES -> RelaySendOutcome(PICK_CHANNEL_MESSAGE)
    text.isBlank() -> RelaySendOutcome(EMPTY_TEXT_MESSAGE)
    text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES -> RelaySendOutcome(LARGE_TEXT_MESSAGE)
    else -> null
}

/**
 * What one post result means for the draft and for its `clientMessageId`.
 *
 * The whole idempotency policy is this function, and it turns on one question:
 * *does this answer tell us where the message ended up?*
 *
 * - **It landed** — accepted, or a conflict, which is Relay saying it already
 *   holds a message with this id. Both retire the draft and the id.
 * - **It definitively did not land, and never will** — Relay refused the body
 *   itself (400/413). The id is spent with it; the same bytes would be refused
 *   again, so the next send is a new draft and gets a new id.
 * - **Nobody knows** — a timeout, an unreachable Gateway, a 5xx, or a 200 this
 *   build could not read. The attempt is kept verbatim, because re-sending the
 *   *same* id can only ever collapse into a conflict, while minting a fresh one
 *   is the only way to post the same message twice.
 *
 * When in doubt the attempt is kept. That asymmetry is deliberate: keeping an
 * id costs a person one extra sentence, and dropping one costs them a
 * duplicate message in a channel other people are reading.
 */
internal fun relayPostVerdict(result: RelayPostResult): RelayPostVerdict = when (result) {
    is RelayPostResult.Accepted -> RelayPostVerdict(
        outcome = RelaySendOutcome(SENT_MESSAGE),
        clearsDraft = true,
        keepsAttempt = false,
    )

    is RelayPostResult.Failed -> result.toVerdict()
}

/** The draft and retry-id consequences of one settled post. */
internal data class RelayPostVerdict(
    val outcome: RelaySendOutcome,
    /** The message is in Relay; the composer starts empty again. */
    val clearsDraft: Boolean,
    /** The `clientMessageId` stays reserved for the next attempt at this text. */
    val keepsAttempt: Boolean,
)

private fun RelayPostResult.Failed.toVerdict(): RelayPostVerdict = when (statusCode) {
    // The Gateway leg refused this client. The message did not reach Relay,
    // but the attempt is kept anyway: after a reconnect the same send should
    // carry the same id, and no refusal is worth risking a duplicate over.
    401, 403 -> RelayPostVerdict(
        outcome = RelaySendOutcome(REFUSED_MESSAGE, RelaySendAction.OpenGateways),
        clearsDraft = false,
        keepsAttempt = true,
    )

    // Exactly-once, working. Relay already holds a message with this id, so
    // the draft has in fact been sent — once — and its id is now spent.
    409 -> RelayPostVerdict(
        outcome = RelaySendOutcome(CONFLICT_MESSAGE),
        clearsDraft = true,
        keepsAttempt = false,
    )

    // Relay refused the body. Its own sentence is already the specific one —
    // whether it came from the local pre-check or from the wire — and there is
    // nothing to retry until the text changes, which is a new draft anyway.
    400, 413 -> RelayPostVerdict(
        outcome = RelaySendOutcome(safeMessage),
        clearsDraft = false,
        keepsAttempt = false,
    )

    // The channel itself is gone. Retrying the same post cannot fix that, and
    // the list is one back-gesture away.
    404 -> RelayPostVerdict(
        outcome = RelaySendOutcome(MISSING_CHANNEL_MESSAGE),
        clearsDraft = false,
        keepsAttempt = true,
    )

    // Unreachable (0), a Gateway 5xx, a 200 in a shape this build cannot read
    // (null), or anything Relay itself called retryable. None of these say
    // where the message ended up, so all of them keep the attempt.
    else -> RelayPostVerdict(
        outcome = RelaySendOutcome(UNCONFIRMED_MESSAGE, RelaySendAction.Retry),
        clearsDraft = false,
        keepsAttempt = true,
    )
}

/**
 * A fresh id for a fresh draft.
 *
 * A random UUID, as Desktop mints (`desktop/plugin.js:171-179`): 36 bytes,
 * comfortably inside the contract's 512-byte bound, and unique without asking
 * the Gateway for anything. Nothing about the message is derived into it —
 * this id is a retry key, not a fingerprint of what was typed.
 */
internal fun newRelayClientMessageId(): String = UUID.randomUUID().toString()

internal const val WRITE_HINT = "Write a message…"
internal const val MARKDOWN_NOTE = "Sends Markdown to Relay."
internal const val ARCHIVED_HINT = "This channel is archived."
internal const val OFFLINE_HINT = "Relay is offline. Your draft is kept until it reconnects."
internal const val AUTH_HINT = "Authorize Relay on the Gateway host before sending."
internal const val NOT_READY_HINT = "Relay is not ready to accept messages."

internal const val SENT_MESSAGE = "Sent to Relay."
internal const val CONFLICT_MESSAGE = "Relay already has this message. It was not sent twice."
internal const val UNCONFIRMED_MESSAGE =
    "Relay did not confirm this message. Try again — a retry cannot post it twice."
internal const val REFUSED_MESSAGE =
    "The Gateway refused this message. Reconnect under Gateways, then send it again."
internal const val MISSING_CHANNEL_MESSAGE =
    "Relay no longer has this channel. Go back and pick another one."
