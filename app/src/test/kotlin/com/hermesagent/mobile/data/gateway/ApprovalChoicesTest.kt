package com.hermesagent.mobile.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Gateway's wire vocabulary, and the three of it a shade can carry. */
class ApprovalChoicesTest {

    @Test
    fun `every offered wire value gets Desktop's own word`() {
        assertEquals("Run", approvalChoiceLabel(APPROVAL_ONCE))
        assertEquals("Allow this session", approvalChoiceLabel(APPROVAL_SESSION))
        assertEquals("Always allow", approvalChoiceLabel(APPROVAL_ALWAYS))
        assertEquals("Reject", approvalChoiceLabel(APPROVAL_DENY))
    }

    /**
     * A newer Gateway offering something this app has never heard of should
     * still be answerable, and the only honest label for it is the one the
     * Gateway sent — not a guess, and not silence.
     */
    @Test
    fun `an unknown choice keeps the word the Gateway sent`() {
        assertEquals("escalate", approvalChoiceLabel("escalate"))
    }

    @Test
    fun `the full offer becomes run, the strongest grant, and the refusal`() {
        assertEquals(
            listOf(APPROVAL_ONCE, APPROVAL_ALWAYS, APPROVAL_DENY),
            shadeApprovalChoices(listOf(APPROVAL_ONCE, APPROVAL_SESSION, APPROVAL_ALWAYS, APPROVAL_DENY)),
        )
    }

    /**
     * `api_server.py:108` @ `72a3277cd7` drops `always` when the endpoint
     * refuses permanent grants, and the session grant takes its place rather
     * than the refusal falling off the end.
     */
    @Test
    fun `without a permanent grant the session grant takes the middle slot`() {
        assertEquals(
            listOf(APPROVAL_ONCE, APPROVAL_SESSION, APPROVAL_DENY),
            shadeApprovalChoices(listOf(APPROVAL_ONCE, APPROVAL_SESSION, APPROVAL_DENY)),
        )
    }

    @Test
    fun `a two-choice offer yields two buttons, not a padded three`() {
        assertEquals(
            listOf(APPROVAL_ONCE, APPROVAL_DENY),
            shadeApprovalChoices(listOf(APPROVAL_ONCE, APPROVAL_DENY)),
        )
    }

    /** Android draws three; supported values are selected before the limit is applied. */
    @Test
    fun `never more than the shade can render`() {
        val offered = listOf(APPROVAL_ONCE, APPROVAL_SESSION, APPROVAL_ALWAYS, APPROVAL_DENY, "escalate")
        assertEquals(MAX_SHADE_ACTIONS, shadeApprovalChoices(offered).size)
    }

    @Test
    fun `an unknown choice is omitted because the shade receiver cannot send it`() {
        assertEquals(
            listOf(APPROVAL_ONCE),
            shadeApprovalChoices(listOf(APPROVAL_ONCE, "escalate")),
        )
    }

    @Test
    fun `only the grants that outlive their request are persistent`() {
        assertTrue(isPersistentGrant(APPROVAL_SESSION))
        assertTrue(isPersistentGrant(APPROVAL_ALWAYS))
        assertFalse(isPersistentGrant(APPROVAL_ONCE))
        assertFalse(isPersistentGrant(APPROVAL_DENY))
        assertFalse(isPersistentGrant("escalate"))
    }

    @Test
    fun `only deny denies`() {
        assertTrue(isDenial(APPROVAL_DENY))
        assertFalse(isDenial(APPROVAL_ONCE))
    }
}
