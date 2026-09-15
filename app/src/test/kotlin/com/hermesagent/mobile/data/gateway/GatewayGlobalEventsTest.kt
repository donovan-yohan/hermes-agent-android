package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session-less lane in isolation.
 *
 * `GatewayEvent` here is the same frame the socket hands the repository, so the
 * envelope a session-less broadcast really arrives in — empty `session_id`, no
 * `seq` (`tui_gateway/event_replay.py:46-49` @
 * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`) — is what these cases drive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayGlobalEventsTest {

    @Test
    fun `session-less broadcasts route to the global lane and session events do not`() {
        listOf(
            "gateway.ready",
            "session.reclaimed",
            "cron.changed",
            "pet.changed",
            "sessions.changed",
            "bot_relay.outbox.pending",
        ).forEach { type ->
            assertEquals(
                "$type is broadcast with no session to route by",
                GatewayEventLane.Global,
                gatewayEventLane(type),
            )
        }

        // Every other admitted type is a session event — including the ones an
        // older Local backend emits with no `session_id` at all: those are
        // attributed to the pinned unscoped runtime inside `applyEvent`, and
        // steering them here would drop live transcript bytes.
        listOf(
            "session.info",
            "message.start",
            "message.delta",
            "message.complete",
            "reasoning.delta",
            "reasoning.available",
            "thinking.delta",
            "tool.start",
            "tool.progress",
            "tool.complete",
            "status.update",
            "error",
            "request.cancel",
        ).forEach { type ->
            assertEquals(type, GatewayEventLane.Session, gatewayEventLane(type))
        }

        // A type this client has never heard of is not a lane at all; the
        // allow-list refuses it before dispatch, and this stays total rather
        // than throwing.
        assertEquals(GatewayEventLane.Session, gatewayEventLane("future.broadcast"))
    }

    @Test
    fun `a change hint carries its kind and the backend payload`() = runTest {
        val lane = GatewayGlobalEventLane()
        val hints = mutableListOf<GatewayChangeHint>()
        val tap = launch { lane.changeHints.collect { hints += it } }
        runCurrent()

        // Only the sessions hint claims the backend's rows moved, which is the
        // one refetch this repository owns itself.
        assertEquals(true, lane.accept(sessionLess("sessions.changed", buildJsonObject { put("count", JsonPrimitive(3)) })))
        assertEquals(false, lane.accept(sessionLess("cron.changed", JsonNull)))
        assertEquals(false, lane.accept(sessionLess("pet.changed", buildJsonObject { put("enabled", JsonPrimitive(false)) })))
        assertEquals(false, lane.accept(sessionLess("bot_relay.outbox.pending", JsonNull)))
        runCurrent()

        assertEquals(
            listOf(
                GatewayChangeHintKind.Sessions,
                GatewayChangeHintKind.Cron,
                GatewayChangeHintKind.Pet,
                GatewayChangeHintKind.BotRelayOutbox,
            ),
            hints.map { it.kind },
        )
        assertEquals("3", (hints[0].payload as JsonObject).getValue("count").jsonPrimitive.content)
        assertEquals("false", (hints[2].payload as JsonObject).getValue("enabled").jsonPrimitive.content)
        tap.cancel()
    }

    /**
     * The lane is a *session-less* path. A session event that arrives with no
     * `seq` still belongs to the session lane, and one that carries a `seq` is
     * the watermark source — never a hint.
     */
    @Test
    fun `a session event never reaches the hint stream`() = runTest {
        val lane = GatewayGlobalEventLane()
        val hints = mutableListOf<GatewayChangeHint>()
        val tap = launch { lane.changeHints.collect { hints += it } }
        runCurrent()

        lane.noteSessionSeq("runtime-a", 12)
        runCurrent()

        assertEquals(emptyList<GatewayChangeHint>(), hints)
        tap.cancel()
    }

    /**
     * A type from a newer backend than this client must be dropped, never
     * thrown: the pin's change watcher broadcasts a wider set than the four
     * hint kinds this client renders (`tui_gateway/change_watcher.py:177-184` @
     * the pin SHA).
     */
    @Test
    fun `an unknown session-less type is dropped without throwing`() = runTest {
        val lane = GatewayGlobalEventLane()
        val hints = mutableListOf<GatewayChangeHint>()
        val tap = launch { lane.changeHints.collect { hints += it } }
        runCurrent()

        assertEquals(false, lane.accept(sessionLess("platforms.changed", JsonNull)))
        assertEquals(false, lane.accept(sessionLess("future.broadcast", JsonNull)))
        runCurrent()

        assertEquals(emptyList<GatewayChangeHint>(), hints)
        assertNull(lane.replayEpoch())
        assertEquals(emptyMap<String, Long>(), lane.watermarks())
        tap.cancel()
    }

    @Test
    fun `a changed replay epoch clears the cached watermarks and an unchanged one keeps them`() {
        val lane = GatewayGlobalEventLane()
        lane.noteSessionSeq("runtime-a", 97)
        lane.noteSessionSeq("runtime-b", 4)
        assertEquals(mapOf("runtime-a" to 97L, "runtime-b" to 4L), lane.watermarks())

        // A change on disk is not a restart.
        lane.accept(sessionLess("sessions.changed", JsonNull))
        assertEquals(2, lane.watermarks().size)

        lane.accept(ready("epoch-one"))
        assertEquals("epoch-one", lane.replayEpoch())
        assertEquals(
            "a first sighting of the process is a restart as far as any watermark is concerned",
            emptyMap<String, Long>(),
            lane.watermarks(),
        )

        lane.noteSessionSeq("runtime-a", 12)
        lane.accept(ready("epoch-one"))
        assertEquals("the same process keeps its watermarks", mapOf("runtime-a" to 12L), lane.watermarks())

        lane.noteSessionSeq("runtime-a", 300)
        lane.accept(ready("epoch-two"))
        assertEquals("epoch-two", lane.replayEpoch())
        assertEquals(
            "a restarted gateway renumbers every session from 1, so an old high watermark reads as 'nothing newer'",
            emptyMap<String, Long>(),
            lane.watermarks(),
        )
    }

    @Test
    fun `a backend that advertises no epoch cannot be resumed from`() {
        val lane = GatewayGlobalEventLane()
        lane.accept(ready("epoch-one"))
        lane.noteSessionSeq("runtime-a", 9)

        lane.accept(ready(null))

        assertNull(lane.replayEpoch())
        assertEquals("undetectable is not the same as unchanged", emptyMap<String, Long>(), lane.watermarks())
    }

    @Test
    fun `watermarks never move backwards and ignore frames with no sequence`() {
        val lane = GatewayGlobalEventLane()
        lane.noteSessionSeq("runtime-a", 5)
        lane.noteSessionSeq("runtime-a", 3)
        lane.noteSessionSeq("runtime-a", null)
        lane.noteSessionSeq(null, 7)
        lane.noteSessionSeq("  ", 7)

        assertEquals(mapOf("runtime-a" to 5L), lane.watermarks())
    }

    @Test
    fun `a changed connection drops the epoch and the watermarks it guarded`() {
        val lane = GatewayGlobalEventLane()
        lane.accept(ready("epoch-one"))
        lane.noteSessionSeq("runtime-a", 42)

        lane.clearConnectionState()

        assertNull(lane.replayEpoch())
        assertTrue(lane.watermarks().isEmpty())
    }

    private fun ready(epoch: String?): GatewayEvent = sessionLess(
        "gateway.ready",
        buildJsonObject {
            put("change_events", JsonPrimitive(true))
            put("replay_epoch", epoch?.let(::JsonPrimitive) ?: JsonNull)
        },
    )

    /** The envelope the socket builds for a broadcast: empty `session_id`, no `seq`. */
    private fun sessionLess(type: String, payload: JsonElement): GatewayEvent =
        GatewayEvent(type, null, payload)
}
