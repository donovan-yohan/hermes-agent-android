package com.hermesagent.mobile.plugins.groups

import com.hermesagent.mobile.data.gateway.CorrelatedGatewayRpc
import com.hermesagent.mobile.data.gateway.EndpointDispatchingGatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayRpcWire
import com.hermesagent.mobile.plugins.GatewayPluginHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupSendConnectionFenceTest {
    private val identity = GroupSendIdentity("saved", "binding", "gateway")
    private val target = GroupSendTarget(identity, "room")

    private class Wire : GatewayRpcWire {
        val frames = mutableListOf<String>()
        override fun send(text: String): Boolean = frames.add(text)
        override fun close() {}
    }

    private class PausedDispatch(
        private val rpc: CorrelatedGatewayRpc,
        private val beforeDispatch: suspend () -> Unit,
    ) : EndpointDispatchingGatewayRpcClient by rpc {
        override suspend fun requestAtEndpointDispatch(
            method: String,
            params: JsonObject,
            dispatch: (() -> Boolean) -> Boolean,
        ): JsonElement {
            beforeDispatch()
            return rpc.requestAtEndpointDispatch(method, params, dispatch)
        }
    }

    @Test fun `persisted operation precedes wire and replacement cannot receive it`() = runTest {
        for (switchEndpoint in listOf(false, true)) {
            val store = TransientGroupSendStore()
            val release = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val wire = Wire()
            val nextWire = Wire()
            val rpc = CorrelatedGatewayRpc(wire, timeoutMillis = 1000)
            val next = CorrelatedGatewayRpc(nextWire, timeoutMillis = 1000)
            val old = PausedDispatch(rpc) {
                val snapshot = store.snapshot()
                assertEquals("event", snapshot.records.values.single().operation.rawId)
                assertEquals(GroupSendRecordState.Prepared, snapshot.records.values.single().state)
                assertEquals("Message", snapshot.drafts.values.single().text)
                entered.complete(Unit)
                release.await()
            }
            val clients = MutableStateFlow<GatewayRpcClient?>(old)
            val generation = MutableStateFlow(0L)
            val host = GatewayPluginHost(backgroundScope, clients, generation)
            val connection = BoundGroupSendConnection(host, identity, 0L, checkNotNull(host.connectionToken.value))
            val coordinator = GroupSendCoordinator(store, connection) { 1L }
            try {
                val send = async { coordinator.submit(target, "event", "Message", "thread", 1L) }
                runCurrent()
                assertTrue("send must pass the captured dispatch boundary", entered.isCompleted)
                if (switchEndpoint) generation.value = 1L
                clients.value = next
                release.complete(Unit)
                assertTrue(send.await().isFailure)
                assertTrue(wire.frames.isEmpty())
                assertTrue(nextWire.frames.isEmpty())
                assertNotEquals(GroupSendCoordinatorState.Confirmed, coordinator.executionState.first().status)
            } finally {
                rpc.close()
                next.close()
            }
        }
    }

    @Test fun `late old connection response cannot confirm operation after replacement`() = runTest {
        for (switchEndpoint in listOf(false, true)) {
            val wire = Wire()
            val nextWire = Wire()
            val rpc = CorrelatedGatewayRpc(wire, timeoutMillis = 1000)
            val next = CorrelatedGatewayRpc(nextWire, timeoutMillis = 1000)
            val clients = MutableStateFlow<GatewayRpcClient?>(rpc)
            val generation = MutableStateFlow(0L)
            val host = GatewayPluginHost(backgroundScope, clients, generation)
            val connection = BoundGroupSendConnection(host, identity, 0L, checkNotNull(host.connectionToken.value))
            val store = TransientGroupSendStore()
            val coordinator = GroupSendCoordinator(store, connection) { 1L }
            try {
                val send = async { coordinator.submit(target, "event", "Message", "thread", 1L) }
                runCurrent()
                val id = Json.parseToJsonElement(wire.frames.single()).jsonObject.getValue("id")
                if (switchEndpoint) generation.value = 1L
                clients.value = next
                val storedId = storedUserEventId("event")
                rpc.receive("""{"jsonrpc":"2.0","id":$id,"result":{"client_event_id":"event","accepted":true,"driver_started":true,"event":{"event_id":"$storedId","room_id":"room","kind":"message.user","actor":{"kind":"user","id":"desktop"},"payload":{"text":"Message","thread_id":"thread"},"authority_epoch":1,"created_at":1.0,"idempotent":false,"seq":1}}}""")
                assertTrue(send.await().isFailure)
                assertEquals(1, wire.frames.size)
                assertTrue(nextWire.frames.isEmpty())
                assertNotEquals(GroupSendRecordState.Confirmed, store.snapshot().records.values.single().state)
            } finally {
                rpc.close()
                next.close()
            }
        }
    }
}
