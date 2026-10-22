package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Canonical serializers/dispatch: gateway/session_controls.py:345-356,420-473 and tui_gateway/contracts/canonical_sessions.py:17-51,120-168,219-269 @ 379cf1220fe289b5ecfe8bcf61cd4d5126e140cd. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanonicalGatewayRpcTest {
    @Test
    fun `authority advertisement selects gui create with retained identity and frozen policy options`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler))
        peer.ready()
        val result = peer.rpc.request("session.create", buildJsonObject {
            put("source", "desktop"); put("profile", "work"); put("cwd", "/workspace/public")
            put("model", "public-model"); put("provider", "public-provider")
            put("reasoning_effort", "high"); put("fast", false)
        }).jsonObject
        val create = peer.frames.single { it["method"] == JsonPrimitive("session.create") }.getValue("params").jsonObject
        assertEquals(JsonPrimitive("gui"), create["source"])
        assertTrue(create.getValue("request_id").jsonPrimitive.content.isNotEmpty())
        assertEquals(JsonPrimitive("high"), create["reasoning"])
        assertEquals(JsonPrimitive("normal"), create["service_tier"])
        assertFalse("reasoning_effort" in create)
        assertFalse("fast" in create)
        val storedKey = result.getValue("stored_session_id").jsonPrimitive.content
        peer.rpc.request("session.resume", buildJsonObject { put("session_id", storedKey) })
        assertEquals(JsonPrimitive("canonical-public"), peer.frames.last().getValue("params").jsonObject["session_id"])
        assertEquals(JsonPrimitive("work"), peer.frames.last().getValue("params").jsonObject["profile"])
        peer.rpc.close()
    }

    @Test
    fun `warm activate and legacy history use canonical display snapshot without raw history confusion`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler))
        peer.ready()
        val params = buildJsonObject { put("session_id", "canonical-public"); put("profile", "work") }
        peer.rpc.request("session.activate", params)
        val history = peer.rpc.request("session.history", params).jsonObject
        assertFalse(peer.frames.any { it["method"] in listOf(JsonPrimitive("session.activate"), JsonPrimitive("session.history")) })
        val resumes = peer.frames.filter { it["method"] == JsonPrimitive("session.resume") }
        assertTrue(resumes.isNotEmpty())
        assertTrue(resumes.all { it.getValue("params").jsonObject["display_projection"] == JsonPrimitive(true) })
        assertEquals("Display public answer", history.getValue("messages").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        peer.rpc.close()
    }

    @Test
    fun `canonical stored list and exact generation control preserve owning profile`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler))
        peer.ready()
        peer.rpc.request("session.list", buildJsonObject { put("profile", "work"); put("limit", 100) })
        assertEquals(JsonPrimitive("stored"), peer.frames.last().getValue("params").jsonObject["scope"])
        peer.rpc.request("session.resume", buildJsonObject { put("profile", "work"); put("session_id", "canonical-public") })
        peer.rpc.request("session.interrupt", buildJsonObject { put("profile", "work"); put("session_id", "canonical-public") })
        assertEquals(JsonPrimitive(3), peer.frames.last().getValue("params").jsonObject["execution_generation"])
        assertEquals(JsonPrimitive("work"), peer.frames.last().getValue("params").jsonObject["profile"])
        peer.rpc.close()
    }

    @Test
    fun `stock nonlocal snapshot confirms missing owner through same scoped session info`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        peer.snapshotOwner = null
        peer.confirmationOwner = "public-owner"
        val result = peer.rpc.request("session.resume", buildJsonObject {
            put("profile", "work"); put("session_id", "canonical-public")
        }).jsonObject
        assertEquals(JsonPrimitive("public-owner"), result.getValue("info").jsonObject["profile_id"])
        val confirmation = peer.frames.single { it["method"] == JsonPrimitive("session.info") }.getValue("params").jsonObject
        assertEquals(JsonPrimitive("work"), confirmation["profile"])
        assertEquals(JsonPrimitive("canonical-public"), confirmation["session_id"])
        peer.rpc.close()
    }

    @Test
    fun `scoped roster cannot adopt snapshot without exact serving owner identity`() = runTest {
        for (reportedOwner in listOf<String?>(null, "public-other-owner")) {
            val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
            peer.snapshotOwner = reportedOwner
            assertTrue(runCatching { peer.rpc.request("session.resume", buildJsonObject {
                put("profile", "work"); put("session_id", "canonical-public")
            }) }.exceptionOrNull() is GatewayRpcException)
            assertTrue(runCatching { peer.rpc.request("prompt.submit", buildJsonObject {
                put("profile", "work"); put("session_id", "canonical-public"); put("text", "Public prompt")
            }) }.exceptionOrNull() is GatewayRpcException)
            assertTrue(peer.frames.none { it["method"] == JsonPrimitive("prompt.submit") })
            peer.rpc.close()
        }
    }

    @Test
    fun `owner key survives reconnect only after exact endpoint and owner are reverified`() = runTest {
        val first = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        val key = first.rpc.request("session.resume", buildJsonObject { put("session_id", "canonical-public"); put("profile", "work") })
            .jsonObject.getValue("session_id").jsonPrimitive.content
        first.rpc.close()
        val second = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        val resumed = second.rpc.request("session.resume", buildJsonObject { put("session_id", key); put("profile", "work") })
        assertEquals(JsonPrimitive(key), resumed.jsonObject["session_id"])
        assertEquals(JsonPrimitive("canonical-public"), second.frames.last().getValue("params").jsonObject["session_id"])
        second.rpc.close()
        val foreign = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        foreign.rpc.bindSessionEndpoint("another public endpoint")
        assertTrue(runCatching { foreign.rpc.request("session.resume", buildJsonObject { put("session_id", key); put("profile", "work") }) }.exceptionOrNull() is GatewayRpcException)
        assertTrue(foreign.frames.none { it["method"] == JsonPrimitive("session.resume") })
        foreign.rpc.close()
    }

    @Test
    fun `anonymous canonical producer gets fresh identified admissions and accepted backend generation`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        val key = peer.rpc.request("session.resume", buildJsonObject { put("session_id", "canonical-public"); put("profile", "work") })
            .jsonObject.getValue("session_id").jsonPrimitive.content
        val submit = buildJsonObject { put("session_id", key); put("text", "Public prompt") }
        val first = peer.rpc.request("prompt.submit", submit).jsonObject
        val second = peer.rpc.request("prompt.submit", JsonObject(submit + ("queued" to JsonPrimitive(true)))).jsonObject
        val calls = peer.frames.filter { it["method"] == JsonPrimitive("prompt.submit") }.map { it.getValue("params").jsonObject }
        assertTrue(calls.all { it.getValue("submission_id").jsonPrimitive.content.isNotBlank() })
        assertNotEquals(calls.first()["submission_id"], calls.last()["submission_id"])
        assertEquals(JsonPrimitive("canonical-public"), calls.first()["session_id"])
        assertEquals(JsonPrimitive("work"), calls.first()["profile"])
        assertEquals(JsonPrimitive("started"), first["status"])
        assertEquals(JsonPrimitive("queued"), second["status"])
        peer.rpc.request("session.interrupt", buildJsonObject { put("session_id", key) })
        assertEquals(JsonPrimitive(4), peer.frames.last().getValue("params").jsonObject["execution_generation"])
        peer.rpc.close()
    }

    @Test
    fun `identified retry after lost receipt sends exact same logical input identity`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        val key = peer.rpc.request("session.resume", buildJsonObject { put("session_id", "canonical-public"); put("profile", "work") })
            .jsonObject.getValue("session_id").jsonPrimitive.content
        val submit = buildJsonObject { put("session_id", key); put("text", "Public prompt"); put("submission_id", "public-send-1") }
        peer.silenceNextSubmit = true
        assertTrue(runCatching { peer.rpc.request("prompt.submit", submit) }.isFailure)
        val receipt = peer.rpc.request("prompt.submit", submit).jsonObject
        val calls = peer.frames.filter { it["method"] == JsonPrimitive("prompt.submit") }.map { it.getValue("params").jsonObject }
        assertEquals(2, calls.size)
        assertEquals(calls.first(), calls.last())
        assertEquals(JsonPrimitive("public-admission-1"), receipt["admission_id"])
        peer.rpc.close()
    }

    @Test
    fun `canonical edits carry revision and generation with exact ambiguous retry`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        peer.rpc.request("session.resume", buildJsonObject { put("session_id", "canonical-public"); put("profile", "work") })
        val rename = buildJsonObject { put("session_id", "canonical-public"); put("profile", "work"); put("title", "Public title") }
        peer.silenceNextMutation = true
        assertTrue(runCatching { peer.rpc.request("session.title", rename) }.isFailure)
        val result = peer.rpc.request("session.title", rename).jsonObject
        val calls = peer.frames.filter { it["method"] == JsonPrimitive("session.mutate") }.map { it.getValue("params").jsonObject }
        assertEquals(2, calls.size)
        assertEquals(calls.first(), calls.last())
        assertEquals(JsonPrimitive(7), calls.first()["expected_revision"])
        assertEquals(JsonPrimitive("rename"), calls.first()["operation"])
        assertEquals(JsonPrimitive("Public title"), result["title"])
        peer.rpc.request("config.set", buildJsonObject { put("session_id", "canonical-public"); put("profile", "work"); put("key", "reasoning"); put("value", "high") })
        val reasoning = peer.frames.last().getValue("params").jsonObject
        assertEquals(JsonPrimitive(8), reasoning["expected_revision"])
        assertEquals(JsonPrimitive(3), reasoning["expected_generation"])
        assertEquals(JsonPrimitive("high"), reasoning.getValue("payload").jsonObject["effort"])
        peer.rpc.close()
    }

    @Test
    fun `canonical message branch requires saved row and qualifies child`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        peer.rpc.request("session.resume", buildJsonObject { put("session_id", "canonical-public"); put("profile", "work") })
        val base = buildJsonObject { put("session_id", "canonical-public"); put("profile", "work"); put("count", 2) }
        assertTrue(runCatching { peer.rpc.request("session.branch", base) }.isFailure)
        assertTrue(peer.frames.none { it["method"] == JsonPrimitive("session.mutate") })
        val result = peer.rpc.request("session.branch", JsonObject(base + ("through_message_id" to JsonPrimitive(42)))).jsonObject
        assertEquals(JsonPrimitive(42), peer.frames.last { it["method"] == JsonPrimitive("session.mutate") }.getValue("params").jsonObject.getValue("payload").jsonObject["through_message_id"])
        assertNotEquals(JsonPrimitive("public-child"), result["session_id"])
        assertEquals(result["session_id"], result["stored_session_id"])
        peer.rpc.close()
    }

    @Test
    fun `canonical edit rewinds durable row before identified plain submit and refuses unsaved cut`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        peer.rpc.request("session.resume", buildJsonObject { put("session_id", "canonical-public"); put("profile", "work") })
        val base = buildJsonObject { put("session_id", "canonical-public"); put("profile", "work"); put("text", "Edited public prompt"); put("submission_id", "public-edit") }
        assertTrue(runCatching { peer.rpc.request("prompt.submit", JsonObject(base + ("truncate_before_user_ordinal" to JsonPrimitive(1)))) }.isFailure)
        assertTrue(peer.frames.none { it["method"] == JsonPrimitive("prompt.submit") })
        peer.rpc.request("prompt.submit", JsonObject(base + mapOf("truncate_before_row_id" to JsonPrimitive(42), "confirm_truncate" to JsonPrimitive(true))))
        assertEquals(listOf("session.mutate", "prompt.submit"), peer.frames.takeLast(2).map { it.getValue("method").jsonPrimitive.content })
        assertEquals(JsonPrimitive("rewind"), peer.frames[peer.frames.lastIndex - 1].getValue("params").jsonObject["operation"])
        assertFalse("truncate_before_row_id" in peer.frames.last().getValue("params").jsonObject)
        peer.rpc.close()
    }

    @Test
    fun `guarded model returns confirmation then only explicit consent presents token`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        peer.rpc.request("session.resume", buildJsonObject { put("session_id", "canonical-public"); put("profile", "work") })
        val pick = buildJsonObject { put("session_id", "canonical-public"); put("profile", "work"); put("key", "model"); put("value", "public-model --provider public-provider --session") }
        val first = peer.rpc.request("config.set", pick).jsonObject
        assertEquals(JsonPrimitive(true), first["confirm_required"])
        peer.rpc.request("config.set", pick)
        assertFalse("confirm" in peer.frames.last().getValue("params").jsonObject.getValue("payload").jsonObject)
        peer.rpc.request("config.set", JsonObject(pick + ("confirm_expensive_model" to JsonPrimitive(true))))
        assertEquals(JsonPrimitive("public-confirmation"), peer.frames.last().getValue("params").jsonObject.getValue("payload").jsonObject["confirm"])
        peer.rpc.close()
    }

    @Test
    fun `canonical image upload is staging only and image-only admission carries returned path and mime`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        peer.requireAttachments = true
        val cache = com.hermesagent.mobile.data.session.SessionCache()
        val repository = LiveGatewaySessionRepository(cache,
            kotlinx.coroutines.flow.MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            kotlinx.coroutines.flow.MutableStateFlow<GatewayRpcClient?>(peer.rpc), backgroundScope) { 1800000000000 }
        val key = repository.openSession("canonical-public")
        assertEquals(GatewaySubmitOutcome.Accepted, repository.submit(key, "", false, listOf(
            com.hermesagent.mobile.data.attachments.OutgoingAttachment.Image("public.png", "iVBORw0KGgo="))))
        val submit = peer.frames.single { it["method"] == JsonPrimitive("prompt.submit") }.getValue("params").jsonObject
        assertEquals(JsonArray(listOf(buildJsonObject { put("path", "/workspace/public/upload.png"); put("mime", "image/png") })), submit["attachments"])
        assertTrue(submit.getValue("submission_id").jsonPrimitive.content.isNotBlank())
        assertEquals(JsonPrimitive("What do you see in this image?"), submit["text"])
        assertFalse(peer.frames.any { it["method"] == JsonPrimitive("image.detach") })
        peer.rpc.close()
    }

    @Test
    fun `canonical regenerate reacquires authenticated image then rewinds and admits fresh stage`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        peer.requireAttachments = true
        val downloads = mutableListOf<GatewayHttpRequest>()
        val image = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val http = object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
                if (request.path != "api/fs/download") return GatewayHttpResult.Rejected(404, "Unavailable")
                downloads += request
                return GatewayHttpResult.Success(200, image)
            }
        }
        val cache = com.hermesagent.mobile.data.session.SessionCache()
        val repository = LiveGatewaySessionRepository(cache,
            kotlinx.coroutines.flow.MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            kotlinx.coroutines.flow.MutableStateFlow<GatewayRpcClient?>(peer.rpc), backgroundScope,
            http = { http }, restContext = UnconfinedTestDispatcher(testScheduler)) { 1800000000000 }
        val key = repository.openSession("canonical-public")
        assertEquals(GatewaySubmitOutcome.Accepted, repository.regenerate(key,
            "Inspect public image\n@image:/workspace/public/old.png", com.hermesagent.mobile.data.session.TranscriptRowId(42), "public-user", cache.endpointGeneration.value))
        assertEquals("/workspace/public/old.png", downloads.single().query["path"])
        assertEquals("work", downloads.single().query["profile"])
        assertEquals("canonical-public", downloads.single().query["session_id"])
        assertTrue(image.all { it == 0.toByte() })
        assertFalse(peer.frames.any { it["method"] in setOf(JsonPrimitive("image.attach"), JsonPrimitive("image.detach")) })
        val operations = peer.frames.map { it.getValue("method").jsonPrimitive.content }.filter { it in setOf("image.attach_bytes", "session.mutate", "prompt.submit") }
        assertEquals(listOf("image.attach_bytes", "session.mutate", "prompt.submit"), operations)
        assertEquals(JsonPrimitive("/workspace/public/upload.png"), peer.frames.last().getValue("params").jsonObject.getValue("attachments").jsonArray.single().jsonObject["path"])
        peer.rpc.close()
    }

    @Test
    fun `snapshot lost admission keeps its own generation and queued cancellation never replays input`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        peer.snapshotGeneration = 9
        peer.pendingRows = JsonArray(listOf(
            buildJsonObject { put("admission_id", "public-lost"); put("input_id", "public-input-lost"); put("status", "unknown"); put("execution_generation", 4); put("text", "Lost public turn")
                put("ref", buildJsonObject { put("profile_id", "public-owner"); put("session_id", "canonical-public") }); put("sequence", 1); put("authority_epoch", 2); put("outcome", JsonNull) },
            buildJsonObject { put("admission_id", "public-next"); put("input_id", "public-input-next"); put("status", "queued"); put("execution_generation", JsonNull); put("text", "Next public turn")
                put("ref", buildJsonObject { put("profile_id", "public-owner"); put("session_id", "canonical-public") }); put("sequence", 2); put("authority_epoch", 2); put("outcome", JsonNull) }))
        val cache = com.hermesagent.mobile.data.session.SessionCache()
        val repository = LiveGatewaySessionRepository(cache,
            kotlinx.coroutines.flow.MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            kotlinx.coroutines.flow.MutableStateFlow<GatewayRpcClient?>(peer.rpc), backgroundScope) { 1800000000000 }
        val key = repository.openSession("canonical-public")
        val rows = cache.session(key)?.composerStatus?.gatewayAdmissions.orEmpty()
        assertEquals(listOf(GatewayAdmissionStatus.Unknown, GatewayAdmissionStatus.Queued), rows.map { it.key.status })
        assertTrue(peer.frames.none { it["method"] == JsonPrimitive("prompt.submit") })
        assertEquals(PendingInputResponse.Resolved, repository.resolveGatewayAdmission(rows.first().key))
        val discard = peer.frames.last().getValue("params").jsonObject
        assertEquals(JsonPrimitive("public-lost"), discard["admission_id"])
        assertEquals(JsonPrimitive(4), discard["execution_generation"])
        val before = peer.frames.size
        repository.resolveGatewayAdmission(rows.first().key)
        assertEquals(before, peer.frames.size)
        assertEquals(PendingInputResponse.Resolved, repository.resolveGatewayAdmission(rows.last().key))
        assertEquals(JsonPrimitive("prompt.cancel"), peer.frames.last()["method"])
        assertTrue(cache.session(key)?.composerStatus?.gatewayAdmissions.orEmpty().isEmpty())
        assertTrue(peer.frames.none { it["method"] == JsonPrimitive("prompt.submit") })
        peer.rpc.close()
    }

    @Test
    fun `receipt queued unknown and terminal states reconcile without pretending every admission started`() = runTest {
        for (status in listOf("queued", "unknown", "terminal")) {
            val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
            peer.submitStatus = status
            val cache = com.hermesagent.mobile.data.session.SessionCache()
            val repository = LiveGatewaySessionRepository(cache,
                kotlinx.coroutines.flow.MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
                kotlinx.coroutines.flow.MutableStateFlow<GatewayRpcClient?>(peer.rpc), backgroundScope) { 1800000000000 }
            val key = repository.openSession("canonical-public")
            val result = repository.submitIdentified(key, "Public $status input", queued = status == "queued", submissionId = "public-$status")
            assertEquals(if (status == "unknown") GatewaySubmitOutcome.Ambiguous else GatewaySubmitOutcome.Accepted, result)
            val row = cache.session(key)!!
            val admissions = row.composerStatus?.gatewayAdmissions.orEmpty()
            assertEquals(if (status == "terminal") 0 else 1, admissions.size)
            if (status != "terminal") assertEquals(if (status == "queued") GatewayAdmissionStatus.Queued else GatewayAdmissionStatus.Unknown, admissions.single().key.status)
            assertEquals(com.hermesagent.mobile.data.session.SessionStatus.Idle, row.status)
            assertEquals(1, peer.frames.count { it["method"] == JsonPrimitive("prompt.submit") })
            peer.rpc.close()
        }
    }

    @Test
    fun `Bots hidden chat accepts committed canonical title through actual PluginHost without redundant registry read`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        peer.refuseRegistryAfterCreate = true
        val host = com.hermesagent.mobile.plugins.GatewayPluginHost(backgroundScope,
            kotlinx.coroutines.flow.MutableStateFlow<GatewayRpcClient?>(peer.rpc))
        val opened = com.hermesagent.mobile.plugins.bots.BotsPluginRepository(host).openCanonicalChat("work", null)
        val expected = peer.rpc.authoritySessionIdentity!!.qualify("canonical-public", "work")
        assertEquals(com.hermesagent.mobile.plugins.bots.BotChatOpen.Opened(expected), opened)
        val create = peer.frames.single { it["method"] == JsonPrimitive("session.create") }.getValue("params").jsonObject
        assertEquals(JsonPrimitive("gui"), create["source"])
        assertEquals(JsonPrimitive("Bot Chat"), create["title"])
        assertEquals(JsonPrimitive(true), create["hidden"])
        assertEquals(JsonPrimitive(true), create["follow_profile_config"])
        assertEquals(2, peer.frames.count { it["method"] == JsonPrimitive("session.list") })
        assertTrue(peer.frames.none { it["method"] == JsonPrimitive("prompt.submit") })
        assertEquals(JsonPrimitive("rename"), peer.frames.last().getValue("params").jsonObject["operation"])
        peer.rpc.close()
    }

    @Test
    fun `repository goal read uses canonical raw session id and retained owning profile without slash`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        val cache = com.hermesagent.mobile.data.session.SessionCache()
        val repository = LiveGatewaySessionRepository(cache,
            kotlinx.coroutines.flow.MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            kotlinx.coroutines.flow.MutableStateFlow<GatewayRpcClient?>(peer.rpc), backgroundScope) { 1800000000000 }
        val key = repository.openSession("canonical-public", "work")
        assertNotEquals("canonical-public", key)
        assertEquals(GatewayGoalStatusOutcome.Available(null), repository.goalStatus(key))
        val read = peer.frames.single { it["method"] == JsonPrimitive("session.control.read") }.getValue("params").jsonObject
        assertEquals(buildJsonObject { put("session_id", "canonical-public"); put("profile", "work") }, read)
        assertFalse(peer.frames.any { it["method"] == JsonPrimitive("slash.exec") })
        peer.rpc.close()
    }

    @Test
    fun `consumed canonical voice stop is not an ambiguous admission or phantom running message`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        peer.submitResultOverride = buildJsonObject { put("voice_stopped", true) }
        val cache = com.hermesagent.mobile.data.session.SessionCache()
        val repository = LiveGatewaySessionRepository(cache,
            kotlinx.coroutines.flow.MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            kotlinx.coroutines.flow.MutableStateFlow<GatewayRpcClient?>(peer.rpc), backgroundScope) { 1800000000000 }
        val key = repository.openSession("canonical-public", "work")
        val before = cache.transcript(key)
        assertEquals(GatewaySubmitOutcome.VoiceStopped, repository.submit(key, "stop"))
        assertEquals(before, cache.transcript(key))
        assertEquals(com.hermesagent.mobile.data.session.SessionStatus.Idle, cache.session(key)?.status)
        assertTrue(repository.activeTurns.value.isEmpty())
        assertEquals(1, peer.frames.count { it["method"] == JsonPrimitive("prompt.submit") })
        peer.rpc.close()
    }

    @Test
    fun `voice stop rollback preserves newer canonical admission and metadata`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        peer.submitResultOverride = buildJsonObject { put("voice_stopped", true) }
        val cache = com.hermesagent.mobile.data.session.SessionCache()
        val repository = LiveGatewaySessionRepository(cache,
            kotlinx.coroutines.flow.MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            kotlinx.coroutines.flow.MutableStateFlow<GatewayRpcClient?>(peer.rpc), backgroundScope) { 1800000000000 }
        val key = repository.openSession("canonical-public", "work")
        val before = cache.transcript(key)
        peer.beforeSubmitReply = {
            peer.rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"session.info","ref":{"profile_id":"public-owner","session_id":"canonical-public"},"session_id":"canonical-public","profile_id":"public-owner","replay_epoch":"public-session-epoch","seq":1,"execution_generation":3,"authority_epoch":2,"payload":{"running":false,"revision":8,"cwd":"/workspace/newer","pending":[{"admission_id":"other-client","input_id":"other-input","status":"queued","text":"Keep queued","sequence":2,"authority_epoch":2,"execution_generation":null,"ref":{"profile_id":"public-owner","session_id":"canonical-public"}}]}}}""")
        }
        val send = async { repository.submit(key, "stop") }
        runCurrent()
        assertEquals(GatewaySubmitOutcome.VoiceStopped, send.await())
        assertEquals(before, cache.transcript(key))
        assertEquals("other-client", cache.session(key)?.composerStatus?.gatewayAdmissions?.single()?.key?.admissionId)
        assertEquals("/workspace/newer", cache.session(key)?.worktreePath)
        assertTrue(repository.activeTurns.value.isEmpty())
        peer.rpc.close()
    }

    @Test
    fun `voice stop receipt must be exact literal boolean and never hides unusable admission`() = runTest {
        for (bad in listOf(buildJsonObject { put("voice_stopped", "true") },
            buildJsonObject { put("voice_stopped", true); put("admission_id", "unusable") })) {
            val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
            peer.submitResultOverride = bad
            val key = peer.rpc.request("session.resume", buildJsonObject { put("session_id", "canonical-public"); put("profile", "work") })
                .jsonObject.getValue("session_id").jsonPrimitive.content
            val failure = runCatching { peer.rpc.request("prompt.submit", buildJsonObject {
                put("session_id", key); put("text", "stop")
            }) }.exceptionOrNull()
            assertTrue(failure is GatewayRpcException && failure.requestMayHaveBeenAccepted)
            peer.rpc.close()
        }
    }

    @Test
    fun `authoritative pending return from transfer restores queued admission and cancellation`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        fun pending(status: String, steering: Boolean) = JsonArray(listOf(buildJsonObject {
            put("admission_id", "public-next"); put("input_id", "public-input-next")
            put("status", status); put("steering", steering); put("execution_generation", JsonNull)
            put("text", "Next public turn"); put("sequence", 2); put("authority_epoch", 2)
            put("ref", buildJsonObject { put("profile_id", "public-owner"); put("session_id", "canonical-public") })
        }))
        peer.pendingRows = pending("queued", false)
        val key = peer.rpc.request("session.resume", buildJsonObject {
            put("session_id", "canonical-public"); put("profile", "work")
        }).jsonObject.getValue("session_id").jsonPrimitive.content
        peer.pendingRows = pending("started", true)
        peer.rpc.request("session.resume", buildJsonObject { put("session_id", key) })
        peer.pendingRows = pending("queued", false)
        val restored = peer.rpc.request("session.resume", buildJsonObject { put("session_id", key) }).jsonObject
        assertEquals(JsonPrimitive("queued"), restored.getValue("pending").jsonArray.single().jsonObject["status"])
        peer.rpc.request("prompt.cancel", buildJsonObject { put("session_id", key); put("admission_id", "public-next") })
        peer.pendingRows = pending("queued", false)
        val after = peer.rpc.request("session.resume", buildJsonObject { put("session_id", key) }).jsonObject
        assertTrue(after.getValue("pending").jsonArray.isEmpty())
        peer.rpc.close()
    }

    @Test
    fun `real peer deferred receipt reaches repository as next turn intent not applied`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready(); it.deferModels = true }
        val cache = com.hermesagent.mobile.data.session.SessionCache()
        val repository = LiveGatewaySessionRepository(cache,
            kotlinx.coroutines.flow.MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            kotlinx.coroutines.flow.MutableStateFlow<GatewayRpcClient?>(peer.rpc), backgroundScope) { 1800000000000 }
        val key = repository.openSession("canonical-public", "work")
        assertEquals(com.hermesagent.mobile.data.composer.ControlMutationResult.Deferred,
            repository.setLiveModel(key, com.hermesagent.mobile.data.composer.ComposerModelSelection("public-next", "public-provider")))
        assertEquals(1, peer.frames.count { it["method"] == JsonPrimitive("session.mutate") })
        peer.rpc.close()
    }

    @Test
    fun `deferred event confirmation uses exact whole payload and token once on its owner`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        val cache = com.hermesagent.mobile.data.session.SessionCache()
        val repository = LiveGatewaySessionRepository(cache,
            kotlinx.coroutines.flow.MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            kotlinx.coroutines.flow.MutableStateFlow<GatewayRpcClient?>(peer.rpc), backgroundScope) { 1800000000000 }
        val key = repository.openSession("canonical-public", "work")
        val events = mutableListOf<com.hermesagent.mobile.data.composer.DeferredModelPick>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.deferredModelPicks.collect { events += it } }
        runCurrent()
        val whole = buildJsonObject { put("model", "public-guarded"); put("provider", "public-provider"); put("reasoning", "high") }
        peer.modelEvent(1, whole)
        runCurrent()
        assertEquals(1, events.size)
        assertEquals(key, events.single().durableId)
        assertTrue(peer.frames.none { it["method"] == JsonPrimitive("session.mutate") })
        val forged = buildJsonObject { put("session_id", key); put("payload", whole); put("confirm", "wrong-token") }
        assertTrue(runCatching { peer.rpc.request("session.model", forged) }.isFailure)
        val partial = buildJsonObject { put("session_id", key); put("payload", JsonObject(whole - "reasoning")); put("confirm", "public-event-token") }
        assertTrue(runCatching { peer.rpc.request("session.model", partial) }.isFailure)
        assertEquals(com.hermesagent.mobile.data.composer.ControlMutationResult.Applied, events.single().confirm!!.invoke())
        val mutation = peer.frames.last { it["method"] == JsonPrimitive("session.mutate") }.getValue("params").jsonObject
        assertEquals(JsonPrimitive("work"), mutation["profile"])
        assertEquals(JsonPrimitive("canonical-public"), mutation["session_id"])
        assertEquals(JsonObject(whole + ("confirm" to JsonPrimitive("public-event-token"))), mutation["payload"])
        assertEquals(JsonPrimitive(3), mutation["expected_generation"])
        assertTrue(events.single().confirm!!.invoke() is com.hermesagent.mobile.data.composer.ControlMutationResult.Rejected)
        assertEquals(1, peer.frames.count { it["method"] == JsonPrimitive("session.mutate") })
        collector.cancel(); peer.rpc.close()
    }

    @Test
    fun `older foreign and superseded deferred asks cannot grant consent and refusal revokes it`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready(); it.deferModels = true }
        peer.rpc.request("session.resume", buildJsonObject { put("session_id", "canonical-public"); put("profile", "work") })
        val whole = buildJsonObject { put("model", "public-newer"); put("provider", "public-provider") }
        peer.rpc.request("config.set", buildJsonObject { put("session_id", "canonical-public"); put("profile", "work")
            put("key", "model"); put("value", "public-newer --provider public-provider --session") })
        val confirm = buildJsonObject { put("session_id", "canonical-public"); put("profile", "work"); put("payload", whole); put("confirm", "public-event-token") }
        peer.modelEvent(1, whole, generation = 2)
        assertTrue(runCatching { peer.rpc.request("session.model", confirm) }.isFailure)
        peer.modelEvent(1, whole, sid = "foreign-session")
        assertTrue(runCatching { peer.rpc.request("session.model", confirm) }.isFailure)
        peer.modelEvent(1, whole, owner = "public-foreign-owner")
        assertTrue(runCatching { peer.rpc.request("session.model", confirm) }.isFailure)
        peer.modelEvent(2, buildJsonObject { put("model", "public-older"); put("provider", "public-provider") })
        assertTrue(runCatching { peer.rpc.request("session.model", confirm) }.isFailure)
        peer.modelEvent(3, whole)
        peer.modelEvent(4, whole, status = "refused")
        assertTrue(runCatching { peer.rpc.request("session.model", confirm) }.isFailure)
        assertEquals(1, peer.frames.count { it["method"] == JsonPrimitive("session.mutate") })
        peer.rpc.close()
    }

    @Test
    fun `new execution revokes event token and duplicate ask does not revive it`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        peer.rpc.request("session.resume", buildJsonObject { put("session_id", "canonical-public"); put("profile", "work") })
        val pick = buildJsonObject { put("model", "public-guarded"); put("provider", "public-provider") }
        peer.modelEvent(1, pick)
        peer.rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"session.info","session_id":"canonical-public","seq":2,"replay_epoch":"public-session-epoch","execution_generation":4,"payload":{"model":"public-model","running":true}}}""")
        peer.modelEvent(1, pick)
        assertTrue(runCatching { peer.rpc.request("session.model", buildJsonObject {
            put("session_id", "canonical-public"); put("profile", "work"); put("payload", pick); put("confirm", "public-event-token")
        }) }.isFailure)
        assertTrue(peer.frames.none { it["method"] == JsonPrimitive("session.mutate") })
        peer.rpc.close()
    }

    @Test
    fun `refused next turn event restores committed controls and does not mutate`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        val cache = com.hermesagent.mobile.data.session.SessionCache()
        val repository = LiveGatewaySessionRepository(cache,
            kotlinx.coroutines.flow.MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            kotlinx.coroutines.flow.MutableStateFlow<GatewayRpcClient?>(peer.rpc), backgroundScope) { 1800000000000 }
        val key = repository.openSession("canonical-public", "work")
        val controls = mutableListOf<com.hermesagent.mobile.data.composer.SessionComposerControls>()
        val outcomes = mutableListOf<com.hermesagent.mobile.data.composer.DeferredModelPick>()
        val controlJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.composerControls.collect { controls += it } }
        val outcomeJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.deferredModelPicks.collect { outcomes += it } }
        runCurrent()
        peer.rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"session.updated","session_id":"canonical-public","seq":1,"replay_epoch":"public-session-epoch","execution_generation":3,"payload":{"session_id":"canonical-public","operation":"model","status":"refused","target_model":"public-next","model":"public-committed","provider":"public-provider","reason":"model_resolution_failed"}}}""")
        runCurrent()
        assertEquals(key, outcomes.single().durableId)
        assertNull(outcomes.single().confirm)
        assertEquals("public-committed", controls.single().selection?.model)
        assertTrue(peer.frames.none { it["method"] == JsonPrimitive("session.mutate") })
        controlJob.cancel(); outcomeJob.cancel(); peer.rpc.close()
    }

    @Test
    fun `same model late refusal cannot erase newer provider or combined reasoning intent`() = runTest {
        for (combined in listOf(false, true)) {
            val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready(); it.deferModels = true }
            val cache = com.hermesagent.mobile.data.session.SessionCache()
            val repository = LiveGatewaySessionRepository(cache,
                kotlinx.coroutines.flow.MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
                kotlinx.coroutines.flow.MutableStateFlow<GatewayRpcClient?>(peer.rpc), backgroundScope) { 1800000000000 }
            val key = repository.openSession("canonical-public", "work")
            val outcomes = mutableListOf<com.hermesagent.mobile.data.composer.DeferredModelPick>()
            val controls = mutableListOf<com.hermesagent.mobile.data.composer.SessionComposerControls>()
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.deferredModelPicks.collect { outcomes += it } }
            val controlsJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.composerControls.collect { controls += it } }
            repository.setLiveModel(key, com.hermesagent.mobile.data.composer.ComposerModelSelection("public-same", "public-provider-a"))
            repository.setLiveModel(key, com.hermesagent.mobile.data.composer.ComposerModelSelection("public-same", "public-provider-b"))
            if (combined) peer.rpc.request("config.set", buildJsonObject {
                put("session_id", key); put("key", "reasoning"); put("value", "high")
            })
            // Exact pinned producer refusal: no target provider, reasoning, request id or revision.
            peer.refusedEvent(1, "public-same")
            runCurrent()
            assertTrue(outcomes.isEmpty())
            assertTrue(controls.isEmpty())
            val expected = buildJsonObject {
                put("model", "public-same"); put("provider", "public-provider-b")
                if (combined) put("reasoning", "high")
            }
            peer.modelEvent(2, expected)
            runCurrent()
            assertEquals("public-provider-b", outcomes.single().target?.provider)
            assertNotNull(outcomes.single().confirm)
            var sequence = 3
            if (combined) {
                peer.pickInfo(sequence++, "low")
                peer.modelEvent(sequence++, JsonObject(expected + ("reasoning" to JsonPrimitive("low"))))
                runCurrent()
                assertEquals(1, outcomes.size)
            }
            peer.pickInfo(sequence++, "high")
            peer.refusedEvent(sequence++, "public-same"); runCurrent()
            assertEquals(1, outcomes.size)
            assertEquals("public-provider-b", controls.last().selection?.provider)
            peer.modelEvent(sequence, buildJsonObject { put("model", "public-other-viewer"); put("provider", "public-provider-c") })
            runCurrent()
            assertEquals(2, outcomes.size)
            assertEquals("public-other-viewer", outcomes.last().target?.model)
            assertNotNull(outcomes.last().confirm)
            job.cancel(); controlsJob.cancel(); peer.rpc.close()
        }
    }

    @Test
    fun `gap and epoch loss revoke consent before held or failed resume`() = runTest {
        for (kind in listOf("sequence", "epoch", "authority", "native")) for (fail in listOf(false, true)) {
            val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
            val cache = com.hermesagent.mobile.data.session.SessionCache()
            val repository = LiveGatewaySessionRepository(cache,
                kotlinx.coroutines.flow.MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
                kotlinx.coroutines.flow.MutableStateFlow<GatewayRpcClient?>(peer.rpc), backgroundScope) { 1800000000000 }
            repository.openSession("canonical-public", "work")
            val outcomes = mutableListOf<com.hermesagent.mobile.data.composer.DeferredModelPick>()
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.deferredModelPicks.collect { outcomes += it } }
            val whole = buildJsonObject { put("model", "public-next"); put("provider", "public-provider"); put("reasoning", "high") }
            peer.modelEvent(1, whole); runCurrent()
            val callback = outcomes.single().confirm!!
            peer.holdResume = !fail; peer.failResume = fail
            peer.gap(kind); runCurrent()
            assertTrue(outcomes.last().consentInvalidated)
            assertTrue(callback() is com.hermesagent.mobile.data.composer.ControlMutationResult.Rejected)
            // No old-stream ask can re-arm consent while recovery is unresolved.
            peer.modelEvent(2, whole); runCurrent()
            assertNull(outcomes.last().confirm)
            assertTrue(peer.frames.none { it["method"] == JsonPrimitive("session.mutate") })
            assertTrue(peer.frames.count { it["method"] == JsonPrimitive("session.resume") } >= 2)
            job.cancel(); peer.rpc.close()
        }
    }

    @Test
    fun `authority revocation at held final wire admission prevents confirmed send`() = runTest {
        for (kind in listOf("sequence", "epoch", "authority", "native")) {
            val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
            peer.rpc.request("session.resume", buildJsonObject { put("session_id", "canonical-public"); put("profile", "work") })
            val whole = buildJsonObject { put("model", "public-next"); put("provider", "public-provider"); put("reasoning", "high") }
            peer.modelEvent(1, whole)
            val request = buildJsonObject {
                put("session_id", "canonical-public"); put("profile", "work"); put("payload", whole); put("confirm", "public-event-token")
            }
            val result = runCatching { peer.rpc.requestAtEndpointDispatch("session.model", request) { finalSend ->
                // The protocol already consumed the exact token and prepared the mutation.
                // Revocation wins at the last dispatch boundary, before wire.send.
                peer.gap(kind)
                finalSend()
            } }
            assertTrue(result.isFailure)
            assertTrue(peer.frames.none { it["method"] == JsonPrimitive("session.mutate") })
            assertTrue(runCatching { peer.rpc.request("session.model", request) }.isFailure)
            peer.rpc.close()
        }
    }

    @Test
    fun `confirmation timeout or malformed receipt never automatically resends and needs a fresh ask`() = runTest {
        for (malformed in listOf(false, true)) {
            val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
            val cache = com.hermesagent.mobile.data.session.SessionCache()
            val repository = LiveGatewaySessionRepository(cache,
                kotlinx.coroutines.flow.MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
                kotlinx.coroutines.flow.MutableStateFlow<GatewayRpcClient?>(peer.rpc), backgroundScope) { 1800000000000 }
            repository.openSession("canonical-public", "work")
            val outcomes = mutableListOf<com.hermesagent.mobile.data.composer.DeferredModelPick>()
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.deferredModelPicks.collect { outcomes += it } }
            val whole = buildJsonObject { put("model", "public-next"); put("provider", "public-provider") }
            peer.modelEvent(1, whole); runCurrent()
            peer.silenceNextMutation = !malformed; peer.malformedMutation = malformed
            assertTrue(outcomes.single().confirm!!() is com.hermesagent.mobile.data.composer.ControlMutationResult.Rejected)
            assertTrue(outcomes.single().confirm!!() is com.hermesagent.mobile.data.composer.ControlMutationResult.Rejected)
            assertEquals(1, peer.frames.count { it["method"] == JsonPrimitive("session.mutate") })
            peer.malformedMutation = false
            peer.modelEvent(2, whole, token = "public-fresh-event-token"); runCurrent()
            assertEquals(com.hermesagent.mobile.data.composer.ControlMutationResult.Applied, outcomes.last().confirm!!())
            assertEquals(2, peer.frames.count { it["method"] == JsonPrimitive("session.mutate") })
            job.cancel(); peer.rpc.close()
        }
    }

    @Test
    fun `combined guard warning remains whole and redacted or oversized native consent is refused`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        val cache = com.hermesagent.mobile.data.session.SessionCache()
        val repository = LiveGatewaySessionRepository(cache,
            kotlinx.coroutines.flow.MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            kotlinx.coroutines.flow.MutableStateFlow<GatewayRpcClient?>(peer.rpc), backgroundScope) { 1800000000000 }
        repository.openSession("canonical-public", "work")
        val outcomes = mutableListOf<com.hermesagent.mobile.data.composer.DeferredModelPick>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.deferredModelPicks.collect { outcomes += it } }
        val whole = buildJsonObject { put("model", "public-next"); put("provider", "public-provider") }
        val warning = "High cost.\nData policy warning.\nLarge context: next reply re-reads uncached at full-price input cost.\nConfirm only if you intend to switch now."
        peer.modelEvent(1, whole, message = warning); runCurrent()
        assertEquals(warning, outcomes.single().safeMessage)
        assertNotNull(outcomes.single().confirm)
        peer.modelEvent(2, whole, message = warning + "\npassword=synthetic-warning-marker", token = "public-second-event-token"); runCurrent()
        assertFalse(outcomes.last().safeMessage.contains("synthetic-warning-marker"))
        assertTrue(outcomes.last().safeMessage.contains(warning))
        val previous = outcomes.last().confirm!!
        peer.modelEvent(3, whole, message = "x".repeat(4097) + warning, token = "public-oversized-event-token"); runCurrent()
        assertNull(outcomes.last().confirm)
        assertTrue(outcomes.last().safeMessage.length <= 4096)
        assertTrue(outcomes.last().safeMessage.contains("Review it in Hermes Desktop"))
        assertTrue(previous() is com.hermesagent.mobile.data.composer.ControlMutationResult.Rejected)
        assertTrue(peer.frames.none { it["method"] == JsonPrimitive("session.mutate") })
        job.cancel(); peer.rpc.close()
    }

    @Test
    fun `event confirmed model can be deferred again and exact wire admission is single use`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready(); it.deferConfirmedModels = true }
        val cache = com.hermesagent.mobile.data.session.SessionCache()
        val repository = LiveGatewaySessionRepository(cache,
            kotlinx.coroutines.flow.MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            kotlinx.coroutines.flow.MutableStateFlow<GatewayRpcClient?>(peer.rpc), backgroundScope) { 1800000000000 }
        repository.openSession("canonical-public", "work")
        val outcomes = mutableListOf<com.hermesagent.mobile.data.composer.DeferredModelPick>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.deferredModelPicks.collect { outcomes += it } }
        val whole = buildJsonObject { put("model", "public-next"); put("provider", "public-provider"); put("reasoning", "high") }
        peer.modelEvent(1, whole); runCurrent()
        assertEquals(com.hermesagent.mobile.data.composer.ControlMutationResult.Deferred, outcomes.single().confirm!!())
        assertEquals(JsonObject(whole + ("confirm" to JsonPrimitive("public-event-token"))),
            peer.frames.single { it["method"] == JsonPrimitive("session.mutate") }.getValue("params").jsonObject["payload"])
        peer.modelEvent(2, whole, token = "public-fresh-event-token"); runCurrent()
        peer.rpc.requestAtEndpointDispatch("session.model", buildJsonObject {
            put("session_id", "canonical-public"); put("profile", "work"); put("payload", whole); put("confirm", "public-fresh-event-token")
        }) { send ->
            val first = send()
            assertTrue(first)
            assertFalse(send())
            first
        }
        assertEquals(2, peer.frames.count { it["method"] == JsonPrimitive("session.mutate") })
        job.cancel(); peer.rpc.close()
    }

    @Test
    fun `synchronous model consent is revoked at gap or final admission and timeout never reuses it`() = runTest {
        for (mode in listOf("gap", "held", "timeout")) {
            val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
            peer.rpc.request("session.resume", buildJsonObject { put("session_id", "canonical-public"); put("profile", "work") })
            val pick = buildJsonObject {
                put("session_id", "canonical-public"); put("profile", "work"); put("key", "model")
                put("value", "public-next --provider public-provider --session")
            }
            assertEquals(JsonPrimitive(true), peer.rpc.request("config.set", pick).jsonObject["confirm_required"])
            val confirmed = JsonObject(pick + ("confirm_expensive_model" to JsonPrimitive(true)))
            if (mode == "gap") peer.gap("native")
            if (mode == "timeout") peer.silenceNextMutation = true
            val result = runCatching { peer.rpc.requestAtEndpointDispatch("config.set", confirmed) { send ->
                if (mode == "held") peer.gap("native")
                send()
            } }
            assertTrue(result.isFailure)
            assertTrue(runCatching { peer.rpc.request("config.set", confirmed) }.isFailure)
            assertEquals(if (mode == "timeout") 2 else 1,
                peer.frames.count { it["method"] == JsonPrimitive("session.mutate") })
            if (mode == "timeout") {
                peer.rpc.request("config.set", pick)
                assertFalse("confirm" in peer.frames.last().getValue("params").jsonObject.getValue("payload").jsonObject)
            }
            peer.rpc.close()
        }
    }

    private class CanonicalPeer(dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        var deferModels = false
        var deferConfirmedModels = false
        val frames = mutableListOf<JsonObject>()
        var refuseRegistryAfterCreate = false
        private var created = false
        var submitStatus: String? = null
        var submitResultOverride: JsonObject? = null
        var beforeSubmitReply: (() -> Unit)? = null
        var pendingRows = JsonArray(emptyList())
        var snapshotGeneration = 3
        var snapshotOwner: String? = "public-owner"
        var confirmationOwner: String? = null
        var requireAttachments = false
        var silenceNextSubmit = false
        var silenceNextMutation = false
        var malformedMutation = false
        var holdResume = false
        var failResume = false
        private val mutations = linkedMapOf<String, JsonObject>()
        var expectedGeneration = 3
        private val admissions = linkedMapOf<String, JsonObject>()
        val rpc: CorrelatedGatewayRpc = CorrelatedGatewayRpc(object : GatewayRpcWire {
            override fun send(text: String): Boolean {
                val frame = Json.parseToJsonElement(text).jsonObject
                frames += frame
                val method = frame.getValue("method").jsonPrimitive.content
                val params = frame.getValue("params").jsonObject
                val result = when (method) {
                    "profiles.list" -> """{"profiles":[{"name":"work","path":"public-owner"}],"bot_mode_protocol":true}"""
                    "runtime.describe" -> """{"instance_id":"public-instance","profile_id":"public-owner","authority_epoch":2,"capabilities":["durable-admission-v1","event-replay-v1","local-cli-create-v1"],"session_create":{"sources":["gui"],"parameters":[]},"skin":{}}"""
                    "session.create" -> {
                        if (params["source"] != JsonPrimitive("gui") || "request_id" !in params || "fast" in params || "reasoning_effort" in params) {
                            replyError(frame)
                            return true
                        }
                        created = true
                        snapshot()
                    }
                    "session.control.read" -> """{"control":{"goal":null,"loop":null,"heartbeat":null,"revision":"","updated_at":0}}"""
                    "session.info" -> confirmationOwner?.let { """{"profile_id":"$it","desktop_protocol":"hermes-gateway-v1"}""" } ?: "{}"
                    "session.resume" -> {
                        if (holdResume) return true
                        if (failResume) { replyError(frame); return true }
                        snapshot(params["session_id"]?.jsonPrimitive?.content ?: "canonical-public")
                    }
                    "session.list" -> {
                        if (refuseRegistryAfterCreate && created) return false
                        """{"sessions":[],"scope":"${params["scope"]?.jsonPrimitive?.content ?: "live"}"}"""
                    }
                    "image.attach_bytes" -> """{"attached":true,"path":"/workspace/public/upload.png","mime":"image/png","name":"upload.png","count":1,"remainder":"","text":"[User attached image: upload.png]","bytes":8}"""
                    "prompt.submit" -> {
                        submitResultOverride?.let {
                            beforeSubmitReply?.invoke()
                            rpc.receive("""{"jsonrpc":"2.0","id":${frame.getValue("id")},"result":$it}""")
                            return true
                        }
                        if (requireAttachments && params["attachments"] !is JsonArray) { replyError(frame); return true }
                        val submission = (params["submission_id"] ?: params["input_id"]) as? JsonPrimitive
                        if (submission == null || submission.content.isBlank()) {
                            replyError(frame)
                            return true
                        }
                        val receipt = admissions.getOrPut(submission.content) {
                            val queued = params["queued"] == JsonPrimitive(true)
                            val status = submitStatus ?: if (queued) "queued" else "started"
                            if (!queued) expectedGeneration = 4
                            buildJsonObject {
                                put("admission_id", "public-admission-${admissions.size + 1}")
                                put("ref", buildJsonObject { put("profile_id", "public-owner"); put("session_id", "canonical-public") })
                                put("sequence", admissions.size + 1); put("status", status)
                                put("outcome", if (status == "terminal") JsonPrimitive("completed") else JsonNull); put("authority_epoch", 2)
                                put("execution_generation", if (queued) JsonNull else JsonPrimitive(expectedGeneration))
                            }
                        }
                        if (silenceNextSubmit) { silenceNextSubmit = false; return true }
                        receipt.toString()
                    }
                    "session.mutate" -> {
                        val id = params["request_id"]?.jsonPrimitive?.content
                        if (id.isNullOrBlank() || params["expected_revision"] == null) { replyError(frame); return true }
                        val operation = params.getValue("operation").jsonPrimitive.content
                        val payload = params.getValue("payload").jsonObject
                        val receipt = mutations.getOrPut(id) { buildJsonObject {
                            put("session_id", "canonical-public"); put("operation", operation)
                            val guarded = operation == "model" && "confirm" !in payload
                            if (!guarded) {
                                put("revision", 8)
                                payload.forEach { (key, value) -> put(key, value) }
                            }
                            if (operation == "branch") { put("branched_session_id", "public-child"); put("copied_messages", 2) }
                            if (operation == "model" && (deferConfirmedModels || deferModels && "confirm" !in payload)) {
                                put("status", "deferred"); put("revision", 7); put("execution_generation", 3)
                                put("model", payload.getValue("model"))
                            } else if (operation == "model" && "confirm" !in payload) {
                                put("status", "confirmation_required"); put("confirm_required", true)
                                put("confirm", "public-confirmation"); put("confirm_message", "Confirm this model change.")
                                put("target_model", "public-model"); put("target_provider", "public-provider"); put("warnings", JsonArray(emptyList()))
                            }
                        } }
                        if (silenceNextMutation) { silenceNextMutation = false; return true }
                        if (malformedMutation) {
                            rpc.receive("""{"jsonrpc":"2.0","id":${frame.getValue("id")},"result":{}}""")
                            return true
                        }
                        receipt.toString()
                    }
                    "prompt.resolve_unknown", "prompt.cancel" -> {
                        val target = params.getValue("admission_id").jsonPrimitive.content
                        if (target == "public-lost" && params["execution_generation"] != JsonPrimitive(4)) { replyError(frame); return true }
                        """{"admission_id":"$target","ref":{"profile_id":"public-owner","session_id":"canonical-public"},"sequence":${if (target == "public-lost") 1 else 2},"status":"terminal","outcome":"${if (target == "public-lost") "interrupted" else "cancelled"}","authority_epoch":2,"execution_generation":${if (target == "public-lost") "4" else "null"}}"""
                    }
                    "session.interrupt" -> {
                        if (params["execution_generation"] != JsonPrimitive(expectedGeneration)) {
                            replyError(frame)
                            return true
                        }
                        """{"ref":{"profile_id":"public-owner","session_id":"canonical-public"},"instance_id":"public-instance","authority_epoch":2,"revision":7,"execution_generation":$expectedGeneration,"execution_state":"idle"}"""
                    }
                    else -> {
                        replyError(frame, -32601)
                        return true
                    }
                }
                rpc.receive("""{"jsonrpc":"2.0","id":${frame.getValue("id")},"result":$result}""")
                return true
            }
            override fun close() = Unit
        }, eventPumpDispatcher = dispatcher)

        private fun replyError(frame: JsonObject, code: Int = 4001) {
            rpc.receive("""{"jsonrpc":"2.0","id":${frame.getValue("id")},"error":{"code":$code,"message":"invalid_params","data":{"reason":"invalid_params"}}}""")
        }
        fun ready() = rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true,"change_events":true,"heartbeat":true,"replay_epoch":"public-global"}}}""")
        fun modelEvent(seq: Int, pick: JsonObject, generation: Int = 3, sid: String = "canonical-public", status: String = "confirmation_required", owner: String = "public-owner", message: String = "Confirm this model change.", token: String = "public-event-token") =
            rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"session.updated","session_id":"$sid","seq":$seq,"replay_epoch":"public-session-epoch","authority_epoch":2,"execution_generation":$generation,"payload":{"session_id":"$sid","profile_id":"$owner","operation":"model","status":"$status","confirm":${JsonPrimitive(token)},"confirm_message":${JsonPrimitive(message)},"payload":$pick,"target_model":${pick.getValue("model")},"committed_model":"public-model"}}}""")
        fun pickInfo(seq: Int, reasoning: String) = rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"session.info","session_id":"canonical-public","seq":$seq,"replay_epoch":"public-session-epoch","authority_epoch":2,"execution_generation":3,"payload":{"model":"public-same","provider":"public-provider-b","reasoning_effort":${JsonPrimitive(reasoning)}}}}""")
        fun refusedEvent(seq: Int, model: String) = rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"session.updated","session_id":"canonical-public","seq":$seq,"replay_epoch":"public-session-epoch","authority_epoch":2,"execution_generation":3,"payload":{"session_id":"canonical-public","operation":"model","status":"refused","target_model":${JsonPrimitive(model)},"model":"public-committed","provider":"public-provider-a","reason":"model_resolution_failed"}}}""")
        fun gap(kind: String) = rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"${if (kind == "native") "session.replay_gap" else "session.info"}","session_id":"canonical-public","seq":${if (kind == "sequence") 4 else 2},"replay_epoch":"${if (kind == "epoch") "public-new-epoch" else "public-session-epoch"}","authority_epoch":${if (kind == "authority") 3 else 2},"execution_generation":3,"payload":{}}}""")
        private fun snapshot(sid: String = "canonical-public") = """{"session_id":"$sid","stored_session_id":"$sid","messages":[{"role":"assistant","content":"Raw public answer"}],"display_messages":[{"role":"assistant","text":"Display public answer"}],"message_count":1,"running":false,"authority_epoch":2,"replay_epoch":"public-session-epoch","last_sequence":0,"subscription_id":"public-subscription","revision":7,"execution_generation":$snapshotGeneration,"pending":$pendingRows,"prompts":[],"info":{"desktop_protocol":"hermes-gateway-v1","profile_name":"work",${snapshotOwner?.let { "\"profile_id\":\"$it\"," }.orEmpty()}"model":"public-model","cwd":"/workspace/public"}}"""
    }
}
