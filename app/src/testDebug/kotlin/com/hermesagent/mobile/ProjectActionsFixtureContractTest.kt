package com.hermesagent.mobile

import org.junit.Assert.*
import org.junit.Test

class ProjectActionsFixtureContractTest {
    @Test fun `debug native project actions fixture exists`() {
        assertNotNull(Class.forName("com.hermesagent.mobile.ProjectActionsParityActivity"))
    }

    @Test fun `write refusal fixture supplies a producer proven no write error`() = kotlinx.coroutines.test.runTest {
        val rpc = ProjectActionsFixtureRpc().apply { failWrites = true }
        val params = kotlinx.serialization.json.Json.parseToJsonElement("""{"id":"synthetic-project","name":"Not saved"}""") as kotlinx.serialization.json.JsonObject
        val failure = runCatching { rpc.request("projects.update", params) }.exceptionOrNull()
        assertTrue("Refusal must not masquerade as a generic post-dispatch failure", failure is com.hermesagent.mobile.data.gateway.GatewayRpcError)
        assertEquals(-32601, (failure as com.hermesagent.mobile.data.gateway.GatewayRpcError).code)
        val tree = rpc.request("projects.tree")
        assertTrue(tree.toString().contains("Synthetic project"))
        assertFalse(tree.toString().contains("Not saved"))
    }
}
