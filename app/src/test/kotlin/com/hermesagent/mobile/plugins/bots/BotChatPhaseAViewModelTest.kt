package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostEvent
import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Direct Phase-A canonical lookup/resume contract, with both boundaries gated. */
class BotChatPhaseAViewModelTest {
    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

    private class LookupHost : PluginHost {
        val lookup = CompletableDeferred<PluginHostResult>()
        var lookupCalls = 0

        /** Any creation attempt this door was asked for — Phase B's one licence. */
        var createCalls = 0

        override suspend fun request(method: String, params: JsonObject): PluginHostResult {
            return when (method) {
                "session.list" -> {
                    lookupCalls += 1
                    lookup.await()
                }

                "session.create" -> {
                    createCalls += 1
                    PluginHostResult.Success(
                        Json.parseToJsonElement("""{"session_id":"runtime","stored_session_id":"durable"}"""),
                    )
                }

                else -> PluginHostResult.Success(Json.parseToJsonElement("""{"profiles":[{"name":"researcher"}]}"""))
            }
        }

        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }

    private fun found(id: String = "canonical-id") = PluginHostResult.Success(
        Json.parseToJsonElement("""{"sessions":[{"title":"Bot Chat","resolved_id":"$id"}]}"""),
    )

    @Test
    fun `canonical lookup keeps one spinner through deferred resume and clears it on success`() = runTest {
        val host = LookupHost()
        val viewModel = BotsViewModel(BotsPluginRepository(host), scope())
        val row = BotRosterRow(name = "researcher")
        var opened: Pair<String, String>? = null
        var completion: ((Boolean) -> Unit)? = null

        viewModel.openBotChat(row) { profile, id, finished ->
            opened = profile to id
            completion = finished
        }
        runCurrent()
        assertEquals(row.rosterKey, viewModel.uiState.value.openingBotKey)

        host.lookup.complete(found())
        runCurrent()
        assertEquals("researcher" to "canonical-id", opened)
        assertEquals(row.rosterKey, viewModel.uiState.value.openingBotKey)

        completion!!.invoke(true)
        assertNull(viewModel.uiState.value.openingBotKey)
        assertNull(viewModel.uiState.value.botChatMessage)
    }

    @Test
    fun `failed resume keeps roster actionable with fixed copy and retry works`() = runTest {
        val host = LookupHost()
        val viewModel = BotsViewModel(BotsPluginRepository(host), scope())
        val row = BotRosterRow(name = "researcher")
        var opened = 0
        var completion: ((Boolean) -> Unit)? = null
        viewModel.refreshNow()
        assertEquals(listOf("researcher"), viewModel.uiState.value.sections.single().rows.map { it.name })

        viewModel.openBotChat(row) { _, _, finished -> opened += 1; completion = finished }
        runCurrent()
        host.lookup.complete(found())
        runCurrent()
        completion!!.invoke(false)

        assertNull(viewModel.uiState.value.openingBotKey)
        assertEquals("Bot Chat could not be opened. Check the Gateway and try again.", viewModel.uiState.value.botChatMessage)
        assertEquals(listOf("researcher"), viewModel.uiState.value.sections.single().rows.map { it.name })
        viewModel.openBotChat(row) { _, _, _ -> opened += 1 }
        runCurrent()
        assertEquals(2, opened)
    }

    @Test
    fun `duplicate taps and unsafe lookup outcomes never duplicate or navigate`() = runTest {
        val host = LookupHost()
        val viewModel = BotsViewModel(BotsPluginRepository(host), scope())
        val row = BotRosterRow(name = "researcher")
        var opens = 0
        var completion: ((Boolean) -> Unit)? = null

        viewModel.openBotChat(row) { _, _, finished -> opens += 1; completion = finished }
        viewModel.openBotChat(row) { _, _, _ -> opens += 1 }
        runCurrent()
        assertEquals(1, host.lookupCalls)
        host.lookup.complete(found())
        runCurrent()
        viewModel.openBotChat(row) { _, _, _ -> opens += 1 }
        assertEquals(1, opens)
        completion!!.invoke(true)

        // A zero-row answer is no longer one of these: Phase B treats a
        // confirmed-absent registry as the one licence to create, and
        // `BotChatPhaseBViewModelTest` owns that branch and its refusals.
        for ((result, expected) in listOf(
            PluginHostResult.Success(Json.parseToJsonElement("""{"sessions":[{"title":"not canonical","id":"x"}]}""")) to
                BotChatFailure.Unreadable,
            PluginHostResult.Success(Json.parseToJsonElement("""{"sessions":[]}""")) to
                BotChatFailure.Unreadable,
            PluginHostResult.Refused(500, "backend prose") to
                BotChatFailure.Refused,
            PluginHostResult.UnavailableOnGateway to
                BotChatFailure.UnavailableOnGateway,
        )) {
            // The roster says a canonical chat exists (`rosterCanonicalId` is
            // set), so the zero-row answer is unconfirmed absence: create is
            // forbidden and the row reports failure.
            val unsafeHost = LookupHost().also { it.lookup.complete(result) }
            val unsafe = BotsViewModel(BotsPluginRepository(unsafeHost), scope())
            var unsafeOpens = 0
            val rosterBacked = BotRosterRow(name = "researcher", canonicalSession = BotSessionPreview(id = "roster-tip"))
            unsafe.openBotChat(rosterBacked) { _, _, _ -> unsafeOpens += 1 }
            runCurrent()
            assertEquals(0, unsafeOpens)
            // One sentence per reason: the answer is not "check the Gateway"
            // for a build that cannot serve the method, nor for an answer this
            // app could not read.
            assertEquals(expected.sentence(), unsafe.uiState.value.botChatMessage)
            assertTrue(unsafeHost.createCalls == 0)
        }
    }

    @Test
    fun `endpoint switch clears lookup or resume and late callbacks are ignored`() = runTest {
        val endpoint = MutableStateFlow(0L)
        val host = LookupHost()
        val viewModel = BotsViewModel(BotsPluginRepository(host), scope(), endpointGeneration = endpoint)
        val row = BotRosterRow(name = "researcher")
        var opens = 0

        viewModel.openBotChat(row) { _, _, _ -> opens += 1 }
        runCurrent()
        endpoint.value = 1L
        runCurrent()
        assertNull(viewModel.uiState.value.openingBotKey)
        host.lookup.complete(found())
        runCurrent()
        assertEquals(0, opens)

        val resumeHost = LookupHost().also { it.lookup.complete(found()) }
        val resumeEndpoint = MutableStateFlow(0L)
        val resumeViewModel = BotsViewModel(BotsPluginRepository(resumeHost), scope(), endpointGeneration = resumeEndpoint)
        var completion: ((Boolean) -> Unit)? = null
        resumeViewModel.openBotChat(row) { _, _, finished -> completion = finished }
        runCurrent()
        assertTrue(completion != null)
        resumeEndpoint.value = 1L
        runCurrent()
        assertNull(resumeViewModel.uiState.value.openingBotKey)
        completion!!.invoke(false)
        assertNull(resumeViewModel.uiState.value.botChatMessage)
    }
}
