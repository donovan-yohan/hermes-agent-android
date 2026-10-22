package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class SessionExportTest {
    private fun message(text: String) = JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive(text)))
    private fun page(offset: Int, size: Int, id: String = "stored") = GatewaySessionMessagePage(id,
        List(size) { message("message-${offset + it}") }, 100, offset.toLong(), "oldest")

    @Test fun `all pages exported chronologically with desktop envelope`() = runTest {
        val offsets = mutableListOf<Int>()
        val bytes = collectSessionExport("stored", "Title", 0, {}) { offset ->
            offsets += offset
            page(offset, if (offset == 0) 100 else 3)
        }
        val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        assertEquals(listOf(0, 100), offsets)
        assertEquals("103", json.getValue("message_count").jsonPrimitive.content)
        assertEquals("message-102", json.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content)
        assertFalse(json.containsKey("path"))
    }
    @Test fun `changed page identity refuses partial export`() = runTest {
        assertTrue(runCatching { collectSessionExport("stored", null, 0, {}) { page(it, 100, if (it == 0) "a" else "b") } }.isFailure)
    }
    @Test fun `incorrect paging refuses a truncated success`() = runTest {
        assertTrue(runCatching { collectSessionExport("stored", null, 0, {}) { page(9, 0) } }.isFailure)
    }
    @Test fun `unbounded transcript is refused`() = runTest {
        var reads = 0
        assertTrue(runCatching { collectSessionExport("stored", null, 0, {}) { reads++; page(it, 100) } }.isFailure)
        assertTrue(reads <= 101)
    }
    @Test fun `endpoint change after fetch prevents export`() = runTest {
        var current = true
        assertTrue(runCatching { collectSessionExport("stored", null, 0, { check(current) }) { current = false; page(0, 1) } }.isFailure)
    }
    @Test fun `byte bound refuses oversized transcript`() = runTest {
        assertTrue(runCatching { collectSessionExport("stored", null, 0, {}) {
            page(0, 1).copy(messages = listOf(message("x".repeat(8 * 1024 * 1024))))
        } }.isFailure)
    }
}
