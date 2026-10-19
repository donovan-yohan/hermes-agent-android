package com.hermesagent.mobile.data.cron

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CronModelsTest {
    @Test fun `job and draft preserve editable values and script-only identity`() {
        val job = CronJob(" job/% ", "writer", prompt = "  keep exactly  ", script = "daily.sh", noAgent = true)
        assertEquals("keep exactly", job.title)
        assertTrue(job.scriptOnly)
        assertEquals("  keep exactly  ", CronDraft.from(job).prompt)
        assertEquals("daily.sh", job.copy(prompt = "").title)
        assertEquals(" job/% ", job.copy(prompt = "", script = null).title)
    }

    @Test fun `title uses trimmed name prompt script then id then generic fallback`() {
        val job = CronJob("id", "writer", name = "  Name  ", prompt = "  Prompt  ", script = "  script.sh  ")
        assertEquals("Name", job.title)
        assertEquals("Prompt", job.copy(name = " \t ").title)
        assertEquals("script.sh", job.copy(name = "", prompt = " \n ").title)
        assertEquals("id", job.copy(name = "", prompt = "", script = " ").title)
        assertEquals("Cron job", CronJob("", "writer").title)
        assertEquals("n".repeat(61), job.copy(name = "n".repeat(61)).title)
    }

    @Test fun `prompt and script titles truncate at sixty intact graphemes with ellipsis`() {
        for (grapheme in listOf("🇨🇳", "👩🏽‍💻", "e\u0301")) {
            val prefix = "a".repeat(59) + grapheme
            val job = CronJob("id", "writer", prompt = "  ${prefix}tail  ")
            assertEquals(prefix + "…", job.title)
            assertEquals(prefix + "…", job.copy(prompt = "", script = "  ${prefix}tail  ").title)
            assertEquals(prefix, job.copy(prompt = "  $prefix  ").title)
        }
    }

    @Test fun `run navigation requires explicit cron source regardless of legacy shaped id`() {
        for (id in listOf("local-123", "cron_job_20261019_090000")) {
            assertFalse(CronRun(id, "writer").isConversation)
            assertTrue(CronRun(id, "writer", source = "cron").isConversation)
            for (source in listOf("", "legacy", "other", "cron_output", "CRON", " cron ")) {
                assertFalse("id=$id source=$source", CronRun(id, "writer", source = source).isConversation)
            }
        }
    }
}
