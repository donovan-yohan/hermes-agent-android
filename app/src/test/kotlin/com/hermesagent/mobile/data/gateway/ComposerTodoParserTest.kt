package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.ComposerTodoState
import com.hermesagent.mobile.data.session.ToolActivity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerTodoParserTest {
    @Test
    fun `parses every Desktop todo status and ignores malformed rows`() {
        val todos = parseComposerTodos(
            Json.parseToJsonElement(
                """[
                    {"id":"a","content":"pending work","status":"pending"},
                    {"id":"b","content":"active work","status":"in_progress"},
                    {"id":"c","content":"finished work","status":"completed"},
                    {"id":"d","content":"dropped work","status":"cancelled"},
                    {"id":5,"content":42,"status":"pending"},
                    {"id":"bad","content":"unknown work","status":"invented"},
                    {"content":"missing id","status":"pending"}
                ]""",
            ),
        ).orEmpty()

        assertEquals(listOf("a", "b", "c", "d", "5"), todos.map { it.id })
        assertEquals(
            listOf(
                ComposerTodoState.Pending,
                ComposerTodoState.InProgress,
                ComposerTodoState.Completed,
                ComposerTodoState.Cancelled,
                ComposerTodoState.Pending,
            ),
            todos.map { it.state },
        )
        assertEquals("42", todos.last().title)
    }

    @Test
    fun `accepts bounded JSON string and todos wrapper shapes`() {
        val wrapped = Json.parseToJsonElement(
            """{"todos":"[{\"id\":\"one\",\"content\":\"ship it\",\"status\":\"completed\"}]"}""",
        )

        assertEquals("ship it", parseComposerTodos(wrapped)?.single()?.title)
        assertNull(
            parseComposerTodos(
                Json.parseToJsonElement(
                    """{"todos":"{\"todos\":\"{\\\"todos\\\":[{\\\"id\\\":\\\"deep\\\",\\\"content\\\":\\\"nope\\\",\\\"status\\\":\\\"pending\\\"}]}\"}"}""",
                ),
            ),
        )
    }

    @Test
    fun `live payload uses Desktop field priority and redacts task copy`() {
        val payload = Json.parseToJsonElement(
            """{
                "result":{"todos":[{"id":"result","content":"result","status":"pending"}]},
                "arguments":{"todos":[{"id":"args","content":"Authorization: Bearer should-not-survive","status":"in_progress"}]}
            }""",
        ) as JsonObject
        assertEquals("result", parseComposerTodosFromTool(payload)?.single()?.id)

        val argsOnly = Json.parseToJsonElement(
            """{"arguments":{"todos":[{"id":"args","content":"Authorization: Bearer should-not-survive","status":"in_progress"}]}}""",
        ) as JsonObject
        val title = parseComposerTodosFromTool(argsOnly)?.single()?.title.orEmpty()
        assertFalse(title.contains("should-not-survive"))
    }

    @Test
    fun `latest parseable historical todo call wins`() {
        val history = Json.parseToJsonElement(
            """{"messages":[
                {"role":"assistant","content":[{"type":"tool-call","toolName":"todo","args":{"todos":[{"id":"old","content":"old","status":"pending"}]}}]},
                {"role":"tool","name":"todo","result":{"todos":[{"id":"new","content":"new","status":"completed"}]}}
            ]}""",
        )

        val latest = latestComposerTodosFromHistory(history)
        assertEquals("new", latest?.single()?.id)
        assertEquals(ComposerTodoState.Completed, latest?.single()?.state)
    }

    /**
     * The pin registers the task tool as `todo_list` (`model_tools.py:607`,
     * `agent/inline_tool_executors.py:177` @
     * `d177b119e9c56c9ddc0b7379ffce52341ec06584`), so a pinned Gateway's stored
     * rows spell it that way — a REST page or a history read from it derives the
     * same list as a row an older Gateway stored as `todo`. Both spellings are
     * one tool (`isTodoToolName`, Desktop
     * `apps/desktop/src/lib/todos.ts:23` @ the same SHA).
     */
    @Test
    fun `a pinned gateway's todo_list rows derive the list the legacy spelling did`() {
        val history = Json.parseToJsonElement(
            """{"messages":[
                {"role":"assistant","content":[{"type":"tool-call","toolName":"todo","args":{"todos":[{"id":"old","content":"old","status":"pending"}]}}]},
                {"role":"tool","name":"todo_list","result":{"todos":[{"id":"new","content":"new","status":"completed"}]}}
            ]}""",
        )

        val latest = latestComposerTodosFromHistory(history)
        assertEquals("new", latest?.single()?.id)
        assertEquals(ComposerTodoState.Completed, latest?.single()?.state)
    }

    @Test
    fun `a pinned gateway's todo_list row is hoisted out of the transcript, not rendered`() {
        val root = Json.parseToJsonElement(
            """{"messages":[
                {"role":"user","text":"plan this"},
                {"role":"tool","name":"todo_list","result":{"todos":[{"id":"plan","content":"plan","status":"pending"}]}}
            ]}""",
        )

        assertTrue(
            "the task row belongs to the composer panel",
            parseHistory(root, "runtime-a", 0L).none { it is ToolActivity },
        )
        assertEquals(listOf("plan"), latestComposerTodosFromHistory(root)?.map { it.id })
    }

    @Test
    fun `a projected REST page derives the list and renders no task row`() {
        val rows = Json.parseToJsonElement(
            """[
                {"id":10,"role":"assistant","content":"","tool_calls":[{"id":"call-1","function":{"name":"todo_list","arguments":"{\"todos\":[{\"id\":\"plan\",\"content\":\"plan\",\"status\":\"pending\"}]}"}}]},
                {"id":11,"role":"tool","tool_call_id":"call-1","tool_name":"todo_list","content":"ok"}
            ]""",
        ) as JsonArray
        val projected = projectRestTranscriptRows(rows.map { it as JsonObject })

        // The projection keeps the stored name and the call's arguments: it is
        // the boundary that feeds derivation, not a place to drop task data.
        val tool = projected.single { it.string("role") == "tool" }
        assertEquals("todo_list", tool.string("name"))
        assertEquals("ok", tool.string("content"))
        assertEquals(
            listOf("plan"),
            ((tool["args"] as JsonObject)["todos"] as JsonArray).map { (it as JsonObject).string("id") },
        )
        assertEquals(listOf("plan"), latestComposerTodosFromRows(projected)?.map { it.id })

        // Hoisting is the transcript parser's call, and it holds for both
        // spellings on this contract.
        val root = buildJsonObject { put("messages", JsonArray(projected)) }
        assertTrue(
            "the task row belongs to the composer panel",
            parseHistory(root, "runtime-a", 0L).none { it is ToolActivity },
        )
    }
}
