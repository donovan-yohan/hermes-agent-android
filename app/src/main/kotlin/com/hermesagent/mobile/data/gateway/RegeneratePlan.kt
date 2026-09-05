package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.TranscriptRowId
import com.hermesagent.mobile.data.session.UserTurn

sealed interface RegeneratePlan {
    data class Ready(
        val sourceText: String,
        val sourceEntryId: String,
        val sourceRowId: TranscriptRowId?,
        val sourceIsLastUserTurn: Boolean
    ) : RegeneratePlan
    
    data object NotNewest : RegeneratePlan
    data object NoSource : RegeneratePlan
}

fun planRegenerate(transcript: List<TranscriptEntry>, entryId: String): RegeneratePlan {
    val lastAssistant = transcript.findLast { it is AssistantTurn }
    if (lastAssistant == null || lastAssistant.id != entryId) {
        return RegeneratePlan.NotNewest
    }

    val targetIndex = transcript.indexOf(lastAssistant)
    val precedingEntries = transcript.take(targetIndex)
    val precedingUserTurn = precedingEntries.findLast { it is UserTurn } as? UserTurn
    
    if (precedingUserTurn == null || precedingUserTurn.text.isBlank()) {
        return RegeneratePlan.NoSource
    }
    
    val isLastUserTurn = transcript.findLast { it is UserTurn } == precedingUserTurn
    
    return RegeneratePlan.Ready(
        sourceText = precedingUserTurn.text,
        sourceEntryId = precedingUserTurn.id,
        sourceRowId = precedingUserTurn.rowId,
        sourceIsLastUserTurn = isLastUserTurn
    )
}
