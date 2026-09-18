package com.hermesagent.mobile.plugins.groups

import com.hermesagent.mobile.plugins.PluginHostResult
import com.hermesagent.mobile.plugins.PluginRefusalReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupSendWireTest {
    private val testIdentity = GroupSendIdentity(
        savedRow = "saved-1",
        endpointBinding = "binding-1",
        gateway = "gateway-1"
    )
    private val testTarget = GroupSendTarget(testIdentity, "room-1")

    @Test
    fun `identifiers and payloads are strictly validated and normalized`() {
        assertEquals("room-1", sendIdentifier("room-1"))
        assertThrows(IllegalArgumentException::class.java) { sendIdentifier("") }
        assertThrows(IllegalArgumentException::class.java) { sendIdentifier(" space-start") }
        assertThrows(IllegalArgumentException::class.java) { sendIdentifier("a".repeat(129)) }

        val payload = GroupSendPayload.normalize("  hello world \n ", "thread-1")
        assertEquals("hello world", payload.text)
        assertEquals("thread-1", payload.thread)
        assertEquals("""{"text":"hello world","thread_id":"thread-1"}""", payload.wire().toString())
    }

    @Test
    fun `stored user event id derives deterministic sha256 user prefix`() {
        val raw = "client-event-123"
        val derived = storedUserEventId(raw)
        assertTrue(derived.startsWith("user:"))
        assertEquals(derived, storedUserEventId(raw))
        assertNotEquals(derived, storedUserEventId("client-event-456"))
    }

    @Test
    fun `matchingSendEvent strictly checks room, kind, actor, epoch, payload, and derives seq`() {
        val op = GroupSendOperation(
            target = testTarget,
            rawId = "client-1",
            payload = GroupSendPayload.normalize("hi", "thread-1")
        )
        val validEvent = buildJsonObject {
            put("event_id", storedUserEventId("client-1"))
            put("room_id", "room-1")
            put("kind", "message.user")
            put("actor", buildJsonObject {
                put("kind", "user")
                put("id", "desktop")
            })
            put("payload", op.payload.wire())
            put("authority_epoch", 1)
            put("created_at", 123.456)
            put("idempotent", false)
            put("seq", 42)
        }

        assertEquals(42L, matchingSendEvent(validEvent, op))

        // Different event id returns null
        val diffId = buildJsonObject {
            validEvent.forEach { (k, v) -> if (k == "event_id") put(k, "user:other") else put(k, v) }
        }
        assertNull(matchingSendEvent(diffId, op))

        // Wrong actor id fails require
        val wrongActor = buildJsonObject {
            validEvent.forEach { (k, v) ->
                if (k == "actor") put("actor", buildJsonObject { put("kind", "user"); put("id", "android") })
                else put(k, v)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { matchingSendEvent(wrongActor, op) }
    }

    @Test
    fun `parseSendReceipt validates client_event_id and accepted`() {
        val op = GroupSendOperation(
            target = testTarget,
            rawId = "client-1",
            payload = GroupSendPayload.normalize("hi", "thread-1")
        )
        val eventObj = buildJsonObject {
            put("event_id", storedUserEventId("client-1"))
            put("room_id", "room-1")
            put("kind", "message.user")
            put("actor", buildJsonObject { put("kind", "user"); put("id", "desktop") })
            put("payload", op.payload.wire())
            put("authority_epoch", 1)
            put("created_at", 10.0)
            put("idempotent", false)
            put("seq", 1)
        }
        val response = buildJsonObject {
            put("client_event_id", "client-1")
            put("accepted", true)
            put("driver_started", true)
            put("event", eventObj)
        }

        val receipt = parseSendReceipt(response, op)
        assertEquals(storedUserEventId("client-1"), receipt.storedId)
        assertEquals(1L, receipt.sequence)
        assertEquals(true, receipt.driverStarted)
    }

    @Test
    fun `sendResult maps plugin host errors to typed GroupSendProblems`() {
        val unavailable = sendResultCatching(PluginHostResult.UnavailableOnGateway)
        assertEquals(GroupSendProblem.Unsupported, unavailable.problem)

        val expired = sendResultCatching(PluginHostResult.Refused(4111, "expired", PluginRefusalReason.RoomHistoryExpired))
        assertEquals(GroupSendProblem.Expired, expired.problem)

        val authority = sendResultCatching(PluginHostResult.Refused(4111, "conflict", PluginRefusalReason.AuthorityConflict))
        assertEquals(GroupSendProblem.AuthorityBlocked, authority.problem)

        val workerDown = sendResultCatching(PluginHostResult.Refused(4123, "down"))
        assertEquals(GroupSendProblem.WorkerUnavailable, workerDown.problem)

        val transportUncertain = sendResultCatching(PluginHostResult.Refused(0, "timeout"))
        assertEquals(GroupSendProblem.TransportUncertain, transportUncertain.problem)

        val serverRefused = sendResultCatching(PluginHostResult.Refused(5112, "other"))
        assertEquals(GroupSendProblem.ServerRefused, serverRefused.problem)
    }

    private fun sendResultCatching(result: PluginHostResult): GroupSendFailure {
        try {
            sendResult(result)
            fail("Expected GroupSendFailure")
            throw RuntimeException()
        } catch (e: GroupSendFailure) {
            return e
        }
    }
}
