package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
        assertEquals(JsonPrimitive(42), peer.frames.last().getValue("params").jsonObject.getValue("payload").jsonObject["through_message_id"])
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

    private class CanonicalPeer(dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        val frames = mutableListOf<JsonObject>()
        var refuseRegistryAfterCreate = false
        private var created = false
        var submitStatus: String? = null
        var pendingRows = JsonArray(emptyList())
        var snapshotGeneration = 3
        var requireAttachments = false
        var silenceNextSubmit = false
        var silenceNextMutation = false
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
                    "runtime.describe" -> """{"instance_id":"public-instance","profile_id":"public-owner","authority_epoch":2,"capabilities":["durable-admission-v1","event-replay-v1","local-cli-create-v1"],"session_create":{"sources":["gui"],"parameters":[]},"skin":{}}"""
                    "session.create" -> {
                        if (params["source"] != JsonPrimitive("gui") || "request_id" !in params || "fast" in params || "reasoning_effort" in params) {
                            replyError(frame)
                            return true
                        }
                        created = true
                        snapshot()
                    }
                    "session.resume" -> snapshot()
                    "session.list" -> {
                        if (refuseRegistryAfterCreate && created) return false
                        """{"sessions":[],"scope":"${params["scope"]?.jsonPrimitive?.content ?: "live"}"}"""
                    }
                    "image.attach_bytes" -> """{"attached":true,"path":"/workspace/public/upload.png","mime":"image/png","name":"upload.png","count":1,"remainder":"","text":"[User attached image: upload.png]","bytes":8}"""
                    "prompt.submit" -> {
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
                            if (operation == "model" && "confirm" !in payload) {
                                put("status", "confirmation_required"); put("confirm_required", true)
                                put("confirm", "public-confirmation"); put("confirm_message", "Confirm this model change.")
                                put("target_model", "public-model"); put("target_provider", "public-provider"); put("warnings", JsonArray(emptyList()))
                            }
                        } }
                        if (silenceNextMutation) { silenceNextMutation = false; return true }
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
        private fun snapshot() = """{"session_id":"canonical-public","stored_session_id":"canonical-public","messages":[{"role":"assistant","content":"Raw public answer"}],"display_messages":[{"role":"assistant","text":"Display public answer"}],"message_count":1,"running":false,"authority_epoch":2,"replay_epoch":"public-session-epoch","last_sequence":0,"subscription_id":"public-subscription","revision":7,"execution_generation":$snapshotGeneration,"pending":$pendingRows,"prompts":[],"info":{"desktop_protocol":"hermes-gateway-v1","profile_name":"work","profile_id":"public-owner","model":"public-model","cwd":"/workspace/public"}}"""
    }
}
