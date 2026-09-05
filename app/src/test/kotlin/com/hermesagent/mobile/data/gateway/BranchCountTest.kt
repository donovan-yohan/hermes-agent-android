package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.TranscriptRowId
import com.hermesagent.mobile.data.session.UserTurn
import org.junit.Assert.assertEquals
import org.junit.Test

class BranchCountTest {

    @Test
    fun `rowId match`() {
        val localTarget = AssistantTurn("local-target", "response", 100, rowId = TranscriptRowId(2))
        val local = listOf(
            UserTurn("u1", "hello", 90, rowId = TranscriptRowId(1)),
            localTarget
        )
        val auth = listOf(
            UserTurn("auth-u1", "hello", 90, rowId = TranscriptRowId(1)),
            AssistantTurn("auth-a1", "response", 100, rowId = TranscriptRowId(2)),
            UserTurn("auth-u2", "more", 110, rowId = TranscriptRowId(3))
        )

        val count = deriveBranchCount(local, auth, "local-target")
        assertEquals(BranchPlan.Keep(2), count)
    }

    @Test
    fun `text ordinal fallback`() {
        val local = listOf(
            UserTurn("u1", "hello", 90),
            AssistantTurn("a1", "response", 100),
            UserTurn("u2", "hello again", 110),
            AssistantTurn("a2", "response", 120),
            UserTurn("u3", "bye", 130)
        )
        val auth = listOf(
            UserTurn("auth-u1", "hello", 90),
            AssistantTurn("auth-a1", "response", 100),
            UserTurn("auth-u2", "hello again", 110),
            AssistantTurn("auth-a2", "response", 120),
            UserTurn("auth-u3", "bye", 130),
            AssistantTurn("auth-a3", "final", 140)
        )

        val count = deriveBranchCount(local, auth, "a2")
        assertEquals(BranchPlan.Keep(4), count)
    }

    @Test
    fun `last message yields null count`() {
        val localTarget = AssistantTurn("local-target", "response", 100)
        val local = listOf(UserTurn("u1", "hello", 90), localTarget)
        val auth = listOf(UserTurn("auth-u1", "hello", 90), AssistantTurn("auth-a1", "response", 100))

        val count = deriveBranchCount(local, auth, "local-target")
        assertEquals(BranchPlan.Whole, count)
    }

    @Test
    fun `unlocatable or blank yields unlocatable plan`() {
        val localTarget = AssistantTurn("local-target", "  ", 100) // blank
        val local = listOf(UserTurn("u1", "hello", 90), localTarget)
        val auth = listOf(UserTurn("auth-u1", "hello", 90), AssistantTurn("auth-a1", "  ", 100))

        val count = deriveBranchCount(local, auth, "local-target")
        assertEquals(BranchPlan.Unlocatable, count)
    }
}
