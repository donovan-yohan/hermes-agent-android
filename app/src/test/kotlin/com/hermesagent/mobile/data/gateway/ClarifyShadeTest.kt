package com.hermesagent.mobile.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which clarifies a notification may answer, and which it must not try to. */
class ClarifyShadeTest {

    @Test
    fun `a single question with a few choices is answerable`() {
        val question = shadeQuestion(
            clarify(question = "Which store?", choices = listOf("Redis", "Postgres")),
        )

        assertEquals(ShadeQuestion("", "Which store?", listOf("Redis", "Postgres")), question)
    }

    /** No choices is a question whose answer was always going to be typed. */
    @Test
    fun `a single question with no choices becomes a reply box`() {
        val question = shadeQuestion(clarify(question = "What should I name it?"))

        assertEquals(emptyList<String>(), question?.choices)
    }

    /**
     * Answering the first question from a shade leaves the rest unanswered and
     * the turn still parked, which reads as the answer having failed.
     */
    @Test
    fun `a batch is not answerable from a shade`() {
        val pending = clarify(
            questions = listOf(
                ClarifyQuestion("q1", "Which store?", listOf("Redis"), multiSelect = false),
                ClarifyQuestion("q2", "Which region?", listOf("eu"), multiSelect = false),
            ),
        )

        assertNull(shadeQuestion(pending))
    }

    /** A batch of one is still one question, and is answerable by its id. */
    @Test
    fun `a batch of one carries its question id`() {
        val pending = clarify(
            questions = listOf(ClarifyQuestion("q1", "Which store?", listOf("Redis"), multiSelect = false)),
        )

        assertEquals(ShadeQuestion("q1", "Which store?", listOf("Redis")), shadeQuestion(pending))
    }

    /** One action is one tap and one send; there is nowhere to accumulate. */
    @Test
    fun `multi-select is not answerable from a shade`() {
        assertNull(shadeQuestion(clarify(question = "Which?", choices = listOf("a", "b"), multiSelect = true)))
        assertNull(
            shadeQuestion(
                clarify(questions = listOf(ClarifyQuestion("q1", "Which?", listOf("a"), multiSelect = true))),
            ),
        )
    }

    /**
     * Android draws three actions and drops the rest silently, and a truncated
     * list of constrained choices is a lie about what the options were.
     */
    @Test
    fun `more choices than the shade can draw is not answerable`() {
        val pending = clarify(question = "Which?", choices = listOf("a", "b", "c", "d"))

        assertNull(shadeQuestion(pending))
    }

    @Test
    fun `exactly the three the shade can draw is answerable`() {
        val pending = clarify(question = "Which?", choices = listOf("a", "b", "c"))

        assertEquals(listOf("a", "b", "c"), shadeQuestion(pending)?.choices)
    }

    /** A question with no text is nothing a notification can put on a button. */
    @Test
    fun `a blank question is not answerable`() {
        assertNull(shadeQuestion(clarify(question = "")))
    }

    private fun clarify(
        question: String = "",
        choices: List<String> = emptyList(),
        multiSelect: Boolean = false,
        questions: List<ClarifyQuestion> = emptyList(),
    ): ClarifyPending {
        val key = PendingInputKey(1L, "runtime", "req", PendingInputKind.Clarify)
        return ClarifyPending(
            key = key,
            durableSessionId = "s1",
            runtimeSessionId = "runtime",
            questions = questions,
            question = question,
            choices = choices,
            multiSelect = multiSelect,
        )
    }
}
