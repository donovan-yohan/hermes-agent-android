package com.hermesagent.mobile.data.prefs

import kotlinx.serialization.json.*

/** Exact nullable route: omitted launch is deliberately not explicit default. */
data class HiddenProjectsScope(val endpoint: String, val profile: String?)

interface HiddenProjectsStore {
    suspend fun readHiddenProjects(scope: HiddenProjectsScope): Set<String>
    suspend fun writeHiddenProjects(scope: HiddenProjectsScope, ids: Set<String>)
}

class TransientHiddenProjectsStore : HiddenProjectsStore {
    private val rows = mutableMapOf<HiddenProjectsScope, Set<String>>()
    override suspend fun readHiddenProjects(scope: HiddenProjectsScope): Set<String> = rows[scope].orEmpty()
    override suspend fun writeHiddenProjects(scope: HiddenProjectsScope, ids: Set<String>) { rows[scope] = ids.toSet() }
}

/** Length/nullable-safe structured key, not concatenation of user-controlled path fragments. */
internal fun HiddenProjectsScope.preferenceKey(): String = "projects.hidden.v1." + JsonArray(listOf(
    JsonPrimitive(endpoint), profile?.let(::JsonPrimitive) ?: JsonNull,
)).toString()

internal fun decodeHiddenProjects(value: String?): Set<String> = value?.let {
    Json.parseToJsonElement(it).jsonArray.map { row -> row.jsonPrimitive.content }.toSet()
}.orEmpty()
internal fun encodeHiddenProjects(ids: Set<String>): String = JsonArray(ids.sorted().map(::JsonPrimitive)).toString()
