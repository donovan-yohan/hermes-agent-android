package com.hermesagent.mobile.data.gateway

/**
 * The Gateway's approval vocabulary, and what a person is shown for it.
 *
 * `approval.request` offers `once`, `session`, `always` and `deny`
 * (`gateway/platforms/api_server.py:107-108` @
 * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`) and
 * [ApprovalPending.choices] carries exactly what it offered, because the
 * response is validated against that list. Those are wire values, not words:
 * rendering them straight onto buttons — which is what the composer's approval
 * card did — asks somebody to press `once` or `always` and work out which is
 * which.
 *
 * One mapping, used by the card and by the shade, so the two surfaces can never
 * label the same decision differently.
 */

/** `once` (`i18n/en.ts:3749` `approval.run` @ the pin). */
const val APPROVAL_ONCE: String = "once"

/** `session` (`en.ts:3752` `approval.allowSession`). */
const val APPROVAL_SESSION: String = "session"

/** `always` (`en.ts:3759` `approval.alwaysAllow`). */
const val APPROVAL_ALWAYS: String = "always"

/** `deny` (`en.ts:3754` `approval.reject`). */
const val APPROVAL_DENY: String = "deny"

/**
 * Desktop's own label for a choice, or the choice itself.
 *
 * An unrecognised value is rendered verbatim rather than dropped or renamed: a
 * newer Gateway offering a choice this app has never heard of should still be
 * answerable, and the only honest label for it is the one the Gateway sent.
 * It reaches here already redacted and bounded (`normalizeChoice`).
 */
fun approvalChoiceLabel(choice: String): String = when (choice.lowercase()) {
    APPROVAL_ONCE -> "Run"
    APPROVAL_SESSION -> "Allow this session"
    APPROVAL_ALWAYS -> "Always allow"
    APPROVAL_DENY -> "Reject"
    else -> choice
}

/** Whether answering with this choice outlives the request it answers. */
fun isPersistentGrant(choice: String): Boolean =
    choice.lowercase() in setOf(APPROVAL_SESSION, APPROVAL_ALWAYS)

/** Whether this choice refuses. Drawn destructively, and never auto-selected. */
fun isDenial(choice: String): Boolean = choice.lowercase() == APPROVAL_DENY

/**
 * The at-most-three choices a notification can carry, out of what was offered.
 *
 * Android renders three action buttons and silently drops the rest, so the
 * three are chosen here rather than by truncation: run once, the strongest
 * persistent grant on offer, and the refusal. A Gateway that offered only two
 * yields two; one that offered a choice this app does not know yields it only
 * if there is room, because an unknown choice is the one a person is least able
 * to judge from a shade.
 *
 * The refusal is last on purpose. It is the destructive one, and Android lays
 * actions out left to right in the order they are added.
 */
fun shadeApprovalChoices(offered: List<String>): List<String> {
    val has = { choice: String -> offered.any { it.equals(choice, ignoreCase = true) } }
    val pick = { choice: String -> offered.first { it.equals(choice, ignoreCase = true) } }
    return buildList {
        if (has(APPROVAL_ONCE)) add(pick(APPROVAL_ONCE))
        when {
            has(APPROVAL_ALWAYS) -> add(pick(APPROVAL_ALWAYS))
            has(APPROVAL_SESSION) -> add(pick(APPROVAL_SESSION))
        }
        if (has(APPROVAL_DENY)) add(pick(APPROVAL_DENY))
        if (size < MAX_SHADE_ACTIONS) {
            // Whatever else this Gateway offered, in its own order, up to the
            // limit — including choices this app has no label for.
            for (choice in offered) {
                if (size >= MAX_SHADE_ACTIONS) break
                if (none { it.equals(choice, ignoreCase = true) }) add(choice)
            }
        }
    }.take(MAX_SHADE_ACTIONS)
}

/** Android draws three; a fourth is added to the notification and never seen. */
const val MAX_SHADE_ACTIONS: Int = 3
