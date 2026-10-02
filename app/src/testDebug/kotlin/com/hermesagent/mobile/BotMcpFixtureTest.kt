package com.hermesagent.mobile

import kotlinx.coroutines.test.*
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BotMcpFixtureTest {
    @Test fun `real typed scope closes without cancelling admitted plugin job and reopened read reconciles`() = runTest {
        val fixture = BotMcpFixture("mcp-disabled", backgroundScope)
        backgroundScope.launch { fixture.stage() }; runCurrent()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        fixture.afterPut = { release.await() }
        fixture.vm.toggle(fixture.vm.state.value, "synthetic-research"); runCurrent()
        assertTrue(fixture.vm.state.value.busy)
        fixture.vm.close()
        fixture.vm.open(com.hermesagent.mobile.plugins.bots.BotManagementTarget("synthetic-mcp", 0)); runCurrent()
        assertTrue(fixture.vm.state.value.busy)
        fixture.vm.toggle(fixture.vm.state.value, "synthetic-research"); runCurrent()
        release.complete(Unit); runCurrent()
        assertEquals(1, fixture.calls.count { it == "PUT" })
        assertTrue(fixture.vm.state.value.rows!!.single().enabled)
        assertFalse(fixture.vm.state.value.busy)
        assertTrue(fixture.vm.state.value.message!!.startsWith("Change not confirmed"))
        assertFalse(fixture.snapshot().toString().contains("cancelled"))
    }
    @Test fun `typed consumer reconciles post admission cancellation without replay`() = runTest {
        val fixture = BotMcpFixture("mcp-disabled", backgroundScope)
        backgroundScope.launch { fixture.stage() }; runCurrent()
        fixture.cancelAfterPut = true
        fixture.vm.toggle(fixture.vm.state.value, "synthetic-research"); runCurrent()
        assertEquals(1, fixture.calls.count { it == "PUT" })
        assertTrue(fixture.vm.state.value.rows!!.first().enabled)
        assertTrue(fixture.vm.state.value.message!!.startsWith("Change not confirmed"))
        fixture.vm.close(); fixture.vm.open(com.hermesagent.mobile.plugins.bots.BotManagementTarget("synthetic-mcp", 0)); runCurrent()
        assertEquals(1, fixture.calls.count { it == "PUT" })
    }
    @Test fun `runtime snapshot records actual named readback and pending response outcomes`() = runTest {
        val fixture = BotMcpFixture("mcp-saved", backgroundScope)
        backgroundScope.launch { fixture.stage() }; runCurrent()
        val snapshot = fixture.snapshot()
        val requests = snapshot.getValue("requests") as kotlinx.serialization.json.JsonArray
        assertEquals(listOf("GET", "GET", "PUT", "GET"), fixture.calls)
        assertEquals(4, requests.size)
        assertTrue(requests.all { it.toString().contains("synthetic-mcp") && it.toString().contains("completed") })
        assertFalse(snapshot.toString().contains("secret"))
    }

    @Test fun `loading and pending keep the twenty second production deadline`() = runTest {
        for (name in listOf("mcp-loading", "mcp-pending")) {
            val fixture = BotMcpFixture(name, backgroundScope)
            backgroundScope.launchStage(fixture); runCurrent()
            assertTrue(fixture.vm.state.value.loading || fixture.vm.state.value.busy)
            advanceTimeBy(19_999); runCurrent()
            assertTrue(fixture.vm.state.value.loading || fixture.vm.state.value.busy)
            advanceTimeBy(1); runCurrent()
            assertFalse(fixture.vm.state.value.loading)
            assertFalse(fixture.vm.state.value.busy)
            assertNotNull(fixture.vm.state.value.message)
            assertFalse(fixture.vm.state.value.message!!.contains("saved", ignoreCase = true))
            fixture.vm.close()
        }
    }
    @Test fun `capture states stage real reads toggles and named readback`() = runTest {
        for (name in BotMcpFixture.STATES) {
            val fixture = BotMcpFixture(name, backgroundScope)
            backgroundScope.launchStage(fixture)
            runCurrent()
            val state = fixture.vm.state.value
            assertNotNull(state.ticket)
            when (name) {
                "mcp-loading" -> assertTrue(state.loading)
                "mcp-pending" -> { assertTrue(state.busy); assertEquals(1, fixture.calls.count { it == "PUT" }) }
                "mcp-saved", "mcp-reopened" -> {
                    assertFalse(state.busy); assertTrue(state.rows!!.first().enabled)
                    assertEquals(1, fixture.calls.count { it == "PUT" })
                }
                "mcp-refused", "mcp-unconfirmed" -> {
                    assertFalse(state.busy); assertTrue(state.message!!.startsWith("Change not confirmed"))
                    assertEquals(1, fixture.calls.count { it == "PUT" })
                }
                "mcp-error", "mcp-unavailable" -> { assertNull(state.rows); assertNotNull(state.message) }
                "mcp-empty" -> assertEquals(emptyList<Any>(), state.rows)
                else -> { assertNotNull(state.rows); assertFalse(state.busy) }
            }
            fixture.vm.close()
        }
    }
}
private fun kotlinx.coroutines.CoroutineScope.launchStage(fixture: BotMcpFixture) =
    launch { fixture.stage() }
