package com.hermesagent.mobile.plugins.relay

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Who wrote a Relay message, as the transcript treats it.
 *
 * Desktop normalises the wire's sender kind into exactly these three and
 * defaults anything else to [System] (hermes-plugin-relay @ `563a8c8`,
 * `desktop/plugin.js:132-138`). Keeping that normalisation here means an
 * unknown kind gets the quietest treatment rather than an invented one.
 */
enum class RelaySenderKind { Human, Agent, System }

internal fun relaySenderKind(wire: String): RelaySenderKind = when (wire) {
    "human" -> RelaySenderKind.Human
    "agent" -> RelaySenderKind.Agent
    else -> RelaySenderKind.System
}

/** One channel as the list paints it. Every field is already display-ready. */
data class RelayChannelRow(
    val id: String,
    /**
     * Title with Desktop's archived suffix already applied
     * (`desktop/plugin.js:492`): the annotation belongs to the name line, not
     * to a badge of its own.
     */
    val title: String,
    val archived: Boolean,
    /** `kind · visibility`, humanised, or null when the backend sent neither. */
    val classification: String?,
    /** The last message, as `sender: text`, or null when the channel has none. */
    val preview: String?,
    val timestamp: String?,
    /** The single authoritative spoken label for the row. */
    val description: String,
)

/** One transcript message as the pane paints it. */
data class RelayTranscriptRow(
    val id: String,
    /** Display name when Relay sent one, else the sender kind (`plugin.js:503`). */
    val attribution: String,
    val senderKind: RelaySenderKind,
    val text: String,
    val timestamp: String?,
    /**
     * Relay's own delivery status, humanised. The frozen contract does not fix
     * a status vocabulary (`relay_proxy.py:293` takes any required string), so
     * this reports what Relay said instead of mapping it into a treatment this
     * app invented.
     */
    val status: String,
    val truncated: Boolean,
    val description: String,
)

/**
 * The one block above the content when Relay is anything other than ready.
 *
 * A state is never an error: a Gateway without the plugin is a fact about that
 * Gateway, so this renders in place of the content it explains and never in a
 * toast (epic #38 boundary).
 *
 * [title] is app-owned and present only where this app has something to add to
 * the sentence. [description] is always app-owned. [detail] is the only text
 * that ever comes from the wire, it is optional, and it is rendered *beside*
 * the app's own sentence — never as the whole explanation.
 */
data class RelayNotice(
    val title: String?,
    val description: String,
    val detail: String? = null,
    /** Present only when the state has a next action the person can take. */
    val action: RelayNoticeAction? = null,
)

enum class RelayNoticeAction {
    /** Re-probe. Only where asking again could plausibly answer differently. */
    Retry,

    /**
     * Desktop's `Authorize Relay` (`desktop/plugin.js:384-388` @ `563a8c8`,
     * the SHA this page pins the Relay plugin at), which redeems the host's
     * one-time grant through `POST /connection/authorize`.
     *
     * This app has no write path to Relay yet — #38 owns it — so the control
     * renders visible, disabled and marked rather than absent: the banner
     * already says authorization is required, and a banner that names the
     * problem and hides the button Desktop offers is the less honest half.
     */
    Authorize,

    /**
     * Open the Gateways screen. Deliberately not named for signing in: it is
     * where a remote leg signs in *and* where a managed SSH leg reconnects,
     * and the availability layer's sentence already says which one this is.
     */
    OpenGateways,
}

/** Everything the Relay surface renders. Selection is UI-only and never persisted. */
data class RelayUiState(
    val notice: RelayNotice? = null,
    /** True only while the very first availability answer is still outstanding. */
    val connecting: Boolean = false,
    val channels: List<RelayChannelRow> = emptyList(),
    /** A channels answer has arrived at least once, so "empty" means empty. */
    val channelsLoaded: Boolean = false,
    val selectedChannelId: String? = null,
    val selectedChannelTitle: String? = null,
    val selectedChannelArchived: Boolean = false,
    val transcript: List<RelayTranscriptRow> = emptyList(),
    val transcriptLoaded: Boolean = false,
    /**
     * The composer under the selected channel's transcript. Rendered only
     * there, so it is only ever meaningful when [showingTranscript] is true.
     */
    val composer: RelayComposerUiState = RelayComposerUiState(),
    /**
     * The last refresh of the visible pane came back unusable and the previous
     * answer is still on screen. Quiet by design — the data is still true, it
     * is just older than this second.
     */
    val stale: Boolean = false,
    /**
     * This Gateway does not expose Relay at all. The entry point says so where
     * Relay would otherwise live, which is the only place that fact is useful.
     */
    val unavailableOnGateway: Boolean = false,
    /**
     * Relay itself answered, so asking it for data is a meaningful thing to be
     * doing. False for a Gateway that has no Relay, a refusal, an answer in a
     * shape this build cannot read, or a connection that has not happened yet.
     */
    val relayAnswered: Boolean = false,
    /**
     * Relay's lane is one the surface actually polls. Strictly narrower than
     * [relayAnswered], which is true for all four lane states: a lane that
     * answered `auth_required`, `offline` or `error` is never asked for data
     * (`RelayViewModel.refreshVisiblePane`), so only this may put a pane into
     * its loading phase. Keying that on [relayAnswered] left a cold start on
     * any of those three lanes claiming to load forever with no request out.
     */
    val relayReady: Boolean = false,
) {
    val showingTranscript: Boolean get() = selectedChannelId != null

    /**
     * Content is shown when there is either something to ask or something
     * already answered. A state that can never produce a list — no Gateway
     * connection, no plugin on it, a refusal, an unreadable answer — renders
     * its own sentence and nothing under it, because a spinner that can never
     * resolve is the one thing worse than an empty screen.
     */
    val showsContent: Boolean
        get() = notice?.action != RelayNoticeAction.OpenGateways &&
            !connecting &&
            (relayAnswered || channelsLoaded || transcriptLoaded)
}

/**
 * Project the settled availability state into the block the surface shows.
 *
 * Device-level states carry the availability layer's own sentence rather than a
 * copy of it. That layer knows something this surface does not: whether a
 * sign-in exists on the live leg at all. On managed SSH and token mode it does
 * not, so the same refusal asks for a reconnect there and a sign-in on a remote
 * Gateway — and this surface renders whichever it was handed.
 *
 * Lane states are this app's own wording, with Relay's own words carried
 * separately by [statusDetail].
 */
internal fun relayNotice(state: RelayAvailabilityState): RelayNotice? {
    if (state.awaitingFirstAnswer) {
        return RelayNotice(
            title = "Connecting to Relay",
            description = "Checking the Relay connection…",
        )
    }
    // The controller deliberately holds no availability and no spinner until
    // the first Connected edge, and for as long as no Gateway is saved at all:
    // nothing has been asked yet, and on a fresh install there is nothing to
    // ask. That is not an answer about Relay, so it must not be rendered as one
    // — and it must not be rendered as a blank screen either. The next step is
    // the Gateway itself, which is why this is the one device-level state whose
    // action is not derived from an availability value.
    val availability = state.availability ?: return RelayNotice(
        title = null,
        description = NOT_CONNECTED_MESSAGE,
        action = RelayNoticeAction.OpenGateways,
    )
    if (availability is RelayAvailability.Available) {
        return availability.channels.laneNotice(availability.statusDetail())
    }
    return RelayNotice(
        title = null,
        // The fallback exists only so a state the layer has no sentence for yet
        // still renders something true.
        description = state.statusMessage() ?: TRANSPORT_DOWN_MESSAGE,
        action = availability.deviceAction(),
    )
}

/**
 * The next step for a device-level state, which is not always the same as the
 * one its sentence describes.
 */
private fun RelayAvailability.deviceAction(): RelayNoticeAction? = when (this) {
    // Nothing to ask again and nowhere to go: this Gateway has no Relay, and
    // an app-version mismatch is not fixed from the Gateways screen either.
    RelayAvailability.Missing,
    RelayAvailability.Incompatible,
    -> null

    // A refused credential is not fixed by asking again; it is fixed on the
    // Gateways screen, whichever leg this is.
    is RelayAvailability.SignInRequired -> RelayNoticeAction.OpenGateways

    // A Gateway that did not answer may answer next time.
    RelayAvailability.GatewayUnreachable -> RelayNoticeAction.Retry

    is RelayAvailability.Available -> null
}

/**
 * Lane wording is Desktop's (hermes-plugin-relay @ `563a8c8`,
 * `desktop/plugin.js:384-412`), adapted where the Desktop sentence describes
 * something this read-only surface does not have.
 *
 * [detail] is the lane's own words, already redacted, collapsed to one line and
 * bounded by [statusDetail] — the one place host-authored text is prepared for
 * this screen. `guidance` is deliberately not rendered: it does not exist at
 * the pinned plugin, and a hint nothing has ever returned is not copy.
 */
private fun RelayChannelsStatus.laneNotice(detail: String?): RelayNotice? = when (state) {
    RelayLaneState.READY -> null
    RelayLaneState.OFFLINE -> RelayNotice(
        title = "Relay is offline",
        description = "Showing the channels and transcript Relay last returned.",
        detail = detail,
        action = RelayNoticeAction.Retry,
    )

    RelayLaneState.AUTH_REQUIRED -> RelayNotice(
        title = "Authorization required",
        description = "Relay needs authorization on the Gateway host before channels can update.",
        detail = detail,
        action = RelayNoticeAction.Authorize,
    )

    RelayLaneState.ERROR -> RelayNotice(
        title = "Relay needs attention",
        description = "Relay returned a recoverable connection error.",
        detail = detail,
        action = RelayNoticeAction.Retry,
    )
}

/**
 * Only a lane Relay calls ready may be polled or listed as live.
 * Desktop gates its interval on exactly this (`desktop/plugin.js:1073-1083`).
 */
internal fun RelayAvailabilityState.laneIsReady(): Boolean =
    (availability as? RelayAvailability.Available)?.channels?.state == RelayLaneState.READY

/**
 * Channel rows in the order the backend returned them.
 *
 * Desktop applies no sort or filter of its own (`desktop/plugin.js:109-130`),
 * and neither does this: the hub owns channel order, and an archived channel
 * stays in place with an annotation rather than being hidden.
 *
 * The one thing it does enforce is that a row id appears once. `id` is the
 * hub's primary key and the list's Compose key, so a repeat is the same
 * channel twice rather than two channels — and a repeated key is not a
 * degraded list but an `IllegalArgumentException` out of `LazyColumn`. The
 * first occurrence wins, which keeps backend order intact. Dropping the
 * duplicate rather than refusing the payload is deliberate: this is a
 * read-only surface, and hiding every honest channel beside one repeated row
 * would be the larger lie.
 */
internal fun relayChannelRows(
    channels: List<RelayChannel>,
    locale: Locale,
    times: RelayTimeLabels,
): List<RelayChannelRow> = channels.map { channel ->
    val archived = channel.archived == true
    val classification = listOfNotNull(
        channel.kind?.humanised(locale),
        channel.visibility?.humanised(locale),
    ).takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    val last = channel.lastMessage
    val sender = last?.senderDisplayName ?: last?.senderKind?.humanised(locale)
    val preview = last?.let { if (sender == null) it.preview else "$sender: ${it.preview}" }
    val timestamp = last?.createdAt?.let(times::of)
    RelayChannelRow(
        id = channel.id,
        title = if (archived) "${channel.title}$ARCHIVED_SUFFIX" else channel.title,
        archived = archived,
        classification = classification,
        preview = preview,
        timestamp = timestamp,
        description = spokenLabel(
            channel.title,
            if (archived) "Archived" else null,
            classification,
            preview,
        ),
    )
}.distinctBy { it.id }

/**
 * The newest-first window Relay returns, rendered oldest to newest.
 *
 * `seq` is the hub's own monotonic order and is required on every projected
 * row (`relay_proxy.py:292-305`), so ordering by it is deterministic whichever
 * direction the window arrived in.
 *
 * Message ids are deduplicated after ordering, for the same reason and on the
 * same rule as [relayChannelRows]: one row per id, first in render order wins,
 * so a repeated id cannot crash a keyed `LazyColumn` and cannot move a row
 * that is already on screen.
 */
internal fun relayTranscriptRows(
    messages: List<RelayMessage>,
    locale: Locale,
    times: RelayTimeLabels,
): List<RelayTranscriptRow> = messages.sortedBy { it.seq }.map { message ->
    val kind = relaySenderKind(message.senderKind)
    val attribution = message.senderDisplayName ?: message.senderKind.humanised(locale)
    val timestamp = times.of(message.createdAt)
    val status = message.status.humanised(locale)
    val truncated = message.truncated == true
    RelayTranscriptRow(
        id = message.id,
        attribution = attribution,
        senderKind = kind,
        text = message.text,
        timestamp = timestamp,
        status = status,
        truncated = truncated,
        description = spokenLabel(
            attribution,
            timestamp,
            message.text,
            status,
            if (truncated) "Truncated by Relay" else null,
        ),
    )
}.distinctBy { it.id }

/**
 * Relay stamps every row with an ISO-8601 instant. Desktop renders that string
 * raw (`desktop/plugin.js:512`), which a phone row cannot afford, so the label
 * is formatted here instead.
 *
 * Built once per projection rather than once per row: a transcript window is up
 * to 50 rows, and parsing the same two patterns and resolving the same locale
 * symbols 50 times is work nobody asked for.
 */
internal class RelayTimeLabels(
    private val zone: ZoneId,
    locale: Locale,
    nowMillis: Long,
) {
    private val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    private val timeOfDay = DateTimeFormatter.ofPattern(TIME_PATTERN, locale)
    private val dateAndTime = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN, locale)

    /** Null for a stamp this build cannot read — never a wall of wire text. */
    fun of(raw: String): String? {
        val instant = runCatching { Instant.parse(raw) }.getOrNull() ?: return null
        val at = instant.atZone(zone)
        val formatter = if (at.toLocalDate() == today) timeOfDay else dateAndTime
        return runCatching { formatter.format(at) }.getOrNull()
    }
}

/**
 * `kind`, `visibility` and message `status` are required strings with no fixed
 * vocabulary in the frozen contract (`relay_proxy.py:265-268,293`). Rendering
 * them through a fixed enum would silently mislabel a value this build has
 * never seen, so the wire token is only made readable, never reinterpreted.
 */
private fun String.humanised(locale: Locale): String {
    val words = trim().replace('_', ' ').replace('-', ' ')
    if (words.isEmpty()) return this
    return words.replaceFirstChar { it.titlecase(locale) }
}

/**
 * One row, one sentence. Screen readers get a single label per row rather than
 * a pile of fragments, so the rule that joins them lives once.
 */
private fun spokenLabel(vararg parts: String?): String =
    parts.filterNotNull().joinToString(". ", postfix = ".")

/** Desktop's separator between the parts of one quiet meta line. */
private const val SEPARATOR = " · "
private const val NOT_CONNECTED_MESSAGE = "Connect to a Gateway to open Relay."
private const val ARCHIVED_SUFFIX = " · archived"
private const val TIME_PATTERN = "HH:mm"
private const val DATE_TIME_PATTERN = "d MMM HH:mm"
