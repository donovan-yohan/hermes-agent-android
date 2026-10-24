package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.plugins.groups.parseGroupLog
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NotificationGroupIdentityTest {
    @Test fun retainsDurableEventIdAndTimestampForMessageDedupe() {
        val log = parseGroupLog(Json.parseToJsonElement("""{"authority":{"gateway_id":"gw","epoch":1},"cursor":1,"latest_seq":1,"has_more":false,"events":[{"room_id":"room","seq":1,"event_id":"event-1","authority_epoch":1,"created_at":2.5,"idempotent":false,"actor":{"id":"member","kind":"member"},"kind":"message.member","payload":{"thread_id":"thread","discussion_event_id":"discussion","turn_id":"turn","member_id":"member","task_id":"task","member_index":0,"round_index":0,"text":"hello"}}]}"""), "room", 0)
        assertEquals("event-1", log.events.single().eventId)
        assertEquals(2500L, log.events.single().atMillis)
    }
}
