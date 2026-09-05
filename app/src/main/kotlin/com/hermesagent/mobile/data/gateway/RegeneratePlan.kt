package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.TranscriptRowId
import com.hermesagent.mobile.data.session.UserTurn

/**
 * Mirrors Desktop's `planReload`
 * (`apps/desktop/src/app/session/hooks/use-prompt-actions/rewind.ts @ 3ca096de`)
 * when deciding whether a single assistant bubble can be regenerated.
 *
 * The current app keeps this control on the newest assistant reply only under
 * #69, preventing destructive partial truncation from an older reply.
 */
sealed interface RegeneratePlan {
    data class Ready(
        val sourceText: String,
        val sourceEntryId: String,
        val sourceRowId: TranscriptRowId?,
        val sourceIsLastUserTurn: Boolean,
    ) : RegeneratePlan

    data object NotNewest : RegeneratePlan
    data object NoSource : RegeneratePlan
}

/**
 * Plans the Android equivalent of Desktop's `planReload`
 * (`apps/desktop/src/app/session/hooks/use-prompt-actions/rewind.ts @ 3ca096de`).
 * Unlike Desktop, #69 restricts refresh to the newest assistant reply.
 */
fun planRegenerate(transcript: List<TranscriptEntry>, entryId: String): RegeneratePlan {
    val lastAssistantIndex = transcript.indexOfLast { it is AssistantTurn }
    if (lastAssistantIndex < 0 || transcript[lastAssistantIndex].id != entryId) {
        return RegeneratePlan.NotNewest
    }

    val targetIndex = lastAssistantIndex
    val precedingEntries = transcript.take(targetIndex)
    val precedingUserTurn = precedingEntries.findLast { it is UserTurn } as? UserTurn

    if (precedingUserTurn == null || precedingUserTurn.text.isBlank()) {
        return RegeneratePlan.NoSource
    }

    val isLastUserTurn = transcript.findLast { it is UserTurn }?.id == precedingUserTurn.id

    return RegeneratePlan.Ready(
        sourceText = precedingUserTurn.text,
        sourceEntryId = precedingUserTurn.id,
        sourceRowId = precedingUserTurn.rowId,
        sourceIsLastUserTurn = isLastUserTurn,
    )
}
