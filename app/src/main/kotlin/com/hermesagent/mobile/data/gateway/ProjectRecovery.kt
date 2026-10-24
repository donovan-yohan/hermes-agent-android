package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.prefs.ProjectAdoptionAttempt
import kotlinx.serialization.json.*

/** Read-only server inventory; never inserted into SessionCache as fabricated tree nodes. */
data class ProjectRecoveryRow(val id: String, val name: String, val paths: List<String>, val archived: Boolean)

interface ProjectRecovery {
    fun isCurrent(): Boolean
    suspend fun pending(): List<ProjectAdoptionAttempt>
    suspend fun refresh(attemptId: String, isSurfaceCurrent: () -> Boolean): ProjectRecoveryTicket
}

/** Strict list shape from projects_db.Project.to_dict at backend 046094df, including archived. */
internal fun parseProjectRecoveryInventory(value: JsonElement): List<ProjectRecoveryRow> {
    val rows = (value as? JsonObject)?.get("projects") as? JsonArray ?: error("Projects could not be read.")
    return rows.map { element ->
        val row = element as? JsonObject ?: error("Projects could not be read.")
        fun text(key: String) = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = text("id")?.takeIf { it.isNotBlank() } ?: error("Projects could not be read.")
        val name = text("name") ?: error("Projects could not be read.")
        val folders = row["folders"] as? JsonArray ?: error("Projects could not be read.")
        val paths = buildList {
            text("primary_path")?.let(::add)
            folders.forEach { folder ->
                add(((folder as? JsonObject)?.get("path") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: error("Projects could not be read."))
            }
        }.distinct()
        val archived = (row["archived"] as? JsonPrimitive)?.booleanOrNull ?: error("Projects could not be read.")
        ProjectRecoveryRow(id, name, paths, archived)
    }.also { require(it.map { row -> row.id }.distinct().size == it.size) }
}

/** Memory-only, one latest read, one original attempt, one mounted review surface. */
interface ProjectRecoveryTicket {
    val attempt: ProjectAdoptionAttempt
    val projects: List<ProjectRecoveryRow>
    fun isCurrent(): Boolean
    suspend fun acknowledge(isSurfaceCurrent: () -> Boolean): Boolean
}
