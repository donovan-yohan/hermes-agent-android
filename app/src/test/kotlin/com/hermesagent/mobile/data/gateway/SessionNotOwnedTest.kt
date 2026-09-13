package com.hermesagent.mobile.data.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live-owner refusal, classified the way upstream classifies it.
 *
 * `c80003ff57` @ `564aef2946` deleted Desktop's own sentence matcher because
 * it "would silently miss a reworded or localized message". These assert the
 * order this app kept: reason code first, prose only where there is no code.
 */
class SessionNotOwnedTest {

    @Test
    fun `the reason code is what decides`() {
        assertTrue(GatewayRpcError(4090, "anything at all", SESSION_NOT_OWNED_REASON).isSessionNotOwned())
    }

    /**
     * The case the prose matcher would have missed: the Gateway reworded its
     * sentence, or shipped it in another language, and the contract did not
     * move.
     */
    @Test
    fun `a reworded message with the reason code still classifies`() {
        val localized = GatewayRpcError(4090, "Une autre session détient ce verrou.", "SESSION_NOT_OWNED")

        assertTrue(localized.isSessionNotOwned())
    }

    /**
     * The opposite mistake, and the one that matters more: *busy* is the same
     * code with the opposite advice. Busy means wait or interrupt; this means
     * waiting will not help.
     */
    @Test
    fun `the busy refusal is not this refusal`() {
        val busy = GatewayRpcError(4090, "Hermes is already working in this session.", "SESSION_BUSY")

        assertFalse(busy.isSessionNotOwned())
    }

    /** A backend that predates the contract still has to be understood. */
    @Test
    fun `prose is the fallback only where no reason came`() {
        assertTrue(GatewayRpcError(4090, "Session is not owned by this client.").isSessionNotOwned())
        assertFalse(GatewayRpcError(4090, "Hermes is already working in this session.").isSessionNotOwned())
    }

    /**
     * A reason that came and said something else closes the question. Falling
     * through to prose there would re-introduce exactly the matcher upstream
     * removed.
     */
    @Test
    fun `a reason that says something else is not overridden by prose`() {
        val other = GatewayRpcError(4090, "Session is not owned by this client.", "SESSION_BUSY")

        assertFalse(other.isSessionNotOwned())
    }

    @Test
    fun `another code is not this refusal, whatever it says`() {
        assertFalse(GatewayRpcError(4009, "Session is not owned by this client.").isSessionNotOwned())
        assertFalse(GatewayRpcException("The gateway connection is closed.").isSessionNotOwned())
    }
}
