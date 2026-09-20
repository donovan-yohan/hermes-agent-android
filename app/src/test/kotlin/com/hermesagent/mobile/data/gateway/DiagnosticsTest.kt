package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.SessionCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsTest {
    @Test fun `only exact safe Nous HTTPS links are openable`() {
        val safe = "https://portal.nousresearch.com/diagnostics/report-1?key=opaque"
        assertEquals(safe, safeDiagnosticsViewUrl(safe))
        listOf(null, "", " https://portal.nousresearch.com/a", "https://portal.nousresearch.com/a\n",
            "http://portal.nousresearch.com/a", "javascript:alert(1)", "intent://report",
            "https://portal.nousresearch.com.evil.invalid/a", "https://evil.invalid/a",
            "https://portal.nousresearch.com@evil.invalid/a", "https://user@portal.nousresearch.com/a",
            "https://portal.nousresearch.com:443/a", "https://portal.nousresearch.com./a",
            "https://portal.nousresearch.com/a#fragment", "https://127.0.0.1/a",
            "https://portal.nousresearch.com\\@evil.invalid/a", "https://portal.nousresearch.com/" + "a".repeat(4096),
        ).forEach { assertNull(it, safeDiagnosticsViewUrl(it)) }
    }

    @Test fun `response is typed bounded redacted and linkless success is not invented`() {
        fun result(raw: String) = parseDiagnosticsResult(Json.parseToJsonElement(raw))
        assertEquals(DiagnosticsResult.Failed, result("{}"))
        assertEquals(DiagnosticsResult.Failed, result("""{"ok":"true","upload_id":"report-1"}"""))
        assertEquals(DiagnosticsResult.Failed, result("""{"ok":true}"""))
        assertEquals(DiagnosticsResult.Failed, result("""{"ok":false,"error":"password=synthetic-secret"}"""))
        assertEquals(DiagnosticsResult.Failed, result("""{"ok":true,"view_url":"file:///private"}"""))
        val accepted = result("""{"ok":true,"upload_id":"report-1","view_url":"javascript:alert(1)","expires_at":"2026-09-21T00:00:00Z"}""") as DiagnosticsResult.Uploaded
        assertNull(accepted.viewUrl)
        assertEquals("report-1", accepted.uploadId)
        assertEquals("2026-09-21T00:00:00Z", accepted.expiresAt)
        val secret = result("""{"ok":true,"upload_id":"password=synthetic-secret"}""")
        assertFalse(secret.toString().contains("synthetic-secret"))
        assertEquals(120_000L, gatewayRpcTimeoutMillis("diagnostics.share_nous"))
    }

    @Test fun `live repository uses consent-gated pinned RPC with redacted context only`() = runTest {
        val rpc = FakeDiagnosticsRpc()
        val repository = LiveGatewaySessionRepository(
            SessionCache(), MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope,
        )
        runCurrent()
        assertEquals(0, rpc.uploads)
        val result = repository.shareDiagnostics("password=synthetic-secret\n[2001:db8::1]:443 fe80::abcd%qa0 [fe80::192.0.2.1%qa0]:443\n" + "x".repeat(9000), 0L) { it() }
        assertTrue(result is DiagnosticsResult.Uploaded)
        assertEquals(1, rpc.uploads)
        assertEquals(setOf("error_context"), rpc.params!!.keys)
        val context = rpc.params!!.getValue("error_context").jsonPrimitive.content
        assertFalse(context.contains("synthetic-secret"))
        assertFalse(context.contains("2001:db8"))
        assertFalse(context.contains("fe80"))
        assertFalse(context.contains("qa0"))
        assertTrue(context.length <= 4096)
    }

    @Test fun `final wire dispatch rejects consent withdrawal and endpoint invalidation`() = runTest {
        val fence = EndpointDispatchFence()
        val rpc = FakeDiagnosticsRpc()
        val repository = LiveGatewaySessionRepository(
            SessionCache(), MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope, endpointDispatchFence = fence,
        )
        runCurrent()
        assertEquals(DiagnosticsResult.Failed, repository.shareDiagnostics("error", 0L) { false })
        assertEquals(0, rpc.uploads)
        rpc.beforeSend = CompletableDeferred()
        val result = async { repository.shareDiagnostics("error", 0L) { it() } }
        runCurrent()
        fence.invalidate() // switch wins after request preparation, before actual send
        rpc.beforeSend!!.complete(Unit)
        runCurrent()
        assertEquals(DiagnosticsResult.Failed, result.await())
        assertEquals(0, rpc.uploads)
    }

    @Test fun `unknown method and permission refusal remain honest and are not retried`() = runTest {
        val rpc = FakeDiagnosticsRpc()
        val repository = LiveGatewaySessionRepository(
            SessionCache(), MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope,
        )
        runCurrent()
        rpc.failure = GatewayRpcError(-32601, "unknown method")
        assertEquals(DiagnosticsResult.Unsupported, repository.shareDiagnostics("error", 0L) { it() })
        assertEquals(1, rpc.uploads)
        rpc.failure = GatewayRpcError(403, "password=synthetic-secret")
        assertEquals(DiagnosticsResult.Refused, repository.shareDiagnostics("error", 0L) { it() })
        assertEquals(2, rpc.uploads)
    }

    @Test fun `reply from a retired endpoint cannot become a success receipt`() = runTest {
        val cache = SessionCache()
        val rpc = FakeDiagnosticsRpc().apply { afterSend = CompletableDeferred() }
        val repository = LiveGatewaySessionRepository(
            cache, MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope,
        )
        runCurrent()
        val result = async { repository.shareDiagnostics("error", 0L) { it() } }
        runCurrent()
        assertEquals(1, rpc.uploads)
        cache.resetForEndpointSwitch()
        rpc.afterSend!!.complete(Unit)
        runCurrent()
        assertEquals(DiagnosticsResult.Failed, result.await())
        assertEquals(1, rpc.uploads)
    }

    private class FakeDiagnosticsRpc : EndpointDispatchingGatewayRpcClient {
        override val events = emptyFlow<GatewayEvent>()
        var uploads = 0
        var params: JsonObject? = null
        var beforeSend: CompletableDeferred<Unit>? = null
        var afterSend: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        override suspend fun request(method: String, params: JsonObject): JsonElement {
            check(method != "diagnostics.share_nous") { "Upload bypassed dispatch gate" }
            return JsonObject(emptyMap())
        }
        override suspend fun requestAtEndpointDispatch(
            method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean,
        ): JsonElement {
            assertEquals("diagnostics.share_nous", method)
            beforeSend?.await()
            if (!dispatch { uploads++; this.params = params; true }) throw GatewayRpcException("Not sent")
            afterSend?.await()
            failure?.let { throw it }
            return Json.parseToJsonElement("""{"ok":true,"upload_id":"report-1"}""")
        }
        override fun close() = Unit
    }
}
