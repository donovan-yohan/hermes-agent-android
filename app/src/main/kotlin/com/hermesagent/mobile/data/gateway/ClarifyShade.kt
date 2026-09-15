package com.hermesagent.mobile.data.gateway

/**
 * One clarify question, in the shape a notification can answer it.
 *
 * [questionId] is empty for single-question mode: a single is answered with one
 * response frame carrying `answer`, and a batch answer is keyed by its `qid`
 * through `clarify.lock` — an empty-key lock would address no question at all.
 */
data class ShadeQuestion(
    val questionId: String,
    val question: String,
    /** Empty means the answer is free text. */
    val choices: List<String>,
)

/**
 * The question a notification may answer, or null if it must not try.
 *
 * The original port refused this outright, and its reason was sound: "a
 * clarify can be a batch of questions with constrained choices, and a single
 * free-text box cannot answer that honestly". That is an argument about
 * batches, not about every question — and most clarifies are one question. So
 * the refusal narrows to the cases where it is actually true:
 *
 *  * **A batch.** Answering the first question from a shade leaves the rest
 *    unanswered and the turn still parked, which reads as the answer having
 *    failed.
 *  * **Multi-select.** A notification action is one tap and one send; there is
 *    nowhere to accumulate a second choice before committing.
 *  * **More choices than the shade can draw.** Android renders three actions
 *    and drops the rest silently, and a truncated list of constrained choices
 *    is a lie about what the options were. Free text is not a substitute:
 *    the question constrained its answers for a reason.
 *
 * Everything else — one question with up to three choices, or one question
 * with none, which is a reply box — is answerable from the shade as honestly
 * as it is in the app.
 */
fun shadeQuestion(pending: ClarifyPending): ShadeQuestion? {
    val single = when {
        pending.questions.isEmpty() -> ShadeQuestion("", pending.question, pending.choices)
            .takeIf { !pending.multiSelect }
        pending.questions.size == 1 -> pending.questions.single()
            .takeIf { !it.multiSelect }
            ?.let { ShadeQuestion(it.questionId, it.question, it.choices) }
        else -> null
    } ?: return null
    if (single.question.isBlank()) return null
    if (single.choices.size > MAX_SHADE_ACTIONS) return null
    return single
}
