package com.hermesagent.mobile.ui.cron

import org.junit.Assert.*
import org.junit.Test
import com.hermesagent.mobile.data.cron.CronJob
import java.time.Instant

class CronEditorModelTest {
    @Test fun schedulePresetsKeepDesktopOrderAndExpressions() {
        assertEquals(listOf("Daily", "Weekdays", "Weekly", "Monthly", "Hourly", "Every 15 minutes", "Custom"), cronSchedules.map { it.label })
        assertEquals("0 9 * * 1-5", cronSchedules[1].expression)
        assertEquals("Daily", scheduleFor("30 8 * * *").label)
        assertEquals("Custom", scheduleFor("every two weeks").label)
    }
    @Test fun deliveryRetainsUnknownAndNeverRemovesLastTarget() {
        assertEquals(listOf("local", "custom"), deliveryIds("local, custom,local"))
        assertEquals("custom", toggleDelivery("custom", "custom", false))
        assertEquals("custom,email", toggleDelivery("custom", "email", true))
    }

    @Test fun overdueKeepsDesktopFifteenMinuteGrace() {
        val job = CronJob("id", "default", nextRunAt = "2026-10-19T09:00:00Z")
        assertFalse(overdue(job, Instant.parse("2026-10-19T09:15:00Z").toEpochMilli()))
        assertTrue(overdue(job, Instant.parse("2026-10-19T09:15:00.001Z").toEpochMilli()))
        assertFalse(overdue(job.copy(state = "completed"), Instant.parse("2026-10-19T10:00:00Z").toEpochMilli()))
    }

    @Test fun explicitStateWinsOverEnabledAndLastError() {
        val job = CronJob("id", "default", state = "disabled", enabled = false)
        assertEquals("disabled", cronState(job))
        assertEquals("scheduled", cronState(job.copy(state = "scheduled", lastError = "failure")))
        assertEquals("disabled", cronState(job.copy(state = " ")))
    }
}
