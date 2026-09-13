package com.hermesagent.mobile.plugins.kanban

import com.hermesagent.mobile.plugins.PluginRestOptions
import com.hermesagent.mobile.plugins.PluginRestResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KanbanPluginRepositoryTest {
    @Test fun `board and detail use exact read-only GET paths and decode forward compatible fixtures`() = runTest {
        val calls = mutableListOf<Pair<String, PluginRestOptions>>()
        val repository = KanbanPluginRepository { path, options ->
            calls += path to options
            PluginRestResult.Success(200, if (path == "board") board else detail)
        }
        val snapshot = repository.board()
        assertTrue(snapshot is KanbanRead.Value)
        val task = ((snapshot as KanbanRead.Value).value.columns.single().tasks.single())
        val loaded = repository.task(task.id)
        assertTrue(loaded is KanbanRead.Value)
        assertEquals(listOf("board", "tasks/task%20one%2Ftwo"), calls.map { it.first })
        assertTrue(calls.all { it.second.method == "GET" && it.second.body == null })
        assertEquals(setOf("comments", "events", "attachments", "links", "child_results", "runs"), (loaded as KanbanRead.Value).value.siblingCollections)
    }

    @Test fun `bare 404 is unavailable but envelope detail 404 is gone and malformed required row refuses`() = runTest {
        val unavailable = KanbanPluginRepository { _, _ -> PluginRestResult.UnavailableOnGateway }
        assertEquals(KanbanRead.Unavailable, unavailable.board())
        val gone = KanbanPluginRepository { _, _ -> PluginRestResult.Refused(404, "safe", "{}".toByteArray()) }
        assertEquals(KanbanRead.Gone, gone.task("synthetic"))
        val malformed = KanbanPluginRepository { _, _ -> PluginRestResult.Success(200, """{"columns":[{"name":"Open","tasks":[{"id":"x","title":"missing status"}]}]}""".toByteArray()) }
        assertEquals(KanbanRead.Refused, malformed.board())
    }

    private companion object {
        val board = """{"columns":[{"name":"Open","tasks":[{"id":"task one/two","title":"Synthetic task","status":"Open","future":true}]}],"tenants":[],"assignees":[],"latest_event_id":"synthetic","now":"2026-01-01"}""".toByteArray()
        val detail = """{"task":{"id":"task one/two","title":"Synthetic task","status":"Open","unknown":1},"comments":[],"events":[],"attachments":[],"links":[],"child_results":[],"runs":[]}""".toByteArray()
    }
}
