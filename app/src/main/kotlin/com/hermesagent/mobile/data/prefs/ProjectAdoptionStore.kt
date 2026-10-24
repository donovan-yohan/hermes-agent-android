package com.hermesagent.mobile.data.prefs

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Saved row/address and exact nullable profile; aliases and other devices are separate owners. */
data class ProjectAdoptionScope(val endpoint: String, val profile: String?)

/** A guard, not a replay journal: never retain an operation, color or icon. */
data class ProjectAdoptionAttempt(val attemptId: String, val autoId: String, val path: String, val label: String)

interface ProjectAdoptionStore {
    suspend fun readProjectAdoptions(scope: ProjectAdoptionScope): List<ProjectAdoptionAttempt>
    /** Atomic compare-and-set of the complete scoped guard set. */
    suspend fun compareProjectAdoptions(scope: ProjectAdoptionScope, expected: List<ProjectAdoptionAttempt>, replacement: List<ProjectAdoptionAttempt>, canCommit: () -> Boolean = { true }): Boolean
}

class TransientProjectAdoptionStore : ProjectAdoptionStore {
    private val mutex = Mutex()
    private val rows = mutableMapOf<ProjectAdoptionScope, List<ProjectAdoptionAttempt>>()
    override suspend fun readProjectAdoptions(scope: ProjectAdoptionScope) = mutex.withLock { rows[scope].orEmpty().toList() }
    override suspend fun compareProjectAdoptions(scope: ProjectAdoptionScope, expected: List<ProjectAdoptionAttempt>, replacement: List<ProjectAdoptionAttempt>, canCommit: () -> Boolean) = mutex.withLock {
        if (!canCommit() || rows[scope].orEmpty() != expected) false else { rows[scope] = replacement.toList(); true }
    }
}

internal fun ProjectAdoptionScope.adoptionPreferenceKey(): String = "projects.adoption.v1." + JsonArray(listOf(
    JsonPrimitive(endpoint), profile?.let(::JsonPrimitive) ?: JsonNull,
)).toString()

internal fun encodeProjectAdoptions(rows: List<ProjectAdoptionAttempt>): String = JsonArray(rows.map { row ->
    buildJsonObject {
        put("attempt", row.attemptId); put("auto", row.autoId); put("path", row.path); put("label", row.label)
    }
}).toString()

/** Malformed durable guards fail closed instead of silently discarding uncertain requests. */
internal fun decodeProjectAdoptions(raw: String?): List<ProjectAdoptionAttempt> = raw?.let {
    Json.parseToJsonElement(it).jsonArray.map { value ->
        val row = value.jsonObject
        fun field(key: String) = row.getValue(key).jsonPrimitive.let { text ->
            require(text.isString && text.content.isNotBlank()); text.content
        }
        ProjectAdoptionAttempt(field("attempt"), field("auto"), field("path"), field("label"))
    }.also { rows -> require(rows.map { it.attemptId }.distinct().size == rows.size) }
}.orEmpty()
