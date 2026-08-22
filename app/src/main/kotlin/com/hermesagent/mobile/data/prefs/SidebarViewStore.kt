package com.hermesagent.mobile.data.prefs

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The subset of Desktop's sidebar grouping contract that Mobile can render.
 *
 * Desktop calls the time-based option `date` internally and labels it "Updated";
 * `project` switches the same session catalog into the project tree. Status and
 * profile grouping remain explicit omissions until Mobile has those authorities.
 */
enum class SidebarGrouping { Date, Project }

interface SidebarViewStore {
    val sidebarGrouping: Flow<SidebarGrouping>
    suspend fun saveSidebarGrouping(grouping: SidebarGrouping)
}

/** Per-ViewModel default for tests/previews that do not own a persistent store. */
internal class TransientSidebarViewStore(
    initialGrouping: SidebarGrouping = SidebarGrouping.Date,
) : SidebarViewStore {
    private val state = MutableStateFlow(initialGrouping)
    override val sidebarGrouping: Flow<SidebarGrouping> = state

    override suspend fun saveSidebarGrouping(grouping: SidebarGrouping) {
        state.value = grouping
    }
}
