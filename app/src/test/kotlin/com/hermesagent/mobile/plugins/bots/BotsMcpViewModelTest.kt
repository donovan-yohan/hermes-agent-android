package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

internal class McpTestHost : PluginHost {
    override val endpointGeneration = MutableStateFlow(7L)
    override val connected = MutableStateFlow(true)
    var rows = listOf(McpServer("research", false, McpSource.Config), McpServer("unchanged", true, McpSource.Config))
    val writes = mutableListOf<Triple<String, String, Boolean>>()
    var beforeWrite: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    var beforeRead: suspend () -> Unit = {}
    var refuse = false
    override val mcp = object : PluginMcp {
        override fun openScope(expectedEndpoint: Long, profile: String): PluginMcpScope = object : PluginMcpScope {
            var closed = false
            override fun close() { closed = true }
            override suspend fun listServers(): McpResult<List<McpServer>> {
                val captured = rows
                beforeRead()
                return if (closed || expectedEndpoint != endpointGeneration.value) McpResult.Failure(McpFailure.StaleScope)
                    else McpResult.Success(captured)
            }
            override suspend fun setEnabled(name: String, enabled: Boolean): McpResult<List<McpServer>> {
                beforeWrite()
                if (closed || expectedEndpoint != endpointGeneration.value) return McpResult.Failure(McpFailure.StaleScope)
                writes += Triple(profile, name, enabled)
                afterWrite()
                if (refuse) return McpResult.Failure(McpFailure.Refused, true)
                if (name == "unchanged") return McpResult.Failure(McpFailure.Unconfirmed, true)
                rows = rows.map { if (it.name == name) it.copy(enabled = enabled) else it }
                return if (closed) McpResult.Failure(McpFailure.StaleScope, true) else McpResult.Success(rows)
            }
        }
    }
    override suspend fun request(method: String, params: JsonObject): PluginHostResult = error("No RPC")
    override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BotsMcpViewModelTest {
    private val target = BotManagementTarget("worker", 7L)
    @Test fun `reopen retains admitted mutation and blocks duplicates until fresh reconciliation`() = runTest {
        val release = CompletableDeferred<Unit>()
        val host = McpTestHost().apply { afterWrite = { release.await() } }
        val vm = BotsMcpViewModel(host, backgroundScope)
        vm.open(target); runCurrent(); vm.toggle(vm.state.value, "research"); runCurrent()
        vm.close(); vm.open(target); runCurrent()
        assertTrue(vm.state.value.busy)
        vm.toggle(vm.state.value, "research"); runCurrent()
        release.complete(Unit); runCurrent()
        assertEquals(1, host.writes.size)
        assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.rows!!.first().enabled)
    }
    @Test fun `late reopen read cannot overwrite completed mutation reconciliation`() = runTest {
        val write = CompletableDeferred<Unit>(); val read = CompletableDeferred<Unit>()
        val host = McpTestHost().apply { afterWrite = { write.await() } }
        val vm = BotsMcpViewModel(host, backgroundScope)
        vm.open(target); runCurrent(); vm.toggle(vm.state.value, "research"); runCurrent()
        host.beforeRead = { read.await() }
        vm.close(); vm.open(target); runCurrent()
        host.beforeRead = {}
        write.complete(Unit); runCurrent()
        assertTrue(vm.state.value.rows!!.first().enabled)
        read.complete(Unit); runCurrent()
        assertTrue(vm.state.value.rows!!.first().enabled)
    }
    @Test fun `endpoint switch closes authority and old snapshot never sends`() = runTest {
        val host = McpTestHost(); val vm = BotsMcpViewModel(host, backgroundScope)
        vm.open(target); runCurrent(); val old = vm.state.value
        host.endpointGeneration.value = 8L; runCurrent()
        assertNull(vm.state.value.ticket)
        vm.toggle(old, "research"); runCurrent(); assertTrue(host.writes.isEmpty())
    }
    @Test fun `unchanged echo refusal and cancellation reconcile without replay or saved claim`() = runTest {
        for (mode in listOf("unchanged", "refused", "cancelled")) {
            val host = McpTestHost().apply {
                refuse = mode == "refused"
                if (mode == "cancelled") afterWrite = { throw kotlinx.coroutines.CancellationException("synthetic") }
            }
            val vm = BotsMcpViewModel(host, backgroundScope)
            vm.open(target); runCurrent()
            vm.toggle(vm.state.value, if (mode == "unchanged") "unchanged" else "research"); runCurrent()
            assertFalse(vm.state.value.busy)
            assertEquals(host.rows, vm.state.value.rows)
            assertNotNull(vm.state.value.message)
            assertFalse(vm.state.value.message!!.contains("saved", ignoreCase = true))
            assertEquals(1, host.writes.size)
            vm.close()
        }
    }
    @Test fun `profile ABA retains original operation and stale callbacks cannot mutate another dialog`() = runTest {
        val release = CompletableDeferred<Unit>()
        val host = McpTestHost().apply { afterWrite = { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() } } }
        val vm = BotsMcpViewModel(host, backgroundScope)
        vm.open(target); runCurrent(); val old = vm.state.value
        vm.toggle(old, "research"); runCurrent()
        vm.open(BotManagementTarget("other", 7L)); runCurrent()
        val other = vm.state.value
        assertTrue(other.editable)
        vm.toggle(old, "research")
        vm.open(target); runCurrent()
        assertTrue(vm.state.value.busy)
        vm.toggle(other, "research")
        release.complete(Unit); runCurrent()
        assertEquals(listOf(Triple("worker", "research", true)), host.writes)
        assertTrue(vm.state.value.rows!!.first().enabled)
    }
    @Test fun `close before dispatch revokes queued work even on same profile reopen`() = runTest {
        val release = CompletableDeferred<Unit>()
        val host = McpTestHost().apply { beforeWrite = { release.await() } }
        val vm = BotsMcpViewModel(host, backgroundScope)
        vm.open(target); runCurrent(); vm.toggle(vm.state.value, "research"); runCurrent()
        vm.close(); vm.open(target); runCurrent()
        assertTrue(vm.state.value.busy)
        release.complete(Unit); runCurrent()
        assertTrue(host.writes.isEmpty())
        assertFalse(vm.state.value.busy)
        assertFalse(vm.state.value.rows!!.first().enabled)
    }
    @Test fun `non config rows and unknown identifiers cannot enqueue`() = runTest {
        val host = McpTestHost().apply { rows = listOf(
            McpServer("plugin", true, McpSource.Plugin), McpServer("other", false, McpSource.Other)) }
        val vm = BotsMcpViewModel(host, backgroundScope)
        vm.open(target); runCurrent()
        for (name in listOf("plugin", "other", "absent")) vm.toggle(vm.state.value, name)
        runCurrent(); assertTrue(host.writes.isEmpty())
    }
    @Test fun `pending is non optimistic and offline callbacks do not send`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val host = McpTestHost().apply { afterWrite = { gate.await() } }
        val vm = BotsMcpViewModel(host, backgroundScope)
        vm.open(target); runCurrent(); val snapshot = vm.state.value
        host.connected.value = false; vm.toggle(snapshot, "research"); runCurrent()
        assertTrue(host.writes.isEmpty())
        host.connected.value = true; vm.toggle(snapshot, "research"); runCurrent()
        assertFalse(vm.state.value.rows!!.first().enabled)
        assertTrue(vm.state.value.busy)
        gate.complete(Unit); runCurrent()
        assertEquals("MCP selection saved. Applies to future sessions or Gateway starts.", vm.state.value.message)
    }
    @Test fun `endpoint A B A old completion never publishes into new endpoint`() = runTest {
        val release = CompletableDeferred<Unit>()
        val host = McpTestHost().apply { afterWrite = { release.await() } }
        val vm = BotsMcpViewModel(host, backgroundScope)
        vm.open(target); runCurrent(); vm.toggle(vm.state.value, "research"); runCurrent()
        host.endpointGeneration.value = 8; runCurrent()
        host.endpointGeneration.value = 9
        vm.open(BotManagementTarget("worker", 9)); runCurrent()
        val next = vm.state.value
        release.complete(Unit); runCurrent()
        assertSame(next, vm.state.value)
        assertFalse(vm.state.value.rows!!.first().enabled)
        assertEquals(1, host.writes.size)
    }
    @Test fun `exact unknown config identifier roundtrips and can be disabled`() = runTest {
        val name = "Research +β"
        val host = McpTestHost().apply { rows = listOf(McpServer(name, true, McpSource.Config)) }
        val vm = BotsMcpViewModel(host, backgroundScope)
        vm.open(target); runCurrent(); vm.toggle(vm.state.value, name); runCurrent()
        assertEquals(listOf(Triple("worker", name, false)), host.writes)
        assertFalse(vm.state.value.rows!!.single().enabled)
    }
    @Test fun `failure during reconciliation keeps uncertainty and removes edit authority`() = runTest {
        val host = McpTestHost().apply {
            refuse = true
            afterWrite = { beforeRead = { error("synthetic error must never render") } }
        }
        val vm = BotsMcpViewModel(host, backgroundScope)
        vm.open(target); runCurrent(); vm.toggle(vm.state.value, "research"); runCurrent()
        assertFalse(vm.state.value.editable)
        assertNull(vm.state.value.rows)
        assertTrue(vm.state.value.message!!.startsWith("Change not confirmed"))
        assertFalse(vm.state.value.message!!.contains("synthetic"))
        assertEquals(1, host.writes.size)
    }
    @Test fun `late read and stale refresh cannot replace newer dialog state`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val host = McpTestHost().apply { beforeRead = { gate.await() } }
        val vm = BotsMcpViewModel(host, backgroundScope)
        vm.open(target); runCurrent(); val old = vm.state.value
        host.beforeRead = {}
        host.rows = listOf(McpServer("replacement", true, McpSource.Config))
        vm.open(target); runCurrent(); val fresh = vm.state.value
        vm.refresh(old); gate.complete(Unit); runCurrent()
        assertSame(fresh, vm.state.value)
    }
    @Test fun `invalid alias disconnected and stale targets never open authority`() = runTest {
        val host = McpTestHost(); val vm = BotsMcpViewModel(host, backgroundScope)
        for (name in listOf("current", "", "bad/name")) {
            vm.open(BotManagementTarget(name, 7)); runCurrent(); assertNull(vm.state.value.ticket)
        }
        vm.open(BotManagementTarget("worker", 8)); runCurrent(); assertNull(vm.state.value.ticket)
        host.connected.value = false
        vm.open(target); runCurrent(); assertNull(vm.state.value.ticket)
        assertTrue(host.writes.isEmpty())
    }
    @Test fun `toggle autosaves one configured name without outer save`() = runTest {
        val host = McpTestHost()
        val vm = BotsMcpViewModel(host, backgroundScope)
        vm.open(target); runCurrent()
        vm.toggle(vm.state.value, "absent")
        assertTrue(host.writes.isEmpty())
        vm.toggle(vm.state.value, "research"); runCurrent()
        assertEquals(listOf(Triple("worker", "research", true)), host.writes)
        assertTrue(vm.state.value.rows!!.first().enabled)
        vm.close(); vm.open(target); runCurrent()
        assertTrue(vm.state.value.rows!!.first().enabled)
    }
}
