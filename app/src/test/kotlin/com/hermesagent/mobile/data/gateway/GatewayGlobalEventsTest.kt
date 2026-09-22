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
    fun `buffered skin retains its source profile`() = runTest {
        val lane = GatewayGlobalEventLane()
        val origin = com.hermesagent.mobile.data.prefs.ComposerControlsScope("connection-a", "profile-a")
        val payload = buildJsonObject { put("name", JsonPrimitive("custom")) }
        lane.accept(sessionLess("skin.changed", payload), origin)
        val skins = mutableListOf<GatewaySkinChange>()
        val tap = launch { lane.skinChanges.collect { skins += it } }
        runCurrent()
        assertEquals(origin, skins.single().sourceScope)
        tap.cancel()
    }

    @Test
    fun `ready skin survives a late subscriber but not a connection reset`() = runTest {
        val lane = GatewayGlobalEventLane()
        val payload = buildJsonObject { put("name", JsonPrimitive("custom")) }
        lane.accept(sessionLess("gateway.ready", buildJsonObject { put("skin", payload) }))
        val skins = mutableListOf<GatewaySkinChange>()
        val first = launch { lane.skinChanges.collect { skins += it } }
        runCurrent()
        assertEquals(listOf(GatewaySkinChange(false, payload, 0L)), skins)
        first.cancel()
        runCurrent()
        lane.clearConnectionState()
        skins.clear()
        val next = launch { lane.skinChanges.collect { skins += it } }
        runCurrent()
        assertTrue(skins.isEmpty())
        next.cancel()
    }

    @Test
    fun `queued skin events retain admission generation rather than delivery generation`() = runTest {
        var generation = 7L
        val lane = GatewayGlobalEventLane { generation }
        val skins = mutableListOf<GatewaySkinChange>()
        val tap = launch { lane.skinChanges.collect { skins += it } }
        runCurrent()
        val payload = buildJsonObject { put("name", JsonPrimitive("custom")) }
        lane.accept(sessionLess("skin.changed", payload))
        generation = 8L
        runCurrent()
        assertEquals(listOf(GatewaySkinChange(true, payload, 7L)), skins)
        lane.accept(sessionLess("skin.changed", payload))
        runCurrent()
        assertEquals(8L, skins.last().endpointGeneration)
        tap.cancel()
    }

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

    /**
     * The deliverable of #212: admission, classification and routing are one
     * table, and this probe enumerates the admitted set. The `when` below is
     * exhaustive over [GatewayGlobalEventType], so a type added to the table
     * does not compile until this test names its handler too — which is what
     * stops a subscribed type from being silently unhandled.
     */
    @Test
    fun `every admitted type reaches the handler its table entry names`() = runTest {
        val lane = GatewayGlobalEventLane()
        val hints = mutableListOf<GatewayChangeHint>()
        val tap = launch { lane.changeHints.collect { hints += it } }
        runCurrent()

        // One membership question, asked twice: the allow-list is the table's
        // own wire types, so a type classified `Global` cannot be unsubscribed
        // and a subscribed type cannot fall out of the lane.
        assertEquals(
            "the allow-list must be the table's wire types",
            GatewayGlobalEventType.entries.map { it.wire }.toSet(),
            GATEWAY_GLOBAL_EVENT_TYPES,
        )

        GATEWAY_GLOBAL_EVENT_TYPES.forEach { type ->
            assertEquals(
                "$type is broadcast with no session to route by",
                GatewayEventLane.Global,
                gatewayEventLane(type),
            )

            val hintsBefore = hints.size
            val refetchRequested = when (val entry = GatewayGlobalEventType.fromWire(type)) {
                null -> org.junit.Assert.fail("$type is subscribed but names no table entry")

                // The one frame that carries the process's seq numbering.
                GatewayGlobalEventType.GatewayReady -> {
                    assertEquals("$type must reach the global lane", GatewayGlobalEventOwner.Lane, entry.owner)
                    lane.accept(ready("epoch-one"))
                    assertEquals("$type must adopt the advertised epoch", "epoch-one", lane.replayEpoch())
                    false
                }

                // Skin changes have a payload stream, not a refetch hint.
                GatewayGlobalEventType.SkinChanged -> {
                    assertEquals(GatewayGlobalEventOwner.Lane, entry.owner)
                    val skins = mutableListOf<GatewaySkinChange>()
                    val skinTap = launch { lane.skinChanges.collect { skins += it } }
                    runCurrent()
                    val payload = buildJsonObject { put("name", JsonPrimitive("custom")) }
                    val refresh = lane.accept(sessionLess(type, payload))
                    runCurrent()
                    assertEquals(listOf(GatewaySkinChange(true, payload, 0L)), skins)
                    skinTap.cancel()
                    refresh
                }

                // The four change hints: each must publish its own kind.
                GatewayGlobalEventType.CronChanged,
                GatewayGlobalEventType.PetChanged,
                GatewayGlobalEventType.SessionsChanged,
                GatewayGlobalEventType.BotRelayOutbox,
                -> {
                    assertEquals("$type must reach the global lane", GatewayGlobalEventOwner.Lane, entry.owner)
                    val refresh = lane.accept(sessionLess(type, JsonNull))
                    runCurrent()
                    assertEquals("$type reached no handler", hintsBefore + 1, hints.size)
                    assertEquals("$type published the wrong kind", HINT_KIND_BY_ENTRY.getValue(entry), hints.last().kind)
                    refresh
                }

                GatewayGlobalEventType.SessionReclaimed -> {
                    // Settled by the repository's own reclaim path instead,
                    // which holds the runtime identity map that settling and
                    // unbinding need. The lane refusing it rather than claiming
                    // it is what keeps that routing honest.
                    assertEquals(
                        "$type's handler is the repository's reclaim path",
                        GatewayGlobalEventOwner.Reclaim,
                        entry.owner,
                    )
                    val refresh = lane.accept(sessionLess(type, JsonNull))
                    runCurrent()
                    assertEquals("$type is not this lane's to settle", hintsBefore, hints.size)
                    refresh
                }
            }

            assertEquals(
                "$type: only the sessions hint asks this repository for a refetch",
                type == GatewayGlobalEventType.SessionsChanged.wire,
                refetchRequested,
            )
        }
        tap.cancel()
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

    private companion object {
        /** What each hint type must publish; absent for the two lifecycle frames. */
        val HINT_KIND_BY_ENTRY = mapOf(
            GatewayGlobalEventType.CronChanged to GatewayChangeHintKind.Cron,
            GatewayGlobalEventType.PetChanged to GatewayChangeHintKind.Pet,
            GatewayGlobalEventType.SessionsChanged to GatewayChangeHintKind.Sessions,
            GatewayGlobalEventType.BotRelayOutbox to GatewayChangeHintKind.BotRelayOutbox,
        )
    }
}
