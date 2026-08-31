package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential probe the connection switch short-circuits on (#116 S-U4).
 *
 * Its two answers are load-bearing in opposite directions: `false` skips a dial
 * the app would otherwise wait twenty seconds on, and `true` is what every
 * uncertain case has to degrade to, because a probe that guessed "empty" would
 * suppress a sign-in that was going to work. The Keystore misfire is the case
 * with no other coverage — nothing else in the app reads a slot purely to find
 * out whether it is there.
 */
class StoredGatewayCredentialsTest {

    @Test
    fun `a slot the store answers is a credential this app can present`() = runTest {
        val asked = mutableListOf<GatewaySecretSlot>()
        val subject = StoredGatewayCredentials(
            store(onLoad = { slot -> asked += slot; TOKENS }),
        )

        assertTrue(subject.hasCredential(SLOT))
        assertEquals("the probe reads exactly the slot it was handed", listOf(SLOT), asked)
    }

    @Test
    fun `an empty or refused slot reads as no credential`() = runTest {
        // One `null` covers both: the store refuses a blob minted by another
        // Gateway by returning nothing, exactly as it does for a slot that was
        // never written (`AndroidGatewayTokenStore.load`).
        val subject = StoredGatewayCredentials(store(onLoad = { null }))

        assertFalse(subject.hasCredential(SLOT))
    }

    @Test
    fun `a Keystore that cannot be read is not evidence the slot is empty`() = runTest {
        val subject = StoredGatewayCredentials(
            store(onLoad = { throw IllegalStateException("alias invalidated") }),
        )

        assertTrue(
            "an unreadable Keystore must fail open, or a working sign-in is skipped",
            subject.hasCredential(SLOT),
        )
    }

    @Test
    fun `a cancelled probe is not a failed read`() = runTest {
        val subject = StoredGatewayCredentials(
            store(onLoad = { throw CancellationException("screen went away") }),
        )

        var cancelled = false
        try {
            subject.hasCredential(SLOT)
        } catch (expected: CancellationException) {
            cancelled = true
        }
        assertTrue("cancellation must propagate rather than read as a stored credential", cancelled)
    }

    private fun store(onLoad: (GatewaySecretSlot) -> GatewayNativeTokens?) = object : GatewayTokenStore {
        override suspend fun load(slot: GatewaySecretSlot): GatewayNativeTokens? = onLoad(slot)

        override suspend fun save(slot: GatewaySecretSlot, tokens: GatewayNativeTokens) =
            throw AssertionError("the probe must never write")

        override suspend fun clear(slot: GatewaySecretSlot) =
            throw AssertionError("the probe must never erase")
    }

    private companion object {
        val SLOT = GatewaySecretSlot("row-one", "https://alpha.test")

        /** No real credential material: the probe only ever asks whether one is there. */
        val TOKENS = GatewayNativeTokens(
            accessToken = "fixture-access",
            refreshToken = "fixture-refresh",
            expiresAt = 0L,
            provider = "fixture",
            userId = "fixture-user",
        )
    }
}
