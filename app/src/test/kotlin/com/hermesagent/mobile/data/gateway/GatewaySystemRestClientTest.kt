package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The System panel's six routes, against the shapes the pinned Gateway actually
 * produces: hermes-agent @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`, with the
 * `path:line` that builds each fixture named where it is used.
 *
 * Kept beside `GatewayRestClientTest` rather than inside it because these are a
 * different contract with a different failure mode — a refusal that arrives as
 * a 200 — and one 900-line file would bury it.
 */
class GatewaySystemRestClientTest {

    // -----------------------------------------------------------------------
    // Request shaping.
    // -----------------------------------------------------------------------

    @Test
    fun `reads the four status fields the panel renders and asks for nothing else`() = runTest {
        // `web_server.py:4011-4031` — the real payload is thirty fields wide.
        val http = RecordingGatewayHttp(
            success(
                """{"version":"0.5.1","release_date":"2026-08-30","config_version":7,""" +
                    """"can_update_hermes":true,"gateway_running":true,"gateway_state":"running",""" +
                    """"active_sessions":3,"auth_required":false,"install_id":"local"}""",
            ),
        )

        val status = GatewayRestClient { http }.status().valueOrFail()

        val request = http.requests.single()
        assertEquals("api/status", request.path)
        assertEquals("GET", request.method)
        assertEquals(emptyMap<String, String>(), request.query)
        assertNull(request.body)
        assertEquals("0.5.1", status.version)
        assertEquals(3L, status.activeSessions)
        assertTrue(status.gatewayRunning)
        assertEquals(true, status.canUpdateHermes)
    }

    @Test
    fun `a Gateway that predates can_update_hermes leaves it unknown rather than false`() = runTest {
        val http = RecordingGatewayHttp(
            success("""{"version":"0.4.0","gateway_running":false,"active_sessions":0}"""),
        )

        val status = GatewayRestClient { http }.status().valueOrFail()

        // Absent is a capability to remember. Reading it as `false` would tell
        // someone their host cannot update when nobody asked it.
        assertNull(status.canUpdateHermes)
        assertFalse(status.gatewayRunning)
    }

    @Test
    fun `forces the update check past the host's six-hour cache`() = runTest {
        // `web_server.py:5279-5283`; Desktop always forces (`updates.ts:374`).
        val http = RecordingGatewayHttp(
            success(
                """{"install_method":"git","current_version":"0.5.1","behind":3,""" +
                    """"update_available":true,"can_apply":true,"update_command":"git pull",""" +
                    """"message":null,"commits":[{"sha":"abc1234","summary":"feat: a thing",""" +
                    """"author":"someone","at":1730000000}]}""",
            ),
        )

        val check = GatewayRestClient { http }.checkHermesUpdate(force = true).valueOrFail()

        val request = http.requests.single()
        assertEquals("api/hermes/update/check", request.path)
        assertEquals("GET", request.method)
        assertEquals(mapOf("force" to "true"), request.query)
        assertEquals("git", check.installMethod)
        assertEquals(3L, check.behind)
        assertTrue(check.canApply)
        assertEquals(listOf(GatewayUpdateCommit("abc1234", "feat: a thing")), check.commits)
    }

    @Test
    fun `a check with no commit list is an empty changelog, not an unusable body`() = runTest {
        // The route omits `commits` for a non-git install and for a host that is
        // up to date (`web_server.py:5300-5301`).
        val absent = GatewayRestClient {
            RecordingGatewayHttp(
                success(
                    """{"install_method":"apt","current_version":"0.5.1","behind":null,""" +
                        """"update_available":false,"can_apply":false,""" +
                        """"update_command":"pkg upgrade hermes-agent","message":"managed"}""",
                ),
            )
        }.checkHermesUpdate(force = false).valueOrFail()

        assertEquals(emptyList<GatewayUpdateCommit>(), absent.commits)
        // `behind: null` is "the check failed", which is not the same fact as 0.
        assertNull(absent.behind)
        assertFalse(absent.canApply)

        val explicitNull = GatewayRestClient {
            RecordingGatewayHttp(
                success("""{"update_available":false,"can_apply":true,"commits":null}"""),
            )
        }.checkHermesUpdate(force = false).valueOrFail()
        assertEquals(emptyList<GatewayUpdateCommit>(), explicitNull.commits)
    }

    @Test
    fun `starts an update with an empty body on the route that takes none`() = runTest {
        // `web_server.py:5078-5079` declares no body; `:5149-5154` is the answer.
        val http = RecordingGatewayHttp(
            success("""{"ok":true,"pid":4242,"name":"hermes-update","action_id":"deadbeef"}"""),
        )

        val started = GatewayRestClient { http }.startHermesUpdate().valueOrFail()

        val request = http.requests.single()
        assertEquals("api/hermes/update", request.path)
        assertEquals("POST", request.method)
        assertEquals(emptyMap<String, String>(), request.query)
        assertEquals("", http.bodies.single())
        assertEquals(GatewayUpdateStart.Started("hermes-update", "deadbeef", alreadyRunning = false), started)
    }

    @Test
    fun `an update the host is already running is adopted rather than started twice`() = runTest {
        // `web_server.py:5126-5137`.
        val started = GatewayRestClient {
            RecordingGatewayHttp(
                success("""{"ok":true,"pid":7,"name":"hermes-update","already_running":true,"action_id":"a1"}"""),
            )
        }.startHermesUpdate().valueOrFail()

        assertEquals(GatewayUpdateStart.Started("hermes-update", "a1", alreadyRunning = true), started)
    }

    @Test
    fun `a refusal arrives as a 200 and is read from the envelope, not the status code`() = runTest {
        // Every refusal is HTTP 200 with `ok:false` (`web_server.py:5088-5095,
        // 5117-5124`), so a client that classified by status code would report
        // a started update that never started.
        val http = RecordingGatewayHttp(
            success(
                """{"ok":false,"pid":null,"name":"hermes-update","error":"apt_update_required",""" +
                    """"message":"Hermes is managed by Termux APT.",""" +
                    """"update_command":"pkg upgrade hermes-agent"}""",
            ),
        )

        val refused = GatewayRestClient { http }.startHermesUpdate().valueOrFail()

        assertEquals(
            GatewayUpdateStart.Refused(
                error = "apt_update_required",
                message = "Hermes is managed by Termux APT.",
                updateCommand = "pkg upgrade hermes-agent",
            ),
            refused,
        )
    }

    @Test
    fun `tails only the two actions this app starts, on the route's own window`() = runTest {
        // `web_server.py:5814-5822`; unknown names 404 at `:5817-5819`, which is
        // why the name is an enum and not a string.
        val http = RecordingGatewayHttp(
            success(
                """{"name":"gateway-restart","running":false,"exit_code":0,"pid":9,""" +
                    """"lines":["restarting","done"]}""",
            ),
        )

        val status = GatewayRestClient { http }
            .actionStatus(GatewayAction.GatewayRestart, lines = 180)
            .valueOrFail()

        val request = http.requests.single()
        assertEquals("api/actions/gateway-restart/status", request.path)
        assertEquals("GET", request.method)
        assertEquals(mapOf("lines" to "180"), request.query)
        // A dead host must not stall a 1500 ms poll cadence for fifteen seconds.
        assertTrue(request.timeoutMillis <= 5_000L)
        assertEquals(listOf("restarting", "done"), status.lines)
        assertEquals(0L, status.exitCode)
        assertFalse(status.running)
        assertNull(status.receipt)
    }

    @Test
    fun `refuses a line count outside the route's window instead of letting it clamp`() = runTest {
        val http = RecordingGatewayHttp()

        GatewayRestClient { http }.actionStatus(GatewayAction.HermesUpdate, lines = 0).assertMalformed()
        GatewayRestClient { http }.actionStatus(GatewayAction.HermesUpdate, lines = 2_001).assertMalformed()

        assertEquals(0, http.requests.size)
    }

    @Test
    fun `carries the durable action id and the receipt summary an update poll attaches`() = runTest {
        // `web_server.py:5876-5886`; the summary's shape is `:5908-5918`.
        val status = GatewayRestClient {
            RecordingGatewayHttp(
                success(
                    """{"name":"hermes-update","running":false,"exit_code":null,"pid":null,""" +
                        """"lines":["=== hermes-update completed feed ==="],"action_id":"feed",""" +
                        """"receipt":{"outcome":"success","started_at":"2026-09-01T10:00:00+00:00",""" +
                        """"finished_at":"2026-09-01T10:04:00+00:00","pre_sha":"aaa","post_sha":"bbb",""" +
                        """"post_version":"0.5.2","fleet_states":["ok"]}}""",
                ),
            )
        }.actionStatus(GatewayAction.HermesUpdate).valueOrFail()

        assertEquals("feed", status.actionId)
        assertNull(status.exitCode)
        assertEquals("success", status.receipt?.outcome)
        assertEquals("0.5.2", status.receipt?.postVersion)
    }

    @Test
    fun `reads the receipt fields that describe serve's own recovery`() = runTest {
        // New at this pin (`update_receipt.py:135-155`), and projected by the
        // receipt endpoint only — the action-status summary drops them
        // (`web_server.py:5908-5918`).
        val http = RecordingGatewayHttp(
            success(
                """{"receipt":{"schema":1,"outcome":"partial",""" +
                    """"pre_update":{"sha":"aaa","version":"0.5.1"},""" +
                    """"post_update":{"sha":"bbb","version":"0.5.2"},""" +
                    """"gateway_restart":{"restarted_services":[],"fresh_recovery":{""" +
                    """"requested":["p"],"verified":[],"relaunch_attempted":[],"failed":[],""" +
                    """"skipped":[],"serve_units":{"verified":["unit-a"],"failed":["unit-b"]},""" +
                    """"stale_runtimes":[{"pid":1,"kind":"serve","profile":"p","supervisor":"none"}]}}},""" +
                    """"summary":{"outcome":"partial"}}""",
            ),
        )

        val receipt = GatewayRestClient { http }.updateReceipt().valueOrFail()

        assertEquals("api/hermes/update/receipt", http.requests.single().path)
        assertEquals("partial", receipt.outcome)
        assertEquals("0.5.1", receipt.preVersion)
        assertEquals("0.5.2", receipt.postVersion)
        assertEquals(listOf("unit-a"), receipt.serveUnitsVerified)
        assertEquals(listOf("unit-b"), receipt.serveUnitsFailed)
        assertEquals(1, receipt.staleRuntimes)
    }

    @Test
    fun `a Gateway with no recorded update says so with a 404, which survives the trip out`() = runTest {
        // `web_server.py:5940-5944`. A capability and a fact, not a retry.
        val missing = GatewayRestClient {
            RecordingGatewayHttp(GatewayHttpResult.Rejected(404, "Hermes refused that Gateway request."))
        }.updateReceipt() as GatewayRestResult.Failed

        assertEquals(404, missing.statusCode)
    }

    @Test
    fun `restarts the messaging gateway with no body and no profile scope`() = runTest {
        // `web_server.py:4988-5002`.
        val http = RecordingGatewayHttp(success("""{"ok":true,"pid":11,"name":"gateway-restart"}"""))

        val started = GatewayRestClient { http }.restartGateway().valueOrFail()

        val request = http.requests.single()
        assertEquals("api/gateway/restart", request.path)
        assertEquals("POST", request.method)
        assertEquals(emptyMap<String, String>(), request.query)
        assertEquals("", http.bodies.single())
        assertEquals(GatewayRestartStart("gateway-restart", 11L), started)
    }

    @Test
    fun `refuses a profile name it cannot vouch for before sending anything`() = runTest {
        val http = RecordingGatewayHttp()

        GatewayRestClient { http }.restartGateway(profile = "../other").assertMalformed()

        assertEquals(0, http.requests.size)
    }

    // -----------------------------------------------------------------------
    // What comes back.
    // -----------------------------------------------------------------------

    @Test
    fun `fails closed on a 2xx that is not the route's envelope`() = runTest {
        val statusBodies = listOf(
            "",
            "not json",
            "[]",
            // Each of the three the panel renders, missing in turn.
            """{"active_sessions":1,"gateway_running":true}""",
            """{"version":"0.5.1","gateway_running":true}""",
            """{"version":"0.5.1","active_sessions":1}""",
            // A quoted boolean is not a boolean; the wire says what it means.
            """{"version":"0.5.1","active_sessions":1,"gateway_running":"true"}""",
        )
        for (body in statusBodies) {
            val failed = GatewayRestClient { RecordingGatewayHttp(success(body)) }
                .status() as GatewayRestResult.Failed
            assertNull(failed.statusCode)
            assertEquals(UNUSABLE_RESPONSE_MESSAGE, failed.safeMessage)
        }

        // The two booleans decide what the sheet offers.
        for (body in listOf("""{"can_apply":true}""", """{"update_available":true}""")) {
            val failed = GatewayRestClient { RecordingGatewayHttp(success(body)) }
                .checkHermesUpdate(force = true) as GatewayRestResult.Failed
            assertNull(failed.statusCode)
        }

        // One unreadable commit poisons the changelog rather than shortening it.
        val poisoned = GatewayRestClient {
            RecordingGatewayHttp(
                success("""{"update_available":true,"can_apply":true,"commits":[{"sha":"a"},"nope"]}"""),
            )
        }.checkHermesUpdate(force = true) as GatewayRestResult.Failed
        assertNull(poisoned.statusCode)

        // `ok` is the whole discriminator on the start route.
        val noOk = GatewayRestClient { RecordingGatewayHttp(success("""{"name":"hermes-update"}""")) }
            .startHermesUpdate() as GatewayRestResult.Failed
        assertNull(noOk.statusCode)

        // A restart that did not say it worked did not work.
        val notOk = GatewayRestClient { RecordingGatewayHttp(success("""{"ok":false}""")) }
            .restartGateway() as GatewayRestResult.Failed
        assertNull(notOk.statusCode)

        // An action status with no `lines` array is not that route's answer.
        val noLines = GatewayRestClient {
            RecordingGatewayHttp(success("""{"name":"hermes-update","running":true,"exit_code":null}"""))
        }.actionStatus(GatewayAction.HermesUpdate) as GatewayRestResult.Failed
        assertNull(noLines.statusCode)

        // The receipt endpoint's envelope wraps the receipt; a summary alone is
        // not enough to read one.
        val noReceipt = GatewayRestClient { RecordingGatewayHttp(success("""{"summary":{}}""")) }
            .updateReceipt() as GatewayRestResult.Failed
        assertNull(noReceipt.statusCode)
    }

    @Test
    fun `every System route wipes the decoded body once it has read it`() = runTest {
        val bytes = """{"version":"0.5.1","active_sessions":0,"gateway_running":true}"""
            .toByteArray(Charsets.UTF_8)

        GatewayRestClient { RecordingGatewayHttp(GatewayHttpResult.Success(200, bytes)) }
            .status()
            .valueOrFail()

        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `carries no backend text out of a refusal on any System route`() = runTest {
        val failed = GatewayRestClient {
            RecordingGatewayHttp(
                GatewayHttpResult.Rejected(401, "Hermes did not accept this connection. Reconnect and try again."),
            )
        }.startHermesUpdate() as GatewayRestResult.Failed

        assertEquals(401, failed.statusCode)
        assertEquals("Hermes did not accept this connection. Reconnect and try again.", failed.safeMessage)
    }

    @Test
    fun `sends nothing at all when there is no transport`() = runTest {
        val client = GatewayRestClient { null }
        for (result in listOf(client.status(), client.startHermesUpdate(), client.restartGateway())) {
            val failed = result as GatewayRestResult.Failed
            assertEquals(0, failed.statusCode)
            assertEquals(RECONNECT_MESSAGE, failed.safeMessage)
        }
    }
}

private fun <T> GatewayRestResult<T>.valueOrFail(): T = when (this) {
    is GatewayRestResult.Success -> value
    is GatewayRestResult.Failed -> throw AssertionError("expected success, got $statusCode $safeMessage")
}

private fun <T> GatewayRestResult<T>.assertMalformed() {
    val failed = this as GatewayRestResult.Failed
    assertNull(failed.statusCode)
    assertEquals(MALFORMED_REQUEST_MESSAGE, failed.safeMessage)
}

private fun success(body: String): GatewayHttpResult =
    GatewayHttpResult.Success(200, body.toByteArray(Charsets.UTF_8))
