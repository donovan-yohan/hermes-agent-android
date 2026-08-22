package com.hermesagent.mobile.ui

import com.hermesagent.mobile.data.voice.TranscriptionResult
import com.hermesagent.mobile.data.voice.VoiceConversationController
import com.hermesagent.mobile.data.voice.VoiceUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration journey: the conversation controller drives capture,
 * transcription, submit and speak through their seams. The host feeds a
 * completed reply on Speaking, proving the full listen -> transcribe ->
 * think -> speak -> re-arm loop without hardware.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceConversationJourneyTest {

    @Test
    fun `conversation completes the full loop`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val phases = mutableListOf<VoiceUiState.ConversationPhase>()
        val submitted = mutableListOf<String>()
        val spoken = mutableListOf<String>()

        val controller = VoiceConversationController(
            scope = scope,
            stopPhrases = { listOf("stop") },
            startCapture = { true },
            stopCapture = { },
            transcribe = { TranscriptionResult.Transcript("what is rust") },
            submitTurn = { text -> submitted += text; true },
            interruptTurn = { true },
            speak = { spoken += it },
            stopPlayback = { },
            onStateChange = { state -> phases.add(state.phase) },
        )
        controller.beginCycle()

        // Feed a reply each time Speaking is reached; two Listening visits
        // prove the loop re-armed after normal playback drain.
        var repliesFed = 0
        var listeningVisits = 0
        var guard = 0
        while (listeningVisits < 2 && guard < 20) {
                advanceTimeBy(VoicePolicyConstants.LISTEN_CAP)
            runCurrent()
            listeningVisits = controller.visitCount
            if (controller.currentState.phase == VoiceUiState.ConversationPhase.Thinking && repliesFed == 0) {
                repliesFed++
                controller.onReplyReady(controller.operationFence(), "Rust is a language.")
            }
            guard++
        }

        assertTrue(submitted.contains("what is rust"))
        assertEquals(listOf("Rust is a language."), spoken)
        assertTrue(phases.containsAll(
            listOf(
                VoiceUiState.ConversationPhase.Listening,
                VoiceUiState.ConversationPhase.Transcribing,
                VoiceUiState.ConversationPhase.Thinking,
                VoiceUiState.ConversationPhase.Speaking,
            ),
        ))
    }

    @Test
    fun `stop phrase ends without submitting`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val submitted = mutableListOf<String>()
        val finalPhases = mutableListOf<VoiceUiState.ConversationPhase>()

        val controller = VoiceConversationController(
            scope = scope,
            stopPhrases = { listOf("stop") },
            startCapture = { true },
            stopCapture = { },
            transcribe = { TranscriptionResult.Transcript("stop") },
            submitTurn = { text -> submitted += text; true },
            interruptTurn = { true },
            speak = { },
            stopPlayback = { },
            onStateChange = { state -> if (finalPhases.lastOrNull() != state.phase) finalPhases.add(state.phase) },
        )
        controller.beginCycle()
        advanceTimeBy(com.hermesagent.mobile.data.voice.VoicePolicy.CONVERSATION_LISTEN_CAP_MILLIS + 1_000)
        runCurrent()

        assertTrue(submitted.isEmpty())
        assertTrue(finalPhases.contains(VoiceUiState.ConversationPhase.Ended))
    }
}

private object VoicePolicyConstants {
    const val LISTEN_CAP = 60_000L
}
