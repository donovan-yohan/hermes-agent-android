package com.hermesagent.mobile.plugins

import com.hermesagent.mobile.data.gateway.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real correlation/host path, including the irreversible handoff and uncertain outcomes. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentPluginsAdmissionTest {
    @Test fun closeAfterFinalPredicateBeforeSendStillRefusesActualFrame() = runTest {
        val wire = Wire()
        val rpc = CorrelatedGatewayRpc(wire, 20_000).also { wire.rpc = it }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = GatewayPluginHost(scope, MutableStateFlow(rpc))
        val owner = PluginRequestOwner()
        val checked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val checks = java.util.concurrent.atomic.AtomicInteger()
        try {
            val pending = async(Dispatchers.Default) {
                host.requestAtEndpointOwned(0L, "plugins.manage", buildJsonObject {
                    put("action", "toggle"); put("profile", "work")
                    put("key", "image_gen/fal"); put("enable", true)
                }, owner) {
                    // lease preflight, async preflight, then final endpoint-fence predicate.
                    if (checks.incrementAndGet() == 3) {
                        checked.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    }
                    true // deliberately stale predicate result, as in the reported interleaving
                }
            }
            assertTrue(checked.await(5, TimeUnit.SECONDS))
            owner.close() // completes before handoff, despite the already-true ownership predicate
            release.countDown()
            assertTrue(pending.await() is PluginHostResult.Refused)
            assertTrue(wire.frames.isEmpty())
        } finally { release.countDown(); scope.cancel(); rpc.close() }
    }

    @Test fun admittedSendMakesCloseWaitForImmediateHandoffButNotResponse() = runTest {
        val wire = Wire()
        val rpc = CorrelatedGatewayRpc(wire, 20_000)
        wire.rpc = rpc
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = AgentPluginsStore(GatewayPluginHost(scope, MutableStateFlow(rpc)), "work")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var closer: Thread? = null
        try {
            store.refresh()
            wire.beforeToggle = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            wire.answerToggle = false
            val pending = async(Dispatchers.Default) { store.toggle("image_gen/fal", true) }
            assertTrue(entered.await(5, TimeUnit.SECONDS)) // final predicates passed; in actual wire.send
            closer = Thread { closing.countDown(); store.close(); closed.countDown() }.apply { start() }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (closer.state != Thread.State.BLOCKED && closed.count != 0L && System.nanoTime() < deadline) Thread.yield()
            assertEquals("close must serialize with admission", Thread.State.BLOCKED, closer.state)
            assertEquals(1L, closed.count)
            release.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS)) // response is still held, close does not await it
            assertEquals(1, wire.toggles.size)
            assertTrue(pending.isActive)
            wire.respond(wire.toggles.single(), buildJsonObject { put("ok", true) })
            pending.await()
            assertFalse(store.state.value.rows.single().enabled) // old owner's response never publishes
            assertEquals(2, wire.frames.size) // no readback after close
        } finally {
            release.countDown(); closer?.join(5_000); scope.cancel(); rpc.close()
        }
    }

    @Test fun closeFirstRefusesHeldRealRpcAndSameProfileReopenCannotReviveIt() = runTest {
        val wire = Wire()
        val rpc = CorrelatedGatewayRpc(wire, 1_000).also { wire.rpc = it }
        val held = HeldRpc(rpc)
        val host = GatewayPluginHost(backgroundScope, MutableStateFlow(held))
        val owner = AgentPluginsOwner()
        val old = owner.open(host, "work")
        old.refresh()
        val gate = CompletableDeferred<Unit>()
        held.before = { gate.await() }
        val pending = async { old.toggle("image_gen/fal", true) }
        runCurrent()
        old.close()
        val reopened = owner.open(host, "work")
        gate.complete(Unit); pending.await()
        assertTrue(wire.toggles.isEmpty())
        reopened.refresh(); reopened.toggle("image_gen/fal", true)
        assertTrue(reopened.state.value.rows.single().enabled)
        assertEquals(1, wire.toggles.size)
        old.toggle("image_gen/fal", true)
        assertEquals(1, wire.toggles.size)
        rpc.close()
    }

    @Test fun profileOwnerABARevokesOldAWithoutRevokingReplacementA() = runTest {
        val wire = Wire()
        val rpc = CorrelatedGatewayRpc(wire, 1_000).also { wire.rpc = it }
        val held = HeldRpc(rpc)
        val host = GatewayPluginHost(backgroundScope, MutableStateFlow(held))
        val owner = AgentPluginsOwner()
        val oldA = owner.open(host, "a")
        oldA.refresh()
        val gate = CompletableDeferred<Unit>()
        held.before = { gate.await() }
        val pending = async { oldA.toggle("image_gen/fal", true) }
        runCurrent()
        owner.open(host, "b")
        val newA = owner.open(host, "a")
        gate.complete(Unit); pending.await()
        assertTrue(wire.toggles.isEmpty())
        newA.refresh(); newA.toggle("image_gen/fal", true)
        assertEquals(JsonPrimitive("a"), wire.toggles.single()["params"]!!.jsonObject["profile"])
        assertTrue(newA.state.value.rows.single().enabled)
        rpc.close()
    }

    @Test fun endpointABAUsesGenerationNotEqualEndpointOrClientIdentity() = runTest {
        val wire = Wire()
        val rpc = CorrelatedGatewayRpc(wire, 1_000).also { wire.rpc = it }
        val held = HeldRpc(rpc)
        val endpoint = MutableStateFlow(0L)
        val fence = EndpointDispatchFence()
        val clients = MutableStateFlow<GatewayRpcClient?>(held)
        val host = GatewayPluginHost(backgroundScope, clients, endpoint, fence)
        val oldA = AgentPluginsStore(host, "work")
        oldA.refresh()
        val gate = CompletableDeferred<Unit>()
        held.before = { gate.await() }
        val pending = async { oldA.toggle("image_gen/fal", true) }
        runCurrent()
        fence.invalidate(); endpoint.value = 1L; clients.value = null // endpoint B
        fence.invalidate(); endpoint.value = 2L; clients.value = held // back to endpoint A, even same RPC
        val newA = AgentPluginsStore(host, "work")
        gate.complete(Unit); pending.await()
        assertTrue(wire.toggles.isEmpty())
        newA.refresh(); newA.toggle("image_gen/fal", true)
        assertEquals(1, wire.toggles.size)
        assertTrue(newA.state.value.rows.single().enabled)
        rpc.close()
    }

    @Test fun realTimeoutReadbackStaysUnconfirmedEvenIfMutationAppliedAndNeverRetries() = runTest {
        val wire = Wire().apply { answerToggle = false }
        val rpc = CorrelatedGatewayRpc(wire, 100).also { wire.rpc = it }
        val store = AgentPluginsStore(GatewayPluginHost(backgroundScope, MutableStateFlow(rpc)), "default")
        store.refresh()
        val pending = async { store.toggle("image_gen/fal", true) }
        runCurrent()
        assertEquals(1, wire.toggles.size)
        assertEquals("image_gen/fal", store.state.value.busyKey)
        advanceTimeBy(101); runCurrent(); pending.await()
        assertTrue(store.state.value.rows.single().enabled) // fresh readback, not echoed receipt
        assertTrue(store.state.value.unconfirmed) // timeout is not an acknowledged success
        assertNull(store.state.value.busyKey)
        assertEquals(3, wire.frames.size)
        store.toggle("image_gen/fal", false)
        assertEquals(1, wire.toggles.size)
        wire.respond(wire.toggles.single(), buildJsonObject { put("ok", true) }) // late response cannot settle it
        assertTrue(store.state.value.unconfirmed)
        store.refresh()
        assertFalse(store.state.value.unconfirmed)
        assertTrue(wire.frames.all { it["params"]!!.jsonObject["profile"] == JsonPrimitive("default") })
        rpc.close()
    }

    @Test fun realCancellationAfterSendRetainsUncertaintyUntilExplicitRefresh() = runTest {
        val wire = Wire().apply { answerToggle = false }
        val rpc = CorrelatedGatewayRpc(wire, 1_000).also { wire.rpc = it }
        val store = AgentPluginsStore(GatewayPluginHost(backgroundScope, MutableStateFlow(rpc)), "work")
        store.refresh()
        val pending = async { store.toggle("image_gen/fal", true) }
        runCurrent()
        assertEquals(1, wire.toggles.size)
        pending.cancelAndJoin(); runCurrent()
        assertTrue(store.state.value.unconfirmed)
        assertNull(store.state.value.busyKey)
        assertFalse(store.state.value.rows.single().enabled)
        store.toggle("image_gen/fal", true)
        assertEquals(2, wire.frames.size) // no automatic readback or mutation replay after cancellation
        wire.respond(wire.toggles.single(), buildJsonObject { put("ok", true) })
        assertTrue(store.state.value.unconfirmed)
        store.refresh()
        assertTrue(store.state.value.rows.single().enabled)
        assertFalse(store.state.value.unconfirmed)
        rpc.close()
    }

    /** Holds before CorrelatedGatewayRpc invokes its production dispatch callback. */
    private class HeldRpc(private val rpc: CorrelatedGatewayRpc) : EndpointDispatchingGatewayRpcClient, GatewayRpcClient by rpc {
        var before: suspend () -> Unit = {}
        override suspend fun requestAtEndpointDispatch(method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean): JsonElement {
            before()
            return rpc.requestAtEndpointDispatch(method, params, dispatch)
        }
    }

    private class Wire : GatewayRpcWire {
        lateinit var rpc: CorrelatedGatewayRpc
        val frames = CopyOnWriteArrayList<JsonObject>()
        val toggles get() = frames.filter { it["params"]!!.jsonObject["action"] == JsonPrimitive("toggle") }
        var beforeToggle: () -> Unit = {}
        var answerToggle = true
        var enabled = false
        override fun send(text: String): Boolean {
            val frame = Json.parseToJsonElement(text).jsonObject
            val params = frame["params"]!!.jsonObject
            assertEquals(JsonPrimitive("plugins.manage"), frame["method"])
            if (params["action"] == JsonPrimitive("toggle")) beforeToggle()
            frames += frame
            if (params["action"] == JsonPrimitive("toggle")) {
                assertEquals(setOf("action", "profile", "key", "enable"), params.keys)
                assertEquals(JsonPrimitive("image_gen/fal"), params["key"])
                enabled = params["enable"] == JsonPrimitive(true)
                if (answerToggle) respond(frame, buildJsonObject { put("ok", true) })
            } else respond(frame, buildJsonObject {
                put("plugins", buildJsonArray { add(buildJsonObject {
                    put("key", "image_gen/fal"); put("name", "fal"); put("source", "user")
                    put("status", if (enabled) "enabled" else "disabled")
                }) })
            })
            return true
        }
        fun respond(frame: JsonObject, result: JsonElement) = rpc.receive(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", frame["id"]!!); put("result", result)
        }.toString())
        override fun close() = Unit
    }
}
