package com.hermesagent.mobile.plugins.bots

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class BotsRoutineCreationModelTest {
    private val draft = RoutineCreationDraft(title = " Morning ", instruction = " Do work \n")
    private fun ack(body: String) = classifyRoutineCreationAck(Json.parseToJsonElement(body))
    private fun payload(schedule: RoutineScheduleDraft) = draft.copy(schedule = schedule).payload("Ops-Team", "ops-team")!!

    @Test fun `eight frequencies and initial draft match Desktop`() {
        assertEquals(listOf("once", "hourly", "daily", "weekdays", "weekly", "monthly", "interval", "advanced"), RoutineFrequency.entries.map { it.id })
        val state = RoutineScheduleDraft()
        assertEquals(RoutineFrequency.Daily, state.frequency)
        assertEquals(listOf("9:0", "1", "1", "2", "h", "30", "m", "", ""),
            listOf(state.time, state.weekday, state.monthday, state.intervalN, state.intervalUnit, state.onceN, state.onceUnit, state.repeatN, state.raw))
        assertEquals("", RoutineCreationDraft().title)
        assertEquals("", RoutineCreationDraft().instruction)
        assertFalse(RoutineCreationDraft().continuity)
        assertEquals(RoutineDelivery.History, RoutineCreationDraft().delivery)
    }

    @Test fun `every frequency composes exact wire text`() {
        val expected = listOf("30m", "every 1h", "30 14 * * *", "30 14 * * 1-5", "30 14 * * 0", "30 14 31 * *", "every 2h", "  custom\n ")
        assertEquals(expected, RoutineFrequency.entries.map {
            RoutineScheduleDraft(frequency = it, time = "14:30", weekday = "0", monthday = "31", raw = "  custom\n ").compose()
        })
    }

    @Test fun `weekday order and all half hour ids and labels`() {
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "0"), routineWeekdayIds())
        val times = routineTimeOptions()
        assertEquals(48, times.size)
        assertEquals((0..23).flatMap { listOf("$it:0", "$it:30") }, times.map { it.id })
        assertEquals(RoutineTimeOption("0:0", "12:00 AM"), times.first())
        assertEquals(RoutineTimeOption("9:0", "9:00 AM"), times[18])
        assertEquals(RoutineTimeOption("12:30", "12:30 PM"), times[25])
        assertEquals(RoutineTimeOption("23:30", "11:30 PM"), times.last())
    }

    @Test fun `detail visibility matrix`() {
        val expected = listOf(
            listOf(false, true, false, false, false, false, false),
            listOf(false, false, false, false, false, false, true),
            listOf(true, false, false, false, false, false, true),
            listOf(true, false, false, false, false, false, true),
            listOf(true, false, false, true, false, false, true),
            listOf(true, false, false, false, true, false, true),
            listOf(false, false, true, false, false, false, true),
            listOf(false, false, false, false, false, true, false),
        )
        assertEquals(expected, RoutineFrequency.entries.map {
            listOf(it.showsTime, it.showsOnceAmount, it.showsIntervalAmount, it.showsWeekday, it.showsMonthday, it.showsRaw, it.showsRepeat)
        })
    }

    @Test fun `sanitization strips non ASCII digits before applying caps`() {
        assertEquals("1234", sanitizeRoutineAmount("a1٢2-3.4x567"))
        assertEquals("12", sanitizeRoutineMonthday("x1٢2-345"))
        assertEquals("", sanitizeRoutineAmount("１２٣abc"))
        assertEquals("0000", sanitizeRoutineAmount("00000"))
    }

    @Test fun `unsanitized constructor and copy inputs are explicitly refused`() {
        listOf("12abc", "-2", "+2", "2.5", " 2", "٢", "12345").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { RoutineScheduleDraft(onceN = input) }
            assertThrows(IllegalArgumentException::class.java) { RoutineScheduleDraft().copy(intervalN = input) }
            assertThrows(IllegalArgumentException::class.java) { RoutineScheduleDraft(repeatN = input) }
        }
        assertThrows(IllegalArgumentException::class.java) { RoutineScheduleDraft(monthday = "100") }
        assertThrows(IllegalArgumentException::class.java) { RoutineScheduleDraft(time = "09:00") }
        assertThrows(IllegalArgumentException::class.java) { RoutineScheduleDraft(weekday = "7") }
        assertThrows(IllegalArgumentException::class.java) { RoutineScheduleDraft(onceUnit = "s") }
        assertThrows(IllegalArgumentException::class.java) { RoutineScheduleDraft(intervalUnit = "s") }
    }

    @Test fun `blank zero and leading zero numbers use Desktop fallback`() {
        listOf("", "0", "0000").forEach { input ->
            assertEquals("1h", RoutineScheduleDraft(frequency = RoutineFrequency.Once, onceN = input, onceUnit = "").compose())
            assertEquals("every 1h", RoutineScheduleDraft(frequency = RoutineFrequency.Interval, intervalN = input, intervalUnit = "").compose())
        }
        assertEquals("12d", RoutineScheduleDraft(frequency = RoutineFrequency.Once, onceN = "0012", onceUnit = "d").compose())
        assertEquals("every 9999m", RoutineScheduleDraft(frequency = RoutineFrequency.Interval, intervalN = "9999", intervalUnit = "m").compose())
    }

    @Test fun `monthday is not clamped and empty picker details fall back`() {
        for (day in listOf("0", "00", "32", "99", "01")) {
            assertEquals("0 9 $day * *", RoutineScheduleDraft(frequency = RoutineFrequency.Monthly, monthday = day).compose())
        }
        assertEquals("0 9 1 * *", RoutineScheduleDraft(frequency = RoutineFrequency.Monthly, monthday = "", time = "").compose())
        assertEquals("0 9 * * 1", RoutineScheduleDraft(frequency = RoutineFrequency.Weekly, weekday = "").compose())
    }

    @Test fun `switching retains hidden details without leaking repeat`() {
        val original = RoutineScheduleDraft(repeatN = "7", raw = "every 3d", time = "12:30", onceN = "45")
        for (frequency in listOf(RoutineFrequency.Once, RoutineFrequency.Advanced)) {
            val switched = original.copy(frequency = frequency)
            assertEquals(original, switched.copy(frequency = original.frequency))
            assertEquals("7", switched.repeatN)
            assertFalse(payload(switched).containsKey("repeat"))
        }
        assertEquals(JsonPrimitive(7), payload(original)["repeat"])
    }

    @Test fun `Once default intentionally preserves Gateway recurring interval mismatch`() {
        // d177b119e9c56c9ddc0b7379ffce52341ec06584:
        // apps/desktop/src/plugins/hermes-bots/cron.tsx:667-670,1002-1005;
        // cron/jobs.py:770-834,1794-1802. Bare 30m + omitted repeat is NOT one-shot.
        val params = payload(RoutineScheduleDraft(frequency = RoutineFrequency.Once))
        assertEquals(JsonPrimitive("30m"), params["schedule"])
        assertFalse(params.containsKey("repeat"))
        assertEquals(JsonPrimitive("every 1h"), payload(RoutineScheduleDraft(frequency = RoutineFrequency.Hourly))["schedule"])
    }

    @Test fun `advanced raw survives composition and trims only at payload boundary`() {
        val schedule = RoutineScheduleDraft(frequency = RoutineFrequency.Advanced, raw = " \n every 2d \t", repeatN = "2")
        assertEquals(" \n every 2d \t", schedule.compose())
        assertEquals(JsonPrimitive("every 2d"), payload(schedule)["schedule"])
        assertFalse(payload(schedule).containsKey("repeat"))
    }

    @Test fun `payload has exact default keys trims inputs and preserves raw profile`() {
        val actual = draft.payload(" Ops-Team ", "ops-TEAM")!!
        assertEquals(Json.parseToJsonElement("""{"action":"add","name":"[bot: Ops-Team ] Morning","schedule":"0 9 * * *","prompt":"Do work","profile":" Ops-Team "}"""), actual)
        assertEquals(setOf("action", "name", "schedule", "prompt"), draft.payload("", "")!!.keys)
        assertTrue((draft.payload("", "")!!["prompt"] as JsonPrimitive).content.startsWith("[bot-mode:routine:v2]"))
    }

    @Test fun `empty and NUL inputs refuse payload`() {
        for (bad in listOf("", " \n\t", "x\u0000y")) {
            assertNull(draft.copy(title = bad).payload("owner", "active"))
            assertNull(draft.copy(instruction = bad).payload("owner", "active"))
        }
        assertNull(draft.copy(schedule = RoutineScheduleDraft(frequency = RoutineFrequency.Advanced, raw = " \n")).payload("owner", "active"))
    }

    @Test fun `delegation prompt matches exact shell apostrophe and newline bytes`() {
        val actual = RoutineCreationDraft(title = " O'Brien\nreport ", instruction = " Say 'hello'\nthen wait ")
            .payload(" Ops'Bot ", "other")!!["prompt"]
        val expected = "[bot-mode:routine:v2] You are running the scheduled routine \"O'Brien\nreport\" for agent ' Ops'Bot '. " +
            "Execute it AS that agent so the run lands in its own history: run this in the terminal and relay the output:\n\n" +
            "hermes -p ' Ops'\"'\"'Bot ' chat -c 'Routine: O'\"'\"'Brien\nreport' -q '[Scheduled routine] Say '\"'\"'hello'\"'\"'\nthen wait'\n\n" +
            "If the command fails, report the error instead."
        assertEquals(JsonPrimitive(expected), actual)
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), (actual as JsonPrimitive).content.toByteArray(Charsets.UTF_8))
    }

    @Test fun `repeat continuity and delivery optional keys are exact`() {
        for (blank in listOf("", " \t\n")) assertFalse(payload(RoutineScheduleDraft(repeatN = blank)).containsKey("repeat"))
        assertEquals(JsonPrimitive(1), payload(RoutineScheduleDraft(repeatN = "0"))["repeat"])
        val actual = draft.copy(schedule = RoutineScheduleDraft(repeatN = "0012"), continuity = true, delivery = RoutineDelivery.BotChat)
            .payload("Ops-Team", "ops-team")!!
        assertEquals(Json.parseToJsonElement("""{"action":"add","name":"[bot:Ops-Team] Morning","schedule":"0 9 * * *","prompt":"Do work","profile":"Ops-Team","repeat":12,"continuity":true,"deliver":"bot-chat"}"""), actual)
        val reset = RoutineCreationDraft(title = "Morning", instruction = "Do work").payload("Ops-Team", "ops-team")!!
        assertFalse(reset.containsKey("continuity"))
        assertFalse(reset.containsKey("deliver"))
        assertFalse(reset.containsKey("repeat"))
    }

    @Test fun `only literal success with coherent identity creates`() {
        assertEquals(RoutineCreationAck.Created("id-1"), ack("""{"success":true,"job_id":"id-1","message":"private","guidance":"private"}"""))
        assertEquals(RoutineCreationAck.Created("id-1"), ack("""{"success":true,"job_id":"id-1","job_saved":true,"scheduler_registered":true,"retry_create":false}"""))
        assertEquals(RoutineCreationAck.Rejected, ack("""{"success":false,"error":"private"}"""))
        // A successful RPC envelope cannot stand in for the inner tool result.
        val envelope = Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"result":{"success":false,"error":"private"}}""") as JsonObject
        assertEquals(RoutineCreationAck.Rejected, classifyRoutineCreationAck(envelope["result"]))
        assertTrue(classifyRoutineCreationAck(envelope) is RoutineCreationAck.Unconfirmed)
    }

    @Test fun `saved registration failure retains identity and never permits add retry`() {
        val result = ack("""{"success":false,"job_id":"saved-id","job_saved":true,"scheduler_registered":false,"retry_create":false,"error":"private","guidance":"private"}""")
        assertEquals(RoutineCreationAck.SavedRegistrationFailed("saved-id"), result)
        assertFalse(result.automaticRecreationAllowed)
        assertFalse(result.toString().contains("private"))
    }

    @Test fun `missing or malformed success and identity are unconfirmed`() {
        for (body in listOf("null", "[]", "true", "{}", """{"job_id":"x"}""", """{"success":"true","job_id":"x"}""",
            """{"success":"false"}""", """{"success":1,"job_id":"x"}""", """{"success":true}""")) {
            assertTrue(body, ack(body) is RoutineCreationAck.Unconfirmed)
        }
        for (id in listOf("null", "false", "4", "[]", "{}", "\"\"", "\" \"", "\" x \"", "\"x\\u0000y\"", "\"x\\ny\"")) {
            assertTrue(id, ack("""{"success":true,"job_id":$id}""") is RoutineCreationAck.Unconfirmed)
        }
        assertEquals(RoutineCreationAck.Unconfirmed(), classifyRoutineCreationAck(null))
    }

    @Test fun `contradictory success and partial evidence never create`() {
        for (extra in listOf("\"job_saved\":false", "\"scheduler_registered\":false", "\"retry_create\":true", "\"error\":\"private\"")) {
            assertEquals(extra, RoutineCreationAck.Unconfirmed("x"), ack("""{"success":true,"job_id":"x",$extra}"""))
        }
        for (key in listOf("job_saved", "scheduler_registered", "retry_create")) {
            for (value in listOf("\"true\"", "\"false\"", "1", "null", "[]", "{}")) {
                for (success in listOf(true, false)) {
                    val result = ack("""{"success":$success,"job_id":"x","$key":$value}""")
                    assertEquals("$key=$value", RoutineCreationAck.Unconfirmed("x"), result)
                    assertFalse(result.automaticRecreationAllowed)
                }
            }
        }
    }

    @Test fun `damaged partial save tuple is unconfirmed not rejected or recreated`() {
        val full = Json.parseToJsonElement("""{"success":false,"job_id":"x","job_saved":true,"scheduler_registered":false,"retry_create":false}""") as JsonObject
        for (key in full.keys) {
            val result = classifyRoutineCreationAck(JsonObject(full - key))
            assertTrue("missing $key", result is RoutineCreationAck.Unconfirmed)
            assertFalse(result.automaticRecreationAllowed)
        }
        for ((key, value) in mapOf("success" to true, "job_saved" to false, "scheduler_registered" to true, "retry_create" to true)) {
            val result = classifyRoutineCreationAck(JsonObject(full + (key to JsonPrimitive(value))))
            assertEquals(key, RoutineCreationAck.Unconfirmed("x"), result)
        }
        for (evidence in listOf("\"job_saved\":true", "\"retry_create\":false", "\"job_id\":\"x\"")) {
            val result = ack("""{"success":false,$evidence}""")
            assertTrue(result is RoutineCreationAck.Unconfirmed)
            assertFalse(result.automaticRecreationAllowed)
        }
        assertFalse(RoutineCreationAck.Rejected.automaticRecreationAllowed)
        assertFalse(RoutineCreationAck.Created("x").automaticRecreationAllowed)
    }
}
