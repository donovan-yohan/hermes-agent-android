package com.hermesagent.mobile.ui.relay

import com.hermesagent.mobile.data.relay.EMPTY_TEXT_MESSAGE
import com.hermesagent.mobile.data.relay.ERROR_AUTH_REQUIRED
import com.hermesagent.mobile.data.relay.ERROR_INVALID_CHANNEL
import com.hermesagent.mobile.data.relay.ERROR_INVALID_FORMAT
import com.hermesagent.mobile.data.relay.ERROR_INVALID_TEXT
import com.hermesagent.mobile.data.relay.ERROR_REQUEST_TOO_LARGE
import com.hermesagent.mobile.data.relay.ERROR_TEXT_TOO_LARGE
import com.hermesagent.mobile.data.relay.LARGE_TEXT_MESSAGE
import com.hermesagent.mobile.data.relay.MAX_CHANNEL_ID_BYTES
import com.hermesagent.mobile.data.relay.PICK_CHANNEL_MESSAGE
import com.hermesagent.mobile.data.relay.RelayAvailability
import com.hermesagent.mobile.data.relay.RelayLaneState
import com.hermesagent.mobile.data.relay.RelayMessageFormat
import com.hermesagent.mobile.data.relay.RelayPostResult
import com.hermesagent.mobile.data.relay.UNSUPPORTED_FORMAT_MESSAGE
import com.hermesagent.mobile.data.relay.relayPostBody
import com.hermesagent.mobile.data.relay.relayPostBodyTooLarge
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
 * A stand-in retry id, the exact size of every id this client mints.
 *
 * The size gate below runs before there is an id to size: minting one for a
 * send that is about to be refused would spend a retry key on a message that
 * never leaves. Every id here is a UUID (see [newRelayClientMessageId]) — 36
 * ASCII bytes that JSON escapes to themselves — so a nil UUID makes the
 * pre-check *exact* rather than merely conservative, and a test pins the two
 * lengths together so a different id shape cannot quietly loosen it.
 */
internal const val SIZING_CLIENT_MESSAGE_ID = "00000000-0000-0000-0000-000000000000"

/**
 * The server's own bounds, checked before anything is dispatched.
 *
 * These mirror `relay_proxy.py`'s limits through the repository's constants
 * rather than through numbers copied a second time, so the two cannot drift.
 * The repository re-checks all of it and the server remains authoritative;
 * this exists so a refusal the app can already predict costs no request and
 * no round trip a person has to wait out.
 *
 * The size question is asked of the *encoded body*, through the very function
 * the repository encodes with, because that is the bound the plugin applies:
 * it reads the whole request under one cap before it looks at the text at all
 * (hermes-plugin-relay @ `563a8c8`, `dashboard/plugin_api.py:121-136`). Text
 * checked raw would promise a 64 KiB message and then watch the wire refuse
 * it — at exactly half that, for a message dense in characters JSON escapes.
 *
 * Returns null when the send may proceed.
 */
internal fun relayLocalRejection(channelId: String?, text: String): RelaySendOutcome? = when {
    channelId == null || channelId.isBlank() -> RelaySendOutcome(PICK_CHANNEL_MESSAGE)
    channelId.toByteArray(Charsets.UTF_8).size > MAX_CHANNEL_ID_BYTES ->
        RelaySendOutcome(PICK_CHANNEL_MESSAGE)

    text.isBlank() -> RelaySendOutcome(EMPTY_TEXT_MESSAGE)
    relayPostBodyTooLarge(
        relayPostBody(text, RelayMessageFormat.MARKDOWN, SIZING_CLIENT_MESSAGE_ID),
    ) -> RelaySendOutcome(LARGE_TEXT_MESSAGE)

    else -> null
}

/**
 * What one post result means for the draft and for its `clientMessageId`.
 *
 * The whole idempotency policy is this function, and it turns on one question:
 * *does this answer tell us where the message ended up?*
 *
 * - **It landed** — accepted. The draft is retired with its id, and the row
 *   Relay stored is the receipt.
 * - **It definitively did not land, and never will** — Relay refused the body
 *   itself (400/413), or refused this id (409). The id is spent with it; the
 *   same bytes under the same id would be refused again, so the next send is a
 *   new message and gets a new id. The draft is *kept* in every one of these:
 *   Desktop clears its field only on success (hermes-plugin-relay @ `563a8c8`,
 *   `desktop/plugin.js:938-950`), and a refusal that empties the field reads
 *   as a delivery receipt for a message that was never delivered.
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
    /**
     * The outcome is a claim a later window can settle. Only a conflict is:
     * Relay says it is already holding this id, and the poll that carries the
     * row proves it — at which point the sentence has nothing left to warn
     * about and goes quiet on its own.
     */
    val watchesForArrival: Boolean = false,
)

private fun RelayPostResult.Failed.toVerdict(): RelayPostVerdict = when (statusCode) {
    // A credential was refused — but not necessarily one this device holds.
    // The plugin's own `auth_required` is the *host's* Relay credential, which
    // no sign-in here can supply, so offering Gateways would send someone to
    // re-authenticate something that was never the problem. Either way the
    // attempt is kept: the message did not reach Relay, and no refusal is
    // worth risking a duplicate over.
    401, 403 -> if (code == ERROR_AUTH_REQUIRED) {
        RelayPostVerdict(
            outcome = RelaySendOutcome(AUTH_HINT),
            clearsDraft = false,
            keepsAttempt = true,
        )
    } else {
        RelayPostVerdict(
            outcome = RelaySendOutcome(REFUSED_MESSAGE, RelaySendAction.OpenGateways),
            clearsDraft = false,
            keepsAttempt = true,
        )
    }

    // Relay refused this id. Exactly-once is working — it is holding a message
    // under this id already — but this send is still a send that did not
    // happen, and the app cannot see what Relay is holding to claim otherwise.
    // So: a failure, the draft stays in the field, and the id is spent. A next
    // send is deliberate, mints a new id, and is the person's call to make
    // once they have looked at the channel above the composer.
    409 -> RelayPostVerdict(
        outcome = RelaySendOutcome(CONFLICT_MESSAGE),
        clearsDraft = false,
        keepsAttempt = false,
        watchesForArrival = true,
    )

    // Relay refused the body. The plugin names what it refused and this maps
    // that name to a sentence, because the transport cannot: it writes one
    // generic line for every status it has no remedy for. There is nothing to
    // retry until the text changes, which is a new message anyway.
    400, 413 -> RelayPostVerdict(
        outcome = RelaySendOutcome(refusedBodyMessage()),
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
 * The sentence for a refused body, named by the refusal's own code.
 *
 * The plugin's vocabulary is fixed and small (`dashboard/plugin_api.py:126-198`
 * at the pin), and this build maps only the codes it actually understands: a
 * local pre-check and its wire twin carry the same code, so both arrive at the
 * same sentence and the two cannot describe one rule two ways. Anything
 * unclassified falls back to the transport's own safe line rather than being
 * folded into a neighbour's meaning.
 */
private fun RelayPostResult.Failed.refusedBodyMessage(): String = when (code) {
    // Both size gates mean the same thing to a person holding a long message.
    ERROR_REQUEST_TOO_LARGE, ERROR_TEXT_TOO_LARGE -> LARGE_TEXT_MESSAGE
    // `invalid_text` is precisely "absent or blank" (`:184-185`), which is the
    // sentence the composer already uses for it before dispatching.
    ERROR_INVALID_TEXT -> EMPTY_TEXT_MESSAGE
    ERROR_INVALID_FORMAT -> UNSUPPORTED_FORMAT_MESSAGE
    ERROR_INVALID_CHANNEL -> PICK_CHANNEL_MESSAGE
    else -> safeMessage
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
internal const val CONFLICT_MESSAGE =
    "Relay did not accept this message. Check the channel, then send it again if it is missing."
internal const val UNCONFIRMED_MESSAGE =
    "Relay did not confirm this message. Try again — a retry cannot post it twice."
internal const val REFUSED_MESSAGE =
    "The Gateway refused this message. Reconnect under Gateways, then send it again."
internal const val MISSING_CHANNEL_MESSAGE =
    "Relay no longer has this channel. Go back and pick another one."
