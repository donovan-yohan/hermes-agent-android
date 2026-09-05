package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.ToolCall
import com.hermesagent.mobile.data.session.TranscriptRowId
import com.hermesagent.mobile.data.session.UserTurn
import org.junit.Assert.assertEquals
import org.junit.Test


class RegeneratePlanTest {
    @Test
    fun `newest reply with preceding user turn returns Ready`() {
        val transcript = listOf(
            UserTurn(id = "1", text = "hello", atMillis = 1000L, rowId = TranscriptRowId(1L)),
            AssistantTurn(id = "2", markdown = "hi", atMillis = 1000L, rowId = TranscriptRowId(2L))
        )
        
        val plan = planRegenerate(transcript, "2")
        assertEquals(RegeneratePlan.Ready("hello", "1", TranscriptRowId(1L), true), plan)
    }
    
    @Test
    fun `older reply returns NotNewest`() {
        val transcript = listOf(
            UserTurn(id = "1", text = "hello", atMillis = 1000L, rowId = TranscriptRowId(1L)),
            AssistantTurn(id = "2", markdown = "hi", atMillis = 1000L, rowId = TranscriptRowId(2L)),
            UserTurn(id = "3", text = "next", atMillis = 1000L, rowId = TranscriptRowId(3L)),
            AssistantTurn(id = "4", markdown = "next reply", atMillis = 1000L, rowId = TranscriptRowId(4L))
        )
        
        val plan = planRegenerate(transcript, "2")
        assertEquals(RegeneratePlan.NotNewest, plan)
    }

    @Test
    fun `no preceding user turn returns NoSource`() {
        val transcript = listOf(
            AssistantTurn(id = "1", markdown = "hi", atMillis = 1000L, rowId = TranscriptRowId(1L))
        )
        
        val plan = planRegenerate(transcript, "1")
        assertEquals(RegeneratePlan.NoSource, plan)
    }
    
    @Test
    fun `blank text returns NoSource`() {
        val transcript = listOf(
            UserTurn(id = "1", text = "   ", atMillis = 1000L, rowId = TranscriptRowId(1L)),
            AssistantTurn(id = "2", markdown = "hi", atMillis = 1000L, rowId = TranscriptRowId(2L))
        )
        
        val plan = planRegenerate(transcript, "2")
        assertEquals(RegeneratePlan.NoSource, plan)
    }
    
    @Test
    fun `tool entries between user turn and reply are skipped`() {
        val transcript = listOf(
            UserTurn(id = "1", text = "hello", atMillis = 1000L, rowId = TranscriptRowId(1L)),
            ToolCall(id = "tool", functionName = "foo", argsJson = "{}", status = ToolCall.Status.Done, atMillis = 1000L),
            AssistantTurn(id = "2", markdown = "hi", atMillis = 1000L, rowId = TranscriptRowId(2L))
        )
        
        val plan = planRegenerate(transcript, "2")
        assertEquals(RegeneratePlan.Ready("hello", "1", TranscriptRowId(1L), true), plan)
    }
}
