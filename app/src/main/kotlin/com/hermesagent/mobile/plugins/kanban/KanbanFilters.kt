package com.hermesagent.mobile.plugins.kanban

import java.util.Locale

/** UI-only projection, never task authority. Desktop board.tsx:1183-1197
 * @ 81a55437f3f31a9f12a30c53b6c5d49d420898aa. */
data class KanbanFilters(
    val search: String = "",
    val assignee: String = "",
    val tenant: String = "",
    val archived: Boolean = false,
) {
    fun apply(columns: List<KanbanColumn>): List<KanbanColumn> {
        val query = search.trim().lowercase(Locale.ROOT)
        return columns.map { column ->
            column.copy(tasks = column.tasks.filter { task ->
                (query.isEmpty() || "${task.title} ${task.body.orEmpty()} ${task.id}".lowercase(Locale.ROOT).contains(query)) &&
                    (assignee.isEmpty() || task.assignee == assignee) &&
                    (tenant.isEmpty() || task.tenant == tenant)
            })
        }.filter { it.tasks.isNotEmpty() }
    }
}
