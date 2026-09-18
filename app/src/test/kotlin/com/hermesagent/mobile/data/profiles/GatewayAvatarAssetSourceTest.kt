package com.hermesagent.mobile.data.profiles

import com.hermesagent.mobile.data.gateway.CorrelatedGatewayRpc
import com.hermesagent.mobile.data.gateway.EndpointDispatchFence
import com.hermesagent.mobile.data.gateway.EndpointDispatchingGatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayRpcWire
import com.hermesagent.mobile.data.gateway.gatewayRpcTimeoutMillis
import com.hermesagent.mobile.plugins.GatewayPluginHost
import com.hermesagent.mobile.plugins.PluginHostResult
import com.hermesagent.mobile.plugins.UnavailablePluginHost
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

internal class AvatarRecordingWire : GatewayRpcWire {
    val frames = CopyOnWriteArrayList<String>()
    var onSend: () -> Unit = {}
    override fun send(text: String): Boolean { onSend(); frames += text; return true }
    override fun close() = Unit
}

/** Gates the production RPC's actual dispatch method, not a second implementation of its fence. */
internal class AvatarGatedRpc(
    private val rpc: CorrelatedGatewayRpc,
    private val beforeWire: suspend () -> Unit,
) : EndpointDispatchingGatewayRpcClient by rpc {
    override suspend fun requestAtEndpointDispatch(
        method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean,
    ): JsonElement {
        beforeWire()
        return rpc.requestAtEndpointDispatch(method, params, dispatch)
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GatewayAvatarAssetSourceTest {
    @Test fun exactReadShapeUsesRealSlowBudgetAndDoesNotAlterOtherMethods() = runTest {
        val wire = AvatarRecordingWire()
        val rpc = CorrelatedGatewayRpc(wire)
        val host = GatewayPluginHost(backgroundScope, MutableStateFlow(rpc))
        val source = checkNotNull(GatewayAvatarAssetSource.capture(host))
        val call = async { source.getAvatar(" Fixture-Alpha ") }
        runCurrent()
        val frame = Json.parseToJsonElement(wire.frames.single()).jsonObject
        assertEquals("profiles.get_asset", frame["method"]!!.jsonPrimitive.content)
        assertEquals(buildJsonObject { put("name", " Fixture-Alpha "); put("asset", "avatar") }, frame["params"])
        advanceTimeBy(59_000)
        runCurrent()
        assertFalse(call.isCompleted)
        rpc.receive("""{"jsonrpc":"2.0","id":"m1","result":{"found":false}}""")
        assertEquals("false", call.await().jsonObject["found"].toString())
        assertEquals(60_000L, gatewayRpcTimeoutMillis("profiles.get_asset"))
        assertEquals(60_000L, gatewayRpcTimeoutMillis("profiles.list"))
        assertEquals(1_800_000L, gatewayRpcTimeoutMillis("prompt.submit"))
        assertEquals(123L, gatewayRpcTimeoutMillis("session.list", 123))
        assertEquals(123L, gatewayRpcTimeoutMillis("profiles.set_asset", 123))
        rpc.close()
    }

    @Test fun transportDeadlineMapsToUnavailableButWaiterCancellationPropagates() = runTest {
        val wire = AvatarRecordingWire()
        val rpc = CorrelatedGatewayRpc(wire)
        val host = GatewayPluginHost(backgroundScope, MutableStateFlow(rpc))
        val source = checkNotNull(GatewayAvatarAssetSource.capture(host))
        val call = async { runCatching { source.getAvatar("fixture-alpha") }.exceptionOrNull() }
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(call.await() is AvatarReadUnavailable)
        val cancelled = async { source.getAvatar("fixture-alpha") }
        runCurrent()
        cancelled.cancel()
        runCurrent()
        assertTrue(cancelled.isCancelled)
        assertTrue(source.isCurrent())
        rpc.close()
    }

    @Test fun endpointAndLegSwitchAtWireGateSendNeitherWire() = runTest {
        for (endpointSwitch in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val oldWire = AvatarRecordingWire()
            val oldRpc = CorrelatedGatewayRpc(oldWire)
            val gated = AvatarGatedRpc(oldRpc) { entered.complete(Unit); release.await() }
            val clients = MutableStateFlow<GatewayRpcClient?>(gated)
            val endpoint = MutableStateFlow(0L)
            val fence = EndpointDispatchFence()
            val host = GatewayPluginHost(backgroundScope, clients, endpoint, fence)
            val source = checkNotNull(GatewayAvatarAssetSource.capture(host))
            val call = async { runCatching { source.getAvatar("fixture-alpha") }.exceptionOrNull() }
            runCurrent()
            assertTrue(entered.isCompleted)
            val newWire = AvatarRecordingWire()
            val next = CorrelatedGatewayRpc(newWire)
            if (endpointSwitch) { fence.invalidate(); endpoint.value = 1L }
            clients.value = next // Do not pump the host token collector before releasing dispatch.
            release.complete(Unit)
            assertTrue(call.await() is AvatarReadUnavailable)
            assertTrue(oldWire.frames.isEmpty())
            assertTrue(newWire.frames.isEmpty())
            assertFalse(source.isCurrent())
            oldRpc.close(); next.close()
        }
    }

    @Test fun replyAfterSentFrameAndReconnectIsNotCurrentTruth() = runTest {
        val oldWire = AvatarRecordingWire()
        val old = CorrelatedGatewayRpc(oldWire)
        val clients = MutableStateFlow<GatewayRpcClient?>(old)
        val host = GatewayPluginHost(backgroundScope, clients)
        val source = checkNotNull(GatewayAvatarAssetSource.capture(host))
        val call = async { runCatching { source.getAvatar("fixture-alpha") }.exceptionOrNull() }
        runCurrent()
        assertEquals(1, oldWire.frames.size)
        val newWire = AvatarRecordingWire()
        val next = CorrelatedGatewayRpc(newWire)
        clients.value = next
        old.receive("""{"jsonrpc":"2.0","id":"m1","result":{"found":false}}""")
        assertTrue(call.await() is AvatarReadUnavailable)
        assertTrue(newWire.frames.isEmpty())
        old.close(); next.close()
    }

    @Test fun tokenIsHostLocalAndUnsupportedDoorNeverFallsBack() = runTest {
        assertNull(GatewayAvatarAssetSource.capture(UnavailablePluginHost))
        val wire = AvatarRecordingWire()
        val rpc = CorrelatedGatewayRpc(wire)
        val clients = MutableStateFlow<GatewayRpcClient?>(rpc)
        val app = GatewayPluginHost(backgroundScope, clients)
        val plugin = GatewayPluginHost(backgroundScope, clients)
        assertTrue(app.requestAtConnection(0, checkNotNull(plugin.connectionToken.value), "profiles.get_asset") is PluginHostResult.Refused)
        assertTrue(wire.frames.isEmpty())
        // A plain client can serve ordinary RPC but cannot authorize an endpoint-dispatch read.
        val plain = object : GatewayRpcClient by rpc {
            override suspend fun request(method: String, params: JsonObject): JsonElement = error("ordinary RPC fallback")
        }
        clients.value = plain
        val source = checkNotNull(GatewayAvatarAssetSource.capture(app))
        assertTrue(runCatching { source.getAvatar("fixture-alpha") }.exceptionOrNull() is AvatarReadUnavailable)
        assertTrue(wire.frames.isEmpty())
        rpc.close()
    }

    @Test fun refusalAndUnknownMethodNeverBecomeAuthoritativeMissing() = runTest {
        val wire = AvatarRecordingWire()
        val rpc = CorrelatedGatewayRpc(wire)
        val source = checkNotNull(GatewayAvatarAssetSource.capture(GatewayPluginHost(backgroundScope, MutableStateFlow(rpc))))
        for ((index, code) in listOf(-32601, -32000).withIndex()) {
            val call = async { runCatching { source.getAvatar("fixture-alpha") }.exceptionOrNull() }
            runCurrent()
            rpc.receive("""{"jsonrpc":"2.0","id":"m${index + 1}","error":{"code":$code,"message":"synthetic backend prose"}}""")
            val failure = call.await()
            assertTrue(failure is AvatarReadUnavailable)
            assertEquals("Avatar unavailable", failure!!.message)
        }
        rpc.close()
    }

    @Test fun actualTwoThreadSwitchBetweenCaptureAndWireFailsClosed() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val oldWire = AvatarRecordingWire()
        val old = CorrelatedGatewayRpc(oldWire)
        val nextWire = AvatarRecordingWire()
        val next = CorrelatedGatewayRpc(nextWire)
        val clients = MutableStateFlow<GatewayRpcClient?>(AvatarGatedRpc(old) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        val endpoint = MutableStateFlow(0L)
        val fence = EndpointDispatchFence()
        val source = checkNotNull(GatewayAvatarAssetSource.capture(GatewayPluginHost(scope, clients, endpoint, fence)))
        try {
            val request = executor.submit<Boolean> { runBlocking { runCatching { source.getAvatar("fixture-alpha") }.exceptionOrNull() is AvatarReadUnavailable } }
            val switch = executor.submit {
                check(entered.await(5, TimeUnit.SECONDS))
                fence.invalidate()
                clients.value = next
                endpoint.value = 1L
                release.countDown()
            }
            switch.get(5, TimeUnit.SECONDS)
            assertTrue(request.get(5, TimeUnit.SECONDS))
            assertTrue(oldWire.frames.isEmpty())
            assertTrue(nextWire.frames.isEmpty())
        } finally {
            release.countDown(); scope.cancel(); old.close(); next.close(); executor.shutdownNow()
        }
    }
}
