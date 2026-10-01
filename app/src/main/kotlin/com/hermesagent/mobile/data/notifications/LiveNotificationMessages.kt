package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.UserTurn

/** Repository-owned identity; turnGeneration increments even when runtime id is reused. */
data class LiveNotificationScope(
    val connectionGeneration: Long,
    val runtimeSessionId: String,
    val turnGeneration: Long,
)

/** Only publish entries directly observed on the live event path, never history hydration. */
data class LiveNotificationMessage(val scope: LiveNotificationScope, val entry: TranscriptEntry)

/**
 * Atomic authoritative state, not a second transcript cache. The repository clears this on
 * disconnect/switch, replaces scopes on turnover, and removes failed/completed turns.
 * Message and scope are separate so retained old entries cannot pass a new scope's fence.
 */
data class LiveNotificationMessages(
    val connectionGeneration: Long = 0,
    val scopes: Map<String, LiveNotificationScope> = emptyMap(),
    val messages: Map<String, LiveNotificationMessage> = emptyMap(),
) {
    fun previewFor(durableSessionId: String): String? {
        val scope = scopes[durableSessionId] ?: return null
        if (scope.connectionGeneration != connectionGeneration) return null
        val message = messages[durableSessionId]?.takeIf { it.scope == scope } ?: return null
        return when (val entry = message.entry) {
            is UserTurn -> entry.text
            is AssistantTurn -> entry.takeIf { it.error == null && it.errorDetails == null && it.termination == null }?.markdown
            else -> null // Tools, reasoning, diagnostics and every future role fail closed.
        }?.takeIf(String::isNotBlank)
    }
}
