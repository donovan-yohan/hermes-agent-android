package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.SessionCache
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProjectEditorSnapshotTest {
    @Test fun `connection change after captured snapshot returns no editor instead of throwing`() = runTest {
        val rpc = object : GatewayRpcClient {
            override val events = emptyFlow<GatewayEvent>()
            override fun close() = Unit
            override suspend fun request(method: String, params: JsonObject): JsonElement =
                Json.parseToJsonElement("""{"projects":[{"id":"p","label":"Project"}]}""")
        }
        val backing = MutableStateFlow<GatewayRpcClient?>(rpc)
        var armed = false
        var crossedBoundary = false
        val clients = object : StateFlow<GatewayRpcClient?> by backing {
            override val value: GatewayRpcClient? get() {
                val captured = backing.value
                val stack = Thread.currentThread().stackTrace
                // Mutate the injected client immediately AFTER the final identity read in
                // connectionSnapshot's ensureCurrent, not an assumed number of getter reads.
                if (armed && stack.any { it.methodName == "ensureCurrent" } &&
                    stack.any { it.methodName == "connectionSnapshot" }) {
                    armed = false
                    crossedBoundary = true
                    backing.value = null
                }
                return captured
            }
        }
        val cache = SessionCache()
        val repo = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)), clients, backgroundScope) { 0 }
        runCurrent(); repo.refreshProjects()
        val project = cache.state.value.projects.projects.getValue("p")
        armed = true
        val result = runCatching { repo.projectEditor(project) }
        assertTrue("The actual snapshot identity boundary must be reached", crossedBoundary)
        assertNull("Synchronous UI lookup must not throw: ${result.exceptionOrNull()}", result.exceptionOrNull())
        assertNull(result.getOrNull())
    }
}
