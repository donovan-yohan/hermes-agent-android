package com.hermesagent.mobile.plugins.bots

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BotsToolsetsViewModelTest {
    private val target = BotManagementTarget("worker", 7L)
    @Test fun `endpoint switch rejects old callbacks and held old reads`() = runTest {
        val release = CompletableDeferred<Unit>()
        val host = ToolsetsTestHost().apply { answer = { _, _ -> release.await(); toolsetsReply(toolsetsJson()) } }
        val vm = BotsToolsetsViewModel(host, backgroundScope, {})
        vm.open(target); runCurrent(); val stale = vm.state.value
        host.endpointGeneration.value = 8L; runCurrent(); release.complete(Unit); runCurrent()
        assertNull(vm.state.value.ticket)
        vm.toggle(stale, "terminal"); vm.save(stale); vm.confirmDefaults(stale)
        assertEquals(0, host.calls.count { it.first == "profiles.configure" })
    }
    @Test fun `stale preflight consumes draft without configure and reopening rereads truth`() = runTest {
        var changed = false
        val host = ToolsetsTestHost().apply { answer = { _, _ -> toolsetsReply(toolsetsJson(b = changed)) } }
        val vm = BotsToolsetsViewModel(host, backgroundScope, {})
        vm.open(target); runCurrent(); vm.toggle(vm.state.value, "terminal"); changed = true
        vm.save(vm.state.value); runCurrent()
        assertTrue(vm.state.value.consumed)
        assertEquals(0, host.calls.count { it.first == "profiles.configure" })
        vm.close(); vm.open(target); runCurrent()
        assertFalse(vm.state.value.consumed); assertEquals(setOf("web", "terminal"), vm.state.value.draft)
    }
    @Test fun `readback failure never claims success and never retries configure`() = runTest {
        var wrote = false
        val host = ToolsetsTestHost().apply { answer = { method, _ ->
            if (method == "profiles.configure") { wrote = true; toolsetsReply("""{"ok":true,"applied":{"toolsets":true}}""") }
            else if (wrote) toolsetsReply("{}") else toolsetsReply(toolsetsJson())
        } }
        var changed = 0
        val vm = BotsToolsetsViewModel(host, backgroundScope, { changed++ })
        vm.open(target); runCurrent(); vm.toggle(vm.state.value, "terminal"); vm.save(vm.state.value); runCurrent()
        assertEquals(0, changed); assertTrue(vm.state.value.consumed); assertFalse(vm.state.value.busy)
        vm.save(vm.state.value); runCurrent()
        assertEquals(1, host.calls.count { it.first == "profiles.configure" })
    }
    @Test fun `save is separate consumes uncertain operation and ignores stale callbacks`() = runTest {
        val host = ToolsetsTestHost().apply { answer = { _, _ -> toolsetsReply(toolsetsJson()) } }
        val vm = BotsToolsetsViewModel(host, backgroundScope, {})
        vm.open(target); runCurrent()
        val old = vm.state.value
        vm.toggle(old, "terminal"); assertEquals(1, host.calls.size)
        vm.save(old); assertEquals(1, host.calls.size)
        val draft = vm.state.value
        vm.save(draft); vm.save(draft); runCurrent()
        assertEquals(1, host.calls.count { it.first == "profiles.configure" })
        assertTrue(vm.state.value.consumed)
        vm.save(vm.state.value); runCurrent()
        assertEquals(1, host.calls.count { it.first == "profiles.configure" })
        vm.close(); vm.open(target); runCurrent()
        assertFalse(vm.state.value.consumed)
        vm.toggle(draft, "terminal"); assertEquals(setOf("web"), vm.state.value.draft)
    }
    @Test fun `reset requires confirmation and cancel has zero writes`() = runTest {
        val host = ToolsetsTestHost().apply { answer = { _, _ -> toolsetsReply(toolsetsJson(pinned = true)) } }
        val vm = BotsToolsetsViewModel(host, backgroundScope, {})
        vm.open(target); runCurrent()
        vm.confirmDefaults(vm.state.value); runCurrent()
        assertEquals(0, host.calls.count { it.first == "profiles.configure" })
        vm.requestDefaults(vm.state.value); vm.cancelDefaults(vm.state.value)
        assertFalse(vm.state.value.confirmDefaults)
        vm.requestDefaults(vm.state.value); vm.confirmDefaults(vm.state.value); runCurrent()
        assertEquals(1, host.calls.count { it.first == "profiles.configure" })
    }
    @Test fun `reopen while held write blocks ABA duplicate then reconciles without replay`() = runTest {
        val release = CompletableDeferred<Unit>(); var wrote = false
        val host = ToolsetsTestHost().apply { answer = { method, _ ->
            if (method == "profiles.configure") { release.await(); wrote = true; toolsetsReply("""{"ok":true,"applied":{"toolsets":true}}""") }
            else toolsetsReply(toolsetsJson(pinned = wrote, b = wrote))
        } }
        val vm = BotsToolsetsViewModel(host, backgroundScope, {})
        vm.open(target); runCurrent(); vm.toggle(vm.state.value, "terminal"); vm.save(vm.state.value); runCurrent()
        vm.close(); vm.open(target); runCurrent()
        assertTrue(vm.state.value.consumed)
        vm.save(vm.state.value); runCurrent()
        release.complete(Unit); runCurrent()
        assertFalse(vm.state.value.consumed); assertEquals(setOf("web", "terminal"), vm.state.value.draft)
        assertEquals(1, host.calls.count { it.first == "profiles.configure" })
    }
    @Test fun `close before wire revokes old dialog even after same profile reopens`() = runTest {
        val release = CompletableDeferred<Unit>()
        val host = ToolsetsTestHost().apply {
            beforeDispatch = { if (it == "profiles.configure") release.await() }
            answer = { _, _ -> toolsetsReply(toolsetsJson()) }
        }
        val vm = BotsToolsetsViewModel(host, backgroundScope, {})
        vm.open(target); runCurrent(); vm.toggle(vm.state.value, "terminal"); vm.save(vm.state.value); runCurrent()
        vm.close(); vm.open(target); runCurrent(); release.complete(Unit); runCurrent()
        assertEquals(0, host.calls.count { it.first == "profiles.configure" })
        assertFalse(vm.state.value.consumed)
    }
}
