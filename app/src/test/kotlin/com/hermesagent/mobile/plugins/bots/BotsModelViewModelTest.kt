package com.hermesagent.mobile.plugins.bots

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BotsModelViewModelTest {
    private fun setup(host: ModelTestHost) {
        host.answer = { method, params -> when (method) {
            "profiles.describe" -> modelReply("""{"name":${params["name"]},"model":{"provider":"custom","default":"original"}}""")
            "model.options" -> modelReply("""{"providers":[]}""")
            else -> modelReply("""{"ok":true,"confirm_required":true,"confirm_message":"Review cost"}""")
        } }
    }
    private val a = BotManagementTarget("a", 7L)
    private val b = BotManagementTarget("b", 7L)

    @Test fun `stale A callbacks refuse B and reopened A dialog`() = runTest {
        val host = ModelTestHost(); setup(host)
        val vm = BotsModelViewModel(host, backgroundScope, {})
        vm.open(a); runCurrent(); val old = vm.state.value
        vm.open(b); runCurrent(); vm.update(old, BotModelSelection("p", "m")); vm.save(old)
        vm.open(a); runCurrent(); vm.save(old)
        assertEquals("original", vm.state.value.draft.model)
        assertTrue(host.calls.none { it.first == "profiles.configure" })
    }

    @Test fun `warning requires current explicit consent and cannot replay after edit or cancel`() = runTest {
        val host = ModelTestHost(); setup(host)
        val vm = BotsModelViewModel(host, backgroundScope, {})
        vm.open(a); runCurrent(); vm.update(vm.state.value, BotModelSelection("p", "guarded"))
        vm.save(vm.state.value); runCurrent()
        val warning = vm.state.value
        assertNotNull(warning.warning); assertEquals(1, host.calls.count { it.first == "profiles.configure" })
        vm.cancelWarning(warning); vm.confirm(warning); runCurrent()
        assertEquals(1, host.calls.count { it.first == "profiles.configure" })
        vm.save(vm.state.value); runCurrent(); vm.confirm(vm.state.value); runCurrent()
        assertEquals(3, host.calls.count { it.first == "profiles.configure" })
        assertEquals(JsonPrimitive(true), host.calls.last { it.first == "profiles.configure" }.second["confirm_expensive_model"])
        assertTrue(vm.state.value.consumed) // repeated warning after consent is uncertain, not another loop
    }

    @Test fun `inventory failure preserves a draft typed after describe`() = runTest {
        val host = ModelTestHost(); setup(host); val usual = host.answer
        val inventory = CompletableDeferred<Unit>()
        host.answer = { method, params -> if (method == "model.options") { inventory.await(); modelReply("{}") } else usual(method, params) }
        val vm = BotsModelViewModel(host, backgroundScope, {})
        vm.open(a); runCurrent()
        val draft = BotModelSelection("unknown", "manual")
        vm.update(vm.state.value, draft)
        inventory.complete(Unit); runCurrent()
        assertEquals(draft, vm.state.value.draft); assertNotNull(vm.state.value.inventoryMessage)
    }

    @Test fun `held write survives dialog A B A without duplicate and cannot publish stale receipt`() = runTest {
        val host = ModelTestHost(); setup(host); val usual = host.answer
        val held = CompletableDeferred<Unit>()
        host.answer = { method, params -> if (method == "profiles.configure") { held.await(); modelReply("{}") } else usual(method, params) }
        val vm = BotsModelViewModel(host, backgroundScope, {})
        vm.open(a); runCurrent(); vm.update(vm.state.value, BotModelSelection("p", "m")); vm.save(vm.state.value); runCurrent()
        vm.close(); vm.open(b); runCurrent(); assertFalse(vm.state.value.consumed)
        vm.open(a); runCurrent(); vm.save(vm.state.value); runCurrent()
        assertTrue(vm.state.value.consumed)
        held.complete(Unit); runCurrent()
        assertEquals(1, host.calls.count { it.first == "profiles.configure" })
        assertNull(vm.state.value.warning)
    }

    @Test fun `endpoint ABA before dispatch drops accepted write and all stale callbacks`() = runTest {
        val host = ModelTestHost(); setup(host)
        val vm = BotsModelViewModel(host, backgroundScope, {})
        vm.open(a); runCurrent(); vm.update(vm.state.value, BotModelSelection("p", "m"))
        val before = vm.state.value
        val gate = CompletableDeferred<Unit>(); host.beforeDispatch = { gate.await() }
        vm.save(before); runCurrent()
        host.endpointGeneration.value = 8L; host.endpointGeneration.value = 9L
        vm.confirm(before); gate.complete(Unit); runCurrent()
        assertTrue(host.calls.none { it.first == "profiles.configure" })
        assertNull(vm.state.value.ticket)
    }

    @Test fun `inventory timeout settles without changing unknown profile pair`() = runTest {
        val host = ModelTestHost(); setup(host); val usual = host.answer
        host.answer = { method, params -> if (method == "model.options") { CompletableDeferred<Unit>().await(); modelReply("{}") } else usual(method, params) }
        val vm = BotsModelViewModel(host, backgroundScope, {}, BotsModelRepository(host, 100L))
        vm.open(a); runCurrent(); advanceTimeBy(100L); runCurrent()
        assertFalse(vm.state.value.inventoryLoading)
        assertEquals(BotModelSelection("custom", "original"), vm.state.value.draft)
        assertNotNull(vm.state.value.inventoryMessage)
        assertTrue(vm.state.value.editable)
    }

    @Test fun `late A describe cannot overwrite reopened A draft`() = runTest {
        val host = ModelTestHost(); setup(host); val usual = host.answer
        val gate = CompletableDeferred<Unit>(); var held = false
        host.answer = { method, params ->
            if (!held && method == "profiles.describe") { held = true; gate.await() }
            usual(method, params)
        }
        val vm = BotsModelViewModel(host, backgroundScope, {})
        vm.open(a); runCurrent(); vm.open(b); runCurrent(); vm.open(a); runCurrent()
        vm.update(vm.state.value, BotModelSelection("mine", "manual"))
        gate.complete(Unit); runCurrent()
        assertEquals(BotModelSelection("mine", "manual"), vm.state.value.draft)
    }

    @Test fun `draft edit revokes held confirmation callback even if changed back`() = runTest {
        val host = ModelTestHost(); setup(host)
        val vm = BotsModelViewModel(host, backgroundScope, {})
        vm.open(a); runCurrent(); val selection = BotModelSelection("p", "guarded")
        vm.update(vm.state.value, selection); vm.save(vm.state.value); runCurrent()
        val warning = vm.state.value
        vm.update(warning, BotModelSelection("p", "other")); vm.update(vm.state.value, selection)
        vm.confirm(warning); runCurrent()
        assertNull(vm.state.value.warning)
        assertEquals(1, host.calls.count { it.first == "profiles.configure" })
    }

    @Test fun `uncertain mutation is consumed and no automatic retry happens`() = runTest {
        val host = ModelTestHost(); setup(host); val usual = host.answer
        host.answer = { method, params -> if (method == "profiles.configure") modelReply("{}") else usual(method, params) }
        val vm = BotsModelViewModel(host, backgroundScope, {})
        vm.open(a); runCurrent(); vm.update(vm.state.value, BotModelSelection("p", "m"))
        vm.save(vm.state.value); runCurrent(); vm.save(vm.state.value); runCurrent()
        assertTrue(vm.state.value.consumed); assertEquals(1, host.calls.count { it.first == "profiles.configure" })
    }
}
