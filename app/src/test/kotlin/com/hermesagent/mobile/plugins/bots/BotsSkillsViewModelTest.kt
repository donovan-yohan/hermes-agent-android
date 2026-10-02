package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

internal class SkillsTestHost : PluginHost {
    override val endpointGeneration = MutableStateFlow(7L)
    override val connected = MutableStateFlow(true)
    var rows = listOf(InstalledSkill("research", false), InstalledSkill("hermes-agent", true))
    val writes = mutableListOf<Triple<String, String, Boolean>>()
    var beforeWrite: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    var beforeRead: suspend () -> Unit = {}
    var refuse = false
    override val skills = object : PluginSkills {
        override fun openScope(expectedEndpoint: Long, profile: String): PluginSkillsScope = object : PluginSkillsScope {
            var closed = false
            override fun close() { closed = true }
            override suspend fun listInstalled(): SkillsResult<List<InstalledSkill>> {
                val captured = rows
                beforeRead()
                return if (closed || expectedEndpoint != endpointGeneration.value) SkillsResult.Failure(SkillsFailure.StaleScope)
                    else SkillsResult.Success(captured)
            }
            override suspend fun toggleInstalled(name: String, enabled: Boolean): SkillsResult<List<InstalledSkill>> {
                beforeWrite()
                if (closed || expectedEndpoint != endpointGeneration.value) return SkillsResult.Failure(SkillsFailure.StaleScope)
                writes += Triple(profile, name, enabled)
                afterWrite()
                if (refuse) return SkillsResult.Failure(SkillsFailure.Refused, true)
                if (name == "hermes-agent") return SkillsResult.Failure(SkillsFailure.Unconfirmed, true)
                rows = rows.map { if (it.name == name) it.copy(enabled = enabled) else it }
                return if (closed) SkillsResult.Failure(SkillsFailure.StaleScope, true) else SkillsResult.Success(rows)
            }
        }
    }
    override suspend fun request(method: String, params: JsonObject): PluginHostResult = error("No RPC")
    override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BotsSkillsViewModelTest {
    private val target = BotManagementTarget("worker", 7L)
    @Test fun `reopen retains admitted mutation and blocks duplicates until fresh reconciliation`() = runTest {
        val release = CompletableDeferred<Unit>()
        val host = SkillsTestHost().apply { afterWrite = { release.await() } }
        val vm = BotsSkillsViewModel(host, backgroundScope)
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
        val host = SkillsTestHost().apply { afterWrite = { write.await() } }
        val vm = BotsSkillsViewModel(host, backgroundScope)
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
        val host = SkillsTestHost(); val vm = BotsSkillsViewModel(host, backgroundScope)
        vm.open(target); runCurrent(); val old = vm.state.value
        host.endpointGeneration.value = 8L; runCurrent()
        assertNull(vm.state.value.ticket)
        vm.toggle(old, "research"); runCurrent(); assertTrue(host.writes.isEmpty())
    }
    @Test fun `essential echo refusal and cancellation reconcile without replay or saved claim`() = runTest {
        for (mode in listOf("essential", "refused", "cancelled")) {
            val host = SkillsTestHost().apply {
                refuse = mode == "refused"
                if (mode == "cancelled") afterWrite = { throw kotlinx.coroutines.CancellationException("synthetic") }
            }
            val vm = BotsSkillsViewModel(host, backgroundScope)
            vm.open(target); runCurrent()
            vm.toggle(vm.state.value, if (mode == "essential") "hermes-agent" else "research"); runCurrent()
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
        val host = SkillsTestHost().apply { afterWrite = { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() } } }
        val vm = BotsSkillsViewModel(host, backgroundScope)
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
        val host = SkillsTestHost().apply { beforeWrite = { release.await() } }
        val vm = BotsSkillsViewModel(host, backgroundScope)
        vm.open(target); runCurrent(); vm.toggle(vm.state.value, "research"); runCurrent()
        vm.close(); vm.open(target); runCurrent()
        assertTrue(vm.state.value.busy)
        release.complete(Unit); runCurrent()
        assertTrue(host.writes.isEmpty())
        assertFalse(vm.state.value.busy)
        assertFalse(vm.state.value.rows!!.first().enabled)
    }
    @Test fun `toggle autosaves one installed name without outer save`() = runTest {
        val host = SkillsTestHost()
        val vm = BotsSkillsViewModel(host, backgroundScope)
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
