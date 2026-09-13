package com.hermesagent.mobile.plugins.kanban

import com.hermesagent.mobile.plugins.PluginRestOptions
import com.hermesagent.mobile.plugins.PluginRestResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KanbanPluginRepositoryTest {
    @Test
    fun `exact GET paths have no body and URL encode task ids`() = runTest {
        val calls = mutableListOf<Pair<String, PluginRestOptions>>()
        val repository = KanbanPluginRepository { path, options ->
            calls += path to options
            PluginRestResult.Success(200, if (path == "board") board else detail)
        }

        val task = (repository.board() as KanbanRead.Value).value.columns.single().tasks.single()

        assertTrue(repository.task(task.id) is KanbanRead.Value)
        assertEquals(listOf("board", "tasks/task%20one%2Ftwo"), calls.map { it.first })
        assertTrue(calls.all { it.second.method == "GET" && it.second.body == null })
    }

    @Test
    fun `detail requires wrapper task and decodes links object and child results`() = runTest {
        val repository = KanbanPluginRepository { _, _ -> PluginRestResult.Success(200, detail) }
        val loaded = (repository.task("task one/two") as KanbanRead.Value).value

        assertEquals(listOf("parent-1"), loaded.parentIds)
        assertEquals(listOf("child-1"), loaded.childIds)
        assertEquals("child-1", loaded.childResults.single().id)
        assertEquals("Ada", loaded.task.assignee)
        assertEquals(2, loaded.task.priority)
        assertEquals("line one\nline two", loaded.task.body)
        assertEquals("latest", loaded.task.latestSummary)
        assertEquals("done", loaded.task.result)

        val rootShapedTask = """{"id":"x","title":"T","status":"open"}""".toByteArray()
        val rootRepository = KanbanPluginRepository { _, _ -> PluginRestResult.Success(200, rootShapedTask) }
        assertEquals(KanbanRead.Refused, rootRepository.task("x"))
    }

    @Test
    fun `missing optionals and unknown fields are accepted`() = runTest {
        val response = """{"task":{"id":"x","title":"T","status":"open","future":{"diagnostic":"no"}},"links":{"parents":[],"children":[]},"child_results":[],"events":[{}]}""".toByteArray()
        val repository = KanbanPluginRepository { _, _ -> PluginRestResult.Success(200, response) }
        val task = ((repository.task("x") as KanbanRead.Value).value.task)

        assertEquals(null, task.assignee)
        assertEquals(null, task.body)
        assertEquals(null, task.result)
    }

    @Test
    fun `malformed required ids titles and statuses refuse`() = runTest {
        listOf(
            """{"id":"","title":"T","status":"open"}""",
            """{"id":"x","title":"","status":"open"}""",
            """{"id":"x","title":"T","status":""}""",
        ).forEach { row ->
            val response = """{"columns":[{"name":"Open","tasks":[$row]}]}""".toByteArray()
            val repository = KanbanPluginRepository { _, _ -> PluginRestResult.Success(200, response) }
            assertEquals(KanbanRead.Refused, repository.board())
        }
    }

    @Test
    fun `blank id bare unavailable envelope gone and refusal classify separately`() = runTest {
        var calls = 0
        val unavailable = KanbanPluginRepository { _, _ ->
            calls++
            PluginRestResult.UnavailableOnGateway
        }
        assertEquals(KanbanRead.Refused, unavailable.task("  "))
        assertEquals(0, calls)
        assertEquals(KanbanRead.Unavailable, unavailable.task("x"))

        val gone = KanbanPluginRepository { _, _ ->
            PluginRestResult.Refused(404, "backend secret", "raw".toByteArray())
        }
        assertEquals(KanbanRead.Gone, gone.task("x"))

        val refused = KanbanPluginRepository { _, _ ->
            PluginRestResult.Refused(500, "backend secret", "raw".toByteArray())
        }
        assertEquals(KanbanRead.Refused, refused.task("x"))
    }

    private companion object {
        val board = """{"columns":[{"name":"Open","tasks":[{"id":"task one/two","title":"Synthetic task","status":"Open","unknown":true}]}]}""".toByteArray()
        val detail = """{"task":{"id":"task one/two","title":"Synthetic task","status":"Open","assignee":"Ada","priority":2,"body":"line one\nline two","latest_summary":"latest","result":"done"},"links":{"parents":["parent-1"],"children":["child-1"]},"child_results":[{"id":"child-1","title":"Child","status":"done","result":"hidden full result"}]}""".toByteArray()
    }
}
