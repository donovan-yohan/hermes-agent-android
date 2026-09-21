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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase B: the roster's open-or-create, driven through the real repository.
 *
 * The contract is Desktop's `createCanonicalChat` / `openBotCanonicalChat`
 * (`apps/desktop/src/plugins/hermes-bots/canonical-chat.ts:290-519` @
 * `564aef2946c436500a5e80ee117b66b789b3f99a`), which the repository mirrors:
 * adopt before minting, create titled/hidden/profile-following, write the
 * title eagerly, and adopt the exact-title winner when a concurrent writer
 * took it. Android ships no kickoff: the whole sequence below may only ever
 * contain `session.list`, `session.create` and `session.title`.
 */
class BotChatPhaseBViewModelTest {
    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

    private class ScriptedHost(
        override val endpointGeneration: MutableStateFlow<Long> = MutableStateFlow(0L),
    ) : PluginHost {
        private val answers = mutableMapOf<String, ArrayDeque<PluginHostResult>>()
        val methods = mutableListOf<String>()
        var onCall: ((String) -> Unit)? = null

        fun answer(method: String, vararg results: PluginHostResult) {
            answers[method] = ArrayDeque(results.toList())
        }

        override suspend fun request(method: String, params: JsonObject): PluginHostResult {
            methods += method
            onCall?.invoke(method)
            return answers[method]?.removeFirstOrNull() ?: error("no scripted answer for $method")
        }

        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }

    private fun sessions(body: String) = PluginHostResult.Success(Json.parseToJsonElement(body))

    private fun emptyRegistry() = sessions("""{"sessions":[]}""")

    private fun registryRow(resolvedId: String? = null) =
        sessions(
            """{"sessions":[{"id":"root"${if (resolvedId != null) ""","resolved_id":"$resolvedId"""" else ""},"title":"Bot Chat"}]}""",
        )

    private fun created(storedId: String = "created-durable", runtimeId: String = "created-runtime") =
        PluginHostResult.Success(
            Json.parseToJsonElement("""{"session_id":"$runtimeId","stored_session_id":"$storedId","messages":[]}"""),
        )

    private fun createdTitle() = PluginHostResult.Success(
        Json.parseToJsonElement("""{"pending":false,"title":"Bot Chat"}"""),
    )

    private fun pendingTitle() = PluginHostResult.Success(
        Json.parseToJsonElement("""{"pending":true,"title":"Bot Chat"}"""),
    )

    private fun titleConflict() = PluginHostResult.Refused(
        4022,
        "Title 'Bot Chat' is already in use by session winner-root",
    )

    @Test
    fun `a chatless bot is created titled and handed to Chat without a kickoff prompt`() = runTest {
        val host = ScriptedHost().apply {
            answer("session.list", emptyRegistry(), emptyRegistry())
            answer("session.create", created())
            answer("session.title", createdTitle())
        }
        val viewModel = BotsViewModel(BotsPluginRepository(host), scope())
        val row = BotRosterRow(name = "researcher")
        var opened: Pair<String, String>? = null
        var completion: ((Boolean) -> Unit)? = null

        viewModel.openBotChat(row) { profile, durableId, finished ->
            opened = profile to durableId
            completion = finished
        }
        runCurrent()

        // The created chat's durable id is what Chat is handed; the row keeps
        // its spinner until that handoff answers.
        assertEquals("researcher" to "created-durable", opened)
        assertEquals(row.rosterKey, viewModel.uiState.value.openingBotKey)
        assertEquals(
            listOf("session.list", "session.list", "session.create", "session.title"),
            host.methods,
        )
        assertTrue("no prompt may be submitted by an open", host.methods.none { it == "prompt.submit" })

        completion!!.invoke(true)
        assertNull(viewModel.uiState.value.openingBotKey)
        assertNull(viewModel.uiState.value.botChatMessage)
    }

    @Test
    fun `a refused read never creates and leaves the row actionable`() = runTest {
        val host = ScriptedHost().apply {
            answer("session.list", PluginHostResult.Refused(500, "backend prose"))
            answer("session.create", created())
            answer("session.title", createdTitle())
        }
        val viewModel = BotsViewModel(BotsPluginRepository(host), scope())
        val row = BotRosterRow(name = "researcher")
        var opens = 0

        viewModel.openBotChat(row) { _, _, _ -> opens += 1 }
        runCurrent()

        assertEquals(0, opens)
        assertEquals(listOf("session.list"), host.methods)
        assertEquals(
            "Bot Chat could not be opened. Check the Gateway and try again.",
            viewModel.uiState.value.botChatMessage,
        )
        assertNull(viewModel.uiState.value.openingBotKey)

        // The row stays actionable: the next tap reads again.
        host.answer("session.list", registryRow())
        viewModel.openBotChat(row) { _, _, finished -> opens += 1; finished(true) }
        runCurrent()
        assertEquals(1, opens)
        assertNull(viewModel.uiState.value.botChatMessage)
    }

    @Test
    fun `a simultaneous creation is adopted from the exact title winner`() = runTest {
        val host = ScriptedHost().apply {
            answer("session.list", emptyRegistry(), emptyRegistry(), registryRow(resolvedId = "winner-tip"))
            answer("session.create", created())
            answer("session.title", titleConflict())
        }
        val viewModel = BotsViewModel(BotsPluginRepository(host), scope())
        var opened: Pair<String, String>? = null

        viewModel.openBotChat(BotRosterRow(name = "researcher")) { profile, durableId, finished ->
            opened = profile to durableId
            finished(true)
        }
        runCurrent()

        // The winner's compression tip is what Chat gets, never our stray
        // lazy session's id.
        assertEquals("researcher" to "winner-tip", opened)
        assertEquals(
            listOf("session.list", "session.list", "session.create", "session.title", "session.list"),
            host.methods,
        )
    }

    @Test
    fun `a roster backed zero row answer is unconfirmed absence and creates nothing`() = runTest {
        val host = ScriptedHost().apply {
            answer("session.list", emptyRegistry())
            answer("session.create", created())
        }
        val viewModel = BotsViewModel(BotsPluginRepository(host), scope())
        val row = BotRosterRow(
            name = "researcher",
            canonicalSession = BotSessionPreview(id = "roster-tip"),
        )
        var opens = 0

        viewModel.openBotChat(row) { _, _, _ -> opens += 1 }
        runCurrent()

        assertEquals(0, opens)
        assertEquals(listOf("session.list"), host.methods)
        assertEquals(
            BotChatFailure.Unreadable.sentence(),
            viewModel.uiState.value.botChatMessage,
        )
    }

    @Test
    fun `a title that cannot be made and confirms no winner fails closed without navigating`() = runTest {
        val host = ScriptedHost().apply {
            answer("session.list", emptyRegistry(), emptyRegistry(), emptyRegistry())
            answer("session.create", created())
            answer("session.title", PluginHostResult.Refused(5007, "title rejected"))
        }
        val viewModel = BotsViewModel(BotsPluginRepository(host), scope())
        var opens = 0

        viewModel.openBotChat(BotRosterRow(name = "researcher")) { _, _, _ -> opens += 1 }
        runCurrent()

        assertEquals(0, opens)
        assertEquals(1, host.methods.count { it == "session.create" })
        assertEquals(
            "Bot Chat could not be opened. Check the Gateway and try again.",
            viewModel.uiState.value.botChatMessage,
        )
    }

    @Test
    fun `a pending title is not a durability receipt and must be confirmed by the registry`() = runTest {
        val host = ScriptedHost().apply {
            answer("session.list", emptyRegistry(), emptyRegistry(), emptyRegistry())
            answer("session.create", created())
            answer("session.title", pendingTitle())
        }
        val viewModel = BotsViewModel(BotsPluginRepository(host), scope())
        var opens = 0

        viewModel.openBotChat(BotRosterRow(name = "researcher")) { _, _, _ -> opens += 1 }
        runCurrent()

        assertEquals(0, opens)
        assertEquals(
            listOf("session.list", "session.list", "session.create", "session.title", "session.list"),
            host.methods,
        )
    }

    @Test
    fun `an endpoint switch during creation discards the result and never navigates`() = runTest {
        // Each case flips the endpoint during one call of the create sequence:
        // the create itself, the eager title, or the post-conflict registry
        // re-read. Nothing after the flip may be sent, and no chat may open on
        // the machine the app has moved to.
        val cases = listOf("session.create" to 3, "session.title" to 4, "session.list" to 5)
        for ((stopAt, expectedCalls) in cases) {
            val endpoint = MutableStateFlow(0L)
            val host = ScriptedHost(endpoint).apply {
                answer("session.list", emptyRegistry(), emptyRegistry(), registryRow(resolvedId = "winner-tip"))
                answer("session.create", created())
                answer("session.title", titleConflict())
                val seen = mutableMapOf<String, Int>()
                onCall = { method ->
                    val ordinal = seen.merge(method, 1, Int::plus)!!
                    // The title conflict is what reaches the third read, so
                    // `session.list` is matched on its third call.
                    val stop = if (method == "session.list") ordinal == 3 && stopAt == "session.list" else method == stopAt
                    if (stop) endpoint.value = 1L
                }
            }
            val viewModel = BotsViewModel(BotsPluginRepository(host), scope(), endpointGeneration = endpoint)
            var opens = 0

            viewModel.openBotChat(BotRosterRow(name = "researcher")) { _, _, _ -> opens += 1 }
            runCurrent()

            assertEquals(stopAt, 0, opens)
            assertEquals(stopAt, 0, host.methods.count { it == "prompt.submit" })
            assertNull(stopAt, viewModel.uiState.value.openingBotKey)
            assertNull(stopAt, viewModel.uiState.value.botChatMessage)
            assertEquals(stopAt, expectedCalls, host.methods.size)
        }
    }
}
