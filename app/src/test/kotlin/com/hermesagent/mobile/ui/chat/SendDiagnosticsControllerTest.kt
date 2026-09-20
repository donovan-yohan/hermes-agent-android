package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.gateway.DiagnosticsResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SendDiagnosticsControllerTest {
    @Test fun `opening and cancel never upload and duplicate confirm sends once`() = runTest {
        var calls = 0
        var sends = 0
        var uploadedContext = ""
        val answer = CompletableDeferred<DiagnosticsResult>()
        val controller = SendDiagnosticsController(this, { 0L }, { 0L }, { true }) { text, endpoint, dispatch ->
            calls++
            uploadedContext = text
            assertEquals(0L, endpoint)
            assertTrue(dispatch { sends++; true })
            assertFalse(dispatch { sends++; true })
            answer.await()
        }
        controller.open("password=synthetic-secret\n" + "x".repeat(9_000))
        runCurrent()
        assertEquals(0, calls)
        controller.dismiss()
        runCurrent()
        assertNull(controller.state.value)
        assertEquals(0, calls)
        controller.open("password=synthetic-secret\n" + "x".repeat(9_000))
        val generation = controller.state.value!!.generation
        controller.confirm(generation)
        controller.confirm(generation)
        runCurrent()
        controller.open("different error")
        assertEquals(generation, controller.state.value!!.generation)
        assertEquals(SendDiagnosticsState.Phase.Uploading, controller.state.value!!.phase)
        assertEquals(1, calls)
        assertEquals(1, sends)
        assertFalse(uploadedContext.contains("synthetic-secret"))
        assertTrue(uploadedContext.length <= 4_096)
        answer.complete(DiagnosticsResult.Uploaded(null, "upload-1", null))
        runCurrent()
        assertEquals(SendDiagnosticsState.Phase.Finished, controller.state.value!!.phase)
    }

    @Test fun `dismiss reopen fences both pending dispatch and stale completion`() = runTest {
        val beforeSend = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<DiagnosticsResult>()
        var sent = 0
        val controller = SendDiagnosticsController(this, { 0L }, { 0L }, { true }) { _, _, dispatch ->
            beforeSend.await()
            dispatch { sent++; true }
            answer.await()
        }
        controller.open("first")
        val first = controller.state.value!!.generation
        controller.confirm(first)
        runCurrent()
        controller.dismiss()
        controller.open("second")
        val second = controller.state.value!!.generation
        controller.confirm(first) // a callback retained from the old modal is not new consent
        beforeSend.complete(Unit)
        answer.complete(DiagnosticsResult.Failed)
        runCurrent()
        assertEquals(0, sent)
        assertEquals(second, controller.state.value!!.generation)
        assertEquals(SendDiagnosticsState.Phase.Consent, controller.state.value!!.phase)
    }

    @Test fun `endpoint or connection changes reject consent and old dispatch`() = runTest {
        var endpoint = 0L
        var connection = 0L
        var calls = 0
        var sends = 0
        val beforeSend = CompletableDeferred<Unit>()
        val controller = SendDiagnosticsController(this, { endpoint }, { connection }, { true }) { _, _, dispatch ->
            calls++
            beforeSend.await()
            dispatch { sends++; true }
            DiagnosticsResult.Failed
        }
        controller.open("old endpoint")
        val first = controller.state.value!!.generation
        endpoint++
        controller.confirm(first)
        runCurrent()
        assertNull(controller.state.value)
        assertEquals(0, calls)
        controller.open("new endpoint")
        controller.confirm(controller.state.value!!.generation)
        runCurrent()
        connection++
        controller.invalidateIfChanged()
        beforeSend.complete(Unit)
        runCurrent()
        assertEquals(0, sends)
        assertNull(controller.state.value)
    }

    @Test fun `unsupported and failures require a fresh consent with no automatic retry`() = runTest {
        var calls = 0
        val controller = SendDiagnosticsController(this, { 0L }, { 0L }, { true }) { _, _, _ ->
            calls++
            if (calls == 1) DiagnosticsResult.Unsupported else error("password=synthetic-secret")
        }
        controller.open("error")
        controller.confirm(controller.state.value!!.generation)
        runCurrent()
        assertEquals(DiagnosticsResult.Unsupported, controller.state.value!!.result)
        controller.confirm(controller.state.value!!.generation)
        runCurrent()
        assertEquals(1, calls)
        controller.dismiss()
        controller.open("error")
        assertEquals(1, calls)
        controller.confirm(controller.state.value!!.generation)
        runCurrent()
        assertEquals(DiagnosticsResult.Failed, controller.state.value!!.result)
        assertFalse(controller.state.value.toString().contains("synthetic-secret"))
    }
}
