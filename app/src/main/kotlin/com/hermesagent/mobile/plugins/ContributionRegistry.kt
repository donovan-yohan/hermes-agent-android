package com.hermesagent.mobile.plugins

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The central registry every area reads from. Keyed by namespaced area id so
 * the same primitive resolves at any depth of the scene graph.
 *
 * Direct Kotlin port of Desktop's `ContributionRegistry`
 * (`apps/desktop/src/contrib/registry.ts:31-155` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`), exposing reactive [StateFlow]s
 * for Compose surfaces.
 */
class ContributionRegistry {
    private val lock = Any()
    private val byArea = mutableMapOf<String, MutableList<Contribution>>()
    private val snapshot = mutableMapOf<String, List<Contribution>>()

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private val _entries = MutableStateFlow<Map<String, List<Contribution>>>(emptyMap())
    val entries: StateFlow<Map<String, List<Contribution>>> = _entries.asStateFlow()

    private val areaFlows = mutableMapOf<String, MutableStateFlow<List<Contribution>>>()

    /** Register one contribution. Returns a disposer that removes it. */
    fun register(contribution: Contribution): () -> Unit = registerMany(listOf(contribution))

    /**
     * Register several at once. Returns a disposer that removes all of them.
     * A batch touches each affected area exactly once.
     */
    fun registerMany(contributions: List<Contribution>): () -> Unit {
        if (contributions.isEmpty()) return {}
        val touchedAreas: List<String>
        synchronized(lock) {
            for (c in contributions) {
                val list = byArea.getOrPut(c.area) { mutableListOf() }
                val existingIndex = list.indexOfFirst { it.id == c.id }
                if (existingIndex >= 0) {
                    list[existingIndex] = c
                } else {
                    list.add(c)
                }
            }
            touchedAreas = contributions.map { it.area }.distinct()
            invalidateLocked(touchedAreas)
        }
        return {
            removeMany(contributions.map { it.area to it.id })
        }
    }

    /**
     * Resolved, sorted, filtered entries for an area.
     * Stable list until mutated.
     */
    fun getArea(area: String): List<Contribution> {
        synchronized(lock) {
            snapshot[area]?.let { return it }
            val raw = byArea[area]
            if (raw.isNullOrEmpty()) {
                val empty = emptyList<Contribution>()
                snapshot[area] = empty
                return empty
            }
            val resolved = raw
                .filter { it.enabled && (it.`when`?.invoke() ?: true) }
                .sortedWith(compareBy { it.order ?: 0 })
            snapshot[area] = resolved
            return resolved
        }
    }

    /**
     * Reactive [StateFlow] of resolved contributions for one area.
     */
    fun areaFlow(area: String): StateFlow<List<Contribution>> {
        synchronized(lock) {
            return areaFlows.getOrPut(area) {
                MutableStateFlow(getArea(area))
            }.asStateFlow()
        }
    }

    private fun removeMany(entriesToRemove: List<Pair<String, String>>) {
        if (entriesToRemove.isEmpty()) return
        synchronized(lock) {
            val changedAreas = mutableListOf<String>()
            for ((area, id) in entriesToRemove) {
                val list = byArea[area] ?: continue
                val removed = list.removeAll { it.id == id }
                if (removed) {
                    if (list.isEmpty()) {
                        byArea.remove(area)
                    }
                    changedAreas.add(area)
                }
            }
            if (changedAreas.isNotEmpty()) {
                invalidateLocked(changedAreas.distinct())
            }
        }
    }

    private fun invalidateLocked(areas: List<String>) {
        for (area in areas) {
            snapshot.remove(area)
            val next = getArea(area)
            val flow = areaFlows[area]
            flow?.value = next
        }
        _version.value += 1
        _entries.value = byArea.mapValues { (k, _) -> getArea(k) }
    }
}
