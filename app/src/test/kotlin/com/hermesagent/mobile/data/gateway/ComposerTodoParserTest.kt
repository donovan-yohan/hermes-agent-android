package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.ComposerTodoState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
