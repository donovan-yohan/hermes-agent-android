package com.hermesagent.mobile.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One predicate for the task tool's rename, checked against the pin.
 *
 * Upstream names it `todo_list` and keeps `todo` as the legacy alias
 * (`model_tools.py:607,611-614` @
 * `d177b119e9c56c9ddc0b7379ffce52341ec06584`); Desktop recognises both
 * (`apps/desktop/src/lib/todos.ts:21-23` @ the same SHA).
 */
class ToolNamesTest {

    @Test
    fun `both spellings name the task tool`() {
        assertTrue(isTodoToolName(TODO_TOOL_NAME))
        assertTrue(isTodoToolName(LEGACY_TODO_TOOL_NAME))
    }

    @Test
    fun `nothing else is the task tool`() {
        assertFalse(isTodoToolName(null))
        assertFalse(isTodoToolName(""))
        assertFalse(isTodoToolName("todo_lists"))
        assertFalse(isTodoToolName("todos"))
        assertFalse(isTodoToolName("terminal"))
        // The rename is a dispatch alias, not a prefix rule: the names this app
        // looks up are exact (`model_tools.py:611-614`).
        assertFalse(isTodoToolName("Todo_List"))
    }

    @Test
    fun `the canonical name is the pin's, and the legacy name is what it aliases`() {
        assertEquals("todo_list", TODO_TOOL_NAME)
        assertEquals("todo", LEGACY_TODO_TOOL_NAME)
    }
}
