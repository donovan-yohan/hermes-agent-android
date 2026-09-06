package com.hermesagent.mobile.plugins.relay

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projection is pure, so it is tested with a fixed instant, a fixed zone
 * and a fixed locale. A test that read the machine's clock would pass in one
 * timezone and fail in the next.
 */
class RelayViewStateTest {

    @Test
    fun `channels keep the order Relay returned and annotate archived in place`() {
        val rows = relayChannelRows(
            listOf(
                channel("zulu", title = "zulu"),
                channel("alpha", title = "alpha", archived = true),
                channel("mike", title = "mike"),
            ),
            LOCALE,
            times(),
        )

        // Desktop applies no sort and no filter of its own; the hub owns order.
        assertEquals(listOf("zulu", "alpha", "mike"), rows.map { it.id })
        // The annotation lives on the name line, not in a badge or a bucket.
        assertEquals("alpha · archived", rows[1].title)
        assertTrue(rows[1].archived)
        assertFalse(rows[0].archived)
    }

    @Test
    fun `a channel row carries classification, preview and one spoken label`() {
        val row = relayChannelRows(
            listOf(
                channel(
                    id = "c1",
                    title = "product",
                    kind = "direct_message",
                    visibility = "private",
                    last = RelayLastMessage(
                        id = "m9",
                        seq = 9,
                        preview = "parity is green",
                        senderKind = "human",
                        status = "delivered",
                        createdAt = "2026-08-26T09:14:00Z",
                        senderDisplayName = "Ada",
                    ),
                ),
            ),
            LOCALE,
            times(),
        ).single()

        assertEquals("Direct message · Private", row.classification)
        assertEquals("Ada: parity is green", row.preview)
        assertEquals("09:14", row.timestamp)
        assertEquals("product. Direct message · Private. Ada: parity is green.", row.description)
    }

    @Test
    fun `a channel with no last message shows no preview and no timestamp`() {
        val row = relayChannelRows(listOf(channel("c1", title = "quiet")), LOCALE, times()).single()

        assertNull(row.preview)
        assertNull(row.timestamp)
        assertNull(row.classification)
        assertEquals("quiet.", row.description)
    }

    @Test
    fun `the newest-first window renders oldest to newest`() {
        val rows = relayTranscriptRows(
            // Relay's window arrives newest first; the transcript reads the
            // other way, so seq — the hub's own order — decides.
            listOf(message("m3", seq = 3), message("m1", seq = 1), message("m2", seq = 2)),
            LOCALE,
            times(),
        )

        // Ids here are named for their seq, so this is the ordering assertion.
        assertEquals(listOf("m1", "m2", "m3"), rows.map { it.id })
        assertEquals(listOf("message 1", "message 2", "message 3"), rows.map { it.text })
    }

    @Test
    fun `attribution prefers the display name and an unknown sender kind stays quiet`() {
        val named = relayTranscriptRows(
            listOf(message("m1", seq = 1, senderKind = "agent", displayName = "Hermes")),
            LOCALE,
            times(),
        ).single()
        assertEquals("Hermes", named.attribution)
        assertEquals(RelaySenderKind.Agent, named.senderKind)

        val anonymous = relayTranscriptRows(
            listOf(message("m2", seq = 2, senderKind = "webhook")),
            LOCALE,
            times(),
        ).single()
        assertEquals("Webhook", anonymous.attribution)
        // An unrecognised kind lands on the quietest treatment, never on one
        // this app invented for it.
        assertEquals(RelaySenderKind.System, anonymous.senderKind)
    }

    @Test
    fun `status and truncation are reported as Relay wrote them`() {
        val row = relayTranscriptRows(
            listOf(message("m1", seq = 1, status = "pending_delivery", truncated = true)),
            LOCALE,
            times(),
        ).single()

        assertEquals("Pending delivery", row.status)
        assertTrue(row.truncated)
        assertTrue(row.description.endsWith("Truncated by Relay."))
    }

    @Test
    fun `timestamps use time of day today and a date before that`() {
        assertEquals("09:14", times().of("2026-08-26T09:14:00Z"))
        assertEquals("2 Aug 17:40", times().of("2026-08-02T17:40:00Z"))
    }

    @Test
    fun `an unparseable stamp becomes no label rather than wire text`() {
        assertNull(times().of("yesterday afternoon"))
        val row = relayTranscriptRows(
            listOf(message("m1", seq = 1, createdAt = "not-a-timestamp")),
            LOCALE,
            times(),
        ).single()
        assertNull(row.timestamp)
    }

    @Test
    fun `a Gateway without the plugin is a state beside the entry point`() {
        val notice = relayNotice(RelayAvailabilityState(RelayAvailability.Missing))

        assertNotNull(notice)
        assertEquals(RELAY_UNAVAILABLE_ON_GATEWAY_MESSAGE, notice?.description)
        // Not an error, and nothing to retry: this Gateway simply has no Relay.
        assertNull(notice?.action)
        assertNull(notice?.detail)
    }

    @Test
    fun `a ready lane shows no notice at all`() {
        assertNull(relayNotice(RelayAvailabilityState(available(RelayLaneState.READY))))
        assertTrue(RelayAvailabilityState(available(RelayLaneState.READY)).laneIsReady())
        assertFalse(RelayAvailabilityState(available(RelayLaneState.OFFLINE)).laneIsReady())
        assertFalse(RelayAvailabilityState(RelayAvailability.Missing).laneIsReady())
    }

    @Test
    fun `host-written lane text sits beside this app's sentence, never instead of it`() {
        val notice = relayNotice(
            RelayAvailabilityState(
                available(RelayLaneState.ERROR, message = "upstream hub refused the read"),
            ),
        )

        assertEquals("Relay needs attention", notice?.title)
        assertEquals("Relay returned a recoverable connection error.", notice?.description)
        assertEquals("upstream hub refused the read", notice?.detail)
        assertEquals(RelayNoticeAction.Retry, notice?.action)
    }

    @Test
    fun `guidance is never rendered, only the lane's message`() {
        // `guidance` does not exist at the pinned plugin and the data layer
        // deliberately keeps it out of statusDetail(); a hint nothing has ever
        // returned is not copy.
        val notice = relayNotice(
            RelayAvailabilityState(
                available(RelayLaneState.AUTH_REQUIRED, guidance = "ask the host operator"),
            ),
        )

        assertEquals("Authorization required", notice?.title)
        assertNull(notice?.detail)
    }

    @Test
    fun `a lane that says nothing renders this app's sentence alone`() {
        val notice = relayNotice(RelayAvailabilityState(available(RelayLaneState.OFFLINE)))

        assertEquals("Relay is offline", notice?.title)
        assertNull(notice?.detail)
    }

    @Test
    fun `a refused credential asks for a sign-in only on a leg that has one`() {
        val remote = relayNotice(
            RelayAvailabilityState(
                RelayAvailability.SignInRequired(RelaySignInReason.SessionExpired),
                signInAvailable = true,
            ),
        )
        assertEquals(RELAY_SIGN_IN_MESSAGE, remote?.description)

        // Managed SSH and token mode have no Gateway sign-in, so the same
        // refusal has to ask for the reconnect that is the real remedy. The
        // availability layer owns that sentence; this surface renders it.
        val managed = relayNotice(
            RelayAvailabilityState(
                RelayAvailability.SignInRequired(RelaySignInReason.SessionExpired),
                signInAvailable = false,
            ),
        )
        assertEquals(TRANSPORT_DOWN_MESSAGE, managed?.description)

        // Either way the next step is the same screen, and it is not a re-probe.
        assertEquals(RelayNoticeAction.OpenGateways, remote?.action)
        assertEquals(RelayNoticeAction.OpenGateways, managed?.action)
        assertNull(remote?.detail)
    }

    @Test
    fun `nothing asked yet is neither a spinner nor an answer about Relay`() {
        // The controller holds no availability and no spinner before the first
        // Connected edge. That is not "unreachable" and must not paint blank.
        val notice = relayNotice(RelayAvailabilityState())

        assertEquals("Connect to a Gateway to open Relay.", notice?.description)
        assertEquals(RelayNoticeAction.OpenGateways, notice?.action)
        assertFalse(RelayUiState(notice = notice).showsContent)
    }

    @Test
    fun `a Gateway with no Relay offers no action, because there is none`() {
        assertNull(relayNotice(RelayAvailabilityState(RelayAvailability.Missing))?.action)
        assertNull(relayNotice(RelayAvailabilityState(RelayAvailability.Incompatible))?.action)
        assertEquals(
            RelayNoticeAction.Retry,
            relayNotice(RelayAvailabilityState(RelayAvailability.GatewayUnreachable))?.action,
        )
    }

    @Test
    fun `the only spinner is the first probe, and it hides the content`() {
        val state = RelayUiState(
            notice = relayNotice(RelayAvailabilityState(availability = null, probing = true)),
            connecting = true,
        )

        assertEquals("Connecting to Relay", state.notice?.title)
        assertFalse(state.showsContent)
        assertTrue(RelayUiState(channelsLoaded = true).showsContent)
    }

    @Test
    fun `a state that can never answer shows no content area at all`() {
        // No plugin here, nothing ever loaded: the sentence is the whole screen.
        assertFalse(RelayUiState(unavailableOnGateway = true).showsContent)
        assertFalse(RelayUiState(relayAnswered = false).showsContent)
        assertFalse(
            RelayUiState(
                notice = relayNotice(RelayAvailabilityState()),
                channelsLoaded = true,
            ).showsContent,
        )
        // But an answer that has gone stale keeps its rows under the notice.
        assertTrue(RelayUiState(channelsLoaded = true, stale = true).showsContent)
        assertTrue(RelayUiState(relayAnswered = true).showsContent)
    }

    @Test
    fun `a pane that never loaded shows a retry, a spinner or nothing — never all three`() {
        // Both panes ask this the same way, so the decision is asserted once.
        assertEquals(
            RelayPanePhase.Retry,
            relayPanePhase(loaded = false, stale = true, relayReady = true, isEmpty = true),
        )
        assertEquals(
            RelayPanePhase.Loading,
            relayPanePhase(loaded = false, stale = false, relayReady = true, isEmpty = true),
        )
        // Nothing loaded and nothing being asked: the notice is the screen.
        // The lane may well have answered — `offline`, `auth_required` and
        // `error` all did — but none of them is polled, so none of them may
        // put a spinner on screen that no request will ever resolve.
        assertEquals(
            RelayPanePhase.Silent,
            relayPanePhase(loaded = false, stale = false, relayReady = false, isEmpty = true),
        )
        assertEquals(
            RelayPanePhase.Empty,
            relayPanePhase(loaded = true, stale = false, relayReady = true, isEmpty = true),
        )
        // A stale answer is still an answer: the rows stay.
        assertEquals(
            RelayPanePhase.Content,
            relayPanePhase(loaded = true, stale = true, relayReady = true, isEmpty = false),
        )
        // And a lane that stopped being ready keeps the rows it already has.
        assertEquals(
            RelayPanePhase.Content,
            relayPanePhase(loaded = true, stale = true, relayReady = false, isEmpty = false),
        )
    }

    @Test
    fun `a repeated channel id projects one row, in the place the first held`() {
        // `id` is the hub's primary key and the list's Compose key: a repeat is
        // the same channel twice, and a repeated key crashes a keyed LazyColumn.
        val rows = relayChannelRows(
            listOf(
                channel("dup", title = "first"),
                channel("other", title = "other"),
                channel("dup", title = "second"),
            ),
            LOCALE,
            times(),
        )

        assertEquals(listOf("dup", "other"), rows.map { it.id })
        // First occurrence wins, so backend order is left exactly as it was.
        assertEquals("first", rows.first().title)
    }

    @Test
    fun `a repeated message id projects one row, after seq ordering`() {
        val rows = relayTranscriptRows(
            listOf(message("dup", seq = 3), message("m1", seq = 1), message("dup", seq = 2)),
            LOCALE,
            times(),
        )

        // Ordering runs first, so the surviving row is the first in *render*
        // order and cannot move under a reader between two polls.
        assertEquals(listOf("m1", "dup"), rows.map { it.id })
        assertEquals("message 2", rows.last().text)
    }

    private companion object {
        /** 2026-08-26T12:00:00Z, so "today" is a fixed calendar date. */
        const val NOW = 1_787_745_600_000L
        val ZONE: ZoneId = ZoneId.of("UTC")
        val LOCALE: Locale = Locale.UK

        /** Fixed clock, fixed zone, fixed locale — the whole point of these. */
        fun times() = RelayTimeLabels(ZONE, LOCALE, NOW)

        fun available(lane: RelayLaneState, message: String? = null, guidance: String? = null) =
            RelayAvailability.Available(RelayChannelsStatus(lane, message, guidance))

        fun channel(
            id: String,
            title: String,
            kind: String? = null,
            visibility: String? = null,
            archived: Boolean? = null,
            last: RelayLastMessage? = null,
        ) = RelayChannel(
            id = id,
            title = title,
            kind = kind,
            visibility = visibility,
            archived = archived,
            latestSeq = last?.seq,
            messageCount = null,
            threadCount = null,
            lastMessage = last,
        )

        fun message(
            id: String,
            seq: Long,
            senderKind: String = "human",
            displayName: String? = null,
            status: String = "delivered",
            truncated: Boolean? = null,
            createdAt: String = "2026-08-26T09:14:00Z",
        ) = RelayMessage(
            id = id,
            channelId = "c1",
            seq = seq,
            kind = "message",
            status = status,
            senderKind = senderKind,
            senderId = "s-$id",
            senderDisplayName = displayName,
            text = "message $seq",
            format = RelayMessageFormat.TEXT,
            threadId = null,
            parentMessageId = null,
            createdAt = createdAt,
            updatedAt = createdAt,
            truncated = truncated,
            clientMessageId = null,
        )
    }
}
