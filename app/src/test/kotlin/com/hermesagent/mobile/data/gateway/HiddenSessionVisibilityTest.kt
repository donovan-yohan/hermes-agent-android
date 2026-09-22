package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.SessionBucketLabel
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.buildSessionRows
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class HiddenSessionVisibilityTest {
    @Test
    fun `compression rehome keeps hidden visibility when continuation omits it`() {
        val cache = SessionCache()
        val hidden = parseSession(Json.parseToJsonElement(
            """{"id":"bot-root","title":"Fixture chat","hidden":true}""",
        ).jsonObject, 1000L)
        cache.upsertSession(hidden)
        cache.rehomeSession("bot-root", hidden.copy(id = "bot-tip", hidden = null), emptyList())
        assertEquals(true, cache.session("bot-tip")?.hidden)
        assertEquals("bot-tip", cache.state.value.rehomes["bot-root"])
    }

    @Test
    fun `resolved hidden chat stays cached but never becomes a sidebar row`() {
        val cache = SessionCache()
        val hidden = parseSession(Json.parseToJsonElement(
            """{"id":"bot-chat","title":"Fixture chat","hidden":true}""",
        ).jsonObject, 1000L)
        cache.upsertSession(hidden)
        // A partial update must not erase authoritative visibility metadata.
        cache.upsertSession(parseSession(Json.parseToJsonElement(
            """{"id":"bot-chat","title":"Fixture updated"}""",
        ).jsonObject, 1000L))
        cache.upsertSessions(emptyList())
        assertNotNull(cache.session("bot-chat"))
        for (query in listOf("", "Fixture")) {
            val rows = buildSessionRows(
                sessions = cache.state.value.sessions.values,
                nowMillis = 1000L,
                query = query,
                serverMatches = listOf(hidden.copy(hidden = null)),
                timeZone = TimeZone.getTimeZone("UTC"),
                locale = Locale.ROOT,
                bucketLabel = SessionBucketLabel { "Fixture bucket" },
            )
            assertEquals(emptyList<SessionListRow.Row>(), rows.filterIsInstance<SessionListRow.Row>())
        }
        // Explicit unhide is authoritative; an older backend's silence is not.
        cache.upsertSession(hidden.copy(hidden = false))
        assertEquals(false, cache.session("bot-chat")?.hidden)
        val visible = buildSessionRows(
            sessions = cache.state.value.sessions.values,
            nowMillis = 1000L,
            timeZone = TimeZone.getTimeZone("UTC"),
            locale = Locale.ROOT,
            bucketLabel = SessionBucketLabel { "Fixture bucket" },
        )
        assertEquals(listOf("bot-chat"), visible.filterIsInstance<SessionListRow.Row>().map { it.session.id })
    }
}
