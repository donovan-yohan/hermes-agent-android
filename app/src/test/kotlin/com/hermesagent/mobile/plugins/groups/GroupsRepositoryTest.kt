package com.hermesagent.mobile.plugins.groups

import com.hermesagent.mobile.plugins.PluginHostResult
import com.hermesagent.mobile.plugins.PluginRefusalReason
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun wire(raw: String) = Json.parseToJsonElement(raw)
internal val capabilityWire = wire("""{"protocol_version":2,"driver":false,"persistent_process":false,
    "authority_gateway_id":"fixture","room_link":{"enabled":false},"features":["future"],
    "methods":["groups.log","groups.state","groups.list","groups.capabilities","future"],"max_log_limit":500}""")
internal fun roomWire(id: String = "room", latest: Long = 0) = wire("""{
    "room_id":"$id","name":"Planning","members":[{"member_id":"member","profile":"ops","handle":"ops",
    "target":{"kind":"local","profile":"ops"}}],"latest_seq":$latest,"authority_gateway_id":"fixture",
    "authority_epoch":1,"revision":1,"created_at":1.0,"updated_at":1.0,"idempotent":false}""")
internal fun eventWire(seq: Long = 1, kind: String = "message.user", payload: String = """{"text":"Hello","thread_id":"thread"}"""): JsonElement {
    val actorKind = when {
        kind == "message.user" -> "user"
        kind == "message.member" -> "member"
        kind.startsWith("turn.") || kind in setOf("room.activity", "room.stop_requested") -> "gateway"
        else -> "system"
    }
    return wire("""{
    "room_id":"room","seq":$seq,"event_id":"event-$seq","kind":"$kind","actor":{"kind":"$actorKind","id":"actor"},
    "authority_epoch":1,"payload":$payload,"created_at":1.0,"idempotent":false}""")
}
internal fun logWire(events: List<JsonElement>, cursor: Long, latest: Long = cursor) = buildJsonObject {
    put("events", JsonArray(events)); put("cursor", cursor); put("latest_seq", latest); put("has_more", cursor < latest)
    put("authority", wire("""{"gateway_id":"fixture","epoch":1}"""))
}

class GroupsRepositoryTest {
    @Test fun nullableApprovalIdentitiesAndPeerRoutesHaveSafeProjection() {
        val room = roomWire().jsonObject
        val member = room.getValue("members").jsonArray.single().jsonObject
        val peer = JsonObject(member + mapOf("display_name" to JsonPrimitive(""), "target" to wire("""{
            "kind":"peer","profile":"ops","peer_id":"peer","installation_id":"installation","capability_digest":"digest"}""")))
        val peerRoom = JsonObject(room + ("members" to JsonArray(listOf(peer))))
        val state = parseGroupState(wire("""{"room":$peerRoom,"driver_status":{"running":true,"working":false,
            "blocked":true,"counts":{"indeterminate":1},"pending_actions":[{"kind":"approval","task_id":"task",
            "member_id":"member","session_id":"session","run_id":null,"request_id":null,"execution_generation":1,
            "approval":{"choices":["once","deny"],"secret":"not rendered"}}],
            "peer_routes":[{"room_id":"room","member_id":"member","status":"needs_reauthorization"}]}}"""), "room")
        assertTrue(state.blocked)
        assertEquals(listOf("Approval required"), state.pending)
        assertEquals(listOf("@ops: Needs authorization"), state.peerRoutes)
        val noDriver = parseGroupState(wire("""{"room":${roomWire()},"driver_status":null}"""), "room")
        assertFalse(noDriver.working)
    }

    @Test fun parsesActualFrozenStoreOutputs() {
        val raw = javaClass.getResource("/groups/hosted-store-d177.json")!!.readText()
        val store = wire(raw).jsonObject
        assertEquals("Release plan", parseHostedGroup(store.getValue("list_rooms").jsonArray.single()).name)
        var cursor = 0L
        val lines = mutableListOf<String>()
        store.getValue("read_events_pages").jsonArray.forEach { rawPage ->
            val page = parseGroupLog(rawPage, "synthetic-room", cursor)
            cursor = page.cursor
            lines += page.events.map { it.text }
        }
        assertEquals(3L, cursor)
        assertEquals(listOf("Review the plan.", "Discussion complete", "Renamed to Release plan"), lines)
    }

    @Test fun exactReadRequestsAndByteShortPages() = runTest {
        val requests = mutableListOf<Pair<String, JsonObject>>()
        val answers = ArrayDeque(listOf(capabilityWire,
            wire("""{"rooms":[${roomWire()}],"next_offset":500}"""),
            wire("""{"rooms":[],"next_offset":null}"""),
            wire("""{"room":${roomWire(latest = 2)}}"""),
            logWire(listOf(eventWire()), 1, 2), logWire(listOf(eventWire(2)), 2)))
        val repo = GroupsRepository({ method, params -> requests += method to params; PluginHostResult.Success(answers.removeFirst()) })
        assertFalse(repo.capabilities().driver)
        assertEquals(1, repo.list().size)
        repo.state("room")
        val pages = mutableListOf<GroupLog>()
        repo.log("room", 0, 500, pages::add)
        assertEquals(listOf(1L, 2L), pages.map { it.cursor })
        assertEquals(listOf(
            "groups.capabilities" to "{}",
            "groups.list" to """{"limit":500,"offset":0}""",
            "groups.list" to """{"limit":500,"offset":500}""",
            "groups.state" to """{"room_id":"room","include_disbanded":true}""",
            "groups.log" to """{"room_id":"room","since_seq":0,"limit":500,"include_disbanded":true}""",
            "groups.log" to """{"room_id":"room","since_seq":1,"limit":500,"include_disbanded":true}"""
        ), requests.map { it.first to it.second.toString() })
    }

    @Test fun malformedPageNeverAppliesPartialEvents() = runTest {
        val good = logWire(listOf(eventWire()), 1).jsonObject
        val badEvents = JsonArray(listOf(eventWire(), eventWire(2).jsonObject.let { JsonObject(it - "kind") }))
        val malformed = listOf<JsonElement>(JsonNull, JsonObject(good - "events"),
            JsonObject(good + ("events" to JsonPrimitive("no"))), JsonObject(good + ("events" to badEvents)),
            JsonObject(good + ("cursor" to JsonPrimitive("1"))), JsonObject(good + ("latest_seq" to JsonPrimitive(0))),
            logWire(listOf(eventWire(2)), 2), logWire(emptyList(), 0, 1),
            logWire(listOf(JsonObject(eventWire().jsonObject + ("room_id" to JsonPrimitive("other")))) , 1))
        malformed.forEach { page ->
            var applied = false
            val repo = GroupsRepository({ _, _ -> PluginHostResult.Success(page) })
            try { repo.log("room", 0, 500) { applied = true }; fail("accepted $page") } catch (_: GroupReadFailure) {}
            assertFalse(applied)
        }
    }

    @Test fun paginationBoundsAndMalformedListsFailClosed() = runTest {
        listOf(
            wire("""{"rooms":[],"next_offset":1}"""),
            wire("""{"rooms":[${roomWire()}],"next_offset":0}"""),
            wire("""{"rooms":[${roomWire()}]}"""),
            wire("""{"rooms":[${roomWire()},${roomWire()}],"next_offset":null}""")
        ).forEach { page ->
            try { GroupsRepository({ _, _ -> PluginHostResult.Success(page) }).list(); fail() } catch (_: GroupReadFailure) {}
        }
        var calls = 0
        val repo = GroupsRepository({ _, _ ->
            calls++
            PluginHostResult.Success(logWire(listOf(eventWire(calls.toLong())), calls.toLong(), 10))
        }, pageBudget = 2)
        try { repo.log("room", 0, 500) {}; fail() } catch (_: GroupReadFailure) {}
        assertEquals(2, calls)
    }

    @Test fun capabilitiesAndRefusalsRemainDistinct() = runTest {
        assertEquals(2L, parseGroupCapabilities(capabilityWire).version)
        assertEquals(3L, parseGroupCapabilities(wire("""{"protocol_version":3}""")).version)
        for (result in listOf(PluginHostResult.UnavailableOnGateway,
            PluginHostResult.Refused(4000, "secret"),
            PluginHostResult.Refused(4112, "secret", PluginRefusalReason.RoomHistoryExpired))) {
            try { GroupsRepository({ _, _ -> result }).capabilities(); fail() } catch (e: GroupReadFailure) {
                assertEquals(result == PluginHostResult.UnavailableOnGateway, e.unsupported)
                assertEquals((result as? PluginHostResult.Refused)?.reason, e.reason)
                assertNull(e.message)
            }
        }
    }

    @Test fun serializedNullableEpochAcceptsNullButNotMalformedValues() {
        // _event_from_row always emits the key, including SQL NULL (hosted_rooms.py:506-511).
        for (kind in listOf("message.user", "room.created", "future.kind")) {
            val event = JsonObject(eventWire(kind = kind).jsonObject + ("authority_epoch" to JsonNull))
            assertEquals(1L, parseGroupLog(logWire(listOf(event), 1), "room", 0).cursor)
            for (bad in listOf(JsonPrimitive("1"), JsonPrimitive(false), JsonPrimitive(-1), JsonObject(emptyMap()))) {
                try {
                    parseGroupLog(logWire(listOf(JsonObject(event + ("authority_epoch" to bad))), 1), "room", 0)
                    fail("accepted malformed epoch")
                } catch (_: InvalidGroupWire) {}
            }
        }
    }

    @Test fun everyEventProjectionAndUnknownActorIsSafe() {
        val coordinates = """"discussion_event_id":"discussion","member_id":"member","member_index":0,"round_index":0,"task_id":"task","thread_id":"thread","turn_id":"turn","seen_through_seq":1"""
        val cases = listOf(
            Triple("message.user", """{"text":"Hello","thread_id":"thread"}""", "Hello"),
            Triple("message.member", "{$coordinates,\"text\":\"Reply\"}", "Reply"),
            Triple("turn.settled", "{$coordinates,\"passed\":true,\"message_event_id\":null}", "Passed"),
            Triple("turn.failed", "{$coordinates,\"error\":\"secret backend prose\"}", "Turn failed"),
            Triple("turn.cancelled", "{$coordinates,\"reason\":\"secret\"}", "Stopped"),
            Triple("turn.deferred", "{$coordinates,\"execution_generation\":1,\"reason\":\"secret\"}", "Member unavailable"),
            Triple("room.activity", """{"status":"bounded","reason_code":"max_rounds","thread_id":"thread","discussion_event_id":"discussion"}""", "Discussion limit reached"),
            Triple("room.stop_requested", """{"cancel_id":"cancel"}""", "Stopped"),
            Triple("room.renamed", """{"name":"New name"}""", "Renamed to New name"),
            Triple("room.disbanded", """{"room_id":"room"}""", "Group Chat disbanded"),
            Triple("authority.claimed", """{"previous_gateway_id":"old","authority_gateway_id":"new","authority_epoch":2}""", "Group Chat authority changed"),
            Triple("authority.lost", """{"previous_gateway_id":"old","authority_gateway_id":"new","authority_epoch":2}""", "Managed by another Gateway"),
            Triple("future.kind", """{"secret":"hidden"}""", "Group Chat updated")
        )
        cases.forEach { (kind, payload, expected) ->
            assertEquals(expected, parseGroupLog(logWire(listOf(eventWire(kind = kind, payload = payload)), 1), "room", 0).events.single().text)
        }
        val unknownActor = JsonObject(eventWire().jsonObject + ("actor" to wire("""{"kind":"future","id":"actor"}""")))
        assertEquals("Group Chat updated", parseGroupLog(logWire(listOf(unknownActor), 1), "room", 0).events.single().text)
    }
}
