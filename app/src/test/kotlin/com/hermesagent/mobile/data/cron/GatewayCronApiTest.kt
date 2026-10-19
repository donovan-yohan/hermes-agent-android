package com.hermesagent.mobile.data.cron

import com.hermesagent.mobile.data.gateway.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GatewayCronApiTest {
    @Test fun `scoped inventory decodes full raw editable records and wipes response bytes`() = runTest {
        val wire = CronWire { _, _ -> CronWire.ok("""[{"id":" a/% ","profile":"writer","name":"  raw  ","prompt":"  exact  ","schedule":{"type":"cron","expr":"0 9 * * *","future":true},"schedule_display":"Daily","deliver":"local,custom","script":"daily.sh","no_agent":true}]""") }
        val scope = api(wire).openScope(0, "writer")
        val job = success(scope.jobs()).single()
        assertEquals(" a/% ", job.id)
        assertEquals("  exact  ", job.prompt)
        assertEquals("0 9 * * *", job.schedule)
        assertEquals("Daily", job.scheduleDisplay)
        assertTrue(job.scriptOnly)
        assertEquals(mapOf("profile" to "writer"), wire.requests.single().query)
        assertTrue(wire.buffers.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test fun `detail prevalidates exact id in owner inventory and encodes whole segment`() = runTest {
        val row = """{"id":" a/% ","profile":"writer","prompt":"raw"}"""
        val wire = CronWire { _, count -> CronWire.ok(if (count == 1) "[$row]" else row) }
        assertEquals(" a/% ", success(api(wire).openScope(0, "writer").detail(" a/% ")).id)
        assertEquals(listOf("/api/cron/jobs", "/api/cron/jobs/%20a%2F%25%20"), wire.requests.map { it.path })
        assertTrue(wire.requests.all { it.query == mapOf("profile" to "writer") })
    }

    @Test fun `absent owner never invokes fallback item and foreign response never publishes`() = runTest {
        val gone = CronWire { _, _ -> CronWire.ok("[]") }
        assertEquals(CronResult.Failure(CronFailure.InvalidScope), api(gone).openScope(0, "writer").detail("same"))
        assertEquals(1, gone.requests.size)
        val foreign = CronWire { _, count -> CronWire.ok(if (count == 1) """[{"id":"same","profile":"writer"}]""" else """{"id":"same","profile":"other"}""") }
        assertEquals(CronResult.Failure(CronFailure.InvalidResponse), api(foreign).openScope(0, "writer").detail("same"))
        assertTrue(foreign.buffers.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test fun `runs keep unified and legacy sessions distinct from inert output documents`() = runTest {
        val wire = CronWire { _, count -> CronWire.ok(if (count == 1) """[{"id":"job","profile":"writer"}]""" else """{"runs":[{"id":"local-1","profile":"writer","source":"cron","started_at":12.5,"last_active":15,"ended_at":null,"scheduler_owned":true,"is_active":true},{"id":"output:1","source":"cron_output","title":"Saved output","preview":"raw"}]}""") }
        val rows = success(api(wire).openScope(0, "writer").runs("job", 7))
        assertTrue(rows[0].isConversation)
        assertEquals(12.5, rows[0].startedAt!!, 0.0)
        assertEquals(true, rows[0].schedulerOwned)
        assertFalse(rows[1].isConversation)
        assertEquals("writer", rows[1].profile)
        assertEquals(mapOf("profile" to "writer", "limit" to "7"), wire.requests.last().query)
    }

    @Test fun `runs missing source stay unknown and inert even for a legacy execution id`() = unknownRunSource("")

    @Test fun `runs null source stay unknown and inert even for a legacy execution id`() = unknownRunSource("null")

    @Test fun `runs noncron source never inherits navigation from a legacy execution id`() = unknownRunSource("\"legacy\"")

    private fun unknownRunSource(source: String) = runTest {
        val field = if (source.isEmpty()) "" else "\"source\":$source,"
        val wire = CronWire { _, count -> CronWire.ok(if (count == 1) """[{"id":"job","profile":"writer"}]""" else """{"runs":[{${field}"id":"cron_job_20261019_090000","profile":"writer","title":"Saved run","preview":"raw","started_at":12.5,"last_active":15,"ended_at":null}]}""") }
        val row = success(api(wire).openScope(0, "writer").runs("job")).single()
        assertEquals(if (source == "\"legacy\"") "legacy" else "", row.source)
        assertFalse(row.isConversation)
    }

    @Test fun `runs wrong typed source is rejected rather than becoming navigable`() = runTest {
        for (source in listOf("17", "true", "{}", "[]")) {
            val wire = CronWire { _, count -> CronWire.ok(if (count == 1) """[{"id":"job","profile":"writer"}]""" else """{"runs":[{"id":"cron_job_20261019_090000","profile":"writer","source":$source}]}""") }
            assertEquals(CronResult.Failure(CronFailure.InvalidResponse), api(wire).openScope(0, "writer").runs("job"))
        }
    }

    @Test fun `delivery and blueprint schema use real field vocabulary without dropping unknown types`() = runTest {
        val wire = CronWire { request, _ -> CronWire.ok(if (request.path.endsWith("delivery-targets")) """{"targets":[{"id":"local","name":"Local (save only)"},{"id":"chat","name":"Chat","home_target_set":false}]}""" else """{"blueprints":[{"key":"plugin:morning","title":"Morning","plugin":"plugin","fields":[{"name":"deliver","type":"enum","label":"Where?","default":"local","options":["local","custom"],"optional":true,"strict":false,"help":"Pick"},{"name":"future","type":"future-widget","label":"Future"}]}]}""") }
        val scope = api(wire).openScope(0, "writer")
        assertFalse(success(scope.deliveryTargets())[1].homeTargetSet)
        val blueprint = success(scope.blueprints()).single()
        assertEquals("plugin:morning", blueprint.key)
        assertEquals("local", blueprint.fields[0].defaultValue)
        assertFalse(blueprint.fields[0].strict)
        assertEquals("future-widget", blueprint.fields[1].type)
        assertTrue(wire.requests.all { it.query["profile"] == "writer" })
    }

    @Test fun `all is inventory only and malformed foreign reads never become empty success`() = runTest {
        val wire = CronWire { _, _ -> CronWire.ok("""{"runs":[]}""") }
        val scope = api(wire).openScope(0, "all")
        assertEquals(CronResult.Failure(CronFailure.InvalidScope), scope.runs("job"))
        assertEquals(CronResult.Failure(CronFailure.InvalidScope), scope.blueprints())
        assertEquals(CronResult.Failure(CronFailure.InvalidScope), scope.deliveryTargets())
        assertEquals(0, wire.requests.size)
        assertEquals(CronResult.Failure(CronFailure.InvalidResponse), scope.jobs())
    }

    @Test fun `foreign run owner is refused even with same id and blank output preview is accepted`() = runTest {
        for (foreign in listOf(false, true)) {
            val wire = CronWire { _, count -> CronWire.ok(if (count == 1) """[{"id":"job","profile":"writer"}]""" else if (foreign) """{"runs":[{"id":"local-1","profile":"other","source":"cron"}]}""" else """{"runs":[{"id":"cron_output:job:latest","source":"cron_output","preview":null}]}""") }
            val result = api(wire).openScope(0, "writer").runs("job")
            if (foreign) assertEquals(CronResult.Failure(CronFailure.InvalidResponse), result)
            else assertEquals("", success(result).single().preview)
        }
    }

    @Test fun `same endpoint replacement transport invalidates old scope before admission`() = runTest {
        val original = CronWire { _, _ -> error("old wire") }
        var live: GatewayHttp? = original
        val scope = GatewayCronApi({ live }, MutableStateFlow(0L), EndpointDispatchFence()).openScope(0, "writer")
        live = CronWire { _, _ -> error("replacement wire") }
        assertEquals(CronResult.Failure(CronFailure.StaleScope), scope.jobs())
    }

    @Test fun `malformed records never authorize items`() = runTest {
        for (json in listOf("""[{"id":"job","profile":"other"}]""", """[{"id":"job","profile":"writer","enabled":"true"}]""", """[{"id":"job","profile":"writer"},{"id":"job","profile":"writer"}]""")) {
            val wire = CronWire { _, _ -> CronWire.ok(json) }
            assertEquals(CronResult.Failure(CronFailure.InvalidResponse), api(wire).openScope(0, "writer").detail("job"))
            assertEquals(1, wire.requests.size)
        }
    }

    private fun api(wire: GatewayHttp) = GatewayCronApi({ wire }, MutableStateFlow(0L), EndpointDispatchFence())
    private fun <T> success(result: CronResult<T>): T = (result as CronResult.Success).value
}

internal class CronWire(
    private val reply: suspend (GatewayHttpRequest, Int) -> GatewayHttpResult,
) : EndpointDispatchingGatewayHttp {
    val requests = mutableListOf<GatewayHttpRequest>()
    val buffers = mutableListOf<ByteArray>()
    var beforeDispatch: suspend (GatewayHttpRequest) -> Unit = {}
    override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult = error("unguarded REST")
    override suspend fun executeAtDispatch(request: GatewayHttpRequest, dispatch: (() -> Boolean) -> Boolean): GatewayHttpResult {
        beforeDispatch(request)
        if (!dispatch { requests += request; true }) return GatewayHttpResult.Rejected(0, "not admitted")
        return reply(request, requests.size).also {
            when (it) {
                is GatewayHttpResult.Success -> buffers += it.bodyBytes
                is GatewayHttpResult.Rejected -> buffers += it.envelopeBytes
            }
        }
    }
    companion object {
        fun ok(body: String) = GatewayHttpResult.Success(200, body.toByteArray())
        fun reject(code: Int, body: String = "") = GatewayHttpResult.Rejected(code, "synthetic refusal", body.toByteArray())
    }
}
