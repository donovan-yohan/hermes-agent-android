package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.gateway.GatewayTurnOutcome
import com.hermesagent.mobile.data.gateway.cancelsThisCoroutine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Shared by the real Application collectors and virtual-time lifetime tests. */
internal suspend fun awaitNotificationRoute(
    routes: Flow<SavedConnection?>,
    captured: SavedConnection,
): Boolean = kotlinx.coroutines.withTimeoutOrNull(5_000) {
    routes.first { it == captured }
    true
} ?: false

internal suspend fun collectBotMessageOutcomes(
    outcomes: Flow<GatewayTurnOutcome>,
    accept: suspend (GatewayTurnOutcome) -> Unit,
    reconcile: () -> Unit,
) {
    outcomes.collect { outcome ->
        try {
            accept(outcome)
            // Missing persisted row_id requires reconciliation, not a synthetic dedupe key.
            if (!outcome.failed && outcome.completedMessageId == null) reconcile()
        } catch (failure: Exception) {
            if (failure.cancelsThisCoroutine()) throw failure
            // A scoped abort or checkpoint failure abandons only this outcome.
            // A later periodic read can recover it; keep the live collector alive.
        }
    }
}
