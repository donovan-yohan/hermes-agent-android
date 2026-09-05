package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.UserTurn

/**
 * What `session.branch` is asked for: keep `count` messages, the whole chat,
 * or nothing because the reply cannot be located.
 *
 * @param localTranscript The transcript as currently held in the app's cache.
 * @param authoritativeTranscript The transcript returned by the backend's `session.history`.
 * @param targetId The ID of the tapped `AssistantTurn`.
 * @return The branching instruction to apply to `session.branch`.
 */
sealed interface BranchPlan {
    data class Keep(val count: Int) : BranchPlan
    data object Whole : BranchPlan
    data object Unlocatable : BranchPlan
}

/**
 * Derives the `count` parameter for `session.branch`, mirroring Desktop's
 * `selectBranchMessages` implementation in
 * `apps/desktop/src/app/session/hooks/use-session-actions/utils.ts:selectBranchMessages`
 * at `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 */
fun deriveBranchCount(
    localTranscript: List<TranscriptEntry>,
    authoritativeTranscript: List<TranscriptEntry>,
    targetId: String
): BranchPlan {
    val localTarget = localTranscript.find { it.id == targetId } as? AssistantTurn
        ?: return BranchPlan.Unlocatable

    val localVisible = localTranscript.filter {
        (it is UserTurn && it.text.isNotBlank()) || (it is AssistantTurn && it.markdown.isNotBlank())
    }

    val authoritativeVisible = authoritativeTranscript.filter {
        (it is UserTurn && it.text.isNotBlank()) || (it is AssistantTurn && it.markdown.isNotBlank())
    }

    if (localTarget !in localVisible) {
        return BranchPlan.Unlocatable
    }

    // Try by rowId
    var matchedIndex = -1
    if (localTarget.rowId != null) {
        matchedIndex = authoritativeVisible.indexOfFirst { it.rowId == localTarget.rowId }
    }

    // Fallback: by role + trimmed text ordinal
    if (matchedIndex == -1) {
        val targetRole = "assistant"
        val targetText = localTarget.markdown.trim()

        // Find N-th match in local visible
        var ordinal = 0
        for (msg in localVisible) {
            val role = if (msg is UserTurn) "user" else "assistant"
            val text = (msg as? UserTurn)?.text?.trim() ?: (msg as? AssistantTurn)?.markdown?.trim() ?: ""
            if (role == targetRole && text == targetText) {
                if (msg.id == targetId) break
                ordinal++
            }
        }

        // Find N-th match in authoritative visible
        var authOrdinal = 0
        for ((index, msg) in authoritativeVisible.withIndex()) {
            val role = if (msg is UserTurn) "user" else "assistant"
            val text = (msg as? UserTurn)?.text?.trim() ?: (msg as? AssistantTurn)?.markdown?.trim() ?: ""
            if (role == targetRole && text == targetText) {
                if (authOrdinal == ordinal) {
                    matchedIndex = index
                    break
                }
                authOrdinal++
            }
        }
    }

    if (matchedIndex == -1) {
        return BranchPlan.Unlocatable
    }

    if (matchedIndex == authoritativeVisible.lastIndex) {
        return BranchPlan.Whole
    }

    return BranchPlan.Keep(matchedIndex + 1)
}
