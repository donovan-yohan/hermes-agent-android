package com.hermesagent.mobile.data.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class CronSessionAdmissionTest {
    private fun row(fields: String): GatewaySessionDetail = GatewaySessionDetail.parse(
        Json.parseToJsonElement("""{"id":"cron_job_20261002_120000","profile":"work",$fields}""").jsonObject,
        "cron_job_20261002_120000", "work",
    )!!

    @Test fun `closed ownership and strict fallback follow Desktop precedence with fixed clock`() {
        val now = 1_000_000L
        fun allowed(fields: String) = row(fields).isResumableCronRun(now)
        assertTrue(allowed("\"ended_at\":0,\"scheduler_owned\":false"))
        assertTrue(allowed("\"scheduler_owned\":true,\"is_active\":false"))
        assertFalse(allowed("\"scheduler_owned\":false,\"is_active\":true"))
        assertTrue(allowed("\"scheduler_owned\":\"false\",\"is_active\":true"))
        assertTrue(allowed("\"is_active\":\"false\",\"last_active\":701"))
        assertFalse(allowed("\"last_active\":700"))
        assertTrue(allowed("\"last_active\":700.001"))
        assertFalse(allowed("\"last_active\":\"999\""))
        assertFalse(allowed("\"last_active\":1e999"))
        assertFalse(allowed("\"started_at\":999"))
        assertTrue(allowed("\"last_active\":1001"))
        assertTrue(isCronExecutionSessionId(" cron_job_20261002_120000 "))
        assertFalse(isCronExecutionSessionId("cron_job"))
        assertFalse(isCronExecutionSessionId("cron__20261002_120000"))
    }

    @Test fun `lookup failures keep known verdicts but unknown remains distinct and scoped`() {
        val gate = CronSessionAdmission()
        val key = CronSessionOwner(1, "work", "cron_job_20261002_120000")
        assertEquals(CronSessionVerdict.LookupUnavailable, gate.record(key, null, 1_000_000))
        assertEquals(CronSessionVerdict.Writable, gate.record(key, row("\"scheduler_owned\":true"), 1_000_000))
        assertEquals(CronSessionVerdict.Writable, gate.record(key, null, 1_000_000))
        assertEquals(CronSessionVerdict.ViewOnly, gate.record(key, row("\"scheduler_owned\":false"), 1_000_000))
        assertEquals(CronSessionVerdict.ViewOnly, gate.record(key, null, 1_000_000))
        assertEquals(CronSessionVerdict.LookupUnavailable, gate.record(key.copy(endpointGeneration = 2), null, 1_000_000))
        assertEquals(CronSessionVerdict.LookupUnavailable, gate.record(key.copy(profile = "other"), null, 1_000_000))
        assertEquals(CronSessionVerdict.Writable, gate.record(key, row("\"source\":\"cli\",\"scheduler_owned\":false"), 1_000_000))
        assertEquals(CronSessionVerdict.ViewOnly, gate.record(key, row("\"source\":\"\",\"scheduler_owned\":false"), 1_000_000))
    }
}
