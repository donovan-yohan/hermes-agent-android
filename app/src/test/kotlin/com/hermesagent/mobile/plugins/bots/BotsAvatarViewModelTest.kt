package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BotsAvatarViewModelTest {
    private val target = BotManagementTarget("worker", 7)
    private fun host() = ModelTestHost().apply { answer = { _, _ -> modelReply("""{"found":false}""") } }
    @Test fun `cancelled pick does not change draft or call write`() = runTest {
        val host = host(); val vm = BotsAvatarViewModel(host, backgroundScope, {})
        vm.open(target); runCurrent()
        val pick = vm.beginPick(vm.state.value)!!
        vm.finishPick(pick, null); runCurrent()
        assertFalse(vm.state.value.canSave)
        assertEquals("Image selection cancelled. Nothing was saved.", vm.state.value.message)
        assertEquals(listOf("profiles.get_asset"), host.calls.map { it.first })
    }
    @Test fun `old picker callback cannot affect replacement same name dialog`() = runTest {
        val host = host(); val vm = BotsAvatarViewModel(host, backgroundScope, {})
        vm.open(target); runCurrent(); val pick = vm.beginPick(vm.state.value)!!
        vm.close(); vm.open(target); runCurrent()
        vm.finishPick(pick, avatarPng())
        assertFalse(vm.state.value.canSave)
    }
    @Test fun `endpoint switch prevents picker and stale save from dispatching`() = runTest {
        val host = host(); val vm = BotsAvatarViewModel(host, backgroundScope, {})
        vm.open(target); runCurrent(); val pick = vm.beginPick(vm.state.value)!!
        vm.finishPick(pick, avatarPng()); val save = vm.state.value
        host.endpointGeneration.value++
        vm.save(save); runCurrent()
        assertEquals(1, host.calls.size)
        assertNull(vm.state.value.ticket)
    }
    @Test fun `save refreshes after readback and consumes repeated callbacks`() = runTest {
        val host = host(); var refreshes = 0
        val vm = BotsAvatarViewModel(host, backgroundScope, { refreshes++ })
        vm.open(target); runCurrent(); val pick = vm.beginPick(vm.state.value)!!
        vm.finishPick(pick, avatarPng()); val save = vm.state.value
        host.answer = { method, _ -> if (method == "profiles.set_asset") modelReply("""{"ok":true,"asset":"avatar","size":${avatarPng().size}}""")
            else PluginHostResult.Success(avatarReply(avatarPng())) }
        vm.save(save); vm.save(save); runCurrent()
        assertEquals("Avatar saved.", vm.state.value.message)
        assertEquals(1, refreshes)
        assertEquals(1, host.calls.count { it.first == "profiles.set_asset" })
    }
    @Test fun `recovery read supersedes a held reopen read without replay`() = runTest {
        val host = host(); val pending = CompletableDeferred<PluginHostResult>()
        val stale = CompletableDeferred<PluginHostResult>()
        val vm = BotsAvatarViewModel(host, backgroundScope, {})
        vm.open(target); runCurrent(); vm.finishPick(vm.beginPick(vm.state.value)!!, avatarPng())
        var reads = 0
        host.answer = { method, _ ->
            if (method == "profiles.set_asset") pending.await()
            else if (++reads == 1) stale.await()
            else PluginHostResult.Success(avatarReply(avatarPng()))
        }
        vm.save(vm.state.value); runCurrent()
        vm.close(); vm.open(target); runCurrent()
        assertFalse(vm.state.value.editable)
        pending.complete(modelReply("""{"ok":true,"asset":"avatar","size":${avatarPng().size}}""")); runCurrent()
        assertTrue(vm.state.value.editable)
        assertArrayEquals(avatarPng(), vm.state.value.original!!.bytes)
        stale.complete(modelReply("""{"found":false}""")); runCurrent()
        assertArrayEquals(avatarPng(), vm.state.value.original!!.bytes)
        assertTrue(vm.state.value.editable)
        assertEquals(1, host.calls.count { it.first == "profiles.set_asset" })
        assertTrue(host.calls.all { it.second["name"] == kotlinx.serialization.json.JsonPrimitive("worker") })
    }
    @Test fun `failed recovery read gives explicit recovery instead of replay`() = runTest {
        val host = host(); val pending = CompletableDeferred<PluginHostResult>()
        val vm = BotsAvatarViewModel(host, backgroundScope, {})
        vm.open(target); runCurrent(); vm.finishPick(vm.beginPick(vm.state.value)!!, avatarPng())
        host.answer = { method, _ -> if (method == "profiles.set_asset") pending.await()
            else PluginHostResult.UnavailableOnGateway }
        vm.save(vm.state.value); runCurrent(); vm.close(); vm.open(target); runCurrent()
        pending.complete(PluginHostResult.UnavailableOnGateway); runCurrent()
        assertFalse(vm.state.value.editable)
        assertEquals("The avatar could not be read. Close and try again.", vm.state.value.message)
        assertEquals(1, host.calls.count { it.first == "profiles.set_asset" })
    }
    @Test fun `pending same target cannot be written again across close reopen`() = runTest {
        val host = host(); val pending = CompletableDeferred<PluginHostResult>()
        val vm = BotsAvatarViewModel(host, backgroundScope, {})
        vm.open(target); runCurrent(); vm.finishPick(vm.beginPick(vm.state.value)!!, avatarPng())
        host.answer = { method, _ -> if (method == "profiles.set_asset") pending.await() else modelReply("""{"found":false}""") }
        vm.save(vm.state.value); runCurrent(); vm.close(); vm.open(target); runCurrent()
        assertNull(vm.beginPick(vm.state.value))
        assertEquals(1, host.calls.count { it.first == "profiles.set_asset" })
    }
}
