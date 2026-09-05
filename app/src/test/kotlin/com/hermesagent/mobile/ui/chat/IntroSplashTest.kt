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

    /**
     * The visible split is this port's; the *name* is still Desktop's, and the
     * two must not drift apart — `Wordmark` publishes [INTRO_WORDMARK] as the
     * accessibility label for the lines it draws.
     */
    @Test
    fun `the drawn lines are the wordmark, split and nothing else`() {
        assertEquals(listOf("HERMES", "AGENT"), INTRO_WORDMARK_LINES)
        assertEquals(INTRO_WORDMARK, INTRO_WORDMARK_LINES.joinToString(" "))
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
     * **Inverted on purpose, and this is the deviation from Desktop.**
     *
     * `intro-visibility.ts:12-33` @ `3ca096de` shows the intro only for a fresh
     * draft, because a homed session gets `ChatEmptySlot` instead. This app has
     * never ported that surface, so the alternative here was the plain
     * `No messages yet` note — and the owner's call is that an empty session is
     * exactly the moment the wordmark is worth showing. Ledgered in
     * `docs/parity/empty-states.md`.
     *
     * What did **not** change is the guard the old assertion existed for: a
     * session whose history is still being read has an empty transcript too,
     * and must not flash. That is now the message count's job, below.
     */
    @Test
    fun `a homed session with nothing in it shows the splash too`() {
        assertTrue(showing(activeSessionId = "session-a", sessionMessageCount = 0))
        assertFalse(showing(activeSessionId = "session-a", sessionMessageCount = 0, transcriptEmpty = false))
    }

    /**
     * The frames between `session.create` and the row landing, and between
     * opening a session and its history arriving. `ChatUiState.transcript` is
     * read straight from the cache, so both look empty; the count is what says
     * whether the emptiness has been vouched for.
     */
    @Test
    fun `a session whose row has not landed cannot splash`() {
        assertFalse("no count means no row yet", showing(activeSessionId = "session-a"))
    }

    @Test
    fun `a session the Gateway says has messages cannot splash while they load`() {
        assertFalse(showing(activeSessionId = "session-a", sessionMessageCount = 12))
    }

    /** A fresh draft is Desktop's own case and needs no count to vouch for it. */
    @Test
    fun `a fresh draft splashes with no count at all`() {
        assertTrue(showing(activeSessionId = null, sessionMessageCount = null))
    }

    @Test
    fun `the Appearance toggle still outranks the homed-session case`() {
        assertFalse(showing(enabled = false, activeSessionId = "session-a", sessionMessageCount = 0))
    }

    @Test
    fun `a transcript with anything in it never shows the splash`() {
        assertFalse(showing(transcriptEmpty = false))
    }

    @Test
    fun `a running turn never shows the splash`() {
        assertFalse(showing(turnRunning = true))
        assertFalse(showing(activeSessionId = "session-a", sessionMessageCount = 0, turnRunning = true))
    }

    /**
     * The working directory under the splash: its tail, because the tail is
     * what identifies it and the head is what a phone column has no room for.
     */
    @Test
    fun `a working directory is shortened to its last two segments`() {
        assertEquals("…/personal/hermes-mobile", shortenWorktreePath("/home/someone/code/personal/hermes-mobile"))
        assertEquals("…/code/app", shortenWorktreePath("/home/someone/code/app/"))
    }

    @Test
    fun `a path already at or below two segments is left alone`() {
        assertEquals("/home/someone", shortenWorktreePath("/home/someone"))
        assertEquals("/srv", shortenWorktreePath("/srv"))
        assertEquals("app", shortenWorktreePath("app"))
        assertEquals("/", shortenWorktreePath("/"))
        assertEquals("", shortenWorktreePath(""))
    }

    private fun showing(
        enabled: Boolean = true,
        activeSessionId: String? = null,
        transcriptEmpty: Boolean = true,
        turnRunning: Boolean = false,
        sessionMessageCount: Int? = null,
    ) = shouldShowIntroSplash(
        enabled = enabled,
        activeSessionId = activeSessionId,
        transcriptEmpty = transcriptEmpty,
        turnRunning = turnRunning,
        sessionMessageCount = sessionMessageCount,
    )
}
