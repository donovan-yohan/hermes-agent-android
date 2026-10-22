package com.hermesagent.mobile.data.gateway

import androidx.lifecycle.viewModelScope
import com.hermesagent.mobile.data.session.*
import com.hermesagent.mobile.ui.chat.ChatViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.After

/** Public synthetic snapshots/receipts derived from session_controls.py:423-473,530-539 and
 * hermes_state_mutation_branch.py:85-97 @ 379cf1220fe289b5ecfe8bcf61cd4d5126e140cd.
 * Real ViewModel -> repository -> correlated adapter path; no model/tool/provider execution. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanonicalBranchRegressionTest {
    @After fun resetMain() { Dispatchers.resetMain() }
    @Test
    fun `menu whole branch hydrates canonical child and first prompt uses child owner`() = runTest {
        fixture("work") { peer, cache, _, viewModel, parent ->
            viewModel.branchSession(parent)
            runCurrent()
            val child = requireNotNull(viewModel.uiState.value.activeSessionId)
            assertNotEquals(parent, child)
            assertFalse(peer.calls("session.mutate").single().getValue("payload").jsonObject.containsKey("through_message_id"))
            assertTrue(cache.transcript(child).isNotEmpty())
            viewModel.setDraft("Continue whole branch")
            viewModel.submit()
            runCurrent()
            val prompt = peer.calls("prompt.submit").single()
            assertEquals(JsonPrimitive("public-child"), prompt["session_id"])
            assertEquals(JsonPrimitive("work"), prompt["profile"])
        }
    }
    @Test
    fun `viewmodel branch immediately sends and stops hydrated child on launch and named owner`() = runTest {
        for (profile in listOf(null, "work")) fixture(profile) { peer, cache, _, viewModel, parent ->
            val latest = cache.transcript(parent).filterIsInstance<AssistantTurn>().last()
            viewModel.branchFromReply(latest.id)
            runCurrent()
            val child = requireNotNull(viewModel.uiState.value.activeSessionId)
            assertNotEquals("branch must adopt child: ${viewModel.uiState.value}", parent, child)
            viewModel.setDraft("First child prompt")
            viewModel.submit()
            runCurrent()
            val submit = peer.calls("prompt.submit").singleOrNull()
            assertNotNull("first child send must work without manual reopen", submit)
            assertEquals(JsonPrimitive("public-child"), submit?.get("session_id"))
            assertEquals(profile?.let(::JsonPrimitive), submit?.get("profile"))
            assertTrue("copied child transcript must already be visible", cache.transcript(child)
                .filterIsInstance<AssistantTurn>().any { it.markdown == "Public answer 6" })
            assertEquals(profile, cache.session(child)?.remoteProfile)
            viewModel.stop()
            runCurrent()
            val stop = peer.calls("session.interrupt").single()
            assertEquals(JsonPrimitive("public-child"), stop["session_id"])
            assertEquals(JsonPrimitive(1), stop["execution_generation"])
            assertEquals(profile?.let(::JsonPrimitive), stop["profile"])
        }
    }

    @Test
    fun `paged visible selection carries saved row instead of indexing cached tail`() = runTest {
        for (projections in listOf(false, true)) fixture("work", projections) { peer, cache, repository, viewModel, parent ->
            val full = repository.fetchSessionHistory(parent)
            val visible = full.filter { it is UserTurn || it is AssistantTurn }
            val tail = visible.takeLast(4)
            cache.setTranscript(parent, tail)
            runCurrent()
            val selected = tail.filterIsInstance<AssistantTurn>().first()
            val expected = if (projections) 5L else 4L
            assertEquals(expected, selected.rowId?.value)
            viewModel.branchFromReply(selected.id)
            runCurrent()
            val branch = peer.calls("session.mutate").single { it["operation"] == JsonPrimitive("branch") }
            assertEquals("selected persisted row, regardless of tail/projections", JsonPrimitive(expected),
                branch.getValue("payload").jsonObject["through_message_id"])
        }
    }

    @Test
    fun `count-only repository caller resolves authoritative visible prefix rather than cache prefix`() = runTest {
        fixture("work") { peer, cache, repository, _, parent ->
            cache.setTranscript(parent, repository.fetchSessionHistory(parent).takeLast(4))
            repository.branchSession(parent, 4)
            val branch = peer.calls("session.mutate").single { it["operation"] == JsonPrimitive("branch") }
            assertEquals(JsonPrimitive(4), branch.getValue("payload").jsonObject["through_message_id"])
        }
    }

    @Test
    fun `canonical unsaved selected reply does not branch matching historical text`() = runTest {
        fixture("work") { peer, cache, _, viewModel, parent ->
            cache.setTranscript(parent, listOf(AssistantTurn("optimistic-public", "Public answer 4", CLOCK)))
            runCurrent()
            viewModel.branchFromReply("optimistic-public")
            runCurrent()
            assertTrue("an unsaved reply cannot name a persisted boundary", peer.calls("session.mutate").isEmpty())
            assertEquals(parent, viewModel.uiState.value.activeSessionId)
        }
    }

    @Test
    fun `duplicate visible persisted identity is refused as ambiguous`() = runTest {
        fixture("work") { peer, cache, _, viewModel, parent ->
            val target = cache.transcript(parent).filterIsInstance<AssistantTurn>().first { it.rowId?.value == 4L }
            peer.duplicateSelectedRow = true
            viewModel.branchFromReply(target.id)
            runCurrent()
            assertTrue("ambiguous authoritative visible identity must not write", peer.calls("session.mutate").isEmpty())
            assertEquals(parent, viewModel.uiState.value.activeSessionId)
        }
    }

    @Test
    fun `failed or wrong child snapshot retries exact branch identity and fences`() = runTest {
        for (wrongChild in listOf(false, true)) fixture("work") { peer, cache, repository, _, parent ->
            if (wrongChild) peer.wrongNextChildSnapshot = true else peer.failNextChildResume = true
            assertTrue(runCatching { repository.branchSession(parent, null) }.isFailure)
            assertTrue(cache.state.value.sessions.values.none { it.id != parent })
            val child = repository.branchSession(parent, null)
            val mutations = peer.calls("session.mutate")
            assertEquals(2, mutations.size)
            assertEquals("hydration failure must not mint another branch request", mutations.first(), mutations.last())
            assertTrue(cache.transcript(child).filterIsInstance<AssistantTurn>().isNotEmpty())
        }
    }

    @Test
    fun `canonical lost child receipt cannot create another child after socket replacement`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val first = BranchPeer("work", false, UnconfinedTestDispatcher(testScheduler))
        val second = BranchPeer("work", false, UnconfinedTestDispatcher(testScheduler))
        val clients = MutableStateFlow<GatewayRpcClient?>(first.rpc)
        val cache = SessionCache()
        val repository = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)), clients, backgroundScope) { CLOCK }
        try {
            val parent = repository.openSession("public-parent", "work")
            first.failNextChildResume = true
            assertTrue(runCatching { repository.branchSession(parent, null) }.isFailure)
            assertEquals(1, first.calls("session.mutate").size)
            clients.value = null
            runCurrent()
            clients.value = second.rpc
            runCurrent()
            repository.openSession(parent, "work")
            val failure = runCatching { repository.branchSession(parent, null) }.exceptionOrNull()
            assertTrue((failure as? GatewayRpcException)?.requestMayHaveBeenAccepted == true)
            assertTrue(second.calls("session.mutate").isEmpty())
        } finally { first.rpc.close(); second.rpc.close() }
    }

    @Test
    fun `endpoint switch while child snapshot parked cannot adopt or send into child`() = runTest {
        fixture("work") { peer, cache, _, viewModel, parent ->
            peer.parkChildResume = true
            viewModel.branchFromReply(cache.transcript(parent).filterIsInstance<AssistantTurn>().last().id)
            runCurrent()
            assertNotNull(peer.parkedChildResume)
            cache.resetForEndpointSwitch()
            runCurrent()
            peer.releaseChildResume()
            runCurrent()
            assertTrue(cache.state.value.sessions.values.isEmpty())
            assertEquals("parked response must not navigate away from the captured parent", parent, viewModel.uiState.value.activeSessionId)
            assertTrue(peer.calls("prompt.submit").isEmpty())
        }
    }

    @Test
    fun `reply branch resolved before endpoint switch is refused before resume or mutation`() = runTest {
        fixture("work") { peer, cache, repository, _, parent ->
            val endpoint = cache.endpointGeneration.value
            cache.resetForEndpointSwitch()
            runCurrent()
            val before = peer.frames.toList()
            assertTrue(runCatching { repository.branchSessionFromReply(parent, 4, TranscriptRowId(4), endpoint) }.isFailure)
            assertEquals(before, peer.frames)
            assertTrue(cache.state.value.sessions.isEmpty())
        }
    }

    @Test
    fun `cancelled branch cannot publish parked child snapshot`() = runTest {
        fixture("work") { peer, cache, _, viewModel, parent ->
            peer.parkChildResume = true
            viewModel.branchFromReply(cache.transcript(parent).filterIsInstance<AssistantTurn>().last().id)
            runCurrent()
            assertNotNull(peer.parkedChildResume)
            viewModel.viewModelScope.cancel()
            runCurrent()
            peer.releaseChildResume()
            runCurrent()
            assertEquals(listOf(parent), cache.state.value.sessions.values.map { it.id })
            assertEquals(parent, viewModel.uiState.value.activeSessionId)
            assertTrue(peer.calls("prompt.submit").isEmpty())
        }
    }

    private suspend fun TestScope.fixture(profile: String?, projections: Boolean = false,
        test: suspend TestScope.(BranchPeer, SessionCache, LiveGatewaySessionRepository, ChatViewModel, String) -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val peer = BranchPeer(profile, projections, UnconfinedTestDispatcher(testScheduler))
        val cache = SessionCache()
        val repository = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(peer.rpc), backgroundScope) { CLOCK }
        try {
            val parent = profile?.let { repository.openSession("public-parent", it) } ?: repository.openSession("public-parent")
            val viewModel = ChatViewModel(cache, repository, clock = { CLOCK },
                bucketLabel = SessionBucketLabel { it.relativeLabel() ?: "Synthetic month" })
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            viewModel.selectSession(parent)
            runCurrent()
            try { test(peer, cache, repository, viewModel, parent) }
            finally { collector.cancel(); viewModel.viewModelScope.cancel(); runCurrent() }
        } finally { peer.rpc.close() }
    }

    private class BranchPeer(private val profile: String?, private val projections: Boolean,
        dispatcher: CoroutineDispatcher) {
        val frames = mutableListOf<JsonObject>()
        var duplicateSelectedRow = false
        var failNextChildResume = false
        var wrongNextChildSnapshot = false
        var parkChildResume = false
        var parkedChildResume: JsonObject? = null
        fun releaseChildResume() {
            val frame = requireNotNull(parkedChildResume)
            parkedChildResume = null
            rpc.receive(buildJsonObject { put("jsonrpc", "2.0"); put("id", frame.getValue("id")); put("result", snapshot("public-child")) }.toString())
        }
        private var cut = 6L
        private val owner = "public-owner-${profile ?: "launch"}"
        val rpc: CorrelatedGatewayRpc = CorrelatedGatewayRpc(object : GatewayRpcWire {
            override fun send(text: String): Boolean {
                val frame = Json.parseToJsonElement(text).jsonObject
                frames += frame
                val method = frame["method"]?.jsonPrimitive?.content ?: return true
                val params = frame.getValue("params").jsonObject
                val sid = params["session_id"]?.jsonPrimitive?.content ?: "public-parent"
                val result = when (method) {
                    "profiles.list" -> buildJsonObject { put("profiles", JsonArray(listOf(buildJsonObject { put("name", profile ?: "default"); put("path", owner) }))); put("bot_mode_protocol", true) }
                    "runtime.describe" -> buildJsonObject { put("instance_id", "public-instance"); put("profile_id", owner)
                        put("authority_epoch", 2); put("capabilities", JsonArray(listOf("durable-admission-v1", "event-replay-v1").map(::JsonPrimitive))) }
                    "session.resume" -> {
                        if (sid == "public-child") {
                            if (failNextChildResume) { failNextChildResume = false; return false }
                            if (parkChildResume) { parkedChildResume = frame; return true }
                            if (wrongNextChildSnapshot) { wrongNextChildSnapshot = false; snapshot("public-parent") }
                            else snapshot(sid)
                        } else snapshot(sid)
                    }
                    "session.mutate" -> buildJsonObject {
                        val operation = params.getValue("operation").jsonPrimitive.content
                        if (operation == "branch") cut = params.getValue("payload").jsonObject["through_message_id"]?.jsonPrimitive?.long ?: 6L
                        put("session_id", sid); put("operation", operation); put("revision", 8)
                        if (operation == "branch") { put("branched_session_id", "public-child"); put("copied_messages", rows(true).size) }
                    }
                    "prompt.submit" -> buildJsonObject { put("admission_id", "public-child-input"); put("ref", ref(sid))
                        put("sequence", 1); put("status", "started"); put("outcome", JsonNull); put("authority_epoch", 2); put("execution_generation", 1) }
                    "session.interrupt" -> buildJsonObject { put("ref", ref(sid)); put("instance_id", "public-instance")
                        put("authority_epoch", 2); put("revision", 0); put("execution_generation", 1); put("execution_state", "idle") }
                    "session.list" -> buildJsonObject { put("sessions", JsonArray(emptyList())); put("scope", "stored") }
                    "model.options" -> buildJsonObject { put("options", JsonArray(emptyList())) }
                    "config.get" -> buildJsonObject { put("value", JsonNull) }
                    else -> JsonObject(emptyMap())
                }
                rpc.receive(buildJsonObject { put("jsonrpc", "2.0"); put("id", frame.getValue("id")); put("result", result) }.toString())
                return true
            }
            override fun close() = Unit
        }, eventPumpDispatcher = dispatcher)
        init { rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""") }
        fun calls(method: String) = frames.filter { it["method"] == JsonPrimitive(method) }.map { it.getValue("params").jsonObject }
        private fun ref(sid: String) = buildJsonObject { put("profile_id", owner); put("session_id", sid) }
        private fun rows(child: Boolean): List<JsonObject> {
            val ids = if (projections) listOf(1L, 2L, 4L, 5L, 7L, 8L) else (1L..6L).toList()
            val result = ids.mapIndexedNotNull { index, id ->
                if (child && id > cut) return@mapIndexedNotNull null
                buildJsonObject { put("row_id", if (child) id + 100 else id)
                    put("role", if (index % 2 == 0) "user" else "assistant")
                    put("text", if (index % 2 == 0) "Public user ${index + 1}" else "Public answer ${index + 1}")
                    if (projections && index == 1) put("reasoning", "Synthetic reasoning") }
            }.toMutableList()
            if (projections) result.add(2, buildJsonObject { put("role", "tool"); put("name", "read_file"); put("text", "Public tool projection") })
            if (duplicateSelectedRow && !child) result.add(4, requireNotNull(result.find { it["row_id"] == JsonPrimitive(4) }))
            return result
        }
        private fun snapshot(sid: String) = buildJsonObject {
            val child = sid == "public-child"; val messages = JsonArray(rows(child))
            put("session_id", sid); put("stored_session_id", sid)
            put("messages", JsonArray(rows(child).map { row -> JsonObject(row - "text" + ("content" to row.getValue("text"))) }))
            put("display_messages", messages)
            put("message_count", messages.size); put("running", false); put("authority_epoch", 2)
            put("replay_epoch", "public-epoch-$sid"); put("last_sequence", 0); put("subscription_id", "public-sub-$sid")
            put("revision", if (child) 0 else 7); put("execution_generation", if (child) 0 else 3)
            put("pending", JsonArray(emptyList())); put("prompts", JsonArray(emptyList()))
            put("info", buildJsonObject { put("desktop_protocol", "hermes-gateway-v1"); put("profile_id", owner)
                profile?.let { put("profile_name", it) }; put("model", "public-model"); put("cwd", "/workspace/public") })
        }
    }
    companion object { private const val CLOCK = 1800000000000L }
}
