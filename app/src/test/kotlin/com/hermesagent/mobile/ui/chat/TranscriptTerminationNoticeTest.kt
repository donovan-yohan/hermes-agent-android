package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.session.TurnTermination
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTerminationNoticeTest {
    @Test
    fun `only a local stop names the user`() {
        assertEquals("Stopped by you", terminationNotice(TurnTermination.UserRequested))
        listOf(
            TurnTermination.WsOrphanReap,
            TurnTermination.IdleTimeout,
            TurnTermination.LruEvict,
            TurnTermination.Reclaimed,
            TurnTermination.SessionNoLongerRunning,
            TurnTermination.InterruptedExternally,
        ).forEach { termination ->
            assertEquals("The Gateway ended this turn. You can try again.", terminationNotice(termination))
        }
    }
}
