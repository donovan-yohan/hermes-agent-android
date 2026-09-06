package com.hermesagent.mobile.ui

import com.hermesagent.mobile.plugins.PluginDecisionStore
import com.hermesagent.mobile.plugins.PluginStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Minimal PluginStore fixture for Compose journeys that do not care about
 * persistence.
 *
 * The production store is provided by [com.hermesagent.mobile.HermesApplication];
 * tests provide this one to satisfy [HermesApp]'s contract without a DataStore.
 */
internal fun testPluginStore(): PluginStore =
    PluginStore(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        decisionStore = NoopDecisionStore(),
    )

private class NoopDecisionStore : PluginDecisionStore {
    override val pluginDecisions: Flow<Map<String, Boolean>> = flowOf(emptyMap())
    override suspend fun savePluginDecision(id: String, enabled: Boolean) = Unit
}

