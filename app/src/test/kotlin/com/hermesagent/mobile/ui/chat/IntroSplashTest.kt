package com.hermesagent.mobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The intro splash's two decisions, offline: which line it draws, and whether
 * it draws at all.
 *
 * The copy set is pinned here rather than merely spot-checked, because it is
 * Desktop's own and the whole point of the port is that it stays Desktop's.
 * Every string below is a `personality: "none"` record of
 * `apps/desktop/src/components/chat/intro-copy.jsonl:71-75` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`, in file order.
 */
class IntroSplashTest {

    @Test
    fun `the neutral copy set is Desktop's, verbatim and in file order`() {
        assertEquals(
            listOf(
                "Ask a question, paste an error, or point me at a repo. I can read code, run tools, and help you ship.",
                "Describe the task in your own words. I'll pick the right tools, explain my plan, and check in before risky steps.",
                "Drop a file path, a traceback, or a rough idea. I'll investigate, suggest next steps, and keep things reversible.",
                "Search the repo, edit files, run tests, open PRs. Tell me the goal and I'll handle the mechanical parts.",
                "Type a task, question, or snippet. I remember the session, cite my sources, and stop to ask when I'm unsure.",
            ),
            NEUTRAL_INTRO_COPY,
        )
    }

    @Test
    fun `the wordmark is Desktop's`() {
        assertEquals("HERMES AGENT", INTRO_WORDMARK)
    }

    @Test
    fun `a seed picks one line and the same seed picks it again`() {
        assertEquals(NEUTRAL_INTRO_COPY[0], pickIntroCopy(0))
        assertEquals(NEUTRAL_INTRO_COPY[3], pickIntroCopy(3))
        assertEquals(pickIntroCopy(4_213), pickIntroCopy(4_213))
    }

    @Test
    fun `a seed past the end of the set wraps, as Desktop's modulo does`() {
        assertEquals(NEUTRAL_INTRO_COPY[1], pickIntroCopy(6))
        assertEquals(NEUTRAL_INTRO_COPY[2], pickIntroCopy(99_997))
    }

    /**
     * Desktop takes `Math.abs` of a double and cannot overflow. Kotlin's
     * `abs(Int.MIN_VALUE)` is still `Int.MIN_VALUE`, so a seed at the boundary
     * would index backwards if the absolute value were taken in `Int`.
     */
    @Test
    fun `a negative seed still lands inside the set`() {
        assertEquals(NEUTRAL_INTRO_COPY[3], pickIntroCopy(-3))
        assertEquals(NEUTRAL_INTRO_COPY[3], pickIntroCopy(Int.MIN_VALUE))
        for (seed in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) {
            assertTrue("seed $seed", pickIntroCopy(seed) in NEUTRAL_INTRO_COPY)
        }
    }

    @Test
    fun `a fresh draft with an empty transcript shows the splash`() {
        assertTrue(showing())
    }

    @Test
    fun `the Appearance toggle outranks every other clause`() {
        assertFalse(showing(enabled = false))
    }

    /**
     * The clause that stops the splash flashing over a session that is still
     * loading: `ChatUiState.activeSessionId` is set the moment the composer is
     * homed, which is before any transcript row can arrive.
     */
    @Test
    fun `a homed session never shows the splash, transcript or not`() {
        assertFalse(showing(activeSessionId = "session-a"))
        assertFalse(showing(activeSessionId = "session-a", transcriptEmpty = false))
    }

    @Test
    fun `a transcript with anything in it never shows the splash`() {
        assertFalse(showing(transcriptEmpty = false))
    }

    @Test
    fun `a running turn never shows the splash`() {
        assertFalse(showing(turnRunning = true))
    }

    private fun showing(
        enabled: Boolean = true,
        activeSessionId: String? = null,
        transcriptEmpty: Boolean = true,
        turnRunning: Boolean = false,
    ) = shouldShowIntroSplash(
        enabled = enabled,
        activeSessionId = activeSessionId,
        transcriptEmpty = transcriptEmpty,
        turnRunning = turnRunning,
    )
}
