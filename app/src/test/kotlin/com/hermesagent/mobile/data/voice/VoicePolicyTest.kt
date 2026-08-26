package com.hermesagent.mobile.data.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePolicyTest {
    private companion object {
        const val STOP_NOTICE = """Say "stop" to end the voice chat."""
    }

    private fun config(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun noticeFor(raw: String): String? =
        VoicePolicy.stopNoticeFor(VoicePolicy.stopPhrasesFromConfig(config(raw)))

    @Test
    fun `wire budget accounts for base64 expansion`() {
        val raw = 3_000_000
        val budget = VoicePolicy.wireBudgetBytes(raw)
        assertEquals(3_000_000 / 3 * 4 + 4096, budget)
        assertTrue(budget > raw)
    }

    @Test
    fun `audio timeout scales with size and stays capped`() {
        assertTrue(VoicePolicy.audioTimeoutMillis(0) == VoicePolicy.AUDIO_TIMEOUT_BASE_MILLIS)
        assertTrue(VoicePolicy.audioTimeoutMillis(Int.MAX_VALUE) == VoicePolicy.AUDIO_TIMEOUT_MAX_MILLIS)
    }

    @Test
    fun `stop phrase matches whole utterance only`() {
        val phrases = listOf("stop listening", "end voice")
        assertTrue(VoicePolicy.isStopUtterance("Stop listening", phrases))
        assertTrue(VoicePolicy.isStopUtterance("  stop   listening ", phrases))
        assertTrue(VoicePolicy.isStopUtterance("end voice", phrases))
        // Substantive speech containing stop words stays a message.
        assertFalse(VoicePolicy.isStopUtterance("stop the container", phrases))
        assertFalse(VoicePolicy.isStopUtterance("please stop listening to me now", phrases))
        assertFalse(VoicePolicy.isStopUtterance("", phrases))
    }

    // ── S19 acceptance quartet: the four cases named in issue #67 ──────────

    @Test
    fun `a bare stop ends the conversation`() {
        assertTrue(VoicePolicy.isStopUtterance("stop", VoicePolicy.DEFAULT_STOP_PHRASES))
    }

    @Test
    fun `a stop with trailing punctuation ends the conversation`() {
        assertTrue(VoicePolicy.isStopUtterance("stop.", VoicePolicy.DEFAULT_STOP_PHRASES))
    }

    @Test
    fun `a stop addressed to Hermes ends the conversation`() {
        assertTrue(VoicePolicy.isStopUtterance("hey hermes, stop", VoicePolicy.DEFAULT_STOP_PHRASES))
    }

    @Test
    fun `stop the docker container is submitted as a turn`() {
        assertFalse(VoicePolicy.isStopUtterance("stop the docker container", VoicePolicy.DEFAULT_STOP_PHRASES))
    }

    // ── Desktop voice-stop-word.test.ts:5-86, ported test-for-test ─────────

    @Test
    fun `matches bare stop commands`() {
        for (phrase in listOf("stop", "Stop", "STOP", "stop.", "stop!", " stop ", "stop…")) {
            assertTrue(phrase, VoicePolicy.isStopUtterance(phrase, VoicePolicy.DEFAULT_STOP_PHRASES))
        }
    }

    @Test
    fun `matches multi-word stop phrases`() {
        val phrases = listOf(
            "stop listening",
            "stop it",
            "please stop",
            "stop please",
            "that's all",
            "that is all",
            "never mind",
            "nevermind",
            "end conversation",
            "end the conversation",
            "goodbye",
            "bye",
            "cancel",
        )
        for (phrase in phrases) {
            assertTrue(phrase, VoicePolicy.isStopUtterance(phrase, VoicePolicy.DEFAULT_STOP_PHRASES))
        }
    }

    @Test
    fun `matches stop commands addressed to Hermes`() {
        for (phrase in listOf("hermes stop", "hey hermes stop", "hey hermes, stop", "ok stop", "okay stop")) {
            assertTrue(phrase, VoicePolicy.isStopUtterance(phrase, VoicePolicy.DEFAULT_STOP_PHRASES))
        }
    }

    @Test
    fun `does NOT match substantive requests that merely contain stop`() {
        val phrases = listOf(
            "stop the docker container",
            "how do I stop a running process",
            "can you stop the deployment",
            "stop the music and play something else",
            "don't stop now",
            "the bus stop is closed",
        )
        for (phrase in phrases) {
            assertFalse(phrase, VoicePolicy.isStopUtterance(phrase, VoicePolicy.DEFAULT_STOP_PHRASES))
        }
    }

    @Test
    fun `does not match bare address words or empty input`() {
        for (phrase in listOf("", "  ", "hermes", "hey hermes", "ok", "okay", "hey")) {
            assertFalse(phrase, VoicePolicy.isStopUtterance(phrase, VoicePolicy.DEFAULT_STOP_PHRASES))
        }
    }

    @Test
    fun `does not match unrelated short utterances`() {
        for (phrase in listOf("hello", "yes", "what time is it", "thanks")) {
            assertFalse(phrase, VoicePolicy.isStopUtterance(phrase, VoicePolicy.DEFAULT_STOP_PHRASES))
        }
    }

    @Test
    fun `intercepts a typed bare stop command while the conversation is active`() {
        for (text in listOf("stop", "Stop.", "never mind", "hey hermes, stop")) {
            assertTrue(text, VoicePolicy.interceptsTypedStop(true, text, VoicePolicy.DEFAULT_STOP_PHRASES))
        }
    }

    @Test
    fun `never intercepts when the voice conversation is inactive`() {
        for (text in listOf("stop", "never mind", "goodbye")) {
            assertFalse(text, VoicePolicy.interceptsTypedStop(false, text, VoicePolicy.DEFAULT_STOP_PHRASES))
        }
    }

    @Test
    fun `passes through substantive messages during a conversation`() {
        for (text in listOf("stop the docker container", "how do I stop a process", "hello")) {
            assertFalse(text, VoicePolicy.interceptsTypedStop(true, text, VoicePolicy.DEFAULT_STOP_PHRASES))
        }
    }

    @Test
    fun `passes through when attachments ride along`() {
        assertFalse(VoicePolicy.interceptsTypedStop(true, "stop", VoicePolicy.DEFAULT_STOP_PHRASES, 1))
    }

    // ── Phrase list resolution from voice stop_phrases ─────────────────────

    @Test
    fun `a failed config read falls back to the embedded default`() {
        assertEquals(VoicePolicy.DEFAULT_STOP_PHRASES, VoicePolicy.stopPhrasesFromConfig(null))
        assertEquals(VoicePolicy.DEFAULT_STOP_PHRASES, VoicePolicy.stopPhrasesOrDefault(null))
        assertEquals("stop", VoicePolicy.DEFAULT_STOP_PHRASES.first())
    }

    @Test
    fun `a config without the key falls back to the embedded default`() {
        assertEquals(VoicePolicy.DEFAULT_STOP_PHRASES, VoicePolicy.stopPhrasesFromConfig(config("""{}""")))
        assertEquals(
            VoicePolicy.DEFAULT_STOP_PHRASES,
            VoicePolicy.stopPhrasesFromConfig(config("""{"voice":{"auto_tts":true}}""")),
        )
    }

    @Test
    fun `the phrase list comes from voice stop_phrases`() {
        val phrases = VoicePolicy.stopPhrasesFromConfig(
            config("""{"voice":{"stop_phrases":["  that will do  ","","end voice"]}}"""),
        )
        assertEquals(listOf("that will do", "end voice"), phrases)
        assertTrue(VoicePolicy.isStopUtterance("That will do.", phrases))
        assertTrue(VoicePolicy.isStopUtterance("hey hermes, end voice", phrases))
        // A configured list is the whole authority: an unlisted default is out.
        assertFalse(VoicePolicy.isStopUtterance("goodbye", phrases))
    }

    @Test
    fun `an empty phrase list disables matching and suppresses the start notice`() {
        val phrases = VoicePolicy.stopPhrasesFromConfig(config("""{"voice":{"stop_phrases":[]}}"""))
        assertEquals(emptyList<String>(), phrases)
        assertFalse(VoicePolicy.isStopUtterance("stop", phrases))
        assertFalse(VoicePolicy.interceptsTypedStop(true, "stop", phrases))
        assertNull(VoicePolicy.stopNoticeFor(phrases))
        assertNull(VoiceStartNotice { phrases }.onConversationStarted())
    }

    @Test
    fun `malformed stop_phrases falls back to the embedded default`() {
        // tools/voice_mode.py:1270-1291: a scalar, null or object never reaches
        // the list branch, so the loader returns the default rather than
        // silently disabling spoken stop.
        for (raw in listOf("true", "7", "null", """{"first":"stop"}""")) {
            assertEquals(
                raw,
                VoicePolicy.DEFAULT_STOP_PHRASES,
                VoicePolicy.stopPhrasesFromConfig(config("""{"voice":{"stop_phrases":$raw}}""")),
            )
        }
    }

    @Test
    fun `a bare string stop_phrases is coerced like the backend loader`() {
        assertEquals(
            listOf("that will do"),
            VoicePolicy.stopPhrasesFromConfig(config("""{"voice":{"stop_phrases":"that will do"}}""")),
        )
        // A blank string survives coercion but not the blank filter, so it
        // disables — the backend's str().strip() falsy check.
        assertEquals(
            emptyList<String>(),
            VoicePolicy.stopPhrasesFromConfig(config("""{"voice":{"stop_phrases":"   "}}""")),
        )
    }

    @Test
    fun `a list whose entries are all non-phrases disables like the backend loader`() {
        // A list IS the list branch, so what survives filtering is authoritative
        // even when nothing survives — unlike a malformed scalar.
        assertEquals(
            emptyList<String>(),
            VoicePolicy.stopPhrasesFromConfig(config("""{"voice":{"stop_phrases":[{},[],null]}}""")),
        )
    }

    // ── Start-notice contract ─────────────────────────────────────────────

    @Test
    fun `the start notice names the first configured phrase verbatim`() {
        assertEquals(STOP_NOTICE, VoicePolicy.stopNotice("stop"))
        assertEquals(
            """Say "that will do" to end the voice chat.""",
            VoicePolicy.stopNoticeFor(listOf("that will do", "end voice")),
        )
    }

    // ── Desktop store/voice-prefs.test.ts:10-38, ported test-for-test ─────

    @Test
    fun `defaults to stop when the key is absent - backend default applies`() {
        assertEquals(STOP_NOTICE, noticeFor("""{"voice":{}}"""))
        assertEquals(STOP_NOTICE, VoicePolicy.stopNoticeFor(VoicePolicy.stopPhrasesFromConfig(null)))
    }

    @Test
    fun `uses the first configured phrase so a custom phrase renders correctly`() {
        assertEquals(
            """Say "goodbye hermes" to end the voice chat.""",
            noticeFor("""{"voice":{"stop_phrases":["goodbye hermes","stop"]}}"""),
        )
    }

    @Test
    fun `coerces a bare string like the backend does`() {
        assertEquals(
            """Say "halt" to end the voice chat.""",
            noticeFor("""{"voice":{"stop_phrases":"halt"}}"""),
        )
    }

    @Test
    fun `null phrase when stop phrases are disabled - no notice is shown`() {
        assertNull(noticeFor("""{"voice":{"stop_phrases":[]}}"""))
    }

    @Test
    fun `malformed entries are skipped - all-blank list disables`() {
        assertNull(noticeFor("""{"voice":{"stop_phrases":["  ",""]}}"""))
    }

    @Test
    fun `a conversation that starts before the phrase list resolves keeps its notice`() {
        var phrases = emptyList<String>()
        val notice = VoiceStartNotice { phrases }

        // Config has not landed yet: no notice, and the one announcement is
        // not spent on it.
        assertNull(notice.onConversationStarted())

        phrases = VoicePolicy.DEFAULT_STOP_PHRASES
        assertEquals(STOP_NOTICE, notice.onConversationStarted())
        assertNull(notice.onConversationStarted())
    }

    @Test
    fun `only one concurrent start announces the notice`() {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(25) {
                val notice = VoiceStartNotice { VoicePolicy.DEFAULT_STOP_PHRASES }
                val start = CyclicBarrier(threads)
                val announced = AtomicInteger(0)
                val done = CountDownLatch(threads)
                repeat(threads) {
                    pool.execute {
                        start.await()
                        if (notice.onConversationStarted() != null) announced.incrementAndGet()
                        done.countDown()
                    }
                }
                assertTrue(done.await(5, TimeUnit.SECONDS))
                assertEquals(1, announced.get())
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the start notice is produced exactly once per conversation`() {
        val notice = VoiceStartNotice { VoicePolicy.DEFAULT_STOP_PHRASES }

        assertEquals(STOP_NOTICE, notice.onConversationStarted())
        assertNull("a re-armed listen cycle must not re-announce", notice.onConversationStarted())
        assertNull(notice.onConversationStarted())

        notice.onConversationEnded()
        assertEquals(STOP_NOTICE, notice.onConversationStarted())
        assertNull(notice.onConversationStarted())
    }
}
