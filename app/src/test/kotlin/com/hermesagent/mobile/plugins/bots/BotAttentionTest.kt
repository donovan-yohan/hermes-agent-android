package com.hermesagent.mobile.plugins.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The needs-attention classification.
 *
 * The load-bearing assertion is the asymmetry: a rate limit, a 5xx, a timeout
 * or any temporary failure classifies to null and therefore never badges,
 * while the four attention-worthy classes do. Desktop pins this directly
 * (`apps/desktop/src/plugins/hermes-bots/data.ts:72-116` @
 * `564aef2946c436500a5e80ee117b66b789b3f99a`).
 */
class BotAttentionTest {

    // ── transient failures NEVER badge ────────────────────────────────────────

    @Test
    fun `rate limits never badge`() {
        assertNull(attentionReasonFromError("429 Too Many Requests"))
        assertNull(attentionReasonFromError("rate limit exceeded"))
        assertNull(attentionReasonFromError("RateLimitError: slow down"))
        assertNull(attentionReasonFromError("rate-limit hit, retrying"))
        assertNull(attentionReasonFromError("too many requests"))
    }

    @Test
    fun `five hundred class errors never badge`() {
        assertNull(attentionReasonFromError("500 Internal Server Error"))
        assertNull(attentionReasonFromError("502 Bad Gateway"))
        assertNull(attentionReasonFromError("503 Service Unavailable"))
        assertNull(attentionReasonFromError("504 gateway timeout"))
        assertNull(attentionReasonFromError("upstream server error"))
        assertNull(attentionReasonFromError("provider overloaded"))
    }

    @Test
    fun `timeouts never badge`() {
        assertNull(attentionReasonFromError("Request timed out after 30s"))
        assertNull(attentionReasonFromError("timeout"))
        assertNull(attentionReasonFromError("TimedOut"))
        assertNull(attentionReasonFromError("connection timeout"))
    }

    @Test
    fun `temporary failures never badge`() {
        assertNull(attentionReasonFromError("temporary failure, retry shortly"))
        assertNull(attentionReasonFromError("temporarily unavailable"))
    }

    @Test
    fun `a transient signal outranks a badgeable keyword in the same message`() {
        // The transient test runs first, so a message carrying both a retryable
        // signal and, say, a quota keyword reads as transient.
        assertNull(attentionReasonFromError("429 rate limit; quota check skipped"))
        assertNull(attentionReasonFromError("503 server error — billing not consulted"))
    }

    @Test
    fun `an empty or unrecognised message never badges`() {
        assertNull(attentionReasonFromError(null))
        assertNull(attentionReasonFromError(""))
        assertNull(attentionReasonFromError("   "))
        assertNull(attentionReasonFromError("something nobody has classified yet"))
    }

    // ── the four classes do badge ─────────────────────────────────────────────

    @Test
    fun `a reason code is taken at face value`() {
        assertEquals(BotAttentionClass.AgentBlocked, attentionReasonFromError("agent_blocked"))
        assertEquals(
            BotAttentionClass.ProviderAuthOrAccess,
            attentionReasonFromError("provider_auth_or_access"),
        )
        assertEquals(
            BotAttentionClass.ProviderQuotaLimit,
            attentionReasonFromError("provider_quota_limit"),
        )
        assertEquals(BotAttentionClass.MissingConfig, attentionReasonFromError("missing_config"))
    }

    @Test
    fun `auth and access failures badge`() {
        assertEquals(
            BotAttentionClass.ProviderAuthOrAccess,
            attentionReasonFromError("401 Unauthorized"),
        )
        assertEquals(
            BotAttentionClass.ProviderAuthOrAccess,
            attentionReasonFromError("403 Forbidden"),
        )
        assertEquals(
            BotAttentionClass.ProviderAuthOrAccess,
            attentionReasonFromError("invalid API key"),
        )
        assertEquals(
            BotAttentionClass.ProviderAuthOrAccess,
            attentionReasonFromError("credentials are invalid"),
        )
    }

    @Test
    fun `missing configuration badges`() {
        assertEquals(
            BotAttentionClass.MissingConfig,
            attentionReasonFromError("no llm provider selected"),
        )
        assertEquals(
            BotAttentionClass.MissingConfig,
            attentionReasonFromError("provider not configured"),
        )
        assertEquals(
            BotAttentionClass.MissingConfig,
            attentionReasonFromError("missing api key"),
        )
    }

    @Test
    fun `quota and billing exhaustion badges`() {
        assertEquals(
            BotAttentionClass.ProviderQuotaLimit,
            attentionReasonFromError("quota exhausted"),
        )
        assertEquals(
            BotAttentionClass.ProviderQuotaLimit,
            attentionReasonFromError("out of funds"),
        )
        assertEquals(
            BotAttentionClass.ProviderQuotaLimit,
            attentionReasonFromError("402 Payment Required"),
        )
        assertEquals(
            BotAttentionClass.ProviderQuotaLimit,
            attentionReasonFromError("insufficient credits"),
        )
    }

    @Test
    fun `a blocked agent badges`() {
        assertEquals(
            BotAttentionClass.AgentBlocked,
            attentionReasonFromError("Bot is blocked — see its last message"),
        )
    }

    @Test
    fun `every class has a hint`() {
        assertEquals(BotAttentionClass.entries.size, BOT_ATTENTION_HINTS.size)
        for (class_ in BotAttentionClass.entries) {
            assertNotNull(class_.wireName, BOT_ATTENTION_HINTS[class_])
        }
    }

    // ── the store ─────────────────────────────────────────────────────────────

    @Test
    fun `a transient failure writes nothing to the store`() {
        val store = BotAttentionStore { 1_000L }

        store.note("legacy::default", "429 Too Many Requests")
        store.note("legacy::default", "timeout")
        store.note("legacy::default", "503 Service Unavailable")

        assertEquals(emptyMap<String, BotAttention>(), store.entries.value)
    }

    @Test
    fun `the latest classified failure wins`() {
        val store = BotAttentionStore { 42L }

        store.note("legacy::researcher", "401 Unauthorized")
        store.note("legacy::researcher", "quota exhausted")

        val entry = store.forKey("legacy::researcher")
        assertNotNull(entry)
        assertEquals(BotAttentionClass.ProviderQuotaLimit, entry!!.reason)
        assertEquals(42L, entry.at)
    }

    @Test
    fun `a good turn clears the badge`() {
        val store = BotAttentionStore { 7L }

        store.note("legacy::writer", "no api key")
        assertNotNull(store.forKey("legacy::writer"))

        store.clear("legacy::writer")
        assertNull(store.forKey("legacy::writer"))
    }

    @Test
    fun `an unclassified failure leaves an existing badge alone`() {
        val store = BotAttentionStore { 7L }

        store.note("legacy::writer", "no api key")
        store.note("legacy::writer", "429 Too Many Requests")

        assertNotNull(store.forKey("legacy::writer"))
    }

    @Test
    fun `the stored message is truncated`() {
        val store = BotAttentionStore { 7L }
        val long = "unauthorized " + "x".repeat(500)

        store.note("legacy::writer", long)

        val entry = store.forKey("legacy::writer")
        assertNotNull(entry)
        assertEquals(BotsRosterLimits.ATTENTION_MESSAGE_LIMIT, entry!!.message.length)
    }

    @Test
    fun `an empty key is never recorded`() {
        val store = BotAttentionStore { 7L }

        store.note("", "401 Unauthorized")

        assertEquals(emptyMap<String, BotAttention>(), store.entries.value)
    }
}
