package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BotsManagementViewModelTest {
    private class Host : PluginHost {
        override val endpointGeneration = MutableStateFlow(7L)
        override val connected = MutableStateFlow(true)
        val calls = mutableListOf<String>()
        var reply: suspend (String) -> PluginHostResult = { PluginHostResult.Refused(0, "private") }
        override suspend fun request(method: String, params: JsonObject): PluginHostResult { calls += method; return reply(method) }
        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }
    private class Storage : PluginStorage {
        val values = mutableMapOf<String, String>()
        var beforeGet: suspend (String) -> Unit = {}
        var beforeSet: suspend (String) -> Unit = {}
        override suspend fun get(key: String, fallback: String?): String? { beforeGet(key); return values[key] ?: fallback }
        override suspend fun set(key: String, value: String) { beforeSet(key); values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }
    @Test fun `switch before queued section mutation never writes another endpoint`() = runTest {
        val host = Host(); val storage = Storage()
        val model = BotsManagementViewModel(host, storage, backgroundScope, onChanged = {}, storageEndpoint = MutableStateFlow(BotStorageEndpoint("A", 7L)))
        runCurrent(); model.openSection(7L); model.updateSectionName("Only A"); model.submit()
        host.endpointGeneration.value = 8L
        runCurrent()
        assertTrue(storage.values.isEmpty())
        assertTrue(model.sections.value.isEmpty())
    }

    @Test fun `stable A and B stores survive generation changes and never share records`() = runTest {
        val host = Host(); val storage = Storage()
        val endpoint = MutableStateFlow<BotStorageEndpoint?>(BotStorageEndpoint("A", 7L))
        val vm = BotsManagementViewModel(host, storage, backgroundScope, {}, storageEndpoint = endpoint)
        runCurrent(); vm.openSection(7L); vm.updateSectionName("A only"); vm.submit(); runCurrent()
        host.endpointGeneration.value = 8L; endpoint.value = BotStorageEndpoint("B", 8L); runCurrent()
        assertTrue(vm.sections.value.isEmpty())
        vm.openSection(8L); vm.updateSectionName("B only"); vm.submit(); runCurrent()
        host.endpointGeneration.value = 9L; endpoint.value = BotStorageEndpoint("A", 9L); runCurrent()
        assertEquals(listOf("A only"), vm.sections.value.map { it.name })
        assertEquals(2, storage.values.size)
        val restored = BotsManagementViewModel(host, storage, backgroundScope, {}, storageEndpoint = endpoint)
        runCurrent(); assertEquals(vm.sections.value, restored.sections.value)
    }

    @Test fun `switch while set or readback is suspended cannot publish A into B`() = runTest {
        for (duringReadback in listOf(false, true)) {
            val host = Host(); val storage = Storage()
            val endpoint = MutableStateFlow<BotStorageEndpoint?>(BotStorageEndpoint("A", 7L))
            var changed = 0
            val vm = BotsManagementViewModel(host, storage, backgroundScope, { changed++ }, storageEndpoint = endpoint)
            runCurrent()
            val gate = CompletableDeferred<Unit>()
            if (duringReadback) storage.beforeGet = { key -> if (key.endsWith("41")) gate.await() }
            else storage.beforeSet = { gate.await() }
            vm.openSection(7L); vm.updateSectionName("A only"); vm.submit(); runCurrent()
            assertTrue(vm.state.value.busy)
            host.endpointGeneration.value = 8L; endpoint.value = BotStorageEndpoint("B", 8L); runCurrent()
            assertTrue(vm.sections.value.isEmpty())
            gate.complete(Unit); runCurrent()
            assertTrue(vm.sections.value.isEmpty()); assertNull(vm.state.value.dialog); assertEquals(0, changed)
            assertEquals(1, storage.values.size)
            assertTrue(storage.values.keys.single().endsWith("41"))
        }
    }

    @Test fun `late initial load cannot leak and unknown identity never reads legacy global key`() = runTest {
        val host = Host(); val storage = Storage()
        storage.values["bot-sections-v1"] = """[{"id":"old","name":"Unowned"}]"""
        val unknown = BotsManagementViewModel(host, storage, backgroundScope, {})
        runCurrent(); unknown.openSection(7L); unknown.updateSectionName("Nope"); unknown.submit(); runCurrent()
        assertTrue(unknown.sections.value.isEmpty()); assertTrue(unknown.state.value.consumed)
        assertEquals(1, storage.values.size)
        val endpoint = MutableStateFlow<BotStorageEndpoint?>(BotStorageEndpoint("A", 7L))
        val gate = CompletableDeferred<Unit>()
        storage.values["bot-sections-v2-41"] = """[{"id":"a","name":"A only"}]"""
        storage.beforeGet = { key -> if (key.endsWith("41")) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() } }
        val vm = BotsManagementViewModel(host, storage, backgroundScope, {}, storageEndpoint = endpoint)
        runCurrent(); host.endpointGeneration.value = 8L; endpoint.value = BotStorageEndpoint("B", 8L)
        runCurrent(); gate.complete(Unit); runCurrent()
        assertTrue(vm.sections.value.isEmpty())
    }

    @Test fun `section creation persists and is restored without contacting gateway`() = runTest {
        val host = Host(); val storage = Storage()
        val model = BotsManagementViewModel(host, storage, backgroundScope, onChanged = {}, storageEndpoint = MutableStateFlow(BotStorageEndpoint("A", 7L)))
        runCurrent()
        model.openSection(7L)
        model.updateSectionName("Clients")
        model.submit()
        runCurrent()
        assertEquals("Clients", model.sections.value.single().name)
        val restored = BotsManagementViewModel(host, storage, backgroundScope, onChanged = {}, storageEndpoint = MutableStateFlow(BotStorageEndpoint("A", 7L)))
        runCurrent()
        assertEquals(model.sections.value, restored.sections.value)
        assertTrue(host.calls.isEmpty())
    }
    @Test fun `renaming a section preserves its display order`() = runTest {
        val host = Host(); val storage = Storage()
        val sections = listOf(BotSection("a", "A"), BotSection("b", "B"))
        val model = BotsManagementViewModel(host, storage, backgroundScope, onChanged = {}, storageEndpoint = MutableStateFlow(BotStorageEndpoint("A", 7L)), initialSections = sections)
        runCurrent()
        model.openSection(7L, sections.first())
        model.updateSectionName("Renamed")
        model.submit(); runCurrent()
        assertEquals(listOf(BotSection("a", "Renamed"), sections.last()), model.sections.value)
    }

    @Test fun `section reordering persists only local order`() = runTest {
        val host = Host(); val storage = Storage()
        val sections = listOf(BotSection("a", "A"), BotSection("b", "B"))
        val model = BotsManagementViewModel(host, storage, backgroundScope, onChanged = {}, storageEndpoint = MutableStateFlow(BotStorageEndpoint("A", 7L)), initialSections = sections)
        runCurrent()
        model.openSection(7L, sections.last(), choices = sections)
        model.moveSection(-1); runCurrent()
        assertEquals(sections.reversed(), model.sections.value)
        assertTrue(host.calls.isEmpty())
    }

    @Test fun `switch drops admitted dialog and stale submit cannot create on new endpoint`() = runTest {
        val host = Host()
        val model = BotsManagementViewModel(host, Storage(), backgroundScope, onChanged = {}, storageEndpoint = MutableStateFlow(BotStorageEndpoint("A", 7L)))
        runCurrent()
        model.openNew(7L)
        model.updateDraft(BotIdentityDraft(name = "worker"))
        host.endpointGeneration.value = 8L
        model.submit() // collector deliberately has not run
        runCurrent()
        assertNull(model.state.value.dialog)
        assertTrue(host.calls.isEmpty())
    }
    @Test fun `uncertain creation is consumed and cannot submit twice`() = runTest {
        val host = Host()
        val pending = CompletableDeferred<PluginHostResult>()
        host.reply = { pending.await() }
        val model = BotsManagementViewModel(host, Storage(), backgroundScope, onChanged = {}, storageEndpoint = MutableStateFlow(BotStorageEndpoint("A", 7L)))
        runCurrent()
        model.openNew(7L)
        model.updateDraft(BotIdentityDraft(name = "worker"))
        model.submit(); model.submit()
        runCurrent()
        assertEquals(listOf("profiles.create"), host.calls)
        pending.complete(PluginHostResult.Refused(0, "private"))
        runCurrent()
        model.submit(); runCurrent()
        assertEquals(1, host.calls.size)
        assertTrue(model.state.value.consumed)
        assertFalse(model.state.value.message.orEmpty().contains("private"))
    }
}
