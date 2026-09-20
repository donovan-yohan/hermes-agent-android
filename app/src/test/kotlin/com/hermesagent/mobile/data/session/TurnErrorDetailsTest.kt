package com.hermesagent.mobile.data.session

import com.hermesagent.mobile.data.gateway.RegeneratePlan
import com.hermesagent.mobile.data.gateway.planRegenerate
import com.hermesagent.mobile.data.gateway.safeGatewayTerminalError
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TurnErrorDetailsTest {
    @Test fun `descriptor carries failed identity and retryability not foreground settings`() {
        val details = parseTurnErrorDetails("Provider refused request", Json.parseToJsonElement(
            """{"layer":"auth","code":"auth_permanent","retryable":false,"provider":"test-provider","model":"failed-model"}""",
        ))
        assertEquals("Authentication error", details.title)
        assertFalse(details.retryable)
        assertTrue(details.copyText("Sign in again").contains("model: failed-model"))
        assertTrue(details.copyText("Sign in again").contains("provider: test-provider"))
        assertTrue(parseTurnErrorDetails(null, Json.parseToJsonElement(
            """{"layer":"gateway","retryable":"false"}""",
        )).retryable)
        assertFalse(parseTurnErrorDetails(null, Json.parseToJsonElement(
            """{"layer":"provider","code":"api_key=sentinel-code"}""",
        )).copyText("Failed").contains("sentinel-code"))
    }

    @Test fun `legacy and malformed descriptors retain useful redacted details`() {
        for (descriptor in listOf("null", "7", "[]", "{}", """{"layer":"new-layer","retryable":false}""")) {
            val details = parseTurnErrorDetails("Runtime stopped", Json.parseToJsonElement(descriptor))
            assertNull(details.layer)
            assertTrue(details.retryable)
            assertEquals("Hermes hit a problem", details.title)
            assertEquals("Runtime stopped", details.details)
        }
    }

    @Test fun `redaction precedes truncation and covers details and copy`() {
        val raw = "password=sentinel-password api_key=sentinel-key Authorization: Bearer sentinel-bearer " +
            "https://example.invalid/api?token=sentinel-query\n" +
            "-----BEGIN PRIVATE KEY-----sentinel-private-----END PRIVATE KEY-----"
        val details = parseTurnErrorDetails(raw, null)
        for (secret in listOf("sentinel-password", "sentinel-key", "sentinel-bearer", "sentinel-query", "sentinel-private", "example.invalid")) {
            assertFalse(details.details.contains(secret))
            assertFalse(details.copyText(raw).contains(secret))
        }
        assertEquals(4096, safeTurnErrorDetails("a".repeat(9000)).length)
        assertFalse(safeTurnErrorDetails("password=" + "s".repeat(9000)).contains("sss"))
    }

    @Test fun `authorization redacts every scheme and all parameters`() {
        val credentials = listOf(
            "Authorization: Basic " + "synthetic-basic==",
            "aUtHoRiZaTiOn = bEaReR synthetic-bearer trailing-credential",
            "Proxy-Authorization: Digest username=\"synthetic-user\", response=\"synthetic-response\"",
            "Authorization: Custom synthetic-one synthetic-two",
            "\"AUTHORIZATION\": \"Basic synthetic-json==\"",
            "'authorization': 'Bearer synthetic-single'",
        )
        for (raw in credentials) {
            val safe = safeTurnErrorDetails("$raw\nUseful diagnostic")
            assertFalse(safe, safe.contains("synthetic-"))
            assertFalse(safe, safe.contains("trailing-credential"))
            assertTrue(safe, safe.contains("Useful diagnostic"))
            assertFalse(TurnErrorDetails(raw).copyText(raw).contains("synthetic-"))
        }
    }

    @Test fun `quoted secrets consume whitespace escapes and delimiters before ssh redaction`() {
        val keys = listOf("password", "PaSsWoRd", "secret", "CLIENT_SECRET", "api-key",
            "access_token", "refresh-token", "sessionToken", "token", "X-Hermes-Session-Token")
        val values = listOf(
            "\"synthetic-first synthetic-last\"",
            "'synthetic-first synthetic-last'",
            "\"synthetic-first \\\" synthetic-last\"",
            "'synthetic-first \\' synthetic-last'",
            "\"synthetic-first \\\\ synthetic-last\"",
            "\"synthetic-first,;=& synthetic-last\"",
            "\"synthetic-first\nsynthetic-last\"",
        )
        for (key in keys) for (value in values) {
            val raw = "\"$key\": $value\nUseful diagnostic"
            val safe = safeTurnErrorDetails(raw)
            assertFalse("$key: $safe", safe.contains("synthetic-"))
            assertTrue(safe, safe.contains("Useful diagnostic"))
            assertFalse(TurnErrorDetails(raw).copyText(raw).contains("synthetic-"))
        }
        assertEquals("token=<redacted>&next=ok", safeTurnErrorDetails("token=synthetic-token&next=ok"))
        assertFalse(safeTurnErrorDetails("bEaReR synthetic-token").contains("synthetic-token"))
        assertEquals("<redacted>", safeTurnErrorDetails("SK-synthetic-token"))
    }

    @Test fun `long and unterminated quoted credentials fail closed before all bounds`() {
        val secret = "synthetic-head " + "x".repeat(20_000) + " synthetic-tail"
        val cases = listOf(
            "password=\"$secret\"" to "password=<redacted>",
            "secret='$secret" to "secret=<redacted>",
            "Authorization: Basic $secret" to "Authorization: <redacted>",
        )
        for ((raw, expected) in cases) {
            assertEquals(expected, safeTurnErrorDetails(raw))
            val descriptor = kotlinx.serialization.json.buildJsonObject {
                put("layer", kotlinx.serialization.json.JsonPrimitive("provider"))
                for (key in listOf("code", "provider", "model")) {
                    put(key, kotlinx.serialization.json.JsonPrimitive(raw))
                }
            }
            val details = parseTurnErrorDetails(raw, descriptor)
            assertFalse(details.copyText(raw).contains("synthetic-"))
            assertFalse(details.copyText(raw).contains("xxx"))
        }
        assertEquals("password=<redacted>", safeTurnErrorDetails("password=\"" + "\\\"".repeat(20_000)))
    }

    @Test fun `internal start explanation is actionable without claiming mobile diagnostics`() {
        val summary = safeGatewayTerminalError("internal_start: dispatcher failed")
        assertTrue(summary.startsWith("Hermes hit an internal problem starting this reply."))
        assertTrue(summary.contains("use Desktop to send diagnostics"))
        assertFalse(summary.contains("dispatcher"))
    }

    @Test fun `retry plan preserves attachment directives in original prompt including attachment only`() {
        val prompt = "@image:/synthetic/staged/image.png\n@file:/synthetic/staged/notes.txt"
        val plan = planRegenerate(listOf(
            UserTurn("user", prompt, 1, TranscriptRowId(10)),
            AssistantTurn("failed", "", 2, error = "Could not finish"),
        ), "failed") as RegeneratePlan.Ready
        assertEquals(prompt, plan.sourceText)
        assertEquals(TranscriptRowId(10), plan.sourceRowId)
    }
}
