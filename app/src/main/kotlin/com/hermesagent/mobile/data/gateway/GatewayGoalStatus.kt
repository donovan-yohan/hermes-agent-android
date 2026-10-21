package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.ComposerGoalState
import com.hermesagent.mobile.data.session.ComposerGoalStatus
import kotlinx.serialization.json.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Automatic status reads carry their originating navigation/endpoint lifetime into cache publication. */
internal class GoalStatusReadLease(private val current: () -> Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GoalStatusReadLease>
    fun isCurrent(): Boolean = current()
}

/** Read-only projection of gateway/session_ancillary.py:334-362 and
 * tui_gateway/methods_session_control.py:48-94 @ 046094df28b712f62a5e3c728a9e59da05dcbf1e.
 * Null means malformed; Available(null) is the producer's explicit absence. */
internal fun parseGatewayControlGoal(root: JsonObject): GatewayGoalStatusOutcome.Available? {
    val control = root["control"] as? JsonObject ?: return null
    if (control["goal"] == JsonNull) return GatewayGoalStatusOutcome.Available(null)
    val goal = control["goal"] as? JsonObject ?: return null
    val title = goal.jsonString("title")?.let(::safeGatewayStatusText)?.takeIf(String::isNotBlank) ?: return null
    val barrier = if ("wait_barrier" in goal) {
        val value = goal["wait_barrier"] as? JsonObject ?: return null
        if (value.jsonString("reason") == null) return null
        val targetValid = when (value.jsonString("type")) {
            "until" -> (value["until_at"] as? JsonPrimitive)?.takeUnless { it.isString }
                ?.doubleOrNull?.isFinite() == true
            "session" -> value.jsonString("target") != null
            "pid" -> (value["target"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull != null
            else -> false
        }
        if (!targetValid) return null
        value
    } else null
    val state = when (goal.jsonString("status")) {
        "active" -> if (barrier != null) ComposerGoalState.Waiting else ComposerGoalState.Active
        "paused" -> ComposerGoalState.Paused
        "done" -> ComposerGoalState.Done
        else -> return null
    }
    val detail = (if (state == ComposerGoalState.Waiting) barrier?.jsonString("reason")
        else if (state == ComposerGoalState.Paused) goal.jsonString("paused_reason") else goal.jsonString("last_reason"))
        ?.let(::safeGatewayStatusText)?.takeIf(String::isNotBlank)
    return GatewayGoalStatusOutcome.Available(ComposerGoalStatus(
        rawText = safeGatewayStatusText(listOfNotNull(title, detail).joinToString(" — ")),
        state = state, title = title, detail = detail,
    ))
}
