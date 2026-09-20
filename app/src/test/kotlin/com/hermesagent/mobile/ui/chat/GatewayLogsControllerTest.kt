package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.gateway.GatewayLogsResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayLogsControllerTest {
    private val original = GatewayLogsIdentity(1, 2, "session-a", "profile-a")

    @Test fun `no read until confirmation duplicate confirms and reopening loading cannot reread`() = runTest {
        var calls = 0
        val answer = CompletableDeferred<GatewayLogsResult>()
        val controller = GatewayLogsController(this, { original }, { true }) { current ->
            assertTrue(current()); calls++; answer.await()
        }
        controller.open()
        runCurrent()
        assertEquals(0, calls)
        controller.dismiss()
        runCurrent()
        assertEquals(0, calls)
        controller.open()
        val generation = controller.state.value!!.generation
        controller.confirm(generation)
        controller.confirm(generation)
        runCurrent()
        controller.open()
        assertEquals(generation, controller.state.value!!.generation)
        assertEquals(1, calls)
        answer.complete(GatewayLogsResult.Empty)
        runCurrent()
        assertEquals(GatewayLogsResult.Empty, controller.state.value!!.result)
        controller.confirm(generation)
        runCurrent()
        assertEquals(1, calls)
        controller.dismiss()
        assertNull(controller.state.value)
    }

    @Test fun `every identity change and disconnect rejects consent and clears completed content`() = runTest {
        for (changed in listOf(original.copy(endpoint = 3), original.copy(connection = 3),
            original.copy(session = "session-b"), original.copy(profile = "profile-b"),
            original.copy(navigation = 1), original.copy(profileRevision = 1), null)) {
            var identity = original
            var connected = true
            var calls = 0
            val controller = GatewayLogsController(this, { identity }, { connected }) { calls++; GatewayLogsResult.Content("safe", false) }
            controller.open()
            val old = controller.state.value!!.generation
            if (changed == null) connected = false else identity = changed
            controller.confirm(old)
            runCurrent()
            assertEquals(0, calls)
            assertNull(controller.state.value)
            identity = original; connected = true
            controller.open(); controller.confirm(controller.state.value!!.generation); runCurrent()
            assertNotNull(controller.state.value!!.result)
            if (changed == null) connected = false else identity = changed
            controller.invalidateIfChanged()
            assertNull(controller.state.value)
        }
    }

    @Test fun `dismiss reopen and scope changes fence noncancellable late results`() = runTest {
        for (dismiss in listOf(true, false)) {
            var identity = original
            val answer = CompletableDeferred<GatewayLogsResult>()
            var fence: (() -> Boolean)? = null
            val controller = GatewayLogsController(this, { identity }, { true }) { current ->
                fence = current
                answer.await()
            }
            controller.open()
            val old = controller.state.value!!.generation
            controller.confirm(old); runCurrent()
            if (dismiss) controller.dismiss() else {
                identity = original.copy(session = "session-b")
                controller.invalidateIfChanged()
            }
            controller.open()
            val fresh = controller.state.value!!.generation
            controller.confirm(old)
            assertFalse(fence!!())
            answer.complete(GatewayLogsResult.Content("old private excerpt", false))
            runCurrent()
            assertEquals(fresh, controller.state.value!!.generation)
            assertEquals(GatewayLogsState.Phase.Consent, controller.state.value!!.phase)
            assertNull(controller.state.value!!.result)
        }
    }

    @Test fun `dismiss before coroutine launch prevents request and failures require new consent`() = runTest {
        var calls = 0
        val controller = GatewayLogsController(this, { original }, { true }) { calls++; error("private") }
        controller.open(); controller.confirm(controller.state.value!!.generation); controller.dismiss()
        runCurrent()
        assertEquals(0, calls)
        controller.open(); controller.confirm(controller.state.value!!.generation); runCurrent()
        assertEquals(GatewayLogsResult.Failed, controller.state.value!!.result)
        assertEquals(1, calls)
        controller.confirm(controller.state.value!!.generation); runCurrent()
        assertEquals(1, calls)
    }
}
