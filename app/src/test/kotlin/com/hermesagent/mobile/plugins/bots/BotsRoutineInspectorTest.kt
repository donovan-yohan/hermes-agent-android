package com.hermesagent.mobile.plugins.bots

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BotsRoutineInspectorTest {
    @Test fun secretPrefixedPrivateKeysAreRemovedOnParseAndInspectorDisplay() {
        val begin = "-----BEGIN " + "OPENSSH PRIVATE KEY-----"
        val end = "-----END " + "OPENSSH PRIVATE KEY-----"
        for (ending in listOf("\n$end\nUseful diagnostic", "")) {
            val raw = "secret=$begin\nSYNTHETIC_PRIVATE_MATERIAL$ending"
            val json = kotlinx.serialization.json.buildJsonObject {
                put("jobs", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.buildJsonObject {
                    put("job_id", kotlinx.serialization.json.JsonPrimitive("one"))
                    for (key in listOf("name", "schedule", "repeat", "last_status", "deliver", "model", "workdir", "prompt_preview", "last_fire_error")) {
                        put(key, kotlinx.serialization.json.JsonPrimitive(raw))
                    }
                })))
            }
            val job = (parseRoutineJobs(json) as RoutineJobsParse.Answered).jobs.single()
            val outputs = listOf(job.toString(), routineDisplay(raw), routineDisplay(raw, 1024),
                routineDisplay(job.instructionPreview, 1024)) + routineDetailRows(job, 0).map { it.value }
            for (safe in outputs) {
                assertFalse(safe, safe.contains("SYNTHETIC_PRIVATE_MATERIAL"))
                assertFalse(safe, safe.contains(begin))
                assertFalse(safe, safe.contains(end))
            }
            if (ending.isNotEmpty()) assertTrue(job.instructionPreview!!.contains("Useful diagnostic"))
        }
    }

    @Test fun upstreamTruncatedPrivateKeyPreviewIsRedactedBeforeInspector() {
        val begin = "-----BEGIN " + "OPENSSH PRIVATE KEY-----"
        val prompt = begin + "\n" + "synthetic-private-material".repeat(20) + "\n-----END OPENSSH PRIVATE KEY-----"
        val preview = prompt.take(100)
        val json = kotlinx.serialization.json.buildJsonObject {
            put("jobs", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.buildJsonObject {
                put("job_id", kotlinx.serialization.json.JsonPrimitive("one"))
                put("prompt_preview", kotlinx.serialization.json.JsonPrimitive(preview))
                put("prompt", kotlinx.serialization.json.JsonPrimitive(prompt))
            })))
        }
        val job = (parseRoutineJobs(json) as RoutineJobsParse.Answered).jobs.single()
        assertEquals("-----BEGIN PRIVATE KEY----- <redacted> -----END PRIVATE KEY-----", job.instructionPreview)
        assertEquals("-----BEGIN PRIVATE KEY----- <redacted> -----END PRIVATE KEY-----", routineDisplay(job.instructionPreview, 1024))
        assertFalse(job.toString().contains("synthetic-private-material"))
    }

    private val oldLocale = java.util.Locale.getDefault()
    private val oldZone = java.util.TimeZone.getDefault()
    @org.junit.Before fun fixedFormatting() {
        java.util.Locale.setDefault(java.util.Locale.US)
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
    }
    @org.junit.After fun restoreFormatting() {
        java.util.Locale.setDefault(oldLocale)
        java.util.TimeZone.setDefault(oldZone)
    }

    @Test fun everyBackendDisplayFieldIsSanitizedOnParseAndDetailsAreOptional() {
        val secret = "password=synthetic-secret " + "x".repeat(5000)
        val json = kotlinx.serialization.json.buildJsonObject {
            put("jobs", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.buildJsonObject {
                put("job_id", kotlinx.serialization.json.JsonPrimitive("one"))
                for (key in listOf("name", "schedule", "repeat", "last_status", "deliver", "model", "workdir", "prompt_preview", "last_fire_error")) {
                    put(key, kotlinx.serialization.json.JsonPrimitive(secret))
                }
            })))
        }
        val job = (parseRoutineJobs(json) as RoutineJobsParse.Answered).jobs.single()
        for (field in listOf(job.title, job.scheduleLabel, job.rawSchedule, job.repeat, job.lastResult, job.delivery, job.model, job.workdir)) {
            assertNotNull(field)
            assertTrue(field!!.length <= 512)
            assertFalse(field.contains("synthetic-secret"))
        }
        assertTrue(job.issue!!.length <= 1024)
        assertTrue(job.instructionPreview!!.length <= 1024)
        assertFalse(job.toString().contains("synthetic-secret"))
        val malformed = row(""", "last_run_at":"invalid", "last_status":3, "model":{}, "deliver":false, "workdir":null, "prompt_preview":[]""")
        assertNull(malformed.lastRunMillis)
        assertNull(malformed.lastResult)
        assertNull(malformed.model)
        assertNull(malformed.delivery)
        assertNull(malformed.workdir)
        assertNull(malformed.instructionPreview)
        val passthrough = row().copy(scheduleLabel = "0 9 * * 1-5", rawSchedule = "0 9 * * 1-5")
        assertFalse(routineDetailRows(passthrough, 0).any { it.label == "Schedule (raw)" })
    }

    private fun row(fields: String = ""): RoutineRow =
        (parseRoutineJobs(Json.parseToJsonElement("""{"jobs":[{"job_id":"one","name":"Check","schedule":"every 1440m"$fields}]}"""))
            as RoutineJobsParse.Answered).jobs.single()

    @Test fun overdueGraceIsStrictAndOnlyForActiveKnownJobs() {
        val job = row(""", "next_run_at":"2026-01-01T00:00:00Z"""")
        val at = job.nextRunMillis!!
        assertNull(job.overdueMillis(at + 900_000))
        assertEquals(900_001L, job.overdueMillis(at + 900_001))
        assertNull(job.overdueMillis(at - 1))
        for (state in listOf(RoutineRunState.Paused, RoutineRunState.Completed, RoutineRunState.Unknown)) {
            assertNull(job.copy(state = state, active = false).overdueMillis(at + 900_001))
        }
        assertNull(row(""", "next_run_at":"invalid"""").overdueMillis(at))
        assertNull(job.copy(active = false).overdueMillis(at + 900_001))
    }

    @Test fun parserKeepsOnlyPreviewAndInspectorOrdersPresentFields() {
        val job = row(""", "repeat":"forever", "next_run_at":"2026-01-01T00:00:00Z",
            "last_run_at":"2025-12-31T00:00:00Z", "last_status":"delivery_failed",
            "deliver":"local", "model":"sample", "workdir":"tasks",
            "prompt_preview":"Short instruction", "prompt":"FULL PRIVATE INSTRUCTION"""")
        val fields = routineDetailRows(job, job.nextRunMillis!! + 900_001)
        assertEquals(listOf("Status", "Schedule", "Schedule (raw)", "Repeat", "Overdue since", "Last run", "Last result", "Deliver to", "Model", "Working directory"), fields.map { it.label })
        assertEquals("Delivery failed", fields.first { it.label == "Last result" }.value)
        assertEquals("Short instruction", job.instructionPreview)
        assertNull(row(""", "prompt":"FULL PRIVATE INSTRUCTION"""").instructionPreview)
        assertFalse(job.toString().contains("FULL PRIVATE INSTRUCTION"))
        assertEquals(listOf("Status", "Schedule", "Schedule (raw)"), routineDetailRows(row(), 0).map { it.label })
    }

    @Test fun errorsHavePrecedenceAndAllDisplayFieldsAreRedactedAndBounded() {
        val job = row(""", "last_fire_error":"password=fire-secret", "last_delivery_error":"delivery", "paused_reason":"paused"""")
        assertEquals("password=<redacted>", job.issue)
        assertEquals("delivery", row(""", "last_fire_error":" ", "last_delivery_error":"delivery", "paused_reason":"paused"""").issue)
        assertEquals("paused", row(""", "paused_reason":"paused"""").issue)
        assertNull(row().issue)
        assertFalse(routineDisplay("password=secret").contains("secret"))
        assertTrue(routineDisplay("x".repeat(5000)).length <= 512)
        assertTrue(routineDisplay("x".repeat(5000), 1024).length <= 1024)
    }

    @Test fun completedAndUnknownDoNotInventNextRunOrActiveStatus() {
        val job = row(""", "state":"completed", "enabled":false, "next_run_at":"2026-01-01T00:00:00Z"""")
        assertEquals("Completed", routineDetailRows(job, Long.MAX_VALUE).first().value)
        assertFalse(routineDetailRows(job, Long.MAX_VALUE).any { it.label in listOf("Next run", "Overdue since") })
        val unknown = row(""", "state":"new_state"""")
        assertFalse(routineDetailRows(unknown, 0).any { it.label == "Status" })
        assertFalse(unknown.permits(RoutineAction.Resume))
        assertFalse(job.permits(RoutineAction.Resume))
    }
}
