package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostEvent
import com.hermesagent.mobile.plugins.PluginHostResult
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
import org.junit.Test

/**
 * A Bot Chat start that fails must say *why*, and must stay retryable.
 *
 * The surface used to answer every failed start with one sentence — "Check the
 * Gateway and try again" — which is true only when the Gateway answered an
 * error. A build that does not serve the method, a dropped connection, and an
 * answer this app cannot read all got the same misleading advice. These cases
 * pin one sentence per reason, and pin that the roster row stays actionable
 * afterwards so a retry is preserved for every one of them.
 */
class BotsBotChatStartFailureTest {
    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

    private class ScriptedHost(
        var answer: PluginHostResult,
        override val endpointGeneration: MutableStateFlow<Long> = MutableStateFlow(0L),
    ) : PluginHost {
        val methods = mutableListOf<String>()

        override suspend fun request(method: String, params: JsonObject): PluginHostResult {
            methods += method
            return answer
        }

        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }

    private fun sessions(body: String) = PluginHostResult.Success(Json.parseToJsonElement(body))

    @Test
    fun `each start failure reports its own reason and keeps the row retryable`() = runTest {
        val cases = listOf(
            // Nothing answered. The door buckets both a missing route and a
            // timed-out exchange as code 0.
            PluginHostResult.Refused(0, "Reconnect to the Gateway and try again.")
                to BotChatFailure.NotAnswered,
            // This Gateway cannot serve the read at all.
            PluginHostResult.UnavailableOnGateway to BotChatFailure.UnavailableOnGateway,
            // The Gateway answered an error envelope of its own.
            PluginHostResult.Refused(500, "backend prose") to BotChatFailure.Refused,
            // The Gateway answered, and the answer proved nothing.
            sessions("""{"sessions":"nope"}""") to BotChatFailure.Unreadable,
        )

        for ((result, expected) in cases) {
            val host = ScriptedHost(result)
            val viewModel = BotsViewModel(BotsPluginRepository(host), scope())
            val row = BotRosterRow(name = "researcher")
            var opens = 0

            viewModel.openBotChat(row) { _, _, _ -> opens += 1 }
            runCurrent()

            assertEquals("$result", 0, opens)
            assertEquals("$result", expected.sentence(), viewModel.uiState.value.botChatMessage)
            assertNull("$result", viewModel.uiState.value.openingBotKey)

            // Retry preserved: the same tap reaches the Gateway again, and a
            // good answer now opens.
            host.answer = sessions("""{"sessions":[{"id":"canonical","title":"Bot Chat"}]}""")
            var opened: String? = null
            viewModel.openBotChat(row) { _, durableId, finished -> opened = durableId; finished(true) }
            runCurrent()
            assertEquals("$result", "canonical", opened)
        }
    }

    @Test
    fun `no two reasons share a sentence`() {
        val sentences = BotChatFailure.entries.map { it.sentence() }
        assertEquals(sentences.size, sentences.toSet().size)
    }

    @Test
    fun `the generic sentence is reserved for a Gateway that actually refused`() {
        // The copy people already know keeps its exact wording for the case it
        // was written about...
        assertEquals(
            "Bot Chat could not be opened. Check the Gateway and try again.",
            BotChatFailure.Refused.sentence(),
        )
        // ...and no other reason borrows it.
        for (other in BotChatFailure.entries - BotChatFailure.Refused) {
            assertEquals(other.name, false, other.sentence() == BotChatFailure.Refused.sentence())
        }
    }
}
