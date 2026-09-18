package com.hermesagent.mobile.data.session

/**
 * The task tool's name on the wire at the pin: `todo_list`.
 *
 * A legacy spelling, `todo`, is what an older Gateway persisted — one tool, two
 * names (Desktop `apps/desktop/src/lib/todos.ts:21-23` @
 * `d177b119e9c56c9ddc0b7379ffce52341ec06584`) — so recognition is one predicate
 * and no seam carries two comparisons of its own. It never rewrites a name;
 * the row's own `name` stays the call the transcript stored.
 */
internal fun isTodoToolName(name: String?): Boolean =
    name == TODO_TOOL_NAME || name == LEGACY_TODO_TOOL_NAME

/** The task tool's name on the wire at the pin. */
internal const val TODO_TOOL_NAME = "todo_list"

/** The spelling an older Gateway persisted. */
internal const val LEGACY_TODO_TOOL_NAME = "todo"
